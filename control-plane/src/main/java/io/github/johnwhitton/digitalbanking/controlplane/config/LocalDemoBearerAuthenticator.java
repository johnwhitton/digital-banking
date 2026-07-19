package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

/** Exact-profile bearer authentication for the disposable local demo only. */
final class LocalDemoBearerAuthenticator {

    private static final int MINIMUM_TOKEN_BYTES = 43;
    private static final int MAXIMUM_TOKEN_BYTES = 128;
    private static final List<SimpleGrantedAuthority> SENDER_AUTHORITIES = authorities(
            "transfer:create", "transfer:read", "usdzelle:acquire",
            "usdzelle:redeem", "usdzelle:read");
    private static final List<SimpleGrantedAuthority> OPERATOR_AUTHORITIES = authorities(
            "local-bank:read", "local-demo:status");

    private final byte[] senderDigest;
    private final byte[] operatorDigest;

    LocalDemoBearerAuthenticator(Path senderTokenFile, Path operatorTokenFile) {
        senderDigest = readDigest(senderTokenFile);
        operatorDigest = readDigest(operatorTokenFile);
        if (MessageDigest.isEqual(senderDigest, operatorDigest)) {
            throw new IllegalArgumentException("local demo bearer tokens must be distinct");
        }
    }

    Optional<Authentication> authenticate(String authorizationHeader) {
        if (authorizationHeader == null
                || !authorizationHeader.startsWith("Bearer ")
                || authorizationHeader.length() <= "Bearer ".length()) {
            return Optional.empty();
        }
        byte[] presented = authorizationHeader.substring("Bearer ".length())
                .getBytes(StandardCharsets.US_ASCII);
        try {
            if (!validTokenBytes(presented)) {
                return Optional.empty();
            }
            byte[] digest = digest(presented);
            boolean sender = MessageDigest.isEqual(senderDigest, digest);
            boolean operator = MessageDigest.isEqual(operatorDigest, digest);
            Arrays.fill(digest, (byte) 0);
            if (sender == operator) {
                return Optional.empty();
            }
            ParticipantPrincipal principal = sender
                    ? new ParticipantPrincipal("local-demo", "USER_1")
                    : new ParticipantPrincipal("local-demo", "OPERATOR");
            return Optional.of(UsernamePasswordAuthenticationToken.authenticated(
                    principal, null, sender ? SENDER_AUTHORITIES : OPERATOR_AUTHORITIES));
        } finally {
            Arrays.fill(presented, (byte) 0);
        }
    }

    @Override
    public String toString() {
        return "LocalDemoBearerAuthenticator[REDACTED]";
    }

    private static byte[] readDigest(Path path) {
        byte[] value = null;
        try {
            if (path == null || !Files.isRegularFile(path)) {
                throw new IllegalArgumentException(
                        "required local demo bearer token file is unavailable");
            }
            value = Files.readAllBytes(path);
            int length = value.length;
            if (length > 0 && value[length - 1] == '\n') {
                length--;
                if (length > 0 && value[length - 1] == '\r') {
                    length--;
                }
            }
            byte[] token = Arrays.copyOf(value, length);
            try {
                if (!validTokenBytes(token)) {
                    throw new IllegalArgumentException(
                            "local demo bearer token file is malformed");
                }
                return digest(token);
            } finally {
                Arrays.fill(token, (byte) 0);
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "required local demo bearer token file is unreadable", failure);
        } finally {
            if (value != null) {
                Arrays.fill(value, (byte) 0);
            }
        }
    }

    private static boolean validTokenBytes(byte[] value) {
        if (value.length < MINIMUM_TOKEN_BYTES || value.length > MAXIMUM_TOKEN_BYTES) {
            return false;
        }
        for (byte character : value) {
            if (!((character >= 'A' && character <= 'Z')
                    || (character >= 'a' && character <= 'z')
                    || (character >= '0' && character <= '9')
                    || character == '_' || character == '-')) {
                return false;
            }
        }
        return true;
    }

    private static byte[] digest(byte[] value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static List<SimpleGrantedAuthority> authorities(String... values) {
        return Arrays.stream(values).map(SimpleGrantedAuthority::new).toList();
    }
}
