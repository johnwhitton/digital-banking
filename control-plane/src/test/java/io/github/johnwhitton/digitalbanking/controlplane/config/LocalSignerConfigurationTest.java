package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Instant;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.signer.local.LocalEphemeralSigner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(OutputCaptureExtension.class)
class LocalSignerConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(LocalSignerConfiguration.class)
            .withBean(DataSource.class, () -> new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:1/not-used"))
            .withBean(ClockPort.class, () -> Instant::now);

    @Test
    void profileIsDisabledByDefaultAndGeneratesNoSigner() {
        contextRunner.run(context -> {
            assertTrue(context.isRunning());
            assertFalse(context.containsBean("localEphemeralSigner"));
            assertFalse(context.containsBean("localSigningAuthorityService"));
            assertTrue(context.getBeansOfType(SignerPort.class).isEmpty());
        });
    }

    @Test
    void explicitProfileCreatesOnlyConfiguredLocalKeys(CapturedOutput output) {
        contextRunner
                .withPropertyValues(validProperties())
                .run(context -> {
                    assertTrue(context.isRunning());
                    LocalEphemeralSigner signer =
                            context.getBean(LocalEphemeralSigner.class);
                    assertEquals(2, signer.keys().size());
                    assertEquals(2, signer.keys().getFirst().roles().size());
                    assertEquals(2, signer.keys().getLast().roles().size());
                    assertSame(signer, context.getBean(SignerPort.class));
                    assertSame(signer, context.getBean(SigningKeyRegistry.class));
                    assertTrue(context.containsBean("localSigningAuthorityService"));
                    assertTrue(context.getBean(SigningAuthorityService.class) != null);
                    assertTrue(output.getOut().contains(
                            "LOCAL_EPHEMERAL signer is active for local development only"));
                    signer.keys().forEach(key -> {
                        assertFalse(output.getAll().contains(key.alias().value()));
                        assertFalse(output.getAll().contains(key.keyVersion()));
                        assertFalse(output.getAll().contains(key.publicKeyFingerprint()));
                    });
                });
    }

    @Test
    void missingOrCrossedConfigurationFailsClosed() {
        contextRunner
                .withPropertyValues("spring.profiles.active=local-signer")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("role allowlists"));
                });

        contextRunner
                .withPropertyValues(
                        "spring.profiles.active=local-signer",
                        "digital-banking.local-signer.evm-roles=MINT_AUTHORITY",
                        "digital-banking.local-signer.evm-networks=SOLANA",
                        "digital-banking.local-signer.solana-roles=MINT_AUTHORITY",
                        "digital-banking.local-signer.solana-networks=SOLANA",
                        "digital-banking.local-signer.max-solana-message-bytes=1024")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("EVM network allowlist"));
                });
    }

    private static String[] validProperties() {
        return new String[] {
            "spring.profiles.active=local-signer",
            "digital-banking.local-signer.evm-roles=MINT_AUTHORITY,TRANSFER_AUTHORITY",
            "digital-banking.local-signer.evm-networks=ETHEREUM",
            "digital-banking.local-signer.solana-roles=TRANSFER_AUTHORITY,BURN_AUTHORITY",
            "digital-banking.local-signer.solana-networks=SOLANA",
            "digital-banking.local-signer.max-solana-message-bytes=2048"
        };
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }
}
