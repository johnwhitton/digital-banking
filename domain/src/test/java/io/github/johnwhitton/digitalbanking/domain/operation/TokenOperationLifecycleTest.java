package io.github.johnwhitton.digitalbanking.domain.operation;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenOperationLifecycleTest {

    private static final Instant START = Instant.parse("2026-07-16T20:00:00Z");
    private static final EvidenceRef EVIDENCE = new EvidenceRef("evidence:transition");

    private static final Map<OperationState, List<OperationState>> PATHS = Map.ofEntries(
            Map.entry(OperationState.REQUESTED, List.of()),
            Map.entry(OperationState.VALIDATED, List.of(OperationState.VALIDATED)),
            Map.entry(OperationState.POLICY_PENDING,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING)),
            Map.entry(OperationState.APPROVAL_PENDING,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING)),
            Map.entry(OperationState.AUTHORIZED,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED)),
            Map.entry(OperationState.SIGNING,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING)),
            Map.entry(OperationState.SUBMISSION_PENDING,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING)),
            Map.entry(OperationState.SUBMISSION_AMBIGUOUS,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING,
                            OperationState.SUBMISSION_AMBIGUOUS)),
            Map.entry(OperationState.OBSERVING,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING,
                            OperationState.OBSERVING)),
            Map.entry(OperationState.CHAIN_FINALITY_REACHED,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING,
                            OperationState.OBSERVING, OperationState.CHAIN_FINALITY_REACHED)),
            Map.entry(OperationState.RECONCILING,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING,
                            OperationState.OBSERVING, OperationState.CHAIN_FINALITY_REACHED,
                            OperationState.RECONCILING)),
            Map.entry(OperationState.MANUAL_REVIEW,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.MANUAL_REVIEW)),
            Map.entry(OperationState.REJECTED, List.of(OperationState.REJECTED)),
            Map.entry(OperationState.FAILED_NO_EFFECT,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING,
                            OperationState.FAILED_NO_EFFECT)),
            Map.entry(OperationState.COMPLETED,
                    List.of(OperationState.VALIDATED, OperationState.POLICY_PENDING,
                            OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED,
                            OperationState.SIGNING, OperationState.SUBMISSION_PENDING,
                            OperationState.OBSERVING, OperationState.CHAIN_FINALITY_REACHED,
                            OperationState.RECONCILING, OperationState.COMPLETED)));

    private static final Set<StateChange> ALLOWED = Set.of(
            change(OperationState.REQUESTED, OperationState.VALIDATED),
            change(OperationState.REQUESTED, OperationState.REJECTED),
            change(OperationState.VALIDATED, OperationState.POLICY_PENDING),
            change(OperationState.POLICY_PENDING, OperationState.APPROVAL_PENDING),
            change(OperationState.POLICY_PENDING, OperationState.REJECTED),
            change(OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED),
            change(OperationState.APPROVAL_PENDING, OperationState.REJECTED),
            change(OperationState.AUTHORIZED, OperationState.SIGNING),
            change(OperationState.SIGNING, OperationState.SUBMISSION_PENDING),
            change(OperationState.SIGNING, OperationState.MANUAL_REVIEW),
            change(OperationState.SUBMISSION_PENDING, OperationState.OBSERVING),
            change(OperationState.SUBMISSION_PENDING, OperationState.SUBMISSION_AMBIGUOUS),
            change(OperationState.SUBMISSION_PENDING, OperationState.FAILED_NO_EFFECT),
            change(OperationState.SUBMISSION_AMBIGUOUS, OperationState.OBSERVING),
            change(OperationState.SUBMISSION_AMBIGUOUS, OperationState.FAILED_NO_EFFECT),
            change(OperationState.SUBMISSION_AMBIGUOUS, OperationState.MANUAL_REVIEW),
            change(OperationState.OBSERVING, OperationState.CHAIN_FINALITY_REACHED),
            change(OperationState.OBSERVING, OperationState.MANUAL_REVIEW),
            change(OperationState.CHAIN_FINALITY_REACHED, OperationState.RECONCILING),
            change(OperationState.RECONCILING, OperationState.COMPLETED),
            change(OperationState.RECONCILING, OperationState.MANUAL_REVIEW),
            change(OperationState.MANUAL_REVIEW, OperationState.OBSERVING),
            change(OperationState.MANUAL_REVIEW, OperationState.RECONCILING));

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void permitsEverySpecifiedTransition(OperationState from, OperationState to) {
        TokenOperation operation = operationAt(from);
        operation = prepareFor(operation, to);

        TokenOperation changed = transition(operation, to);

        assertEquals(to, changed.state());
        assertEquals(operation.version() + 1, changed.version());
        assertEquals(operation.transitions().size() + 1, changed.transitions().size());
        assertEquals(to, changed.transitions().getLast().to());
    }

    @ParameterizedTest
    @MethodSource("forbiddenTransitions")
    void rejectsEveryUnspecifiedTransition(OperationState from, OperationState to) {
        TokenOperation operation = operationAt(from);

        assertThrows(IllegalStateException.class, () -> transition(operation, to));
    }

    @Test
    void rejectsOptimisticVersionConflict() {
        TokenOperation requested = operationAt(OperationState.REQUESTED);

        assertThrows(IllegalStateException.class, () -> requested.transition(
                1, OperationState.VALIDATED, "policy-worker", "validated", START,
                List.of(EVIDENCE)));
    }

    @Test
    void rejectsOutOfOrderTransitionTime() {
        TokenOperation requested = operationAt(OperationState.REQUESTED);

        assertThrows(IllegalArgumentException.class, () -> requested.transition(
                requested.version(), OperationState.VALIDATED, "policy-worker", "validated",
                START.minusSeconds(1), List.of(EVIDENCE)));
    }

    @Test
    void rejectsSigningBeforeAnAttemptIdentityExists() {
        TokenOperation authorized = operationAt(OperationState.AUTHORIZED);

        assertThrows(IllegalStateException.class,
                () -> transition(authorized, OperationState.SIGNING));
    }

    @Test
    void rejectsChainFinalityStateWithoutReachedBlockchainFinality() {
        TokenOperation observing = operationAt(OperationState.OBSERVING);

        assertThrows(IllegalStateException.class,
                () -> transition(observing, OperationState.CHAIN_FINALITY_REACHED));
    }

    @Test
    void rejectsCompletionWithoutReachedBlockchainFinality() {
        TokenOperation manualReview = operationAt(OperationState.MANUAL_REVIEW);
        TokenOperation reconciling = transition(manualReview, OperationState.RECONCILING);

        assertThrows(IllegalStateException.class,
                () -> transition(reconciling, OperationState.COMPLETED));
    }

    @Test
    void rejectsInitialAttemptTimeBeforeLatestOperationHistory() {
        TokenOperation authorized = operationAt(OperationState.AUTHORIZED);

        assertThrows(IllegalArgumentException.class, () -> authorized.addInitialAttempt(
                authorized.version(),
                AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182"),
                new EvidenceRef("evidence:attempt-authorization"),
                START));
    }

    @Test
    void rejectsTransitionTimeBeforeAlreadyRecordedAttempt() {
        TokenOperation authorized = operationAt(OperationState.AUTHORIZED);
        TokenOperation withAttempt = prepareFor(authorized, OperationState.SIGNING);

        assertThrows(IllegalArgumentException.class, () -> withAttempt.transition(
                withAttempt.version(), OperationState.SIGNING, "signer-worker", "authorized",
                withAttempt.attempts().getLast().createdAt().minusSeconds(1),
                List.of(EVIDENCE)));
    }

    @Test
    void terminalStatesAreImmutable() {
        for (OperationState terminal : List.of(
                OperationState.REJECTED, OperationState.FAILED_NO_EFFECT,
                OperationState.COMPLETED)) {
            TokenOperation operation = operationAt(terminal);
            assertThrows(IllegalStateException.class,
                    () -> transition(operation, OperationState.MANUAL_REVIEW), terminal.name());
        }
    }

    static TokenOperation operationAt(OperationState state) {
        AssetUnit unit = new AssetUnit("USDC", "USD", 7, 6, BigInteger.TEN.pow(18));
        TokenOperation operation = TokenOperation.requested(
                OperationId.from("9ecbbdb1-cf29-4f35-b762-1212a5727c38"),
                acceptanceContext(),
                OperationKind.MINT,
                TokenQuantity.parse("1", unit),
                START,
                new EvidenceRef("evidence:acceptance"));
        for (OperationState target : PATHS.get(state)) {
            operation = prepareFor(operation, target);
            operation = operation.transition(operation.version(), target, "test-worker",
                    "advance", nextEventTime(operation), List.of(EVIDENCE));
        }
        return operation;
    }

    private static OperationAcceptanceContext acceptanceContext() {
        return new OperationAcceptanceContext(
                "tenant-a",
                "participant-a",
                "TOKEN_OPERATION",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                1,
                1,
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "corr-001");
    }

    private static TokenOperation prepareFor(
            TokenOperation operation, OperationState target) {
        if (target == OperationState.SIGNING && operation.attempts().isEmpty()) {
            return operation.addInitialAttempt(
                    operation.version(),
                    AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182"),
                    new EvidenceRef("evidence:attempt-authorization"),
                    nextEventTime(operation));
        }
        if (target == OperationState.CHAIN_FINALITY_REACHED
                && operation.finalities().get(FinalityType.BLOCKCHAIN).status()
                != FinalityStatus.REACHED) {
            return operation.recordFinality(
                    operation.version(),
                    FinalityRecord.assessed(
                            FinalityType.BLOCKCHAIN,
                            FinalityStatus.REACHED,
                            "chain-risk-policy",
                            "finality-v1",
                            nextEventTime(operation),
                            List.of(new EvidenceRef("evidence:blockchain-finality"))));
        }
        return operation;
    }

    private static TokenOperation transition(TokenOperation operation, OperationState target) {
        return operation.transition(operation.version(), target, "test-worker", "advance",
                nextEventTime(operation), List.of(EVIDENCE));
    }

    private static Instant nextEventTime(TokenOperation operation) {
        Instant latest = operation.createdAt();
        if (!operation.transitions().isEmpty()) {
            latest = latest.isAfter(operation.transitions().getLast().occurredAt())
                    ? latest : operation.transitions().getLast().occurredAt();
        }
        if (!operation.attempts().isEmpty()) {
            latest = latest.isAfter(operation.attempts().getLast().createdAt())
                    ? latest : operation.attempts().getLast().createdAt();
        }
        for (FinalityRecord finality : operation.finalities().values()) {
            latest = latest.isAfter(finality.updatedAt()) ? latest : finality.updatedAt();
        }
        return latest.plusSeconds(1);
    }

    private static Stream<Arguments> allowedTransitions() {
        return ALLOWED.stream().map(change -> Arguments.of(change.from(), change.to()));
    }

    private static Stream<Arguments> forbiddenTransitions() {
        return Stream.of(OperationState.values())
                .flatMap(from -> Stream.of(OperationState.values())
                        .map(to -> change(from, to)))
                .filter(change -> !ALLOWED.contains(change))
                .map(change -> Arguments.of(change.from(), change.to()));
    }

    private static StateChange change(OperationState from, OperationState to) {
        return new StateChange(from, to);
    }

    private record StateChange(OperationState from, OperationState to) {
    }
}
