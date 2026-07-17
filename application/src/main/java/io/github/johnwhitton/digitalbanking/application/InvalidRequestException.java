package io.github.johnwhitton.digitalbanking.application;

/** Caller-owned request data failed domain validation at the application boundary. */
public final class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(IllegalArgumentException cause) {
        super("request data did not satisfy the application contract", cause);
    }
}
