package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

@FunctionalInterface
public interface SettlementTransferStepExecutor {

    Result execute(SettlementTransfer transfer);

    sealed interface Result permits Pending, Dispatched, Unknown, Confirmed,
            RejectedNoEffect, ManualReview, Reconciled { }

    record Pending(EvidenceRef evidence) implements Result {
        public Pending {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Dispatched(
            SettlementTransfer.ChildReference child,
            EvidenceRef evidence) implements Result {
        public Dispatched {
            Objects.requireNonNull(child, "child");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Unknown(
            SettlementTransfer.ChildReference child,
            EvidenceRef evidence) implements Result {
        public Unknown {
            Objects.requireNonNull(child, "child");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Confirmed(
            Optional<SettlementTransfer.ChildReference> child,
            EvidenceRef evidence) implements Result {
        public Confirmed {
            child = Objects.requireNonNull(child, "child");
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
