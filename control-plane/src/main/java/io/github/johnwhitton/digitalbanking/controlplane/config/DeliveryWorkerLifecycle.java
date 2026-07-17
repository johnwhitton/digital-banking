package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.TaskScheduler;

final class DeliveryWorkerLifecycle implements SmartLifecycle {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DeliveryWorkerLifecycle.class);

    private final OperationDeliveryWorker worker;
    private final DeliveryWorkerMetrics metrics;
    private final TaskScheduler scheduler;
    private final Duration pollInterval;

    private volatile boolean running;
    private ScheduledFuture<?> scheduledPoll;

    DeliveryWorkerLifecycle(
            OperationDeliveryWorker worker,
            DeliveryWorkerMetrics metrics,
            TaskScheduler scheduler,
            Duration pollInterval) {
        this.worker = Objects.requireNonNull(worker, "worker");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
    }

    @Override
    public synchronized void start() {
        if (running) {
            return;
        }
        scheduledPoll = scheduler.scheduleWithFixedDelay(this::pollSafely, pollInterval);
        running = true;
    }

    private void pollSafely() {
        try {
            metrics.record(worker.poll());
        } catch (RuntimeException failure) {
            LOGGER.error("Operation delivery poll failed with {}",
                    failure.getClass().getSimpleName());
        }
    }

    @Override
    public synchronized void stop() {
        if (scheduledPoll != null) {
            scheduledPoll.cancel(false);
            scheduledPoll = null;
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }
}
