package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryPollResult;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeliveryWorkerMetricsTest {

    @Test
    void publishesOnlyBoundedOperationalCountsAndGauges() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        DeliveryWorkerMetrics metrics = new DeliveryWorkerMetrics(registry);

        metrics.record(new DeliveryPollResult(
                7, 2, 1, 2, 1, 3, 1,
                11, 5, Duration.ofSeconds(19), false));
        metrics.record(new DeliveryPollResult(
                0, 0, 0, 0, 0, 0, 0,
                0, 0, Duration.ZERO, true));

        assertEquals(7.0, registry.get("digital.banking.delivery.claimed")
                .counter().count());
        assertEquals(2.0, registry.get("digital.banking.delivery.delivered")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.delivery.duplicates")
                .counter().count());
        assertEquals(2.0, registry.get("digital.banking.delivery.retries")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.delivery.manual.review")
                .counter().count());
        assertEquals(3.0, registry.get("digital.banking.delivery.recovered.leases")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.delivery.stale.lease.updates")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.delivery.overlap.suppressed")
                .counter().count());
        assertEquals(0.0, registry.get("digital.banking.delivery.eligible")
                .gauge().value());
        assertEquals(0.0, registry.get("digital.banking.delivery.active.leases")
                .gauge().value());
        assertEquals(0.0, registry.get("digital.banking.delivery.oldest.eligible.age.seconds")
                .gauge().value());
    }
}
