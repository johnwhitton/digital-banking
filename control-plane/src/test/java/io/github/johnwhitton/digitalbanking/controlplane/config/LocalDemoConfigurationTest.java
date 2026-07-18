package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import javax.sql.DataSource;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.signer.local.LocalConfiguredSigner;
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
class LocalDemoConfigurationTest {

    private static final HexFormat HEX = HexFormat.of();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(
                    LocalDemoConfiguration.class,
                    LocalSignerProfileConflictConfiguration.class,
                    LocalSignerConfiguration.class)
            .withBean(DataSource.class, () -> new DriverManagerDataSource(
                    "jdbc:postgresql://127.0.0.1:1/not-used"))
            .withBean(ClockPort.class, () -> Instant::now);

    @Test
    void profileIsDisabledByDefaultAndCreatesNoConfiguredSigner() {
        contextRunner.run(context -> {
            assertTrue(context.isRunning());
            assertTrue(context.getBeansOfType(LocalConfiguredSigner.class).isEmpty());
            assertTrue(context.getBeansOfType(WalletIdentityRegistry.class).isEmpty());
        });
    }

    @Test
    void validProfileComposesTenWalletsWithoutDisclosingSecrets(CapturedOutput output) {
        Fixture fixture = fixture();
        contextRunner.withPropertyValues(fixture.properties()).run(context -> {
            assertTrue(context.isRunning());
            LocalConfiguredSigner signer = context.getBean(LocalConfiguredSigner.class);
            assertSame(signer, context.getBean(SignerPort.class));
            assertSame(signer, context.getBean(SigningKeyRegistry.class));
            assertSame(signer, context.getBean(WalletIdentityRegistry.class));
            assertEquals(10, signer.identities().size());
            assertTrue(context.getBean(SigningAuthorityService.class) != null);
            assertTrue(output.getOut().contains(
                    "LOCAL_CONFIGURED signer is active for local chain 31337 only"));
            fixture.secrets().forEach(secret ->
                    assertFalse(output.getAll().contains(secret)));
        });
    }

    @Test
    void missingMismatchedOrCrossedConfigurationFailsClosedAndRedacted(
            CapturedOutput output) {
        contextRunner.withPropertyValues("spring.profiles.active=local-demo")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("local demo"));
                });

        Fixture fixture = fixture();
        String suppliedSecret = fixture.secrets().getFirst();
        List<String> mismatched = new ArrayList<>(Arrays.asList(fixture.properties()));
        mismatched.add("digital-banking.local-demo.wallets[0].expected-address="
                + generatedKey().address());
        contextRunner.withPropertyValues(mismatched.toArray(String[]::new))
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("expected local address"));
                    assertFalse(rootMessage(context.getStartupFailure())
                            .contains(suppliedSecret));
                    assertFalse(output.getAll().contains(suppliedSecret));
                });

        contextRunner.withPropertyValues(
                        "spring.profiles.active=local-demo,local-signer")
                .run(context -> {
                    assertTrue(context.getStartupFailure() != null);
                    assertTrue(rootMessage(context.getStartupFailure())
                            .contains("must not be active together"));
                });
    }

    @Test
    void configurationPropertyDiagnosticsAndSerializationRedactSecrets()
            throws Exception {
        GeneratedKey generated = generatedKey();
        String suppliedSecret = generated.privateKey();
        LocalDemoProperties.SecretValue secret =
                new LocalDemoProperties.SecretValue(suppliedSecret);
        LocalDemoProperties.Wallet wallet = new LocalDemoProperties.Wallet(
                "ADMIN", Set.of("ADMIN_REDEMPTION"),
                WalletIdentityRegistry.OwnerCategory.ADMIN,
                io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork.ETHEREUM,
                "local-demo:ADMIN", secret, generated.address(),
                Set.of(WalletIdentityRegistry.Purpose.MINT_AUTHORITY), true);
        LocalDemoProperties properties = new LocalDemoProperties(
                31337, "ADMIN", List.of(wallet));

        assertFalse(secret.toString().contains(suppliedSecret));
        assertFalse(wallet.toString().contains(suppliedSecret));
        assertFalse(properties.toString().contains(suppliedSecret));
        assertFalse(new ObjectMapper().writeValueAsString(properties)
                .contains(suppliedSecret));
    }

    @Test
    void trackedTemplatesContainOnlyTheTenApprovedPublicAddressMappings()
            throws Exception {
        String yaml;
        try (java.io.InputStream stream = getClass().getResourceAsStream(
                "/application-local-demo.yaml")) {
            yaml = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
        String example = Files.readString(Path.of("..", ".env.example"));
        List<String> approved = List.of(
                "0xf39Fd6e51aad88F6F4ce6aB8827279cffFb92266",
                "0x70997970C51812dc3A010C7d01b50e0d17dc79C8",
                "0x3C44CdDdB6a900fa2b585dd299e03d12FA4293BC",
                "0x90F79bf6EB2c4f870365E785982E1f101E93b906",
                "0x15d34AAf54267DB7D7c367839AAf71A00a2C6A65",
                "0x9965507D1a55bcC2695C58ba16FB37d819B0A4dc",
                "0x976EA74026E726554dB657fA54763abd0C3a0aa9",
                "0x14dC79964da2C08b23698B3D3cc7Ca32193d9955",
                "0x23618e81E3f5cdF7f54C3d65f7FBc0aBf5B21E8f",
                "0xa0Ee7A142d267C1f36714E4a8F75612F20a79720");

        approved.forEach(address -> {
            assertTrue(yaml.contains(address));
            assertTrue(example.contains(address));
        });
        assertEquals(10, yaml.lines().filter(line -> line.contains("private-key: ${"))
                .count());
        assertEquals(10, example.lines().filter(line ->
                line.matches("LOCAL_DEMO_.*_PRIVATE_KEY=")).count());
        assertTrue(example.lines().filter(line -> line.contains("_PRIVATE_KEY="))
                .allMatch(line -> line.endsWith("=")));
    }

    private static Fixture fixture() {
        List<WalletFixture> wallets = List.of(
                wallet("CONTRACT_OWNER", "CONTRACT_DEPLOYER", "ADMIN",
                        "CONTRACT_ADMIN,CONTRACT_DEPLOYMENT,ROLE_ADMINISTRATION"),
                wallet("ADMIN", "ADMIN_REDEMPTION", "ADMIN",
                        "MINT_AUTHORITY,BURN_AUTHORITY,REDEMPTION_CUSTODY"),
                wallet("BANK_1_SETTLEMENT", "", "BANK_SETTLEMENT",
                        "BANK_SETTLEMENT_TRANSFER"),
                wallet("BANK_2_SETTLEMENT", "", "BANK_SETTLEMENT",
                        "BANK_SETTLEMENT_TRANSFER"),
                wallet("BANK_3_SETTLEMENT", "", "BANK_SETTLEMENT",
                        "BANK_SETTLEMENT_TRANSFER"),
                wallet("BANK_4_SETTLEMENT", "", "BANK_SETTLEMENT",
                        "BANK_SETTLEMENT_TRANSFER"),
                wallet("USER_WALLET_1", "", "USER_CUSTODY",
                        "USER_CUSTODY_TRANSFER"),
                wallet("USER_WALLET_2", "", "USER_CUSTODY",
                        "USER_CUSTODY_TRANSFER"),
                wallet("USER_WALLET_3", "", "USER_CUSTODY",
                        "USER_CUSTODY_TRANSFER"),
                wallet("USER_WALLET_4", "", "USER_CUSTODY",
                        "USER_CUSTODY_TRANSFER"));
        List<String> properties = new ArrayList<>();
        properties.add("spring.profiles.active=local-demo");
        properties.add("digital-banking.local-demo.chain-id=31337");
        properties.add("digital-banking.local-demo.admin-redemption-key-alias=ADMIN");
        for (int index = 0; index < wallets.size(); index++) {
            WalletFixture wallet = wallets.get(index);
            String prefix = "digital-banking.local-demo.wallets[" + index + "].";
            properties.add(prefix + "reference=" + wallet.reference());
            if (!wallet.alias().isEmpty()) {
                properties.add(prefix + "aliases[0]=" + wallet.alias());
            }
            properties.add(prefix + "owner-category=" + wallet.owner());
            properties.add(prefix + "network=ETHEREUM");
            properties.add(prefix + "key-reference=local-demo:" + wallet.reference());
            properties.add(prefix + "private-key=" + wallet.privateKey());
            properties.add(prefix + "expected-address=" + wallet.address());
            properties.add(prefix + "allowed-purposes=" + wallet.purposes());
            properties.add(prefix + "enabled=true");
        }
        return new Fixture(
                properties.toArray(String[]::new),
                wallets.stream().map(WalletFixture::privateKey).toList());
    }

    private static WalletFixture wallet(
            String reference, String alias, String owner, String purposes) {
        GeneratedKey generated = generatedKey();
        return new WalletFixture(reference, alias, owner, purposes,
                generated.privateKey(), generated.address());
    }

    private static GeneratedKey generatedKey() {
        SecureRandom random = new SecureRandom();
        while (true) {
            byte[] scalar = new byte[32];
            random.nextBytes(scalar);
            String secret = HEX.formatHex(scalar);
            try (LocalConfiguredSigner signer = new LocalConfiguredSigner(
                    new LocalConfiguredSigner.Configuration(31337, List.of(
                            new LocalConfiguredSigner.ConfiguredWallet(
                                    new WalletReference("synthetic-wallet:test-fixture"),
                                    Set.of(), WalletIdentityRegistry.OwnerCategory.ADMIN,
                                    SettlementNetwork.ETHEREUM,
                                    new KeyAlias("local-demo:test-fixture"),
                                    secret.toCharArray(), "",
                                    Set.of(WalletIdentityRegistry.Purpose.MINT_AUTHORITY),
                                    true))),
                    random)) {
                return new GeneratedKey(
                        secret, signer.identities().getFirst().normalizedAddress());
            } catch (IllegalArgumentException invalidScalar) {
                // Generate another in-memory test scalar; no value is retained or printed.
            }
        }
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return String.valueOf(current.getMessage());
    }

    private record Fixture(String[] properties, List<String> secrets) {
    }

    private record GeneratedKey(String privateKey, String address) {
    }

    private record WalletFixture(
            String reference,
            String alias,
            String owner,
            String purposes,
            String privateKey,
            String address) {
    }
}
