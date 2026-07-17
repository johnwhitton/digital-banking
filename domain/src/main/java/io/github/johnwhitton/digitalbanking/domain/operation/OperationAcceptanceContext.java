package io.github.johnwhitton.digitalbanking.domain.operation;

import java.text.Normalizer;
import java.util.regex.Pattern;

/** Immutable, non-secret context binding an operation to its accepted command. */
public record OperationAcceptanceContext(
        String tenantId,
        String participantId,
        String idempotencyResource,
        String idempotencyKeyDigest,
        int requestContractVersion,
        int canonicalizationVersion,
        String commandDigest,
        String businessCorrelation) {

    private static final Pattern SAFE_IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public OperationAcceptanceContext {
        tenantId = requireIdentifier(tenantId, "tenantId");
        participantId = requireIdentifier(participantId, "participantId");
        idempotencyResource = requireIdentifier(idempotencyResource, "idempotencyResource");
        idempotencyKeyDigest = requireDigest(idempotencyKeyDigest, "idempotencyKeyDigest");
        if (requestContractVersion <= 0) {
            throw new IllegalArgumentException("request contract version must be positive");
        }
        if (canonicalizationVersion <= 0) {
            throw new IllegalArgumentException("canonicalization version must be positive");
        }
        commandDigest = requireDigest(commandDigest, "commandDigest");
        if (businessCorrelation == null || businessCorrelation.isBlank()
                || !isWellFormedUtf16(businessCorrelation)
                || businessCorrelation.codePointCount(0, businessCorrelation.length()) > 128
                || !Normalizer.isNormalized(businessCorrelation, Normalizer.Form.NFC)) {
            throw new IllegalArgumentException(
                    "business correlation must be non-blank, NFC-normalized, bounded, and well-formed");
        }
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !SAFE_IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a safe bounded identifier");
        }
        return value;
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
        return value;
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
