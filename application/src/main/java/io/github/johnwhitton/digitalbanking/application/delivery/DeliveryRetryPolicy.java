package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Bounded deterministic retry policy for no-effect and ambiguous delivery outcomes. */
public record DeliveryRetryPolicy(
        int maxAttempts,
        Duration initialBackoff,
        Duration maxBackoff) {

    private static final int MAX_ATTEMPTS = 100;
    private static final Duration MIN_BACKOFF = Duration.ofNanos(1_000);
    private static final Duration MAX_CONFIGURED_BACKOFF = Duration.ofDays(30);

    public DeliveryRetryPolicy {
        Objects.requireNonNull(initialBackoff, "initialBackoff");
        Objects.requireNonNull(maxBackoff, "maxBackoff");
        if (maxAttempts < 1 || maxAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException("maxAttempts must be between 1 and 100");
        }
        if (initialBackoff.compareTo(MIN_BACKOFF) < 0) {
            throw new IllegalArgumentException(
                    "initialBackoff must be at least one microsecond");
        }
        if (maxBackoff.compareTo(initialBackoff) < 0
                || maxBackoff.compareTo(MAX_CONFIGURED_BACKOFF) > 0) {
            throw new IllegalArgumentException(
                    "maxBackoff must be at least initialBackoff and no more than 30 days");
        }
    }

    public boolean exhausted(int attemptNumber) {
        requireAttempt(attemptNumber);
        return attemptNumber >= maxAttempts;
    }

    public Instant nextAttemptAt(Instant failureAt, int attemptNumber) {
        Objects.requireNonNull(failureAt, "failureAt");
        requireAttempt(attemptNumber);
        Duration delay = initialBackoff;
        for (int current = 1; current < attemptNumber; current++) {
            if (delay.compareTo(maxBackoff.dividedBy(2)) > 0) {
                delay = maxBackoff;
                break;
            }
            delay = delay.multipliedBy(2);
        }
        if (delay.compareTo(maxBackoff) > 0) {
            delay = maxBackoff;
        }
        return failureAt.plus(delay);
    }

    private static void requireAttempt(int attemptNumber) {
        if (attemptNumber < 1) {
            throw new IllegalArgumentException("attemptNumber must be positive");
        }
    }
}
