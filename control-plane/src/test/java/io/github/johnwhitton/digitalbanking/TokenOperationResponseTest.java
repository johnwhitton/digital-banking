package io.github.johnwhitton.digitalbanking;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;

import io.github.johnwhitton.digitalbanking.controlplane.api.TokenOperationResponse;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TokenOperationResponseTest {

    @Test
    void exposesOnlyExplicitlyParticipantSafeEvidenceAtEveryResponseLayer() {
        Instant acceptedAt = Instant.parse("2026-07-16T23:00:00Z");
        AssetUnit unit = new AssetUnit(
                "REFERENCE_ASSET", "REFERENCE_UNIT", 3, 2, BigInteger.valueOf(1_000_000));
        TokenOperation operation = TokenOperation.requested(
                OperationId.from("8c5f0a89-9317-4f30-a7ca-f18a395293ce"),
                new OperationAcceptanceContext(
                        "tenant-a", "participant-a", "mints", "a".repeat(64),
                        1, 1, "b".repeat(64), "corr-001"),
                OperationKind.MINT,
                TokenQuantity.parse("12.34", unit),
                acceptedAt,
                new EvidenceRef("evidence:internal-acceptance"));
        operation = operation.transition(
                operation.version(), OperationState.VALIDATED, "validation-worker", "accepted",
                acceptedAt.plusSeconds(1), List.of(
                        new EvidenceRef("evidence:internal-transition"),
                        new EvidenceRef("participant:transition:safe")));
        operation = operation.recordFinality(
                operation.version(), FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.PENDING, "chain-policy",
                        "confirmations-v1", acceptedAt.plusSeconds(2), List.of(
                                new EvidenceRef("evidence:internal-finality"),
                                new EvidenceRef("participant:finality:safe"))));

        TokenOperationResponse response = TokenOperationResponse.from(operation);

        assertEquals(List.of(
                        "participant:transition:safe", "participant:finality:safe"),
                response.evidenceReferences());
        assertEquals(List.of("participant:transition:safe"),
                response.transitions().getFirst().evidenceReferences());
        assertEquals(List.of("participant:finality:safe"),
                response.finalities().blockchain().getLast().evidenceReferences());
    }
}
