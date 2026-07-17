package io.github.johnwhitton.digitalbanking.controlplane.config;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryRetryPolicy;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryFailureReporter;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryWorker;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "digital-banking.delivery-worker",
        name = "enabled",
        havingValue = "true")
@EnableConfigurationProperties(DeliveryWorkerProperties.class)
class DeliveryWorkerConfiguration {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(DeliveryWorkerConfiguration.class);

    @Bean
    DeliveryRetryPolicy deliveryRetryPolicy(DeliveryWorkerProperties properties) {
        return new DeliveryRetryPolicy(
                properties.maxAttempts(),
                properties.initialBackoff(),
                properties.maxBackoff());
    }

    @Bean
    OperationDeliveryFailureReporter operationDeliveryFailureReporter() {
        return (delivery, failure) -> LOGGER.error(
                "Operation delivery handler failed for event {} with {}",
                delivery.deliveryId(), failure.getClass().getSimpleName());
    }

    @Bean
    OperationDeliveryWorker operationDeliveryWorker(
            OperationDeliveryQueue queue,
            OperationDeliveryHandler handler,
            OperationDeliveryFailureReporter failureReporter,
            ClockPort clock,
            DeliveryRetryPolicy retryPolicy,
            DeliveryWorkerProperties properties) {
        return new OperationDeliveryWorker(
                queue, handler, failureReporter, clock, retryPolicy,
                properties.leaseDuration(), properties.workerId(), properties.batchSize());
    }

    @Bean
    DeliveryWorkerMetrics deliveryWorkerMetrics(MeterRegistry registry) {
        return new DeliveryWorkerMetrics(registry);
    }

    @Bean
    ThreadPoolTaskScheduler deliveryTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("operation-delivery-");
        scheduler.setDaemon(true);
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        return scheduler;
    }

    @Bean
    DeliveryWorkerLifecycle deliveryWorkerLifecycle(
            OperationDeliveryWorker worker,
            DeliveryWorkerMetrics metrics,
            @Qualifier("deliveryTaskScheduler") TaskScheduler scheduler,
            DeliveryWorkerProperties properties) {
        return new DeliveryWorkerLifecycle(
                worker, metrics, scheduler, properties.pollInterval());
    }
}
