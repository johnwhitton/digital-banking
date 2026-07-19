package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresUsdzelleWorkflowRepositoryTest {

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final IdempotencyKey KEY = new IdempotencyKey("phase-6b-key");
    private static final String REQUEST_DIGEST = "b".repeat(64);

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:17.10-alpine3.23")
                .withDatabaseName("digital_banking_usdzelle_workflow")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
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
    void clearWorkflowRows() {
        jdbc.sql("DELETE FROM operation_delivery_attempt").update();
        jdbc.sql("DELETE FROM operation_outbox WHERE workflow_id IS NOT NULL").update();
        jdbc.sql("DELETE FROM usdzelle_workflow_conclusion").update();
        jdbc.sql("DELETE FROM usdzelle_chain_state_observation").update();
        jdbc.sql("DELETE FROM usdzelle_workflow_evidence").update();
        jdbc.sql("DELETE FROM usdzelle_workflow_child").update();
        jdbc.sql("DELETE FROM usdzelle_workflow_transition").update();
        jdbc.sql("DELETE FROM usdzelle_workflow_step").update();
        jdbc.sql("DELETE FROM usdzelle_workflow_idempotency").update();
        jdbc.sql("DELETE FROM usdzelle_workflow").update();
    }

    @Test
    void v9MigratesNormalizedWorkflowStateAndExtendsTheExistingOutbox() {
        assertEquals(8, jdbc.sql(
                "SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_name = 'operation_outbox' AND column_name = 'workflow_id'
                """).query(Integer.class).single());
        assertEquals(8, jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public'
                  AND table_name IN (
                    'usdzelle_workflow', 'usdzelle_workflow_idempotency',
                    'usdzelle_workflow_step', 'usdzelle_workflow_transition',
                    'usdzelle_workflow_child', 'usdzelle_workflow_evidence',
                    'usdzelle_workflow_conclusion',
                    'usdzelle_chain_state_observation')
                """).query(Integer.class).single());
    }

    @Test
    void atomicallyAcceptsReplaysAndRejectsAConflictingPayload() {
        PostgresUsdzelleWorkflowRepository repository =
                new PostgresUsdzelleWorkflowRepository(dataSource);
        AtomicInteger factories = new AtomicInteger();

        UsdzelleWorkflowAcceptance accepted = repository.accept(
                PARTICIPANT, UsdzelleWorkflow.Kind.ACQUISITION, KEY, REQUEST_DIGEST,
                () -> workflow(
                        UsdzelleWorkflow.Kind.ACQUISITION,
                        new UUID(10, factories.incrementAndGet())));
        UsdzelleWorkflowAcceptance replayed = repository.accept(
                PARTICIPANT, UsdzelleWorkflow.Kind.ACQUISITION, KEY, REQUEST_DIGEST,
                () -> workflow(
                        UsdzelleWorkflow.Kind.ACQUISITION,
                        new UUID(10, factories.incrementAndGet())));

        assertFalse(accepted.replayed());
        assertTrue(replayed.replayed());
        assertEquals(accepted.workflow().id(), replayed.workflow().id());
        assertEquals(1, factories.get());
        assertEquals(1, count("usdzelle_workflow"));
        assertEquals(5, count("usdzelle_workflow_step"));
        assertEquals(1, count("usdzelle_workflow_transition"));
        assertEquals(1, count("usdzelle_workflow_evidence"));
        assertEquals(1, jdbc.sql(
                "SELECT count(*) FROM operation_outbox WHERE workflow_id IS NOT NULL")
                .query(Integer.class).single());
        var claim = PostgresOperationDeliveryQueue.localEthereumDemo(dataSource)
                .claim("phase-6b-worker", accepted.workflow().context().acceptedAt(),
                        Duration.ofSeconds(30), 1);
        assertEquals(1, claim.deliveries().size());
        assertEquals("UsdzelleWorkflowAccepted",
                claim.deliveries().getFirst().eventType());
        assertEquals(accepted.workflow().id().value(),
                claim.deliveries().getFirst().aggregateId());
        assertTrue(repository.findById(
                accepted.workflow().id(), PARTICIPANT).isPresent());
        assertTrue(repository.findById(
                accepted.workflow().id(),
                new ParticipantScope("tenant-a", "participant-b")).isEmpty());
        assertThrows(IdempotencyConflictException.class, () -> repository.accept(
                PARTICIPANT, UsdzelleWorkflow.Kind.ACQUISITION, KEY, "c".repeat(64),
                () -> workflow(UsdzelleWorkflow.Kind.ACQUISITION, new UUID(10, 99))));
        assertEquals(1, count("usdzelle_workflow"));
    }

    @Test
    void savesVersionFencedProgressAndRehydratesItAfterRepositoryRestart() {
        PostgresUsdzelleWorkflowRepository repository =
                new PostgresUsdzelleWorkflowRepository(dataSource);
        UsdzelleWorkflow accepted = repository.accept(
                PARTICIPANT, UsdzelleWorkflow.Kind.REDEMPTION, KEY, REQUEST_DIGEST,
                () -> workflow(UsdzelleWorkflow.Kind.REDEMPTION, new UUID(11, 1)))
                .workflow();
        Instant firstTime = accepted.context().acceptedAt().plusSeconds(1);
        UsdzelleWorkflow active = accepted.beginCurrent(
                0, new UsdzelleWorkflow.TransitionId(new UUID(12, 1)),
                new EvidenceRef("internal:workflow:dispatch"), firstTime);
        repository.save(active, 0);
        UsdzelleWorkflow completed = active.confirmCurrent(
                1, Optional.of(new UsdzelleWorkflow.ChildReference("wallet-transfer:1")),
                new UsdzelleWorkflow.TransitionId(new UUID(12, 2)),
                new EvidenceRef("chain:wallet-transfer:confirmed"), firstTime.plusSeconds(1));
        repository.save(completed, 1);

        UsdzelleWorkflow restarted = new PostgresUsdzelleWorkflowRepository(dataSource)
                .findById(accepted.id()).orElseThrow();
        assertEquals(UsdzelleWorkflow.Status.CUSTODY_CONFIRMED, restarted.status());
        assertEquals(2, restarted.version());
        assertEquals(UsdzelleWorkflow.StepStatus.COMPLETED,
                restarted.steps().getFirst().status());
        assertEquals("wallet-transfer:1",
                restarted.steps().getFirst().childReference().orElseThrow().value());
        assertEquals(3, count("usdzelle_workflow_transition"));
        assertEquals(1, count("usdzelle_workflow_child"));
        assertThrows(IllegalStateException.class, () -> repository.save(completed, 0));
    }

    @Test
    void concurrentSameKeyAcceptanceCreatesOneParentAndOneContext() throws Exception {
        PostgresUsdzelleWorkflowRepository repository =
                new PostgresUsdzelleWorkflowRepository(dataSource);
        AtomicInteger factories = new AtomicInteger();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<UsdzelleWorkflowAcceptance>> results = new ArrayList<>();
            for (int thread = 0; thread < 2; thread++) {
                results.add(executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return repository.accept(
                            PARTICIPANT, UsdzelleWorkflow.Kind.ACQUISITION,
                            KEY, REQUEST_DIGEST,
                            () -> workflow(
                                    UsdzelleWorkflow.Kind.ACQUISITION,
                                    new UUID(13, factories.incrementAndGet())));
                }));
            }
            ready.await();
            start.countDown();
            UsdzelleWorkflowAcceptance first = results.get(0).get();
            UsdzelleWorkflowAcceptance second = results.get(1).get();
            assertEquals(first.workflow().id(), second.workflow().id());
            assertEquals(1, factories.get());
            assertEquals(1, count("usdzelle_workflow"));
            assertEquals(1, count("usdzelle_workflow_idempotency"));
        }
    }

    @Test
    void exhaustedDeliveryAtomicallyProjectsManualReviewIntoTheParent() {
        PostgresUsdzelleWorkflowRepository repository =
                new PostgresUsdzelleWorkflowRepository(dataSource);
        UsdzelleWorkflow accepted = repository.accept(
                PARTICIPANT, UsdzelleWorkflow.Kind.ACQUISITION, KEY, REQUEST_DIGEST,
                () -> workflow(UsdzelleWorkflow.Kind.ACQUISITION, new UUID(14, 1)))
                .workflow();
        Instant activeAt = accepted.context().acceptedAt().plusSeconds(1);
        repository.save(accepted.beginCurrent(
                0, new UsdzelleWorkflow.TransitionId(new UUID(14, 2)),
                new EvidenceRef("internal:workflow:dispatch"), activeAt), 0);
        PostgresOperationDeliveryQueue queue =
                PostgresOperationDeliveryQueue.localEthereumDemo(dataSource);
        var delivery = queue.claim(
                "phase-6b-worker", activeAt, Duration.ofSeconds(30), 1)
                .deliveries().getFirst();

        assertEquals(OperationDeliveryQueue.LeaseUpdateResult.UPDATED,
                queue.moveToManualReview(
                        delivery,
                        OperationDeliveryQueue.ManualReviewReason.ATTEMPTS_EXHAUSTED,
                        DeliveryOutcome.ambiguousAcknowledgement(
                                "unexpected-handler-failure"),
                        activeAt.plusSeconds(1)));

        UsdzelleWorkflow retained = repository.findById(accepted.id()).orElseThrow();
        assertEquals(UsdzelleWorkflow.Status.MANUAL_REVIEW, retained.status());
        assertEquals(UsdzelleWorkflow.StepStatus.MANUAL_REVIEW,
                retained.currentStep().status());
        assertEquals(2, retained.version());
    }

    private static int count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Integer.class).single();
    }

    private static UsdzelleWorkflow workflow(
            UsdzelleWorkflow.Kind kind, UUID workflowId) {
        AssetUnit unit = new AssetUnit(
                "USDZELLE", "CENT", 1, 2, new BigInteger("999999999999999999"));
        UsdzelleWorkflow.AcceptedContext context = new UsdzelleWorkflow.AcceptedContext(
                "phase-6b-v1", UsdCents.positive(new BigInteger("10000")),
                TokenQuantity.ofAtomic(new BigInteger("10000"), unit),
                new SyntheticBankAccount.BankId("BANK_1"),
                new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT"),
                new WalletReference("synthetic-wallet:USER_WALLET_1"), "user-wallet-v1",
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"), "admin-wallet-v1",
                SettlementNetwork.ETHEREUM, "local-token-v1",
                "payout-before-burn-v1", "one-to-one-v1",
                "accounting-v1", "fee-v1", "finality-v1", "reconciliation-v1",
                KEY.sha256(), "a".repeat(64), Instant.parse("2026-07-18T18:00:00Z"));
        int count = kind == UsdzelleWorkflow.Kind.ACQUISITION ? 5 : 6;
        List<UsdzelleWorkflow.StepId> stepIds = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            stepIds.add(new UsdzelleWorkflow.StepId(
                    new UUID(workflowId.getLeastSignificantBits(), index + 1)));
        }
        return UsdzelleWorkflow.accepted(
                new UsdzelleWorkflow.Id(workflowId), kind,
                new UsdzelleWorkflow.Participant(
                        PARTICIPANT.tenantId(), PARTICIPANT.participantId()),
                context, stepIds,
                new UsdzelleWorkflow.TransitionId(
                        new UUID(workflowId.getLeastSignificantBits(), 100)),
                new EvidenceRef("participant:workflow:accepted"));
    }
}
