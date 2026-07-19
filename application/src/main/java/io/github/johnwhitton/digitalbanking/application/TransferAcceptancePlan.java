package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Atomic transfer acceptance candidate, optionally including its V10 companion. */
public record TransferAcceptancePlan(
        Transfer transfer,
        Optional<SettlementTransfer> settlement) {

    public TransferAcceptancePlan {
        Objects.requireNonNull(transfer, "transfer");
        settlement = Objects.requireNonNull(settlement, "settlement");
        settlement.ifPresent(value -> {
            if (!value.transferId().equals(transfer.transferId())) {
                throw new IllegalArgumentException(
                        "settlement companion must belong to the transfer");
            }
        });
    }
}
