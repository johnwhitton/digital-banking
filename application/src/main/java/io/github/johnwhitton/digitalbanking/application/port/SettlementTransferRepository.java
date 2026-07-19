package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

public interface SettlementTransferRepository {

    Optional<SettlementTransfer> findById(TransferId transferId);

    Optional<SettlementTransfer> findById(
            TransferId transferId, ParticipantScope sender);

    void save(SettlementTransfer transfer, long expectedVersion);
}
