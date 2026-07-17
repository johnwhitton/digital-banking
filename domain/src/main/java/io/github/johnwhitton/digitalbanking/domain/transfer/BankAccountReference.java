package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.util.regex.Pattern;

/** Opaque reference to a synthetic bank account; never an account or routing number. */
public record BankAccountReference(String value) {

    private static final Pattern SYNTHETIC =
            Pattern.compile("synthetic-bank:[A-Za-z0-9][A-Za-z0-9._:-]{0,111}");

    public BankAccountReference {
        if (value == null || !SYNTHETIC.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "bank account reference must be an opaque synthetic reference");
        }
    }
}
