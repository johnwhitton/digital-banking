package io.github.johnwhitton.digitalbanking.application;

/** Raised when durable evidence was already consumed by a different posting command. */
public final class AccountingEvidenceConflictException extends IllegalStateException {

    public AccountingEvidenceConflictException(String message) {
        super(message);
    }
}
