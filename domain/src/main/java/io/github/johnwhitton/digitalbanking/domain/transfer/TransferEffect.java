package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;

public record TransferEffect(
        Id effectId,
        int sequence,
        Kind kind,
        Optional<Id> expectedPredecessor,
        Status status,
        List<Attempt> attempts,
        List<EvidenceRef> evidenceReferences) {

    public TransferEffect {
        Objects.requireNonNull(effectId, "effectId");
        if (sequence < 1 || sequence > 5) {
            throw new IllegalArgumentException("effect sequence must be between 1 and 5");
        }
        Objects.requireNonNull(kind, "kind");
        expectedPredecessor = Objects.requireNonNull(
                expectedPredecessor, "expectedPredecessor");
        Objects.requireNonNull(status, "status");
        attempts = List.copyOf(attempts);
        evidenceReferences = List.copyOf(evidenceReferences);
        if ((sequence == 1) != expectedPredecessor.isEmpty()) {
            throw new IllegalArgumentException("only the first effect has no predecessor");
        }
        if (status == Status.PLANNED && (!attempts.isEmpty() || !evidenceReferences.isEmpty())) {
            throw new IllegalArgumentException("planned effect has no attempt or evidence");
        }
        if (status != Status.PLANNED && evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("advanced effect requires evidence");
        }
        if (attempts.isEmpty() && status != Status.PLANNED && status != Status.PREPARED) {
            throw new IllegalArgumentException("effect outcome requires an attempt");
        }
        if (!attempts.isEmpty() && status != attempts.getLast().status()) {
            throw new IllegalArgumentException("effect status must match its latest attempt");
        }
        if (new HashSet<>(attempts.stream().map(Attempt::attemptId).toList()).size()
                != attempts.size()) {
            throw new IllegalArgumentException("attempt identities must be distinct");
        }
        for (int index = 0; index < attempts.size(); index++) {
            Optional<AttemptId> predecessor = index == 0
                    ? Optional.empty() : Optional.of(attempts.get(index - 1).attemptId());
            if (!attempts.get(index).predecessor().equals(predecessor)
                    || (index < attempts.size() - 1
                            && attempts.get(index).status() != Status.RETRYABLE_NO_EFFECT)) {
                throw new IllegalArgumentException("attempt lineage is invalid");
            }
        }
    }

    static TransferEffect planned(Id id, int sequence, Kind kind, Optional<Id> predecessor) {
        return new TransferEffect(
                id, sequence, kind, predecessor, Status.PLANNED, List.of(), List.of());
    }

    TransferEffect prepare(EvidenceRef evidence) {
        if (status != Status.PLANNED) {
            throw new IllegalStateException("only a planned effect can be prepared");
        }
        return new TransferEffect(
                effectId, sequence, kind, expectedPredecessor, Status.PREPARED,
                attempts, append(evidenceReferences, evidence));
    }

    TransferEffect startAttempt(AttemptId attemptId, EvidenceRef evidence, Instant createdAt) {
        if (status != Status.PREPARED && status != Status.RETRYABLE_NO_EFFECT) {
            throw new IllegalStateException(
                    "attempt requires prepared or retryable no-effect state");
        }
        Optional<AttemptId> predecessor = attempts.isEmpty()
                ? Optional.empty() : Optional.of(attempts.getLast().attemptId());
        Attempt attempt = new Attempt(
                attemptId, predecessor, Status.ATTEMPT_PENDING,
                createdAt, createdAt, List.of(evidence));
        return new TransferEffect(
                effectId, sequence, kind, expectedPredecessor, Status.ATTEMPT_PENDING,
                append(attempts, attempt), append(evidenceReferences, evidence));
    }

    TransferEffect recordOutcome(
            AttemptId attemptId, Status outcome, EvidenceRef evidence, Instant occurredAt) {
        if (status != Status.ATTEMPT_PENDING || attempts.isEmpty()
                || !attempts.getLast().attemptId().equals(attemptId)) {
            throw new IllegalStateException("outcome does not match the active attempt");
        }
        if (!outcome.isOutcome()) {
            throw new IllegalArgumentException("effect outcome is not terminal for the attempt");
        }
        List<Attempt> changed = new ArrayList<>(attempts);
        changed.set(changed.size() - 1,
                attempts.getLast().withOutcome(outcome, evidence, occurredAt));
        return new TransferEffect(
                effectId, sequence, kind, expectedPredecessor, outcome,
                changed, append(evidenceReferences, evidence));
    }

    private static <T> List<T> append(List<T> values, T value) {
        List<T> changed = new ArrayList<>(values);
        changed.add(Objects.requireNonNull(value, "value"));
        return List.copyOf(changed);
    }

    public record Id(UUID value) {
        public Id {
            Objects.requireNonNull(value, "value");
        }
    }

    public record Attempt(
            AttemptId attemptId,
            Optional<AttemptId> predecessor,
            Status status,
            Instant createdAt,
            Instant updatedAt,
            List<EvidenceRef> evidenceReferences) {

        public Attempt {
            Objects.requireNonNull(attemptId, "attemptId");
            predecessor = Objects.requireNonNull(predecessor, "predecessor");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            evidenceReferences = List.copyOf(evidenceReferences);
            if (status == Status.PLANNED || status == Status.PREPARED) {
                throw new IllegalArgumentException("attempt status is invalid");
            }
            if (updatedAt.isBefore(createdAt)
                    || (status == Status.ATTEMPT_PENDING && !updatedAt.equals(createdAt))) {
                throw new IllegalArgumentException("attempt time history is invalid");
            }
            if (evidenceReferences.isEmpty()) {
                throw new IllegalArgumentException("attempt requires evidence");
            }
            int expectedEvidence = status == Status.ATTEMPT_PENDING ? 1 : 2;
            if (evidenceReferences.size() != expectedEvidence) {
                throw new IllegalArgumentException("attempt evidence history is invalid");
            }
        }

        Attempt withOutcome(Status outcome, EvidenceRef evidence, Instant occurredAt) {
            if (occurredAt.isBefore(createdAt)) {
                throw new IllegalArgumentException("attempt outcome cannot precede its start");
            }
            return new Attempt(
                    attemptId, predecessor, outcome, createdAt, occurredAt,
                    append(evidenceReferences, evidence));
        }
    }

    public enum Kind {
        BANK_WITHDRAWAL,
        TOKEN_MINT,
        TOKEN_TRANSFER,
        TOKEN_BURN,
        BANK_DEPOSIT
    }

    public enum Status {
        PLANNED,
        PREPARED,
        ATTEMPT_PENDING,
        APPLIED,
        AMBIGUOUS,
        RETRYABLE_NO_EFFECT,
        TERMINAL_NO_EFFECT,
        MANUAL_REVIEW,
        COMPENSATION_REQUIRED;

        boolean isOutcome() {
            return switch (this) {
                case APPLIED, AMBIGUOUS, RETRYABLE_NO_EFFECT, TERMINAL_NO_EFFECT,
                        MANUAL_REVIEW, COMPENSATION_REQUIRED -> true;
                default -> false;
            };
        }
    }
}
