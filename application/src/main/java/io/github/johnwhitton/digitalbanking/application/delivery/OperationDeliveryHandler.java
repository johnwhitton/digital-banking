package io.github.johnwhitton.digitalbanking.application.delivery;

/**
 * Application-owned at-least-once consumer boundary.
 *
 * <p>A real handler must use {@link OperationDelivery#deliveryId()} as its durable
 * deduplication identity and commit that identity with its bounded business effect.
 */
@FunctionalInterface
public interface OperationDeliveryHandler {

    DeliveryOutcome handle(OperationDelivery delivery) throws Exception;
}
