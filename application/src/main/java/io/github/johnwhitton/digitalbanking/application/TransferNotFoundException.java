package io.github.johnwhitton.digitalbanking.application;

public final class TransferNotFoundException extends RuntimeException {

    public TransferNotFoundException() {
        super("transfer not found");
    }
}
