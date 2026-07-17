package io.github.johnwhitton.digitalbanking.application;

public final class UnknownAssetUnitException extends RuntimeException {

    public UnknownAssetUnitException() {
        super("asset/unit version is not supported");
    }
}
