package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.CorrelationId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Direction;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.EvidenceIdentity;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Journal;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.JournalId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.JournalLineId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.LedgerAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Line;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Positions;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.PolicyVersion;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Posting;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.PostingType;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Reconciliation;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.ReconciliationResultId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.ReconciliationRunId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.ReconciliationStatus;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Snapshot;

/** Trusted pure posting and reserve/supply reconciliation rules. */
public final class AccountingPostingEngine {

    private AccountingPostingEngine() {
    }

    public static Posting post(
            PostingType type,
            Snapshot current,
            UsdCents amount,
            JournalId journalId,
            JournalLineId debitLineId,
            JournalLineId creditLineId,
            PolicyVersion policyVersion,
            CorrelationId correlationId,
            EvidenceIdentity evidenceIdentity,
            Instant effectiveAt,
            Instant recordedAt) {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(amount, "amount");
        if (amount.value().signum() == 0) {
            throw new IllegalArgumentException("posting amount must be positive");
        }
        return switch (type) {
            case RESERVE_FUNDING -> journal(
                    type, current, amount,
                    LedgerAccount.RESERVE_CASH_ASSET,
                    LedgerAccount.FIAT_RECEIVED_PENDING_MINT_LIABILITY,
                    journalId, debitLineId, creditLineId, policyVersion,
                    correlationId, evidenceIdentity, null, effectiveAt, recordedAt,
                    current.positions());
            case MINT_CONFIRMED -> journal(
                    type, current, amount,
                    LedgerAccount.FIAT_RECEIVED_PENDING_MINT_LIABILITY,
                    LedgerAccount.USDZELLE_CIRCULATING_LIABILITY,
                    journalId, debitLineId, creditLineId, policyVersion,
                    correlationId, evidenceIdentity, null, effectiveAt, recordedAt,
                    new Positions(
                            current.positions().adminRedemptionCustodyPendingBurn(),
                            current.positions().confirmedChainTotalSupply().add(amount),
                            current.positions().controlledInventory()));
            case REDEMPTION_CUSTODY_CONFIRMED -> journal(
                    type, current, amount,
                    LedgerAccount.USDZELLE_CIRCULATING_LIABILITY,
                    LedgerAccount.REDEMPTION_PAYABLE_LIABILITY,
                    journalId, debitLineId, creditLineId, policyVersion,
                    correlationId, evidenceIdentity, null, effectiveAt, recordedAt,
                    new Positions(
                            current.positions().adminRedemptionCustodyPendingBurn().add(amount),
                            current.positions().confirmedChainTotalSupply(),
                            current.positions().controlledInventory()));
            case BANK_PAYOUT_CONFIRMED -> journal(
                    type, current, amount,
                    LedgerAccount.REDEMPTION_PAYABLE_LIABILITY,
                    LedgerAccount.RESERVE_CASH_ASSET,
                    journalId, debitLineId, creditLineId, policyVersion,
                    correlationId, evidenceIdentity, null, effectiveAt, recordedAt,
                    current.positions());
            case BURN_CONFIRMED -> new Posting(null, new Snapshot(
                    current.balances(), new Positions(
                            current.positions().adminRedemptionCustodyPendingBurn()
                                    .subtract(amount),
                            current.positions().confirmedChainTotalSupply().subtract(amount),
                            current.positions().controlledInventory())));
            case REVERSAL -> throw new IllegalArgumentException(
                    "reversal requires the original journal");
        };
    }

    public static Posting reverse(
            ReserveAccounting.Journal original,
            Snapshot current,
            JournalId reversalId,
            JournalLineId debitLineId,
            JournalLineId creditLineId,
            EvidenceIdentity evidenceIdentity,
            Instant recordedAt) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(current, "current");
        List<Line> reversed = List.of(
                new Line(debitLineId, original.lines().getFirst().account(),
                        opposite(original.lines().getFirst().direction()),
                        original.lines().getFirst().amount()),
                new Line(creditLineId, original.lines().getLast().account(),
                        opposite(original.lines().getLast().direction()),
                        original.lines().getLast().amount()));
        Journal journal = new Journal(
                reversalId, PostingType.REVERSAL, original.policyVersion(),
                recordedAt, recordedAt, original.correlationId(), evidenceIdentity,
                original.id(), reversed);
        return new Posting(journal, apply(current, reversed, current.positions()));
    }

    public static Reconciliation reconcile(
            ReconciliationRunId runId,
            ReconciliationResultId resultId,
            Snapshot snapshot,
            boolean evidenceComplete,
            boolean observationSupportedAndFresh,
            Instant recordedAt) {
        Objects.requireNonNull(snapshot, "snapshot");
        ReconciliationStatus status;
        if (!evidenceComplete) {
            status = ReconciliationStatus.EVIDENCE_INCOMPLETE;
        } else if (!observationSupportedAndFresh) {
            status = ReconciliationStatus.UNSUPPORTED_OR_STALE_OBSERVATION;
        } else if (!reserveBalancesAgree(snapshot)) {
            status = ReconciliationStatus.RESERVE_LEDGER_MISMATCH;
        } else if (!chainSupplyAgrees(snapshot)) {
            status = ReconciliationStatus.CHAIN_SUPPLY_MISMATCH;
        } else {
            status = ReconciliationStatus.RECONCILED;
        }
        return new Reconciliation(
                runId, resultId, status, snapshot, evidenceComplete,
                observationSupportedAndFresh, recordedAt);
    }

    private static Posting journal(
            PostingType type,
            Snapshot current,
            UsdCents amount,
            LedgerAccount debit,
            LedgerAccount credit,
            JournalId journalId,
            JournalLineId debitLineId,
            JournalLineId creditLineId,
            PolicyVersion policyVersion,
            CorrelationId correlationId,
            EvidenceIdentity evidenceIdentity,
            JournalId reverses,
            Instant effectiveAt,
            Instant recordedAt,
            Positions positions) {
        List<Line> lines = List.of(
                new Line(debitLineId, debit, Direction.DEBIT, amount),
                new Line(creditLineId, credit, Direction.CREDIT, amount));
        Journal journal = new Journal(
                journalId, type, policyVersion, effectiveAt, recordedAt,
                correlationId, evidenceIdentity, reverses, lines);
        return new Posting(journal, apply(current, lines, positions));
    }

    private static Snapshot apply(
            Snapshot current, List<Line> lines, Positions positions) {
        EnumMap<LedgerAccount, UsdCents> balances =
                new EnumMap<>(LedgerAccount.class);
        balances.putAll(current.balances());
        for (Line line : lines) {
            UsdCents currentBalance = balances.get(line.account());
            boolean increases = (line.direction() == Direction.DEBIT)
                    == (line.account().normalBalance()
                            == ReserveAccounting.NormalBalance.DEBIT);
            balances.put(line.account(), increases
                    ? currentBalance.add(line.amount())
                    : currentBalance.subtract(line.amount()));
        }
        return new Snapshot(balances, positions);
    }

    private static Direction opposite(Direction value) {
        return value == Direction.DEBIT ? Direction.CREDIT : Direction.DEBIT;
    }

    private static boolean reserveBalancesAgree(Snapshot snapshot) {
        java.math.BigInteger liabilities = java.math.BigInteger.ZERO;
        liabilities = liabilities.add(snapshot.balances().get(
                LedgerAccount.FIAT_RECEIVED_PENDING_MINT_LIABILITY).value());
        liabilities = liabilities.add(snapshot.balances().get(
                LedgerAccount.USDZELLE_CIRCULATING_LIABILITY).value());
        liabilities = liabilities.add(snapshot.balances().get(
                LedgerAccount.REDEMPTION_PAYABLE_LIABILITY).value());
        return snapshot.balances().get(LedgerAccount.RESERVE_CASH_ASSET)
                .value().equals(liabilities);
    }

    private static boolean chainSupplyAgrees(Snapshot snapshot) {
        java.math.BigInteger expected = snapshot.balances().get(
                        LedgerAccount.USDZELLE_CIRCULATING_LIABILITY).value()
                .add(snapshot.positions().adminRedemptionCustodyPendingBurn().value())
                .add(snapshot.positions().controlledInventory().value());
        return snapshot.positions().confirmedChainTotalSupply().value().equals(expected);
    }
}
