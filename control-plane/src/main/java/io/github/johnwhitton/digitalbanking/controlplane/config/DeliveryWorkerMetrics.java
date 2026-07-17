package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.util.concurrent.atomic.AtomicLong;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryPollResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

final class DeliveryWorkerMetrics {

    private final Counter claimed;
    private final Counter delivered;
    private final Counter duplicates;
    private final Counter retries;
    private final Counter manualReview;
    private final Counter recoveredLeases;
    private final Counter staleLeaseUpdates;
    private final Counter overlapSuppressed;
    private final AtomicLong eligible = new AtomicLong();
    private final AtomicLong activeLeases = new AtomicLong();
    private final AtomicLong oldestEligibleAgeSeconds = new AtomicLong();

    DeliveryWorkerMetrics(MeterRegistry registry) {
        claimed = registry.counter("digital.banking.delivery.claimed");
        delivered = registry.counter("digital.banking.delivery.delivered");
        duplicates = registry.counter("digital.banking.delivery.duplicates");
        retries = registry.counter("digital.banking.delivery.retries");
        manualReview = registry.counter("digital.banking.delivery.manual.review");
        recoveredLeases = registry.counter("digital.banking.delivery.recovered.leases");
        staleLeaseUpdates = registry.counter(
                "digital.banking.delivery.stale.lease.updates");
        overlapSuppressed = registry.counter(
                "digital.banking.delivery.overlap.suppressed");
        registry.gauge("digital.banking.delivery.eligible", eligible);
        registry.gauge("digital.banking.delivery.active.leases", activeLeases);
        registry.gauge(
                "digital.banking.delivery.oldest.eligible.age.seconds",
                oldestEligibleAgeSeconds);
    }

    void record(DeliveryPollResult result) {
        claimed.increment(result.claimed());
        delivered.increment(result.delivered());
        duplicates.increment(result.duplicates());
        retries.increment(result.retries());
        manualReview.increment(result.exhausted());
        recoveredLeases.increment(result.recoveredLeases());
        staleLeaseUpdates.increment(result.staleLeaseUpdates());
        if (result.overlapSuppressed()) {
            overlapSuppressed.increment();
        }
        eligible.set(result.eligibleWork());
        activeLeases.set(result.activeLeases());
        oldestEligibleAgeSeconds.set(result.oldestEligibleAge().toSeconds());
    }
}
