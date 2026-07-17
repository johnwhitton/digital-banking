package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;

/** Independent policy/approval decision over the complete immutable signing request. */
@FunctionalInterface
public interface SigningAuthorizationPort {

    Decision evaluate(SigningRequest request);

    sealed interface Decision permits Authorized, AwaitingApproval, Denied {

        EvidenceRef evidence();
    }

    record Authorized(EvidenceRef evidence) implements Decision {

        public Authorized {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record AwaitingApproval(EvidenceRef evidence) implements Decision {

        public AwaitingApproval {
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    record Denied(String safeCode, EvidenceRef evidence) implements Decision {

        private static final Pattern SAFE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

        public Denied {
            if (safeCode == null || !SAFE.matcher(safeCode).matches()) {
                throw new IllegalArgumentException("authorization denial code is invalid");
            }
            Objects.requireNonNull(evidence, "evidence");
        }
    }
}
