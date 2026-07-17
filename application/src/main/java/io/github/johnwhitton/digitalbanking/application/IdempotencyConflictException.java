package io.github.johnwhitton.digitalbanking.application;

public final class IdempotencyConflictException extends RuntimeException {

    public IdempotencyConflictException() {
        super("idempotency key was already used for a different canonical command");
    }
}
