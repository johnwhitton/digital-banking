package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Optional;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyScope;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

/**
 * Persistence contract. Implementations must atomically bind scope/key/canonical metadata
 * to creation.
 */
public interface OperationRepository {

    OperationAcceptance accept(
            IdempotencyScope scope,
            IdempotencyKey key,
            CanonicalCommandMetadata canonicalCommand,
            Supplier<TokenOperation> operationFactory);

    Optional<TokenOperation> findById(OperationId operationId);

    void save(TokenOperation operation, long expectedVersion);
}
