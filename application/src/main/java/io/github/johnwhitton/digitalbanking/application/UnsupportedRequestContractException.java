package io.github.johnwhitton.digitalbanking.application;

public final class UnsupportedRequestContractException extends RuntimeException {

    public UnsupportedRequestContractException() {
        super("request contract version is not supported");
    }
}
