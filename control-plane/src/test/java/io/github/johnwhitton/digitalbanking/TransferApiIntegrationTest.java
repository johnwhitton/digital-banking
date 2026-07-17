package io.github.johnwhitton.digitalbanking;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class TransferApiIntegrationTest extends PostgresApiIntegrationSupport {

    private static final Pattern TRANSFER_ID = Pattern.compile(
            "\\\"transferId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final String VALID_REQUEST = """
            {
              "amount": "12.34",
              "currency": "USD",
              "sourceBankAccountReference": "synthetic-bank:source-001",
              "destinationBankAccountReference": "synthetic-bank:destination-001",
              "settlementNetwork": "ETHEREUM"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @BeforeEach
    void clearBusinessData() {
        jdbc.sql("DELETE FROM operation_delivery_attempt").update();
        jdbc.sql("DELETE FROM transfer_handler_inbox").update();
        jdbc.sql("DELETE FROM operation_outbox").update();
        jdbc.sql("DELETE FROM transfer_finality_evidence").update();
        jdbc.sql("DELETE FROM transfer_finality").update();
        jdbc.sql("DELETE FROM transfer_transition_evidence").update();
        jdbc.sql("DELETE FROM transfer_transition").update();
        jdbc.sql("DELETE FROM transfer_effect_evidence").update();
        jdbc.sql("DELETE FROM transfer_effect").update();
        jdbc.sql("DELETE FROM transfer_idempotency").update();
        jdbc.sql("DELETE FROM banking_transfer").update();
    }

    @Test
    void acceptsAndReadsFiveEffectPlanWithSeparateAuthorities() throws Exception {
        mvc.perform(post("/v1/transfers")
                        .with(participant("tenant-a", "participant-a", "transfer:read"))
                        .header("Idempotency-Key", "wrong-authority")
                        .contentType("application/json").content(VALID_REQUEST))
                .andExpect(status().isForbidden());

        MvcResult accepted = accept("transfer:create", "accepted-transfer", VALID_REQUEST)
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location", matchesPattern(
                        "/v1/transfers/[0-9a-f-]{36}")))
                .andExpect(jsonPath("$.amount").value("12.34"))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.settlementNetwork").value("ETHEREUM"))
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.effects.length()").value(5))
                .andExpect(jsonPath("$.effects[0].kind").value("BANK_WITHDRAWAL"))
                .andExpect(jsonPath("$.effects[4].kind").value("BANK_DEPOSIT"))
                .andExpect(jsonPath("$.senderWallet").doesNotExist())
                .andExpect(jsonPath("$.walletPolicyVersion").doesNotExist())
                .andReturn();
        String transferId = transferId(accepted);

        mvc.perform(get("/v1/transfers/{transferId}", transferId)
                        .with(participant("tenant-a", "participant-a", "transfer:create")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/transfers/{transferId}", transferId)
                        .with(participant("tenant-a", "participant-a", "transfer:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId))
                .andExpect(jsonPath("$.finalities.blockchain[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.legal[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.customerVisible[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.accounting[0].status")
                        .value("NOT_ASSESSED"))
                .andExpect(jsonPath("$.finalities.blockchain[0].authority").doesNotExist());
        assertEquals(1L, count("banking_transfer"));
        assertEquals(5L, count("transfer_effect"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void replaysExactlyConflictsSafelyAndDefaultsRoute() throws Exception {
        String withoutNetwork = VALID_REQUEST.replace(
                ",\n  \"settlementNetwork\": \"ETHEREUM\"", "");
        MvcResult first = accept(
                "transfer:create", "transfer-replay", withoutNetwork)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.settlementNetwork").value("ETHEREUM"))
                .andReturn();
        MvcResult replay = accept(
                "transfer:create", "transfer-replay", withoutNetwork)
                .andExpect(status().isAccepted()).andReturn();
        MvcResult conflict = accept(
                "transfer:create", "transfer-replay",
                withoutNetwork.replace("12.34", "12.35"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type")
                        .value("urn:digital-banking:problem:idempotency-conflict"))
                .andReturn();

        assertEquals(first.getResponse().getContentAsString(),
                replay.getResponse().getContentAsString());
        assertFalse(conflict.getResponse().getContentAsString().contains("transfer-replay"));
        assertEquals(1L, count("banking_transfer"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void acceptsPositiveSubunitAmountResolvedByTheAssetCatalog() throws Exception {
        accept("transfer:create", "subunit-transfer",
                VALID_REQUEST.replace("12.34", "0.01"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.amount").value("0.01"));

        assertEquals(1L, count("banking_transfer"));
        assertEquals(5L, count("transfer_effect"));
        assertEquals(1L, count("operation_outbox"));
    }

    @Test
    void rejectsWalletOverridesUnsupportedInputAndUnauthenticatedRequests() throws Exception {
        accept("transfer:create", "wallet-override",
                VALID_REQUEST.replace(
                        "\"settlementNetwork\": \"ETHEREUM\"",
                        "\"settlementNetwork\": \"ETHEREUM\",\n"
                                + "  \"senderWallet\": \"synthetic-wallet:caller\""))
                .andExpect(status().isBadRequest());
        accept("transfer:create", "recipient-override",
                VALID_REQUEST.replace(
                        "\"settlementNetwork\": \"ETHEREUM\"",
                        "\"settlementNetwork\": \"ETHEREUM\",\n"
                                + "  \"recipientWalletReference\": \"synthetic-wallet:caller\""))
                .andExpect(status().isBadRequest());
        accept("transfer:create", "unsupported-route",
                VALID_REQUEST.replace("ETHEREUM", "POLYGON"))
                .andExpect(status().isBadRequest());
        accept("transfer:create", "noncanonical-amount",
                VALID_REQUEST.replace("12.34", "12.340"))
                .andExpect(status().isBadRequest());
        accept("transfer:create", "real-account",
                VALID_REQUEST.replace("synthetic-bank:source-001", "123456789"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/v1/transfers")
                        .header("Idempotency-Key", "unauthenticated")
                        .contentType("application/json").content(VALID_REQUEST))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType("application/problem+json"));
        assertEquals(0L, count("banking_transfer"));
    }

    @Test
    void missingAndOtherParticipantTransfersAreIndistinguishable() throws Exception {
        String transferId = transferId(accept(
                "transfer:create", "participant-private", VALID_REQUEST)
                .andExpect(status().isAccepted()).andReturn());
        MvcResult other = mvc.perform(get("/v1/transfers/{transferId}", transferId)
                        .queryParam("participantId", "participant-a")
                        .header("X-Participant-Id", "participant-a")
                        .with(participant("tenant-a", "participant-b", "transfer:read")))
                .andExpect(status().isNotFound()).andReturn();
        MvcResult missing = mvc.perform(get("/v1/transfers/{transferId}",
                        "00000000-0000-0000-0000-000000000099")
                        .with(participant("tenant-a", "participant-a", "transfer:read")))
                .andExpect(status().isNotFound()).andReturn();

        assertEquals(other.getResponse().getContentAsString(),
                missing.getResponse().getContentAsString());
    }

    private org.springframework.test.web.servlet.ResultActions accept(
            String authority, String key, String request) throws Exception {
        return mvc.perform(post("/v1/transfers")
                .with(participant("tenant-a", "participant-a", authority))
                .header("Idempotency-Key", key)
                .contentType("application/json")
                .content(request));
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

    private static String transferId(MvcResult result) throws Exception {
        Matcher matcher = TRANSFER_ID.matcher(result.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private long count(String table) {
        if (!List.of("banking_transfer", "transfer_effect", "operation_outbox")
                .contains(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }
}
