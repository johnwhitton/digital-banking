package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.CommandDigest;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyResource;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyScope;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.RetryAuthorization;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresOperationRepositoryTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant START = Instant.parse("2026-07-16T23:00:00Z");
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final ParticipantScope OTHER_PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-b");
    private static final AssetUnit UNIT = new AssetUnit(
            "REFERENCE_ASSET", "REFERENCE_UNIT", 3, 2,
            new BigInteger("9".repeat(512)));
    private static final CanonicalCommandMetadata CANONICAL = metadata('a');

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresOperationRepository repository;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("digital_banking")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        dataSource = dataSource();
        Flyway.configure()
                .dataSource(dataSource)
                .cleanDisabled(true)
                .load()
                .migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new PostgresOperationRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearBusinessData() {
        jdbc.sql("DELETE FROM operation_outbox").update();
        jdbc.sql("DELETE FROM operation_finality_evidence").update();
        jdbc.sql("DELETE FROM operation_finality").update();
        jdbc.sql("DELETE FROM operation_attempt").update();
        jdbc.sql("DELETE FROM operation_transition_evidence").update();
        jdbc.sql("DELETE FROM operation_transition").update();
        jdbc.sql("DELETE FROM operation_idempotency").update();
        jdbc.sql("DELETE FROM token_operation").update();
    }

    @Test
    void migratesAnEmptyPostgresDatabaseAndCreatesConstrainedIndexedSchema() {
        Integer migrationCount = jdbc.sql("""
                        SELECT count(*)
                        FROM flyway_schema_history
                        WHERE success
                        """).query(Integer.class).single();
        Integer tableCount = jdbc.sql("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                            'token_operation', 'operation_idempotency',
                            'operation_transition', 'operation_transition_evidence',
                            'operation_attempt', 'operation_finality',
                            'operation_finality_evidence', 'operation_outbox',
                            'operation_delivery_attempt')
                        """).query(Integer.class).single();
        List<String> indexes = jdbc.sql("""
                        SELECT indexname
                        FROM pg_indexes
                        WHERE schemaname = 'public'
                        ORDER BY indexname
                        """).query(String.class).list();

        assertEquals(6, migrationCount);
        assertEquals(9, tableCount);
        assertTrue(indexes.contains("idx_token_operation_participant"));
        assertTrue(indexes.contains("idx_operation_idempotency_lookup"));
        assertTrue(indexes.contains("idx_operation_outbox_eligible"));
        assertTrue(indexes.contains("idx_operation_delivery_attempt_event"));
    }

    @Test
    void roundTripsTheMaximumExactQuantityWithoutFloatingPointNarrowing() {
        String maximum = "9".repeat(512);
        AssetUnit maximumUnit = new AssetUnit(
                "BOUNDARY_ASSET", "ATOMIC", 1, 0, new BigInteger(maximum));
        OperationAcceptance accepted = accept(
                repository, PARTICIPANT, OperationKind.MINT, "quantity-boundary", CANONICAL,
                () -> requested(
                        OperationKind.MINT, TokenQuantity.parse(maximum, maximumUnit)));

        TokenOperation loaded = repository.findById(
                accepted.operation().operationId()).orElseThrow();

        assertEquals(maximum, loaded.quantity().toCanonicalString());
        assertEquals(new BigInteger(maximum), loaded.quantity().atomicUnits());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        UPDATE token_operation SET quantity_atomic = 0
                        WHERE operation_id = :operationId
                        """).param("operationId", loaded.operationId().value()).update());
    }

    @Test
    void roundTripsTransitionsAttemptEvidenceAndAllFourFinalityHistories() {
        TokenOperation operation = accept(
                repository, PARTICIPANT, OperationKind.MINT, "aggregate-roundtrip", CANONICAL,
                () -> requested(OperationKind.MINT, TokenQuantity.parse("12.34", UNIT)))
                .operation();
        operation = transition(operation, OperationState.VALIDATED, 1);
        operation = transition(operation, OperationState.POLICY_PENDING, 2);
        operation = transition(operation, OperationState.APPROVAL_PENDING, 3);
        operation = transition(operation, OperationState.AUTHORIZED, 4);
        AttemptId initialAttemptId = new AttemptId(UUID.randomUUID());
        TokenOperation previous = operation;
        operation = operation.addInitialAttempt(
                operation.version(), initialAttemptId,
                new EvidenceRef("evidence:attempt"), START.plusSeconds(5));
        repository.save(operation, previous.version());
        operation = transition(operation, OperationState.SIGNING, 6);
        operation = transition(operation, OperationState.SUBMISSION_PENDING, 7);
        previous = operation;
        operation = operation.addFollowUpAttempt(
                operation.version(), new AttemptId(UUID.randomUUID()),
                new RetryAuthorization(
                        initialAttemptId,
                        RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                        "replacement-policy-v1",
                        new EvidenceRef("evidence:native-safe-replacement")),
                START.plusSeconds(8));
        repository.save(operation, previous.version());
        operation = transition(operation, OperationState.OBSERVING, 9);
        operation = finality(operation, FinalityType.LEGAL, FinalityStatus.PENDING, 10);
        operation = finality(
                operation, FinalityType.CUSTOMER_VISIBLE, FinalityStatus.REACHED, 11);
        operation = finality(operation, FinalityType.ACCOUNTING, FinalityStatus.REJECTED, 12);
        operation = finality(operation, FinalityType.BLOCKCHAIN, FinalityStatus.REACHED, 13);
        operation = transition(operation, OperationState.CHAIN_FINALITY_REACHED, 14);

        TokenOperation loaded = repository.findById(operation.operationId()).orElseThrow();

        assertAggregateEquals(operation, loaded);
        assertEquals(2, loaded.finalityHistory(FinalityType.BLOCKCHAIN).size());
        assertEquals(2, loaded.finalityHistory(FinalityType.LEGAL).size());
        assertEquals(2, loaded.finalityHistory(FinalityType.CUSTOMER_VISIBLE).size());
        assertEquals(2, loaded.finalityHistory(FinalityType.ACCOUNTING).size());
    }

    @Test
    void rejectsOptimisticVersionConflictWithoutAppendingHistory() {
        TokenOperation accepted = accept(
                repository, PARTICIPANT, OperationKind.MINT, "optimistic", CANONICAL,
                () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT)))
                .operation();
        TokenOperation validated = accepted.transition(
                accepted.version(), OperationState.VALIDATED, "test-worker", "validated",
                START.plusSeconds(1), List.of(new EvidenceRef("evidence:validated")));

        assertThrows(IllegalStateException.class,
                () -> repository.save(validated, accepted.version() + 1));
        assertEquals(1L, count("operation_transition"));
        assertEquals(OperationState.REQUESTED,
                repository.findById(accepted.operationId()).orElseThrow().state());
    }

    @Test
    void originalAcceptanceAtomicallyCreatesOneRequiredRecordSet() {
        IdempotencyKey rawKey = IdempotencyKey.of("sensitive-acceptance-key");
        TokenOperation proposed = requested(
                OperationKind.BURN, TokenQuantity.parse("1", UNIT));

        OperationAcceptance accepted = accept(
                repository, PARTICIPANT, OperationKind.BURN, rawKey.value(), CANONICAL,
                () -> proposed);

        assertFalse(accepted.replayed());
        assertEquals(1L, count("operation_idempotency"));
        assertEquals(1L, count("token_operation"));
        assertEquals(1L, count("operation_transition"));
        assertEquals(1L, count("operation_transition_evidence"));
        assertEquals(4L, count("operation_finality"));
        assertEquals(1L, count("operation_outbox"));
        String storedDigest = jdbc.sql("SELECT idempotency_key_digest FROM operation_idempotency")
                .query(String.class).single();
        assertEquals(rawKey.sha256(), storedDigest);
        assertNotEquals(rawKey.value(), storedDigest);
        String payload = jdbc.sql("SELECT payload::text FROM operation_outbox")
                .query(String.class).single();
        assertFalse(payload.contains(rawKey.value()));
        assertTrue(payload.contains(proposed.operationId().toString()));
        assertEquals("TokenOperationAccepted", jdbc.sql(
                        "SELECT event_type FROM operation_outbox")
                .query(String.class).single());
        assertEquals(1, jdbc.sql("SELECT event_version FROM operation_outbox")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("SELECT payload_schema_version FROM operation_outbox")
                .query(Integer.class).single());
        assertEquals("PENDING", jdbc.sql("SELECT status FROM operation_outbox")
                .query(String.class).single());
        assertTrue(jdbc.sql("SELECT created_at = available_at FROM operation_outbox")
                .query(Boolean.class).single());
    }

    @Test
    void sameKeyAndDigestReplaysWithoutAdditionalOperationOrOutbox() {
        OperationAcceptance first = accept(
                repository, PARTICIPANT, OperationKind.MINT, "replay", CANONICAL,
                () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT)));
        OperationAcceptance replay = accept(
                repository, PARTICIPANT, OperationKind.MINT, "replay", CANONICAL,
                () -> {
                    throw new AssertionError("committed replay must not create a proposal");
                });

        assertTrue(replay.replayed());
        assertEquals(first.operation().operationId(), replay.operation().operationId());
        assertEquals(1L, count("token_operation"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void sameKeyAndDifferentDigestConflictsWithoutAdditionalRows() {
        accept(repository, PARTICIPANT, OperationKind.MINT, "conflict", CANONICAL,
                () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT)));

        assertThrows(IdempotencyConflictException.class, () -> accept(
                repository, PARTICIPANT, OperationKind.MINT, "conflict", metadata('b'),
                () -> {
                    throw new AssertionError("committed conflict must not create a proposal");
                }));
        assertEquals(1L, count("token_operation"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void acceptanceForcesReadCommittedIsolationRegardlessOfPoolDefault() {
        try (HikariDataSource repeatableRead = dataSource("TRANSACTION_REPEATABLE_READ")) {
            JdbcClient transactionJdbc = JdbcClient.create(repeatableRead);
            PostgresOperationRepository target =
                    new PostgresOperationRepository(repeatableRead);

            accept(target, PARTICIPANT, OperationKind.MINT, "forced-read-committed",
                    CANONICAL, () -> {
                        assertEquals("read committed", transactionJdbc
                                .sql("SHOW transaction_isolation")
                                .query(String.class).single());
                        return requested(
                                OperationKind.MINT, TokenQuantity.parse("1", UNIT));
                    });
        }
    }

    @Test
    void parallelDuplicateAcceptanceCreatesOneOperationAndOneOutbox() throws Exception {
        PostgresOperationRepository firstRepository = new PostgresOperationRepository(dataSource);
        PostgresOperationRepository secondRepository = new PostgresOperationRepository(dataSource);
        CyclicBarrier bothObservedInitialMiss = new CyclicBarrier(2);
        List<Object> results = race(
                () -> accept(firstRepository, PARTICIPANT, OperationKind.MINT, "parallel-same",
                        CANONICAL,
                        afterBarrier(bothObservedInitialMiss, () -> requested(
                                OperationKind.MINT, TokenQuantity.parse("1", UNIT)))),
                () -> accept(secondRepository, PARTICIPANT, OperationKind.MINT, "parallel-same",
                        CANONICAL,
                        afterBarrier(bothObservedInitialMiss, () -> requested(
                                OperationKind.MINT, TokenQuantity.parse("1", UNIT)))));

        List<OperationAcceptance> acceptances = results.stream()
                .map(OperationAcceptance.class::cast).toList();
        assertEquals(1, acceptances.stream().filter(OperationAcceptance::replayed).count());
        assertEquals(1, acceptances.stream().filter(result -> !result.replayed()).count());
        assertEquals(acceptances.getFirst().operation().operationId(),
                acceptances.getLast().operation().operationId());
        assertEquals(1L, count("token_operation"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void parallelConflictingPayloadsProduceOneWinnerAndOneConflict() throws Exception {
        PostgresOperationRepository firstRepository = new PostgresOperationRepository(dataSource);
        PostgresOperationRepository secondRepository = new PostgresOperationRepository(dataSource);
        CyclicBarrier bothObservedInitialMiss = new CyclicBarrier(2);
        List<Object> results = race(
                () -> captureConflict(() -> accept(
                        firstRepository, PARTICIPANT, OperationKind.BURN, "parallel-conflict",
                        CANONICAL,
                        afterBarrier(bothObservedInitialMiss, () -> requested(
                                OperationKind.BURN, TokenQuantity.parse("1", UNIT))))),
                () -> captureConflict(() -> accept(
                        secondRepository, PARTICIPANT, OperationKind.BURN, "parallel-conflict",
                        metadata('b'),
                        afterBarrier(bothObservedInitialMiss, () -> requested(
                                OperationKind.BURN, TokenQuantity.parse("2", UNIT))))));

        assertEquals(1, results.stream().filter(OperationAcceptance.class::isInstance).count());
        assertEquals(1, results.stream().filter("conflict"::equals).count());
        assertEquals(1L, count("token_operation"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void forcedOutboxFailureRollsBackEveryAcceptanceRecord() {
        jdbc.sql("""
                CREATE FUNCTION reject_test_outbox() RETURNS trigger
                LANGUAGE plpgsql AS $$
                BEGIN
                    RAISE EXCEPTION 'forced test rollback';
                END;
                $$
                """).update();
        jdbc.sql("""
                CREATE TRIGGER reject_test_outbox_trigger
                BEFORE INSERT ON operation_outbox
                FOR EACH ROW EXECUTE FUNCTION reject_test_outbox()
                """).update();
        try {
            assertThrows(RuntimeException.class, () -> accept(
                    repository, PARTICIPANT, OperationKind.MINT, "forced-rollback", CANONICAL,
                    () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT))));
        } finally {
            jdbc.sql("DROP TRIGGER reject_test_outbox_trigger ON operation_outbox").update();
            jdbc.sql("DROP FUNCTION reject_test_outbox()").update();
        }

        assertEquals(0L, count("operation_idempotency"));
        assertEquals(0L, count("token_operation"));
        assertEquals(0L, count("operation_transition"));
        assertEquals(0L, count("operation_outbox"));
    }

    @Test
    void databaseUniquenessRejectsADuplicateAcceptanceEvent() {
        TokenOperation operation = accept(
                repository, PARTICIPANT, OperationKind.MINT, "event-unique", CANONICAL,
                () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT)))
                .operation();

        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        INSERT INTO operation_outbox (
                            event_id, operation_id, event_type, event_version,
                            payload_schema_version, payload, status, created_at, available_at)
                        SELECT :eventId, operation_id, event_type, event_version,
                               payload_schema_version, payload, status, created_at, available_at
                        FROM operation_outbox
                        WHERE operation_id = :operationId
                        """)
                .param("eventId", UUID.randomUUID())
                .param("operationId", operation.operationId().value())
                .update());
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void participantScopedLookupDoesNotDiscloseAnotherParticipantsOperation() {
        TokenOperation operation = accept(
                repository, PARTICIPANT, OperationKind.MINT, "participant-scope", CANONICAL,
                () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT)))
                .operation();

        assertTrue(repository.findById(operation.operationId(), PARTICIPANT).isPresent());
        assertTrue(repository.findById(operation.operationId(), OTHER_PARTICIPANT).isEmpty());
        assertTrue(repository.findById(
                new OperationId(UUID.randomUUID()), PARTICIPANT).isEmpty());
    }

    @Test
    void aFreshPoolAndRepositoryReadTheCommittedOperationAfterTheOriginalCloses() {
        HikariDataSource originalPool = dataSource();
        OperationId operationId;
        try {
            PostgresOperationRepository original = new PostgresOperationRepository(originalPool);
            operationId = accept(
                    original, PARTICIPANT, OperationKind.MINT, "restart-read", CANONICAL,
                    () -> requested(OperationKind.MINT, TokenQuantity.parse("1", UNIT)))
                    .operation().operationId();
        } finally {
            originalPool.close();
        }

        try (HikariDataSource restartedPool = dataSource()) {
            PostgresOperationRepository restarted =
                    new PostgresOperationRepository(restartedPool);
            TokenOperation loaded = restarted.findById(operationId, PARTICIPANT).orElseThrow();
            assertEquals(operationId, loaded.operationId());
            assertEquals(OperationState.REQUESTED, loaded.state());
        }
    }

    private static TokenOperation transition(
            TokenOperation operation, OperationState target, long seconds) {
        TokenOperation changed = operation.transition(
                operation.version(), target, "test-worker", "advance",
                START.plusSeconds(seconds),
                List.of(new EvidenceRef("evidence:transition:" + target.name().toLowerCase())));
        repository.save(changed, operation.version());
        return changed;
    }

    private static TokenOperation finality(
            TokenOperation operation,
            FinalityType type,
            FinalityStatus status,
            long seconds) {
        TokenOperation changed = operation.recordFinality(
                operation.version(),
                FinalityRecord.assessed(
                        type, status, "test-authority", "test-policy-v1",
                        START.plusSeconds(seconds),
                        List.of(new EvidenceRef(
                                "evidence:finality:" + type.name().toLowerCase()))));
        repository.save(changed, operation.version());
        return changed;
    }

    private static OperationAcceptance accept(
            PostgresOperationRepository target,
            ParticipantScope participant,
            OperationKind kind,
            String key,
            CanonicalCommandMetadata canonical,
            java.util.function.Supplier<TokenOperation> operation) {
        IdempotencyKey idempotencyKey = IdempotencyKey.of(key);
        return target.accept(
                scope(participant, kind), idempotencyKey, canonical, () -> {
                    TokenOperation proposed = operation.get();
                    return TokenOperation.requested(
                            proposed.operationId(),
                            new OperationAcceptanceContext(
                                    participant.tenantId(), participant.participantId(),
                                    IdempotencyResource.TOKEN_OPERATION.name(),
                                    idempotencyKey.sha256(), 1,
                                    canonical.canonicalizationVersion(),
                                    canonical.digest().value(),
                                    proposed.acceptanceContext().businessCorrelation()),
                            kind, proposed.quantity(), proposed.createdAt(),
                            proposed.evidenceReferences().getFirst());
                });
    }

    private static IdempotencyScope scope(
            ParticipantScope participant, OperationKind kind) {
        return new IdempotencyScope(
                participant, IdempotencyResource.TOKEN_OPERATION, kind);
    }

    private static TokenOperation requested(OperationKind kind, TokenQuantity quantity) {
        IdempotencyKey key = IdempotencyKey.of("proposal-key");
        return TokenOperation.requested(
                new OperationId(UUID.randomUUID()),
                new OperationAcceptanceContext(
                        PARTICIPANT.tenantId(), PARTICIPANT.participantId(),
                        IdempotencyResource.TOKEN_OPERATION.name(), key.sha256(), 1, 1,
                        CANONICAL.digest().value(), "corr-001"),
                kind, quantity, START, new EvidenceRef("evidence:acceptance"));
    }

    private static CanonicalCommandMetadata metadata(char value) {
        return new CanonicalCommandMetadata(
                1, new CommandDigest(String.valueOf(value).repeat(64)));
    }

    private static long count(String table) {
        if (!List.of(
                "operation_idempotency", "token_operation", "operation_transition",
                "operation_transition_evidence", "operation_attempt", "operation_finality",
                "operation_finality_evidence", "operation_outbox").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static HikariDataSource dataSource() {
        return dataSource(null);
    }

    private static HikariDataSource dataSource(String transactionIsolation) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5_000);
        if (transactionIsolation != null) {
            config.setTransactionIsolation(transactionIsolation);
        }
        return new HikariDataSource(config);
    }

    private static List<Object> race(
            java.util.concurrent.Callable<Object> first,
            java.util.concurrent.Callable<Object> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<Object>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> awaitRace(ready, start, first)));
            futures.add(executor.submit(() -> awaitRace(ready, start, second)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    private static Object awaitRace(
            CountDownLatch ready,
            CountDownLatch start,
            java.util.concurrent.Callable<Object> operation) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("race did not start");
        }
        return operation.call();
    }

    private static Object captureConflict(
            java.util.concurrent.Callable<OperationAcceptance> operation) throws Exception {
        try {
            return operation.call();
        } catch (IdempotencyConflictException expected) {
            return "conflict";
        }
    }

    private static java.util.function.Supplier<TokenOperation> afterBarrier(
            CyclicBarrier barrier,
            java.util.function.Supplier<TokenOperation> operation) {
        return () -> {
            try {
                barrier.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("acceptance race was interrupted", interrupted);
            } catch (Exception failure) {
                throw new IllegalStateException("acceptance race did not contend", failure);
            }
            return operation.get();
        };
    }

    private static void assertAggregateEquals(
            TokenOperation expected, TokenOperation actual) {
        assertEquals(expected.operationId(), actual.operationId());
        assertEquals(expected.acceptanceContext(), actual.acceptanceContext());
        assertEquals(expected.kind(), actual.kind());
        assertEquals(expected.quantity(), actual.quantity());
        assertEquals(expected.state(), actual.state());
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.transitions(), actual.transitions());
        for (FinalityType type : FinalityType.values()) {
            assertEquals(expected.finalityHistory(type), actual.finalityHistory(type));
        }
        assertEquals(expected.evidenceReferences(), actual.evidenceReferences());
    }
}
