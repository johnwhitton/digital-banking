package io.github.johnwhitton.digitalbanking.application.port;

import java.util.List;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;

/**
 * Read-side attempt projection. Attempt creation is persisted only through the guarded
 * {@link OperationRepository} aggregate save contract.
 */
public interface AttemptRepository {

    Optional<OperationAttempt> find(OperationId operationId, AttemptId attemptId);

    List<OperationAttempt> findByOperation(OperationId operationId);
}
