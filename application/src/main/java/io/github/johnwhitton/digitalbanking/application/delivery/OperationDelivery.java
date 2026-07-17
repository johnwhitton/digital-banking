package io.github.johnwhitton.digitalbanking.application.delivery;

import java.util.Objects;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;

/** A committed lease for one durable aggregate event delivery. */
public record OperationDelivery(
        UUID deliveryId,
        UUID aggregateId,
        String eventType,
        int eventVersion,
        int payloadSchemaVersion,
        UUID leaseId,
        String workerId,
        int attemptNumber) {

    public OperationDelivery {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(leaseId, "leaseId");
        Objects.requireNonNull(workerId, "workerId");
        if (eventVersion < 1 || payloadSchemaVersion < 1 || attemptNumber < 1) {
            throw new IllegalArgumentException("versions and attemptNumber must be positive");
        }
        if (workerId.isEmpty() || workerId.length() > 128
                || workerId.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException(
                    "workerId must contain 1-128 visible US-ASCII characters");
        }
    }

    /** Compatibility constructor for the existing TokenOperationAccepted delivery contract. */
    public OperationDelivery(
            UUID deliveryId,
            OperationId operationId,
            int eventVersion,
            int payloadSchemaVersion,
            UUID leaseId,
            String workerId,
            int attemptNumber) {
        this(deliveryId, operationId.value(), "TokenOperationAccepted",
                eventVersion, payloadSchemaVersion, leaseId, workerId, attemptNumber);
    }

    public OperationId operationId() {
        return new OperationId(aggregateId);
    }
}
