package io.github.johnwhitton.digitalbanking.application.delivery;

import java.util.Objects;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;

/** Deduplicates TransferAccepted and transactionally prepares only the first withdrawal. */
public final class TransferAcceptedDeliveryHandler implements OperationDeliveryHandler {

    public static final String EVENT_TYPE = "TransferAccepted";

    private final TransferRepository transfers;
    private final ClockPort clock;
    private final Supplier<TransferTransition.Id> transitionIds;

    public TransferAcceptedDeliveryHandler(
            TransferRepository transfers,
            ClockPort clock,
            Supplier<TransferTransition.Id> transitionIds) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.transitionIds = Objects.requireNonNull(transitionIds, "transitionIds");
    }

    @Override
    public DeliveryOutcome handle(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        if (!EVENT_TYPE.equals(delivery.eventType())) {
            return DeliveryOutcome.terminalFailure("unsupported-event-type");
        }
        if (delivery.eventVersion() != 1) {
            return DeliveryOutcome.terminalFailure("unsupported-event-version");
        }
        if (delivery.payloadSchemaVersion() != 1) {
            return DeliveryOutcome.terminalFailure(
                    "unsupported-payload-schema-version");
        }
        TransferRepository.PreparationResult result = transfers.prepareFirstWithdrawal(
                delivery.deliveryId(), new TransferId(delivery.aggregateId()),
                transitionIds.get(), clock.now());
        return result == TransferRepository.PreparationResult.DUPLICATE
                ? DeliveryOutcome.duplicate() : DeliveryOutcome.delivered();
    }
}
