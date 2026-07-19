package io.github.johnwhitton.digitalbanking.application.delivery;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferStepExecutor;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettlementTransferAcceptedDeliveryHandlerTest {

    private static final Instant TIME = Instant.parse("2026-07-18T20:00:00Z");

    @Test
    void attachesOneChildPerBoundaryAndCompletesOnlyAfterReconciliation() {
        List<SettlementTransfer.BoundaryKind> calls = new ArrayList<>();
        Harness harness = harness(transfer -> {
            SettlementTransfer.Boundary current = transfer.currentBoundary();
            calls.add(current.kind());
            if (current.kind()
                    == SettlementTransfer.BoundaryKind.FINAL_RECONCILIATION) {
                return new SettlementTransferStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.RECONCILED,
                        evidence("final-reconciled"));
            }
            if (current.child().isEmpty()) {
                return new SettlementTransferStepExecutor.Dispatched(
                        child(current.kind().name()), evidence("child-accepted"));
            }
            return new SettlementTransferStepExecutor.Confirmed(
                    current.child(), evidence("child-confirmed"));
        });

        for (int invocation = 0; invocation < 7; invocation++) {
            DeliveryOutcome outcome = harness.handler.handle(harness.delivery);
            assertEquals(invocation == 6
                            ? DeliveryOutcome.Classification.DELIVERED
                            : DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    outcome.classification());
        }

        assertEquals(List.of(
                SettlementTransfer.BoundaryKind.SENDER_ACQUISITION,
                SettlementTransfer.BoundaryKind.SENDER_ACQUISITION,
                SettlementTransfer.BoundaryKind.USER_TRANSFER,
                SettlementTransfer.BoundaryKind.USER_TRANSFER,
                SettlementTransfer.BoundaryKind.RECIPIENT_REDEMPTION,
                SettlementTransfer.BoundaryKind.RECIPIENT_REDEMPTION,
                SettlementTransfer.BoundaryKind.FINAL_RECONCILIATION), calls);
        assertEquals(SettlementTransfer.Status.COMPLETED,
                harness.repository.value.status());
        assertEquals(ReserveAccounting.ReconciliationStatus.RECONCILED,
                harness.repository.value.conclusion().orElseThrow());
    }

    @Test
    void ambiguityRetainsTheFirstChildIdentityUntilAuthoritativeConfirmation() {
        AtomicInteger calls = new AtomicInteger();
        SettlementTransfer.ChildReference retained = child("sender-acquisition");
        Harness harness = harness(transfer -> switch (calls.getAndIncrement()) {
            case 0, 1 -> new SettlementTransferStepExecutor.Unknown(
                    retained, evidence("child-unknown"));
            default -> new SettlementTransferStepExecutor.Confirmed(
                    Optional.of(retained), evidence("child-confirmed"));
        });

        assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(SettlementTransfer.Status.SENDER_ACQUISITION_UNKNOWN,
                harness.repository.value.status());
        assertEquals(retained,
                harness.repository.value.currentBoundary().child().orElseThrow());

        assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(retained,
                harness.repository.value.currentBoundary().child().orElseThrow());

        assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(SettlementTransfer.Status.SENDER_ACQUISITION_COMPLETED,
                harness.repository.value.status());
    }

    @Test
    void definitiveFailureAndReconciliationMismatchCannotReportCompletion() {
        Harness rejected = harness(transfer ->
                new SettlementTransferStepExecutor.RejectedNoEffect(
                        evidence("child-rejected")));
        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                rejected.handler.handle(rejected.delivery).classification());
        assertEquals(SettlementTransfer.Status.FAILED_NO_EFFECT,
                rejected.repository.value.status());

        Harness mismatch = harness(transfer -> {
            SettlementTransfer.Boundary current = transfer.currentBoundary();
            if (current.kind()
                    == SettlementTransfer.BoundaryKind.FINAL_RECONCILIATION) {
                return new SettlementTransferStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                        evidence("supply-mismatch"));
            }
            if (current.child().isEmpty()) {
                return new SettlementTransferStepExecutor.Dispatched(
                        child(current.kind().name()), evidence("child-accepted"));
            }
            return new SettlementTransferStepExecutor.Confirmed(
                    current.child(), evidence("child-confirmed"));
        });
        DeliveryOutcome outcome = null;
        for (int invocation = 0; invocation < 7; invocation++) {
            outcome = mismatch.handler.handle(mismatch.delivery);
        }
        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                outcome.classification());
        assertEquals(SettlementTransfer.Status.MANUAL_REVIEW,
                mismatch.repository.value.status());
        assertEquals(ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                mismatch.repository.value.conclusion().orElseThrow());
    }

    @Test
    void noEffectAfterCompletedAcquisitionRetainsPartialProgressForManualReview() {
        Harness harness = harness(transfer -> {
            SettlementTransfer.Boundary current = transfer.currentBoundary();
            if (current.kind() == SettlementTransfer.BoundaryKind.USER_TRANSFER) {
                return new SettlementTransferStepExecutor.RejectedNoEffect(
                        evidence("transfer-no-effect"));
            }
            if (current.child().isEmpty()) {
                return new SettlementTransferStepExecutor.Dispatched(
                        child("acquisition"), evidence("acquisition-accepted"));
            }
            return new SettlementTransferStepExecutor.Confirmed(
                    current.child(), evidence("acquisition-confirmed"));
        });

        assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                harness.handler.handle(harness.delivery).classification());
        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                harness.handler.handle(harness.delivery).classification());

        assertEquals(SettlementTransfer.Status.MANUAL_REVIEW,
                harness.repository.value.status());
        assertEquals(SettlementTransfer.BoundaryStatus.COMPLETED,
                harness.repository.value.boundaries().getFirst().status());
        assertEquals(SettlementTransfer.BoundaryStatus.MANUAL_REVIEW,
                harness.repository.value.currentBoundary().status());
    }

    private static Harness harness(SettlementTransferStepExecutor steps) {
        InMemoryRepository repository = new InMemoryRepository(accepted());
        AtomicInteger sequence = new AtomicInteger(100);
        SettlementTransferIdentityGenerator ids = new SettlementTransferIdentityGenerator() {
            @Override
            public SettlementTransfer.BoundaryId nextBoundaryId() {
                return new SettlementTransfer.BoundaryId(
                        new UUID(8, sequence.incrementAndGet()));
            }

            @Override
            public SettlementTransfer.TransitionId nextTransitionId() {
                return new SettlementTransfer.TransitionId(
                        new UUID(9, sequence.incrementAndGet()));
            }
        };
        SettlementTransferAcceptedDeliveryHandler handler =
                new SettlementTransferAcceptedDeliveryHandler(
                        repository, steps,
                        () -> TIME.plusSeconds(sequence.incrementAndGet()), ids);
        OperationDelivery delivery = new OperationDelivery(
                new UUID(20, 1), repository.value.transferId().value(),
                SettlementTransferAcceptedDeliveryHandler.EVENT_TYPE, 1, 1,
                new UUID(20, 2), "settlement-worker", 1);
        return new Harness(repository, handler, delivery);
    }

    private static SettlementTransfer accepted() {
        return SettlementTransfer.accepted(
                new TransferId(new UUID(0, 1)), context(),
                List.of(
                        new SettlementTransfer.BoundaryId(new UUID(0, 11)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 12)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 13)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 14))),
                new SettlementTransfer.TransitionId(new UUID(0, 20)),
                evidence("accepted"));
    }

    private static SettlementTransfer.AcceptedContext context() {
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
        SettlementTransfer.RouteSnapshot sender = route(
                "sender", "USER_1", "BANK_1", "USER_1_BANK_ACCOUNT",
                "USER_WALLET_1", SettlementTransfer.InstructionMode.ACQUISITION);
        SettlementTransfer.RouteSnapshot recipient = route(
                "recipient", "USER_2", "BANK_2", "USER_2_BANK_ACCOUNT",
                "USER_WALLET_2", SettlementTransfer.InstructionMode.AUTO_REDEEM);
        return new SettlementTransfer.AcceptedContext(
                "phase-6c-v1", UsdCents.positive(new BigInteger("10000")),
                TokenQuantity.ofAtomic(new BigInteger("10000"), unit),
                sender, recipient,
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"),
                "admin-wallet-v1", SettlementNetwork.ETHEREUM,
                "0x0000000000000000000000000000000000000001",
                "payout-before-burn-v1", "usd-usdzelle-cent-v1",
                "reserve-accounting-v1", "no-fee-local-v1",
                "local-ethereum-finality-v1", "reserve-chain-reconciliation-v1",
                "a".repeat(64), "b".repeat(64), TIME);
    }

    private static SettlementTransfer.RouteSnapshot route(
            String instruction, String participant, String bank,
            String account, String wallet, SettlementTransfer.InstructionMode mode) {
        return new SettlementTransfer.RouteSnapshot(
                "local-" + instruction, "instruction-v1",
                new SettlementTransfer.Participant("local-demo", participant),
                new SyntheticBankAccount.BankId(bank),
                new SyntheticBankAccount.AccountId(account),
                new BankAccountReference("synthetic-bank:" + account),
                new WalletReference("synthetic-wallet:" + wallet),
                instruction + "-wallet-v1", mode);
    }

    private static SettlementTransfer.ChildReference child(String value) {
        return new SettlementTransfer.ChildReference(value.toLowerCase());
    }

    private static EvidenceRef evidence(String value) {
        return new EvidenceRef("internal:settlement-transfer:" + value);
    }

    private record Harness(
            InMemoryRepository repository,
            SettlementTransferAcceptedDeliveryHandler handler,
            OperationDelivery delivery) { }

    private static final class InMemoryRepository
            implements SettlementTransferRepository {
        private SettlementTransfer value;

        private InMemoryRepository(SettlementTransfer value) {
            this.value = value;
        }

        @Override
        public Optional<SettlementTransfer> findById(TransferId transferId) {
            return value.transferId().equals(transferId)
                    ? Optional.of(value) : Optional.empty();
        }

        @Override
        public Optional<SettlementTransfer> findById(
                TransferId transferId, ParticipantScope sender) {
            return findById(transferId);
        }

        @Override
        public void save(SettlementTransfer transfer, long expectedVersion) {
            if (value.version() != expectedVersion) {
                throw new IllegalStateException("version conflict");
            }
            value = transfer;
        }
    }
}
