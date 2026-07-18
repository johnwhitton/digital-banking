package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.github.johnwhitton.digitalbanking.application.AccountingEvidenceConflictException;
import io.github.johnwhitton.digitalbanking.application.command.AccountingCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.command.BankCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.application.port.ReserveAccountingPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Testcontainers(disabledWithoutDocker = true)
class PostgresReserveAccountingAdapterTest {

    private static final Instant TIME = Instant.parse("2026-07-18T12:00:00Z");
    private static final ParticipantScope USER_1 =
            new ParticipantScope("local-demo", "USER_1");
    private static final ParticipantScope USER_2 =
            new ParticipantScope("local-demo", "USER_2");
    private static final SyntheticBankAccount.BankId BANK_1 =
            new SyntheticBankAccount.BankId("BANK_1");
    private static final SyntheticBankAccount.BankId BANK_2 =
            new SyntheticBankAccount.BankId("BANK_2");
    private static final SyntheticBankAccount.AccountId ACCOUNT_1 =
            new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT");
    private static final SyntheticBankAccount.AccountId ACCOUNT_2 =
            new SyntheticBankAccount.AccountId("USER_2_BANK_ACCOUNT");
    private static final UsdCents AMOUNT = cents(10_000);
    private static final String CONTRACT =
            "0x0000000000000000000000000000000000000001";
    private static final ReserveAccountingPort.EvidencePolicy POLICY =
            new ReserveAccountingPort.EvidencePolicy(
                    new ReserveAccounting.PolicyVersion("accounting-v1"),
                    "bank-policy-v1", "mint-evidence-v1",
                    "custody-evidence-v1", "burn-evidence-v1", "USD_STABLE",
                    "LOCAL_ANVIL", CONTRACT, Duration.ofDays(1));

    @Container
    private static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:17.10-alpine3.23")
                    .withDatabaseName("digital_banking")
                    .withUsername("digital_banking")
                    .withPassword("digital_banking");

    private static PGSimpleDataSource dataSource;
    private JdbcClient jdbc;
    private PostgresMockBankAdapter bank;
    private PostgresReserveAccountingAdapter accounting;

    @BeforeAll
    static void migrate() {
        dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).load().migrate();
        createChainAuthorityTables();
    }

    private static void createChainAuthorityTables() {
        JdbcClient schema = JdbcClient.create(dataSource);
        for (String statement : List.of(
                """
                CREATE TABLE ethereum_mint_attempt (
                    operation_id UUID NOT NULL,
                    operation_attempt_id UUID NOT NULL,
                    network VARCHAR(32) NOT NULL,
                    contract_address VARCHAR(42) NOT NULL,
                    attempt_status VARCHAR(32) NOT NULL,
                    PRIMARY KEY (operation_id, operation_attempt_id))
                """,
                """
                CREATE TABLE ethereum_mint_observation (
                    operation_id UUID NOT NULL, operation_attempt_id UUID NOT NULL,
                    observation_sequence INTEGER NOT NULL,
                    observation_status VARCHAR(32) NOT NULL,
                    receipt_success BOOLEAN, observed_contract_address VARCHAR(42),
                    mint_atomic_amount NUMERIC(512, 0),
                    observed_confirmations NUMERIC(78, 0),
                    required_confirmations INTEGER NOT NULL,
                    finality_policy_version VARCHAR(128) NOT NULL,
                    observed_at TIMESTAMPTZ NOT NULL, evidence_ref VARCHAR(256) NOT NULL,
                    PRIMARY KEY (operation_id, operation_attempt_id,
                        observation_sequence))
                """,
                """
                CREATE TABLE wallet_transfer_operation (
                    operation_id UUID PRIMARY KEY, tenant_id VARCHAR(64) NOT NULL,
                    participant_id VARCHAR(64) NOT NULL,
                    transfer_purpose VARCHAR(32) NOT NULL,
                    operation_status VARCHAR(32) NOT NULL)
                """,
                """
                CREATE TABLE wallet_transfer_finality (
                    operation_id UUID NOT NULL, finality_type VARCHAR(32) NOT NULL,
                    history_order INTEGER NOT NULL, finality_status VARCHAR(16) NOT NULL,
                    PRIMARY KEY (operation_id, finality_type, history_order))
                """,
                """
                CREATE TABLE ethereum_wallet_transfer_attempt (
                    operation_id UUID NOT NULL, attempt_id UUID NOT NULL,
                    contract_address VARCHAR(42) NOT NULL,
                    source_address VARCHAR(42) NOT NULL,
                    destination_address VARCHAR(42) NOT NULL,
                    asset_id VARCHAR(64) NOT NULL,
                    quantity_atomic NUMERIC(512, 0) NOT NULL,
                    attempt_status VARCHAR(32) NOT NULL,
                    PRIMARY KEY (operation_id, attempt_id))
                """,
                """
                CREATE TABLE ethereum_wallet_transfer_observation (
                    operation_id UUID NOT NULL, attempt_id UUID NOT NULL,
                    observation_sequence INTEGER NOT NULL,
                    observation_status VARCHAR(32) NOT NULL,
                    receipt_success BOOLEAN, observed_contract_address VARCHAR(42),
                    event_source_address VARCHAR(42),
                    event_destination_address VARCHAR(42),
                    event_atomic_amount NUMERIC(512, 0),
                    observed_confirmations NUMERIC(78, 0),
                    required_confirmations INTEGER NOT NULL,
                    finality_policy_version VARCHAR(128) NOT NULL,
                    observed_at TIMESTAMPTZ NOT NULL, evidence_ref VARCHAR(256) NOT NULL,
                    PRIMARY KEY (operation_id, attempt_id, observation_sequence))
                """,
                """
                CREATE TABLE ethereum_burn_attempt (
                    operation_id UUID NOT NULL, operation_attempt_id UUID NOT NULL,
                    contract_address VARCHAR(42) NOT NULL,
                    admin_address VARCHAR(42) NOT NULL,
                    asset_id VARCHAR(64) NOT NULL,
                    quantity_atomic NUMERIC(512, 0) NOT NULL,
                    attempt_status VARCHAR(32) NOT NULL,
                    PRIMARY KEY (operation_id, operation_attempt_id))
                """,
                """
                CREATE TABLE ethereum_burn_observation (
                    operation_id UUID NOT NULL, operation_attempt_id UUID NOT NULL,
                    observation_sequence INTEGER NOT NULL,
                    observation_status VARCHAR(32) NOT NULL,
                    receipt_success BOOLEAN, observed_contract_address VARCHAR(42),
                    event_source_address VARCHAR(42),
                    event_destination_address VARCHAR(42),
                    event_atomic_amount NUMERIC(512, 0),
                    observed_confirmations NUMERIC(78, 0),
                    required_confirmations INTEGER NOT NULL,
                    finality_policy_version VARCHAR(128) NOT NULL,
                    observed_at TIMESTAMPTZ NOT NULL, evidence_ref VARCHAR(256) NOT NULL,
                    PRIMARY KEY (operation_id, operation_attempt_id,
                        observation_sequence))
                """)) {
            schema.sql(statement).update();
        }
    }

    @BeforeEach
    void reset() {
        jdbc = JdbcClient.create(dataSource);
        jdbc.sql("""
                TRUNCATE synthetic_bank, token_operation,
                    wallet_transfer_operation, accounting_reconciliation_run,
                    accounting_confirmed_evidence, accounting_journal_entry,
                    accounting_evidence_consumption, accounting_reversal_binding,
                    ethereum_mint_attempt,
                    ethereum_mint_observation, wallet_transfer_finality,
                    ethereum_wallet_transfer_attempt,
                    ethereum_wallet_transfer_observation,
                    ethereum_burn_attempt, ethereum_burn_observation
                RESTART IDENTITY CASCADE
                """).update();
        jdbc.sql("""
                UPDATE accounting_ledger_account
                SET balance_cents = 0, account_version = 0,
                    updated_at = TIMESTAMPTZ '1970-01-01 00:00:00+00'
                """).update();
        jdbc.sql("""
                UPDATE accounting_operational_position
                SET quantity_cents = 0, position_version = 0,
                    updated_at = TIMESTAMPTZ '1970-01-01 00:00:00+00'
                """).update();
        bank = new PostgresMockBankAdapter(dataSource);
        bank.initialize(fixture());
        accounting = new PostgresReserveAccountingAdapter(dataSource);
    }

    @Test
    void completeLifecyclePostsClosedJournalRulesAndReconciles() {
        BankOperation withdrawal = bankOperation(
                BankOperation.Kind.WITHDRAWAL, USER_1, BANK_1, ACCOUNT_1, 1);
        post(bankEvidence(withdrawal), ReserveAccounting.PostingType.RESERVE_FUNDING, 1);

        seedChainEvidence("mint-1", "MINT", 10_000, 10_000,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("mint-1"), ReserveAccounting.PostingType.MINT_CONFIRMED, 2);

        seedChainEvidence("custody-1", "REDEMPTION_CUSTODY", 10_000, 10_000,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("custody-1"),
                ReserveAccounting.PostingType.REDEMPTION_CUSTODY_CONFIRMED, 3);

        ReserveAccounting.Snapshot custody = accounting.snapshot();
        assertEquals(AMOUNT,
                custody.positions().adminRedemptionCustodyPendingBurn());
        assertEquals(AMOUNT, custody.balances().get(
                ReserveAccounting.LedgerAccount.REDEMPTION_PAYABLE_LIABILITY));

        BankOperation deposit = bankOperation(
                BankOperation.Kind.DEPOSIT, USER_2, BANK_2, ACCOUNT_2, 4);
        post(bankEvidence(deposit),
                ReserveAccounting.PostingType.BANK_PAYOUT_CONFIRMED, 4);
        assertEquals(AMOUNT,
                accounting.snapshot().positions().adminRedemptionCustodyPendingBurn());

        seedChainEvidence("burn-1", "BURN", 10_000, 0,
                "CONFIRMED", true, false, true, TIME);
        ReserveAccountingPort.AccountingResult burn = post(
                evidence("burn-1"), ReserveAccounting.PostingType.BURN_CONFIRMED, 5);
        assertNull(burn.journalId());
        assertEquals(8, jdbc.sql("SELECT count(*) FROM accounting_journal_line")
                .query(Integer.class).single());

        ReserveAccounting.Reconciliation reconciliation =
                accounting.reconcile(reconcileCommand(6));
        assertEquals(ReserveAccounting.ReconciliationStatus.RECONCILED,
                reconciliation.status());
        assertEquals(0, accounting.snapshot().positions()
                .confirmedChainTotalSupply().value().intValueExact());
    }

    @Test
    void payoutAndBurnEitherOrderPreserveExplicitPendingStates() {
        BankOperation withdrawal = bankOperation(
                BankOperation.Kind.WITHDRAWAL, USER_1, BANK_1, ACCOUNT_1, 10);
        post(bankEvidence(withdrawal), ReserveAccounting.PostingType.RESERVE_FUNDING, 10);
        seedChainEvidence("mint-10", "MINT", 10_000, 10_000,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("mint-10"), ReserveAccounting.PostingType.MINT_CONFIRMED, 11);
        seedChainEvidence("custody-10", "REDEMPTION_CUSTODY", 10_000, 10_000,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("custody-10"),
                ReserveAccounting.PostingType.REDEMPTION_CUSTODY_CONFIRMED, 12);
        seedChainEvidence("burn-10", "BURN", 10_000, 0,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("burn-10"), ReserveAccounting.PostingType.BURN_CONFIRMED, 13);

        ReserveAccounting.Snapshot burnedBeforePayout = accounting.snapshot();
        assertEquals(UsdCents.ZERO,
                burnedBeforePayout.positions().adminRedemptionCustodyPendingBurn());
        assertEquals(AMOUNT, burnedBeforePayout.balances().get(
                ReserveAccounting.LedgerAccount.REDEMPTION_PAYABLE_LIABILITY));

        BankOperation deposit = bankOperation(
                BankOperation.Kind.DEPOSIT, USER_2, BANK_2, ACCOUNT_2, 14);
        post(bankEvidence(deposit),
                ReserveAccounting.PostingType.BANK_PAYOUT_CONFIRMED, 14);
        assertEquals(ReserveAccounting.ReconciliationStatus.RECONCILED,
                accounting.reconcile(reconcileCommand(15)).status());
    }

    @Test
    void consumesEvidenceOnceWithExactReplayConflictAndConcurrencySafety()
            throws Exception {
        BankOperation withdrawal = bankOperation(
                BankOperation.Kind.WITHDRAWAL, USER_1, BANK_1, ACCOUNT_1, 20);
        ReserveAccounting.EvidenceIdentity evidence = bankEvidence(withdrawal);
        ReserveAccountingPort.AccountingResult first = post(
                evidence, ReserveAccounting.PostingType.RESERVE_FUNDING, 20);
        ReserveAccountingPort.AccountingResult replay = post(
                evidence, ReserveAccounting.PostingType.RESERVE_FUNDING, 21);
        assertEquals(first.journalId(), replay.journalId());
        assertTrue(replay.replayed());
        assertThrows(AccountingEvidenceConflictException.class, () -> post(
                evidence, ReserveAccounting.PostingType.BANK_PAYOUT_CONFIRMED, 22));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM accounting_journal_entry")
                .query(Integer.class).single());

        bankOperation(BankOperation.Kind.DEPOSIT, USER_1, BANK_1, ACCOUNT_1, 123);
        BankOperation secondWithdrawal = bankOperation(
                BankOperation.Kind.WITHDRAWAL, USER_1, BANK_1, ACCOUNT_1, 23);
        ReserveAccounting.EvidenceIdentity concurrentEvidence =
                bankEvidence(secondWithdrawal);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<ReserveAccountingPort.AccountingResult> one = pool.submit(() -> {
                start.await();
                return post(concurrentEvidence,
                        ReserveAccounting.PostingType.RESERVE_FUNDING, 24);
            });
            Future<ReserveAccountingPort.AccountingResult> two = pool.submit(() -> {
                start.await();
                return post(concurrentEvidence,
                        ReserveAccounting.PostingType.RESERVE_FUNDING, 25);
            });
            start.countDown();
            assertNotNull(one.get().journalId());
            assertNotNull(two.get().journalId());
        }
        assertEquals(2, jdbc.sql("SELECT count(*) FROM accounting_journal_entry")
                .query(Integer.class).single());

        seedChainEvidence("mint-posting-race", "MINT", 10_000, 10_000,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("mint-posting-race"),
                ReserveAccounting.PostingType.MINT_CONFIRMED, 26);
        seedChainEvidence("custody-posting-race", "REDEMPTION_CUSTODY",
                10_000, 10_000, "CONFIRMED", true, false, true, TIME);
        post(evidence("custody-posting-race"),
                ReserveAccounting.PostingType.REDEMPTION_CUSTODY_CONFIRMED, 27);
        BankOperation firstDeposit = bankOperation(
                BankOperation.Kind.DEPOSIT, USER_2, BANK_2, ACCOUNT_2, 28);
        BankOperation secondDeposit = bankOperation(
                BankOperation.Kind.DEPOSIT, USER_2, BANK_2, ACCOUNT_2, 29);
        CountDownLatch postingStart = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<Boolean> one = pool.submit(() -> tryPost(
                    postingStart, bankEvidence(firstDeposit), 28));
            Future<Boolean> two = pool.submit(() -> tryPost(
                    postingStart, bankEvidence(secondDeposit), 29));
            postingStart.countDown();
            assertEquals(1, (one.get() ? 1 : 0) + (two.get() ? 1 : 0));
        }
        assertEquals(1, jdbc.sql("""
                        SELECT count(*) FROM accounting_evidence_consumption
                        WHERE evidence_id IN (:one, :two)
                        """)
                .param("one", bankEvidence(firstDeposit).value())
                .param("two", bankEvidence(secondDeposit).value())
                .query(Integer.class).single());
    }

    @Test
    void rejectsMissingMismatchedStaleNonfinalRemovedAndNoncanonicalEvidence() {
        assertThrows(IllegalStateException.class, () -> post(
                evidence("missing"), ReserveAccounting.PostingType.MINT_CONFIRMED, 30));
        seedChainEvidence("wrong-type", "BURN", 100, 0,
                "CONFIRMED", true, false, true, TIME);
        assertThrows(IllegalStateException.class, () -> post(
                evidence("wrong-type"), ReserveAccounting.PostingType.MINT_CONFIRMED, 31));
        seedChainEvidence("stale", "MINT", 100, 100,
                "STALE", true, false, true, TIME.minus(Duration.ofDays(2)));
        seedChainEvidence("non-final", "MINT", 100, 100,
                "CONFIRMED", true, false, false, TIME);
        seedChainEvidence("removed", "MINT", 100, 100,
                "CONFIRMED", true, true, true, TIME);
        seedChainEvidence("non-canonical", "MINT", 100, 100,
                "CONFIRMED", false, false, true, TIME);
        seedChainEvidence("supply-mismatch", "MINT", 100, 99,
                "CONFIRMED", true, false, true, TIME);
        seedChainEvidence("missing-native", "MINT", 100, 100,
                "CONFIRMED", true, false, true, TIME);
        for (String id : List.of(
                "stale", "non-final", "removed", "non-canonical",
                "supply-mismatch", "missing-native")) {
            assertThrows(IllegalStateException.class, () -> post(
                    evidence(id), ReserveAccounting.PostingType.MINT_CONFIRMED, 32));
        }
        assertEquals(0, jdbc.sql("SELECT count(*) FROM accounting_evidence_consumption")
                .query(Integer.class).single());
    }

    @Test
    void insufficientPositionsRollbackAndReconciliationRecordsMismatchClasses() {
        seedChainEvidence("mint-no-funds", "MINT", 100, 100,
                "CONFIRMED", true, false, true, TIME);
        assertThrows(IllegalArgumentException.class, () -> post(
                evidence("mint-no-funds"),
                ReserveAccounting.PostingType.MINT_CONFIRMED, 40));
        assertEquals(0, jdbc.sql("SELECT count(*) FROM accounting_evidence_consumption")
                .query(Integer.class).single());

        assertEquals(ReserveAccounting.ReconciliationStatus.EVIDENCE_INCOMPLETE,
                accounting.reconcile(reconcileCommand(41)).status());

        BankOperation withdrawal = bankOperation(
                BankOperation.Kind.WITHDRAWAL, USER_1, BANK_1, ACCOUNT_1, 41);
        post(bankEvidence(withdrawal), ReserveAccounting.PostingType.RESERVE_FUNDING, 41);

        jdbc.sql("""
                UPDATE accounting_ledger_account SET balance_cents = balance_cents + 100
                WHERE account_type = 'RESERVE_CASH_ASSET'
                """).update();
        assertEquals(ReserveAccounting.ReconciliationStatus.RESERVE_LEDGER_MISMATCH,
                accounting.reconcile(reconcileCommand(42)).status());

        jdbc.sql("""
                UPDATE accounting_ledger_account SET balance_cents = balance_cents - 100
                WHERE account_type = 'RESERVE_CASH_ASSET'
                """).update();
        seedChainEvidence("mint-stales-later", "MINT", 10_000, 10_000,
                "CONFIRMED", true, false, true, TIME);
        post(evidence("mint-stales-later"),
                ReserveAccounting.PostingType.MINT_CONFIRMED, 43);
        assertEquals(
                ReserveAccounting.ReconciliationStatus.UNSUPPORTED_OR_STALE_OBSERVATION,
                accounting.reconcile(reconcileCommandAt(
                        43, TIME.plus(Duration.ofDays(2)))).status());
        assertEquals(3, jdbc.sql("SELECT count(*) FROM accounting_reconciliation_run")
                .query(Integer.class).single());

        PostgresReserveAccountingAdapter restarted =
                new PostgresReserveAccountingAdapter(dataSource);
        assertEquals(accounting.snapshot(), restarted.snapshot());
    }

    @Test
    void databaseRejectsUnbalancedAndHistoryMutation() {
        assertThrows(RuntimeException.class, () -> jdbc.sql("""
                INSERT INTO accounting_journal_entry (
                    journal_entry_id, posting_type, accounting_policy_version,
                    effective_at, recorded_at, correlation_id, evidence_id,
                    amount_cents, entry_status)
                VALUES (:id, 'RESERVE_FUNDING', 'accounting-v1', :time, :time,
                    'correlation-db', 'evidence-db', 100, 'POSTED')
                """).param("id", new UUID(9, 1))
                .param("time", TIME.atOffset(ZoneOffset.UTC)).update());

        BankOperation withdrawal = bankOperation(
                BankOperation.Kind.WITHDRAWAL, USER_1, BANK_1, ACCOUNT_1, 50);
        ReserveAccountingPort.AccountingResult original = post(
                bankEvidence(withdrawal), ReserveAccounting.PostingType.RESERVE_FUNDING, 50);
        assertThrows(RuntimeException.class, () -> jdbc.sql(
                "UPDATE accounting_journal_entry SET amount_cents = 1").update());

        ReserveAccounting.EvidenceIdentity correction = evidence("correction-50");
        ReserveAccountingPort.ReverseCommand reversal = reverseCommand(
                original.journalId(), correction, 51);
        ReserveAccountingPort.AccountingResult reversed = accounting.reverse(reversal);
        assertEquals(ReserveAccounting.PostingType.REVERSAL, reversed.postingType());
        assertEquals(UsdCents.ZERO, accounting.snapshot().balances().get(
                ReserveAccounting.LedgerAccount.RESERVE_CASH_ASSET));
        assertEquals(reversed.journalId(), accounting.reverse(reversal).journalId());
        assertTrue(accounting.reverse(reversal).replayed());
        assertThrows(AccountingEvidenceConflictException.class,
                () -> accounting.reverse(reverseCommand(
                        original.journalId(), evidence("other-correction"), 52)));
        assertEquals(1, jdbc.sql("SELECT count(*) FROM accounting_reversal_binding")
                .query(Integer.class).single());
        assertEquals(2, jdbc.sql("SELECT count(*) FROM accounting_journal_entry")
                .query(Integer.class).single());
        assertThrows(RuntimeException.class, () -> jdbc.sql(
                "DELETE FROM accounting_reversal_binding").update());
    }

    private ReserveAccountingPort.AccountingResult post(
            ReserveAccounting.EvidenceIdentity evidence,
            ReserveAccounting.PostingType type,
            long id) {
        String digest = AccountingCommandCanonicalizer.digest(evidence, type, POLICY);
        return accounting.post(new ReserveAccountingPort.PostCommand(
                evidence, type, journal(id), line(id * 10), line(id * 10 + 1),
                POLICY, digest, TIME.plusSeconds(id)));
    }

    private boolean tryPost(
            CountDownLatch start,
            ReserveAccounting.EvidenceIdentity evidence,
            long id) throws InterruptedException {
        start.await();
        try {
            post(evidence, ReserveAccounting.PostingType.BANK_PAYOUT_CONFIRMED, id);
            return true;
        } catch (IllegalArgumentException insufficientPosition) {
            return false;
        }
    }

    private BankOperation bankOperation(
            BankOperation.Kind kind,
            ParticipantScope participant,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            long id) {
        BankOperation.Id operationId = new BankOperation.Id(new UUID(5, id));
        BankOperation.EvidenceId evidenceId = new BankOperation.EvidenceId(new UUID(6, id));
        var canonical = BankCommandCanonicalizer.encode(
                participant, bankId, accountId, kind, AMOUNT);
        MockBankPort.BankResponse response = bank.execute(new MockBankPort.BankCommand(
                operationId, evidenceId, bankId, accountId, participant, AMOUNT, kind,
                "%064x".formatted(id), canonical.sha256(),
                new BankOperation.CorrelationId("bank-correlation-" + id),
                new BankOperation.PolicyVersion("bank-policy-v1"),
                TIME.plusSeconds(id)));
        assertEquals(MockBankPort.ResponseStatus.SUCCEEDED, response.status());
        return bank.inquire(operationId, participant).orElseThrow();
    }

    private void seedChainEvidence(
            String id, String type, int amount, int supply, String status,
            boolean canonical, boolean removed, boolean finality, Instant observedAt) {
        UUID operationId = UUID.nameUUIDFromBytes((id + "-op").getBytes());
        UUID attemptId = UUID.nameUUIDFromBytes((id + "-attempt").getBytes());
        jdbc.sql("""
                INSERT INTO accounting_confirmed_evidence (
                    evidence_id, evidence_type, operation_id, attempt_id,
                    observation_sequence, observed_supply_cents, recorded_at)
                VALUES (:id, :type, :operationId, :attemptId, 1, :supply, :recordedAt)
                """)
                .param("id", id).param("type", type)
                .param("operationId", operationId)
                .param("attemptId", attemptId)
                .param("supply", BigDecimal.valueOf(supply))
                .param("recordedAt", TIME.atOffset(ZoneOffset.UTC)).update();
        if (id.equals("missing-native")) {
            return;
        }
        String observationStatus = status.equals("STALE") ? "CONFIRMED" : status;
        String attemptStatus = canonical ? "CONFIRMED" : "MISMATCHED";
        if (type.equals("REDEMPTION_CUSTODY")) {
            seedCustodyAuthority(id, operationId, attemptId, amount,
                    observationStatus, attemptStatus, finality, observedAt);
        } else {
            seedTokenOperation(operationId, type, amount);
            seedOperationFinality(operationId, finality);
            if (type.equals("MINT")) {
                seedMintAuthority(id, operationId, attemptId, amount,
                        observationStatus, attemptStatus, observedAt);
            } else {
                seedBurnAuthority(id, operationId, attemptId, amount,
                        observationStatus, attemptStatus, observedAt);
            }
        }
        if (removed) {
            seedLaterOrphan(type, id, operationId, attemptId, observedAt.plusSeconds(1));
        }
    }

    private void seedTokenOperation(UUID operationId, String type, int amount) {
        jdbc.sql("""
                INSERT INTO token_operation (
                    operation_id, tenant_id, participant_id, idempotency_resource,
                    operation_kind, idempotency_key_digest,
                    request_contract_version, canonicalization_version,
                    command_digest, business_correlation, asset_id, unit_id,
                    unit_version, unit_scale, unit_max_atomic, quantity_atomic,
                    lifecycle_state, aggregate_version, acceptance_evidence_ref,
                    created_at, updated_at)
                VALUES (:operationId, 'local-demo', 'USER_1', 'TOKEN_OPERATION',
                    :kind, :digest, 1, 1, :digest, :correlation,
                    'USD_STABLE', 'USD_CENTS', 1, 2, 999999999999999999,
                    :amount, 'COMPLETED', 1, :acceptance, :time, :time)
                """)
                .param("operationId", operationId)
                .param("kind", type)
                .param("digest", "%064x".formatted(operationId.getLeastSignificantBits()
                        & Long.MAX_VALUE))
                .param("correlation", operationId.toString())
                .param("amount", BigDecimal.valueOf(amount))
                .param("acceptance", "acceptance-" + operationId)
                .param("time", TIME.atOffset(ZoneOffset.UTC)).update();
    }

    private void seedOperationFinality(UUID operationId, boolean reached) {
        jdbc.sql("""
                INSERT INTO operation_finality (
                    operation_id, finality_type, history_order, aggregate_version,
                    finality_status, authority, policy_version, updated_at)
                VALUES (:operationId, 'BLOCKCHAIN', 1, 1, :status,
                    'local-anvil-observer', :policy, :time)
                """)
                .param("operationId", operationId)
                .param("status", reached ? "REACHED" : "PENDING")
                .param("policy", "finality-policy-v1")
                .param("time", TIME.atOffset(ZoneOffset.UTC)).update();
    }

    private void seedMintAuthority(
            String id, UUID operationId, UUID attemptId, int amount,
            String observationStatus, String attemptStatus, Instant observedAt) {
        jdbc.sql("""
                INSERT INTO ethereum_mint_attempt (
                    operation_id, operation_attempt_id, network,
                    contract_address, attempt_status)
                VALUES (:operationId, :attemptId, 'LOCAL_ANVIL',
                    :contract, :attemptStatus)
                """).param("operationId", operationId).param("attemptId", attemptId)
                .param("contract", CONTRACT).param("attemptStatus", attemptStatus)
                .update();
        jdbc.sql("""
                INSERT INTO ethereum_mint_observation (
                    operation_id, operation_attempt_id, observation_sequence,
                    observation_status, receipt_success, observed_contract_address,
                    mint_atomic_amount, observed_confirmations, required_confirmations,
                    finality_policy_version, observed_at, evidence_ref)
                VALUES (:operationId, :attemptId, 1, :status, true, :contract,
                    :amount, 1, 1, 'mint-evidence-v1', :observedAt, :evidenceId)
                """).param("operationId", operationId).param("attemptId", attemptId)
                .param("status", observationStatus).param("contract", CONTRACT)
                .param("amount", BigDecimal.valueOf(amount))
                .param("observedAt", observedAt.atOffset(ZoneOffset.UTC))
                .param("evidenceId", id).update();
    }

    private void seedCustodyAuthority(
            String id, UUID operationId, UUID attemptId, int amount,
            String observationStatus, String attemptStatus,
            boolean finality, Instant observedAt) {
        String source = "0x0000000000000000000000000000000000000002";
        String destination = "0x0000000000000000000000000000000000000003";
        jdbc.sql("""
                INSERT INTO wallet_transfer_operation (
                    operation_id, tenant_id, participant_id,
                    transfer_purpose, operation_status)
                VALUES (:operationId, 'local-demo', 'USER_1',
                    'REDEMPTION_CUSTODY', 'COMPLETED')
                """).param("operationId", operationId).update();
        jdbc.sql("""
                INSERT INTO wallet_transfer_finality (
                    operation_id, finality_type, history_order, finality_status)
                VALUES (:operationId, 'BLOCKCHAIN', 1, :status)
                """).param("operationId", operationId)
                .param("status", finality ? "REACHED" : "PENDING").update();
        jdbc.sql("""
                INSERT INTO ethereum_wallet_transfer_attempt (
                    operation_id, attempt_id, contract_address,
                    source_address, destination_address, asset_id,
                    quantity_atomic, attempt_status)
                VALUES (:operationId, :attemptId, :contract,
                    :source, :destination, 'USD_STABLE', :amount, :attemptStatus)
                """).param("operationId", operationId).param("attemptId", attemptId)
                .param("contract", CONTRACT).param("source", source)
                .param("destination", destination)
                .param("amount", BigDecimal.valueOf(amount))
                .param("attemptStatus", attemptStatus).update();
        jdbc.sql("""
                INSERT INTO ethereum_wallet_transfer_observation (
                    operation_id, attempt_id, observation_sequence,
                    observation_status, receipt_success, observed_contract_address,
                    event_source_address, event_destination_address,
                    event_atomic_amount, observed_confirmations, required_confirmations,
                    finality_policy_version, observed_at, evidence_ref)
                VALUES (:operationId, :attemptId, 1, :status, true, :contract,
                    :source, :destination, :amount, 1, 1,
                    'custody-evidence-v1', :observedAt, :evidenceId)
                """).param("operationId", operationId).param("attemptId", attemptId)
                .param("status", observationStatus).param("contract", CONTRACT)
                .param("source", source).param("destination", destination)
                .param("amount", BigDecimal.valueOf(amount))
                .param("observedAt", observedAt.atOffset(ZoneOffset.UTC))
                .param("evidenceId", id).update();
    }

    private void seedBurnAuthority(
            String id, UUID operationId, UUID attemptId, int amount,
            String observationStatus, String attemptStatus, Instant observedAt) {
        String admin = "0x0000000000000000000000000000000000000003";
        jdbc.sql("""
                INSERT INTO ethereum_burn_attempt (
                    operation_id, operation_attempt_id, contract_address,
                    admin_address, asset_id, quantity_atomic, attempt_status)
                VALUES (:operationId, :attemptId, :contract,
                    :admin, 'USD_STABLE', :amount, :attemptStatus)
                """).param("operationId", operationId).param("attemptId", attemptId)
                .param("contract", CONTRACT).param("admin", admin)
                .param("amount", BigDecimal.valueOf(amount))
                .param("attemptStatus", attemptStatus).update();
        jdbc.sql("""
                INSERT INTO ethereum_burn_observation (
                    operation_id, operation_attempt_id, observation_sequence,
                    observation_status, receipt_success, observed_contract_address,
                    event_source_address, event_destination_address,
                    event_atomic_amount, observed_confirmations, required_confirmations,
                    finality_policy_version, observed_at, evidence_ref)
                VALUES (:operationId, :attemptId, 1, :status, true, :contract,
                    :admin, '0x0000000000000000000000000000000000000000',
                    :amount, 1, 1, 'burn-evidence-v1', :observedAt, :evidenceId)
                """).param("operationId", operationId).param("attemptId", attemptId)
                .param("status", observationStatus).param("contract", CONTRACT)
                .param("admin", admin).param("amount", BigDecimal.valueOf(amount))
                .param("observedAt", observedAt.atOffset(ZoneOffset.UTC))
                .param("evidenceId", id).update();
    }

    private void seedLaterOrphan(
            String type, String id, UUID operationId, UUID attemptId, Instant observedAt) {
        String table = switch (type) {
            case "MINT" -> "ethereum_mint_observation";
            case "REDEMPTION_CUSTODY" -> "ethereum_wallet_transfer_observation";
            case "BURN" -> "ethereum_burn_observation";
            default -> throw new IllegalArgumentException("unsupported chain evidence type");
        };
        String attemptColumn = type.equals("REDEMPTION_CUSTODY")
                ? "attempt_id" : "operation_attempt_id";
        jdbc.sql("""
                INSERT INTO %s (
                    operation_id, %s, observation_sequence, observation_status,
                    required_confirmations, finality_policy_version,
                    observed_at, evidence_ref)
                VALUES (:operationId, :attemptId, 2, 'ORPHANED', 1,
                    :policy, :observedAt, :evidenceId)
                """.formatted(table, attemptColumn))
                .param("operationId", operationId).param("attemptId", attemptId)
                .param("policy", switch (type) {
                    case "MINT" -> "mint-evidence-v1";
                    case "REDEMPTION_CUSTODY" -> "custody-evidence-v1";
                    case "BURN" -> "burn-evidence-v1";
                    default -> throw new IllegalArgumentException(
                            "unsupported chain evidence type");
                })
                .param("observedAt", observedAt.atOffset(ZoneOffset.UTC))
                .param("evidenceId", id + "-orphan").update();
    }

    private static ReserveAccountingPort.ReconcileCommand reconcileCommand(long id) {
        return reconcileCommandAt(id, TIME.plusSeconds(id));
    }

    private static ReserveAccountingPort.ReconcileCommand reconcileCommandAt(
            long id, Instant recordedAt) {
        return new ReserveAccountingPort.ReconcileCommand(
                new ReserveAccounting.ReconciliationRunId(new UUID(7, id)),
                new ReserveAccounting.ReconciliationResultId(new UUID(8, id)),
                POLICY, recordedAt);
    }

    private static ReserveAccountingPort.ReverseCommand reverseCommand(
            ReserveAccounting.JournalId original,
            ReserveAccounting.EvidenceIdentity correction,
            long id) {
        String digest = AccountingCommandCanonicalizer.reversalDigest(
                original, correction, POLICY.accountingPolicyVersion());
        return new ReserveAccountingPort.ReverseCommand(
                original, correction, journal(id), line(id * 10), line(id * 10 + 1),
                POLICY.accountingPolicyVersion(), digest, TIME.plusSeconds(id));
    }

    private static MockBankPort.Fixture fixture() {
        return new MockBankPort.Fixture(
                new SyntheticBankAccount.FixtureVersion("fixture-v1"),
                List.of(new MockBankPort.BankFixture(BANK_1, true),
                        new MockBankPort.BankFixture(BANK_2, true)),
                List.of(
                        new MockBankPort.AccountFixture(
                                BANK_1, ACCOUNT_1, USER_1, AMOUNT, true),
                        new MockBankPort.AccountFixture(
                                BANK_2, ACCOUNT_2, USER_2, UsdCents.ZERO, true)),
                TIME);
    }

    private static ReserveAccounting.JournalId journal(long id) {
        return new ReserveAccounting.JournalId(new UUID(1, id));
    }

    private static ReserveAccounting.JournalLineId line(long id) {
        return new ReserveAccounting.JournalLineId(new UUID(2, id));
    }

    private static ReserveAccounting.EvidenceIdentity bankEvidence(BankOperation operation) {
        return evidence(operation.evidenceId().value().toString());
    }

    private static ReserveAccounting.EvidenceIdentity evidence(String id) {
        return new ReserveAccounting.EvidenceIdentity(id);
    }

    private static UsdCents cents(long value) {
        return new UsdCents(BigInteger.valueOf(value));
    }
}
