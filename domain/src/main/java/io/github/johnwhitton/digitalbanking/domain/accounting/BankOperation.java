package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Immutable result and evidence for one synthetic bank balance operation. */
public record BankOperation(
        Id id,
        EvidenceId evidenceId,
        Kind kind,
        Status status,
        SyntheticBankAccount.BankId bankId,
        SyntheticBankAccount.AccountId accountId,
        SyntheticBankAccount.Owner owner,
        UsdCents amount,
        UsdCents balanceAfter,
        String idempotencyKeyDigest,
        String commandDigest,
        CorrelationId correlationId,
        PolicyVersion policyVersion,
        String safeFailureCode,
        Instant recordedAt) {

    private static final Pattern DIGEST = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_FAILURE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    public BankOperation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(evidenceId, "evidenceId");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(bankId, "bankId");
        Objects.requireNonNull(accountId, "accountId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(policyVersion, "policyVersion");
        Objects.requireNonNull(recordedAt, "recordedAt");
        requireDigest(idempotencyKeyDigest, "idempotency key digest");
        requireDigest(commandDigest, "command digest");
        if (amount.value().signum() == 0) {
            throw new IllegalArgumentException("bank operation amount must be positive");
        }
        if (status == Status.SUCCEEDED) {
            Objects.requireNonNull(balanceAfter, "balanceAfter");
            if (safeFailureCode != null) {
                throw new IllegalArgumentException("successful bank operation has no failure");
            }
        } else if (balanceAfter != null || safeFailureCode == null
                || !SAFE_FAILURE.matcher(safeFailureCode).matches()) {
            throw new IllegalArgumentException("rejected bank operation requires safe failure");
        }
    }

    public record Id(UUID value) {
        public Id {
            Objects.requireNonNull(value, "bank operation identity");
        }
    }

    public record EvidenceId(UUID value) {
        public EvidenceId {
            Objects.requireNonNull(value, "bank evidence identity");
        }
    }

    public record CorrelationId(String value) {
        private static final Pattern VALUE =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public CorrelationId {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("bank correlation identity is invalid");
            }
        }
    }

    public record PolicyVersion(String value) {
        private static final Pattern VALUE =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public PolicyVersion {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("bank policy version is invalid");
            }
        }
    }

    public enum Kind {
        WITHDRAWAL,
        DEPOSIT
    }

    public enum Status {
        SUCCEEDED,
        REJECTED
    }

    private static void requireDigest(String value, String field) {
        if (value == null || !DIGEST.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is invalid");
        }
    }
}
