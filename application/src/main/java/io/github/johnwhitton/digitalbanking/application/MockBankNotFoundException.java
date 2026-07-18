package io.github.johnwhitton.digitalbanking.application;

public final class MockBankNotFoundException extends RuntimeException {

    public MockBankNotFoundException() {
        super("synthetic bank resource not found");
    }
}
