package io.github.johnwhitton.digitalbanking.application.port;

import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;

/** Provider-neutral native execution boundary for one accepted wallet transfer. */
public interface WalletTransferChainPort {

    ChainPort.PreparedAttempt prepare(
            UUID deliveryId, WalletTransferOperation operation);

    Optional<ChainPort.SignedAttempt> findSignedAttempt(
            ChainPort.AttemptIdentity attemptIdentity);

    ChainPort.SignedAttempt attachSignature(
            ChainPort.AttemptIdentity attemptIdentity,
            ChainPort.AuthorizedSignature signature);

    ChainPort.SubmissionResult submitOnce(ChainPort.SignedAttempt signedAttempt);

    ChainPort.InquiryResult inquire(ChainPort.AttemptIdentity attemptIdentity);

    ChainPort.Observation observe(ChainPort.ObservationRequest request);
}
