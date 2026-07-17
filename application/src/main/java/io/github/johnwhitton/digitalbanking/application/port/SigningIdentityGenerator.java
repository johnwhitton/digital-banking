package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;

/** Server-owned signing-attempt and provider-request identity source. */
public interface SigningIdentityGenerator {

    SigningAttemptId nextAttemptId();

    ProviderRequestId nextProviderRequestId();
}
