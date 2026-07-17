package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome.Classification;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue.ClaimBatch;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue.LeaseUpdateResult;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue.ManualReviewReason;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue.QueueMeasurements;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;

/** Claims committed work, invokes the handler outside the claim transaction, and records outcomes. */
public final class OperationDeliveryWorker {

    private static final Duration MIN_LEASE = Duration.ofNanos(1_000);

    private final OperationDeliveryQueue queue;
    private final OperationDeliveryHandler handler;
    private final OperationDeliveryFailureReporter failureReporter;
    private final ClockPort clock;
    private final DeliveryRetryPolicy retryPolicy;
    private final Duration leaseDuration;
    private final String workerId;
    private final int batchSize;
    private final AtomicBoolean polling = new AtomicBoolean();

    public OperationDeliveryWorker(
            OperationDeliveryQueue queue,
            OperationDeliveryHandler handler,
            OperationDeliveryFailureReporter failureReporter,
            ClockPort clock,
            DeliveryRetryPolicy retryPolicy,
            Duration leaseDuration,
            String workerId,
            int batchSize) {
        this.queue = Objects.requireNonNull(queue, "queue");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        this.workerId = Objects.requireNonNull(workerId, "workerId");
        if (leaseDuration.compareTo(MIN_LEASE) < 0
                || leaseDuration.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be between one microsecond and one day");
        }
        if (workerId.isEmpty() || workerId.length() > 128
                || workerId.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException(
                    "workerId must contain 1-128 visible US-ASCII characters");
        }
        if (batchSize < 1 || batchSize > 100) {
            throw new IllegalArgumentException("batchSize must be between 1 and 100");
        }
        this.batchSize = batchSize;
    }

    public DeliveryPollResult poll() {
        if (!polling.compareAndSet(false, true)) {
            return DeliveryPollResult.suppressedOverlap();
        }
        try {
            ClaimBatch batch = queue.claim(workerId, clock.now(), leaseDuration, batchSize);
            MutableCounts counts = new MutableCounts(batch.deliveries().size());
            for (OperationDelivery delivery : batch.deliveries()) {
                apply(delivery, handle(delivery), counts);
            }
            QueueMeasurements measurements = queue.measurements(clock.now());
            return counts.result(batch.recoveredLeases(), measurements);
        } finally {
            polling.set(false);
        }
    }

    private DeliveryOutcome handle(OperationDelivery delivery) {
        try {
            return Objects.requireNonNull(handler.handle(delivery), "handler outcome");
        } catch (Exception failure) {
            failureReporter.reportUnexpectedHandlerFailure(delivery, failure);
            return DeliveryOutcome.ambiguousAcknowledgement("unexpected-handler-failure");
        }
    }

    private void apply(
            OperationDelivery delivery, DeliveryOutcome outcome, MutableCounts counts) {
        Instant recordedAt = clock.now();
        switch (outcome.classification()) {
            case DELIVERED, DUPLICATE -> counts.updated(
                    queue.acknowledge(delivery, outcome, recordedAt), outcome.classification());
            case TERMINAL_NO_EFFECT -> counts.manualReview(queue.moveToManualReview(
                    delivery, ManualReviewReason.TERMINAL_NO_EFFECT, outcome, recordedAt));
            case RETRYABLE_NO_EFFECT, AMBIGUOUS_ACKNOWLEDGEMENT -> {
                if (retryPolicy.exhausted(delivery.attemptNumber())) {
                    counts.manualReview(queue.moveToManualReview(
                            delivery, ManualReviewReason.ATTEMPTS_EXHAUSTED,
                            outcome, recordedAt));
                } else {
                    counts.retry(queue.reschedule(
                            delivery, outcome,
                            retryPolicy.nextAttemptAt(recordedAt, delivery.attemptNumber()),
                            recordedAt));
                }
            }
        }
    }

    private static final class MutableCounts {
        private final int claimed;
        private int delivered;
        private int duplicates;
        private int retries;
        private int exhausted;
        private int stale;

        private MutableCounts(int claimed) {
            this.claimed = claimed;
        }

        private void updated(LeaseUpdateResult result, Classification classification) {
            if (result == LeaseUpdateResult.STALE_LEASE) {
                stale++;
            } else if (classification == Classification.DUPLICATE) {
                duplicates++;
            } else {
                delivered++;
            }
        }

        private void retry(LeaseUpdateResult result) {
            if (result == LeaseUpdateResult.STALE_LEASE) {
                stale++;
            } else {
                retries++;
            }
        }

        private void manualReview(LeaseUpdateResult result) {
            if (result == LeaseUpdateResult.STALE_LEASE) {
                stale++;
            } else {
                exhausted++;
            }
        }

        private DeliveryPollResult result(
                int recoveredLeases, QueueMeasurements measurements) {
            return new DeliveryPollResult(
                    claimed, delivered, duplicates, retries, exhausted,
                    recoveredLeases, stale, measurements.eligibleWork(),
                    measurements.activeLeases(), measurements.oldestEligibleAge(), false);
        }
    }
}
