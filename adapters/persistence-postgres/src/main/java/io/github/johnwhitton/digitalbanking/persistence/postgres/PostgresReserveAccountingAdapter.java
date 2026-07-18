package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.AccountingEvidenceConflictException;
import io.github.johnwhitton.digitalbanking.application.port.ReserveAccountingPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.AccountingPostingEngine;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit-JDBC durable reserve ledger and evidence-consumption boundary. */
public final class PostgresReserveAccountingAdapter implements ReserveAccountingPort {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresReserveAccountingAdapter(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public AccountingResult post(PostCommand command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(transactions.execute(status -> postInTransaction(command)),
                "accounting posting result");
    }

    @Override
    public AccountingResult reverse(ReverseCommand command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(transactions.execute(status ->
                reverseInTransaction(command)), "accounting reversal result");
    }

    @Override
    public ReserveAccounting.Snapshot snapshot() {
        return snapshot(false);
    }

    @Override
    public ReserveAccounting.Reconciliation reconcile(ReconcileCommand command) {
        Objects.requireNonNull(command, "command");
        return Objects.requireNonNull(transactions.execute(status -> {
            ReserveAccounting.Snapshot current = snapshot(true);
            List<ConsumedEvidence> consumed = jdbc.sql("""
                            SELECT evidence_id, evidence_type
                            FROM accounting_evidence_consumption
                            ORDER BY consumed_at, evidence_id
                            """)
                    .query((row, rowNumber) -> new ConsumedEvidence(
                            new ReserveAccounting.EvidenceIdentity(
                                    row.getString("evidence_id")),
                            EvidenceType.valueOf(row.getString("evidence_type"))))
                    .list();
            boolean complete = !consumed.isEmpty();
            boolean supportedFresh = true;
            List<ReserveAccounting.EvidenceIdentity> durableEvidence =
                    new ArrayList<>(consumed.size());
            for (ConsumedEvidence item : consumed) {
                Optional<DurableEvidence> evidence = findEvidence(
                        item.identity(), item.type());
                if (evidence.isEmpty()) {
                    complete = false;
                    supportedFresh = false;
                    continue;
                }
                durableEvidence.add(item.identity());
                if (!isSupportedAndFresh(evidence.orElseThrow(), item.type(),
                        command.evidencePolicy(), command.recordedAt())) {
                    supportedFresh = false;
                }
            }
            ReserveAccounting.Reconciliation result = AccountingPostingEngine.reconcile(
                    command.runId(), command.resultId(), current, complete,
                    supportedFresh, command.recordedAt());
            insertReconciliation(result, command, durableEvidence);
            return result;
        }), "reconciliation result");
    }

    private AccountingResult reverseInTransaction(ReverseCommand command) {
        StoredJournal original = loadOriginalJournal(command.originalJournalId());
        if (!original.journal().policyVersion().equals(
                command.accountingPolicyVersion())) {
            throw new IllegalStateException("reversal accounting policy does not match");
        }
        int claimed = jdbc.sql("""
                        INSERT INTO accounting_reversal_binding (
                            correction_evidence_id, original_journal_id,
                            reversal_journal_id, command_digest, recorded_at)
                        VALUES (:evidenceId, :originalId, :reversalId,
                            :digest, :recordedAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("evidenceId", command.correctionEvidenceIdentity().value())
                .param("originalId", command.originalJournalId().value())
                .param("reversalId", command.reversalJournalId().value())
                .param("digest", command.commandDigest())
                .param("recordedAt", utc(command.recordedAt())).update();
        if (claimed == 0) {
            List<ExistingReversal> existing = jdbc.sql("""
                            SELECT correction_evidence_id, original_journal_id,
                                   reversal_journal_id, command_digest
                            FROM accounting_reversal_binding
                            WHERE correction_evidence_id = :evidenceId
                               OR original_journal_id = :originalId
                            """)
                    .param("evidenceId", command.correctionEvidenceIdentity().value())
                    .param("originalId", command.originalJournalId().value())
                    .query((row, rowNumber) -> new ExistingReversal(
                            row.getString("correction_evidence_id"),
                            row.getObject("original_journal_id", UUID.class),
                            row.getObject("reversal_journal_id", UUID.class),
                            row.getString("command_digest")))
                    .list();
            if (existing.size() == 1) {
                ExistingReversal replay = existing.getFirst();
                if (replay.correctionEvidenceId().equals(
                            command.correctionEvidenceIdentity().value())
                        && replay.originalJournalId().equals(
                            command.originalJournalId().value())
                        && replay.commandDigest().equals(command.commandDigest())) {
                    return new AccountingResult(
                            command.correctionEvidenceIdentity(),
                            ReserveAccounting.PostingType.REVERSAL,
                            new ReserveAccounting.JournalId(replay.reversalJournalId()),
                            true);
                }
            }
            throw new AccountingEvidenceConflictException(
                    "accounting journal was already reversed differently");
        }
        ReserveAccounting.Snapshot current = snapshot(true);
        ReserveAccounting.Posting reversal = AccountingPostingEngine.reverse(
                original.journal(), current, command.reversalJournalId(),
                command.debitLineId(), command.creditLineId(),
                command.correctionEvidenceIdentity(), command.recordedAt());
        insertJournal(reversal.journal(), original.tenantId(), original.participantId());
        updateState(reversal.snapshot(), command.recordedAt());
        return new AccountingResult(
                command.correctionEvidenceIdentity(),
                ReserveAccounting.PostingType.REVERSAL,
                reversal.journal().id(), false);
    }

    private AccountingResult postInTransaction(PostCommand command) {
        EvidenceType expectedType = expectedEvidenceType(command.postingType());
        UUID journalId = command.postingType()
                == ReserveAccounting.PostingType.BURN_CONFIRMED
                ? null : command.journalId().value();
        int claimed = jdbc.sql("""
                        INSERT INTO accounting_evidence_consumption (
                            evidence_id, evidence_type, posting_type, command_digest,
                            journal_entry_id, consumed_at)
                        VALUES (:evidenceId, :evidenceType, :postingType, :digest,
                            :journalId, :consumedAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("evidenceId", command.evidenceIdentity().value())
                .param("evidenceType", expectedType.name())
                .param("postingType", command.postingType().name())
                .param("digest", command.commandDigest())
                .param("journalId", journalId, Types.OTHER)
                .param("consumedAt", utc(command.recordedAt()))
                .update();
        if (claimed == 0) {
            ExistingConsumption existing = jdbc.sql("""
                            SELECT posting_type, command_digest, journal_entry_id
                            FROM accounting_evidence_consumption
                            WHERE evidence_id = :evidenceId
                            """)
                    .param("evidenceId", command.evidenceIdentity().value())
                    .query((row, rowNumber) -> new ExistingConsumption(
                            ReserveAccounting.PostingType.valueOf(
                                    row.getString("posting_type")),
                            row.getString("command_digest"),
                            row.getObject("journal_entry_id", UUID.class)))
                    .single();
            if (existing.postingType() != command.postingType()
                    || !existing.commandDigest().equals(command.commandDigest())) {
                throw new AccountingEvidenceConflictException(
                        "accounting evidence was already consumed differently");
            }
            return new AccountingResult(
                    command.evidenceIdentity(), existing.postingType(),
                    existing.journalId() == null ? null
                            : new ReserveAccounting.JournalId(existing.journalId()),
                    true);
        }

        DurableEvidence evidence = loadEvidence(command.evidenceIdentity(), expectedType);
        ReserveAccounting.Snapshot current = snapshot(true);
        verifyEvidence(evidence, expectedType, command, current);
        ReserveAccounting.Posting posting = AccountingPostingEngine.post(
                command.postingType(), current, evidence.amount(), command.journalId(),
                command.debitLineId(), command.creditLineId(),
                command.evidencePolicy().accountingPolicyVersion(),
                new ReserveAccounting.CorrelationId(evidence.correlationId()),
                command.evidenceIdentity(), evidence.effectiveAt(), command.recordedAt());
        if (posting.journal() != null) {
            insertJournal(posting.journal(), evidence.tenantId(), evidence.participantId());
        }
        updateState(posting.snapshot(), command.recordedAt());
        return new AccountingResult(
                command.evidenceIdentity(), command.postingType(),
                posting.journal() == null ? null : posting.journal().id(), false);
    }

    private DurableEvidence loadEvidence(
            ReserveAccounting.EvidenceIdentity identity, EvidenceType expectedType) {
        return findEvidence(identity, expectedType).orElseThrow(() ->
                new IllegalStateException(expectedType == EvidenceType.BANK_WITHDRAWAL
                        || expectedType == EvidenceType.BANK_DEPOSIT
                        ? "authoritative bank evidence is unavailable"
                        : "authoritative chain evidence is unavailable"));
    }

    private Optional<DurableEvidence> findEvidence(
            ReserveAccounting.EvidenceIdentity identity, EvidenceType expectedType) {
        if (expectedType == EvidenceType.BANK_WITHDRAWAL
                || expectedType == EvidenceType.BANK_DEPOSIT) {
            return jdbc.sql("""
                            SELECT evidence_id::text AS evidence_id,
                                   CASE operation_kind
                                       WHEN 'WITHDRAWAL' THEN 'BANK_WITHDRAWAL'
                                       ELSE 'BANK_DEPOSIT'
                                   END AS evidence_type,
                                   correlation_id, tenant_id, participant_id,
                                   operation_id, NULL::uuid AS attempt_id,
                                   NULL::uuid AS wallet_transfer_id,
                                   'USD' AS asset_id, NULL::varchar AS network,
                                   NULL::varchar AS contract_reference,
                                   amount_cents, NULL::numeric AS observed_supply_cents,
                                   operation_status AS evidence_status,
                                   true AS canonical, false AS removed,
                                   true AS finality_reached, policy_version,
                                   recorded_at AS observed_at
                            FROM synthetic_bank_operation
                            WHERE evidence_id::text = :evidenceId
                            """)
                    .param("evidenceId", identity.value())
                    .query(PostgresReserveAccountingAdapter::mapEvidence)
                    .optional();
        }
        String sql = switch (expectedType) {
            case MINT -> """
                    SELECT p.evidence_id, p.evidence_type,
                           op.operation_id::text AS correlation_id,
                           op.tenant_id, op.participant_id,
                           op.operation_id, a.operation_attempt_id AS attempt_id,
                           NULL::uuid AS wallet_transfer_id,
                           op.asset_id, a.network,
                           a.contract_address AS contract_reference,
                           op.quantity_atomic AS amount_cents,
                           p.observed_supply_cents,
                           o.observation_status AS evidence_status,
                           (a.attempt_status = 'CONFIRMED'
                               AND o.observation_status = 'CONFIRMED'
                               AND o.receipt_success IS TRUE
                               AND o.observed_contract_address = a.contract_address
                               AND o.mint_atomic_amount = op.quantity_atomic
                               AND o.observed_confirmations >= o.required_confirmations
                               AND f.finality_status = 'REACHED') AS canonical,
                           false AS removed,
                           (f.finality_status = 'REACHED') AS finality_reached,
                           o.finality_policy_version AS policy_version,
                           o.observed_at
                    FROM accounting_confirmed_evidence p
                    JOIN token_operation op ON op.operation_id = p.operation_id
                        AND op.operation_kind = 'MINT'
                    JOIN ethereum_mint_attempt a
                        ON a.operation_id = p.operation_id
                       AND a.operation_attempt_id = p.attempt_id
                    JOIN ethereum_mint_observation o
                        ON o.operation_id = p.operation_id
                       AND o.operation_attempt_id = p.attempt_id
                       AND o.observation_sequence = p.observation_sequence
                       AND o.evidence_ref = p.evidence_id
                    JOIN LATERAL (
                        SELECT finality_status
                        FROM operation_finality
                        WHERE operation_id = p.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1
                    ) f ON true
                    WHERE p.evidence_id = :evidenceId
                      AND p.evidence_type = 'MINT'
                      AND p.recorded_at >= o.observed_at
                      AND NOT EXISTS (
                          SELECT 1 FROM ethereum_mint_observation later
                          WHERE later.operation_id = p.operation_id
                            AND later.operation_attempt_id = p.attempt_id
                            AND later.observation_sequence > p.observation_sequence)
                    """;
            case REDEMPTION_CUSTODY -> """
                    SELECT p.evidence_id, p.evidence_type,
                           w.operation_id::text AS correlation_id,
                           w.tenant_id, w.participant_id,
                           NULL::uuid AS operation_id, a.attempt_id,
                           w.operation_id AS wallet_transfer_id,
                           a.asset_id, 'LOCAL_ANVIL' AS network,
                           a.contract_address AS contract_reference,
                           a.quantity_atomic AS amount_cents,
                           p.observed_supply_cents,
                           o.observation_status AS evidence_status,
                           (w.transfer_purpose = 'REDEMPTION_CUSTODY'
                               AND w.operation_status = 'COMPLETED'
                               AND a.attempt_status = 'CONFIRMED'
                               AND o.observation_status = 'CONFIRMED'
                               AND o.receipt_success IS TRUE
                               AND o.observed_contract_address = a.contract_address
                               AND o.event_source_address = a.source_address
                               AND o.event_destination_address = a.destination_address
                               AND o.event_atomic_amount = a.quantity_atomic
                               AND o.observed_confirmations >= o.required_confirmations
                               AND f.finality_status = 'REACHED') AS canonical,
                           false AS removed,
                           (f.finality_status = 'REACHED') AS finality_reached,
                           o.finality_policy_version AS policy_version,
                           o.observed_at
                    FROM accounting_confirmed_evidence p
                    JOIN wallet_transfer_operation w
                        ON w.operation_id = p.operation_id
                    JOIN ethereum_wallet_transfer_attempt a
                        ON a.operation_id = p.operation_id
                       AND a.attempt_id = p.attempt_id
                    JOIN ethereum_wallet_transfer_observation o
                        ON o.operation_id = p.operation_id
                       AND o.attempt_id = p.attempt_id
                       AND o.observation_sequence = p.observation_sequence
                       AND o.evidence_ref = p.evidence_id
                    JOIN LATERAL (
                        SELECT finality_status
                        FROM wallet_transfer_finality
                        WHERE operation_id = p.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1
                    ) f ON true
                    WHERE p.evidence_id = :evidenceId
                      AND p.evidence_type = 'REDEMPTION_CUSTODY'
                      AND p.recorded_at >= o.observed_at
                      AND NOT EXISTS (
                          SELECT 1 FROM ethereum_wallet_transfer_observation later
                          WHERE later.operation_id = p.operation_id
                            AND later.attempt_id = p.attempt_id
                            AND later.observation_sequence > p.observation_sequence)
                    """;
            case BURN -> """
                    SELECT p.evidence_id, p.evidence_type,
                           op.operation_id::text AS correlation_id,
                           op.tenant_id, op.participant_id,
                           op.operation_id, a.operation_attempt_id AS attempt_id,
                           NULL::uuid AS wallet_transfer_id,
                           a.asset_id, 'LOCAL_ANVIL' AS network,
                           a.contract_address AS contract_reference,
                           a.quantity_atomic AS amount_cents,
                           p.observed_supply_cents,
                           o.observation_status AS evidence_status,
                           (a.attempt_status = 'CONFIRMED'
                               AND o.observation_status = 'CONFIRMED'
                               AND o.receipt_success IS TRUE
                               AND o.observed_contract_address = a.contract_address
                               AND o.event_source_address = a.admin_address
                               AND o.event_destination_address =
                                   '0x0000000000000000000000000000000000000000'
                               AND o.event_atomic_amount = a.quantity_atomic
                               AND o.observed_confirmations >= o.required_confirmations
                               AND f.finality_status = 'REACHED') AS canonical,
                           false AS removed,
                           (f.finality_status = 'REACHED') AS finality_reached,
                           o.finality_policy_version AS policy_version,
                           o.observed_at
                    FROM accounting_confirmed_evidence p
                    JOIN token_operation op ON op.operation_id = p.operation_id
                        AND op.operation_kind = 'BURN'
                    JOIN ethereum_burn_attempt a
                        ON a.operation_id = p.operation_id
                       AND a.operation_attempt_id = p.attempt_id
                       AND a.quantity_atomic = op.quantity_atomic
                    JOIN ethereum_burn_observation o
                        ON o.operation_id = p.operation_id
                       AND o.operation_attempt_id = p.attempt_id
                       AND o.observation_sequence = p.observation_sequence
                       AND o.evidence_ref = p.evidence_id
                    JOIN LATERAL (
                        SELECT finality_status
                        FROM operation_finality
                        WHERE operation_id = p.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1
                    ) f ON true
                    WHERE p.evidence_id = :evidenceId
                      AND p.evidence_type = 'BURN'
                      AND p.recorded_at >= o.observed_at
                      AND NOT EXISTS (
                          SELECT 1 FROM ethereum_burn_observation later
                          WHERE later.operation_id = p.operation_id
                            AND later.operation_attempt_id = p.attempt_id
                            AND later.observation_sequence > p.observation_sequence)
                    """;
            case BANK_WITHDRAWAL, BANK_DEPOSIT -> throw new IllegalStateException(
                    "bank evidence query was not selected");
        };
        return jdbc.sql(sql)
                .param("evidenceId", identity.value())
                .query(PostgresReserveAccountingAdapter::mapEvidence)
                .optional();
    }

    private static void verifyEvidence(
            DurableEvidence evidence,
            EvidenceType expectedType,
            PostCommand command,
            ReserveAccounting.Snapshot current) {
        if (!isSupportedAndFresh(evidence, expectedType,
                command.evidencePolicy(), command.recordedAt())) {
            throw new IllegalStateException("evidence is unsupported, nonfinal, or stale");
        }
        boolean bank = expectedType == EvidenceType.BANK_WITHDRAWAL
                || expectedType == EvidenceType.BANK_DEPOSIT;
        if (bank) {
            return;
        }
        UsdCents expectedSupply = switch (expectedType) {
            case MINT -> current.positions().confirmedChainTotalSupply()
                    .add(evidence.amount());
            case REDEMPTION_CUSTODY ->
                    current.positions().confirmedChainTotalSupply();
            case BURN -> current.positions().confirmedChainTotalSupply()
                    .subtract(evidence.amount());
            case BANK_WITHDRAWAL, BANK_DEPOSIT -> throw new IllegalStateException(
                    "bank evidence has no chain supply observation");
        };
        if (!expectedSupply.equals(evidence.observedSupply())) {
            throw new IllegalStateException("chain supply observation does not match");
        }
    }

    private static boolean isSupportedAndFresh(
            DurableEvidence evidence,
            EvidenceType expectedType,
            EvidencePolicy policy,
            Instant recordedAt) {
        if (evidence.type() != expectedType) {
            return false;
        }
        boolean bank = expectedType == EvidenceType.BANK_WITHDRAWAL
                || expectedType == EvidenceType.BANK_DEPOSIT;
        String expectedPolicy = switch (expectedType) {
            case BANK_WITHDRAWAL, BANK_DEPOSIT -> policy.bankEvidencePolicyVersion();
            case MINT -> policy.mintEvidencePolicyVersion();
            case REDEMPTION_CUSTODY -> policy.custodyEvidencePolicyVersion();
            case BURN -> policy.burnEvidencePolicyVersion();
        };
        if (!evidence.policyVersion().equals(expectedPolicy)) {
            return false;
        }
        if (bank) {
            return "SUCCEEDED".equals(evidence.status())
                    && evidence.operationId() != null
                    && "USD".equals(evidence.assetId());
        }
        boolean nativeIdentitiesPresent = evidence.attemptId() != null
                && (expectedType == EvidenceType.REDEMPTION_CUSTODY
                        ? evidence.walletTransferId() != null
                        : evidence.operationId() != null);
        return "CONFIRMED".equals(evidence.status())
                && nativeIdentitiesPresent
                && evidence.canonical() && !evidence.removed()
                && evidence.finalityReached()
                && evidence.assetId().equals(policy.chainAssetId())
                && Objects.equals(evidence.network(), policy.settlementNetwork())
                && Objects.equals(evidence.contractReference(), policy.contractReference())
                && !evidence.effectiveAt().isAfter(recordedAt)
                && !evidence.effectiveAt().plus(policy.maximumObservationAge())
                        .isBefore(recordedAt);
    }

    private ReserveAccounting.Snapshot snapshot(boolean lock) {
        String ledgerSql = """
                SELECT account_type, balance_cents
                FROM accounting_ledger_account ORDER BY account_type
                """ + (lock ? " FOR UPDATE" : "");
        EnumMap<ReserveAccounting.LedgerAccount, UsdCents> balances =
                new EnumMap<>(ReserveAccounting.LedgerAccount.class);
        jdbc.sql(ledgerSql).query((row, rowNumber) -> Map.entry(
                        ReserveAccounting.LedgerAccount.valueOf(
                                row.getString("account_type")),
                        cents(row.getBigDecimal("balance_cents"))))
                .list().forEach(entry -> balances.put(entry.getKey(), entry.getValue()));
        String positionsSql = """
                SELECT position_type, quantity_cents
                FROM accounting_operational_position ORDER BY position_type
                """ + (lock ? " FOR UPDATE" : "");
        EnumMap<PositionType, UsdCents> positions = new EnumMap<>(PositionType.class);
        jdbc.sql(positionsSql).query((row, rowNumber) -> Map.entry(
                        PositionType.valueOf(row.getString("position_type")),
                        cents(row.getBigDecimal("quantity_cents"))))
                .list().forEach(entry -> positions.put(entry.getKey(), entry.getValue()));
        if (balances.size() != ReserveAccounting.LedgerAccount.values().length
                || positions.size() != PositionType.values().length) {
            throw new IllegalStateException("accounting chart is incomplete");
        }
        return new ReserveAccounting.Snapshot(balances,
                new ReserveAccounting.Positions(
                        positions.get(PositionType.ADMIN_REDEMPTION_CUSTODY_PENDING_BURN),
                        positions.get(PositionType.CONFIRMED_CHAIN_TOTAL_SUPPLY),
                        positions.get(PositionType.CONTROLLED_INVENTORY)));
    }

    private StoredJournal loadOriginalJournal(ReserveAccounting.JournalId id) {
        JournalHeader header = jdbc.sql("""
                        SELECT posting_type, accounting_policy_version,
                               effective_at, recorded_at, tenant_id, participant_id,
                               correlation_id, evidence_id, reverses_entry_id
                        FROM accounting_journal_entry
                        WHERE journal_entry_id = :journalId
                        FOR UPDATE
                        """)
                .param("journalId", id.value())
                .query((row, rowNumber) -> new JournalHeader(
                        ReserveAccounting.PostingType.valueOf(
                                row.getString("posting_type")),
                        new ReserveAccounting.PolicyVersion(
                                row.getString("accounting_policy_version")),
                        row.getObject("effective_at", OffsetDateTime.class).toInstant(),
                        row.getObject("recorded_at", OffsetDateTime.class).toInstant(),
                        row.getString("tenant_id"), row.getString("participant_id"),
                        new ReserveAccounting.CorrelationId(
                                row.getString("correlation_id")),
                        new ReserveAccounting.EvidenceIdentity(
                                row.getString("evidence_id")),
                        row.getObject("reverses_entry_id", UUID.class)))
                .optional().orElseThrow(() ->
                        new IllegalStateException("original journal is unavailable"));
        if (header.postingType() == ReserveAccounting.PostingType.REVERSAL
                || header.reversesEntryId() != null) {
            throw new IllegalArgumentException("a reversal cannot reverse another reversal");
        }
        List<ReserveAccounting.Line> lines = jdbc.sql("""
                        SELECT journal_line_id, account_type, direction, amount_cents
                        FROM accounting_journal_line
                        WHERE journal_entry_id = :journalId
                        ORDER BY CASE direction WHEN 'DEBIT' THEN 0 ELSE 1 END
                        """)
                .param("journalId", id.value())
                .query((row, rowNumber) -> new ReserveAccounting.Line(
                        new ReserveAccounting.JournalLineId(
                                row.getObject("journal_line_id", UUID.class)),
                        ReserveAccounting.LedgerAccount.valueOf(
                                row.getString("account_type")),
                        ReserveAccounting.Direction.valueOf(row.getString("direction")),
                        cents(row.getBigDecimal("amount_cents"))))
                .list();
        ReserveAccounting.Journal journal = new ReserveAccounting.Journal(
                id, header.postingType(), header.policyVersion(),
                header.effectiveAt(), header.recordedAt(), header.correlationId(),
                header.evidenceIdentity(), null, lines);
        return new StoredJournal(journal, header.tenantId(), header.participantId());
    }

    private void insertJournal(
            ReserveAccounting.Journal journal, String tenantId, String participantId) {
        UsdCents amount = journal.lines().getFirst().amount();
        jdbc.sql("""
                        INSERT INTO accounting_journal_entry (
                            journal_entry_id, posting_type, accounting_policy_version,
                            effective_at, recorded_at, tenant_id, participant_id,
                            correlation_id, evidence_id, reverses_entry_id,
                            amount_cents, entry_status)
                        VALUES (:journalId, :postingType, :policyVersion,
                            :effectiveAt, :recordedAt, :tenantId, :participantId,
                            :correlationId, :evidenceId, :reverses,
                            :amount, 'POSTED')
                        """)
                .param("journalId", journal.id().value())
                .param("postingType", journal.postingType().name())
                .param("policyVersion", journal.policyVersion().value())
                .param("effectiveAt", utc(journal.effectiveAt()))
                .param("recordedAt", utc(journal.recordedAt()))
                .param("tenantId", tenantId, Types.VARCHAR)
                .param("participantId", participantId, Types.VARCHAR)
                .param("correlationId", journal.correlationId().value())
                .param("evidenceId", journal.evidenceIdentity().value())
                .param("reverses", journal.reverses() == null
                        ? null : journal.reverses().value(), Types.OTHER)
                .param("amount", decimal(amount)).update();
        for (ReserveAccounting.Line line : journal.lines()) {
            jdbc.sql("""
                            INSERT INTO accounting_journal_line (
                                journal_line_id, journal_entry_id,
                                account_type, direction, amount_cents)
                            VALUES (:lineId, :journalId, :account, :direction, :amount)
                            """)
                    .param("lineId", line.id().value())
                    .param("journalId", journal.id().value())
                    .param("account", line.account().name())
                    .param("direction", line.direction().name())
                    .param("amount", decimal(line.amount())).update();
        }
    }

    private void updateState(ReserveAccounting.Snapshot state, Instant updatedAt) {
        for (Map.Entry<ReserveAccounting.LedgerAccount, UsdCents> entry
                : state.balances().entrySet()) {
            requireOne(jdbc.sql("""
                            UPDATE accounting_ledger_account
                            SET balance_cents = :balance,
                                account_version = account_version + 1,
                                updated_at = :updatedAt
                            WHERE account_type = :account
                            """)
                    .param("balance", decimal(entry.getValue()))
                    .param("updatedAt", utc(updatedAt))
                    .param("account", entry.getKey().name()).update(),
                    "ledger account update");
        }
        updatePosition(PositionType.ADMIN_REDEMPTION_CUSTODY_PENDING_BURN,
                state.positions().adminRedemptionCustodyPendingBurn(), updatedAt);
        updatePosition(PositionType.CONFIRMED_CHAIN_TOTAL_SUPPLY,
                state.positions().confirmedChainTotalSupply(), updatedAt);
        updatePosition(PositionType.CONTROLLED_INVENTORY,
                state.positions().controlledInventory(), updatedAt);
    }

    private void updatePosition(PositionType type, UsdCents value, Instant updatedAt) {
        requireOne(jdbc.sql("""
                        UPDATE accounting_operational_position
                        SET quantity_cents = :quantity,
                            position_version = position_version + 1,
                            updated_at = :updatedAt
                        WHERE position_type = :type
                        """)
                .param("quantity", decimal(value))
                .param("updatedAt", utc(updatedAt))
                .param("type", type.name()).update(), "operational position update");
    }

    private void insertReconciliation(
            ReserveAccounting.Reconciliation result,
            ReconcileCommand command,
            List<ReserveAccounting.EvidenceIdentity> evidence) {
        ReserveAccounting.Snapshot state = result.snapshot();
        jdbc.sql("""
                        INSERT INTO accounting_reconciliation_run (
                            reconciliation_run_id, reconciliation_result_id,
                            reconciliation_status, reserve_cash_cents,
                            pending_mint_cents, circulating_cents,
                            redemption_payable_cents, custody_pending_burn_cents,
                            confirmed_supply_cents, controlled_inventory_cents,
                            evidence_complete, observation_supported_fresh,
                            accounting_policy_version, recorded_at)
                        VALUES (:runId, :resultId, :status, :reserve, :pending,
                            :circulating, :payable, :custody, :supply, :inventory,
                            :complete, :supportedFresh, :policy, :recordedAt)
                        """)
                .param("runId", result.runId().value())
                .param("resultId", result.resultId().value())
                .param("status", result.status().name())
                .param("reserve", balance(state,
                        ReserveAccounting.LedgerAccount.RESERVE_CASH_ASSET))
                .param("pending", balance(state,
                        ReserveAccounting.LedgerAccount
                                .FIAT_RECEIVED_PENDING_MINT_LIABILITY))
                .param("circulating", balance(state,
                        ReserveAccounting.LedgerAccount.USDZELLE_CIRCULATING_LIABILITY))
                .param("payable", balance(state,
                        ReserveAccounting.LedgerAccount.REDEMPTION_PAYABLE_LIABILITY))
                .param("custody", decimal(
                        state.positions().adminRedemptionCustodyPendingBurn()))
                .param("supply", decimal(state.positions().confirmedChainTotalSupply()))
                .param("inventory", decimal(state.positions().controlledInventory()))
                .param("complete", result.evidenceComplete())
                .param("supportedFresh", result.observationSupportedAndFresh())
                .param("policy", command.evidencePolicy()
                        .accountingPolicyVersion().value())
                .param("recordedAt", utc(result.recordedAt())).update();
        int order = 0;
        for (ReserveAccounting.EvidenceIdentity item : evidence) {
            jdbc.sql("""
                            INSERT INTO accounting_reconciliation_evidence (
                                reconciliation_run_id, evidence_order, evidence_id)
                            VALUES (:runId, :order, :evidenceId)
                            """)
                    .param("runId", result.runId().value())
                    .param("order", order++)
                    .param("evidenceId", item.value()).update();
        }
    }

    private static DurableEvidence mapEvidence(ResultSet row, int rowNumber)
            throws SQLException {
        return new DurableEvidence(
                new ReserveAccounting.EvidenceIdentity(row.getString("evidence_id")),
                EvidenceType.valueOf(row.getString("evidence_type")),
                row.getString("correlation_id"), row.getString("tenant_id"),
                row.getString("participant_id"),
                row.getObject("operation_id", UUID.class),
                row.getObject("attempt_id", UUID.class),
                row.getObject("wallet_transfer_id", UUID.class),
                row.getString("asset_id"),
                row.getString("network"), row.getString("contract_reference"),
                cents(row.getBigDecimal("amount_cents")),
                nullableCents(row.getBigDecimal("observed_supply_cents")),
                row.getString("evidence_status"), row.getBoolean("canonical"),
                row.getBoolean("removed"), row.getBoolean("finality_reached"),
                row.getString("policy_version"),
                row.getObject("observed_at", OffsetDateTime.class).toInstant());
    }

    private static EvidenceType expectedEvidenceType(
            ReserveAccounting.PostingType postingType) {
        return switch (postingType) {
            case RESERVE_FUNDING -> EvidenceType.BANK_WITHDRAWAL;
            case MINT_CONFIRMED -> EvidenceType.MINT;
            case REDEMPTION_CUSTODY_CONFIRMED -> EvidenceType.REDEMPTION_CUSTODY;
            case BANK_PAYOUT_CONFIRMED -> EvidenceType.BANK_DEPOSIT;
            case BURN_CONFIRMED -> EvidenceType.BURN;
            case REVERSAL -> throw new IllegalArgumentException(
                    "reversal does not consume lifecycle evidence");
        };
    }

    private static BigDecimal balance(
            ReserveAccounting.Snapshot state,
            ReserveAccounting.LedgerAccount account) {
        return decimal(state.balances().get(account));
    }

    private static BigDecimal decimal(UsdCents value) {
        return new BigDecimal(value.value());
    }

    private static UsdCents cents(BigDecimal value) {
        return new UsdCents(value.toBigIntegerExact());
    }

    private static UsdCents nullableCents(BigDecimal value) {
        return value == null ? null : cents(value);
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }

    private enum EvidenceType {
        BANK_WITHDRAWAL,
        BANK_DEPOSIT,
        MINT,
        REDEMPTION_CUSTODY,
        BURN
    }

    private enum PositionType {
        ADMIN_REDEMPTION_CUSTODY_PENDING_BURN,
        CONFIRMED_CHAIN_TOTAL_SUPPLY,
        CONTROLLED_INVENTORY
    }

    private record DurableEvidence(
            ReserveAccounting.EvidenceIdentity identity,
            EvidenceType type,
            String correlationId,
            String tenantId,
            String participantId,
            UUID operationId,
            UUID attemptId,
            UUID walletTransferId,
            String assetId,
            String network,
            String contractReference,
            UsdCents amount,
            UsdCents observedSupply,
            String status,
            boolean canonical,
            boolean removed,
            boolean finalityReached,
            String policyVersion,
            Instant effectiveAt) { }

    private record ExistingConsumption(
            ReserveAccounting.PostingType postingType,
            String commandDigest,
            UUID journalId) { }

    private record ConsumedEvidence(
            ReserveAccounting.EvidenceIdentity identity,
            EvidenceType type) { }

    private record ExistingReversal(
            String correctionEvidenceId,
            UUID originalJournalId,
            UUID reversalJournalId,
            String commandDigest) { }

    private record JournalHeader(
            ReserveAccounting.PostingType postingType,
            ReserveAccounting.PolicyVersion policyVersion,
            Instant effectiveAt,
            Instant recordedAt,
            String tenantId,
            String participantId,
            ReserveAccounting.CorrelationId correlationId,
            ReserveAccounting.EvidenceIdentity evidenceIdentity,
            UUID reversesEntryId) { }

    private record StoredJournal(
            ReserveAccounting.Journal journal,
            String tenantId,
            String participantId) { }
}
