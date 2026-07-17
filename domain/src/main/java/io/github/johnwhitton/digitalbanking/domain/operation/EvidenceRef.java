package io.github.johnwhitton.digitalbanking.domain.operation;

/** Opaque reference to evidence stored outside the domain aggregate. */
public record EvidenceRef(String value) {

    public EvidenceRef {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(
                    "evidence reference must be non-blank and at most 256 characters");
        }
    }
}
