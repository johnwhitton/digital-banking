package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
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

    /** Executes one durable local synthetic-bank operation. */
    default BankResponse execute(BankCommand command) {
        throw new UnsupportedOperationException("executable mock bank is unavailable");
    }

    /** Inquires durable bank truth by stable identity; never repeats an effect. */
    default Optional<BankOperation> inquire(
            BankOperation.Id operationId, ParticipantScope participant) {
        return Optional.empty();
    }

    default Optional<SyntheticBankAccount> findAccount(
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            ParticipantScope participant) {
        return Optional.empty();
    }

    /** Idempotently installs server-owned local fixtures without resetting balances. */
    default void initialize(Fixture fixture) {
        throw new UnsupportedOperationException("mock-bank fixture initialization unavailable");
    }

    record BankCommand(
            BankOperation.Id operationId,
            BankOperation.EvidenceId evidenceId,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            ParticipantScope participant,
            UsdCents amount,
            BankOperation.Kind kind,
            String idempotencyKeyDigest,
            String commandDigest,
            BankOperation.CorrelationId correlationId,
            BankOperation.PolicyVersion policyVersion,
            Instant requestedAt) {

        private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");

        public BankCommand {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(bankId, "bankId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(participant, "participant");
            Objects.requireNonNull(amount, "amount");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(requestedAt, "requestedAt");
            if (amount.value().signum() == 0
                    || idempotencyKeyDigest == null
                    || !DIGEST.matcher(idempotencyKeyDigest).matches()
                    || commandDigest == null
                    || !DIGEST.matcher(commandDigest).matches()) {
                throw new IllegalArgumentException("bank command exactness or digest is invalid");
            }
        }
    }

    record BankResponse(
            BankOperation.Id operationId,
            ResponseStatus status,
            BankOperation.EvidenceId evidenceId,
            UsdCents balanceAfter,
            boolean replayed,
            String safeFailureCode) {
        public BankResponse {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(status, "status");
            if (status == ResponseStatus.SUCCEEDED) {
                Objects.requireNonNull(evidenceId, "evidenceId");
                Objects.requireNonNull(balanceAfter, "balanceAfter");
                if (safeFailureCode != null) {
                    throw new IllegalArgumentException("successful response has no failure");
                }
            } else if (status == ResponseStatus.REJECTED) {
                Objects.requireNonNull(evidenceId, "evidenceId");
                if (balanceAfter != null || safeFailureCode == null) {
                    throw new IllegalArgumentException("rejected response shape is invalid");
                }
            } else if (evidenceId != null || balanceAfter != null
                    || safeFailureCode == null || replayed) {
                throw new IllegalArgumentException("unknown response shape is invalid");
            }
        }
    }

    record Fixture(
            SyntheticBankAccount.FixtureVersion version,
            List<BankFixture> banks,
            List<AccountFixture> accounts,
            Instant initializedAt) {
        public Fixture {
            Objects.requireNonNull(version, "version");
            banks = List.copyOf(banks);
            accounts = List.copyOf(accounts);
            Objects.requireNonNull(initializedAt, "initializedAt");
            if (banks.isEmpty()) {
                throw new IllegalArgumentException("at least one synthetic bank is required");
            }
        }
    }

    record BankFixture(SyntheticBankAccount.BankId bankId, boolean enabled) {
        public BankFixture {
            Objects.requireNonNull(bankId, "bankId");
        }
    }

    record AccountFixture(
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId accountId,
            ParticipantScope owner,
            UsdCents initialBalance,
            boolean enabled) {
        public AccountFixture {
            Objects.requireNonNull(bankId, "bankId");
            Objects.requireNonNull(accountId, "accountId");
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(initialBalance, "initialBalance");
        }
    }

    enum ResponseStatus {
        SUCCEEDED,
        REJECTED,
        UNKNOWN
    }

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
