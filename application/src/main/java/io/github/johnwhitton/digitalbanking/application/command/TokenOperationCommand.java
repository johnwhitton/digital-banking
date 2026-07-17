package io.github.johnwhitton.digitalbanking.application.command;

import java.text.Normalizer;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;

public sealed interface TokenOperationCommand permits MintCommand, BurnCommand {

    int contractVersion();

    ParticipantScope participantScope();

    TokenQuantity quantity();

    String businessCorrelation();

    OperationKind kind();

    default IdempotencyScope idempotencyScope() {
        return new IdempotencyScope(
                participantScope(), IdempotencyResource.TOKEN_OPERATION, kind());
    }

    static String validateAndNormalize(
            int contractVersion,
            ParticipantScope participantScope,
            TokenQuantity quantity,
            String businessCorrelation) {
        if (contractVersion <= 0) {
            throw new IllegalArgumentException("request contract version must be positive");
        }
        Objects.requireNonNull(participantScope, "participantScope");
        Objects.requireNonNull(quantity, "quantity");
        if (businessCorrelation == null || !isWellFormedUtf16(businessCorrelation)) {
            throw new IllegalArgumentException(
                    "business correlation must contain well-formed Unicode");
        }
        String normalized = Normalizer.normalize(businessCorrelation, Normalizer.Form.NFC);
        if (normalized.isBlank() || normalized.codePointCount(0, normalized.length()) > 128) {
            throw new IllegalArgumentException(
                    "business correlation must be non-blank and at most 128 characters");
        }
        return normalized;
    }

    private static boolean isWellFormedUtf16(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (++index >= value.length() || !Character.isLowSurrogate(value.charAt(index))) {
                    return false;
                }
            } else if (Character.isLowSurrogate(current)) {
                return false;
            }
        }
        return true;
    }
}
