package io.github.johnwhitton.digitalbanking;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.signer.local.LocalConfiguredSigner;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
class LocalDemoProfileIntegrationTest extends PostgresApiIntegrationSupport {

    private static final HexFormat HEX = HexFormat.of();
    private static final List<Fixture> WALLETS = List.of(
            fixture("CONTRACT_OWNER"), fixture("ADMIN"),
            fixture("BANK_1"), fixture("BANK_2"), fixture("BANK_3"), fixture("BANK_4"),
            fixture("USER_WALLET_1"), fixture("USER_WALLET_2"),
            fixture("USER_WALLET_3"), fixture("USER_WALLET_4"));

    @DynamicPropertySource
    static void localDemoProperties(DynamicPropertyRegistry registry) {
        for (Fixture fixture : WALLETS) {
            registry.add("LOCAL_DEMO_" + fixture.name() + "_PRIVATE_KEY",
                    fixture::privateKey);
            registry.add("LOCAL_DEMO_" + fixture.name() + "_EXPECTED_ADDRESS",
                    fixture::address);
        }
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private LocalConfiguredSigner configuredSigner;

    @Autowired
    private SignerPort signerPort;

    @Autowired
    private SigningKeyRegistry signingKeys;

    @Autowired
    private WalletIdentityRegistry wallets;

    @Autowired
    private SigningAuthorityService signingAuthority;

    @Autowired
    private MockMvc mvc;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping mappings;

    @Test
    void localDemoIsReadyButAddsNoSigningOrChainEndpoint() throws Exception {
        assertSame(configuredSigner, signerPort);
        assertSame(configuredSigner, signingKeys);
        assertSame(configuredSigner, wallets);
        assertTrue(signingAuthority != null);
        assertEquals(10, wallets.identities().size());
        assertTrue(context.getBeansOfType(ChainPort.class).isEmpty());
        assertTrue(mappings.getHandlerMethods().keySet().stream()
                .noneMatch(mapping -> mapping.toString().toLowerCase(Locale.ROOT)
                        .contains("sign")));

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    private static Fixture fixture(String name) {
        SecureRandom random = new SecureRandom();
        while (true) {
            byte[] scalar = new byte[32];
            random.nextBytes(scalar);
            String secret = HEX.formatHex(scalar);
            try (LocalConfiguredSigner signer = new LocalConfiguredSigner(
                    new LocalConfiguredSigner.Configuration(31337, List.of(
                            new LocalConfiguredSigner.ConfiguredWallet(
                                    new WalletReference("synthetic-wallet:test-" + name),
                                    Set.of(), WalletIdentityRegistry.OwnerCategory.ADMIN,
                                    SettlementNetwork.ETHEREUM,
                                    new KeyAlias("local-demo:test-" + name),
                                    secret.toCharArray(), "",
                                    Set.of(WalletIdentityRegistry.Purpose.MINT_AUTHORITY),
                                    true))),
                    random)) {
                return new Fixture(
                        name, secret,
                        signer.identities().getFirst().normalizedAddress());
            } catch (IllegalArgumentException invalidScalar) {
                // Generate another in-memory test scalar; no value is retained or printed.
            }
        }
    }

    private record Fixture(String name, String privateKey, String address) {
    }
}
