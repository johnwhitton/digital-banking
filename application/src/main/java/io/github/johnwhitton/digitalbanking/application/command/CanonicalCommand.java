package io.github.johnwhitton.digitalbanking.application.command;

import java.util.Arrays;
import java.util.Objects;

public final class CanonicalCommand {

    private final int canonicalizationVersion;
    private final byte[] bytes;
    private final CommandDigest digest;

    CanonicalCommand(int canonicalizationVersion, byte[] bytes, CommandDigest digest) {
        if (canonicalizationVersion <= 0) {
            throw new IllegalArgumentException("canonicalization version must be positive");
        }
        this.canonicalizationVersion = canonicalizationVersion;
        this.bytes = Objects.requireNonNull(bytes, "bytes").clone();
        this.digest = Objects.requireNonNull(digest, "digest");
    }

    public int canonicalizationVersion() {
        return canonicalizationVersion;
    }

    public byte[] bytes() {
        return bytes.clone();
    }

    public CommandDigest digest() {
        return digest;
    }

    public CanonicalCommandMetadata metadata() {
        return new CanonicalCommandMetadata(canonicalizationVersion, digest);
    }

    public String sha256() {
        return digest.value();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CanonicalCommand that
                && canonicalizationVersion == that.canonicalizationVersion
                && digest.equals(that.digest)
                && Arrays.equals(bytes, that.bytes);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(canonicalizationVersion, digest);
        return 31 * result + Arrays.hashCode(bytes);
    }
}
