package io.github.johnwhitton.digitalbanking.application;

public final class OperationNotFoundException extends RuntimeException {

    public OperationNotFoundException() {
        super("operation was not found");
    }
}
