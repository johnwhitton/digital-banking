package io.github.johnwhitton.digitalbanking.application.delivery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferChainPort;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;

/** Coordinates one accepted user-wallet transfer through signing and chain ports. */
public final class WalletTransferAcceptedDeliveryHandler
        implements OperationDeliveryHandler {

    public static final String EVENT_TYPE = "WalletTransferAccepted";

    private final WalletTransferRepository transfers;
    private final WalletTransferChainPort chain;
    private final SigningAuthorityService signing;
    private final WalletIdentityRegistry wallets;
    private final ClockPort clock;
    private final Duration signingLifetime;

    public WalletTransferAcceptedDeliveryHandler(
            WalletTransferRepository transfers,
            WalletTransferChainPort chain,
            SigningAuthorityService signing,
            WalletIdentityRegistry wallets,
            ClockPort clock,
            Duration signingLifetime) {
        this.transfers = Objects.requireNonNull(transfers, "transfers");
        this.chain = Objects.requireNonNull(chain, "chain");
        this.signing = Objects.requireNonNull(signing, "signing");
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.signingLifetime = Objects.requireNonNull(signingLifetime, "signingLifetime");
        if (signingLifetime.isZero() || signingLifetime.isNegative()
                || signingLifetime.compareTo(Duration.ofHours(1)) > 0) {
            throw new IllegalArgumentException(
                    "signingLifetime must be positive and at most one hour");
        }
    }

    @Override
    public DeliveryOutcome handle(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        if (!EVENT_TYPE.equals(delivery.eventType()) || delivery.eventVersion() != 1
                || delivery.payloadSchemaVersion() != 1) {
            return DeliveryOutcome.terminalFailure("unsupported-wallet-transfer-delivery");
        }
        WalletTransferOperation operation = transfers.findById(delivery.operationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "wallet transfer was not found"));
        if (operation.status() == WalletTransferOperation.Status.COMPLETED) {
            return DeliveryOutcome.duplicate();
        }
        if (operation.status() == WalletTransferOperation.Status.MANUAL_REVIEW
                || operation.status() == WalletTransferOperation.Status.FAILED_NO_EFFECT) {
            return DeliveryOutcome.terminalFailure("wallet-transfer-not-executable");
        }
        if (operation.status() == WalletTransferOperation.Status.CHAIN_FINALITY_REACHED) {
            transition(operation, WalletTransferOperation.Status.COMPLETED,
                    "wallet-transfer-chain-slice-complete");
            return DeliveryOutcome.delivered();
        }
        if (operation.status() == WalletTransferOperation.Status.ACCEPTED) {
            operation = transfers.startDelivery(
                    delivery.deliveryId(), operation.operationId(), now()).operation();
        }

        ChainPort.AttemptIdentity identity = new ChainPort.AttemptIdentity(
                operation.operationId(), operation.attemptId(), Optional.empty());
        if (operation.status() == WalletTransferOperation.Status.SIGNING) {
            verifyCurrentSource(operation);
            ChainPort.PreparedAttempt prepared = chain.prepare(delivery.deliveryId(), operation);
            List<ChainPort.SigningRequirement> requirements = signingRequirements(
                    identity, operation, prepared);
            Optional<ChainPort.SignedAttempt> retained = chain.findSignedAttempt(identity);
            if (retained.isEmpty()) {
                Set<Integer> attached = chain.retainedSignatureOrders(identity);
                for (ChainPort.SigningRequirement requirement : requirements) {
                    if (attached.contains(requirement.order())) {
                        continue;
                    }
                    SigningAuthorityService.Request request = signingRequest(
                            operation, prepared, requirement);
                    SigningAuthorityService.Result result = signing.resumeExisting(
                            request.requestId(), prepared.signableMaterial())
                            .orElseGet(() -> signing.sign(request));
                    if (!(result instanceof SigningAuthorityService.Signed signed)) {
                        return signingFailure(operation, result);
                    }
                    if (!"resolved-by-signing-registry".equals(requirement.keyVersion())
                            && !signed.request().keyContext().keyVersion()
                            .equals(Optional.of(requirement.keyVersion()))) {
                        transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                                "signing-key-version-mismatch");
                        return DeliveryOutcome.terminalFailure(
                                "signing-key-version-mismatch");
                    }
                    Optional<SigningAuthorityService.SignatureMaterial> material =
                            signed.signatureMaterial();
                    if (material.isEmpty()) {
                        material = signing.recoverSignature(
                                signed.request().requestId(), prepared.signableMaterial());
                    }
                    if (material.isEmpty()) {
                        transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                                "signed-material-unavailable");
                        return DeliveryOutcome.terminalFailure(
                                "signed-material-unavailable");
                    }
                    SigningAuthorityService.SignatureMaterial signature =
                            material.orElseThrow();
                    chain.attachSignature(
                            identity, new ChainPort.AuthorizedSignature(
                                    signature.bytes(), signature.encoding(),
                                    requirement.signerReference()));
                }
                retained = chain.findSignedAttempt(identity);
            }
            if (retained.isEmpty()) {
                transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                        "required-signatures-incomplete");
                return DeliveryOutcome.terminalFailure(
                        "required-signatures-incomplete");
            }
            operation = transition(
                    operation, WalletTransferOperation.Status.SUBMISSION_PENDING,
                    retained.orElseThrow().evidenceReference().value());
        }

        ChainPort.SignedAttempt signed = chain.findSignedAttempt(identity).orElse(null);
        if (signed == null) {
            transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                    "signed-attempt-missing");
            return DeliveryOutcome.terminalFailure("signed-attempt-missing");
        }
        if (operation.status() == WalletTransferOperation.Status.SUBMISSION_PENDING) {
            ChainPort.SubmissionResult submission = chain.submitOnce(signed);
            switch (submission.classification()) {
                case ACCEPTED -> operation = transition(
                        operation, WalletTransferOperation.Status.OBSERVING,
                        submission.evidenceReference().value());
                case DEFINITIVELY_REJECTED -> {
                    transition(operation, WalletTransferOperation.Status.FAILED_NO_EFFECT,
                            submission.evidenceReference().value());
                    return DeliveryOutcome.terminalFailure("wallet-transfer-rejected");
                }
                case RETRYABLE_NO_EFFECT -> {
                    return DeliveryOutcome.retryableFailure(
                            "wallet-transfer-submission-unavailable");
                }
                case AMBIGUOUS -> {
                    transition(operation,
                            WalletTransferOperation.Status.SUBMISSION_AMBIGUOUS,
                            submission.evidenceReference().value());
                    return DeliveryOutcome.ambiguousAcknowledgement(
                            "wallet-transfer-submission-ambiguous");
                }
            }
        }
        if (operation.status() == WalletTransferOperation.Status.SUBMISSION_AMBIGUOUS) {
            ChainPort.InquiryResult inquiry = chain.inquire(new ChainPort.AttemptIdentity(
                    operation.operationId(), operation.attemptId(),
                    Optional.of(signed.nativeIdentity())));
            if (inquiry.retrySafety() == ChainPort.RetrySafety.REQUIRES_OBSERVATION) {
                operation = transition(operation, WalletTransferOperation.Status.OBSERVING,
                        inquiry.evidenceReference().value());
            } else if (inquiry.retrySafety() == ChainPort.RetrySafety.NO_EFFECT_PROVEN) {
                transition(operation, WalletTransferOperation.Status.FAILED_NO_EFFECT,
                        inquiry.evidenceReference().value());
                return DeliveryOutcome.terminalFailure("wallet-transfer-no-effect-proven");
            } else {
                transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                        inquiry.evidenceReference().value());
                return DeliveryOutcome.terminalFailure("wallet-transfer-ambiguity-unresolved");
            }
        }
        if (operation.status() == WalletTransferOperation.Status.OBSERVING) {
            ChainPort.Observation observation = chain.observe(new ChainPort.ObservationRequest(
                    operation.operationId(), operation.attemptId(), signed.nativeIdentity(),
                    operation.finalityPolicyVersion()));
            switch (observation.classification()) {
                case ABSENT_OR_PENDING -> {
                    return DeliveryOutcome.ambiguousAcknowledgement(
                            "wallet-transfer-observation-pending");
                }
                case CONFIRMED -> {
                    WalletTransferOperation finality = operation.reachBlockchainFinality(
                            operation.version(), "independent-" + networkName(operation)
                                    + "-observer",
                            observation.evidenceReferences().getLast(), now());
                    transfers.save(finality, operation.version());
                    operation = transition(finality, WalletTransferOperation.Status.COMPLETED,
                            "wallet-transfer-chain-slice-complete");
                }
                case REVERTED, MISMATCHED, ORPHANED -> {
                    transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                            observation.evidenceReferences().getLast().value());
                    return DeliveryOutcome.terminalFailure(
                            "wallet-transfer-observation-unsafe");
                }
            }
        }
        return operation.status() == WalletTransferOperation.Status.COMPLETED
                ? DeliveryOutcome.delivered()
                : DeliveryOutcome.ambiguousAcknowledgement(
                        "wallet-transfer-workflow-incomplete");
    }

    private SigningAuthorityService.Request signingRequest(
            WalletTransferOperation operation, ChainPort.PreparedAttempt prepared,
            ChainPort.SigningRequirement requirement) {
        Instant issuedAt = now();
        UUID requestId = UUID.nameUUIDFromBytes(
                ("wallet-transfer-signing-v2:" + operation.operationId() + ":"
                        + operation.attemptId() + ":" + requirement.order() + ":"
                        + requirement.keyRole()).getBytes(StandardCharsets.UTF_8));
        return new SigningAuthorityService.Request(
                new SigningRequestId(requestId),
                new SigningRequest.Correlation(
                        operation.operationId(), operation.attemptId(),
                        Optional.of(operation.transferId()), Optional.of(operation.effectId())),
                Optional.empty(), SigningRequest.Action.TRANSFER, requirement.network(),
                operation.quantity(), requirement.signerReference(),
                prepared.destinationReference(), prepared.nativeActionIdentity(),
                prepared.lifetimeContextDigest(), prepared.feeLimit(),
                prepared.nativeConstraintDigest(), requirement.keyAlias(),
                requirement.keyRole(), requirement.mode(), requirement.algorithm(),
                prepared.signableMaterial(), prepared.policyVersion(),
                List.of(prepared.evidenceReference()), issuedAt,
                issuedAt.plus(signingLifetime));
    }

    private List<ChainPort.SigningRequirement> signingRequirements(
            ChainPort.AttemptIdentity identity,
            WalletTransferOperation operation,
            ChainPort.PreparedAttempt prepared) {
        if (!prepared.sourceReference().equals(operation.source().normalizedAddress())
                || !prepared.destinationReference().equals(
                        operation.destination().normalizedAddress())
                || !operation.finalityPolicyVersion().equals(prepared.policyVersion())) {
            throw new IllegalStateException(
                    "prepared transfer context differs from accepted server policy");
        }
        List<ChainPort.SigningRequirement> requirements = chain.requiredSigners(identity);
        if (requirements.isEmpty()) {
            requirements = List.of(new ChainPort.SigningRequirement(
                    0, operation.source().keyReference(),
                    SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                    operation.network(), SigningRequest.Mode.EVM_DIGEST,
                    SigningRequest.Algorithm.SECP256K1,
                    operation.source().normalizedAddress(),
                    operation.source().keyVersion()));
        }
        Set<Integer> orders = new HashSet<>();
        for (int index = 0; index < requirements.size(); index++) {
            ChainPort.SigningRequirement requirement = requirements.get(index);
            if (requirement.order() != index || !orders.add(requirement.order())
                    || requirement.network() != operation.network()) {
                throw new IllegalStateException(
                        "native signer requirements do not match transfer policy");
            }
        }
        if (operation.network()
                == io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork
                .SOLANA) {
            if (requirements.size() != 2
                    || requirements.get(0).keyRole()
                            != SigningRequest.KeyRole.FEE_PAYER
                    || requirements.get(1).keyRole()
                            != SigningRequest.KeyRole.TRANSFER_AUTHORITY
                    || requirements.stream().anyMatch(requirement ->
                            requirement.mode() != SigningRequest.Mode.SOLANA_MESSAGE
                                    || requirement.algorithm()
                                    != SigningRequest.Algorithm.ED25519)) {
                throw new IllegalStateException(
                        "Solana transfer requires fee payer then source owner");
            }
        } else if (requirements.size() != 1
                || requirements.getFirst().keyRole()
                        != SigningRequest.KeyRole.TRANSFER_AUTHORITY
                || requirements.getFirst().mode() != SigningRequest.Mode.EVM_DIGEST
                || requirements.getFirst().algorithm()
                        != SigningRequest.Algorithm.SECP256K1) {
            throw new IllegalStateException(
                    "Ethereum transfer requires its configured source authority");
        }
        ChainPort.SigningRequirement source = requirements.getLast();
        if (!source.keyAlias().equals(operation.source().keyReference())
                || !source.signerReference().equals(
                        operation.source().normalizedAddress())
                || !source.keyVersion().equals(operation.source().keyVersion())) {
            throw new IllegalStateException(
                    "transfer source signer differs from accepted wallet snapshot");
        }
        Set<Integer> retained = chain.retainedSignatureOrders(identity);
        if (!orders.containsAll(retained)) {
            throw new IllegalStateException("retained signature order is not required");
        }
        return List.copyOf(requirements);
    }

    private void verifyCurrentSource(WalletTransferOperation operation) {
        WalletTransferOperation.WalletSnapshot current =
                WalletTransferOperation.WalletSnapshot.from(
                        wallets.resolve(operation.source().reference()));
        if (!current.equals(operation.source())) {
            throw new IllegalStateException(
                    "source wallet authority changed after transfer acceptance");
        }
    }

    private DeliveryOutcome signingFailure(
            WalletTransferOperation operation, SigningAuthorityService.Result result) {
        if (result instanceof SigningAuthorityService.Ambiguous
                || result instanceof SigningAuthorityService.ApprovalRequired
                || result instanceof SigningAuthorityService.RetryableNoSignature) {
            return DeliveryOutcome.ambiguousAcknowledgement(
                    "wallet-transfer-signing-incomplete");
        }
        transition(operation, WalletTransferOperation.Status.MANUAL_REVIEW,
                "wallet-transfer-signing-rejected");
        return DeliveryOutcome.terminalFailure("wallet-transfer-signing-rejected");
    }

    private WalletTransferOperation transition(
            WalletTransferOperation operation,
            WalletTransferOperation.Status status,
            String evidence) {
        WalletTransferOperation changed = operation.transition(
                operation.version(), status,
                new EvidenceRef(evidence.startsWith("internal:") ? evidence
                        : "internal:local-" + networkName(operation) + ":" + evidence + ":"
                                + operation.operationId()),
                now());
        transfers.save(changed, operation.version());
        return changed;
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock result")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static String networkName(WalletTransferOperation operation) {
        return operation.network().name().toLowerCase(java.util.Locale.ROOT);
    }
}
