package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;

public interface TransferIdentityGenerator {

    TransferId nextTransferId();

    TransferEffect.Id nextEffectId();

    TransferTransition.Id nextTransitionId();
}
