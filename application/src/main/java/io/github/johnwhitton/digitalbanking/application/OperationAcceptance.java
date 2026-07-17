package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

public record OperationAcceptance(TokenOperation operation, boolean replayed) {

    public OperationAcceptance {
        Objects.requireNonNull(operation, "operation");
    }
}
