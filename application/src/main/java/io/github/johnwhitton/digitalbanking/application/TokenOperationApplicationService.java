package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.BurnCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.MintCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.command.TokenOperationCommand;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

/** Transport-neutral durable acceptance and participant-scoped status use cases. */
public final class TokenOperationApplicationService {

    private final TokenOperationService lifecycle;
    private final OperationRepository operations;
    private final AssetUnitCatalog assets;
    private final int supportedContractVersion;

    public TokenOperationApplicationService(
            TokenOperationService lifecycle,
            OperationRepository operations,
            AssetUnitCatalog assets,
            int supportedContractVersion) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.operations = Objects.requireNonNull(operations, "operations");
        this.assets = Objects.requireNonNull(assets, "assets");
        if (supportedContractVersion <= 0) {
            throw new IllegalArgumentException("supported contract version must be positive");
        }
        this.supportedContractVersion = supportedContractVersion;
    }

    public OperationAcceptance accept(
            OperationKind kind,
            ParticipantScope participant,
            AcceptanceRequest request,
            IdempotencyKey idempotencyKey) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        if (request.contractVersion() != supportedContractVersion) {
            throw new UnsupportedRequestContractException();
        }
        AssetUnit unit = assets.find(
                        request.assetId(), request.unitId(), request.unitVersion())
                .orElseThrow(UnknownAssetUnitException::new);
        TokenOperationCommand command;
        try {
            TokenQuantity quantity = TokenQuantity.parse(request.quantity(), unit);
            command = switch (kind) {
                case MINT -> new MintCommand(
                        request.contractVersion(), participant, quantity,
                        request.businessCorrelation());
                case BURN -> new BurnCommand(
                        request.contractVersion(), participant, quantity,
                        request.businessCorrelation());
            };
        } catch (IllegalArgumentException failure) {
            throw new InvalidRequestException(failure);
        }
        return lifecycle.accept(command, idempotencyKey);
    }

    public TokenOperation find(OperationId operationId, ParticipantScope participant) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(participant, "participant");
        return operations.findById(operationId, participant)
                .orElseThrow(OperationNotFoundException::new);
    }

    public record AcceptanceRequest(
            int contractVersion,
            String assetId,
            String unitId,
            int unitVersion,
            String quantity,
            String businessCorrelation) {

        public AcceptanceRequest {
            Objects.requireNonNull(assetId, "assetId");
            Objects.requireNonNull(unitId, "unitId");
            Objects.requireNonNull(quantity, "quantity");
            Objects.requireNonNull(businessCorrelation, "businessCorrelation");
        }
    }
}
