package io.github.johnwhitton.digitalbanking.application.command;

import java.util.regex.Pattern;

public record CommandDigest(String value) {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public CommandDigest {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException("command digest must be lowercase SHA-256 hex");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
