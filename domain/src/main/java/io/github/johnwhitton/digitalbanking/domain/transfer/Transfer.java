package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;

/** Immutable chain-neutral transfer and its ordered value-moving effect plan. */
public final class Transfer {

    private static final List<TransferEffect.Kind> EFFECT_PLAN = List.of(
            TransferEffect.Kind.BANK_WITHDRAWAL,
            TransferEffect.Kind.TOKEN_MINT,
            TransferEffect.Kind.TOKEN_TRANSFER,
            TransferEffect.Kind.TOKEN_BURN,
            TransferEffect.Kind.BANK_DEPOSIT);

    private final TransferId transferId;
    private final TransferParticipant participant;
    private final TransferAcceptanceContext acceptanceContext;
    private final TokenQuantity quantity;
    private final TransferStatus status;
    private final long version;
    private final Instant createdAt;
    private final List<TransferEffect> effects;
    private final List<TransferTransition> transitions;
    private final Map<FinalityType, List<FinalityRecord>> finalityHistories;

    private Transfer(
            TransferId transferId,
            TransferParticipant participant,
            TransferAcceptanceContext acceptanceContext,
            TokenQuantity quantity,
            TransferStatus status,
            long version,
            Instant createdAt,
            List<TransferEffect> effects,
            List<TransferTransition> transitions,
            Map<FinalityType, List<FinalityRecord>> finalityHistories) {
        this.transferId = Objects.requireNonNull(transferId, "transferId");
        this.participant = Objects.requireNonNull(participant, "participant");
        this.acceptanceContext = Objects.requireNonNull(
                acceptanceContext, "acceptanceContext");
        this.quantity = Objects.requireNonNull(quantity, "quantity");
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("transfer version must be non-negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.effects = List.copyOf(effects);
        this.transitions = List.copyOf(transitions);
        this.finalityHistories = copyFinalities(finalityHistories);
        validateShape();
    }

    public static Transfer accepted(
            TransferId transferId,
            TransferParticipant participant,
            TransferAcceptanceContext context,
            TokenQuantity quantity,
            List<TransferEffect.Id> effectIds,
            TransferTransition.Id transitionId,
            Instant acceptedAt,
            EvidenceRef evidence) {
        if (effectIds == null || effectIds.size() != EFFECT_PLAN.size()
                || new HashSet<>(effectIds).size() != EFFECT_PLAN.size()) {
            throw new IllegalArgumentException("five distinct effect identities are required");
        }
        List<TransferEffect> effects = new ArrayList<>(EFFECT_PLAN.size());
        for (int index = 0; index < EFFECT_PLAN.size(); index++) {
            effects.add(TransferEffect.planned(
                    effectIds.get(index), index + 1, EFFECT_PLAN.get(index),
                    index == 0 ? Optional.empty()
                            : Optional.of(effectIds.get(index - 1))));
        }
        Map<FinalityType, List<FinalityRecord>> finalities = new EnumMap<>(FinalityType.class);
        for (FinalityType type : FinalityType.values()) {
            finalities.put(type, List.of(FinalityRecord.notAssessed(type)));
        }
        TransferTransition acceptance = new TransferTransition(
                transitionId, 0, Optional.empty(), TransferStatus.ACCEPTED,
                Optional.empty(), "ACCEPTED", acceptedAt, List.of(evidence));
        return new Transfer(
                transferId, participant, context, quantity, TransferStatus.ACCEPTED,
                0, acceptedAt, effects, List.of(acceptance), finalities);
    }

    public static Transfer rehydrate(
            TransferId transferId,
            TransferParticipant participant,
            TransferAcceptanceContext context,
            TokenQuantity quantity,
            TransferStatus status,
            long version,
            Instant createdAt,
            List<TransferEffect> effects,
            List<TransferTransition> transitions,
            Map<FinalityType, List<FinalityRecord>> finalityHistories) {
        return new Transfer(
                transferId, participant, context, quantity, status, version,
                createdAt, effects, transitions, finalityHistories);
    }

    public Transfer prepareEffect(
            long expectedVersion,
            TransferEffect.Id effectId,
            TransferTransition.Id transitionId,
            EvidenceRef evidence,
            Instant occurredAt) {
        requireVersion(expectedVersion);
        int index = effectIndex(effectId);
        for (int prior = 0; prior < index; prior++) {
            if (effects.get(prior).status() != TransferEffect.Status.APPLIED) {
                throw new IllegalStateException("prior effect is not confirmed applied");
            }
        }
        List<TransferEffect> changed = replace(index, effects.get(index).prepare(evidence));
        TransferStatus next = TransferStatus.IN_PROGRESS;
        return changed(next, changed, transitionId, effectId, "EFFECT_PREPARED", evidence,
                occurredAt);
    }

    public Transfer startAttempt(
            long expectedVersion,
            TransferEffect.Id effectId,
            AttemptId attemptId,
            TransferTransition.Id transitionId,
            EvidenceRef evidence,
            Instant occurredAt) {
        requireVersion(expectedVersion);
        int index = effectIndex(effectId);
        List<TransferEffect> changed = replace(
                index, effects.get(index).startAttempt(attemptId, evidence, occurredAt));
        return changed(TransferStatus.IN_PROGRESS, changed, transitionId, effectId,
                "ATTEMPT_STARTED", evidence, occurredAt);
    }

    public Transfer recordAttemptOutcome(
            long expectedVersion,
            TransferEffect.Id effectId,
            AttemptId attemptId,
            TransferEffect.Status outcome,
            TransferTransition.Id transitionId,
            EvidenceRef evidence,
            Instant occurredAt) {
        requireVersion(expectedVersion);
        int index = effectIndex(effectId);
        List<TransferEffect> changed = replace(
                index, effects.get(index).recordOutcome(
                        attemptId, outcome, evidence, occurredAt));
        TransferStatus next = outcome == TransferEffect.Status.APPLIED
                && index == effects.size() - 1
                ? TransferStatus.EFFECTS_APPLIED : switch (outcome) {
            case MANUAL_REVIEW, TERMINAL_NO_EFFECT -> TransferStatus.MANUAL_REVIEW;
            case COMPENSATION_REQUIRED -> TransferStatus.COMPENSATION_REQUIRED;
            default -> TransferStatus.IN_PROGRESS;
        };
        return changed(next, changed, transitionId, effectId,
                "ATTEMPT_" + outcome.name(), evidence, occurredAt);
    }

    private Transfer changed(
            TransferStatus nextStatus,
            List<TransferEffect> changedEffects,
            TransferTransition.Id transitionId,
            TransferEffect.Id effectId,
            String action,
            EvidenceRef evidence,
            Instant occurredAt) {
        if (occurredAt.isBefore(transitions.getLast().occurredAt())) {
            throw new IllegalArgumentException("transition cannot precede retained history");
        }
        TransferTransition transition = new TransferTransition(
                transitionId, version + 1, Optional.of(status), nextStatus,
                Optional.of(effectId), action, occurredAt, List.of(evidence));
        List<TransferTransition> changedTransitions = new ArrayList<>(transitions);
        changedTransitions.add(transition);
        return new Transfer(
                transferId, participant, acceptanceContext, quantity, nextStatus,
                version + 1, createdAt, changedEffects, changedTransitions,
                finalityHistories);
    }

    private void requireVersion(long expectedVersion) {
        if (expectedVersion != version) {
            throw new IllegalStateException("transfer version conflict");
        }
        if (status == TransferStatus.MANUAL_REVIEW
                || status == TransferStatus.COMPENSATION_REQUIRED
                || status == TransferStatus.EFFECTS_APPLIED) {
            throw new IllegalStateException("transfer does not permit automatic progress");
        }
    }

    private int effectIndex(TransferEffect.Id effectId) {
        for (int index = 0; index < effects.size(); index++) {
            if (effects.get(index).effectId().equals(effectId)) {
                return index;
            }
        }
        throw new IllegalArgumentException("effect does not belong to transfer");
    }

    private List<TransferEffect> replace(int index, TransferEffect effect) {
        List<TransferEffect> changed = new ArrayList<>(effects);
        changed.set(index, effect);
        return List.copyOf(changed);
    }

    private void validateShape() {
        if (effects.size() != EFFECT_PLAN.size()) {
            throw new IllegalArgumentException("transfer must contain five effects");
        }
        for (int index = 0; index < effects.size(); index++) {
            TransferEffect effect = effects.get(index);
            if (effect.sequence() != index + 1 || effect.kind() != EFFECT_PLAN.get(index)
                    || (index == 0 && effect.expectedPredecessor().isPresent())
                    || (index > 0 && !effect.expectedPredecessor()
                            .equals(Optional.of(effects.get(index - 1).effectId())))) {
                throw new IllegalArgumentException("transfer effect plan is invalid");
            }
        }
        if (transitions.isEmpty() || transitions.getFirst().version() != 0
                || transitions.getLast().version() != version) {
            throw new IllegalArgumentException("transfer transition history is incomplete");
        }
        for (int index = 0; index < transitions.size(); index++) {
            TransferTransition transition = transitions.get(index);
            boolean initial = index == 0;
            if (transition.version() != index
                    || (initial && (!transition.from().isEmpty()
                            || transition.to() != TransferStatus.ACCEPTED
                            || transition.effectId().isPresent()
                            || !transition.occurredAt().equals(createdAt)))
                    || (!initial && (!transition.from().equals(
                            Optional.of(transitions.get(index - 1).to()))
                            || transition.effectId().isEmpty()
                            || transition.occurredAt().isBefore(
                                    transitions.get(index - 1).occurredAt())))) {
                throw new IllegalArgumentException("transfer transition history is invalid");
            }
        }
        if (transitions.getLast().to() != status) {
            throw new IllegalArgumentException("transfer status does not match its history");
        }
        int transitionIndex = 1;
        boolean progressionClosed = false;
        TransferStatus derivedStatus = TransferStatus.ACCEPTED;
        for (int effectIndex = 0; effectIndex < effects.size(); effectIndex++) {
            TransferEffect effect = effects.get(effectIndex);
            if (effect.status() == TransferEffect.Status.PLANNED) {
                progressionClosed = true;
                continue;
            }
            if (progressionClosed || (effectIndex > 0
                    && effects.get(effectIndex - 1).status()
                            != TransferEffect.Status.APPLIED)) {
                throw new IllegalArgumentException("transfer effects advanced out of order");
            }
            int expectedEvidence = 1 + effect.attempts().stream()
                    .mapToInt(attempt -> attempt.status()
                            == TransferEffect.Status.ATTEMPT_PENDING ? 1 : 2)
                    .sum();
            if (effect.evidenceReferences().size() != expectedEvidence) {
                throw new IllegalArgumentException("effect evidence history is invalid");
            }
            int evidenceIndex = 0;
            transitionIndex = requireEffectTransition(
                    transitionIndex, effect, "EFFECT_PREPARED",
                    effect.evidenceReferences().get(evidenceIndex++));
            for (TransferEffect.Attempt attempt : effect.attempts()) {
                EvidenceRef attemptStarted = effect.evidenceReferences().get(evidenceIndex);
                if (!attempt.evidenceReferences().getFirst().equals(attemptStarted)) {
                    throw new IllegalArgumentException("attempt evidence history is invalid");
                }
                transitionIndex = requireEffectTransition(
                        transitionIndex, effect, "ATTEMPT_STARTED",
                        effect.evidenceReferences().get(evidenceIndex++));
                if (attempt.status() != TransferEffect.Status.ATTEMPT_PENDING) {
                    EvidenceRef attemptOutcome = effect.evidenceReferences().get(evidenceIndex);
                    if (!attempt.evidenceReferences().getLast().equals(attemptOutcome)) {
                        throw new IllegalArgumentException(
                                "attempt evidence history is invalid");
                    }
                    transitionIndex = requireEffectTransition(
                            transitionIndex, effect,
                            "ATTEMPT_" + attempt.status().name(),
                            effect.evidenceReferences().get(evidenceIndex++));
                }
            }
            if (evidenceIndex != effect.evidenceReferences().size()) {
                throw new IllegalArgumentException("effect evidence history is invalid");
            }
            derivedStatus = effect.status() == TransferEffect.Status.APPLIED
                    && effectIndex == effects.size() - 1
                    ? TransferStatus.EFFECTS_APPLIED : switch (effect.status()) {
                case MANUAL_REVIEW, TERMINAL_NO_EFFECT -> TransferStatus.MANUAL_REVIEW;
                case COMPENSATION_REQUIRED -> TransferStatus.COMPENSATION_REQUIRED;
                default -> TransferStatus.IN_PROGRESS;
            };
        }
        if (transitionIndex != transitions.size() || derivedStatus != status) {
            throw new IllegalArgumentException(
                    "effect state does not match transfer transition history");
        }
        for (FinalityType type : FinalityType.values()) {
            List<FinalityRecord> history = finalityHistories.get(type);
            if (history == null || history.isEmpty()
                    || history.stream().anyMatch(value -> value.type() != type)) {
                throw new IllegalArgumentException("transfer finality history is invalid");
            }
        }
    }

    private int requireEffectTransition(
            int transitionIndex,
            TransferEffect effect,
            String action,
            EvidenceRef evidence) {
        if (transitionIndex >= transitions.size()) {
            throw new IllegalArgumentException("effect transition history is incomplete");
        }
        TransferTransition transition = transitions.get(transitionIndex);
        if (!transition.effectId().equals(Optional.of(effect.effectId()))
                || !transition.action().equals(action)
                || !transition.evidenceReferences().equals(List.of(evidence))) {
            throw new IllegalArgumentException("effect transition history is invalid");
        }
        return transitionIndex + 1;
    }

    private static Map<FinalityType, List<FinalityRecord>> copyFinalities(
            Map<FinalityType, List<FinalityRecord>> source) {
        EnumMap<FinalityType, List<FinalityRecord>> copy =
                new EnumMap<>(FinalityType.class);
        source.forEach((type, history) -> copy.put(type, List.copyOf(history)));
        return Map.copyOf(copy);
    }

    public TransferId transferId() {
        return transferId;
    }

    public TransferParticipant participant() {
        return participant;
    }

    public TransferAcceptanceContext acceptanceContext() {
        return acceptanceContext;
    }

    public TokenQuantity quantity() {
        return quantity;
    }

    public TransferStatus status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<TransferEffect> effects() {
        return effects;
    }

    public List<TransferTransition> transitions() {
        return transitions;
    }

    public Map<FinalityType, List<FinalityRecord>> finalityHistories() {
        return finalityHistories;
    }

    public List<FinalityRecord> finalityHistory(FinalityType type) {
        return finalityHistories.get(Objects.requireNonNull(type, "type"));
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Transfer that
                && transferId.equals(that.transferId)
                && participant.equals(that.participant)
                && acceptanceContext.equals(that.acceptanceContext)
                && quantity.equals(that.quantity)
                && status == that.status
                && version == that.version
                && createdAt.equals(that.createdAt)
                && effects.equals(that.effects)
                && transitions.equals(that.transitions)
                && finalityHistories.equals(that.finalityHistories);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
                transferId, participant, acceptanceContext, quantity, status,
                version, createdAt, effects, transitions, finalityHistories);
    }
}
