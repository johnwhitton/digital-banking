package io.github.johnwhitton.digitalbanking.domain.signing;

import java.util.Objects;
import java.util.UUID;

/** Stable business identity for one immutable signing request. */
public record SigningRequestId(UUID value) {

    public SigningRequestId {
        Objects.requireNonNull(value, "value");
    }

    public static SigningRequestId from(String value) {
        return new SigningRequestId(UUID.fromString(value));
    }
}
