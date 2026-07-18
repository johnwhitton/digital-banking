package io.github.johnwhitton.digitalbanking.controlplane.config;

import io.github.johnwhitton.digitalbanking.application.AccountingEvidenceConflictException;
import io.github.johnwhitton.digitalbanking.application.port.ReserveAccountingPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.micrometer.core.instrument.MeterRegistry;

/** Low-cardinality metrics for the local synthetic financial boundary. */
public final class LocalFinancialMetrics {

    private final MeterRegistry registry;

    LocalFinancialMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void bank(BankOperation.Kind kind, String outcome) {
        registry.counter("digital_banking.local.bank.operations",
                "kind", kind.name(), "outcome", outcome).increment();
        if ("UNKNOWN".equals(outcome)) {
            registry.counter("digital_banking.local.bank.ambiguous").increment();
        }
    }

    void posting(ReserveAccounting.PostingType type) {
        registry.counter("digital_banking.local.accounting.postings",
                "type", type.name()).increment();
    }

    void evidenceConflict() {
        registry.counter("digital_banking.local.accounting.evidence_conflicts")
                .increment();
    }

    void reconciliation(ReserveAccounting.ReconciliationStatus status) {
        registry.counter("digital_banking.local.accounting.reconciliations",
                "status", status.name()).increment();
        if (status == ReserveAccounting.ReconciliationStatus.RESERVE_LEDGER_MISMATCH
                || status == ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH) {
            registry.counter("digital_banking.local.accounting.mismatches",
                    "kind", status.name()).increment();
        }
    }

    ReserveAccountingPort metered(ReserveAccountingPort delegate) {
        return new ReserveAccountingPort() {
            @Override
            public AccountingResult post(PostCommand command) {
                try {
                    AccountingResult result = delegate.post(command);
                    if (!result.replayed()) {
                        posting(result.postingType());
                    }
                    return result;
                } catch (AccountingEvidenceConflictException conflict) {
                    evidenceConflict();
                    throw conflict;
                }
            }

            @Override
            public AccountingResult reverse(ReverseCommand command) {
                try {
                    return delegate.reverse(command);
                } catch (AccountingEvidenceConflictException conflict) {
                    evidenceConflict();
                    throw conflict;
                }
            }

            @Override
            public ReserveAccounting.Snapshot snapshot() {
                return delegate.snapshot();
            }

            @Override
            public ReserveAccounting.Reconciliation reconcile(
                    ReconcileCommand command) {
                ReserveAccounting.Reconciliation result = delegate.reconcile(command);
                reconciliation(result.status());
                return result;
            }
        };
    }
}
