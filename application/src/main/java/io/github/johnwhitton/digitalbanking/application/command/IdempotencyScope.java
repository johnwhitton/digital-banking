package io.github.johnwhitton.digitalbanking.application.command;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;

public record IdempotencyScope(
        ParticipantScope participant,
        IdempotencyResource resource,
        OperationKind operationKind) {

    public IdempotencyScope {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(resource, "resource");
        Objects.requireNonNull(operationKind, "operationKind");
    }
}
