package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Executes or inquires the one parent step already made durable and eligible. */
@FunctionalInterface
public interface UsdzelleWorkflowStepExecutor {

    Result execute(UsdzelleWorkflow workflow);

    sealed interface Result permits Confirmed, Dispatched, Pending, Unknown,
            RejectedNoEffect, ManualReview, Reconciled {
        EvidenceRef evidence();
    }

    record Dispatched(
            UsdzelleWorkflow.ChildReference child,
            EvidenceRef evidence) implements Result {
        public Dispatched {
            Objects.requireNonNull(child, "child");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Confirmed(
            Optional<UsdzelleWorkflow.ChildReference> child,
            EvidenceRef evidence) implements Result {
        public Confirmed {
            child = Objects.requireNonNull(child, "child");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Pending(EvidenceRef evidence) implements Result {
        public Pending {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Unknown(
            UsdzelleWorkflow.ChildReference child,
            EvidenceRef evidence) implements Result {
        public Unknown {
            Objects.requireNonNull(child, "child");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record RejectedNoEffect(EvidenceRef evidence) implements Result {
        public RejectedNoEffect {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record ManualReview(EvidenceRef evidence) implements Result {
        public ManualReview {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Reconciled(
            ReserveAccounting.ReconciliationStatus conclusion,
            EvidenceRef evidence) implements Result {
        public Reconciled {
            Objects.requireNonNull(conclusion, "conclusion");
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
