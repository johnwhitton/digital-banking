package io.github.johnwhitton.digitalbanking.application.command;

import java.util.regex.Pattern;

public record ParticipantScope(String tenantId, String participantId) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    public ParticipantScope {
        tenantId = requireText(tenantId, "tenantId");
        participantId = requireText(participantId, "participantId");
    }

    private static String requireText(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a safe bounded identifier");
        }
        return value;
    }
}
