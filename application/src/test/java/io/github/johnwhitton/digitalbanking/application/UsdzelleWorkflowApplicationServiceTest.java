package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowContextResolver;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UsdzelleWorkflowApplicationServiceTest {

    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final IdempotencyKey KEY = new IdempotencyKey("workflow-key-1");
    private static final Instant TIME = Instant.parse("2026-07-18T16:00:00Z");

    @Test
    void acceptsExactServerResolvedAcquisitionAndReplaysWithoutReresolution() {
        InMemoryRepository repository = new InMemoryRepository();
        AtomicInteger resolutions = new AtomicInteger();
        UsdzelleWorkflowApplicationService service = service(repository, resolutions);
        var request = new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                "100", "USD", "synthetic-bank:USER_1_BANK_ACCOUNT", "ETHEREUM");

        UsdzelleWorkflowAcceptance accepted = service.accept(
                UsdzelleWorkflow.Kind.ACQUISITION, PARTICIPANT, request, KEY);
        UsdzelleWorkflowAcceptance replayed = service.accept(
                UsdzelleWorkflow.Kind.ACQUISITION, PARTICIPANT, request, KEY);

        assertFalse(accepted.replayed());
        assertTrue(replayed.replayed());
        assertEquals(accepted.workflow().id(), replayed.workflow().id());
        assertEquals(new BigInteger("10000"),
                accepted.workflow().context().usdAmount().value());
        assertEquals(new BigInteger("10000"),
                accepted.workflow().context().tokenQuantity().atomicUnits());
        assertEquals(new WalletReference("synthetic-wallet:USER_WALLET_1"),
                accepted.workflow().context().userWallet());
        assertEquals(1, resolutions.get());
    }

    @Test
    void conflictingReplayAndCrossParticipantLookupCreateNoSecondParent() {
        InMemoryRepository repository = new InMemoryRepository();
        UsdzelleWorkflowApplicationService service = service(
                repository, new AtomicInteger());
        var original = new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                "100", "USD", "synthetic-bank:USER_1_BANK_ACCOUNT", null);
        UsdzelleWorkflow workflow = service.accept(
                UsdzelleWorkflow.Kind.REDEMPTION, PARTICIPANT, original, KEY).workflow();

        assertThrows(IdempotencyConflictException.class, () -> service.accept(
                UsdzelleWorkflow.Kind.REDEMPTION, PARTICIPANT,
                new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                        "99", "USD", "synthetic-bank:USER_1_BANK_ACCOUNT", null), KEY));
        assertEquals(1, repository.workflows.size());
        assertThrows(UsdzelleWorkflowNotFoundException.class, () -> service.find(
                workflow.id(), new ParticipantScope("tenant-a", "participant-b")));
    }

    @Test
    void rejectsNonCanonicalAmountAndUnsupportedPublicFieldValuesBeforeResolution() {
        AtomicInteger resolutions = new AtomicInteger();
        UsdzelleWorkflowApplicationService service = service(
                new InMemoryRepository(), resolutions);

        assertThrows(InvalidRequestException.class, () -> service.accept(
                UsdzelleWorkflow.Kind.ACQUISITION, PARTICIPANT,
                new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                        "100.00", "USD", "synthetic-bank:USER_1_BANK_ACCOUNT", "ETHEREUM"), KEY));
        assertThrows(InvalidRequestException.class, () -> service.accept(
                UsdzelleWorkflow.Kind.ACQUISITION, PARTICIPANT,
                new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                        "100", "USD", "synthetic-bank:USER_1_BANK_ACCOUNT", "MAINNET"), KEY));
        assertEquals(0, resolutions.get());
    }

    @Test
    void acceptedContextFailsClosedAfterWalletMetadataRotation() {
        UsdzelleWorkflow workflow = service(
                new InMemoryRepository(), new AtomicInteger()).accept(
                        UsdzelleWorkflow.Kind.ACQUISITION, PARTICIPANT,
                        new UsdzelleWorkflowApplicationService.AcceptanceRequest(
                                "100", "USD",
                                "synthetic-bank:USER_1_BANK_ACCOUNT", "ETHEREUM"),
                        KEY).workflow();
        UsdzelleWorkflowContextResolver unchanged =
                (kind, participant, account, currency, network) ->
                        resolution("user-key-v1", network);
        UsdzelleWorkflowContextResolver rotated =
                (kind, participant, account, currency, network) ->
                        resolution("user-key-v2", network);

        unchanged.verifyAccepted(workflow);
        assertThrows(IllegalStateException.class,
                () -> rotated.verifyAccepted(workflow));
    }

    private static UsdzelleWorkflowApplicationService service(
            InMemoryRepository repository, AtomicInteger resolutions) {
        AtomicInteger ids = new AtomicInteger();
        UsdzelleWorkflowIdentityGenerator identityGenerator =
                new UsdzelleWorkflowIdentityGenerator() {
                    @Override
                    public UsdzelleWorkflow.Id nextWorkflowId() {
                        return new UsdzelleWorkflow.Id(new UUID(0, ids.incrementAndGet()));
                    }

                    @Override
                    public UsdzelleWorkflow.StepId nextStepId() {
                        return new UsdzelleWorkflow.StepId(new UUID(1, ids.incrementAndGet()));
                    }

                    @Override
                    public UsdzelleWorkflow.TransitionId nextTransitionId() {
                        return new UsdzelleWorkflow.TransitionId(
                                new UUID(2, ids.incrementAndGet()));
                    }
                };
        UsdzelleWorkflowContextResolver resolver = (kind, participant, account, currency, network) -> {
            resolutions.incrementAndGet();
            assertEquals(new BankAccountReference("synthetic-bank:USER_1_BANK_ACCOUNT"), account);
            return resolution("user-key-v1", network);
        };
        return new UsdzelleWorkflowApplicationService(
                repository, resolver, identityGenerator);
    }

    private static UsdzelleWorkflowContextResolver.Resolution resolution(
            String userMetadataVersion, Optional<SettlementNetwork> network) {
        return new UsdzelleWorkflowContextResolver.Resolution(
                new AssetUnit("USD_STABLE", "USD", 1, 2,
                        new BigInteger("1000000000000")),
                new SyntheticBankAccount.BankId("BANK_1"),
                new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT"),
                new WalletReference("synthetic-wallet:USER_WALLET_1"),
                userMetadataVersion,
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"), "admin-key-v1",
                network.orElse(SettlementNetwork.ETHEREUM), "local-token-v1",
                "payout-before-burn-v1", "user-held-v1",
                "usd-usdzelle-1-to-1-v1", "accounting-v1",
                "fee-v1", "finality-v1", "reconciliation-v1", TIME);
    }

    private static final class InMemoryRepository implements UsdzelleWorkflowRepository {
        private final Map<String, Binding> bindings = new HashMap<>();
        private final Map<UsdzelleWorkflow.Id, UsdzelleWorkflow> workflows = new HashMap<>();

        @Override
        public UsdzelleWorkflowAcceptance accept(
                ParticipantScope participant,
                UsdzelleWorkflow.Kind kind,
                IdempotencyKey key,
                String requestDigest,
                Supplier<UsdzelleWorkflow> workflowFactory) {
            String scope = participant.tenantId() + ":" + participant.participantId()
                    + ":" + kind + ":" + key.sha256();
            Binding existing = bindings.get(scope);
            if (existing != null) {
                if (!existing.requestDigest.equals(requestDigest)) {
                    throw new IdempotencyConflictException();
                }
                return new UsdzelleWorkflowAcceptance(
                        workflows.get(existing.workflowId), true);
            }
            UsdzelleWorkflow workflow = workflowFactory.get();
            bindings.put(scope, new Binding(requestDigest, workflow.id()));
            workflows.put(workflow.id(), workflow);
            return new UsdzelleWorkflowAcceptance(workflow, false);
        }

        @Override
        public Optional<UsdzelleWorkflow> findById(UsdzelleWorkflow.Id workflowId) {
            return Optional.ofNullable(workflows.get(workflowId));
        }

        @Override
        public Optional<UsdzelleWorkflow> findById(
                UsdzelleWorkflow.Id workflowId, ParticipantScope participant) {
            return findById(workflowId).filter(workflow ->
                    workflow.participant().tenantId().equals(participant.tenantId())
                    && workflow.participant().participantId().equals(participant.participantId()));
        }

        @Override
        public void save(UsdzelleWorkflow workflow, long expectedVersion) {
            workflows.put(workflow.id(), workflow);
        }

        private record Binding(String requestDigest, UsdzelleWorkflow.Id workflowId) {
        }
    }
}
