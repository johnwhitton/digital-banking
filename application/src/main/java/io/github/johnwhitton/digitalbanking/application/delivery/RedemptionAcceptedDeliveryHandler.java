package io.github.johnwhitton.digitalbanking.application.delivery;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Dispatches the accepted burn and its server-created redemption custody transfer. */
public final class RedemptionAcceptedDeliveryHandler implements OperationDeliveryHandler {

    private final OperationRepository operations;
    private final WalletTransferRepository transfers;
    private final WalletTransferAcceptanceService acceptance;
    private final OperationDeliveryHandler burnHandler;
    private final OperationDeliveryHandler custodyHandler;
    private final WalletReference sourceWallet;
    private final WalletReference adminRedemptionWallet;

    public RedemptionAcceptedDeliveryHandler(
            OperationRepository operations,
            WalletTransferRepository transfers,
            WalletTransferAcceptanceService acceptance,
            OperationDeliveryHandler burnHandler,
            OperationDeliveryHandler custodyHandler,
            WalletReference sourceWallet,
            WalletReference adminRedemptionWallet) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.acceptance = Objects.requireNonNull(acceptance, "acceptance");
        this.burnHandler = Objects.requireNonNull(burnHandler, "burnHandler");
        this.custodyHandler = Objects.requireNonNull(custodyHandler, "custodyHandler");
        this.sourceWallet = Objects.requireNonNull(sourceWallet, "sourceWallet");
        this.adminRedemptionWallet = Objects.requireNonNull(
                adminRedemptionWallet, "adminRedemptionWallet");
    }

    @Override
    public DeliveryOutcome handle(OperationDelivery delivery) throws Exception {
        Objects.requireNonNull(delivery, "delivery");
        if (WalletTransferAcceptedDeliveryHandler.EVENT_TYPE.equals(delivery.eventType())) {
            WalletTransferOperation custody = transfers.findById(delivery.operationId())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "redemption custody transfer was not found"));
            return custodyHandler.handle(delivery);
        }
        if (!TokenOperationAcceptedDeliveryHandler.EVENT_TYPE.equals(delivery.eventType())) {
            return DeliveryOutcome.terminalFailure("unsupported-redemption-delivery");
        }
        TokenOperation burn = operations.findById(delivery.operationId())
                .orElseThrow(() -> new IllegalArgumentException("burn operation was not found"));
        if (burn.kind() != OperationKind.BURN) {
            return DeliveryOutcome.terminalFailure("unsupported-token-operation-kind");
        }
        WalletTransferOperation custody = acceptance.acceptRedemption(
                burn, sourceWallet, adminRedemptionWallet).operation();
        if (custody.status() == WalletTransferOperation.Status.MANUAL_REVIEW
                || custody.status() == WalletTransferOperation.Status.FAILED_NO_EFFECT) {
            return DeliveryOutcome.terminalFailure("redemption-custody-unsafe");
        }
        if (custody.status() != WalletTransferOperation.Status.COMPLETED) {
            return DeliveryOutcome.retryableFailure("redemption-custody-pending");
        }
        return burnHandler.handle(delivery);
    }
}
