package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");

    @Test
    void calculatesDeterministicBoundedExponentialBackoff() {
        DeliveryRetryPolicy policy = new DeliveryRetryPolicy(
                4, Duration.ofSeconds(5), Duration.ofSeconds(12));

        assertEquals(NOW.plusSeconds(5), policy.nextAttemptAt(NOW, 1));
        assertEquals(NOW.plusSeconds(10), policy.nextAttemptAt(NOW, 2));
        assertEquals(NOW.plusSeconds(12), policy.nextAttemptAt(NOW, 3));
        assertFalse(policy.exhausted(3));
        assertTrue(policy.exhausted(4));
    }

    @Test
    void rejectsUnboundedOrInvalidPolicyValues() {
        assertThrows(IllegalArgumentException.class, () ->
                new DeliveryRetryPolicy(0, Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new DeliveryRetryPolicy(2, Duration.ZERO, Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new DeliveryRetryPolicy(
                        2, Duration.ofNanos(999), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new DeliveryRetryPolicy(2, Duration.ofSeconds(2), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () ->
                new DeliveryRetryPolicy(2, Duration.ofSeconds(1), Duration.ofDays(31)));
    }
}
