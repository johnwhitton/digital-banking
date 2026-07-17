package io.github.johnwhitton.digitalbanking.application;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.TransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TransferAcceptedDeliveryHandlerTest {

    @Test
    void firstDeliveryPreparesWithdrawalAndRedeliveryReturnsDuplicate() {
        UUID transferId = new UUID(0, 10);
        RecordingRepository repository = new RecordingRepository();
        TransferAcceptedDeliveryHandler handler = new TransferAcceptedDeliveryHandler(
                repository, () -> Instant.parse("2026-07-17T18:00:00Z"),
                () -> new TransferTransition.Id(new UUID(0, 20)));
        OperationDelivery delivery = new OperationDelivery(
                new UUID(0, 30), transferId, "TransferAccepted", 1, 1,
                new UUID(0, 40), "worker-a", 1);

        assertEquals(DeliveryOutcome.Classification.DELIVERED,
                handler.handle(delivery).classification());
        assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                handler.handle(delivery).classification());
        assertEquals(2, repository.calls);
        assertEquals(new TransferId(transferId), repository.transferId);
    }

    @Test
    void unsupportedDeliveryIsTerminalWithoutEffect() {
        RecordingRepository repository = new RecordingRepository();
        TransferAcceptedDeliveryHandler handler = new TransferAcceptedDeliveryHandler(
                repository, Instant::now,
                () -> new TransferTransition.Id(new UUID(0, 21)));
        OperationDelivery tokenDelivery = new OperationDelivery(
                new UUID(0, 31), new UUID(0, 11), "TokenOperationAccepted", 1, 1,
                new UUID(0, 41), "worker-a", 1);

        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                handler.handle(tokenDelivery).classification());
        assertEquals(0, repository.calls);
    }

    @Test
    void unsupportedEventOrPayloadVersionIsTerminalWithoutEffect() {
        RecordingRepository repository = new RecordingRepository();
        TransferAcceptedDeliveryHandler handler = new TransferAcceptedDeliveryHandler(
                repository, Instant::now,
                () -> new TransferTransition.Id(new UUID(0, 22)));
        OperationDelivery futureEvent = new OperationDelivery(
                new UUID(0, 32), new UUID(0, 12), "TransferAccepted", 2, 1,
                new UUID(0, 42), "worker-a", 1);
        OperationDelivery futurePayload = new OperationDelivery(
                new UUID(0, 33), new UUID(0, 13), "TransferAccepted", 1, 2,
                new UUID(0, 43), "worker-a", 1);

        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                handler.handle(futureEvent).classification());
        assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                handler.handle(futurePayload).classification());
        assertEquals(0, repository.calls);
    }

    private static final class RecordingRepository implements TransferRepository {
        private int calls;
        private TransferId transferId;
        private UUID appliedDelivery;

        @Override
        public TransferAcceptance accept(
                ParticipantScope participant, IdempotencyKey key,
                CanonicalCommandMetadata requestCommand, Supplier<Transfer> factory) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Transfer> findById(TransferId id, ParticipantScope participant) {
            return Optional.empty();
        }

        @Override
        public PreparationResult prepareFirstWithdrawal(
                UUID deliveryId, TransferId id,
                TransferTransition.Id transitionId, Instant preparedAt) {
            calls++;
            transferId = id;
            if (deliveryId.equals(appliedDelivery)) {
                return PreparationResult.DUPLICATE;
            }
            appliedDelivery = deliveryId;
            return PreparationResult.APPLIED;
        }
    }
}
