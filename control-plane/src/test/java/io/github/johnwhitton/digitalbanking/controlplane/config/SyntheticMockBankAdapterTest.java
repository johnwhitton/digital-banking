package io.github.johnwhitton.digitalbanking.controlplane.config;

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

class SyntheticMockBankAdapterTest {

    @Test
    void appliedModeIsDeterministicallyIdempotent() {
        SyntheticMockBankAdapter adapter = new SyntheticMockBankAdapter(
                SyntheticMockBankAdapter.Mode.APPLIED);
        MockBankPort.Command command = command("bank-effect-1");

        MockBankPort.Outcome first = adapter.request(command);
        MockBankPort.Outcome replay = adapter.request(command);
        MockBankPort.Outcome conflict = adapter.request(command("bank-effect-1", "2"));

        assertEquals(MockBankPort.Classification.APPLIED, first.classification());
        assertEquals(MockBankPort.Classification.ALREADY_APPLIED, replay.classification());
        assertEquals(first.evidenceReference(), replay.evidenceReference());
        assertEquals(MockBankPort.Classification.REJECTED_NO_EFFECT,
                conflict.classification());
        assertEquals("idempotency-conflict", conflict.safeFailureCode());
    }

    @Test
    void nonAppliedModesPreserveExplicitNoEffectAndAmbiguityClassification() {
        assertEquals(MockBankPort.Classification.REJECTED_NO_EFFECT,
                new SyntheticMockBankAdapter(SyntheticMockBankAdapter.Mode.REJECTED_NO_EFFECT)
                        .request(command("rejected")).classification());
        assertEquals(MockBankPort.Classification.RETRYABLE_NO_EFFECT,
                new SyntheticMockBankAdapter(SyntheticMockBankAdapter.Mode.RETRYABLE_NO_EFFECT)
                        .request(command("retryable")).classification());
        MockBankPort.Outcome ambiguous = new SyntheticMockBankAdapter(
                SyntheticMockBankAdapter.Mode.AMBIGUOUS)
                .request(command("ambiguous"));
        assertEquals(MockBankPort.Classification.AMBIGUOUS,
                ambiguous.classification());
        assertEquals("synthetic-bank-evidence:ambiguous",
                ambiguous.evidenceReference());
    }

    private static MockBankPort.Command command(String idempotencyIdentity) {
        return command(idempotencyIdentity, "1");
    }

    private static MockBankPort.Command command(
            String idempotencyIdentity, String amount) {
        Instant requested = Instant.parse("2026-07-17T18:00:00Z");
        return new MockBankPort.Command(
                new TransferId(new UUID(0, 1)), new TransferEffect.Id(new UUID(0, 2)),
                new TransferParticipant("tenant-a", "participant-a"),
                new BankAccountReference("synthetic-bank:source"),
                TokenQuantity.parse(amount, new AssetUnit(
                        "USD_STABLE", "USD", 1, 2, new BigInteger("1000000"))),
                MockBankPort.Operation.WITHDRAWAL, idempotencyIdentity,
                new AttemptId(new UUID(0, 3)), "policy-v1", requested,
                requested.plusSeconds(30));
    }
}
