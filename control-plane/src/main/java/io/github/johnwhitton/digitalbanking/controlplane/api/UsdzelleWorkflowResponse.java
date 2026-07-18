package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.time.Instant;
import java.util.List;

import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Participant-safe projection of parent progress without internal evidence or authority data. */
public record UsdzelleWorkflowResponse(
        String workflowId,
        String kind,
        String amount,
        String currency,
        String status,
        long version,
        Instant acceptedAt,
        Instant updatedAt,
        Progress progress,
        List<Step> steps) {

    public static UsdzelleWorkflowResponse from(UsdzelleWorkflow workflow) {
        return new UsdzelleWorkflowResponse(
                workflow.id().value().toString(), workflow.kind().name(),
                workflow.context().usdAmount().toCanonicalString(), "USD",
                workflow.status().name(), workflow.version(),
                workflow.context().acceptedAt(),
                workflow.transitions().getLast().recordedAt(),
                progress(workflow),
                workflow.steps().stream()
                        .map(step -> new Step(step.kind().name(), step.status().name()))
                        .toList());
    }

    private static Progress progress(UsdzelleWorkflow workflow) {
        boolean acquisition = workflow.kind() == UsdzelleWorkflow.Kind.ACQUISITION;
        boolean bank = completed(workflow,
                acquisition ? UsdzelleWorkflow.StepKind.WITHDRAWAL
                        : UsdzelleWorkflow.StepKind.PAYOUT);
        boolean chain = completed(workflow,
                acquisition ? UsdzelleWorkflow.StepKind.MINT
                        : UsdzelleWorkflow.StepKind.BURN);
        boolean accounting = completed(workflow,
                acquisition ? UsdzelleWorkflow.StepKind.MINT_ACCOUNTING_POST
                        : UsdzelleWorkflow.StepKind.PAYOUT_ACCOUNTING_POST);
        String reconciliation = workflow.reconciliationConclusion()
                .map(Enum::name)
                .orElseGet(() -> workflow.currentStep().kind()
                                == UsdzelleWorkflow.StepKind.RECONCILIATION
                        ? "PENDING" : "NOT_ASSESSED");
        return new Progress(
                bank ? "CONFIRMED" : "PENDING",
                chain ? "CONFIRMED" : "PENDING",
                accounting ? "POSTED" : "PENDING",
                reconciliation,
                workflow.status() == UsdzelleWorkflow.Status.MANUAL_REVIEW);
    }

    private static boolean completed(
            UsdzelleWorkflow workflow, UsdzelleWorkflow.StepKind kind) {
        return workflow.steps().stream()
                .filter(step -> step.kind() == kind)
                .anyMatch(step -> step.status() == UsdzelleWorkflow.StepStatus.COMPLETED);
    }

    public record Progress(
            String bankEffectStatus,
            String blockchainEffectStatus,
            String accountingPostingStatus,
            String reconciliationStatus,
            boolean manualReviewRequired) {
    }

    public record Step(String kind, String status) {
    }
}
