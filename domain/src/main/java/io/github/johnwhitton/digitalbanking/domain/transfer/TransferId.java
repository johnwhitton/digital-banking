package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.util.Objects;
import java.util.UUID;

public record TransferId(UUID value) {

    public TransferId {
        Objects.requireNonNull(value, "value");
    }

    public static TransferId from(String value) {
        if (value == null) {
            throw new IllegalArgumentException("transfer ID is required");
        }
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equals(value)) {
                throw new IllegalArgumentException("transfer ID must use canonical UUID text");
            }
            return new TransferId(parsed);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException(
                    "transfer ID must use canonical UUID text", failure);
        }
    }

    @Override
    public String toString() {
        return value.toString();
    }
}
