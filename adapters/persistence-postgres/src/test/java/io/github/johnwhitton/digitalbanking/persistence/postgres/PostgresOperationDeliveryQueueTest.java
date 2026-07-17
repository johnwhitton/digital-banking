package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.CommandDigest;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyResource;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyScope;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryRetryPolicy;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryWorker;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresOperationDeliveryQueueTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00.123456Z");
    private static final Duration LEASE = Duration.ofSeconds(10);
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final AssetUnit UNIT = new AssetUnit(
            "REFERENCE_ASSET", "REFERENCE_UNIT", 1, 2,
            new BigInteger("999999999999999999"));
    private static final CanonicalCommandMetadata CANONICAL = new CanonicalCommandMetadata(
            1, new CommandDigest("a".repeat(64)));

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresOperationRepository operations;
    private static PostgresOperationDeliveryQueue queue;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("digital_banking")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        operations = new PostgresOperationRepository(dataSource);
        queue = new PostgresOperationDeliveryQueue(dataSource);
        jdbc.sql("CREATE TABLE test_delivery_inbox (delivery_id UUID PRIMARY KEY)").update();
        jdbc.sql("""
                CREATE TABLE test_delivery_effect (
                    delivery_id UUID PRIMARY KEY,
                    operation_id UUID NOT NULL)
                """).update();
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
    void clearData() {
        jdbc.sql("DELETE FROM test_delivery_effect").update();
        jdbc.sql("DELETE FROM test_delivery_inbox").update();
        jdbc.sql("DELETE FROM operation_delivery_attempt").update();
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
    void pendingWorkIsClaimedOnceByOneOfTwoConcurrentWorkers() throws Exception {
        accept("single-claim");

        List<OperationDeliveryQueue.ClaimBatch> batches = concurrent(
                () -> new PostgresOperationDeliveryQueue(dataSource)
                        .claim("worker-a", NOW, LEASE, 1),
                () -> new PostgresOperationDeliveryQueue(dataSource)
                        .claim("worker-b", NOW, LEASE, 1));

        assertEquals(1, batches.stream().mapToInt(batch -> batch.deliveries().size()).sum());
        assertEquals(1, count("operation_delivery_attempt"));
        assertEquals("IN_PROGRESS", outboxStatus());
    }

    @Test
    void claimCommitsAndReleasesItsDatabaseLockBeforeHandlerExecutes() {
        OperationId operationId = accept("commit-before-handler");
        AtomicReference<String> observedStatus = new AtomicReference<>();
        OperationDeliveryWorker worker = worker(queue, delivery -> {
            observedStatus.set(outboxStatus());
            TransactionTemplate transaction = transaction(dataSource);
            transaction.executeWithoutResult(ignored -> jdbc.sql("""
                    SELECT event_id
                    FROM operation_outbox
                    WHERE operation_id = :operationId
                    FOR UPDATE NOWAIT
                    """).param("operationId", operationId.value()).query(UUID.class).single());
            return DeliveryOutcome.delivered();
        }, NOW, new DeliveryRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4)));

        worker.poll();

        assertEquals("IN_PROGRESS", observedStatus.get());
        assertEquals("DELIVERED", outboxStatus());
    }

    @Test
    void successfulDeliveryIsAcknowledgedAndNeverClaimedAgain() {
        accept("acknowledged");
        AtomicInteger calls = new AtomicInteger();
        OperationDeliveryWorker worker = worker(queue, ignored -> {
            calls.incrementAndGet();
            return DeliveryOutcome.delivered();
        }, NOW, new DeliveryRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4)));

        assertEquals(1, worker.poll().delivered());
        assertEquals(0, worker.poll().claimed());
        assertEquals(1, calls.get());
        assertEquals("DELIVERED", outboxStatus());
    }

    @Test
    void expiredLeaseIsRecoveredAfterProcessDeathBeforeHandlerInvocation() {
        accept("expired-lease");
        OperationDelivery first = queue.claim("dead-worker", NOW, LEASE, 1)
                .deliveries().getFirst();

        OperationDeliveryQueue.ClaimBatch recovered = queue.claim(
                "recovery-worker", NOW.plusSeconds(11), LEASE, 1);

        OperationDelivery second = recovered.deliveries().getFirst();
        assertEquals(first.deliveryId(), second.deliveryId());
        assertNotEquals(first.leaseId(), second.leaseId());
        assertEquals(2, second.attemptNumber());
        assertEquals(1, recovered.recoveredLeases());
        assertEquals("LEASE_EXPIRED", attemptOutcome(1));
    }

    @Test
    void postHandlerPreAcknowledgementFailureRedeliversWithoutRepeatingEffect() {
        accept("post-handler-pre-ack");
        OperationDeliveryHandler handler = transactionalDeduplicatingHandler();
        OperationDeliveryQueue lostAcknowledgement = new DelegatingQueue(queue) {
            @Override
            public LeaseUpdateResult acknowledge(
                    OperationDelivery delivery,
                    DeliveryOutcome outcome,
                    Instant completedAt) {
                return LeaseUpdateResult.STALE_LEASE;
            }
        };

        worker(lostAcknowledgement, handler, NOW,
                new DeliveryRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4))).poll();
        var redelivery = worker(queue, handler, NOW.plusSeconds(11),
                new DeliveryRetryPolicy(3, Duration.ofSeconds(1), Duration.ofSeconds(4))).poll();

        assertEquals(1, redelivery.duplicates());
        assertEquals(1, count("test_delivery_inbox"));
        assertEquals(1, count("test_delivery_effect"));
        assertEquals("DELIVERED", outboxStatus());
        assertEquals("DUPLICATE", attemptOutcome(2));
    }

    @Test
    void retryScheduleIsDurableAndWorkIsIneligibleUntilItsTime() {
        accept("durable-retry");
        OperationDelivery claim = queue.claim("worker-a", NOW, LEASE, 1)
                .deliveries().getFirst();
        Instant retryAt = NOW.plusSeconds(30);

        assertEquals(OperationDeliveryQueue.LeaseUpdateResult.UPDATED,
                queue.reschedule(
                        claim, DeliveryOutcome.retryableFailure("dependency-unavailable"),
                        retryAt, NOW.plusSeconds(1)));
        assertEquals(0, queue.claim("early", retryAt.minusNanos(1), LEASE, 1)
                .deliveries().size());
        assertEquals(1, queue.claim("on-time", retryAt, LEASE, 1)
                .deliveries().size());
        assertEquals(retryAt, jdbc.sql("SELECT available_at FROM operation_outbox")
                .query(OffsetDateTime.class).single().toInstant());
    }

    @Test
    void exhaustedAttemptPreservesRowAndRoutesItToManualReview() {
        accept("exhausted");
        var result = worker(
                queue,
                ignored -> DeliveryOutcome.retryableFailure("dependency-unavailable"),
                NOW,
                new DeliveryRetryPolicy(1, Duration.ofSeconds(1), Duration.ofSeconds(1)))
                .poll();

        assertEquals(1, result.exhausted());
        assertEquals("MANUAL_REVIEW", outboxStatus());
        assertEquals("RETRYABLE_NO_EFFECT", attemptOutcome(1));
        assertEquals("ATTEMPTS_EXHAUSTED", jdbc.sql("""
                SELECT manual_review_reason
                FROM operation_delivery_attempt
                WHERE attempt_number = 1
                """).query(String.class).single());
        assertEquals(1, count("operation_outbox"));
    }

    @Test
    void replacedLeaseRejectsAcknowledgementFromStaleOwner() {
        accept("stale-owner");
        OperationDelivery stale = queue.claim("worker-a", NOW, LEASE, 1)
                .deliveries().getFirst();
        OperationDelivery replacement = queue.claim(
                "worker-b", NOW.plusSeconds(11), LEASE, 1).deliveries().getFirst();

        assertEquals(OperationDeliveryQueue.LeaseUpdateResult.STALE_LEASE,
                queue.acknowledge(stale, DeliveryOutcome.delivered(), NOW.plusSeconds(12)));
        assertEquals(replacement.leaseId(), jdbc.sql("SELECT lease_id FROM operation_outbox")
                .query(UUID.class).single());
        assertEquals("IN_PROGRESS", outboxStatus());
    }

    @Test
    void differentOperationsCanBeClaimedConcurrently() throws Exception {
        accept("parallel-a");
        accept("parallel-b");

        List<OperationDeliveryQueue.ClaimBatch> batches = concurrent(
                () -> new PostgresOperationDeliveryQueue(dataSource)
                        .claim("worker-a", NOW, LEASE, 1),
                () -> new PostgresOperationDeliveryQueue(dataSource)
                        .claim("worker-b", NOW, LEASE, 1));

        assertEquals(2, batches.stream().mapToInt(batch -> batch.deliveries().size()).sum());
        assertEquals(2, batches.stream()
                .flatMap(batch -> batch.deliveries().stream())
                .map(OperationDelivery::operationId).distinct().count());
    }

    @Test
    void laterEventForSameOperationWaitsForConcurrentEarlierClaim() throws Exception {
        OperationId operationId = accept("ordered-events");
        UUID secondEvent = UUID.randomUUID();
        jdbc.sql("""
                INSERT INTO operation_outbox (
                    event_id, operation_id, event_type, event_version,
                    payload_schema_version, payload, status, created_at, available_at,
                    updated_at)
                VALUES (
                    :eventId, :operationId, 'TokenOperationAccepted', 2,
                    1, '{}'::jsonb, 'PENDING', :createdAt, :availableAt, :updatedAt)
                """)
                .param("eventId", secondEvent)
                .param("operationId", operationId.value())
                .param("createdAt", NOW.plusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("availableAt", NOW.plusSeconds(1).atOffset(ZoneOffset.UTC))
                .param("updatedAt", NOW.plusSeconds(1).atOffset(ZoneOffset.UTC))
                .update();

        List<OperationDeliveryQueue.ClaimBatch> batches = concurrent(
                () -> new PostgresOperationDeliveryQueue(dataSource)
                        .claim("worker-a", NOW.plusSeconds(2), LEASE, 1),
                () -> new PostgresOperationDeliveryQueue(dataSource)
                        .claim("worker-b", NOW.plusSeconds(2), LEASE, 1));
        assertEquals(1, batches.stream().mapToInt(batch -> batch.deliveries().size()).sum());
        OperationDelivery first = batches.stream()
                .flatMap(batch -> batch.deliveries().stream()).findFirst().orElseThrow();
        assertNotEquals(secondEvent, first.deliveryId());
        queue.acknowledge(first, DeliveryOutcome.delivered(), NOW.plusSeconds(3));

        assertEquals(secondEvent, queue.claim("worker-b", NOW.plusSeconds(3), LEASE, 1)
                .deliveries().getFirst().deliveryId());
    }

    @Test
    void claimRollbackLeavesWorkRecoverable() {
        accept("claim-rollback");
        jdbc.sql("""
                CREATE FUNCTION fail_delivery_claim() RETURNS trigger AS $$
                BEGIN RAISE EXCEPTION 'forced delivery claim rollback'; END;
                $$ LANGUAGE plpgsql
                """).update();
        jdbc.sql("""
                CREATE TRIGGER fail_delivery_claim
                BEFORE INSERT ON operation_delivery_attempt
                FOR EACH ROW EXECUTE FUNCTION fail_delivery_claim()
                """).update();
        try {
            assertThrows(RuntimeException.class,
                    () -> queue.claim("worker-a", NOW, LEASE, 1));
        } finally {
            jdbc.sql("DROP TRIGGER fail_delivery_claim ON operation_delivery_attempt").update();
            jdbc.sql("DROP FUNCTION fail_delivery_claim()").update();
        }

        assertEquals("PENDING", outboxStatus());
        assertEquals(0, count("operation_delivery_attempt"));
        assertEquals(1, queue.claim("worker-b", NOW, LEASE, 1).deliveries().size());
    }

    @Test
    void freshProcessPoolRecoversLeaseAndPreservesHistory() {
        accept("restart-recovery");
        OperationDelivery original;
        try (HikariDataSource firstPool = dataSource()) {
            original = new PostgresOperationDeliveryQueue(firstPool)
                    .claim("first-process", NOW, LEASE, 1).deliveries().getFirst();
        }

        try (HikariDataSource restartedPool = dataSource()) {
            OperationDelivery recovered = new PostgresOperationDeliveryQueue(restartedPool)
                    .claim("restarted-process", NOW.plusSeconds(11), LEASE, 1)
                    .deliveries().getFirst();
            assertEquals(original.deliveryId(), recovered.deliveryId());
            assertEquals(2, recovered.attemptNumber());
        }
        assertEquals("LEASE_EXPIRED", attemptOutcome(1));
        assertEquals(2, count("operation_delivery_attempt"));
    }

    @Test
    void freshProcessPoolsPreserveRetryAndAcknowledgementState() {
        accept("restart-retry-ack");
        Instant retryAt = NOW.plusSeconds(30);
        try (HikariDataSource firstPool = dataSource()) {
            PostgresOperationDeliveryQueue firstQueue =
                    new PostgresOperationDeliveryQueue(firstPool);
            OperationDelivery original = firstQueue
                    .claim("first-process", NOW, LEASE, 1).deliveries().getFirst();
            assertEquals(OperationDeliveryQueue.LeaseUpdateResult.UPDATED,
                    firstQueue.reschedule(
                            original,
                            DeliveryOutcome.retryableFailure("dependency-unavailable"),
                            retryAt,
                            NOW.plusSeconds(1)));
        }

        try (HikariDataSource restartedPool = dataSource()) {
            PostgresOperationDeliveryQueue restartedQueue =
                    new PostgresOperationDeliveryQueue(restartedPool);
            assertEquals(0, restartedQueue
                    .claim("restarted-process", retryAt.minusNanos(1), LEASE, 1)
                    .deliveries().size());
            OperationDelivery retry = restartedQueue
                    .claim("restarted-process", retryAt, LEASE, 1)
                    .deliveries().getFirst();
            assertEquals(OperationDeliveryQueue.LeaseUpdateResult.UPDATED,
                    restartedQueue.acknowledge(
                            retry, DeliveryOutcome.delivered(), retryAt.plusSeconds(1)));
        }

        try (HikariDataSource finalPool = dataSource()) {
            assertEquals(0, new PostgresOperationDeliveryQueue(finalPool)
                    .claim("final-process", retryAt.plusSeconds(20), LEASE, 1)
                    .deliveries().size());
            assertEquals("DELIVERED", JdbcClient.create(finalPool)
                    .sql("SELECT status FROM operation_outbox")
                    .query(String.class).single());
        }
        assertEquals("RETRYABLE_NO_EFFECT", attemptOutcome(1));
        assertEquals("DELIVERED", attemptOutcome(2));
    }

    @Test
    void measurementsExposeOnlyLowCardinalityQueueEvidence() {
        accept("metrics-eligible");
        accept("metrics-leased");
        queue.claim("worker-a", NOW, LEASE, 1);

        OperationDeliveryQueue.QueueMeasurements measurements = queue.measurements(NOW);

        assertEquals(1, measurements.eligibleWork());
        assertEquals(1, measurements.activeLeases());
        assertEquals(Duration.ZERO, measurements.oldestEligibleAge());
    }

    private static OperationDeliveryWorker worker(
            OperationDeliveryQueue target,
            OperationDeliveryHandler handler,
            Instant now,
            DeliveryRetryPolicy policy) {
        return new OperationDeliveryWorker(
                target, handler, (delivery, failure) -> { }, () -> now,
                policy, LEASE, "worker-test", 10);
    }

    private static OperationDeliveryHandler transactionalDeduplicatingHandler() {
        TransactionTemplate transaction = transaction(dataSource);
        return delivery -> transaction.execute(status -> {
            int inserted = jdbc.sql("""
                    INSERT INTO test_delivery_inbox (delivery_id)
                    VALUES (:deliveryId)
                    ON CONFLICT DO NOTHING
                    """).param("deliveryId", delivery.deliveryId()).update();
            if (inserted == 0) {
                return DeliveryOutcome.duplicate();
            }
            jdbc.sql("""
                    INSERT INTO test_delivery_effect (delivery_id, operation_id)
                    VALUES (:deliveryId, :operationId)
                    """)
                    .param("deliveryId", delivery.deliveryId())
                    .param("operationId", delivery.operationId().value())
                    .update();
            return DeliveryOutcome.delivered();
        });
    }

    private static OperationId accept(String keyValue) {
        IdempotencyKey key = IdempotencyKey.of(keyValue);
        OperationAcceptance accepted = operations.accept(
                new IdempotencyScope(
                        PARTICIPANT, IdempotencyResource.TOKEN_OPERATION, OperationKind.MINT),
                key,
                CANONICAL,
                () -> TokenOperation.requested(
                        new OperationId(UUID.randomUUID()),
                        new OperationAcceptanceContext(
                                PARTICIPANT.tenantId(), PARTICIPANT.participantId(),
                                IdempotencyResource.TOKEN_OPERATION.name(), key.sha256(), 1,
                                CANONICAL.canonicalizationVersion(), CANONICAL.digest().value(),
                                "corr-" + keyValue),
                        OperationKind.MINT,
                        TokenQuantity.parse("1", UNIT),
                        NOW,
                        new EvidenceRef("evidence:acceptance")));
        return accepted.operation().operationId();
    }

    private static String outboxStatus() {
        return jdbc.sql("SELECT status FROM operation_outbox ORDER BY created_at LIMIT 1")
                .query(String.class).single();
    }

    private static String attemptOutcome(int attemptNumber) {
        return jdbc.sql("""
                SELECT outcome
                FROM operation_delivery_attempt
                WHERE attempt_number = :attemptNumber
                """).param("attemptNumber", attemptNumber).query(String.class).single();
    }

    private static long count(String table) {
        if (!List.of(
                "operation_outbox", "operation_delivery_attempt",
                "test_delivery_inbox", "test_delivery_effect").contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static TransactionTemplate transaction(HikariDataSource target) {
        TransactionTemplate transaction = new TransactionTemplate(
                new DataSourceTransactionManager(target));
        transaction.setIsolationLevel(TransactionTemplate.ISOLATION_READ_COMMITTED);
        return transaction;
    }

    private static HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    private static <T> List<T> concurrent(
            java.util.concurrent.Callable<T> first,
            java.util.concurrent.Callable<T> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<T>> futures = new ArrayList<>();
            futures.add(executor.submit(() -> await(ready, start, first)));
            futures.add(executor.submit(() -> await(ready, start, second)));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            return List.of(
                    futures.get(0).get(10, TimeUnit.SECONDS),
                    futures.get(1).get(10, TimeUnit.SECONDS));
        }
    }

    private static <T> T await(
            CountDownLatch ready,
            CountDownLatch start,
            java.util.concurrent.Callable<T> operation) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("concurrent claim did not start");
        }
        return operation.call();
    }

    private abstract static class DelegatingQueue implements OperationDeliveryQueue {
        private final OperationDeliveryQueue delegate;

        private DelegatingQueue(OperationDeliveryQueue delegate) {
            this.delegate = delegate;
        }

        @Override
        public ClaimBatch claim(
                String workerId, Instant now, Duration leaseDuration, int limit) {
            return delegate.claim(workerId, now, leaseDuration, limit);
        }

        @Override
        public LeaseUpdateResult acknowledge(
                OperationDelivery delivery, DeliveryOutcome outcome, Instant completedAt) {
            return delegate.acknowledge(delivery, outcome, completedAt);
        }

        @Override
        public LeaseUpdateResult reschedule(
                OperationDelivery delivery,
                DeliveryOutcome outcome,
                Instant nextAttemptAt,
                Instant recordedAt) {
            return delegate.reschedule(delivery, outcome, nextAttemptAt, recordedAt);
        }

        @Override
        public LeaseUpdateResult moveToManualReview(
                OperationDelivery delivery,
                ManualReviewReason reason,
                DeliveryOutcome outcome,
                Instant recordedAt) {
            return delegate.moveToManualReview(delivery, reason, outcome, recordedAt);
        }

        @Override
        public QueueMeasurements measurements(Instant now) {
            return delegate.measurements(now);
        }
    }
}
