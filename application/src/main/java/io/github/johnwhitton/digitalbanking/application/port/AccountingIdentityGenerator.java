package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;

/** Generates stable accounting identities outside the domain. */
public interface AccountingIdentityGenerator {

    ReserveAccounting.JournalId nextJournalId();

    ReserveAccounting.JournalLineId nextJournalLineId();

    ReserveAccounting.ReconciliationRunId nextReconciliationRunId();

    ReserveAccounting.ReconciliationResultId nextReconciliationResultId();
}
