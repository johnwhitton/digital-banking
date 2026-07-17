package io.github.johnwhitton.digitalbanking.domain.transfer;

/** Parent orchestration state. EFFECTS_APPLIED is deliberately not a settlement judgment. */
public enum TransferStatus {
    ACCEPTED,
    IN_PROGRESS,
    MANUAL_REVIEW,
    COMPENSATION_REQUIRED,
    EFFECTS_APPLIED
}
