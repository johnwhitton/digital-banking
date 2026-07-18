package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Optional;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

public interface UsdzelleWorkflowRepository {

    UsdzelleWorkflowAcceptance accept(
            ParticipantScope participant,
            UsdzelleWorkflow.Kind kind,
            IdempotencyKey key,
            String requestDigest,
            Supplier<UsdzelleWorkflow> workflowFactory);

    Optional<UsdzelleWorkflow> findById(UsdzelleWorkflow.Id workflowId);

    Optional<UsdzelleWorkflow> findById(
            UsdzelleWorkflow.Id workflowId, ParticipantScope participant);

    void save(UsdzelleWorkflow workflow, long expectedVersion);
}
