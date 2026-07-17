package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeliveryWorkerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(DeliveryWorkerConfiguration.class)
            .withBean(OperationDeliveryQueue.class, EmptyQueue::new)
            .withBean(ClockPort.class, () -> () -> Instant.parse("2026-07-17T12:00:00Z"))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

    @Test
    void workerIsDisabledByDefault() {
        contextRunner.run(context -> {
            assertTrue(context.isRunning());
            assertFalse(context.containsBean("operationDeliveryWorker"));
            assertFalse(context.containsBean("deliveryWorkerLifecycle"));
        });
    }

    @Test
    void enablingWithoutAHandlerFailsClosed() {
        contextRunner
                .withPropertyValues(
                        "digital-banking.delivery-worker.enabled=true",
                        "digital-banking.delivery-worker.worker-id=test-worker")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("OperationDeliveryHandler"));
                });
    }

    @Test
    void typedBoundsAndLifecycleAreAppliedWhenEnabled() {
        contextRunner
                .withBean(OperationDeliveryHandler.class,
                        () -> delivery -> DeliveryOutcome.delivered())
                .withPropertyValues(
                        "digital-banking.delivery-worker.enabled=true",
                        "digital-banking.delivery-worker.worker-id=test-worker",
                        "digital-banking.delivery-worker.batch-size=7",
                        "digital-banking.delivery-worker.lease-duration=PT45S",
                        "digital-banking.delivery-worker.poll-interval=PT1H",
                        "digital-banking.delivery-worker.max-attempts=4",
                        "digital-banking.delivery-worker.initial-backoff=PT2S",
                        "digital-banking.delivery-worker.max-backoff=PT20S")
                .run(context -> {
                    assertTrue(context.isRunning());
                    DeliveryWorkerProperties properties =
                            context.getBean(DeliveryWorkerProperties.class);
                    assertEquals("test-worker", properties.workerId());
                    assertEquals(7, properties.batchSize());
                    assertEquals(Duration.ofSeconds(45), properties.leaseDuration());

                    DeliveryWorkerLifecycle lifecycle =
                            context.getBean(DeliveryWorkerLifecycle.class);
                    assertTrue(lifecycle.isRunning());
                    lifecycle.stop();
                    assertFalse(lifecycle.isRunning());
                });
    }

    @Test
    void invalidRetryBoundsFailConfiguration() {
        contextRunner
                .withBean(OperationDeliveryHandler.class,
                        () -> delivery -> DeliveryOutcome.delivered())
                .withPropertyValues(
                        "digital-banking.delivery-worker.enabled=true",
                        "digital-banking.delivery-worker.worker-id=test-worker",
                        "digital-banking.delivery-worker.initial-backoff=PT20S",
                        "digital-banking.delivery-worker.max-backoff=PT2S")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("maxBackoff"));
                });

        contextRunner
                .withBean(OperationDeliveryHandler.class,
                        () -> delivery -> DeliveryOutcome.delivered())
                .withPropertyValues(
                        "digital-banking.delivery-worker.enabled=true",
                        "digital-banking.delivery-worker.worker-id=test-worker",
                        "digital-banking.delivery-worker.lease-duration=PT0.000000999S")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("one microsecond"));
                });
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    private static final class EmptyQueue implements OperationDeliveryQueue {

        @Override
        public ClaimBatch claim(
                String workerId, Instant now, Duration leaseDuration, int limit) {
            return new ClaimBatch(List.of(), 0);
        }

        @Override
        public LeaseUpdateResult acknowledge(
                OperationDelivery delivery, DeliveryOutcome outcome, Instant completedAt) {
            return LeaseUpdateResult.UPDATED;
        }

        @Override
        public LeaseUpdateResult reschedule(
                OperationDelivery delivery,
                DeliveryOutcome outcome,
                Instant nextAttemptAt,
                Instant recordedAt) {
            return LeaseUpdateResult.UPDATED;
        }

        @Override
        public LeaseUpdateResult moveToManualReview(
                OperationDelivery delivery,
                ManualReviewReason reason,
                DeliveryOutcome outcome,
                Instant recordedAt) {
            return LeaseUpdateResult.UPDATED;
        }

        @Override
        public QueueMeasurements measurements(Instant now) {
            return new QueueMeasurements(0, 0, Duration.ZERO);
        }
    }
}
