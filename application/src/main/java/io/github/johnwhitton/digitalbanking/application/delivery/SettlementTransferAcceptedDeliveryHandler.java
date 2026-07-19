package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferStepExecutor;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Advances one durable Phase 6C child boundary per leased delivery. */
public final class SettlementTransferAcceptedDeliveryHandler
        implements OperationDeliveryHandler {

    public static final String EVENT_TYPE = "SettlementTransferAccepted";

    private final SettlementTransferRepository settlements;
    private final SettlementTransferStepExecutor steps;
    private final ClockPort clock;
    private final SettlementTransferIdentityGenerator ids;

    public SettlementTransferAcceptedDeliveryHandler(
            SettlementTransferRepository settlements,
            SettlementTransferStepExecutor steps,
            ClockPort clock,
            SettlementTransferIdentityGenerator ids) {
        this.settlements = Objects.requireNonNull(settlements, "settlements");
        this.steps = Objects.requireNonNull(steps, "steps");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public DeliveryOutcome handle(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        if (!EVENT_TYPE.equals(delivery.eventType())
                || delivery.eventVersion() != 1
                || delivery.payloadSchemaVersion() != 1) {
            return DeliveryOutcome.terminalFailure(
                    "unsupported-settlement-transfer-delivery");
        }
        SettlementTransfer transfer = settlements.findById(
                        new io.github.johnwhitton.digitalbanking.domain.transfer.TransferId(
                                delivery.aggregateId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "settlement transfer was not found"));
        if (transfer.status() == SettlementTransfer.Status.COMPLETED) {
            return DeliveryOutcome.duplicate();
        }
        if (transfer.status() == SettlementTransfer.Status.MANUAL_REVIEW
                || transfer.status() == SettlementTransfer.Status.FAILED_NO_EFFECT) {
            return DeliveryOutcome.terminalFailure(
                    "settlement-transfer-not-executable");
        }

        if (transfer.currentBoundary().status()
                == SettlementTransfer.BoundaryStatus.ELIGIBLE) {
            long expected = transfer.version();
            SettlementTransfer.Boundary current = transfer.currentBoundary();
            transfer = transfer.beginCurrent(
                    expected, ids.nextTransitionId(),
                    new EvidenceRef(
                            "internal:settlement-transfer:boundary-dispatched:"
                                    + current.id().value()), now());
            settlements.save(transfer, expected);
        }

        SettlementTransferStepExecutor.Result result = steps.execute(transfer);
        long expected = transfer.version();
        if (result instanceof SettlementTransferStepExecutor.Pending) {
            return DeliveryOutcome.retryableFailure(
                    "settlement-transfer-child-pending");
        }
        if (result instanceof SettlementTransferStepExecutor.Dispatched dispatched) {
            SettlementTransfer changed = transfer.attachCurrentChild(
                    expected, dispatched.child(), ids.nextTransitionId(),
                    dispatched.evidence(), now());
            settlements.save(changed, expected);
            return DeliveryOutcome.retryableFailure(
                    "settlement-transfer-child-dispatched");
        }
        if (result instanceof SettlementTransferStepExecutor.Unknown unknown) {
            if (transfer.currentBoundary().status()
                    == SettlementTransfer.BoundaryStatus.UNKNOWN) {
                if (!transfer.currentBoundary().child()
                        .filter(unknown.child()::equals).isPresent()) {
                    SettlementTransfer unsafe = transfer.requireManualReview(
                            expected, ids.nextTransitionId(),
                            unknown.evidence(), now());
                    settlements.save(unsafe, expected);
                    return DeliveryOutcome.terminalFailure(
                            "settlement-transfer-child-identity-conflict");
                }
                return DeliveryOutcome.ambiguousAcknowledgement(
                        "settlement-transfer-child-unknown");
            }
            SettlementTransfer changed = transfer.markCurrentUnknown(
                    expected, unknown.child(), ids.nextTransitionId(),
                    unknown.evidence(), now());
            settlements.save(changed, expected);
            return DeliveryOutcome.ambiguousAcknowledgement(
                    "settlement-transfer-child-unknown");
        }
        if (result instanceof SettlementTransferStepExecutor.RejectedNoEffect rejected) {
            SettlementTransfer failed = transfer.failCurrentNoEffect(
                    expected, ids.nextTransitionId(), rejected.evidence(), now());
            settlements.save(failed, expected);
            return DeliveryOutcome.terminalFailure(
                    "settlement-transfer-child-rejected");
        }
        if (result instanceof SettlementTransferStepExecutor.ManualReview manual) {
            SettlementTransfer unsafe = transfer.requireManualReview(
                    expected, ids.nextTransitionId(), manual.evidence(), now());
            settlements.save(unsafe, expected);
            return DeliveryOutcome.terminalFailure(
                    "settlement-transfer-manual-review");
        }
        if (result instanceof SettlementTransferStepExecutor.Reconciled reconciled) {
            SettlementTransfer concluded = transfer.recordReconciliation(
                    expected, reconciled.conclusion(), ids.nextTransitionId(),
                    reconciled.evidence(), now());
            settlements.save(concluded, expected);
            return concluded.status() == SettlementTransfer.Status.COMPLETED
                    ? DeliveryOutcome.delivered()
                    : DeliveryOutcome.terminalFailure(
                            "settlement-transfer-reconciliation-mismatch");
        }
        SettlementTransferStepExecutor.Confirmed confirmed =
                (SettlementTransferStepExecutor.Confirmed) result;
        SettlementTransfer changed = transfer.confirmCurrent(
                expected, confirmed.child(), ids.nextTransitionId(),
                confirmed.evidence(), now());
        settlements.save(changed, expected);
        return DeliveryOutcome.retryableFailure(
                "settlement-transfer-next-boundary-pending");
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock result")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
