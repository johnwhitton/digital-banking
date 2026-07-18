package io.github.johnwhitton.digitalbanking.domain.workflow;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsdzelleWorkflowTest {

    private static final Instant ACCEPTED_AT = Instant.parse("2026-07-18T15:00:00Z");
    private static final AssetUnit USDZELLE = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @Test
    void acquisitionBindsExactQuantityAndOnlyCompletesAfterReconciliation() {
        UsdzelleWorkflow workflow = accepted(UsdzelleWorkflow.Kind.ACQUISITION);

        assertEquals(UsdzelleWorkflow.Status.ACCEPTED, workflow.status());
        assertEquals(new BigInteger("10000"), workflow.context().usdAmount().value());
        assertEquals(new BigInteger("10000"),
                workflow.context().tokenQuantity().atomicUnits());
        assertEquals(List.of(
                UsdzelleWorkflow.StepKind.WITHDRAWAL,
                UsdzelleWorkflow.StepKind.RESERVE_FUNDING_POST,
                UsdzelleWorkflow.StepKind.MINT,
                UsdzelleWorkflow.StepKind.MINT_ACCOUNTING_POST,
                UsdzelleWorkflow.StepKind.RECONCILIATION),
                workflow.steps().stream().map(UsdzelleWorkflow.Step::kind).toList());

        workflow = completeCurrent(workflow, 20);
        assertEquals(UsdzelleWorkflow.Status.WITHDRAWAL_CONFIRMED, workflow.status());
        workflow = completeCurrent(workflow, 30);
        assertEquals(UsdzelleWorkflow.Status.RESERVE_FUNDED, workflow.status());
        workflow = completeCurrent(workflow, 40);
        assertEquals(UsdzelleWorkflow.Status.MINT_CONFIRMED, workflow.status());
        workflow = completeCurrent(workflow, 50);
        assertEquals(UsdzelleWorkflow.Status.MINT_ACCOUNTED, workflow.status());

        workflow = workflow.beginCurrent(
                workflow.version(), transition(60), evidence("reconciliation-started"), time(60));
        assertEquals(UsdzelleWorkflow.Status.RECONCILIATION_PENDING, workflow.status());
        UsdzelleWorkflow mismatch = workflow.recordReconciliation(
                workflow.version(), ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                transition(61), evidence("reconciliation-mismatch"), time(61));
        assertEquals(UsdzelleWorkflow.Status.MANUAL_REVIEW, mismatch.status());
        assertEquals(ReserveAccounting.ReconciliationStatus.CHAIN_SUPPLY_MISMATCH,
                mismatch.reconciliationConclusion().orElseThrow());
        UsdzelleWorkflow completed = workflow.recordReconciliation(
                workflow.version(), ReserveAccounting.ReconciliationStatus.RECONCILED,
                transition(62), evidence("reconciled"), time(62));
        assertEquals(UsdzelleWorkflow.Status.COMPLETED, completed.status());
        assertEquals(ReserveAccounting.ReconciliationStatus.RECONCILED,
                completed.reconciliationConclusion().orElseThrow());
    }

    @Test
    void redemptionCannotBeginBurnBeforeConfirmedPayoutAccounting() {
        UsdzelleWorkflow workflow = accepted(UsdzelleWorkflow.Kind.REDEMPTION);
        assertEquals(List.of(
                UsdzelleWorkflow.StepKind.CUSTODY_TRANSFER,
                UsdzelleWorkflow.StepKind.CUSTODY_ACCOUNTING_POST,
                UsdzelleWorkflow.StepKind.PAYOUT,
                UsdzelleWorkflow.StepKind.PAYOUT_ACCOUNTING_POST,
                UsdzelleWorkflow.StepKind.BURN,
                UsdzelleWorkflow.StepKind.RECONCILIATION),
                workflow.steps().stream().map(UsdzelleWorkflow.Step::kind).toList());

        UsdzelleWorkflow accepted = workflow;
        assertThrows(IllegalStateException.class, () -> accepted.confirmCurrent(
                accepted.version(), Optional.of(child("burn")), transition(70),
                evidence("burn-before-custody"), time(70)));

        workflow = completeCurrent(workflow, 80);
        assertEquals(UsdzelleWorkflow.Status.CUSTODY_CONFIRMED, workflow.status());
        workflow = completeCurrent(workflow, 90);
        assertEquals(UsdzelleWorkflow.Status.REDEMPTION_PAYABLE_RECORDED, workflow.status());
        workflow = completeCurrent(workflow, 100);
        assertEquals(UsdzelleWorkflow.Status.PAYOUT_CONFIRMED, workflow.status());
        workflow = completeCurrent(workflow, 110);
        assertEquals(UsdzelleWorkflow.Status.PAYOUT_ACCOUNTED, workflow.status());

        UsdzelleWorkflow burnActive = workflow.beginCurrent(
                workflow.version(), transition(120), evidence("burn-started"), time(120));
        assertEquals(UsdzelleWorkflow.Status.BURN_DISPATCH_PENDING, burnActive.status());
    }

    @Test
    void unknownExternalEffectRetainsTheOriginalChildIdentity() {
        UsdzelleWorkflow workflow = accepted(UsdzelleWorkflow.Kind.ACQUISITION)
                .beginCurrent(0, transition(130), evidence("withdrawal-started"), time(130));
        UsdzelleWorkflow unknown = workflow.markCurrentUnknown(
                workflow.version(), child("bank-operation-1"), transition(131),
                evidence("bank-response-lost"), time(131));

        assertEquals(UsdzelleWorkflow.Status.WITHDRAWAL_UNKNOWN, unknown.status());
        assertEquals(child("bank-operation-1"),
                unknown.steps().getFirst().childReference().orElseThrow());
        assertThrows(IllegalStateException.class, () -> unknown.beginCurrent(
                unknown.version(), transition(132), evidence("repeat-withdrawal"), time(132)));
    }

    @Test
    void explicitNoEffectAndUnsafeOutcomesAreTerminalWithoutEligibleSuccessors() {
        UsdzelleWorkflow active = accepted(UsdzelleWorkflow.Kind.ACQUISITION)
                .beginCurrent(0, transition(140), evidence("withdrawal-started"), time(140));
        UsdzelleWorkflow failed = active.failCurrentNoEffect(
                1, transition(141), evidence("withdrawal-rejected"), time(141));
        assertEquals(UsdzelleWorkflow.Status.FAILED_NO_EFFECT, failed.status());
        assertEquals(UsdzelleWorkflow.StepStatus.FAILED_NO_EFFECT,
                failed.steps().getFirst().status());

        UsdzelleWorkflow unsafe = active.requireManualReview(
                1, transition(142), evidence("withdrawal-unsafe"), time(142));
        assertEquals(UsdzelleWorkflow.Status.MANUAL_REVIEW, unsafe.status());
        assertEquals(UsdzelleWorkflow.StepStatus.MANUAL_REVIEW,
                unsafe.steps().getFirst().status());
    }

    private static UsdzelleWorkflow completeCurrent(UsdzelleWorkflow workflow, long id) {
        UsdzelleWorkflow active = workflow.beginCurrent(
                workflow.version(), transition(id), evidence("begin-" + id), time(id));
        return active.confirmCurrent(
                active.version(), Optional.of(child("child-" + id)), transition(id + 1),
                evidence("confirmed-" + id), time(id + 1));
    }

    private static UsdzelleWorkflow accepted(UsdzelleWorkflow.Kind kind) {
        int count = kind == UsdzelleWorkflow.Kind.ACQUISITION ? 5 : 6;
        List<UsdzelleWorkflow.StepId> steps = java.util.stream.LongStream.rangeClosed(1, count)
                .mapToObj(value -> new UsdzelleWorkflow.StepId(new UUID(1, value)))
                .toList();
        return UsdzelleWorkflow.accepted(
                new UsdzelleWorkflow.Id(new UUID(0, kind.ordinal() + 1)), kind,
                new UsdzelleWorkflow.Participant("tenant-a", "participant-a"),
                context(), steps, transition(1), evidence("accepted"));
    }

    private static UsdzelleWorkflow.AcceptedContext context() {
        return new UsdzelleWorkflow.AcceptedContext(
                "user-held-v1", new UsdCents(new BigInteger("10000")),
                TokenQuantity.parse("100", USDZELLE),
                new SyntheticBankAccount.BankId("BANK_1"),
                new SyntheticBankAccount.AccountId("USER_1_BANK_ACCOUNT"),
                new WalletReference("synthetic-wallet:USER_WALLET_1"), "user-key-v1",
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"), "admin-key-v1",
                SettlementNetwork.ETHEREUM, "local-token-v1", "payout-before-burn-v1",
                "usd-usdzelle-1-to-1-v1",
                "accounting-v1", "fee-v1", "finality-v1", "reconciliation-v1",
                "a".repeat(64), "b".repeat(64), ACCEPTED_AT);
    }

    private static UsdzelleWorkflow.TransitionId transition(long value) {
        return new UsdzelleWorkflow.TransitionId(new UUID(2, value));
    }

    private static UsdzelleWorkflow.ChildReference child(String value) {
        return new UsdzelleWorkflow.ChildReference(value);
    }

    private static EvidenceRef evidence(String value) {
        return new EvidenceRef("internal:workflow:" + value);
    }

    private static Instant time(long seconds) {
        return ACCEPTED_AT.plusSeconds(seconds);
    }
}
