package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MockBankPortTest {

    @Test
    void commandBindsExactContextAndOutcomesAreExplicit() {
        MockBankPort.Command command = new MockBankPort.Command(
                new TransferId(new UUID(0, 1)), new TransferEffect.Id(new UUID(0, 2)),
                new TransferParticipant("tenant-a", "participant-a"),
                new BankAccountReference("synthetic-bank:source"),
                TokenQuantity.parse("10.25", new AssetUnit(
                        "USD_STABLE", "USD", 1, 2, new BigInteger("1000000"))),
                MockBankPort.Operation.WITHDRAWAL, "bank-idempotency-v1",
                new AttemptId(new UUID(0, 3)), "policy-v1",
                Instant.parse("2026-07-17T18:00:00Z"),
                Instant.parse("2026-07-17T18:01:00Z"));

        assertEquals("10.25", command.quantity().toCanonicalString());
        assertEquals(MockBankPort.Classification.APPLIED,
                MockBankPort.Outcome.applied("synthetic-bank-evidence:1").classification());
        assertEquals(MockBankPort.Classification.ALREADY_APPLIED,
                MockBankPort.Outcome.alreadyApplied("synthetic-bank-evidence:1")
                        .classification());
        MockBankPort.Outcome ambiguous = MockBankPort.Outcome.ambiguous(
                "synthetic-bank-evidence:ambiguous-1", "synthetic-timeout");
        assertEquals(MockBankPort.Classification.AMBIGUOUS, ambiguous.classification());
        assertEquals("synthetic-bank-evidence:ambiguous-1",
                ambiguous.evidenceReference());
        assertThrows(IllegalArgumentException.class, () -> new MockBankPort.Command(
                command.transferId(), command.effectId(), command.participant(),
                command.accountReference(), command.quantity(), command.operation(),
                command.idempotencyIdentity(), command.attemptId(), command.policyVersion(),
                command.requestedAt(), command.requestedAt().minusSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new MockBankPort.Outcome(
                MockBankPort.Classification.APPLIED, "", null));
        assertThrows(IllegalArgumentException.class, () -> new MockBankPort.Outcome(
                MockBankPort.Classification.APPLIED, "x".repeat(257), null));
        assertThrows(IllegalArgumentException.class, () -> new MockBankPort.Outcome(
                MockBankPort.Classification.AMBIGUOUS, " ", "synthetic-timeout"));
        assertThrows(IllegalArgumentException.class, () -> new MockBankPort.Outcome(
                MockBankPort.Classification.AMBIGUOUS, "x".repeat(257),
                "synthetic-timeout"));
    }
}
