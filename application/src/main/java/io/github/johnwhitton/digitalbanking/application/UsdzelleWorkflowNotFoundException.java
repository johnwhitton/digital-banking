package io.github.johnwhitton.digitalbanking.application;

public final class UsdzelleWorkflowNotFoundException extends RuntimeException {
    public UsdzelleWorkflowNotFoundException() {
        super("workflow was not found");
    }
}
