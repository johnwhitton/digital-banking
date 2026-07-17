package io.github.johnwhitton.digitalbanking.application.delivery;

/** Internal diagnostics boundary; implementations must not expose the cause publicly. */
@FunctionalInterface
public interface OperationDeliveryFailureReporter {

    void reportUnexpectedHandlerFailure(OperationDelivery delivery, Exception failure);
}
