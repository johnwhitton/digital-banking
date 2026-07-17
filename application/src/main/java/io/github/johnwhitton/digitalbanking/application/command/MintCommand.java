package io.github.johnwhitton.digitalbanking.application.command;

import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;

public record MintCommand(
        int contractVersion,
        ParticipantScope participantScope,
        TokenQuantity quantity,
        String businessCorrelation) implements TokenOperationCommand {

    public MintCommand {
        businessCorrelation = TokenOperationCommand.validateAndNormalize(
                contractVersion, participantScope, quantity, businessCorrelation);
    }

    @Override
    public OperationKind kind() {
        return OperationKind.MINT;
    }
}
