package io.github.johnwhitton.digitalbanking.application;

public final class UnsupportedTransferConfigurationException extends RuntimeException {

    public UnsupportedTransferConfigurationException() {
        super("unsupported transfer configuration");
    }
}
