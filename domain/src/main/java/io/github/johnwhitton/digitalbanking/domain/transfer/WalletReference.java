package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.util.regex.Pattern;

/** Server-resolved opaque institution wallet identity; never key material or an address. */
public record WalletReference(String value) {

    private static final Pattern SYNTHETIC =
            Pattern.compile("synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}");

    public WalletReference {
        if (value == null || !SYNTHETIC.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "wallet reference must be an opaque server-resolved synthetic reference");
        }
    }
}
