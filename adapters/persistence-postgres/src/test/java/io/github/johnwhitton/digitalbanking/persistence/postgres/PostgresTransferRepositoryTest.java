package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.TransferAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.CommandDigest;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferStatus;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresTransferRepositoryTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant NOW = Instant.parse("2026-07-17T18:00:00.123456Z");
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final ParticipantScope OTHER =
            new ParticipantScope("tenant-a", "participant-b");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 4, 2, new BigInteger("1000000000000"));

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresTransferRepository repository;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("digital_banking_transfer")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new PostgresTransferRepository(dataSource);
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
        jdbc.sql("DELETE FROM operation_delivery_attempt").update();
        jdbc.sql("DELETE FROM transfer_handler_inbox").update();
        jdbc.sql("DELETE FROM operation_outbox").update();
        jdbc.sql("DELETE FROM transfer_finality_evidence").update();
        jdbc.sql("DELETE FROM transfer_finality").update();
        jdbc.sql("DELETE FROM transfer_transition_evidence").update();
        jdbc.sql("DELETE FROM transfer_transition").update();
        jdbc.sql("DELETE FROM transfer_effect_evidence").update();
        jdbc.sql("DELETE FROM transfer_effect").update();
        jdbc.sql("DELETE FROM transfer_idempotency").update();
        jdbc.sql("DELETE FROM banking_transfer").update();
    }

    @Test
    void v3MigratesAndAcceptanceCommitsCompleteTransferAtomically() {
        TransferAcceptance accepted = accept(
                repository, PARTICIPANT, "transfer-key", metadata('a'),
                () -> transfer(1, PARTICIPANT, "transfer-key", metadata('a')));

        assertFalse(accepted.replayed());
        assertEquals(6, jdbc.sql("SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single());
        assertEquals(1L, count("banking_transfer"));
        assertEquals(1L, count("transfer_idempotency"));
        assertEquals(5L, count("transfer_effect"));
        assertEquals(1L, count("transfer_transition"));
        assertEquals(1L, count("transfer_transition_evidence"));
        assertEquals(4L, count("transfer_finality"));
        assertEquals(1L, count("operation_outbox"));
        assertEquals("TransferAccepted", jdbc.sql(
                "SELECT event_type FROM operation_outbox").query(String.class).single());
        assertEquals("PENDING", jdbc.sql(
                "SELECT status FROM operation_outbox").query(String.class).single());
        assertFalse(jdbc.sql("SELECT payload::text FROM operation_outbox")
                .query(String.class).single().contains("transfer-key"));
    }

    @Test
    void exactReplaySkipsFactoryConflictCreatesNothingAndScopeIsHidden() {
        TransferAcceptance first = accept(
                repository, PARTICIPANT, "replay", metadata('a'),
                () -> transfer(2, PARTICIPANT, "replay", metadata('a')));
        TransferAcceptance replay = accept(
                repository, PARTICIPANT, "replay", metadata('a'),
                () -> { throw new AssertionError("replay must not resolve again"); });

        assertTrue(replay.replayed());
        assertEquals(first.transfer(), replay.transfer());
        assertThrows(IdempotencyConflictException.class, () -> accept(
                repository, PARTICIPANT, "replay", metadata('b'),
                () -> transfer(3, PARTICIPANT, "replay", metadata('b'))));
        assertEquals(Optional.empty(), repository.findById(
                first.transfer().transferId(), OTHER));
        try (HikariDataSource restarted = newDataSource()) {
            assertEquals(first.transfer(), new PostgresTransferRepository(restarted)
                    .findById(first.transfer().transferId(), PARTICIPANT).orElseThrow());
        }
        jdbc.sql("""
                        INSERT INTO transfer_finality (
                            transfer_id, finality_type, history_order, finality_status,
                            authority, policy_version, updated_at)
                        VALUES (
                            :transferId, 'BLOCKCHAIN', 1, 'PENDING',
                            'independent-observer', 'observer-v1', :updatedAt)
                        """)
                .param("transferId", first.transfer().transferId().value())
                .param("updatedAt", NOW.plusSeconds(1).atOffset(java.time.ZoneOffset.UTC))
                .update();
        jdbc.sql("""
                        INSERT INTO transfer_finality_evidence (
                            transfer_id, finality_type, history_order,
                            evidence_order, evidence_ref)
                        VALUES (
                            :transferId, 'BLOCKCHAIN', 1, 0,
                            'internal:blockchain-observation:pending')
                        """)
                .param("transferId", first.transfer().transferId().value()).update();
        try (HikariDataSource restarted = newDataSource()) {
            Transfer reconstructed = new PostgresTransferRepository(restarted)
                    .findById(first.transfer().transferId(), PARTICIPANT).orElseThrow();
            assertEquals(FinalityStatus.PENDING,
                    reconstructed.finalityHistory(FinalityType.BLOCKCHAIN)
                            .getLast().status());
            assertEquals("internal:blockchain-observation:pending",
                    reconstructed.finalityHistory(FinalityType.BLOCKCHAIN)
                            .getLast().evidenceRefs().getFirst().value());
        }
        assertEquals(1L, count("banking_transfer"));
        assertEquals(5L, count("transfer_effect"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void concurrentDuplicateAcceptanceSelectsOneDurableWinner() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<TransferAcceptance> first = executor.submit(() -> accept(
                    new PostgresTransferRepository(dataSource), PARTICIPANT,
                    "parallel", metadata('c'),
                    () -> afterBarrier(barrier,
                            transfer(10, PARTICIPANT, "parallel", metadata('c')))));
            Future<TransferAcceptance> second = executor.submit(() -> accept(
                    new PostgresTransferRepository(dataSource), PARTICIPANT,
                    "parallel", metadata('c'),
                    () -> afterBarrier(barrier,
                            transfer(20, PARTICIPANT, "parallel", metadata('c')))));

            TransferAcceptance one = first.get(10, TimeUnit.SECONDS);
            TransferAcceptance two = second.get(10, TimeUnit.SECONDS);
            assertEquals(one.transfer().transferId(), two.transfer().transferId());
            assertEquals(1, (one.replayed() ? 1 : 0) + (two.replayed() ? 1 : 0));
        }
        assertEquals(1L, count("banking_transfer"));
        assertEquals(5L, count("transfer_effect"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void concurrentConflictingAcceptanceCommitsOneCommandAndRejectsTheOther()
            throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<RaceOutcome> first = executor.submit(() -> raceAccept(
                    barrier, "parallel-conflict", metadata('a'), 50));
            Future<RaceOutcome> second = executor.submit(() -> raceAccept(
                    barrier, "parallel-conflict", metadata('b'), 60));

            List<RaceOutcome> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter(RaceOutcome::accepted).count());
            assertEquals(1L, outcomes.stream().filter(RaceOutcome::conflict).count());
        }
        assertEquals(1L, count("banking_transfer"));
        assertEquals(5L, count("transfer_effect"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void deliveryClaimAndInboxPreparationAreCompatibleAndIdempotent() {
        Transfer transfer = accept(
                repository, PARTICIPANT, "delivery", metadata('d'),
                () -> transfer(30, PARTICIPANT, "delivery", metadata('d'))).transfer();
        PostgresOperationDeliveryQueue queue = new PostgresOperationDeliveryQueue(dataSource);
        OperationDelivery claimed = queue.claim(
                "transfer-worker", NOW, Duration.ofSeconds(30), 1)
                .deliveries().getFirst();

        assertEquals("TransferAccepted", claimed.eventType());
        assertEquals(transfer.transferId().value(), claimed.aggregateId());
        assertEquals(TransferRepository.PreparationResult.APPLIED,
                repository.prepareFirstWithdrawal(
                        claimed.deliveryId(), transfer.transferId(),
                        transitionId(100), NOW.plusSeconds(1)));
        assertEquals(TransferRepository.PreparationResult.DUPLICATE,
                new PostgresTransferRepository(dataSource).prepareFirstWithdrawal(
                        claimed.deliveryId(), transfer.transferId(),
                        transitionId(101), NOW.plusSeconds(2)));
        assertThrows(IllegalStateException.class,
                () -> repository.prepareFirstWithdrawal(
                        new UUID(20, 99), transfer.transferId(),
                        transitionId(102), NOW.plusSeconds(3)));

        Transfer loaded = new PostgresTransferRepository(dataSource)
                .findById(transfer.transferId(), PARTICIPANT).orElseThrow();
        assertEquals(TransferStatus.IN_PROGRESS, loaded.status());
        assertEquals(1, loaded.version());
        assertEquals(TransferEffect.Status.PREPARED, loaded.effects().getFirst().status());
        assertEquals(TransferEffect.Status.PLANNED, loaded.effects().get(1).status());
        assertEquals(1L, count("transfer_handler_inbox"));
        assertEquals(2L, count("transfer_transition"));
    }

    @Test
    void concurrentDuplicateDeliveryProducesOnePreparationAndOneDurableDuplicate()
            throws Exception {
        Transfer transfer = accept(
                repository, PARTICIPANT, "concurrent-delivery", metadata('d'),
                () -> transfer(31, PARTICIPANT, "concurrent-delivery", metadata('d')))
                .transfer();
        UUID deliveryId = jdbc.sql("SELECT event_id FROM operation_outbox")
                .query(UUID.class).single();
        CyclicBarrier barrier = new CyclicBarrier(2);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<TransferRepository.PreparationResult> first = executor.submit(() ->
                    prepareAfterBarrier(
                            barrier, deliveryId, transfer.transferId(), transitionId(110)));
            Future<TransferRepository.PreparationResult> second = executor.submit(() ->
                    prepareAfterBarrier(
                            barrier, deliveryId, transfer.transferId(), transitionId(111)));
            List<TransferRepository.PreparationResult> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));

            assertEquals(1L, outcomes.stream()
                    .filter(value -> value == TransferRepository.PreparationResult.APPLIED)
                    .count());
            assertEquals(1L, outcomes.stream()
                    .filter(value -> value == TransferRepository.PreparationResult.DUPLICATE)
                    .count());
        }
        assertEquals(1L, count("transfer_handler_inbox"));
        assertEquals(2L, count("transfer_transition"));
        assertEquals(1L, count("transfer_effect_evidence"));
    }

    @Test
    void deliveryIdentityCannotPrepareAnotherTransfer() {
        Transfer first = accept(
                repository, PARTICIPANT, "delivery-owner-a", metadata('a'),
                () -> transfer(32, PARTICIPANT, "delivery-owner-a", metadata('a')))
                .transfer();
        Transfer second = accept(
                repository, PARTICIPANT, "delivery-owner-b", metadata('b'),
                () -> transfer(33, PARTICIPANT, "delivery-owner-b", metadata('b')))
                .transfer();
        UUID firstDelivery = jdbc.sql("""
                        SELECT event_id FROM operation_outbox
                        WHERE transfer_id = :transferId
                        """)
                .param("transferId", first.transferId().value())
                .query(UUID.class).single();

        assertThrows(IllegalStateException.class,
                () -> repository.prepareFirstWithdrawal(
                        firstDelivery, second.transferId(), transitionId(120),
                        NOW.plusSeconds(1)));

        Transfer unchanged = repository.findById(second.transferId(), PARTICIPANT)
                .orElseThrow();
        assertEquals(TransferStatus.ACCEPTED, unchanged.status());
        assertEquals(TransferEffect.Status.PLANNED,
                unchanged.effects().getFirst().status());
        assertEquals(0L, count("transfer_handler_inbox"));
    }

    @Test
    void aggregateReadHoldsAConsistentSnapshotAcrossConcurrentPreparation()
            throws Exception {
        Transfer transfer = accept(
                repository, PARTICIPANT, "consistent-read", metadata('c'),
                () -> transfer(34, PARTICIPANT, "consistent-read", metadata('c')))
                .transfer();
        UUID deliveryId = jdbc.sql("SELECT event_id FROM operation_outbox")
                .query(UUID.class).single();

        try (Connection blocker = dataSource.getConnection();
                Connection readerConnection = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE transfer_effect IN ACCESS EXCLUSIVE MODE");
            }
            SingleConnectionDataSource readerDataSource =
                    new SingleConnectionDataSource(readerConnection, true);
            int readerPid = JdbcClient.create(readerDataSource)
                    .sql("SELECT pg_backend_pid()")
                    .query(Integer.class).single();
            CountDownLatch writerStarted = new CountDownLatch(1);

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<Transfer> read = executor.submit(() ->
                        new PostgresTransferRepository(readerDataSource)
                                .findById(transfer.transferId(), PARTICIPANT).orElseThrow());
                awaitBlockedEffectRead(readerPid);
                Future<TransferRepository.PreparationResult> write = executor.submit(() -> {
                    writerStarted.countDown();
                    return new PostgresTransferRepository(dataSource)
                            .prepareFirstWithdrawal(
                                    deliveryId, transfer.transferId(), transitionId(130),
                                    NOW.plusSeconds(1));
                });
                assertTrue(writerStarted.await(5, TimeUnit.SECONDS));
                assertFalse(write.isDone());

                blocker.commit();

                Transfer snapshot = read.get(10, TimeUnit.SECONDS);
                assertEquals(TransferStatus.ACCEPTED, snapshot.status());
                assertEquals(TransferEffect.Status.PLANNED,
                        snapshot.effects().getFirst().status());
                assertEquals(TransferRepository.PreparationResult.APPLIED,
                        write.get(10, TimeUnit.SECONDS));
            }
        }
        Transfer current = repository.findById(transfer.transferId(), PARTICIPANT)
                .orElseThrow();
        assertEquals(TransferStatus.IN_PROGRESS, current.status());
        assertEquals(TransferEffect.Status.PREPARED,
                current.effects().getFirst().status());
    }

    @Test
    void inboxPreparationFailureRollsBackBothInboxAndTransferStep() {
        Transfer transfer = accept(
                repository, PARTICIPANT, "handler-rollback", metadata('f'),
                () -> transfer(35, PARTICIPANT, "handler-rollback", metadata('f')))
                .transfer();
        UUID deliveryId = jdbc.sql("SELECT event_id FROM operation_outbox")
                .query(UUID.class).single();
        TransferTransition.Id duplicateTransitionId =
                transfer.transitions().getFirst().transitionId();

        assertThrows(DataIntegrityViolationException.class,
                () -> repository.prepareFirstWithdrawal(
                        deliveryId, transfer.transferId(), duplicateTransitionId,
                        NOW.plusSeconds(1)));

        Transfer loaded = repository.findById(transfer.transferId(), PARTICIPANT)
                .orElseThrow();
        assertEquals(TransferStatus.ACCEPTED, loaded.status());
        assertEquals(0, loaded.version());
        assertEquals(TransferEffect.Status.PLANNED, loaded.effects().getFirst().status());
        assertEquals(0L, count("transfer_handler_inbox"));
        assertEquals(1L, count("transfer_transition"));
        assertEquals(0L, count("transfer_effect_evidence"));
    }

    @Test
    void rejectedProposalRollsBackIdempotencyAndAllAggregateRows() {
        Transfer mismatched = transfer(40, OTHER, "rollback", metadata('e'));

        assertThrows(IllegalArgumentException.class, () -> accept(
                repository, PARTICIPANT, "rollback", metadata('e'), () -> mismatched));

        assertEquals(0L, count("transfer_idempotency"));
        assertEquals(0L, count("banking_transfer"));
        assertEquals(0L, count("transfer_effect"));
        assertEquals(0L, count("operation_outbox"));
    }

    private static TransferAcceptance accept(
            PostgresTransferRepository target,
            ParticipantScope participant,
            String key,
            CanonicalCommandMetadata metadata,
            java.util.function.Supplier<Transfer> factory) {
        return target.accept(participant, IdempotencyKey.of(key), metadata, factory);
    }

    private static Transfer transfer(
            long seed,
            ParticipantScope participant,
            String key,
            CanonicalCommandMetadata request) {
        List<TransferEffect.Id> effects = java.util.stream.LongStream.range(1, 6)
                .mapToObj(index -> effectId(seed * 10 + index)).toList();
        return Transfer.accepted(
                transferId(seed),
                new TransferParticipant(participant.tenantId(), participant.participantId()),
                new TransferAcceptanceContext(
                        new BankAccountReference("synthetic-bank:source-" + seed),
                        new BankAccountReference("synthetic-bank:destination-" + seed),
                        new WalletReference("synthetic-wallet:sender-" + seed),
                        new WalletReference("synthetic-wallet:recipient-" + seed),
                        SettlementNetwork.ETHEREUM, "USD", "route-v3", "wallet-v2",
                        request.canonicalizationVersion(), request.digest().value(),
                        1, "f".repeat(64), IdempotencyKey.of(key).sha256()),
                TokenQuantity.parse("12.34", UNIT), effects, transitionId(seed), NOW,
                new EvidenceRef("participant:transfer:accepted:" + seed));
    }

    private static Transfer afterBarrier(CyclicBarrier barrier, Transfer transfer) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return transfer;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static RaceOutcome raceAccept(
            CyclicBarrier barrier,
            String key,
            CanonicalCommandMetadata request,
            long seed) {
        try {
            accept(new PostgresTransferRepository(dataSource), PARTICIPANT, key, request,
                    () -> afterBarrier(barrier, transfer(seed, PARTICIPANT, key, request)));
            return new RaceOutcome(true, false);
        } catch (IdempotencyConflictException expected) {
            return new RaceOutcome(false, true);
        }
    }

    private static TransferRepository.PreparationResult prepareAfterBarrier(
            CyclicBarrier barrier,
            UUID deliveryId,
            TransferId transferId,
            TransferTransition.Id transitionId) {
        try {
            barrier.await(5, TimeUnit.SECONDS);
            return new PostgresTransferRepository(dataSource).prepareFirstWithdrawal(
                    deliveryId, transferId, transitionId, NOW.plusSeconds(1));
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void awaitBlockedEffectRead(int readerPid) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            boolean blocked = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM pg_stat_activity
                                WHERE pid = :pid
                                  AND wait_event_type = 'Lock'
                                  AND query LIKE '%FROM transfer_effect%')
                            """)
                    .param("pid", readerPid).query(Boolean.class).single();
            if (blocked) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new AssertionError("reader did not reach the blocked effect query");
    }

    private static HikariDataSource newDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(2);
        return new HikariDataSource(config);
    }

    private static CanonicalCommandMetadata metadata(char value) {
        return new CanonicalCommandMetadata(1, new CommandDigest(
                Character.toString(value).repeat(64)));
    }

    private static TransferId transferId(long value) {
        return new TransferId(new UUID(10, value));
    }

    private static TransferEffect.Id effectId(long value) {
        return new TransferEffect.Id(new UUID(11, value));
    }

    private static TransferTransition.Id transitionId(long value) {
        return new TransferTransition.Id(new UUID(12, value));
    }

    private static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private record RaceOutcome(boolean accepted, boolean conflict) { }
}
