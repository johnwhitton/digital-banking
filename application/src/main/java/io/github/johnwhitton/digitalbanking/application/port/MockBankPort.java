package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;

/** Provider-neutral contract for synthetic withdrawal and deposit effects. */
@FunctionalInterface
public interface MockBankPort {

    Outcome request(Command command);

    record Command(
            TransferId transferId,
            TransferEffect.Id effectId,
            TransferParticipant participant,
            BankAccountReference accountReference,
            TokenQuantity quantity,
            Operation operation,
            String idempotencyIdentity,
            AttemptId attemptId,
            String policyVersion,
            Instant requestedAt,
            Instant deadline) {

        private static final Pattern IDENTITY =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public Command {
            Objects.requireNonNull(transferId, "transferId");
            Objects.requireNonNull(effectId, "effectId");
            Objects.requireNonNull(participant, "participant");
            Objects.requireNonNull(accountReference, "accountReference");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(operation, "operation");
            requireIdentity(idempotencyIdentity, "idempotencyIdentity");
            Objects.requireNonNull(attemptId, "attemptId");
            requireIdentity(policyVersion, "policyVersion");
            Objects.requireNonNull(requestedAt, "requestedAt");
            Objects.requireNonNull(deadline, "deadline");
            if (!deadline.isAfter(requestedAt)) {
                throw new IllegalArgumentException("deadline must follow requestedAt");
            }
        }

        private static void requireIdentity(String value, String field) {
            if (value == null || !IDENTITY.matcher(value).matches()) {
                throw new IllegalArgumentException(field + " must be a safe identifier");
            }
        }
    }

    record Outcome(
            Classification classification,
            String evidenceReference,
            String safeFailureCode) {

        private static final Pattern SAFE_FAILURE =
                Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

        public Outcome {
            Objects.requireNonNull(classification, "classification");
            boolean applied = classification == Classification.APPLIED
                    || classification == Classification.ALREADY_APPLIED;
            boolean evidenceRequired = applied || classification == Classification.AMBIGUOUS;
            if (evidenceRequired != (evidenceReference != null)) {
                throw new IllegalArgumentException(
                        "applied and ambiguous outcomes require evidence");
            }
            if (evidenceRequired) {
                requireEvidence(evidenceReference);
            }
            if (!applied && (safeFailureCode == null
                    || !SAFE_FAILURE.matcher(safeFailureCode).matches())) {
                throw new IllegalArgumentException(
                        "failure outcome requires a safe failure code");
            }
            if (applied && safeFailureCode != null) {
                throw new IllegalArgumentException("applied outcome has no failure code");
            }
        }

        public static Outcome applied(String evidenceReference) {
            return new Outcome(Classification.APPLIED,
                    requireEvidence(evidenceReference), null);
        }

        public static Outcome alreadyApplied(String evidenceReference) {
            return new Outcome(Classification.ALREADY_APPLIED,
                    requireEvidence(evidenceReference), null);
        }

        public static Outcome rejectedNoEffect(String safeFailureCode) {
            return new Outcome(
                    Classification.REJECTED_NO_EFFECT, null, safeFailureCode);
        }

        public static Outcome retryableNoEffect(String safeFailureCode) {
            return new Outcome(
                    Classification.RETRYABLE_NO_EFFECT, null, safeFailureCode);
        }

        public static Outcome ambiguous(
                String evidenceReference, String safeFailureCode) {
            return new Outcome(
                    Classification.AMBIGUOUS,
                    requireEvidence(evidenceReference), safeFailureCode);
        }

        private static String requireEvidence(String value) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(
                        "evidence reference must contain 1-256 characters");
            }
            return value;
        }
    }

    enum Operation {
        WITHDRAWAL,
        DEPOSIT
    }

    enum Classification {
        APPLIED,
        ALREADY_APPLIED,
        REJECTED_NO_EFFECT,
        RETRYABLE_NO_EFFECT,
        AMBIGUOUS
    }
}
