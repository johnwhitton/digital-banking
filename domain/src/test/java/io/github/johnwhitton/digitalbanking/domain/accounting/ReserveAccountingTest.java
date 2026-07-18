package io.github.johnwhitton.digitalbanking.domain.accounting;

import java.math.BigInteger;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.CorrelationId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Direction;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.EvidenceIdentity;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.JournalId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.JournalLineId;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.LedgerAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.PolicyVersion;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Posting;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.PostingType;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.ReconciliationStatus;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting.Snapshot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ReserveAccountingTest {

    private static final Instant TIME = Instant.parse("2026-07-18T12:00:00Z");
    private static final UsdCents AMOUNT = new UsdCents(new BigInteger("10000"));

    @Test
    void trustedPostingRulesPreserveLedgerAndCustodySupplyInvariants() {
        Posting funded = post(PostingType.RESERVE_FUNDING, Snapshot.zero(), 1);
        assertJournal(funded, LedgerAccount.RESERVE_CASH_ASSET,
                LedgerAccount.FIAT_RECEIVED_PENDING_MINT_LIABILITY);

        Posting minted = post(PostingType.MINT_CONFIRMED, funded.snapshot(), 2);
        assertJournal(minted, LedgerAccount.FIAT_RECEIVED_PENDING_MINT_LIABILITY,
                LedgerAccount.USDZELLE_CIRCULATING_LIABILITY);
        assertEquals(AMOUNT, minted.snapshot().positions().confirmedChainTotalSupply());

        Posting custody = post(
                PostingType.REDEMPTION_CUSTODY_CONFIRMED, minted.snapshot(), 3);
        assertJournal(custody, LedgerAccount.USDZELLE_CIRCULATING_LIABILITY,
                LedgerAccount.REDEMPTION_PAYABLE_LIABILITY);
        assertEquals(AMOUNT,
                custody.snapshot().positions().adminRedemptionCustodyPendingBurn());

        Posting burnedFirst = post(PostingType.BURN_CONFIRMED, custody.snapshot(), 4);
        assertNull(burnedFirst.journal());
        assertEquals(UsdCents.ZERO,
                burnedFirst.snapshot().positions().adminRedemptionCustodyPendingBurn());
        assertEquals(UsdCents.ZERO,
                burnedFirst.snapshot().positions().confirmedChainTotalSupply());

        Posting paid = post(PostingType.BANK_PAYOUT_CONFIRMED, burnedFirst.snapshot(), 5);
        assertJournal(paid, LedgerAccount.REDEMPTION_PAYABLE_LIABILITY,
                LedgerAccount.RESERVE_CASH_ASSET);
        assertEquals(ReconciliationStatus.RECONCILED,
                reconcile(paid.snapshot(), true, true, 10).status());

        Posting paidFirst = post(PostingType.BANK_PAYOUT_CONFIRMED, custody.snapshot(), 6);
        Posting burned = post(PostingType.BURN_CONFIRMED, paidFirst.snapshot(), 7);
        assertEquals(ReconciliationStatus.RECONCILED,
                reconcile(burned.snapshot(), true, true, 11).status());
    }

    @Test
    void rejectsInsufficientPositionsAndReportsEveryMismatchClass() {
        assertThrows(IllegalArgumentException.class,
                () -> post(PostingType.MINT_CONFIRMED, Snapshot.zero(), 20));
        assertThrows(IllegalArgumentException.class,
                () -> post(PostingType.BANK_PAYOUT_CONFIRMED, Snapshot.zero(), 21));
        assertThrows(IllegalArgumentException.class,
                () -> post(PostingType.BURN_CONFIRMED, Snapshot.zero(), 22));

        Snapshot reserveMismatch = snapshot(Map.of(
                LedgerAccount.RESERVE_CASH_ASSET, AMOUNT));
        assertEquals(ReconciliationStatus.RESERVE_LEDGER_MISMATCH,
                reconcile(reserveMismatch, true, true, 23).status());

        Snapshot chainMismatch = new Snapshot(Map.of(
                LedgerAccount.RESERVE_CASH_ASSET, AMOUNT,
                LedgerAccount.USDZELLE_CIRCULATING_LIABILITY, AMOUNT),
                ReserveAccounting.Positions.zero());
        assertEquals(ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                reconcile(chainMismatch, true, true, 24).status());
        assertEquals(ReconciliationStatus.EVIDENCE_INCOMPLETE,
                reconcile(Snapshot.zero(), false, true, 25).status());
        assertEquals(ReconciliationStatus.UNSUPPORTED_OR_STALE_OBSERVATION,
                reconcile(Snapshot.zero(), true, false, 26).status());
    }

    @Test
    void reversalIsBalancedLinkedAndLeavesOriginalImmutable() {
        Posting funded = post(PostingType.RESERVE_FUNDING, Snapshot.zero(), 30);
        ReserveAccounting.Journal original = funded.journal();
        Posting reversed = AccountingPostingEngine.reverse(
                original, funded.snapshot(), journal(31), line(32), line(33),
                evidence(31), TIME.plusSeconds(1));

        assertEquals(original.id(), reversed.journal().reverses());
        assertEquals(PostingType.REVERSAL, reversed.journal().postingType());
        assertEquals(Direction.CREDIT, reversed.journal().lines().getFirst().direction());
        assertEquals(Snapshot.zero(), reversed.snapshot());
        assertEquals(PostingType.RESERVE_FUNDING, original.postingType());
    }

    private static Posting post(PostingType type, Snapshot snapshot, long id) {
        return AccountingPostingEngine.post(
                type, snapshot, AMOUNT, journal(id), line(id * 10), line(id * 10 + 1),
                new PolicyVersion("accounting-v1"), new CorrelationId("correlation-" + id),
                evidence(id), TIME, TIME);
    }

    private static ReserveAccounting.Reconciliation reconcile(
            Snapshot snapshot, boolean complete, boolean fresh, long id) {
        return AccountingPostingEngine.reconcile(
                new ReserveAccounting.ReconciliationRunId(new UUID(0, id)),
                new ReserveAccounting.ReconciliationResultId(new UUID(1, id)),
                snapshot, complete, fresh, TIME);
    }

    private static Snapshot snapshot(Map<LedgerAccount, UsdCents> balances) {
        EnumMap<LedgerAccount, UsdCents> complete = new EnumMap<>(LedgerAccount.class);
        complete.putAll(balances);
        return new Snapshot(complete, ReserveAccounting.Positions.zero());
    }

    private static void assertJournal(
            Posting posting, LedgerAccount debit, LedgerAccount credit) {
        assertEquals(debit, posting.journal().lines().getFirst().account());
        assertEquals(Direction.DEBIT, posting.journal().lines().getFirst().direction());
        assertEquals(credit, posting.journal().lines().getLast().account());
        assertEquals(Direction.CREDIT, posting.journal().lines().getLast().direction());
        assertEquals(AMOUNT, posting.journal().lines().getFirst().amount());
        assertEquals(AMOUNT, posting.journal().lines().getLast().amount());
    }

    private static JournalId journal(long value) {
        return new JournalId(new UUID(0, value));
    }

    private static JournalLineId line(long value) {
        return new JournalLineId(new UUID(0, value));
    }

    private static EvidenceIdentity evidence(long value) {
        return new EvidenceIdentity("evidence-" + value);
    }
}
