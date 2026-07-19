package io.github.johnwhitton.digitalbanking.domain.workflow;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.junit.jupiter.api.Test;

class SettlementTransferTest {

    @Test
    void acceptsOnlyTheFourOrderedChildBoundariesAndImmutableAutoRedeemRoute() {
        SettlementTransfer transfer = accepted();

        assertEquals(SettlementTransfer.Status.ACCEPTED, transfer.status());
        assertEquals(List.of(
                SettlementTransfer.BoundaryKind.SENDER_ACQUISITION,
                SettlementTransfer.BoundaryKind.USER_TRANSFER,
                SettlementTransfer.BoundaryKind.RECIPIENT_REDEMPTION,
                SettlementTransfer.BoundaryKind.FINAL_RECONCILIATION),
                transfer.boundaries().stream()
                        .map(SettlementTransfer.Boundary::kind).toList());
        assertEquals(SettlementTransfer.BoundaryStatus.ELIGIBLE,
                transfer.boundaries().getFirst().status());
        assertEquals(SettlementTransfer.InstructionMode.AUTO_REDEEM,
                transfer.context().recipient().mode());
        assertEquals(new BigInteger("10000"),
                transfer.context().tokenQuantity().atomicUnits());
    }

    @Test
    void rejectsDuplicateBoundaryIdentities() {
        var duplicate = new SettlementTransfer.BoundaryId(new UUID(0, 11));
        assertThrows(IllegalArgumentException.class, () -> SettlementTransfer.accepted(
                new TransferId(new UUID(0, 1)), context(),
                List.of(duplicate, duplicate,
                        new SettlementTransfer.BoundaryId(new UUID(0, 13)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 14))),
                new SettlementTransfer.TransitionId(new UUID(0, 20)),
                new EvidenceRef("participant:settlement-transfer:accepted")));
    }

    @Test
    void noEffectAfterPriorValueMovementRequiresReviewWhileUnknownFirstChildCanFailCleanly() {
        SettlementTransfer partial = accepted();
        partial = partial.beginCurrent(
                partial.version(), transition(21), evidence("acquisition-started"), time(1));
        var acquisition = new SettlementTransfer.ChildReference("acquisition-child");
        partial = partial.attachCurrentChild(
                partial.version(), acquisition, transition(22),
                evidence("acquisition-attached"), time(2));
        partial = partial.confirmCurrent(
                partial.version(), java.util.Optional.of(acquisition), transition(23),
                evidence("acquisition-completed"), time(3));
        partial = partial.beginCurrent(
                partial.version(), transition(24), evidence("transfer-started"), time(4));

        partial = partial.failCurrentNoEffect(
                partial.version(), transition(25),
                evidence("transfer-no-effect"), time(5));

        assertEquals(SettlementTransfer.Status.MANUAL_REVIEW, partial.status());
        assertEquals(SettlementTransfer.BoundaryStatus.MANUAL_REVIEW,
                partial.currentBoundary().status());

        SettlementTransfer first = accepted();
        first = first.beginCurrent(
                first.version(), transition(31), evidence("first-started"), time(1));
        var unknown = new SettlementTransfer.ChildReference("unknown-child");
        first = first.markCurrentUnknown(
                first.version(), unknown, transition(32),
                evidence("first-unknown"), time(2));
        first = first.failCurrentNoEffect(
                first.version(), transition(33),
                evidence("absence-proven"), time(3));

        assertEquals(SettlementTransfer.Status.FAILED_NO_EFFECT, first.status());
        assertEquals(SettlementTransfer.BoundaryStatus.FAILED_NO_EFFECT,
                first.currentBoundary().status());
    }

    private static SettlementTransfer accepted() {
        return SettlementTransfer.accepted(
                new TransferId(new UUID(0, 1)), context(),
                List.of(
                        new SettlementTransfer.BoundaryId(new UUID(0, 11)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 12)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 13)),
                        new SettlementTransfer.BoundaryId(new UUID(0, 14))),
                new SettlementTransfer.TransitionId(new UUID(0, 20)),
                new EvidenceRef("participant:settlement-transfer:accepted"));
    }

    private static SettlementTransfer.TransitionId transition(long value) {
        return new SettlementTransfer.TransitionId(new UUID(0, value));
    }

    private static EvidenceRef evidence(String value) {
        return new EvidenceRef("internal:settlement-transfer:" + value);
    }

    private static Instant time(long seconds) {
        return Instant.parse("2026-07-18T20:00:00Z").plusSeconds(seconds);
    }

    private static SettlementTransfer.AcceptedContext context() {
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
        var sender = new SettlementTransfer.RouteSnapshot(
                "local-sender", "instruction-v1",
                new SettlementTransfer.Participant("local-demo", "USER_1"),
                new SyntheticBankAccount.BankId("BANK_1"),
                new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT"),
                new BankAccountReference("synthetic-bank:USER_1_BANK_ACCOUNT"),
                new WalletReference("synthetic-wallet:USER_WALLET_1"),
                "sender-wallet-v1", SettlementTransfer.InstructionMode.ACQUISITION);
        var recipient = new SettlementTransfer.RouteSnapshot(
                "local-recipient", "instruction-v1",
                new SettlementTransfer.Participant("local-demo", "USER_2"),
                new SyntheticBankAccount.BankId("BANK_2"),
                new SyntheticBankAccount.AccountId("USER_2_BANK_ACCOUNT"),
                new BankAccountReference("synthetic-bank:USER_2_BANK_ACCOUNT"),
                new WalletReference("synthetic-wallet:USER_WALLET_2"),
                "recipient-wallet-v1", SettlementTransfer.InstructionMode.AUTO_REDEEM);
        return new SettlementTransfer.AcceptedContext(
                "phase-6c-v1", UsdCents.positive(new BigInteger("10000")),
                TokenQuantity.ofAtomic(new BigInteger("10000"), unit),
                sender, recipient,
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"),
                "admin-wallet-v1", SettlementNetwork.ETHEREUM,
                "0x0000000000000000000000000000000000000001",
                "payout-before-burn-v1", "usd-usdzelle-cent-v1",
                "reserve-accounting-v1", "no-fee-local-v1",
                "local-ethereum-finality-v1", "reserve-chain-reconciliation-v1",
                "a".repeat(64), "b".repeat(64), Instant.parse("2026-07-18T20:00:00Z"));
    }
}
