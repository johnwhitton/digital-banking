package io.github.johnwhitton.digitalbanking.domain.operation;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record OperationAttempt(
        AttemptId attemptId,
        Optional<AttemptId> predecessor,
        Optional<RetryAuthorization> retryAuthorization,
        EvidenceRef authorizationEvidence,
        Instant createdAt) {

    public OperationAttempt {
        Objects.requireNonNull(attemptId, "attemptId");
        predecessor = Objects.requireNonNull(predecessor, "predecessor");
        retryAuthorization = Objects.requireNonNull(retryAuthorization, "retryAuthorization");
        Objects.requireNonNull(authorizationEvidence, "authorizationEvidence");
        Objects.requireNonNull(createdAt, "createdAt");
        if (predecessor.filter(attemptId::equals).isPresent()) {
            throw new IllegalArgumentException("an attempt cannot replace itself");
        }
        if (retryAuthorization.isPresent()) {
            RetryAuthorization retry = retryAuthorization.orElseThrow();
            if (predecessor.isEmpty() || !predecessor.orElseThrow().equals(retry.predecessor())
                    || !authorizationEvidence.equals(retry.evidenceReference())) {
                throw new IllegalArgumentException("retry authorization does not match attempt lineage");
            }
        } else if (predecessor.isPresent()) {
            throw new IllegalArgumentException("follow-up attempt requires retry authorization");
        }
    }
}
