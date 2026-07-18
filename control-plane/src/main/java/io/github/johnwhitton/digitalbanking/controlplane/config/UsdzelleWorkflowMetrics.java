package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;

import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import io.micrometer.core.instrument.MeterRegistry;

/** Bounded operational metrics for the local user-held workflows. */
public final class UsdzelleWorkflowMetrics {

    private final MeterRegistry registry;

    public UsdzelleWorkflowMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void accepted(UsdzelleWorkflow.Kind kind, boolean replayed) {
        if (!replayed) {
            workflow(kind, "ACCEPTED");
        }
    }

    UsdzelleWorkflowStepExecutor metered(
            UsdzelleWorkflowStepExecutor delegate, ClockPort clock) {
        return workflow -> {
            UsdzelleWorkflowStepExecutor.Result result = delegate.execute(workflow);
            record(workflow.kind(), workflow.currentStep().kind(), result, Duration.between(
                    workflow.context().acceptedAt(), clock.now()));
            return result;
        };
    }

    void record(
            UsdzelleWorkflow.Kind kind,
            UsdzelleWorkflow.StepKind step,
            UsdzelleWorkflowStepExecutor.Result result,
            Duration age) {
        registry.counter("digital.banking.usdzelle.steps",
                "kind", kind.name(),
                "step", step.name(),
                "outcome", result.getClass().getSimpleName().toUpperCase(
                        java.util.Locale.ROOT)).increment();
        registry.counter("digital.banking.usdzelle.age",
                "kind", kind.name(),
                "bucket", ageBucket(age)).increment();
        if (result instanceof UsdzelleWorkflowStepExecutor.ManualReview) {
            workflow(kind, "MANUAL_REVIEW");
        } else if (result instanceof UsdzelleWorkflowStepExecutor.Reconciled reconciled) {
            registry.counter("digital.banking.usdzelle.reconciliations",
                    "kind", kind.name(),
                    "conclusion", reconciled.conclusion().name()).increment();
            workflow(kind,
                    reconciled.conclusion() == ReserveAccounting.ReconciliationStatus.RECONCILED
                            ? "COMPLETED" : "MANUAL_REVIEW");
        }
    }

    private void workflow(UsdzelleWorkflow.Kind kind, String outcome) {
        registry.counter("digital.banking.usdzelle.workflows",
                "kind", kind.name(), "outcome", outcome).increment();
    }

    private static String ageBucket(Duration age) {
        long seconds = Math.max(0, age.toSeconds());
        if (seconds < 60) {
            return "LT_1M";
        }
        if (seconds < 300) {
            return "LT_5M";
        }
        if (seconds < 3600) {
            return "LT_1H";
        }
        return "GTE_1H";
    }
}
