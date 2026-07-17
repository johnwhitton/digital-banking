package io.github.johnwhitton.digitalbanking.domain.operation;

import java.util.Objects;

/**
 * Typed policy proof permitting a related attempt without treating ambiguity as failure.
 */
public record RetryAuthorization(
        AttemptId predecessor,
        Basis basis,
        String policyVersion,
        EvidenceRef evidenceReference) {

    public RetryAuthorization {
        Objects.requireNonNull(predecessor, "predecessor");
        Objects.requireNonNull(basis, "basis");
        if (policyVersion == null || policyVersion.isBlank() || policyVersion.length() > 128) {
            throw new IllegalArgumentException("retry policy version is required and bounded");
        }
        Objects.requireNonNull(evidenceReference, "evidenceReference");
    }

    public enum Basis {
        NATIVE_SAFE_REPLACEMENT
    }
}
