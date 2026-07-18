package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;

/** Atomic durable boundary for verified evidence posting and reconciliation. */
public interface ReserveAccountingPort {

    AccountingResult post(PostCommand command);

    AccountingResult reverse(ReverseCommand command);

    ReserveAccounting.Snapshot snapshot();

    ReserveAccounting.Reconciliation reconcile(ReconcileCommand command);

    record EvidencePolicy(
            ReserveAccounting.PolicyVersion accountingPolicyVersion,
            String bankEvidencePolicyVersion,
            String mintEvidencePolicyVersion,
            String custodyEvidencePolicyVersion,
            String burnEvidencePolicyVersion,
            String chainAssetId,
            String settlementNetwork,
            String contractReference,
            Duration maximumObservationAge) {

        private static final Pattern ID =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public EvidencePolicy {
            Objects.requireNonNull(accountingPolicyVersion, "accountingPolicyVersion");
            requireId(bankEvidencePolicyVersion, "bankEvidencePolicyVersion");
            requireId(mintEvidencePolicyVersion, "mintEvidencePolicyVersion");
            requireId(custodyEvidencePolicyVersion, "custodyEvidencePolicyVersion");
            requireId(burnEvidencePolicyVersion, "burnEvidencePolicyVersion");
            requireId(chainAssetId, "chainAssetId");
            requireId(settlementNetwork, "settlementNetwork");
            requireId(contractReference, "contractReference");
            Objects.requireNonNull(maximumObservationAge, "maximumObservationAge");
            if (maximumObservationAge.isNegative() || maximumObservationAge.isZero()) {
                throw new IllegalArgumentException("maximum observation age must be positive");
            }
        }

        private static void requireId(String value, String name) {
            if (value == null || !ID.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }

    record PostCommand(
            ReserveAccounting.EvidenceIdentity evidenceIdentity,
            ReserveAccounting.PostingType postingType,
            ReserveAccounting.JournalId journalId,
            ReserveAccounting.JournalLineId debitLineId,
            ReserveAccounting.JournalLineId creditLineId,
            EvidencePolicy evidencePolicy,
            String commandDigest,
            Instant recordedAt) {

        private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

        public PostCommand {
            Objects.requireNonNull(evidenceIdentity, "evidenceIdentity");
            Objects.requireNonNull(postingType, "postingType");
            Objects.requireNonNull(journalId, "journalId");
            Objects.requireNonNull(debitLineId, "debitLineId");
            Objects.requireNonNull(creditLineId, "creditLineId");
            Objects.requireNonNull(evidencePolicy, "evidencePolicy");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (postingType == ReserveAccounting.PostingType.REVERSAL
                    || commandDigest == null || !DIGEST.matcher(commandDigest).matches()) {
                throw new IllegalArgumentException("unsupported posting or digest");
            }
        }
    }

    record AccountingResult(
            ReserveAccounting.EvidenceIdentity evidenceIdentity,
            ReserveAccounting.PostingType postingType,
            ReserveAccounting.JournalId journalId,
            boolean replayed) {
        public AccountingResult {
            Objects.requireNonNull(evidenceIdentity, "evidenceIdentity");
            Objects.requireNonNull(postingType, "postingType");
            if ((postingType == ReserveAccounting.PostingType.BURN_CONFIRMED)
                    != (journalId == null)) {
                throw new IllegalArgumentException("accounting result journal shape is invalid");
            }
        }
    }

    record ReverseCommand(
            ReserveAccounting.JournalId originalJournalId,
            ReserveAccounting.EvidenceIdentity correctionEvidenceIdentity,
            ReserveAccounting.JournalId reversalJournalId,
            ReserveAccounting.JournalLineId debitLineId,
            ReserveAccounting.JournalLineId creditLineId,
            ReserveAccounting.PolicyVersion accountingPolicyVersion,
            String commandDigest,
            Instant recordedAt) {
        private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

        public ReverseCommand {
            Objects.requireNonNull(originalJournalId, "originalJournalId");
            Objects.requireNonNull(correctionEvidenceIdentity,
                    "correctionEvidenceIdentity");
            Objects.requireNonNull(reversalJournalId, "reversalJournalId");
            Objects.requireNonNull(debitLineId, "debitLineId");
            Objects.requireNonNull(creditLineId, "creditLineId");
            Objects.requireNonNull(accountingPolicyVersion, "accountingPolicyVersion");
            Objects.requireNonNull(recordedAt, "recordedAt");
            if (commandDigest == null || !DIGEST.matcher(commandDigest).matches()) {
                throw new IllegalArgumentException("reversal digest is invalid");
            }
        }
    }

    record ReconcileCommand(
            ReserveAccounting.ReconciliationRunId runId,
            ReserveAccounting.ReconciliationResultId resultId,
            EvidencePolicy evidencePolicy,
            Instant recordedAt) {
        public ReconcileCommand {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(resultId, "resultId");
            Objects.requireNonNull(evidencePolicy, "evidencePolicy");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }
}
