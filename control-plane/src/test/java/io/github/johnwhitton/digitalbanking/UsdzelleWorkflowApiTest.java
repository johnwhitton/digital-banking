package io.github.johnwhitton.digitalbanking;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowAcceptance;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowApplicationService;
import io.github.johnwhitton.digitalbanking.controlplane.api.ApiExceptionHandler;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import io.github.johnwhitton.digitalbanking.controlplane.api.UsdzelleWorkflowController;
import io.github.johnwhitton.digitalbanking.controlplane.config.SecurityConfiguration;
import io.github.johnwhitton.digitalbanking.controlplane.config.UsdzelleWorkflowMetrics;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UsdzelleWorkflowController.class)
@ActiveProfiles({"local-demo", "local-ethereum"})
@Import({SecurityConfiguration.class, ApiExceptionHandler.class,
        UsdzelleWorkflowApiTest.StubConfiguration.class})
class UsdzelleWorkflowApiTest {

    private static final UUID ACQUISITION_ID = new UUID(20, 1);
    private static final UUID REDEMPTION_ID = new UUID(20, 2);
    private static final String REQUEST = """
            {
              "amount": "10",
              "currency": "USD",
              "bankAccountReference": "synthetic-bank:USER_1_BANK_ACCOUNT",
              "settlementNetwork": "ETHEREUM"
            }
            """;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private UsdzelleWorkflowApplicationService workflows;

    @BeforeEach
    void stubWorkflows() {
        doAnswer(invocation -> {
            UsdzelleWorkflow.Kind kind = invocation.getArgument(0);
            return new UsdzelleWorkflowAcceptance(workflow(kind), false);
        }).when(workflows).accept(any(), any(), any(), any());
        doAnswer(invocation -> {
            UsdzelleWorkflow.Id id = invocation.getArgument(0);
            return workflow(id.value().equals(ACQUISITION_ID)
                    ? UsdzelleWorkflow.Kind.ACQUISITION
                    : UsdzelleWorkflow.Kind.REDEMPTION);
        }).when(workflows).find(any(), any());
    }

    @Test
    void separatesAcquisitionRedemptionAndReadAuthorities() throws Exception {
        mvc.perform(post("/v1/usdzelle/acquisitions")
                        .header("Idempotency-Key", "unauthenticated")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/v1/usdzelle/acquisitions")
                        .with(participant("usdzelle:redeem"))
                        .header("Idempotency-Key", "wrong-authority")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isForbidden());

        mvc.perform(post("/v1/usdzelle/acquisitions")
                        .with(participant("usdzelle:acquire"))
                        .header("Idempotency-Key", "acquisition-1")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(header().string("Location",
                        "/v1/usdzelle/acquisitions/" + ACQUISITION_ID))
                .andExpect(jsonPath("$.kind").value("ACQUISITION"))
                .andExpect(jsonPath("$.amount").value("10"))
                .andExpect(jsonPath("$.senderWalletReference").doesNotExist())
                .andExpect(jsonPath("$.steps[0].kind").value("WITHDRAWAL"));
        mvc.perform(post("/v1/usdzelle/redemptions")
                        .with(participant("usdzelle:redeem"))
                        .header("Idempotency-Key", "redemption-1")
                        .contentType("application/json").content(REQUEST))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.kind").value("REDEMPTION"))
                .andExpect(jsonPath("$.steps[0].kind").value("CUSTODY_TRANSFER"));

        mvc.perform(post("/v1/usdzelle/acquisitions")
                        .with(participant("usdzelle:acquire"))
                        .header("Idempotency-Key", "caller-wallet")
                        .contentType("application/json")
                        .content(REQUEST.replace(
                                "\"settlementNetwork\": \"ETHEREUM\"",
                                "\"settlementNetwork\": \"ETHEREUM\",\n"
                                        + "  \"senderWalletReference\": "
                                        + "\"synthetic-wallet:CALLER_CONTROLLED\"")))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/v1/usdzelle/acquisitions/{workflowId}", ACQUISITION_ID)
                        .with(participant("usdzelle:acquire")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/v1/usdzelle/acquisitions/{workflowId}", ACQUISITION_ID)
                        .with(participant("usdzelle:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value(ACQUISITION_ID.toString()));
    }

    @Test
    void rejectsNonCanonicalUsdRequests() throws Exception {
        for (String amount : List.of("1e2", "-1")) {
            mvc.perform(post("/v1/usdzelle/acquisitions")
                            .with(participant("usdzelle:acquire"))
                            .header("Idempotency-Key", "invalid-amount")
                            .contentType("application/json")
                            .content(REQUEST.replace("\"10\"", "\"" + amount + "\"")))
                    .andExpect(status().isBadRequest());
        }
    }

    private static RequestPostProcessor participant(String authority) {
        var principal = new ParticipantPrincipal("local-demo", "USER_1");
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private static UsdzelleWorkflow workflow(UsdzelleWorkflow.Kind kind) {
        UUID workflowId = kind == UsdzelleWorkflow.Kind.ACQUISITION
                ? ACQUISITION_ID : REDEMPTION_ID;
        AssetUnit unit = new AssetUnit(
                "USDZELLE", "CENT", 1, 2, new BigInteger("999999999999999999"));
        UsdzelleWorkflow.AcceptedContext context = new UsdzelleWorkflow.AcceptedContext(
                "phase-6b-v1", UsdCents.positive(new BigInteger("1000")),
                TokenQuantity.ofAtomic(new BigInteger("1000"), unit),
                new SyntheticBankAccount.BankId("BANK_1"),
                new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT"),
                new WalletReference("synthetic-wallet:USER_WALLET_1"), "user-wallet-v1",
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"), "admin-wallet-v1",
                SettlementNetwork.ETHEREUM, "local-token-v1",
                "payout-before-burn-v1", "one-to-one-v1", "accounting-v1",
                "no-fee-v1", "finality-v1", "reconciliation-v1",
                "a".repeat(64), "b".repeat(64), Instant.parse("2026-07-18T18:00:00Z"));
        int stepCount = kind == UsdzelleWorkflow.Kind.ACQUISITION ? 5 : 6;
        List<UsdzelleWorkflow.StepId> steps = java.util.stream.LongStream
                .rangeClosed(1, stepCount)
                .mapToObj(value -> new UsdzelleWorkflow.StepId(new UUID(21, value)))
                .toList();
        return UsdzelleWorkflow.accepted(
                new UsdzelleWorkflow.Id(workflowId), kind,
                new UsdzelleWorkflow.Participant("local-demo", "USER_1"),
                context, steps, new UsdzelleWorkflow.TransitionId(new UUID(22, 1)),
                new EvidenceRef("participant:usdzelle:accepted"));
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableWebSecurity
    static class StubConfiguration {
        @Bean
        UsdzelleWorkflowApplicationService usdzelleWorkflowApplicationService() {
            return mock(UsdzelleWorkflowApplicationService.class);
        }

        @Bean
        UsdzelleWorkflowMetrics usdzelleWorkflowMetrics() {
            return mock(UsdzelleWorkflowMetrics.class);
        }
    }
}
