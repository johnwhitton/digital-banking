package io.github.johnwhitton.digitalbanking.application.port;

import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Resolves only non-secret, versioned key metadata; implementations expose no credentials. */
@FunctionalInterface
public interface SigningKeyRegistry {

    SigningRequest.KeyContext resolve(
            KeyAlias alias,
            SigningRequest.KeyRole role,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network);
}
