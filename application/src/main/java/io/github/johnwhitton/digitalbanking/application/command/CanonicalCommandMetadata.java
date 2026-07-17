package io.github.johnwhitton.digitalbanking.application.command;

import java.util.Objects;

/** Persistable identity of a canonical command without transport representation. */
public record CanonicalCommandMetadata(
        int canonicalizationVersion,
        CommandDigest digest) {

    public CanonicalCommandMetadata {
        if (canonicalizationVersion <= 0) {
            throw new IllegalArgumentException("canonicalization version must be positive");
        }
        Objects.requireNonNull(digest, "digest");
    }
}
