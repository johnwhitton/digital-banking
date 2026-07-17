package io.github.johnwhitton.digitalbanking.domain.operation;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;

/**
 * Immutable token-operation aggregate with optimistic version guards.
 */
public final class TokenOperation {

    private static final Map<OperationState, Set<OperationState>> ALLOWED_TRANSITIONS =
            allowedTransitions();

    private final OperationId operationId;
    private final OperationAcceptanceContext acceptanceContext;
    private final OperationKind kind;
    private final TokenQuantity quantity;
    private final OperationState state;
    private final long version;
    private final Instant createdAt;
    private final List<OperationAttempt> attempts;
    private final List<OperationTransition> transitions;
    private final Map<FinalityType, List<FinalityRecord>> finalityHistory;
    private final List<EvidenceRef> evidenceReferences;

    private TokenOperation(
            OperationId operationId,
            OperationAcceptanceContext acceptanceContext,
            OperationKind kind,
            TokenQuantity quantity,
            OperationState state,
            long version,
            Instant createdAt,
            List<OperationAttempt> attempts,
            List<OperationTransition> transitions,
            Map<FinalityType, List<FinalityRecord>> finalityHistory,
            List<EvidenceRef> evidenceReferences) {
        this.operationId = Objects.requireNonNull(operationId, "operationId");
        this.acceptanceContext = Objects.requireNonNull(acceptanceContext, "acceptanceContext");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.state = Objects.requireNonNull(state, "state");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.attempts = List.copyOf(attempts);
        this.transitions = List.copyOf(transitions);
        this.finalityHistory = immutableFinalityHistory(finalityHistory);
        this.evidenceReferences = List.copyOf(evidenceReferences);
    }

    public static TokenOperation requested(
            OperationId operationId,
            OperationAcceptanceContext acceptanceContext,
            OperationKind kind,
            TokenQuantity quantity,
            Instant createdAt,
            EvidenceRef acceptanceEvidence) {
        Objects.requireNonNull(acceptanceEvidence, "acceptanceEvidence");
        EnumMap<FinalityType, List<FinalityRecord>> history =
                new EnumMap<>(FinalityType.class);
        for (FinalityType type : FinalityType.values()) {
            history.put(type, List.of(FinalityRecord.notAssessed(type)));
        }
        return new TokenOperation(
                operationId, acceptanceContext, kind, quantity,
                OperationState.REQUESTED, 0, createdAt,
                List.of(), List.of(), history, List.of(acceptanceEvidence));
    }

    public TokenOperation transition(
            long expectedVersion,
            OperationState target,
            String actor,
            String reason,
            Instant occurredAt,
            List<EvidenceRef> evidenceRefs) {
        requireMutable(expectedVersion);
        Objects.requireNonNull(target, "target");
        if (!ALLOWED_TRANSITIONS.getOrDefault(state, Set.of()).contains(target)) {
            throw new IllegalStateException("operation transition is not allowed");
        }
        requireTransitionPrerequisites(target);
        Instant earliest = latestRecordedAt();
        if (occurredAt == null || occurredAt.isBefore(earliest)) {
            throw new IllegalArgumentException("transition time cannot move backward");
        }

        OperationTransition transition = new OperationTransition(
                version + 1, state, target, actor, reason, occurredAt, evidenceRefs);
        List<OperationTransition> newTransitions = appended(transitions, transition);
        List<EvidenceRef> newEvidence = appendedAll(evidenceReferences, transition.evidenceRefs());
        return copy(target, version + 1, attempts, newTransitions, finalityHistory, newEvidence);
    }

    public TokenOperation addInitialAttempt(
            long expectedVersion,
            AttemptId attemptId,
            EvidenceRef authorizationEvidence,
            Instant createdAt) {
        requireMutable(expectedVersion);
        if (state != OperationState.AUTHORIZED || !attempts.isEmpty()) {
            throw new IllegalStateException("initial attempt is not allowed in the current state");
        }
        ensureUnique(attemptId);
        Instant earliest = latestRecordedAt();
        if (createdAt == null || createdAt.isBefore(earliest)) {
            throw new IllegalArgumentException("attempt time cannot move backward");
        }
        OperationAttempt attempt = new OperationAttempt(
                attemptId, Optional.empty(), Optional.empty(), authorizationEvidence, createdAt);
        return copy(
                state, version + 1, appended(attempts, attempt), transitions, finalityHistory,
                appended(evidenceReferences, authorizationEvidence));
    }

    public TokenOperation addFollowUpAttempt(
            long expectedVersion,
            AttemptId attemptId,
            RetryAuthorization retryAuthorization,
            Instant createdAt) {
        requireMutable(expectedVersion);
        Objects.requireNonNull(retryAuthorization, "retryAuthorization");
        if (state != OperationState.SUBMISSION_PENDING || attempts.isEmpty()) {
            throw new IllegalStateException(
                    "follow-up attempt requires a pending submission and explicit safe lineage");
        }
        ensureUnique(attemptId);
        if (!attempts.getLast().attemptId().equals(retryAuthorization.predecessor())) {
            throw new IllegalArgumentException("follow-up attempt must reference the latest attempt");
        }
        if (createdAt == null || createdAt.isBefore(latestRecordedAt())) {
            throw new IllegalArgumentException("attempt time cannot move backward");
        }
        OperationAttempt attempt = new OperationAttempt(
                attemptId, Optional.of(retryAuthorization.predecessor()),
                Optional.of(retryAuthorization), retryAuthorization.evidenceReference(), createdAt);
        return copy(
                state, version + 1, appended(attempts, attempt), transitions, finalityHistory,
                appended(evidenceReferences, retryAuthorization.evidenceReference()));
    }

    public TokenOperation recordFinality(long expectedVersion, FinalityRecord finality) {
        requireExpectedVersion(expectedVersion);
        Objects.requireNonNull(finality, "finality");
        FinalityRecord current = finalityHistory.get(finality.type()).getLast();
        if (finality.status() == FinalityStatus.NOT_ASSESSED) {
            throw new IllegalStateException("finality cannot return to not assessed");
        }
        if (finality.updatedAt().isBefore(latestRecordedAt())) {
            throw new IllegalArgumentException("finality time cannot move backward");
        }
        if (!allowedFinalityStatuses(current.status()).contains(finality.status())) {
            throw new IllegalStateException("finality status transition is not allowed");
        }
        EnumMap<FinalityType, List<FinalityRecord>> history =
                new EnumMap<>(FinalityType.class);
        history.putAll(finalityHistory);
        history.put(finality.type(), appended(history.get(finality.type()), finality));
        return copy(
                state, version + 1, attempts, transitions, history,
                appendedAll(evidenceReferences, finality.evidenceRefs()));
    }

    public OperationId operationId() {
        return operationId;
    }

    public OperationKind kind() {
        return kind;
    }

    public OperationAcceptanceContext acceptanceContext() {
        return acceptanceContext;
    }

    public TokenQuantity quantity() {
        return quantity;
    }

    public OperationState state() {
        return state;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<OperationAttempt> attempts() {
        return attempts;
    }

    public List<AttemptId> attemptIds() {
        return attempts.stream().map(OperationAttempt::attemptId).toList();
    }

    public List<OperationTransition> transitions() {
        return transitions;
    }

    public Map<FinalityType, FinalityRecord> finalities() {
        EnumMap<FinalityType, FinalityRecord> current = new EnumMap<>(FinalityType.class);
        finalityHistory.forEach((type, records) -> current.put(type, records.getLast()));
        return Collections.unmodifiableMap(current);
    }

    public List<FinalityRecord> finalityHistory(FinalityType type) {
        return finalityHistory.get(Objects.requireNonNull(type, "type"));
    }

    public List<EvidenceRef> evidenceReferences() {
        return evidenceReferences;
    }

    private void requireMutable(long expectedVersion) {
        requireExpectedVersion(expectedVersion);
        if (state.isTerminal()) {
            throw new IllegalStateException("terminal operation is immutable");
        }
    }

    private void requireExpectedVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalStateException("operation version conflict");
        }
    }

    private void requireTransitionPrerequisites(OperationState target) {
        if ((target == OperationState.SIGNING || target == OperationState.SUBMISSION_PENDING)
                && attempts.isEmpty()) {
            throw new IllegalStateException("signing and submission require an authorized attempt");
        }
        if ((target == OperationState.CHAIN_FINALITY_REACHED
                || target == OperationState.COMPLETED)
                && finalities().get(FinalityType.BLOCKCHAIN).status()
                != FinalityStatus.REACHED) {
            throw new IllegalStateException(
                    "blockchain finality must be reached before lifecycle completion");
        }
    }

    private Instant latestRecordedAt() {
        Instant latest = createdAt;
        if (!transitions.isEmpty() && transitions.getLast().occurredAt().isAfter(latest)) {
            latest = transitions.getLast().occurredAt();
        }
        if (!attempts.isEmpty() && attempts.getLast().createdAt().isAfter(latest)) {
            latest = attempts.getLast().createdAt();
        }
        for (List<FinalityRecord> history : finalityHistory.values()) {
            Instant updatedAt = history.getLast().updatedAt();
            if (updatedAt.isAfter(latest)) {
                latest = updatedAt;
            }
        }
        return latest;
    }

    private void ensureUnique(AttemptId attemptId) {
        Objects.requireNonNull(attemptId, "attemptId");
        if (attemptIds().contains(attemptId)) {
            throw new IllegalArgumentException("attempt ID is already present");
        }
    }

    private TokenOperation copy(
            OperationState newState,
            long newVersion,
            List<OperationAttempt> newAttempts,
            List<OperationTransition> newTransitions,
            Map<FinalityType, List<FinalityRecord>> newFinalityHistory,
            List<EvidenceRef> newEvidenceReferences) {
        return new TokenOperation(
                operationId, acceptanceContext, kind, quantity, newState, newVersion, createdAt,
                newAttempts, newTransitions, newFinalityHistory, newEvidenceReferences);
    }

    private static Set<FinalityStatus> allowedFinalityStatuses(FinalityStatus current) {
        return switch (current) {
            case NOT_ASSESSED -> Set.of(
                    FinalityStatus.PENDING, FinalityStatus.REACHED, FinalityStatus.REJECTED);
            case PENDING -> Set.of(
                    FinalityStatus.PENDING, FinalityStatus.REACHED, FinalityStatus.REJECTED);
            case REACHED -> Set.of(FinalityStatus.REACHED, FinalityStatus.REJECTED);
            case REJECTED -> Set.of(FinalityStatus.REJECTED);
        };
    }

    private static Map<OperationState, Set<OperationState>> allowedTransitions() {
        EnumMap<OperationState, Set<OperationState>> allowed = new EnumMap<>(OperationState.class);
        allowed.put(OperationState.REQUESTED,
                Set.of(OperationState.VALIDATED, OperationState.REJECTED));
        allowed.put(OperationState.VALIDATED, Set.of(OperationState.POLICY_PENDING));
        allowed.put(OperationState.POLICY_PENDING,
                Set.of(OperationState.APPROVAL_PENDING, OperationState.REJECTED));
        allowed.put(OperationState.APPROVAL_PENDING,
                Set.of(OperationState.AUTHORIZED, OperationState.REJECTED));
        allowed.put(OperationState.AUTHORIZED, Set.of(OperationState.SIGNING));
        allowed.put(OperationState.SIGNING,
                Set.of(OperationState.SUBMISSION_PENDING, OperationState.MANUAL_REVIEW));
        allowed.put(OperationState.SUBMISSION_PENDING,
                Set.of(OperationState.OBSERVING, OperationState.SUBMISSION_AMBIGUOUS,
                        OperationState.FAILED_NO_EFFECT));
        allowed.put(OperationState.SUBMISSION_AMBIGUOUS,
                Set.of(OperationState.OBSERVING, OperationState.FAILED_NO_EFFECT,
                        OperationState.MANUAL_REVIEW));
        allowed.put(OperationState.OBSERVING,
                Set.of(OperationState.CHAIN_FINALITY_REACHED, OperationState.MANUAL_REVIEW));
        allowed.put(OperationState.CHAIN_FINALITY_REACHED,
                Set.of(OperationState.RECONCILING));
        allowed.put(OperationState.RECONCILING,
                Set.of(OperationState.COMPLETED, OperationState.MANUAL_REVIEW));
        allowed.put(OperationState.MANUAL_REVIEW,
                Set.of(OperationState.OBSERVING, OperationState.RECONCILING));
        return Collections.unmodifiableMap(allowed);
    }

    private static Map<FinalityType, List<FinalityRecord>> immutableFinalityHistory(
            Map<FinalityType, List<FinalityRecord>> history) {
        EnumMap<FinalityType, List<FinalityRecord>> copy = new EnumMap<>(FinalityType.class);
        for (FinalityType type : FinalityType.values()) {
            List<FinalityRecord> records = List.copyOf(
                    Objects.requireNonNull(history.get(type), "missing finality history"));
            if (records.isEmpty()) {
                throw new IllegalArgumentException("finality history cannot be empty");
            }
            copy.put(type, records);
        }
        return Collections.unmodifiableMap(copy);
    }

    private static <T> List<T> appended(List<T> source, T value) {
        ArrayList<T> copy = new ArrayList<>(source);
        copy.add(Objects.requireNonNull(value, "value"));
        return List.copyOf(copy);
    }

    private static <T> List<T> appendedAll(List<T> source, List<T> values) {
        ArrayList<T> copy = new ArrayList<>(source);
        copy.addAll(List.copyOf(values));
        return List.copyOf(copy);
    }
}
