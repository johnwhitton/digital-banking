package io.github.johnwhitton.digitalbanking.application.command;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

public record IdempotencyKey(String value) {

    private static final Pattern VISIBLE_ASCII = Pattern.compile("[!-~]{1,128}");

    public IdempotencyKey {
        if (value == null || !VISIBLE_ASCII.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "idempotency key must contain 1 to 128 visible ASCII characters");
        }
    }

    public static IdempotencyKey of(String value) {
        return new IdempotencyKey(value);
    }

    public String sha256() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    @Override
    public String toString() {
        return "[REDACTED]";
    }
}
