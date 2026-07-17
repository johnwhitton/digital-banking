package io.github.johnwhitton.digitalbanking.domain.operation;

import java.util.Objects;
import java.util.UUID;

/** Stable identity issued before an external attempt is signed or submitted. */
public record AttemptId(UUID value) {

    public AttemptId {
        Objects.requireNonNull(value, "value");
    }

    public static AttemptId from(String canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException("attempt ID is required");
        }
        try {
            UUID parsed = UUID.fromString(canonical);
            if (!parsed.toString().equals(canonical)) {
                throw new IllegalArgumentException("attempt ID must use canonical UUID text");
            }
            return new AttemptId(parsed);
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("attempt ID must use canonical UUID text", exception);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
