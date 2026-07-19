package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.BankPreEffectFailureException;
import io.github.johnwhitton.digitalbanking.application.command.BankCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresMockBankAdapterTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant TIME = Instant.parse("2026-07-18T12:00:00Z");
    private static final ParticipantScope USER_1 =
            new ParticipantScope("local-demo", "user-1");
    private static final ParticipantScope USER_2 =
            new ParticipantScope("local-demo", "user-2");
    private static final SyntheticBankAccount.BankId BANK_1 =
            new SyntheticBankAccount.BankId("BANK_1");
    private static final SyntheticBankAccount.AccountId ACCOUNT_1 =
            new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT");

    private static PostgreSQLContainer postgres;
    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private PostgresMockBankAdapter adapter;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("digital_banking")
                .withUsername("digital_banking")
                .withPassword("digital_banking");
        postgres.start();
        dataSource = new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        postgres.stop();
    }

    @BeforeEach
    void reset() {
        jdbc.sql("TRUNCATE synthetic_bank CASCADE").update();
        adapter = new PostgresMockBankAdapter(dataSource);
        adapter.initialize(fixture("fixture-v1", new UsdCents(new BigInteger("10000"))));
    }

    @Test
    void migratesAndInitializesFixturesIdempotentlyWithoutResettingBalance() {
        Integer count = jdbc.sql("""
                        SELECT COUNT(*) FROM flyway_schema_history
                        WHERE success = TRUE
                        """).query(Integer.class).single();
        assertEquals(8, count);
        assertEquals(new BigInteger("10000"), account(USER_1).balance().value());

        MockBankPort.BankResponse withdrawal = adapter.execute(command(
                "withdraw-one", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(new BigInteger("2500")), USER_1));
        adapter.initialize(fixture("fixture-v1", new UsdCents(new BigInteger("10000"))));

        assertEquals(MockBankPort.ResponseStatus.SUCCEEDED, withdrawal.status());
        assertEquals(new BigInteger("7500"), account(USER_1).balance().value());
        assertThrows(IllegalStateException.class, () -> adapter.initialize(
                fixture("fixture-v2", new UsdCents(new BigInteger("10000")))));
    }

    @Test
    void executesWithdrawalAndDepositWithReplayConflictIsolationAndRejection() {
        MockBankPort.BankResponse first = adapter.execute(command(
                "stable-key", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(new BigInteger("6000")), USER_1));
        MockBankPort.BankResponse replay = adapter.execute(command(
                "stable-key", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(new BigInteger("6000")), USER_1));

        assertEquals(MockBankPort.ResponseStatus.SUCCEEDED, first.status());
        assertTrue(replay.replayed());
        assertEquals(first.operationId(), replay.operationId());
        assertEquals(new BigInteger("4000"), account(USER_1).balance().value());
        assertThrows(IdempotencyConflictException.class, () -> adapter.execute(command(
                "stable-key", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(new BigInteger("1")), USER_1)));

        MockBankPort.BankResponse rejected = adapter.execute(command(
                "overdraft", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(new BigInteger("5000")), USER_1));
        assertEquals(MockBankPort.ResponseStatus.REJECTED, rejected.status());
        assertEquals("insufficient-funds", rejected.safeFailureCode());
        assertEquals(new BigInteger("4000"), account(USER_1).balance().value());

        MockBankPort.BankResponse deposit = adapter.execute(command(
                "deposit", BankOperation.Kind.DEPOSIT,
                new UsdCents(new BigInteger("1000")), USER_1));
        assertEquals(new BigInteger("5000"), deposit.balanceAfter().value());
        assertFalse(adapter.findAccount(BANK_1, ACCOUNT_1, USER_2).isPresent());
        assertFalse(adapter.inquire(first.operationId(), USER_2).isPresent());
    }

    @Test
    void disabledAndOverflowRejectionsDoNotMutateBalance() {
        jdbc.sql("""
                UPDATE synthetic_bank_account SET enabled = false
                WHERE bank_id = 'BANK_1' AND account_id = 'USER_1_BANK_ACCOUNT'
                """).update();
        MockBankPort.BankResponse disabled = adapter.execute(command(
                "disabled", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(BigInteger.ONE), USER_1));
        assertEquals(MockBankPort.ResponseStatus.REJECTED, disabled.status());
        assertEquals("account-disabled", disabled.safeFailureCode());
        assertEquals(new BigInteger("10000"), account(USER_1).balance().value());

        jdbc.sql("""
                UPDATE synthetic_bank_account
                SET enabled = true, balance_cents = 999999999999999999
                WHERE bank_id = 'BANK_1' AND account_id = 'USER_1_BANK_ACCOUNT'
                """).update();
        MockBankPort.BankResponse overflow = adapter.execute(command(
                "overflow", BankOperation.Kind.DEPOSIT,
                new UsdCents(BigInteger.ONE), USER_1));
        assertEquals(MockBankPort.ResponseStatus.REJECTED, overflow.status());
        assertEquals("balance-overflow", overflow.safeFailureCode());
        assertEquals(UsdCents.MAX_VALUE, account(USER_1).balance().value());
    }

    @Test
    void simultaneousWithdrawalAndDepositCannotLoseAnUpdate() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var withdrawal = executor.submit(() -> {
                start.await();
                return adapter.execute(command(
                        "race-withdrawal", BankOperation.Kind.WITHDRAWAL,
                        new UsdCents(new BigInteger("7500")), USER_1));
            });
            var deposit = executor.submit(() -> {
                start.await();
                return adapter.execute(command(
                        "race-deposit", BankOperation.Kind.DEPOSIT,
                        new UsdCents(new BigInteger("5000")), USER_1));
            });
            start.countDown();
            assertEquals(MockBankPort.ResponseStatus.SUCCEEDED,
                    withdrawal.get(10, TimeUnit.SECONDS).status());
            assertEquals(MockBankPort.ResponseStatus.SUCCEEDED,
                    deposit.get(10, TimeUnit.SECONDS).status());
        }
        assertEquals(new BigInteger("7500"), account(USER_1).balance().value());
    }

    @Test
    void concurrentWithdrawalsCannotOverdrawAndAmbiguousCommitUsesInquiry() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return adapter.execute(command(
                        "concurrent-1", BankOperation.Kind.WITHDRAWAL,
                        new UsdCents(new BigInteger("7500")), USER_1));
            });
            var second = executor.submit(() -> {
                start.await();
                return adapter.execute(command(
                        "concurrent-2", BankOperation.Kind.WITHDRAWAL,
                        new UsdCents(new BigInteger("7500")), USER_1));
            });
            start.countDown();
            List<MockBankPort.BankResponse> outcomes =
                    List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream()
                    .filter(value -> value.status() == MockBankPort.ResponseStatus.SUCCEEDED)
                    .count());
            assertEquals(1, outcomes.stream()
                    .filter(value -> value.status() == MockBankPort.ResponseStatus.REJECTED)
                    .count());
        }
        assertEquals(new BigInteger("2500"), account(USER_1).balance().value());

        PostgresMockBankAdapter ambiguous = new PostgresMockBankAdapter(
                dataSource, PostgresMockBankAdapter.FailurePoint.AFTER_COMMIT_UNKNOWN);
        MockBankPort.BankCommand command = command(
                "ambiguous", BankOperation.Kind.DEPOSIT,
                new UsdCents(new BigInteger("500")), USER_1);
        MockBankPort.BankResponse response = ambiguous.execute(command);
        assertEquals(MockBankPort.ResponseStatus.UNKNOWN, response.status());
        BankOperation inquired = adapter.inquire(response.operationId(), USER_1).orElseThrow();
        assertEquals(BankOperation.Status.SUCCEEDED, inquired.status());
        assertEquals(new BigInteger("3000"), inquired.balanceAfter().value());
        assertTrue(adapter.execute(command).replayed());
        assertEquals(new BigInteger("3000"), account(USER_1).balance().value());
    }

    @Test
    void rollbackAfterMutationLeavesNoOperationOrBalanceChange() {
        PostgresMockBankAdapter failing = new PostgresMockBankAdapter(
                dataSource, PostgresMockBankAdapter.FailurePoint.BEFORE_OPERATION_PERSIST);
        MockBankPort.BankCommand command = command(
                "rollback", BankOperation.Kind.WITHDRAWAL,
                new UsdCents(new BigInteger("1000")), USER_1);
        assertThrows(BankPreEffectFailureException.class, () -> failing.execute(command));
        assertEquals(new BigInteger("10000"), account(USER_1).balance().value());
        assertTrue(adapter.inquire(command.operationId(), USER_1).isEmpty());
    }

    private SyntheticBankAccount account(ParticipantScope participant) {
        return adapter.findAccount(BANK_1, ACCOUNT_1, participant).orElseThrow();
    }

    private static MockBankPort.BankCommand command(
            String key,
            BankOperation.Kind kind,
            UsdCents amount,
            ParticipantScope participant) {
        UUID stable = UUID.nameUUIDFromBytes((key + kind).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String digest = BankCommandCanonicalizer.encode(
                participant, BANK_1, ACCOUNT_1, kind, amount).sha256();
        return new MockBankPort.BankCommand(
                new BankOperation.Id(stable),
                new BankOperation.EvidenceId(new UUID(stable.getMostSignificantBits(),
                        stable.getLeastSignificantBits() ^ 1)),
                BANK_1, ACCOUNT_1, participant, amount, kind,
                IdempotencyKey.of(key).sha256(), digest,
                new BankOperation.CorrelationId("test:" + stable),
                new BankOperation.PolicyVersion("bank-policy-v1"), TIME);
    }

    private static MockBankPort.Fixture fixture(String version, UsdCents initial) {
        SyntheticBankAccount.BankId bank2 = new SyntheticBankAccount.BankId("BANK_2");
        return new MockBankPort.Fixture(
                new SyntheticBankAccount.FixtureVersion(version),
                List.of(
                        new MockBankPort.BankFixture(BANK_1, true),
                        new MockBankPort.BankFixture(bank2, true),
                        new MockBankPort.BankFixture(
                                new SyntheticBankAccount.BankId("BANK_3"), true),
                        new MockBankPort.BankFixture(
                                new SyntheticBankAccount.BankId("BANK_4"), true)),
                List.of(
                        new MockBankPort.AccountFixture(
                                BANK_1, ACCOUNT_1, USER_1, initial, true),
                        new MockBankPort.AccountFixture(
                                bank2,
                                new SyntheticBankAccount.AccountId("USER_2_BANK_ACCOUNT"),
                                USER_2, UsdCents.ZERO, true)),
                TIME);
    }
}
