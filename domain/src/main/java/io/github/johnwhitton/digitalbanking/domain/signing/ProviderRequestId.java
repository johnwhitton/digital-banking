package io.github.johnwhitton.digitalbanking.domain.signing;

import java.util.regex.Pattern;

/** Opaque stable identity used for idempotent provider invocation and inquiry. */
public record ProviderRequestId(String value) {

    private static final Pattern SAFE =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public ProviderRequestId {
        if (value == null || !SAFE.matcher(value).matches()) {
            throw new IllegalArgumentException("provider request ID must be a safe identifier");
        }
    }

    @Override
    public String toString() {
        return "ProviderRequestId[[REDACTED]]";
    }
}
