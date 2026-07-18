package io.github.johnwhitton.digitalbanking.application;

/** Explicit adapter signal that no bank effect committed and bounded retry is safe. */
public final class BankPreEffectFailureException extends RuntimeException {

    public BankPreEffectFailureException(String message) {
        super(message);
    }
}
