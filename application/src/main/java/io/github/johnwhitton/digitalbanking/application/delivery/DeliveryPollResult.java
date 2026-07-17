package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Duration;
import java.util.Objects;

/** Low-cardinality operational result for one bounded poll. */
public record DeliveryPollResult(
        int claimed,
        int delivered,
        int duplicates,
        int retries,
        int exhausted,
        int recoveredLeases,
        int staleLeaseUpdates,
        long eligibleWork,
        long activeLeases,
        Duration oldestEligibleAge,
        boolean overlapSuppressed) {

    public DeliveryPollResult {
        Objects.requireNonNull(oldestEligibleAge, "oldestEligibleAge");
        if (claimed < 0 || delivered < 0 || duplicates < 0 || retries < 0
                || exhausted < 0 || recoveredLeases < 0 || staleLeaseUpdates < 0
                || eligibleWork < 0 || activeLeases < 0 || oldestEligibleAge.isNegative()) {
            throw new IllegalArgumentException("poll result values must not be negative");
        }
    }

    static DeliveryPollResult suppressedOverlap() {
        return new DeliveryPollResult(
                0, 0, 0, 0, 0, 0, 0, 0, 0, Duration.ZERO, true);
    }
}
