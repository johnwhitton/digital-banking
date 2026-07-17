package io.github.johnwhitton.digitalbanking.domain.signing;

import java.util.Objects;
import java.util.UUID;

/** Stable identity for one provider invocation or inquiry lineage. */
public record SigningAttemptId(UUID value) {

    public SigningAttemptId {
        Objects.requireNonNull(value, "value");
    }

    public static SigningAttemptId from(String value) {
        return new SigningAttemptId(UUID.fromString(value));
    }
}
