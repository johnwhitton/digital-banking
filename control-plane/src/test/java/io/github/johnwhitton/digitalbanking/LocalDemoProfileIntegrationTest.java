package io.github.johnwhitton.digitalbanking;

import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.AccountingApplicationService;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferChainPort;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.signer.local.LocalConfiguredSigner;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.ClassPathResource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("local-demo")
class LocalDemoProfileIntegrationTest extends PostgresApiIntegrationSupport {

    private static final HexFormat HEX = HexFormat.of();
    private static final Pattern OPERATION_ID =
            Pattern.compile("\\\"operationId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final String MONEY =
            "{\"amount\":\"50\",\"currency\":\"USD\"}";
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

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private AccountingApplicationService accounting;

    @Test
    void localDemoIsReadyButAddsNoSigningOrChainEndpoint() throws Exception {
        assertSame(configuredSigner, signerPort);
        assertSame(configuredSigner, signingKeys);
        assertSame(configuredSigner, wallets);
        assertTrue(signingAuthority != null);
        assertEquals(10, wallets.identities().size());
        assertTrue(context.getBeansOfType(ChainPort.class).isEmpty());
        assertTrue(context.getBeansOfType(WalletTransferChainPort.class).isEmpty());
        assertTrue(mappings.getHandlerMethods().keySet().stream()
                .noneMatch(mapping -> mapping.toString().toLowerCase(Locale.ROOT)
                        .contains("sign")));
        for (ReserveAccounting.PostingType type : List.of(
                ReserveAccounting.PostingType.MINT_CONFIRMED,
                ReserveAccounting.PostingType.REDEMPTION_CUSTODY_CONFIRMED,
                ReserveAccounting.PostingType.BURN_CONFIRMED)) {
            assertThrows(IllegalStateException.class, () -> accounting.post(
                    new ReserveAccounting.EvidenceIdentity(
                            "missing_authoritative_" + type.name().toLowerCase()),
                    type));
        }

        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/env"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void localMockBankApiIsParticipantSafeAndLeastAuthority() throws Exception {
        String withdrawal = "/local/v1/mock-banks/BANK_1/accounts/"
                + "USER_1_BANK_ACCOUNT/withdrawals";
        String deposit = "/local/v1/mock-banks/BANK_2/accounts/"
                + "USER_2_BANK_ACCOUNT/deposits";
        String account = "/local/v1/mock-banks/BANK_1/accounts/USER_1_BANK_ACCOUNT";

        mvc.perform(post(withdrawal).header("Idempotency-Key", "unauthenticated")
                        .contentType("application/json").content(MONEY))
                .andExpect(status().isUnauthorized());
        mvc.perform(post(withdrawal)
                        .with(participant("local-demo", "USER_1", "local-bank:read"))
                        .header("Idempotency-Key", "wrong-authority")
                        .contentType("application/json").content(MONEY))
                .andExpect(status().isForbidden());

        MvcResult first = mvc.perform(post(withdrawal)
                        .with(participant("local-demo", "USER_1", "local-bank:debit"))
                        .header("Idempotency-Key", "local-withdrawal")
                        .contentType("application/json").content(MONEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.balanceAfter").value("50"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andReturn();
        String operationId = operationId(first);

        mvc.perform(post(withdrawal)
                        .with(participant("local-demo", "USER_1", "local-bank:debit"))
                        .header("Idempotency-Key", "local-withdrawal")
                        .contentType("application/json").content(MONEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.operationId").value(operationId))
                .andExpect(jsonPath("$.replayed").value(true));
        mvc.perform(post(withdrawal)
                        .with(participant("local-demo", "USER_1", "local-bank:debit"))
                        .header("Idempotency-Key", "local-withdrawal")
                        .contentType("application/json")
                        .content(MONEY.replace("50", "40")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value(
                        "urn:digital-banking:problem:idempotency-conflict"));

        mvc.perform(get(account)
                        .with(participant("local-demo", "USER_1", "local-bank:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balance").value("50"));
        mvc.perform(get(account)
                        .with(participant("local-demo", "USER_2", "local-bank:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(
                        "urn:digital-banking:problem:local-resource-not-found"));
        mvc.perform(get("/local/v1/mock-banks/operations/{id}", operationId)
                        .with(participant("local-demo", "USER_2", "local-bank:read")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.type").value(
                        "urn:digital-banking:problem:local-resource-not-found"));

        mvc.perform(post(deposit)
                        .with(participant("local-demo", "USER_2", "local-bank:debit"))
                        .header("Idempotency-Key", "wrong-deposit-authority")
                        .contentType("application/json").content(MONEY))
                .andExpect(status().isForbidden());
        mvc.perform(post(deposit)
                        .with(participant("local-demo", "USER_2", "local-bank:credit"))
                        .header("Idempotency-Key", "local-deposit")
                        .contentType("application/json").content(MONEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));

        mvc.perform(post(withdrawal)
                        .with(participant("local-demo", "USER_1", "local-bank:debit"))
                        .header("Idempotency-Key", "caller-control")
                        .contentType("application/json")
                        .content("""
                                {"amount":"1.00","currency":"USD",
                                 "resultingBalance":"999999.00","status":"SUCCEEDED"}
                                """))
                .andExpect(status().isBadRequest());

        for (String invalidAmount : List.of(
                "0", "0.00", "0.10", "1.00", "10000000000000000")) {
            mvc.perform(post(withdrawal)
                            .with(participant(
                                    "local-demo", "USER_1", "local-bank:debit"))
                            .header("Idempotency-Key", "invalid-" + invalidAmount)
                            .contentType("application/json")
                            .content("""
                                    {"amount":"%s","currency":"USD"}
                                    """.formatted(invalidAmount)))
                    .andExpect(status().isBadRequest());
        }

        String contract = new ClassPathResource("openapi/local-mock-banks-v1.yaml")
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        mvc.perform(get("/local/v1/mock-banks/openapi.yaml")
                        .with(participant("local-demo", "USER_1", "local-bank:read")))
                .andExpect(status().isOk())
                .andExpect(content().string(contract));

        var bankCounters = meterRegistry.find("digital_banking.local.bank.operations")
                .counters();
        assertFalse(bankCounters.isEmpty());
        assertTrue(bankCounters.stream().allMatch(counter -> counter.getId().getTags().stream()
                        .noneMatch(tag -> Set.of("participant", "account", "amount")
                                .contains(tag.getKey()))));
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

    private static RequestPostProcessor participant(
            String tenantId, String participantId, String... authorities) {
        ParticipantPrincipal principal = new ParticipantPrincipal(tenantId, participantId);
        UsernamePasswordAuthenticationToken token =
                new UsernamePasswordAuthenticationToken(
                        principal, null, List.of(authorities).stream()
                        .map(SimpleGrantedAuthority::new).toList());
        return authentication(token);
    }

    private static String operationId(MvcResult result) throws Exception {
        Matcher matcher = OPERATION_ID.matcher(result.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private record Fixture(String name, String privateKey, String address) {
    }
}
