package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Registers independently observed canonical chain state for accounting consumption. */
@FunctionalInterface
public interface UsdzelleChainEvidencePort {

    ReserveAccounting.EvidenceIdentity register(
            Effect effect, UsdzelleWorkflow workflow, OperationId childOperationId);

    /** Binds an already-confirmed custody child to the later payout-gated burn. */
    default void bindBurn(
            UsdzelleWorkflow workflow,
            OperationId custodyOperationId,
            OperationId burnOperationId) {
        throw new UnsupportedOperationException("workflow burn binding is unavailable");
    }

    enum Effect {
        MINT,
        REDEMPTION_CUSTODY,
        BURN
    }
}
