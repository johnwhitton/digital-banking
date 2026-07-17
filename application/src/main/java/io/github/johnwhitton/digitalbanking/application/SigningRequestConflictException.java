package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;

/** Internal deterministic conflict for one reused signing request identity. */
public final class SigningRequestConflictException extends RuntimeException {

    private final SigningRequest existing;

    public SigningRequestConflictException(SigningRequest existing) {
        super("signing request identity conflicts with its durable canonical context");
        this.existing = Objects.requireNonNull(existing, "existing");
    }

    public SigningRequest existing() {
        return existing;
    }
}
