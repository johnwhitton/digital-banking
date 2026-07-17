package io.github.johnwhitton.digitalbanking.domain.operation;

import java.util.Objects;
import java.util.UUID;

/** Stable identity issued before an operation begins. */
public record OperationId(UUID value) {

    public OperationId {
        Objects.requireNonNull(value, "value");
    }

    public static OperationId from(String canonical) {
        return new OperationId(parseCanonical(canonical));
    }

    @Override
    public String toString() {
        return value.toString();
    }

    private static UUID parseCanonical(String canonical) {
        if (canonical == null) {
            throw new IllegalArgumentException("operation ID is required");
        }
        try {
            UUID parsed = UUID.fromString(canonical);
            if (!parsed.toString().equals(canonical)) {
                throw new IllegalArgumentException("operation ID must use canonical UUID text");
            }
            return parsed;
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("operation ID must use canonical UUID text", exception);
        }
    }
}
