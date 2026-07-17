package io.github.johnwhitton.digitalbanking.domain.operation;

public enum OperationState {
    REQUESTED(false),
    VALIDATED(false),
    POLICY_PENDING(false),
    APPROVAL_PENDING(false),
    AUTHORIZED(false),
    SIGNING(false),
    SUBMISSION_PENDING(false),
    SUBMISSION_AMBIGUOUS(false),
    OBSERVING(false),
    CHAIN_FINALITY_REACHED(false),
    RECONCILING(false),
    MANUAL_REVIEW(false),
    REJECTED(true),
    FAILED_NO_EFFECT(true),
    COMPLETED(true);

    private final boolean terminal;

    OperationState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }
}
