package io.github.johnwhitton.digitalbanking.application;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

public record UsdzelleWorkflowAcceptance(
        UsdzelleWorkflow workflow, boolean replayed) {
    public UsdzelleWorkflowAcceptance {
        Objects.requireNonNull(workflow, "workflow");
    }
}
