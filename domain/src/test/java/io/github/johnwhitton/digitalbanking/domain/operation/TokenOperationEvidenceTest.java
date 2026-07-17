package io.github.johnwhitton.digitalbanking.domain.operation;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenOperationEvidenceTest {

    private static final Instant NOW = Instant.parse("2026-07-16T21:00:00Z");

    @Test
    void preservesOrderedAttemptLineageAndBlocksBlindRetryWhenAmbiguous() {
        TokenOperation authorized = TokenOperationLifecycleTest.operationAt(OperationState.AUTHORIZED);
        AttemptId firstId = AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182");
        AttemptId replacementId = AttemptId.from("df31f44a-1ef5-4e01-9aa9-b886f9db966b");

        TokenOperation withInitial = authorized.addInitialAttempt(
                authorized.version(), firstId, new EvidenceRef("evidence:initial-attempt"), NOW);
        TokenOperation signing = withInitial.transition(
                withInitial.version(), OperationState.SIGNING, "signer-worker", "authorized",
                NOW.plusSeconds(1), List.of(new EvidenceRef("evidence:signing")));
        TokenOperation pending = signing.transition(
                signing.version(), OperationState.SUBMISSION_PENDING, "submit-worker", "signed",
                NOW.plusSeconds(2), List.of(new EvidenceRef("evidence:submission")));
        TokenOperation withReplacement = pending.addFollowUpAttempt(
                pending.version(), replacementId,
                new RetryAuthorization(
                        firstId,
                        RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                        "replacement-policy-v1",
                        new EvidenceRef("evidence:native-safe-replacement")),
                NOW.plusSeconds(3));

        assertEquals(List.of(firstId, replacementId), withReplacement.attemptIds());
        assertEquals(firstId, withReplacement.attempts().get(1).predecessor().orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> withReplacement.addFollowUpAttempt(
                withReplacement.version(), replacementId,
                new RetryAuthorization(
                        replacementId,
                        RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                        "replacement-policy-v1",
                        new EvidenceRef("evidence:duplicate")),
                NOW.plusSeconds(4)));

        TokenOperation ambiguous = withReplacement.transition(
                withReplacement.version(), OperationState.SUBMISSION_AMBIGUOUS, "submit-worker",
                "response-lost", NOW.plusSeconds(5),
                List.of(new EvidenceRef("evidence:timeout")));
        assertThrows(IllegalStateException.class, () -> ambiguous.addFollowUpAttempt(
                ambiguous.version(),
                AttemptId.from("4ab3345e-d39e-4ee3-90fc-c50df024e03e"),
                new RetryAuthorization(
                        replacementId,
                        RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                        "replacement-policy-v1",
                        new EvidenceRef("evidence:unsafe-retry")),
                NOW.plusSeconds(6)));

        EvidenceRef inquiry = new EvidenceRef("evidence:inquiry-found-effect");
        TokenOperation observing = ambiguous.transition(
                ambiguous.version(), OperationState.OBSERVING, "inquiry-worker",
                "effect-found", NOW.plusSeconds(7), List.of(inquiry));
        assertEquals(inquiry, observing.transitions().getLast().evidenceRefs().getFirst());
        assertEquals(inquiry, observing.evidenceReferences().getLast());
    }

    @Test
    void keepsAllFourFinalitiesDistinctAndIndependentlyVersioned() {
        TokenOperation requested = TokenOperationLifecycleTest.operationAt(OperationState.REQUESTED);

        assertEquals(4, requested.finalities().size());
        for (FinalityType type : FinalityType.values()) {
            assertEquals(type, requested.finalities().get(type).type());
            assertEquals(FinalityStatus.NOT_ASSESSED,
                    requested.finalities().get(type).status());
        }

        TokenOperation updated = requested.recordFinality(
                requested.version(),
                FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.PENDING, "chain-risk-policy",
                        "confirmations-v1", NOW, List.of(new EvidenceRef("evidence:block-10"))));

        assertEquals(FinalityStatus.PENDING,
                updated.finalities().get(FinalityType.BLOCKCHAIN).status());
        assertEquals(FinalityStatus.NOT_ASSESSED,
                updated.finalities().get(FinalityType.LEGAL).status());
        assertNotEquals(updated.finalities().get(FinalityType.BLOCKCHAIN),
                updated.finalities().get(FinalityType.LEGAL));
        assertEquals(requested.version() + 1, updated.version());
    }

    @Test
    void finalityHistoryCannotReturnToUnassessedOrMoveBackward() {
        TokenOperation requested = TokenOperationLifecycleTest.operationAt(OperationState.REQUESTED);
        TokenOperation pending = requested.recordFinality(
                requested.version(),
                FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.PENDING,
                        "chain-risk-policy", "confirmations-v1", NOW,
                        List.of(new EvidenceRef("evidence:block-10"))));

        assertThrows(IllegalStateException.class, () -> pending.recordFinality(
                pending.version(), FinalityRecord.notAssessed(FinalityType.BLOCKCHAIN)));
        assertThrows(IllegalArgumentException.class, () -> pending.recordFinality(
                pending.version(), FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.REACHED,
                        "chain-risk-policy", "confirmations-v1", NOW.minusSeconds(1),
                        List.of(new EvidenceRef("evidence:block-9")))));

        TokenOperation reached = pending.recordFinality(
                pending.version(), FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.REACHED,
                        "chain-risk-policy", "confirmations-v1", NOW.plusSeconds(1),
                        List.of(new EvidenceRef("evidence:block-11"))));
        assertThrows(IllegalStateException.class, () -> reached.recordFinality(
                reached.version(), FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.PENDING,
                        "chain-risk-policy", "confirmations-v1", NOW.plusSeconds(2),
                        List.of(new EvidenceRef("evidence:regression")))));
    }

    @Test
    void followUpAttemptCannotPredateTheSubmissionPendingTransition() {
        TokenOperation authorized = TokenOperationLifecycleTest.operationAt(OperationState.AUTHORIZED);
        AttemptId firstId = AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182");
        TokenOperation withInitial = authorized.addInitialAttempt(
                authorized.version(), firstId, new EvidenceRef("evidence:initial"), NOW);
        TokenOperation signing = withInitial.transition(
                withInitial.version(), OperationState.SIGNING, "signer-worker", "authorized",
                NOW.plusSeconds(1), List.of(new EvidenceRef("evidence:signing")));
        TokenOperation pending = signing.transition(
                signing.version(), OperationState.SUBMISSION_PENDING,
                "submit-worker", "signed", NOW.plusSeconds(2),
                List.of(new EvidenceRef("evidence:submission")));

        assertThrows(IllegalArgumentException.class, () -> pending.addFollowUpAttempt(
                pending.version(),
                AttemptId.from("df31f44a-1ef5-4e01-9aa9-b886f9db966b"),
                new RetryAuthorization(
                        firstId,
                        RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                        "replacement-policy-v1",
                        new EvidenceRef("evidence:safe-replacement")),
                NOW.plusSeconds(1)));
    }

    @Test
    void independentFinalityEvidenceCanAdvanceAfterLifecycleCompletion() {
        TokenOperation completed = TokenOperationLifecycleTest.operationAt(OperationState.COMPLETED);

        assertThrows(IllegalArgumentException.class, () -> completed.recordFinality(
                completed.version(),
                FinalityRecord.assessed(
                        FinalityType.LEGAL, FinalityStatus.PENDING,
                        "legal-policy", "legal-v1",
                        completed.transitions().getLast().occurredAt().minusSeconds(1),
                        List.of(new EvidenceRef("evidence:late-recorded-old-assessment")))));

        TokenOperation legallyPending = completed.recordFinality(
                completed.version(),
                FinalityRecord.assessed(
                        FinalityType.LEGAL, FinalityStatus.PENDING,
                        "legal-policy", "legal-v1",
                        completed.transitions().getLast().occurredAt().plusSeconds(1),
                        List.of(new EvidenceRef("evidence:legal-review"))));

        assertEquals(OperationState.COMPLETED, legallyPending.state());
        assertEquals(FinalityStatus.PENDING,
                legallyPending.finalities().get(FinalityType.LEGAL).status());
    }
}
