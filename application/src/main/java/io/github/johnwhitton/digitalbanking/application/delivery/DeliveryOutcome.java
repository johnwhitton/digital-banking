package io.github.johnwhitton.digitalbanking.application.delivery;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** Explicit handler outcome. Failure codes are stable, bounded, and non-sensitive. */
public record DeliveryOutcome(
        Classification classification,
        Optional<String> safeFailureCode) {

    private static final Pattern SAFE_CODE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    public DeliveryOutcome {
        Objects.requireNonNull(classification, "classification");
        Objects.requireNonNull(safeFailureCode, "safeFailureCode");
        boolean failure = switch (classification) {
            case DELIVERED, DUPLICATE -> false;
            case RETRYABLE_NO_EFFECT, TERMINAL_NO_EFFECT, AMBIGUOUS_ACKNOWLEDGEMENT -> true;
        };
        if (failure != safeFailureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "failure outcomes require exactly one safe failure code");
        }
        safeFailureCode.ifPresent(code -> {
            if (!SAFE_CODE.matcher(code).matches()) {
                throw new IllegalArgumentException("invalid safe failure code");
            }
        });
    }

    public static DeliveryOutcome delivered() {
        return new DeliveryOutcome(Classification.DELIVERED, Optional.empty());
    }

    public static DeliveryOutcome duplicate() {
        return new DeliveryOutcome(Classification.DUPLICATE, Optional.empty());
    }

    public static DeliveryOutcome retryableFailure(String safeFailureCode) {
        return failure(Classification.RETRYABLE_NO_EFFECT, safeFailureCode);
    }

    public static DeliveryOutcome terminalFailure(String safeFailureCode) {
        return failure(Classification.TERMINAL_NO_EFFECT, safeFailureCode);
    }

    public static DeliveryOutcome ambiguousAcknowledgement(String safeFailureCode) {
        return failure(Classification.AMBIGUOUS_ACKNOWLEDGEMENT, safeFailureCode);
    }

    private static DeliveryOutcome failure(
            Classification classification, String safeFailureCode) {
        return new DeliveryOutcome(
                classification, Optional.of(Objects.requireNonNull(safeFailureCode)));
    }

    public enum Classification {
        DELIVERED,
        DUPLICATE,
        RETRYABLE_NO_EFFECT,
        TERMINAL_NO_EFFECT,
        AMBIGUOUS_ACKNOWLEDGEMENT
    }
}
