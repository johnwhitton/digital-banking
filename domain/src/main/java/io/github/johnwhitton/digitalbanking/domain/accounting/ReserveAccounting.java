package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Closed chart, immutable journals, operational positions, and reconciliation vocabulary. */
public final class ReserveAccounting {

    private ReserveAccounting() {
    }

    public enum LedgerAccount {
        RESERVE_CASH_ASSET(NormalBalance.DEBIT),
        FIAT_RECEIVED_PENDING_MINT_LIABILITY(NormalBalance.CREDIT),
        USDZELLE_CIRCULATING_LIABILITY(NormalBalance.CREDIT),
        REDEMPTION_PAYABLE_LIABILITY(NormalBalance.CREDIT);

        private final NormalBalance normalBalance;

        LedgerAccount(NormalBalance normalBalance) {
            this.normalBalance = normalBalance;
        }

        public NormalBalance normalBalance() {
            return normalBalance;
        }
    }

    public enum NormalBalance { DEBIT, CREDIT }

    public enum Direction { DEBIT, CREDIT }

    public enum PostingType {
        RESERVE_FUNDING,
        MINT_CONFIRMED,
        REDEMPTION_CUSTODY_CONFIRMED,
        BANK_PAYOUT_CONFIRMED,
        BURN_CONFIRMED,
        REVERSAL
    }

    public enum ReconciliationStatus {
        RECONCILED,
        RESERVE_LEDGER_MISMATCH,
        CHAIN_SUPPLY_MISMATCH,
        EVIDENCE_INCOMPLETE,
        UNSUPPORTED_OR_STALE_OBSERVATION
    }

    public record JournalId(UUID value) {
        public JournalId {
            Objects.requireNonNull(value, "journal identity");
        }
    }

    public record JournalLineId(UUID value) {
        public JournalLineId {
            Objects.requireNonNull(value, "journal line identity");
        }
    }

    public record EvidenceIdentity(String value) {
        private static final Pattern VALUE =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}");

        public EvidenceIdentity {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("external evidence identity is invalid");
            }
        }
    }

    public record PolicyVersion(String value) {
        private static final Pattern VALUE =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public PolicyVersion {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("accounting policy version is invalid");
            }
        }
    }

    public record CorrelationId(String value) {
        private static final Pattern VALUE =
                Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

        public CorrelationId {
            if (value == null || !VALUE.matcher(value).matches()) {
                throw new IllegalArgumentException("accounting correlation identity is invalid");
            }
        }
    }

    public record Line(
            JournalLineId id,
            LedgerAccount account,
            Direction direction,
            UsdCents amount) {
        public Line {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(account, "account");
            Objects.requireNonNull(direction, "direction");
            Objects.requireNonNull(amount, "amount");
            if (amount.value().signum() == 0) {
                throw new IllegalArgumentException("journal line amount must be positive");
            }
        }
    }

    public record Journal(
            JournalId id,
            PostingType postingType,
            PolicyVersion policyVersion,
            Instant effectiveAt,
            Instant recordedAt,
            CorrelationId correlationId,
            EvidenceIdentity evidenceIdentity,
            JournalId reverses,
            List<Line> lines) {
        public Journal {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(postingType, "postingType");
            Objects.requireNonNull(policyVersion, "policyVersion");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            Objects.requireNonNull(recordedAt, "recordedAt");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(evidenceIdentity, "evidenceIdentity");
            lines = List.copyOf(lines);
            if (lines.size() != 2 || recordedAt.isBefore(effectiveAt)) {
                throw new IllegalArgumentException("journal shape or time is invalid");
            }
            if ((postingType == PostingType.REVERSAL) != (reverses != null)) {
                throw new IllegalArgumentException("only reversal journals link an original");
            }
            UsdCents debits = sum(lines, Direction.DEBIT);
            UsdCents credits = sum(lines, Direction.CREDIT);
            if (!debits.equals(credits)) {
                throw new IllegalArgumentException("journal must balance");
            }
        }

        private static UsdCents sum(List<Line> lines, Direction direction) {
            UsdCents total = UsdCents.ZERO;
            for (Line line : lines) {
                if (line.direction == direction) {
                    total = total.add(line.amount);
                }
            }
            return total;
        }
    }

    public record Positions(
            UsdCents adminRedemptionCustodyPendingBurn,
            UsdCents confirmedChainTotalSupply,
            UsdCents controlledInventory) {
        public Positions {
            Objects.requireNonNull(adminRedemptionCustodyPendingBurn,
                    "adminRedemptionCustodyPendingBurn");
            Objects.requireNonNull(confirmedChainTotalSupply,
                    "confirmedChainTotalSupply");
            Objects.requireNonNull(controlledInventory, "controlledInventory");
        }

        public static Positions zero() {
            return new Positions(UsdCents.ZERO, UsdCents.ZERO, UsdCents.ZERO);
        }
    }

    public record Snapshot(Map<LedgerAccount, UsdCents> balances, Positions positions) {
        public Snapshot {
            Objects.requireNonNull(balances, "balances");
            EnumMap<LedgerAccount, UsdCents> complete = new EnumMap<>(LedgerAccount.class);
            for (LedgerAccount account : LedgerAccount.values()) {
                complete.put(account, Objects.requireNonNullElse(
                        balances.get(account), UsdCents.ZERO));
            }
            balances = Map.copyOf(complete);
            Objects.requireNonNull(positions, "positions");
        }

        public static Snapshot zero() {
            return new Snapshot(Map.of(), Positions.zero());
        }
    }

    public record Posting(Journal journal, Snapshot snapshot) {
        public Posting {
            Objects.requireNonNull(snapshot, "snapshot");
        }
    }

    public record ReconciliationRunId(UUID value) {
        public ReconciliationRunId {
            Objects.requireNonNull(value, "reconciliation run identity");
        }
    }

    public record ReconciliationResultId(UUID value) {
        public ReconciliationResultId {
            Objects.requireNonNull(value, "reconciliation result identity");
        }
    }

    public record Reconciliation(
            ReconciliationRunId runId,
            ReconciliationResultId resultId,
            ReconciliationStatus status,
            Snapshot snapshot,
            boolean evidenceComplete,
            boolean observationSupportedAndFresh,
            Instant recordedAt) {
        public Reconciliation {
            Objects.requireNonNull(runId, "runId");
            Objects.requireNonNull(resultId, "resultId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(snapshot, "snapshot");
            Objects.requireNonNull(recordedAt, "recordedAt");
        }
    }
}
