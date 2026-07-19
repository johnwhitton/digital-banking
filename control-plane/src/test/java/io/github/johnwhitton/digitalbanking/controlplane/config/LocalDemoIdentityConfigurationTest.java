package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalDemoIdentityConfigurationTest {

    @TempDir
    private Path temporary;

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(LocalDemoIdentityConfiguration.class);

    @Test
    void exactProfileOnlyComposesTheBearerBoundary() throws Exception {
        Path sender = secret("sender", "a".repeat(64));
        Path operator = secret("operator", "b".repeat(64));

        runner.run(context -> assertTrue(context.getBeansOfType(
                LocalDemoBearerTokenAuthenticationFilter.class).isEmpty()));
        runner.withPropertyValues(
                        "spring.profiles.active=local-demo",
                        "digital-banking.local-demo-identity.sender-token-file=" + sender,
                        "digital-banking.local-demo-identity.operator-token-file=" + operator)
                .run(context -> assertTrue(context.getBeansOfType(
                        LocalDemoBearerTokenAuthenticationFilter.class).isEmpty()));
        runner.withPropertyValues(
                        "spring.profiles.active=local-demo-environment",
                        "digital-banking.local-demo-identity.sender-token-file=" + sender,
                        "digital-banking.local-demo-identity.operator-token-file=" + operator)
                .run(context -> {
                    assertTrue(context.isRunning());
                    assertTrue(context.getBeansOfType(
                            LocalDemoBearerTokenAuthenticationFilter.class).size() == 1);
                });
    }

    @Test
    void activeProfileFailsClosedAndRedactedForMissingOrEqualTokens()
            throws Exception {
        Path sender = secret("sender", "a".repeat(64));
        Path same = secret("same", "a".repeat(64));

        runner.withPropertyValues("spring.profiles.active=local-demo-environment")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertFalse(rootMessage(context.getStartupFailure())
                            .contains(temporary.toString()));
                });
        runner.withPropertyValues(
                        "spring.profiles.active=local-demo-environment",
                        "digital-banking.local-demo-identity.sender-token-file=" + sender,
                        "digital-banking.local-demo-identity.operator-token-file=" + same)
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertFalse(rootMessage(context.getStartupFailure())
                            .contains("a".repeat(64)));
                });
    }

    private Path secret(String name, String value) throws Exception {
        Path file = temporary.resolve(name);
        Files.writeString(file, value + System.lineSeparator());
        return file;
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
