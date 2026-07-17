package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/** Durable queue contract. Every lease-changing operation must be atomic. */
public interface OperationDeliveryQueue {

    ClaimBatch claim(String workerId, Instant now, Duration leaseDuration, int limit);

    LeaseUpdateResult acknowledge(
            OperationDelivery delivery, DeliveryOutcome outcome, Instant completedAt);

    LeaseUpdateResult reschedule(
            OperationDelivery delivery,
            DeliveryOutcome outcome,
            Instant nextAttemptAt,
            Instant recordedAt);

    LeaseUpdateResult moveToManualReview(
            OperationDelivery delivery,
            ManualReviewReason reason,
            DeliveryOutcome outcome,
            Instant recordedAt);

    QueueMeasurements measurements(Instant now);

    record ClaimBatch(List<OperationDelivery> deliveries, int recoveredLeases) {
        public ClaimBatch {
            deliveries = List.copyOf(Objects.requireNonNull(deliveries, "deliveries"));
            if (recoveredLeases < 0) {
                throw new IllegalArgumentException("recoveredLeases must not be negative");
            }
        }
    }

    record QueueMeasurements(
            long eligibleWork,
            long activeLeases,
            Duration oldestEligibleAge) {
        public QueueMeasurements {
            Objects.requireNonNull(oldestEligibleAge, "oldestEligibleAge");
            if (eligibleWork < 0 || activeLeases < 0 || oldestEligibleAge.isNegative()) {
                throw new IllegalArgumentException("queue measurements must not be negative");
            }
        }
    }

    enum LeaseUpdateResult {
        UPDATED,
        STALE_LEASE
    }

    enum ManualReviewReason {
        TERMINAL_NO_EFFECT,
        ATTEMPTS_EXHAUSTED
    }
}
