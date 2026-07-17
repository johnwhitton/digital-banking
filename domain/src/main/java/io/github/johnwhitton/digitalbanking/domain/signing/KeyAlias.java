package io.github.johnwhitton.digitalbanking.domain.signing;

import java.util.regex.Pattern;

/** Non-secret application alias for an externally held signing key. */
public record KeyAlias(String value) {

    private static final Pattern SAFE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public KeyAlias {
        if (value == null || !SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("key alias must be a safe identifier");
        }
    }

    @Override
    public String toString() {
        return "KeyAlias[[REDACTED]]";
    }
}
