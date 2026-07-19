package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

public record TransferAcceptance(
        Transfer transfer,
        Optional<SettlementTransfer> settlement,
        boolean replayed) {

    public TransferAcceptance {
        Objects.requireNonNull(transfer, "transfer");
        settlement = Objects.requireNonNull(settlement, "settlement");
    }

    public TransferAcceptance(Transfer transfer, boolean replayed) {
        this(transfer, Optional.empty(), replayed);
    }
}
