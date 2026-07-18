package io.github.johnwhitton.digitalbanking.application.delivery;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Advances one durable USDZELLE workflow boundary per leased delivery. */
public final class UsdzelleWorkflowAcceptedDeliveryHandler
        implements OperationDeliveryHandler {

    public static final String EVENT_TYPE = "UsdzelleWorkflowAccepted";

    private final UsdzelleWorkflowRepository workflows;
    private final UsdzelleWorkflowStepExecutor steps;
    private final ClockPort clock;
    private final UsdzelleWorkflowIdentityGenerator ids;

    public UsdzelleWorkflowAcceptedDeliveryHandler(
            UsdzelleWorkflowRepository workflows,
            UsdzelleWorkflowStepExecutor steps,
            ClockPort clock,
            UsdzelleWorkflowIdentityGenerator ids) {
        this.workflows = Objects.requireNonNull(workflows, "workflows");
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
            return DeliveryOutcome.terminalFailure("unsupported-usdzelle-workflow-delivery");
        }
        UsdzelleWorkflow workflow = workflows.findById(
                        new UsdzelleWorkflow.Id(delivery.aggregateId()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "USDZELLE workflow was not found"));
        if (workflow.status() == UsdzelleWorkflow.Status.COMPLETED) {
            return DeliveryOutcome.duplicate();
        }
        if (workflow.status() == UsdzelleWorkflow.Status.MANUAL_REVIEW
                || workflow.status() == UsdzelleWorkflow.Status.FAILED_NO_EFFECT) {
            return DeliveryOutcome.terminalFailure("usdzelle-workflow-not-executable");
        }

        UsdzelleWorkflow.Step current = workflow.currentStep();
        if (current.status() == UsdzelleWorkflow.StepStatus.ELIGIBLE) {
            long expected = workflow.version();
            workflow = workflow.beginCurrent(
                    expected, ids.nextTransitionId(),
                    new io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef(
                            "internal:usdzelle-workflow:step-dispatched:"
                                    + current.id().value()),
                    now());
            workflows.save(workflow, expected);
        }

        UsdzelleWorkflowStepExecutor.Result result = steps.execute(workflow);
        long expected = workflow.version();
        if (result instanceof UsdzelleWorkflowStepExecutor.Pending) {
            return DeliveryOutcome.retryableFailure("usdzelle-workflow-step-pending");
        }
        if (result instanceof UsdzelleWorkflowStepExecutor.Dispatched dispatched) {
            UsdzelleWorkflow changed = workflow.attachCurrentChild(
                    expected, dispatched.child(), ids.nextTransitionId(),
                    dispatched.evidence(), now());
            workflows.save(changed, expected);
            return DeliveryOutcome.retryableFailure(
                    "usdzelle-workflow-child-dispatched");
        }
        if (result instanceof UsdzelleWorkflowStepExecutor.Unknown unknown) {
            if (workflow.currentStep().status() == UsdzelleWorkflow.StepStatus.UNKNOWN) {
                if (!workflow.currentStep().childReference()
                        .filter(unknown.child()::equals).isPresent()) {
                    UsdzelleWorkflow unsafe = workflow.requireManualReview(
                            expected, ids.nextTransitionId(), unknown.evidence(), now());
                    workflows.save(unsafe, expected);
                    return DeliveryOutcome.terminalFailure(
                            "usdzelle-workflow-child-identity-conflict");
                }
                return DeliveryOutcome.ambiguousAcknowledgement(
                        "usdzelle-workflow-effect-unknown");
            }
            UsdzelleWorkflow unknownWorkflow = workflow.markCurrentUnknown(
                    expected, unknown.child(), ids.nextTransitionId(),
                    unknown.evidence(), now());
            workflows.save(unknownWorkflow, expected);
            return DeliveryOutcome.ambiguousAcknowledgement(
                    "usdzelle-workflow-effect-unknown");
        }
        if (result instanceof UsdzelleWorkflowStepExecutor.RejectedNoEffect rejected) {
            UsdzelleWorkflow failed = workflow.failCurrentNoEffect(
                    expected, ids.nextTransitionId(), rejected.evidence(), now());
            workflows.save(failed, expected);
            return DeliveryOutcome.terminalFailure("usdzelle-workflow-effect-rejected");
        }
        if (result instanceof UsdzelleWorkflowStepExecutor.ManualReview manual) {
            UsdzelleWorkflow unsafe = workflow.requireManualReview(
                    expected, ids.nextTransitionId(), manual.evidence(), now());
            workflows.save(unsafe, expected);
            return DeliveryOutcome.terminalFailure("usdzelle-workflow-manual-review");
        }
        if (result instanceof UsdzelleWorkflowStepExecutor.Reconciled reconciled) {
            UsdzelleWorkflow concluded = workflow.recordReconciliation(
                    expected, reconciled.conclusion(), ids.nextTransitionId(),
                    reconciled.evidence(), now());
            workflows.save(concluded, expected);
            return concluded.status() == UsdzelleWorkflow.Status.COMPLETED
                    ? DeliveryOutcome.delivered()
                    : DeliveryOutcome.terminalFailure("usdzelle-workflow-reconciliation-mismatch");
        }
        UsdzelleWorkflowStepExecutor.Confirmed confirmed =
                (UsdzelleWorkflowStepExecutor.Confirmed) result;
        UsdzelleWorkflow changed = workflow.confirmCurrent(
                expected, confirmed.child(), ids.nextTransitionId(),
                confirmed.evidence(), now());
        workflows.save(changed, expected);
        return DeliveryOutcome.retryableFailure("usdzelle-workflow-next-step-pending");
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock result")
                .truncatedTo(ChronoUnit.MICROS);
    }
}
