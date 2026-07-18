package io.github.johnwhitton.digitalbanking.application.delivery;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsdzelleWorkflowAcceptedDeliveryHandlerTest {

    private static final Instant TIME = Instant.parse("2026-07-18T20:00:00Z");

    @Test
    void acquisitionAdvancesOnlyInTheDurableOrderAndDerivesCompletion() {
        Harness harness = harness(UsdzelleWorkflow.Kind.ACQUISITION, workflow -> {
            UsdzelleWorkflow.StepKind kind = workflow.currentStep().kind();
            if (kind == UsdzelleWorkflow.StepKind.RECONCILIATION) {
                return new UsdzelleWorkflowStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.RECONCILED,
                        evidence("reconciled"));
            }
            return new UsdzelleWorkflowStepExecutor.Confirmed(
                    external(kind)
                            ? Optional.of(child(kind.name().toLowerCase()))
                            : Optional.empty(),
                    evidence("confirmed-" + kind.name().toLowerCase()));
        });

        for (int step = 0; step < 5; step++) {
            DeliveryOutcome outcome = harness.handler.handle(harness.delivery);
            assertEquals(step == 4
                            ? DeliveryOutcome.Classification.DELIVERED
                            : DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    outcome.classification());
        }
        assertEquals(List.of(
                UsdzelleWorkflow.StepKind.WITHDRAWAL,
                UsdzelleWorkflow.StepKind.RESERVE_FUNDING_POST,
                UsdzelleWorkflow.StepKind.MINT,
                UsdzelleWorkflow.StepKind.MINT_ACCOUNTING_POST,
                UsdzelleWorkflow.StepKind.RECONCILIATION), harness.calls);
        assertEquals(UsdzelleWorkflow.Status.COMPLETED, harness.repository.value.status());
    }

    @Test
    void redemptionEnforcesPayoutAndAccountingBeforeBurn() {
        Harness harness = harness(UsdzelleWorkflow.Kind.REDEMPTION, workflow -> {
            UsdzelleWorkflow.StepKind kind = workflow.currentStep().kind();
            if (kind == UsdzelleWorkflow.StepKind.RECONCILIATION) {
                return new UsdzelleWorkflowStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.RECONCILED,
                        evidence("reconciled"));
            }
            return new UsdzelleWorkflowStepExecutor.Confirmed(
                    external(kind)
                            ? Optional.of(child(kind.name().toLowerCase()))
                            : Optional.empty(),
                    evidence("confirmed-" + kind.name().toLowerCase()));
        });

        for (int step = 0; step < 6; step++) {
            harness.handler.handle(harness.delivery);
        }
        assertEquals(List.of(
                UsdzelleWorkflow.StepKind.CUSTODY_TRANSFER,
                UsdzelleWorkflow.StepKind.CUSTODY_ACCOUNTING_POST,
                UsdzelleWorkflow.StepKind.PAYOUT,
                UsdzelleWorkflow.StepKind.PAYOUT_ACCOUNTING_POST,
                UsdzelleWorkflow.StepKind.BURN,
                UsdzelleWorkflow.StepKind.RECONCILIATION), harness.calls);
        assertEquals(UsdzelleWorkflow.Status.COMPLETED, harness.repository.value.status());
    }

    @Test
    void anUnknownBankEffectRetainsItsIdentityAndIsInquiredBeforeProgress() {
        AtomicLong calls = new AtomicLong();
        Harness harness = harness(UsdzelleWorkflow.Kind.ACQUISITION, workflow -> {
            if (calls.getAndIncrement() == 0) {
                return new UsdzelleWorkflowStepExecutor.Unknown(
                        child("bank-operation-1"), evidence("response-lost"));
            }
            return new UsdzelleWorkflowStepExecutor.Confirmed(
                    Optional.of(child("bank-operation-1")), evidence("inquiry-confirmed"));
        });

        assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(UsdzelleWorkflow.Status.WITHDRAWAL_UNKNOWN,
                harness.repository.value.status());
        assertEquals("bank-operation-1", harness.repository.value.currentStep()
                .childReference().orElseThrow().value());

        assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(UsdzelleWorkflow.Status.WITHDRAWAL_CONFIRMED,
                harness.repository.value.status());
        assertEquals(2, calls.get());
    }

    @Test
    void definitiveNoEffectAndMismatchRemainExplicitTerminalConclusions() {
        Harness rejected = harness(
                UsdzelleWorkflow.Kind.ACQUISITION,
                workflow -> new UsdzelleWorkflowStepExecutor.RejectedNoEffect(
                        evidence("withdrawal-rejected")));
        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                rejected.handler.handle(rejected.delivery).classification());
        assertEquals(UsdzelleWorkflow.Status.FAILED_NO_EFFECT,
                rejected.repository.value.status());

        Harness mismatch = harness(UsdzelleWorkflow.Kind.ACQUISITION, workflow -> {
            UsdzelleWorkflow.StepKind kind = workflow.currentStep().kind();
            if (kind == UsdzelleWorkflow.StepKind.RECONCILIATION) {
                return new UsdzelleWorkflowStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                        evidence("supply-mismatch"));
            }
            return new UsdzelleWorkflowStepExecutor.Confirmed(
                    external(kind) ? Optional.of(child(kind.name())) : Optional.empty(),
                    evidence("confirmed-" + kind.name()));
        });
        for (int step = 0; step < 5; step++) {
            mismatch.handler.handle(mismatch.delivery);
        }
        assertEquals(UsdzelleWorkflow.Status.MANUAL_REVIEW,
                mismatch.repository.value.status());
        assertEquals(ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                mismatch.repository.value.reconciliationConclusion().orElseThrow());
    }

    private static Harness harness(
            UsdzelleWorkflow.Kind kind, UsdzelleWorkflowStepExecutor delegate) {
        InMemoryRepository repository = new InMemoryRepository(accepted(kind));
        List<UsdzelleWorkflow.StepKind> calls = new ArrayList<>();
        UsdzelleWorkflowStepExecutor recording = workflow -> {
            calls.add(workflow.currentStep().kind());
            return delegate.execute(workflow);
        };
        AtomicLong ids = new AtomicLong(100);
        UsdzelleWorkflowIdentityGenerator identities = new UsdzelleWorkflowIdentityGenerator() {
            @Override
            public UsdzelleWorkflow.Id nextWorkflowId() {
                return new UsdzelleWorkflow.Id(new UUID(7, ids.incrementAndGet()));
            }

            @Override
            public UsdzelleWorkflow.StepId nextStepId() {
                return new UsdzelleWorkflow.StepId(new UUID(8, ids.incrementAndGet()));
            }

            @Override
            public UsdzelleWorkflow.TransitionId nextTransitionId() {
                return new UsdzelleWorkflow.TransitionId(new UUID(9, ids.incrementAndGet()));
            }
        };
        var handler = new UsdzelleWorkflowAcceptedDeliveryHandler(
                repository, recording, () -> TIME.plusSeconds(ids.incrementAndGet()), identities);
        var delivery = new OperationDelivery(
                new UUID(20, 1), repository.value.id().value(),
                UsdzelleWorkflowAcceptedDeliveryHandler.EVENT_TYPE, 1, 1,
                new UUID(20, 2), "workflow-worker", 1);
        return new Harness(repository, handler, delivery, calls);
    }

    private static UsdzelleWorkflow accepted(UsdzelleWorkflow.Kind kind) {
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
        int count = kind == UsdzelleWorkflow.Kind.ACQUISITION ? 5 : 6;
        List<UsdzelleWorkflow.StepId> steps = new ArrayList<>();
        for (int index = 1; index <= count; index++) {
            steps.add(new UsdzelleWorkflow.StepId(new UUID(2, index)));
        }
        return UsdzelleWorkflow.accepted(
                new UsdzelleWorkflow.Id(new UUID(1, kind.ordinal() + 1)), kind,
                new UsdzelleWorkflow.Participant("tenant-a", "participant-a"),
                new UsdzelleWorkflow.AcceptedContext(
                        "phase-6b-v1", UsdCents.positive(new BigInteger("10000")),
                        TokenQuantity.parse("100", unit),
                        new SyntheticBankAccount.BankId("BANK_1"),
                        new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT"),
                        new WalletReference("synthetic-wallet:USER_WALLET_1"), "user-v1",
                        new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"), "admin-v1",
                        SettlementNetwork.ETHEREUM, "local-token-v1",
                        "payout-before-burn-v1", "conversion-v1",
                        "accounting-v1", "fee-v1", "finality-v1", "reconciliation-v1",
                        "a".repeat(64), "b".repeat(64), TIME),
                steps, new UsdzelleWorkflow.TransitionId(new UUID(3, 1)),
                evidence("accepted"));
    }

    private static boolean external(UsdzelleWorkflow.StepKind kind) {
        return switch (kind) {
            case WITHDRAWAL, MINT, CUSTODY_TRANSFER, PAYOUT, BURN -> true;
            default -> false;
        };
    }

    private static EvidenceRef evidence(String value) {
        return new EvidenceRef("internal:workflow:" + value.toLowerCase());
    }

    private static UsdzelleWorkflow.ChildReference child(String value) {
        return new UsdzelleWorkflow.ChildReference(value.toLowerCase());
    }

    private record Harness(
            InMemoryRepository repository,
            UsdzelleWorkflowAcceptedDeliveryHandler handler,
            OperationDelivery delivery,
            List<UsdzelleWorkflow.StepKind> calls) {
    }

    private static final class InMemoryRepository implements UsdzelleWorkflowRepository {
        private UsdzelleWorkflow value;

        private InMemoryRepository(UsdzelleWorkflow value) {
            this.value = value;
        }

        @Override
        public UsdzelleWorkflowAcceptance accept(
                ParticipantScope participant, UsdzelleWorkflow.Kind kind,
                IdempotencyKey key, String requestDigest,
                Supplier<UsdzelleWorkflow> workflowFactory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<UsdzelleWorkflow> findById(UsdzelleWorkflow.Id workflowId) {
            return value.id().equals(workflowId) ? Optional.of(value) : Optional.empty();
        }

        @Override
        public Optional<UsdzelleWorkflow> findById(
                UsdzelleWorkflow.Id workflowId, ParticipantScope participant) {
            return findById(workflowId);
        }

        @Override
        public void save(UsdzelleWorkflow workflow, long expectedVersion) {
            if (value.version() != expectedVersion) {
                throw new IllegalStateException("version conflict");
            }
            value = workflow;
        }
    }
}
