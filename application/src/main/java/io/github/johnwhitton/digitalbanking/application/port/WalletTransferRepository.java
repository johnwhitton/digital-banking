package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;

public interface WalletTransferRepository {

    Acceptance accept(WalletTransferOperation proposed);

    Acceptance acceptRedemption(
            WalletTransferOperation proposed, OperationId burnOperationId);

    /** Accepts custody correlated by a parent workflow before a later burn exists. */
    default Acceptance acceptRedemptionCustody(WalletTransferOperation proposed) {
        throw new UnsupportedOperationException(
                "workflow redemption custody is unavailable");
    }

    Optional<WalletTransferOperation> findByIdempotency(
            ParticipantScope participant, String idempotencyKeyDigest);

    Optional<WalletTransferOperation> findById(OperationId operationId);

    Optional<WalletTransferOperation> findRedemptionByBurn(OperationId burnOperationId);

    StartResult startDelivery(UUID deliveryId, OperationId operationId, Instant startedAt);

    void save(WalletTransferOperation operation, long expectedVersion);

    record Acceptance(WalletTransferOperation operation, boolean replayed) {
    }

    record StartResult(WalletTransferOperation operation, boolean duplicate) {
    }
}
