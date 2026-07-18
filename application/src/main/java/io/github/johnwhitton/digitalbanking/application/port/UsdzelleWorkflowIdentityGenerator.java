package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

public interface UsdzelleWorkflowIdentityGenerator {
    UsdzelleWorkflow.Id nextWorkflowId();
    UsdzelleWorkflow.StepId nextStepId();
    UsdzelleWorkflow.TransitionId nextTransitionId();
}
