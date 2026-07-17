package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;

public record TransferAcceptance(Transfer transfer, boolean replayed) {

    public TransferAcceptance {
        Objects.requireNonNull(transfer, "transfer");
    }
}
