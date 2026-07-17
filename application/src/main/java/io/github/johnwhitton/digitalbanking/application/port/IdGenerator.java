package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;

public interface IdGenerator {
    OperationId nextOperationId();

    AttemptId nextAttemptId();
}
