package io.github.johnwhitton.digitalbanking.controlplane.config;

import java.time.Duration;

import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowStepExecutor;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferStepExecutor;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UsdzelleWorkflowMetricsTest {

    @Test
    void publishesOnlyBoundedWorkflowStepConclusionAndAgeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        UsdzelleWorkflowMetrics metrics = new UsdzelleWorkflowMetrics(registry);

        metrics.accepted(UsdzelleWorkflow.Kind.REDEMPTION, false);
        metrics.accepted(UsdzelleWorkflow.Kind.REDEMPTION, true);
        metrics.record(UsdzelleWorkflow.Kind.REDEMPTION,
                UsdzelleWorkflow.StepKind.RECONCILIATION,
                new UsdzelleWorkflowStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.RECONCILED,
                        new EvidenceRef("internal:reconciliation")),
                Duration.ofMinutes(7));

        assertEquals(1.0, registry.get("digital.banking.usdzelle.workflows")
                .tags("kind", "REDEMPTION", "outcome", "ACCEPTED")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.usdzelle.workflows")
                .tags("kind", "REDEMPTION", "outcome", "COMPLETED")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.usdzelle.steps")
                .tags("kind", "REDEMPTION", "step", "RECONCILIATION",
                        "outcome", "RECONCILED")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.usdzelle.reconciliations")
                .tags("kind", "REDEMPTION", "conclusion", "RECONCILED")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.usdzelle.age")
                .tags("kind", "REDEMPTION", "bucket", "LT_1H")
                .counter().count());

        metrics.recordSettlement(
                SettlementTransfer.BoundaryKind.FINAL_RECONCILIATION,
                SettlementTransfer.Status.FINAL_RECONCILIATION_PENDING,
                new SettlementTransferStepExecutor.Reconciled(
                        ReserveAccounting.ReconciliationStatus.RECONCILED,
                        new EvidenceRef("internal:settlement-reconciliation")),
                Duration.ofMinutes(2));
        assertEquals(1.0, registry.get("digital.banking.settlement.transfers")
                .tag("outcome", "COMPLETED").counter().count());
        assertEquals(1.0, registry.get("digital.banking.settlement.boundaries")
                .tags("boundary", "FINAL_RECONCILIATION", "outcome", "RECONCILED")
                .counter().count());
        assertEquals(1.0, registry.get("digital.banking.settlement.reconciliations")
                .tag("conclusion", "RECONCILED").counter().count());
    }
}
