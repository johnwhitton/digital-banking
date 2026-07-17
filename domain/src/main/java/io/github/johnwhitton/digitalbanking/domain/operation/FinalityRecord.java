package io.github.johnwhitton.digitalbanking.domain.operation;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

public record FinalityRecord(
        FinalityType type,
        FinalityStatus status,
        String authority,
        String policyVersion,
        Instant updatedAt,
        List<EvidenceRef> evidenceRefs) {

    public FinalityRecord {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        authority = requireText(authority, "authority");
        policyVersion = requireText(policyVersion, "policyVersion");
        Objects.requireNonNull(updatedAt, "updatedAt");
        evidenceRefs = List.copyOf(evidenceRefs);
        if (status != FinalityStatus.NOT_ASSESSED && evidenceRefs.isEmpty()) {
            throw new IllegalArgumentException("assessed finality requires evidence");
        }
    }

    public static FinalityRecord notAssessed(FinalityType type) {
        return new FinalityRecord(
                type, FinalityStatus.NOT_ASSESSED, "not-assessed", "none",
                Instant.EPOCH, List.of());
    }

    public static FinalityRecord assessed(
            FinalityType type,
            FinalityStatus status,
            String authority,
            String policyVersion,
            Instant updatedAt,
            List<EvidenceRef> evidenceRefs) {
        if (status == FinalityStatus.NOT_ASSESSED) {
            throw new IllegalArgumentException("use notAssessed for an unassessed finality");
        }
        return new FinalityRecord(
                type, status, authority, policyVersion, updatedAt, evidenceRefs);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be non-blank and at most 128 characters");
        }
        return value;
    }
}
