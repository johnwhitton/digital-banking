package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.core.Authentication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDemoBearerAuthenticatorTest {

    private static final String SENDER = "a".repeat(64);
    private static final String OPERATOR = "b".repeat(64);

    @TempDir
    private Path temporary;

    @Test
    void mapsOnlyTheTwoExactTokensToTheirLeastAuthorityPrincipals() throws Exception {
        LocalDemoBearerAuthenticator authenticator = authenticator(SENDER, OPERATOR);

        Authentication sender = authenticator.authenticate("Bearer " + SENDER).orElseThrow();
        assertEquals(new ParticipantPrincipal("local-demo", "USER_1"),
                sender.getPrincipal());
        assertEquals(Set.of(
                        "transfer:create", "transfer:read",
                        "usdzelle:acquire", "usdzelle:redeem", "usdzelle:read"),
                sender.getAuthorities().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertEquals(null, sender.getCredentials());

        Authentication operator = authenticator.authenticate(
                "Bearer " + OPERATOR).orElseThrow();
        assertEquals(new ParticipantPrincipal("local-demo", "OPERATOR"),
                operator.getPrincipal());
        assertEquals(Set.of("local-bank:read", "local-demo:status"),
                operator.getAuthorities().stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        assertEquals(null, operator.getCredentials());
    }

    @Test
    void rejectsMalformedUnknownOrAmbiguousInputsWithoutDisclosingSecrets()
            throws Exception {
        LocalDemoBearerAuthenticator authenticator = authenticator(SENDER, OPERATOR);

        for (String header : java.util.List.of(
                "", "Basic " + SENDER, "bearer " + SENDER,
                "Bearer", "Bearer " + SENDER + " ", "Bearer " + "c".repeat(64))) {
            assertTrue(authenticator.authenticate(header).isEmpty());
        }
        assertFalse(authenticator.toString().contains(SENDER));
        assertFalse(authenticator.toString().contains(OPERATOR));

        IllegalArgumentException duplicate = assertThrows(
                IllegalArgumentException.class, () -> authenticator(SENDER, SENDER));
        assertFalse(duplicate.getMessage().contains(SENDER));
        IllegalArgumentException missing = assertThrows(
                IllegalArgumentException.class,
                () -> new LocalDemoBearerAuthenticator(
                        temporary.resolve("missing"), secret("present", OPERATOR)));
        assertFalse(missing.getMessage().contains(temporary.toString()));
    }

    private LocalDemoBearerAuthenticator authenticator(String sender, String operator)
            throws Exception {
        return new LocalDemoBearerAuthenticator(
                secret("sender", sender), secret("operator", operator));
    }

    private Path secret(String name, String value) throws Exception {
        Path file = temporary.resolve(name);
        Files.writeString(file, value + System.lineSeparator());
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }
        return file;
    }
}
