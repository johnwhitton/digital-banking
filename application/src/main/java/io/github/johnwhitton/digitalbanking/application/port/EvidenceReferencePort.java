package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;

@FunctionalInterface
public interface EvidenceReferencePort {
    EvidenceRef registerAcceptance(
            CanonicalCommand canonicalCommand,
            ParticipantScope participantScope);
}
