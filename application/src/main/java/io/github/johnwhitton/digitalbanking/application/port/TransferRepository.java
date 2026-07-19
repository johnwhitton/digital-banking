package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.TransferAcceptance;
import io.github.johnwhitton.digitalbanking.application.TransferAcceptancePlan;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

public interface TransferRepository {

    TransferAcceptance accept(
            ParticipantScope participant,
            IdempotencyKey key,
            CanonicalCommandMetadata requestCommand,
            Supplier<TransferAcceptancePlan> transferFactory);

    Optional<Transfer> findById(TransferId transferId, ParticipantScope participant);

    default Optional<SettlementTransfer> findSettlementById(
            TransferId transferId, ParticipantScope participant) {
        return Optional.empty();
    }

    PreparationResult prepareFirstWithdrawal(
            UUID deliveryId,
            TransferId transferId,
            TransferTransition.Id transitionId,
            Instant preparedAt);

    enum PreparationResult {
        APPLIED,
        DUPLICATE
    }
}
