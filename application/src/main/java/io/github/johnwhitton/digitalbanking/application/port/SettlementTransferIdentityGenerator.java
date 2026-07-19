package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

public interface SettlementTransferIdentityGenerator {

    SettlementTransfer.BoundaryId nextBoundaryId();

    SettlementTransfer.TransitionId nextTransitionId();
}
