package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;

/** Durable exact-replay, append-only history, and optimistic-version contract. */
public interface SigningRequestRepository {

    Acceptance accept(SigningRequest proposed);

    Optional<SigningRequest> findById(SigningRequestId requestId);

    void save(SigningRequest request, long expectedVersion);

    record Acceptance(SigningRequest request, boolean replayed) {

        public Acceptance {
            Objects.requireNonNull(request, "request");
        }
    }
}
