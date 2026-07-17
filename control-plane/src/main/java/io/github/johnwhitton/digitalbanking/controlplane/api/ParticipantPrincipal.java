package io.github.johnwhitton.digitalbanking.controlplane.api;

import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;

/** Authenticated tenant and participant identity supplied by an identity adapter. */
public record ParticipantPrincipal(String tenantId, String participantId) {

    private static final Pattern IDENTIFIER =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,63}");

    public ParticipantPrincipal {
        tenantId = requireIdentifier(tenantId, "tenantId");
        participantId = requireIdentifier(participantId, "participantId");
    }

    public ParticipantScope scope() {
        return new ParticipantScope(tenantId, participantId);
    }

    @Override
    public String toString() {
        return "ParticipantPrincipal[REDACTED]";
    }

    private static String requireIdentifier(String value, String field) {
        if (value == null || !IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be a safe bounded identifier");
        }
        return value;
    }
}
