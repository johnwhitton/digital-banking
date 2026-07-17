package io.github.johnwhitton.digitalbanking.domain.operation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record OperationTransition(
        long version,
        OperationState from,
        OperationState to,
        String actor,
        String reason,
        Instant occurredAt,
        List<EvidenceRef> evidenceRefs) {

    public OperationTransition {
        if (version <= 0) {
            throw new IllegalArgumentException("transition version must be positive");
        }
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        actor = requireText(actor, "actor");
        reason = requireText(reason, "reason");
        Objects.requireNonNull(occurredAt, "occurredAt");
        evidenceRefs = List.copyOf(evidenceRefs);
        if (evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("transition evidence is required");
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be non-blank and at most 128 characters");
        }
        return value;
    }
}
