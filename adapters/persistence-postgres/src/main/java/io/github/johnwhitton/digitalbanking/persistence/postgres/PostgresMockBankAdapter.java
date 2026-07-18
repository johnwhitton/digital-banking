package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.BankPreEffectFailureException;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit-JDBC durable implementation of the provider-neutral synthetic-bank port. */
public final class PostgresMockBankAdapter implements MockBankPort {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final FailurePoint failurePoint;

    public PostgresMockBankAdapter(DataSource dataSource) {
        this(dataSource, Duration.ofSeconds(5), FailurePoint.NONE);
    }

    public PostgresMockBankAdapter(DataSource dataSource, Duration queryTimeout) {
        this(dataSource, queryTimeout, FailurePoint.NONE);
    }

    PostgresMockBankAdapter(DataSource dataSource, FailurePoint failurePoint) {
        this(dataSource, Duration.ofSeconds(5), failurePoint);
    }

    private PostgresMockBankAdapter(
            DataSource dataSource, Duration queryTimeout, FailurePoint failurePoint) {
        Objects.requireNonNull(dataSource, "dataSource");
        Objects.requireNonNull(queryTimeout, "queryTimeout");
        if (queryTimeout.isNegative() || queryTimeout.isZero()
                || queryTimeout.compareTo(Duration.ofSeconds(Integer.MAX_VALUE)) > 0) {
            throw new IllegalArgumentException("bank query timeout is invalid");
        }
        JdbcTemplate template = new JdbcTemplate(dataSource);
        template.setQueryTimeout((int) Math.max(1, queryTimeout.toSeconds()));
        this.jdbc = JdbcClient.create(template);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.failurePoint = Objects.requireNonNull(failurePoint, "failurePoint");
    }

    @Override
    public Outcome request(Command command) {
        throw new UnsupportedOperationException(
                "Phase 3C transfer-effect execution remains a Phase 6B concern");
    }

    @Override
    public BankResponse execute(BankCommand command) {
        Objects.requireNonNull(command, "command");
        Execution execution = Objects.requireNonNull(transactions.execute(status ->
                executeInTransaction(command)), "bank execution result");
        if (!execution.replayed()
                && failurePoint == FailurePoint.AFTER_COMMIT_UNKNOWN) {
            return new BankResponse(
                    execution.operation().id(), ResponseStatus.UNKNOWN,
                    null, null, false, "response-unknown");
        }
        return response(execution.operation(), execution.replayed());
    }

    @Override
    public Optional<BankOperation> inquire(
            BankOperation.Id operationId, ParticipantScope participant) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(participant, "participant");
        return jdbc.sql("""
                        SELECT * FROM synthetic_bank_operation
                        WHERE operation_id = :operationId
                          AND tenant_id = :tenantId
                          AND participant_id = :participantId
                        """)
                .param("operationId", operationId.value())
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .query(PostgresMockBankAdapter::mapOperation).optional();
    }

    @Override
    public Optional<SyntheticBankAccount> findAccount(
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            ParticipantScope participant) {
        Objects.requireNonNull(bankId, "bankId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(participant, "participant");
        return jdbc.sql("""
                        SELECT a.bank_id, a.account_id, a.tenant_id, a.participant_id,
                               a.balance_cents, (a.enabled AND b.enabled) AS enabled,
                               a.account_version, a.fixture_version,
                               a.initial_balance_cents, a.created_at, a.updated_at
                        FROM synthetic_bank_account a
                        JOIN synthetic_bank b ON b.bank_id = a.bank_id
                        WHERE a.bank_id = :bankId AND a.account_id = :accountId
                          AND a.tenant_id = :tenantId
                          AND a.participant_id = :participantId
                        """)
                .param("bankId", bankId.value())
                .param("accountId", accountId.value())
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .query(PostgresMockBankAdapter::mapAccount).optional();
    }

    @Override
    public void initialize(Fixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        transactions.executeWithoutResult(status -> {
            for (BankFixture bank : fixture.banks()) {
                int inserted = jdbc.sql("""
                                INSERT INTO synthetic_bank (
                                    bank_id, enabled, fixture_version, created_at)
                                VALUES (:bankId, :enabled, :fixtureVersion, :createdAt)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("bankId", bank.bankId().value())
                        .param("enabled", bank.enabled())
                        .param("fixtureVersion", fixture.version().value())
                        .param("createdAt", utc(fixture.initializedAt()))
                        .update();
                if (inserted == 0) {
                    Boolean matches = jdbc.sql("""
                                    SELECT enabled = :enabled
                                           AND fixture_version = :fixtureVersion
                                    FROM synthetic_bank WHERE bank_id = :bankId
                                    FOR UPDATE
                                    """)
                            .param("enabled", bank.enabled())
                            .param("fixtureVersion", fixture.version().value())
                            .param("bankId", bank.bankId().value())
                            .query(Boolean.class).single();
                    if (!matches) {
                        throw new IllegalStateException(
                                "synthetic bank fixture definition changed");
                    }
                }
            }
            for (AccountFixture account : fixture.accounts()) {
                int inserted = jdbc.sql("""
                                INSERT INTO synthetic_bank_account (
                                    bank_id, account_id, tenant_id, participant_id,
                                    currency, balance_cents, initial_balance_cents,
                                    enabled, account_version, fixture_version,
                                    created_at, updated_at)
                                VALUES (
                                    :bankId, :accountId, :tenantId, :participantId,
                                    'USD', :initialBalance, :initialBalance,
                                    :enabled, 0, :fixtureVersion, :createdAt, :createdAt)
                                ON CONFLICT DO NOTHING
                                """)
                        .param("bankId", account.bankId().value())
                        .param("accountId", account.accountId().value())
                        .param("tenantId", account.owner().tenantId())
                        .param("participantId", account.owner().participantId())
                        .param("initialBalance", decimal(account.initialBalance()))
                        .param("enabled", account.enabled())
                        .param("fixtureVersion", fixture.version().value())
                        .param("createdAt", utc(fixture.initializedAt()))
                        .update();
                if (inserted == 0 && !fixtureAccountMatches(account, fixture.version())) {
                    throw new IllegalStateException(
                            "synthetic bank account fixture definition changed");
                }
            }
        });
    }

    private Execution executeInTransaction(BankCommand command) {
        AccountState account = jdbc.sql("""
                        SELECT a.balance_cents, a.enabled AS account_enabled,
                               b.enabled AS bank_enabled
                        FROM synthetic_bank_account a
                        JOIN synthetic_bank b ON b.bank_id = a.bank_id
                        WHERE a.bank_id = :bankId AND a.account_id = :accountId
                          AND a.tenant_id = :tenantId
                          AND a.participant_id = :participantId
                        FOR UPDATE OF a
                        """)
                .param("bankId", command.bankId().value())
                .param("accountId", command.accountId().value())
                .param("tenantId", command.participant().tenantId())
                .param("participantId", command.participant().participantId())
                .query((row, rowNumber) -> new AccountState(
                        cents(row.getBigDecimal("balance_cents")),
                        row.getBoolean("account_enabled")
                                && row.getBoolean("bank_enabled")))
                .optional().orElseThrow(() -> new IllegalStateException(
                        "participant-scoped synthetic bank account is unavailable"));

        int claimed = jdbc.sql("""
                        INSERT INTO synthetic_bank_idempotency (
                            tenant_id, participant_id, bank_id, account_id,
                            operation_kind, idempotency_key_digest, command_digest,
                            operation_id, created_at)
                        VALUES (
                            :tenantId, :participantId, :bankId, :accountId,
                            :kind, :keyDigest, :commandDigest,
                            :operationId, :createdAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("tenantId", command.participant().tenantId())
                .param("participantId", command.participant().participantId())
                .param("bankId", command.bankId().value())
                .param("accountId", command.accountId().value())
                .param("kind", command.kind().name())
                .param("keyDigest", command.idempotencyKeyDigest())
                .param("commandDigest", command.commandDigest())
                .param("operationId", command.operationId().value())
                .param("createdAt", utc(command.requestedAt()))
                .update();
        if (claimed == 0) {
            ExistingBinding existing = jdbc.sql("""
                            SELECT operation_id, command_digest
                            FROM synthetic_bank_idempotency
                            WHERE tenant_id = :tenantId
                              AND participant_id = :participantId
                              AND bank_id = :bankId AND account_id = :accountId
                              AND operation_kind = :kind
                              AND idempotency_key_digest = :keyDigest
                            """)
                    .param("tenantId", command.participant().tenantId())
                    .param("participantId", command.participant().participantId())
                    .param("bankId", command.bankId().value())
                    .param("accountId", command.accountId().value())
                    .param("kind", command.kind().name())
                    .param("keyDigest", command.idempotencyKeyDigest())
                    .query((row, rowNumber) -> new ExistingBinding(
                            row.getObject("operation_id", UUID.class),
                            row.getString("command_digest")))
                    .single();
            if (!existing.commandDigest().equals(command.commandDigest())) {
                throw new IdempotencyConflictException();
            }
            BankOperation operation = jdbc.sql("""
                            SELECT * FROM synthetic_bank_operation
                            WHERE operation_id = :operationId
                            """)
                    .param("operationId", existing.operationId())
                    .query(PostgresMockBankAdapter::mapOperation).single();
            return new Execution(operation, true);
        }

        BankOperation operation = decide(command, account);
        if (operation.status() == BankOperation.Status.SUCCEEDED) {
            int updated = jdbc.sql("""
                            UPDATE synthetic_bank_account
                            SET balance_cents = :balance,
                                account_version = account_version + 1,
                                updated_at = :updatedAt
                            WHERE bank_id = :bankId AND account_id = :accountId
                            """)
                    .param("balance", decimal(operation.balanceAfter()))
                    .param("updatedAt", utc(command.requestedAt()))
                    .param("bankId", command.bankId().value())
                    .param("accountId", command.accountId().value())
                    .update();
            requireOne(updated, "synthetic bank account mutation");
        }
        if (failurePoint == FailurePoint.BEFORE_OPERATION_PERSIST) {
            throw new BankPreEffectFailureException("bank effect did not commit");
        }
        insertOperation(operation);
        if (operation.status() == BankOperation.Status.SUCCEEDED) {
            jdbc.sql("""
                            INSERT INTO synthetic_bank_balance_entry (
                                operation_id, evidence_id, direction, amount_cents,
                                balance_before_cents, balance_after_cents, recorded_at)
                            VALUES (
                                :operationId, :evidenceId, :direction, :amount,
                                :before, :after, :recordedAt)
                            """)
                    .param("operationId", operation.id().value())
                    .param("evidenceId", operation.evidenceId().value())
                    .param("direction", operation.kind() == BankOperation.Kind.WITHDRAWAL
                            ? "DEBIT" : "CREDIT")
                    .param("amount", decimal(operation.amount()))
                    .param("before", decimal(account.balance()))
                    .param("after", decimal(operation.balanceAfter()))
                    .param("recordedAt", utc(operation.recordedAt()))
                    .update();
        }
        return new Execution(operation, false);
    }

    private BankOperation decide(BankCommand command, AccountState account) {
        if (!account.enabled()) {
            return rejected(command, "account-disabled");
        }
        if (command.kind() == BankOperation.Kind.WITHDRAWAL
                && account.balance().compareTo(command.amount()) < 0) {
            return rejected(command, "insufficient-funds");
        }
        UsdCents balance;
        try {
            balance = command.kind() == BankOperation.Kind.WITHDRAWAL
                    ? account.balance().subtract(command.amount())
                    : account.balance().add(command.amount());
        } catch (IllegalArgumentException failure) {
            return rejected(command, "balance-overflow");
        }
        return new BankOperation(
                command.operationId(), command.evidenceId(), command.kind(),
                BankOperation.Status.SUCCEEDED, command.bankId(), command.accountId(),
                owner(command.participant()), command.amount(), balance,
                command.idempotencyKeyDigest(), command.commandDigest(),
                command.correlationId(), command.policyVersion(), null,
                command.requestedAt());
    }

    private static BankOperation rejected(BankCommand command, String code) {
        return new BankOperation(
                command.operationId(), command.evidenceId(), command.kind(),
                BankOperation.Status.REJECTED, command.bankId(), command.accountId(),
                owner(command.participant()), command.amount(), null,
                command.idempotencyKeyDigest(), command.commandDigest(),
                command.correlationId(), command.policyVersion(), code,
                command.requestedAt());
    }

    private void insertOperation(BankOperation operation) {
        jdbc.sql("""
                        INSERT INTO synthetic_bank_operation (
                            operation_id, evidence_id, tenant_id, participant_id,
                            bank_id, account_id, operation_kind, operation_status,
                            amount_cents, balance_after_cents,
                            idempotency_key_digest, command_digest,
                            correlation_id, policy_version, safe_failure_code, recorded_at)
                        VALUES (
                            :operationId, :evidenceId, :tenantId, :participantId,
                            :bankId, :accountId, :kind, :status,
                            :amount, :balanceAfter,
                            :keyDigest, :commandDigest,
                            :correlationId, :policyVersion, :failureCode, :recordedAt)
                        """)
                .param("operationId", operation.id().value())
                .param("evidenceId", operation.evidenceId().value())
                .param("tenantId", operation.owner().tenantId())
                .param("participantId", operation.owner().participantId())
                .param("bankId", operation.bankId().value())
                .param("accountId", operation.accountId().value())
                .param("kind", operation.kind().name())
                .param("status", operation.status().name())
                .param("amount", decimal(operation.amount()))
                .param("balanceAfter", operation.balanceAfter() == null
                        ? null : decimal(operation.balanceAfter()))
                .param("keyDigest", operation.idempotencyKeyDigest())
                .param("commandDigest", operation.commandDigest())
                .param("correlationId", operation.correlationId().value())
                .param("policyVersion", operation.policyVersion().value())
                .param("failureCode", operation.safeFailureCode())
                .param("recordedAt", utc(operation.recordedAt()))
                .update();
    }

    private boolean fixtureAccountMatches(
            AccountFixture account, SyntheticBankAccount.FixtureVersion version) {
        return jdbc.sql("""
                        SELECT tenant_id = :tenantId
                               AND participant_id = :participantId
                               AND currency = 'USD'
                               AND initial_balance_cents = :initialBalance
                               AND enabled = :enabled
                               AND fixture_version = :fixtureVersion
                        FROM synthetic_bank_account
                        WHERE bank_id = :bankId AND account_id = :accountId
                        FOR UPDATE
                        """)
                .param("tenantId", account.owner().tenantId())
                .param("participantId", account.owner().participantId())
                .param("initialBalance", decimal(account.initialBalance()))
                .param("enabled", account.enabled())
                .param("fixtureVersion", version.value())
                .param("bankId", account.bankId().value())
                .param("accountId", account.accountId().value())
                .query(Boolean.class).single();
    }

    private static BankResponse response(BankOperation operation, boolean replayed) {
        if (operation.status() == BankOperation.Status.SUCCEEDED) {
            return new BankResponse(
                    operation.id(), ResponseStatus.SUCCEEDED, operation.evidenceId(),
                    operation.balanceAfter(), replayed, null);
        }
        return new BankResponse(
                operation.id(), ResponseStatus.REJECTED, operation.evidenceId(),
                null, replayed, operation.safeFailureCode());
    }

    private static SyntheticBankAccount mapAccount(ResultSet row, int rowNumber)
            throws SQLException {
        return new SyntheticBankAccount(
                new SyntheticBankAccount.BankId(row.getString("bank_id")),
                new SyntheticBankAccount.AccountId(row.getString("account_id")),
                new SyntheticBankAccount.Owner(
                        row.getString("tenant_id"), row.getString("participant_id")),
                cents(row.getBigDecimal("balance_cents")), row.getBoolean("enabled"),
                row.getLong("account_version"),
                new SyntheticBankAccount.FixtureVersion(row.getString("fixture_version")),
                cents(row.getBigDecimal("initial_balance_cents")),
                instant(row, "created_at"), instant(row, "updated_at"));
    }

    private static BankOperation mapOperation(ResultSet row, int rowNumber)
            throws SQLException {
        BigDecimal balance = row.getBigDecimal("balance_after_cents");
        return new BankOperation(
                new BankOperation.Id(row.getObject("operation_id", UUID.class)),
                new BankOperation.EvidenceId(row.getObject("evidence_id", UUID.class)),
                BankOperation.Kind.valueOf(row.getString("operation_kind")),
                BankOperation.Status.valueOf(row.getString("operation_status")),
                new SyntheticBankAccount.BankId(row.getString("bank_id")),
                new SyntheticBankAccount.AccountId(row.getString("account_id")),
                new SyntheticBankAccount.Owner(
                        row.getString("tenant_id"), row.getString("participant_id")),
                cents(row.getBigDecimal("amount_cents")),
                balance == null ? null : cents(balance),
                row.getString("idempotency_key_digest"), row.getString("command_digest"),
                new BankOperation.CorrelationId(row.getString("correlation_id")),
                new BankOperation.PolicyVersion(row.getString("policy_version")),
                row.getString("safe_failure_code"), instant(row, "recorded_at"));
    }

    private static SyntheticBankAccount.Owner owner(ParticipantScope participant) {
        return new SyntheticBankAccount.Owner(
                participant.tenantId(), participant.participantId());
    }

    private static BigDecimal decimal(UsdCents value) {
        return new BigDecimal(value.value());
    }

    private static UsdCents cents(BigDecimal value) {
        return new UsdCents(value.toBigIntegerExact());
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static void requireOne(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }

    enum FailurePoint {
        NONE,
        BEFORE_OPERATION_PERSIST,
        AFTER_COMMIT_UNKNOWN
    }

    private record AccountState(UsdCents balance, boolean enabled) { }

    private record ExistingBinding(UUID operationId, String commandDigest) { }

    private record Execution(BankOperation operation, boolean replayed) { }
}
