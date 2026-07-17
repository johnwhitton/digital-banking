package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryRetryPolicy;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("digital-banking.delivery-worker")
record DeliveryWorkerProperties(
        boolean enabled,
        @NotBlank
        @Pattern(regexp = "[\\x21-\\x7e]{1,128}")
        String workerId,
        @DefaultValue("10") @Min(1) @Max(100) int batchSize,
        @DefaultValue("PT30S") Duration leaseDuration,
        @DefaultValue("PT1S") Duration pollInterval,
        @DefaultValue("10") @Min(1) @Max(100) int maxAttempts,
        @DefaultValue("PT1S") Duration initialBackoff,
        @DefaultValue("PT5M") Duration maxBackoff) {

    DeliveryWorkerProperties {
        requireDatabaseDurationAtMost(leaseDuration, Duration.ofDays(1), "leaseDuration");
        requireDatabaseDurationAtMost(pollInterval, Duration.ofHours(1), "pollInterval");
        new DeliveryRetryPolicy(maxAttempts, initialBackoff, maxBackoff);
    }

    private static void requireDatabaseDurationAtMost(
            Duration value, Duration upperBound, String name) {
        if (value == null || value.compareTo(Duration.ofNanos(1_000)) < 0
                || value.compareTo(upperBound) > 0) {
            throw new IllegalArgumentException(
                    name + " must be at least one microsecond and at most " + upperBound);
        }
    }
}
