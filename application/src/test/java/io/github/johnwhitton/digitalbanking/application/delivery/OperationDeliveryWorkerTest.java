package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OperationDeliveryWorkerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final Duration LEASE = Duration.ofSeconds(30);
    private static final DeliveryRetryPolicy RETRIES = new DeliveryRetryPolicy(
            3, Duration.ofSeconds(5), Duration.ofSeconds(20));

    @Test
    void acknowledgesDeliveredAndDuplicateOutcomes() {
        FakeQueue queue = new FakeQueue(List.of(delivery(1), delivery(1)));
        List<DeliveryOutcome> outcomes = new ArrayList<>(List.of(
                DeliveryOutcome.delivered(), DeliveryOutcome.duplicate()));
        OperationDeliveryWorker worker = worker(
                queue, ignored -> outcomes.removeFirst(), ignoredReporter());

        DeliveryPollResult result = worker.poll();

        assertEquals(2, result.claimed());
        assertEquals(1, result.delivered());
        assertEquals(1, result.duplicates());
        assertEquals(2, queue.acknowledged.size());
        assertTrue(queue.rescheduled.isEmpty());
        assertTrue(queue.manualReviews.isEmpty());
    }

    @Test
    void durablySchedulesRetryAndRoutesExhaustionToManualReview() {
        FakeQueue queue = new FakeQueue(List.of(delivery(1), delivery(3)));
        OperationDeliveryWorker worker = worker(
                queue,
                ignored -> DeliveryOutcome.retryableFailure("dependency-unavailable"),
                ignoredReporter());

        DeliveryPollResult result = worker.poll();

        assertEquals(1, result.retries());
        assertEquals(1, result.exhausted());
        assertEquals(NOW.plusSeconds(5), queue.rescheduled.getFirst().nextAttemptAt());
        assertEquals(
                OperationDeliveryQueue.ManualReviewReason.ATTEMPTS_EXHAUSTED,
                queue.manualReviews.getFirst().reason());
    }

    @Test
    void routesTerminalNoEffectToManualReviewWithoutRetry() {
        FakeQueue queue = new FakeQueue(List.of(delivery(1)));
        OperationDeliveryWorker worker = worker(
                queue,
                ignored -> DeliveryOutcome.terminalFailure("request-rejected"),
                ignoredReporter());

        DeliveryPollResult result = worker.poll();

        assertEquals(1, result.exhausted());
        assertTrue(queue.rescheduled.isEmpty());
        assertEquals(
                OperationDeliveryQueue.ManualReviewReason.TERMINAL_NO_EFFECT,
                queue.manualReviews.getFirst().reason());
        assertEquals("request-rejected", queue.manualReviews.getFirst()
                .outcome().safeFailureCode().orElseThrow());
    }

    @Test
    void treatsUnexpectedHandlerFailureAsAmbiguousAndRetainsDiagnosticCause() {
        FakeQueue queue = new FakeQueue(List.of(delivery(1)));
        RuntimeException failure = new RuntimeException("sensitive provider detail");
        AtomicReference<Exception> reported = new AtomicReference<>();
        OperationDeliveryWorker worker = worker(
                queue,
                ignored -> { throw failure; },
                (ignored, cause) -> reported.set(cause));

        DeliveryPollResult result = worker.poll();

        assertEquals(1, result.retries());
        assertEquals(
                DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                queue.rescheduled.getFirst().outcome().classification());
        assertEquals("unexpected-handler-failure",
                queue.rescheduled.getFirst().outcome().safeFailureCode().orElseThrow());
        assertSame(failure, reported.get());
    }

    @Test
    void reportsStaleLeaseWithoutOverwritingTheReplacementOwner() {
        FakeQueue queue = new FakeQueue(List.of(delivery(1)));
        queue.updateResult = OperationDeliveryQueue.LeaseUpdateResult.STALE_LEASE;
        OperationDeliveryWorker worker = worker(
                queue, ignored -> DeliveryOutcome.delivered(), ignoredReporter());

        DeliveryPollResult result = worker.poll();

        assertEquals(1, result.staleLeaseUpdates());
        assertEquals(0, result.delivered());
    }

    @Test
    void suppressesAnOverlappingPollWithoutStartingAnotherClaim() throws Exception {
        FakeQueue queue = new FakeQueue(List.of(delivery(1)));
        CountDownLatch handlerStarted = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        OperationDeliveryWorker worker = worker(queue, ignored -> {
            handlerStarted.countDown();
            if (!releaseHandler.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("handler was not released");
            }
            return DeliveryOutcome.delivered();
        }, ignoredReporter());

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<DeliveryPollResult> first = executor.submit(worker::poll);
            assertTrue(handlerStarted.await(5, TimeUnit.SECONDS));

            DeliveryPollResult overlapping = worker.poll();
            releaseHandler.countDown();

            assertTrue(overlapping.overlapSuppressed());
            assertEquals(1, queue.claimCalls);
            assertFalse(first.get(5, TimeUnit.SECONDS).overlapSuppressed());
        }
    }

    @Test
    void rejectsLeaseBelowDatabaseTimestampPrecision() {
        FakeQueue queue = new FakeQueue(List.of());

        assertThrows(IllegalArgumentException.class, () -> new OperationDeliveryWorker(
                queue, ignored -> DeliveryOutcome.delivered(), ignoredReporter(),
                () -> NOW, RETRIES, Duration.ofNanos(999), "worker-a", 10));
    }

    private static OperationDeliveryWorker worker(
            FakeQueue queue,
            OperationDeliveryHandler handler,
            OperationDeliveryFailureReporter reporter) {
        ClockPort clock = () -> NOW;
        return new OperationDeliveryWorker(
                queue, handler, reporter, clock, RETRIES,
                LEASE, "worker-a", 10);
    }

    private static OperationDelivery delivery(int attempt) {
        return new OperationDelivery(
                UUID.randomUUID(), new OperationId(UUID.randomUUID()), 1, 1,
                UUID.randomUUID(), "worker-a", attempt);
    }

    private static OperationDeliveryFailureReporter ignoredReporter() {
        return (delivery, failure) -> { };
    }

    private static final class FakeQueue implements OperationDeliveryQueue {
        private final List<OperationDelivery> claims;
        private final List<Acknowledgement> acknowledged = new ArrayList<>();
        private final List<Reschedule> rescheduled = new ArrayList<>();
        private final List<ManualReview> manualReviews = new ArrayList<>();
        private int claimCalls;
        private LeaseUpdateResult updateResult = LeaseUpdateResult.UPDATED;

        private FakeQueue(List<OperationDelivery> claims) {
            this.claims = List.copyOf(claims);
        }

        @Override
        public ClaimBatch claim(
                String workerId, Instant now, Duration leaseDuration, int limit) {
            claimCalls++;
            return new ClaimBatch(claims, 0);
        }

        @Override
        public LeaseUpdateResult acknowledge(
                OperationDelivery delivery, DeliveryOutcome outcome, Instant completedAt) {
            acknowledged.add(new Acknowledgement(delivery, outcome, completedAt));
            return updateResult;
        }

        @Override
        public LeaseUpdateResult reschedule(
                OperationDelivery delivery,
                DeliveryOutcome outcome,
                Instant nextAttemptAt,
                Instant recordedAt) {
            rescheduled.add(new Reschedule(delivery, outcome, nextAttemptAt, recordedAt));
            return updateResult;
        }

        @Override
        public LeaseUpdateResult moveToManualReview(
                OperationDelivery delivery,
                ManualReviewReason reason,
                DeliveryOutcome outcome,
                Instant recordedAt) {
            manualReviews.add(new ManualReview(delivery, reason, outcome, recordedAt));
            return updateResult;
        }

        @Override
        public QueueMeasurements measurements(Instant now) {
            return new QueueMeasurements(4, 2, Duration.ofSeconds(7));
        }
    }

    private record Acknowledgement(
            OperationDelivery delivery, DeliveryOutcome outcome, Instant completedAt) { }

    private record Reschedule(
            OperationDelivery delivery,
            DeliveryOutcome outcome,
            Instant nextAttemptAt,
            Instant recordedAt) { }

    private record ManualReview(
            OperationDelivery delivery,
            OperationDeliveryQueue.ManualReviewReason reason,
            DeliveryOutcome outcome,
            Instant recordedAt) { }
}
