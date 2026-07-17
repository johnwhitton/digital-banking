package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.TokenOperationCommand;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;

public interface PolicyApprovalPort {

    PolicyDecision evaluate(TokenOperationCommand command, CanonicalCommand canonicalCommand);

    record PolicyDecision(
            boolean approved,
            String policyVersion,
            EvidenceRef evidenceReference) {

        public PolicyDecision {
            if (policyVersion == null || policyVersion.isBlank()) {
                throw new IllegalArgumentException("policy version is required");
            }
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }
    }
}
