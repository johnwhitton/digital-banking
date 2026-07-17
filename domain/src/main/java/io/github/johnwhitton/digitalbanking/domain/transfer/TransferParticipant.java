package io.github.johnwhitton.digitalbanking.domain.transfer;

import java.util.regex.Pattern;

public record TransferParticipant(String tenantId, String participantId) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    public TransferParticipant {
        requireIdentifier(tenantId, "tenantId");
        requireIdentifier(participantId, "participantId");
    }

    private static void requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a safe identifier");
        }
    }
}
