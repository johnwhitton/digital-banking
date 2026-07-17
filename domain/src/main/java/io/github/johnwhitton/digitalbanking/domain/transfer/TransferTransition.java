package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;

public record TransferTransition(
        Id transitionId,
        long version,
        Optional<TransferStatus> from,
        TransferStatus to,
        Optional<TransferEffect.Id> effectId,
        String action,
        Instant occurredAt,
        List<EvidenceRef> evidenceReferences) {

    public TransferTransition {
        Objects.requireNonNull(transitionId, "transitionId");
        if (version < 0) {
            throw new IllegalArgumentException("transition version must be non-negative");
        }
        from = Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        effectId = Objects.requireNonNull(effectId, "effectId");
        if (action == null || action.isBlank() || action.length() > 64) {
            throw new IllegalArgumentException("action must contain 1-64 characters");
        }
        Objects.requireNonNull(occurredAt, "occurredAt");
        evidenceReferences = List.copyOf(evidenceReferences);
        if (evidenceReferences.isEmpty()) {
            throw new IllegalArgumentException("transition requires evidence");
        }
    }

    public record Id(UUID value) {
        public Id {
            Objects.requireNonNull(value, "value");
        }
    }
}
