package io.github.johnwhitton.digitalbanking.application.command;

import java.util.Objects;

public record IdempotencyScope(
        ParticipantScope participant,
        IdempotencyResource resource) {

    public IdempotencyScope {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(resource, "resource");
    }
}
