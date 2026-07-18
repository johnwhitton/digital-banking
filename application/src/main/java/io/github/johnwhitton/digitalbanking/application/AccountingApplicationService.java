package io.github.johnwhitton.digitalbanking.application;

import java.time.temporal.ChronoUnit;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.AccountingCommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.port.AccountingIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.ReserveAccountingPort;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;

/** Trusted use cases for posting verified evidence and recording reconciliation. */
public final class AccountingApplicationService {

    private final ReserveAccountingPort accounting;
    private final ClockPort clock;
    private final AccountingIdentityGenerator ids;
    private final ReserveAccountingPort.EvidencePolicy evidencePolicy;

    public AccountingApplicationService(
            ReserveAccountingPort accounting,
            ClockPort clock,
            AccountingIdentityGenerator ids,
            ReserveAccountingPort.EvidencePolicy evidencePolicy) {
        this.accounting = Objects.requireNonNull(accounting, "accounting");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.evidencePolicy = Objects.requireNonNull(evidencePolicy, "evidencePolicy");
    }

    public ReserveAccountingPort.AccountingResult post(
            ReserveAccounting.EvidenceIdentity evidenceIdentity,
            ReserveAccounting.PostingType postingType) {
        String digest = AccountingCommandCanonicalizer.digest(
                evidenceIdentity, postingType, evidencePolicy);
        return accounting.post(new ReserveAccountingPort.PostCommand(
                evidenceIdentity, postingType, ids.nextJournalId(),
                ids.nextJournalLineId(), ids.nextJournalLineId(), evidencePolicy,
                digest, clock.now().truncatedTo(ChronoUnit.MICROS)));
    }

    public ReserveAccounting.Snapshot snapshot() {
        return accounting.snapshot();
    }

    public ReserveAccountingPort.AccountingResult reverse(
            ReserveAccounting.JournalId originalJournalId,
            ReserveAccounting.EvidenceIdentity correctionEvidenceIdentity) {
        String digest = AccountingCommandCanonicalizer.reversalDigest(
                originalJournalId, correctionEvidenceIdentity,
                evidencePolicy.accountingPolicyVersion());
        return accounting.reverse(new ReserveAccountingPort.ReverseCommand(
                originalJournalId, correctionEvidenceIdentity, ids.nextJournalId(),
                ids.nextJournalLineId(), ids.nextJournalLineId(),
                evidencePolicy.accountingPolicyVersion(), digest,
                clock.now().truncatedTo(ChronoUnit.MICROS)));
    }

    public ReserveAccounting.Reconciliation reconcile() {
        return accounting.reconcile(new ReserveAccountingPort.ReconcileCommand(
                ids.nextReconciliationRunId(), ids.nextReconciliationResultId(),
                evidencePolicy,
                clock.now().truncatedTo(ChronoUnit.MICROS)));
    }
}
