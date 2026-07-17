package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-17T18:00:00Z");

    @Test
    void acceptedTransferHasExactOrderedCorrelatedEffectPlanAndSeparateFinalities() {
        Transfer transfer = accepted();

        assertEquals(TransferStatus.ACCEPTED, transfer.status());
        assertEquals("12.34", transfer.quantity().toCanonicalString());
        assertEquals(List.of(
                TransferEffect.Kind.BANK_WITHDRAWAL,
                TransferEffect.Kind.TOKEN_MINT,
                TransferEffect.Kind.TOKEN_TRANSFER,
                TransferEffect.Kind.TOKEN_BURN,
                TransferEffect.Kind.BANK_DEPOSIT),
                transfer.effects().stream().map(TransferEffect::kind).toList());
        assertEquals(List.of(1, 2, 3, 4, 5),
                transfer.effects().stream().map(TransferEffect::sequence).toList());
        assertEquals(transfer.effects().get(0).effectId(),
                transfer.effects().get(1).expectedPredecessor().orElseThrow());
        assertEquals(transfer.effects().get(3).effectId(),
                transfer.effects().get(4).expectedPredecessor().orElseThrow());
        assertEquals(FinalityType.values().length, transfer.finalityHistories().size());
        for (FinalityType type : FinalityType.values()) {
            assertEquals(FinalityStatus.NOT_ASSESSED,
                    transfer.finalityHistory(type).getFirst().status());
        }
        assertNotEquals(
                transfer.finalityHistory(FinalityType.BLOCKCHAIN),
                transfer.finalityHistory(FinalityType.ACCOUNTING));
    }

    @Test
    void effectOrderAndAmbiguousAttemptBlockUnsafeProgress() {
        Transfer initial = accepted();
        TransferEffect withdrawal = initial.effects().getFirst();
        TransferEffect mint = initial.effects().get(1);

        assertThrows(IllegalStateException.class, () -> initial.prepareEffect(
                0, mint.effectId(), transitionId(10), evidence("participant:unsafe"),
                ACCEPTED_AT.plusSeconds(1)));

        Transfer transfer = initial.prepareEffect(
                0, withdrawal.effectId(), transitionId(11), evidence("internal:prepared"),
                ACCEPTED_AT.plusSeconds(1));
        AttemptId attempt = attemptId(1);
        transfer = transfer.startAttempt(
                1, withdrawal.effectId(), attempt, transitionId(12),
                evidence("internal:authorized"), ACCEPTED_AT.plusSeconds(2));
        Transfer pending = transfer;
        assertThrows(IllegalArgumentException.class, () -> pending.recordAttemptOutcome(
                2, withdrawal.effectId(), attempt, TransferEffect.Status.AMBIGUOUS,
                transitionId(13), evidence("internal:backdated"),
                ACCEPTED_AT.plusSeconds(1)));
        Transfer ambiguous = transfer.recordAttemptOutcome(
                2, withdrawal.effectId(), attempt, TransferEffect.Status.AMBIGUOUS,
                transitionId(13), evidence("internal:ambiguous"),
                ACCEPTED_AT.plusSeconds(3));

        assertEquals(ACCEPTED_AT.plusSeconds(2),
                ambiguous.effects().getFirst().attempts().getFirst().createdAt());
        assertEquals(ACCEPTED_AT.plusSeconds(3),
                ambiguous.effects().getFirst().attempts().getFirst().updatedAt());
        assertThrows(IllegalStateException.class, () -> ambiguous.startAttempt(
                3, withdrawal.effectId(), attemptId(2), transitionId(14),
                evidence("internal:retry"), ACCEPTED_AT.plusSeconds(4)));
        assertThrows(IllegalStateException.class, () -> ambiguous.prepareEffect(
                3, mint.effectId(), transitionId(15), evidence("internal:skip"),
                ACCEPTED_AT.plusSeconds(4)));
    }

    @Test
    void rehydrationRejectsEffectStateWithoutItsAppendOnlyTransitionHistory() {
        Transfer accepted = accepted();
        TransferEffect first = accepted.effects().getFirst();
        EvidenceRef started = evidence("internal:fabricated-start");
        EvidenceRef applied = evidence("internal:fabricated-applied");
        TransferEffect fabricated = new TransferEffect(
                first.effectId(), first.sequence(), first.kind(),
                first.expectedPredecessor(), TransferEffect.Status.APPLIED,
                List.of(new TransferEffect.Attempt(
                        attemptId(20), Optional.empty(), TransferEffect.Status.APPLIED,
                        ACCEPTED_AT.plusSeconds(1), ACCEPTED_AT.plusSeconds(2),
                        List.of(started, applied))),
                List.of(started, applied));
        List<TransferEffect> effects = new ArrayList<>(accepted.effects());
        effects.set(0, fabricated);

        assertThrows(IllegalArgumentException.class, () -> Transfer.rehydrate(
                accepted.transferId(), accepted.participant(),
                accepted.acceptanceContext(), accepted.quantity(),
                accepted.status(), accepted.version(), accepted.createdAt(), effects,
                accepted.transitions(), accepted.finalityHistories()));
    }

    private static Transfer accepted() {
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 4, 2, new BigInteger("1000000000000"));
        TransferAcceptanceContext context = new TransferAcceptanceContext(
                new BankAccountReference("synthetic-bank:source-001"),
                new BankAccountReference("synthetic-bank:destination-001"),
                new WalletReference("synthetic-wallet:treasury-eth"),
                new WalletReference("synthetic-wallet:recipient-eth"),
                SettlementNetwork.ETHEREUM,
                "USD", "route-v3", "wallet-policy-v2",
                1, "a".repeat(64), 1, "b".repeat(64), "c".repeat(64));
        return Transfer.accepted(
                transferId(1), new TransferParticipant("tenant-a", "participant-a"),
                context, TokenQuantity.parse("12.34", unit),
                List.of(effectId(1), effectId(2), effectId(3), effectId(4), effectId(5)),
                transitionId(1), ACCEPTED_AT, evidence("participant:transfer:accepted"));
    }

    private static TransferId transferId(long value) {
        return new TransferId(new UUID(0, value));
    }

    private static TransferEffect.Id effectId(long value) {
        return new TransferEffect.Id(new UUID(1, value));
    }

    private static TransferTransition.Id transitionId(long value) {
        return new TransferTransition.Id(new UUID(2, value));
    }

    private static AttemptId attemptId(long value) {
        return new AttemptId(new UUID(3, value));
    }

    private static EvidenceRef evidence(String value) {
        return new EvidenceRef(value);
    }
}
