package io.github.johnwhitton.digitalbanking.application.delivery;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Coordinates one accepted mint through the generic chain and Phase 4 signing ports. */
public final class MintAcceptedDeliveryHandler implements OperationDeliveryHandler {

    public static final String EVENT_TYPE = "TokenOperationAccepted";

    private final OperationRepository operations;
    private final ChainPort chain;
    private final SigningAuthorityService signing;
    private final ClockPort clock;
    private final IdGenerator ids;
    private final Policy policy;

    public MintAcceptedDeliveryHandler(
            OperationRepository operations,
            ChainPort chain,
            SigningAuthorityService signing,
            ClockPort clock,
            IdGenerator ids,
            Policy policy) {
        this.operations = Objects.requireNonNull(operations, "operations");
        this.chain = Objects.requireNonNull(chain, "chain");
        this.signing = Objects.requireNonNull(signing, "signing");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    @Override
    public DeliveryOutcome handle(OperationDelivery delivery) {
        Objects.requireNonNull(delivery, "delivery");
        if (!EVENT_TYPE.equals(delivery.eventType()) || delivery.eventVersion() != 1
                || delivery.payloadSchemaVersion() != 1) {
            return DeliveryOutcome.terminalFailure("unsupported-mint-delivery");
        }
        TokenOperation operation = operations.findById(delivery.operationId())
                .orElseThrow(() -> new IllegalArgumentException("operation was not found"));
        if (operation.kind() != OperationKind.MINT) {
            return DeliveryOutcome.terminalFailure("unsupported-token-operation-kind");
        }
        if (operation.state() == OperationState.COMPLETED) {
            return DeliveryOutcome.duplicate();
        }
        if (operation.state().isTerminal() || operation.state() == OperationState.MANUAL_REVIEW) {
            return DeliveryOutcome.terminalFailure("mint-operation-not-executable");
        }

        operation = advanceAcceptance(operation);
        AttemptId attemptId = operation.attempts().getLast().attemptId();
        ChainPort.AttemptIdentity identity = new ChainPort.AttemptIdentity(
                operation.operationId(), attemptId, Optional.empty());

        if (operation.state() == OperationState.SIGNING) {
            OperationAttempt attempt = operation.attempts().getLast();
            ChainPort.PreparedAttempt prepared = chain.prepare(
                    delivery.deliveryId(), operation, attempt);
            Optional<ChainPort.SignedAttempt> retained = chain.findSignedAttempt(identity);
            if (retained.isEmpty()) {
                SigningAuthorityService.Result result = signing.sign(
                        signingRequest(operation, attemptId, prepared));
                if (!(result instanceof SigningAuthorityService.Signed signed)) {
                    return signingFailure(operation, result);
                }
                Optional<SigningAuthorityService.SignatureMaterial> material =
                        signed.signatureMaterial();
                if (material.isEmpty()) {
                    operation = transition(
                            operation, OperationState.MANUAL_REVIEW,
                            "signed-material-unavailable", evidence(operation, "restart-fence"));
                    return DeliveryOutcome.terminalFailure("signed-material-unavailable");
                }
                SigningAuthorityService.SignatureMaterial signature = material.orElseThrow();
                retained = Optional.of(chain.attachSignature(
                        identity,
                        new ChainPort.AuthorizedSignature(
                                signature.bytes(), signature.encoding(),
                                policy.expectedSignerReference())));
            }
            operation = transition(
                    operation, OperationState.SUBMISSION_PENDING,
                    "authorized-signature-attached",
                    retained.orElseThrow().evidenceReference());
        }

        ChainPort.SignedAttempt signedAttempt = chain.findSignedAttempt(identity)
                .orElse(null);
        if (signedAttempt == null) {
            operation = transition(
                    operation, OperationState.MANUAL_REVIEW,
                    "signed-attempt-missing", evidence(operation, "signed-attempt-missing"));
            return DeliveryOutcome.terminalFailure("signed-attempt-missing");
        }

        if (operation.state() == OperationState.SUBMISSION_PENDING) {
            ChainPort.SubmissionResult submission = chain.submitOnce(signedAttempt);
            switch (submission.classification()) {
                case ACCEPTED -> operation = transition(
                        operation, OperationState.OBSERVING,
                        "submission-accepted", submission.evidenceReference());
                case DEFINITIVELY_REJECTED -> {
                    transition(operation, OperationState.FAILED_NO_EFFECT,
                            "submission-rejected", submission.evidenceReference());
                    return DeliveryOutcome.terminalFailure("mint-submission-rejected");
                }
                case RETRYABLE_NO_EFFECT -> {
                    return DeliveryOutcome.retryableFailure("mint-submission-unavailable");
                }
                case AMBIGUOUS -> {
                    transition(operation, OperationState.SUBMISSION_AMBIGUOUS,
                            "submission-ambiguous", submission.evidenceReference());
                    return DeliveryOutcome.ambiguousAcknowledgement(
                            "mint-submission-ambiguous");
                }
            }
        }

        if (operation.state() == OperationState.SUBMISSION_AMBIGUOUS) {
            ChainPort.AttemptIdentity nativeIdentity = new ChainPort.AttemptIdentity(
                    operation.operationId(), attemptId,
                    Optional.of(signedAttempt.nativeIdentity()));
            ChainPort.InquiryResult inquiry = chain.inquire(nativeIdentity);
            if (inquiry.nativeIdentity().isPresent()
                    && inquiry.retrySafety() == ChainPort.RetrySafety.REQUIRES_OBSERVATION) {
                operation = transition(
                        operation, OperationState.OBSERVING,
                        "ambiguous-submission-found", inquiry.evidenceReference());
            } else if (inquiry.retrySafety() == ChainPort.RetrySafety.NO_EFFECT_PROVEN) {
                transition(operation, OperationState.FAILED_NO_EFFECT,
                        "submission-no-effect-proven", inquiry.evidenceReference());
                return DeliveryOutcome.terminalFailure("mint-no-effect-proven");
            } else {
                transition(operation, OperationState.MANUAL_REVIEW,
                        "ambiguous-submission-unresolved", inquiry.evidenceReference());
                return DeliveryOutcome.terminalFailure("mint-ambiguity-unresolved");
            }
        }

        if (operation.state() == OperationState.OBSERVING) {
            ChainPort.Observation observation = chain.observe(new ChainPort.ObservationRequest(
                    operation.operationId(), attemptId, signedAttempt.nativeIdentity(),
                    policy.finalityPolicyVersion()));
            switch (observation.classification()) {
                case ABSENT_OR_PENDING -> {
                    return DeliveryOutcome.ambiguousAcknowledgement("mint-observation-pending");
                }
                case CONFIRMED -> operation = complete(operation, observation);
                case REVERTED, MISMATCHED, ORPHANED -> {
                    transition(operation, OperationState.MANUAL_REVIEW,
                            "mint-observation-" + observation.classification().name()
                                    .toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                            observation.evidenceReferences().getLast());
                    return DeliveryOutcome.terminalFailure("mint-observation-unsafe");
                }
            }
        }

        if (operation.state() == OperationState.CHAIN_FINALITY_REACHED) {
            operation = transition(operation, OperationState.RECONCILING,
                    "blockchain-evidence-reconciled", evidence(operation, "reconciling"));
        }
        if (operation.state() == OperationState.RECONCILING) {
            operation = transition(operation, OperationState.COMPLETED,
                    "mint-chain-slice-complete", evidence(operation, "completed"));
        }
        return operation.state() == OperationState.COMPLETED
                ? DeliveryOutcome.delivered()
                : DeliveryOutcome.ambiguousAcknowledgement("mint-workflow-incomplete");
    }

    private TokenOperation advanceAcceptance(TokenOperation operation) {
        while (true) {
            operation = switch (operation.state()) {
                case REQUESTED -> transition(operation, OperationState.VALIDATED,
                        "accepted-mint-validated", evidence(operation, "validated"));
                case VALIDATED -> transition(operation, OperationState.POLICY_PENDING,
                        "local-policy-selected", evidence(operation, "policy"));
                case POLICY_PENDING -> transition(operation, OperationState.APPROVAL_PENDING,
                        "approval-evidence-bound", evidence(operation, "approval-pending"));
                case APPROVAL_PENDING -> transition(operation, OperationState.AUTHORIZED,
                        "local-mint-authorized", evidence(operation, "authorized"));
                case AUTHORIZED -> startAttempt(operation);
                default -> operation;
            };
            if (operation.state() != OperationState.REQUESTED
                    && operation.state() != OperationState.VALIDATED
                    && operation.state() != OperationState.POLICY_PENDING
                    && operation.state() != OperationState.APPROVAL_PENDING
                    && operation.state() != OperationState.AUTHORIZED) {
                return operation;
            }
        }
    }

    private TokenOperation startAttempt(TokenOperation operation) {
        if (operation.attempts().isEmpty()) {
            EvidenceRef authorization = evidence(operation, "attempt-authorized");
            TokenOperation withAttempt = operation.addInitialAttempt(
                    operation.version(), ids.nextAttemptId(), authorization, now());
            operations.save(withAttempt, operation.version());
            operation = withAttempt;
        }
        return transition(operation, OperationState.SIGNING,
                "mint-attempt-signing", evidence(operation, "signing"));
    }

    private SigningAuthorityService.Request signingRequest(
            TokenOperation operation,
            AttemptId attemptId,
            ChainPort.PreparedAttempt prepared) {
        if (!prepared.sourceReference().equals(policy.expectedSignerReference())) {
            throw new IllegalStateException("prepared signer does not match server policy");
        }
        Instant issuedAt = now();
        UUID requestId = UUID.nameUUIDFromBytes(
                ("mint-signing-v1:" + operation.operationId() + ":" + attemptId)
                        .getBytes(StandardCharsets.UTF_8));
        return new SigningAuthorityService.Request(
                new SigningRequestId(requestId),
                new SigningRequest.Correlation(
                        operation.operationId(), attemptId, Optional.empty(), Optional.empty()),
                Optional.empty(), SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                operation.quantity(), prepared.sourceReference(), prepared.destinationReference(),
                prepared.nativeActionIdentity(), prepared.lifetimeContextDigest(),
                prepared.feeLimit(), prepared.nativeConstraintDigest(), policy.keyAlias(),
                SigningRequest.KeyRole.MINT_AUTHORITY, SigningRequest.Mode.EVM_DIGEST,
                SigningRequest.Algorithm.SECP256K1, prepared.signableMaterial(),
                prepared.policyVersion(), List.of(prepared.evidenceReference()), issuedAt,
                issuedAt.plus(policy.signingLifetime()));
    }

    private DeliveryOutcome signingFailure(
            TokenOperation operation, SigningAuthorityService.Result result) {
        if (result instanceof SigningAuthorityService.Ambiguous
                || result instanceof SigningAuthorityService.ApprovalRequired
                || result instanceof SigningAuthorityService.RetryableNoSignature) {
            return DeliveryOutcome.ambiguousAcknowledgement("mint-signing-incomplete");
        }
        transition(operation, OperationState.MANUAL_REVIEW,
                "mint-signing-rejected", evidence(operation, "signing-rejected"));
        return DeliveryOutcome.terminalFailure("mint-signing-rejected");
    }

    private TokenOperation complete(
            TokenOperation operation, ChainPort.Observation observation) {
        TokenOperation withFinality = operation.recordFinality(
                operation.version(), FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.REACHED,
                        "independent-ethereum-observer", observation.policyVersion(),
                        now(), observation.evidenceReferences()));
        operations.save(withFinality, operation.version());
        return transition(withFinality, OperationState.CHAIN_FINALITY_REACHED,
                "confirmed-mint-observed", observation.evidenceReferences().getLast());
    }

    private TokenOperation transition(
            TokenOperation operation,
            OperationState target,
            String reason,
            EvidenceRef evidence) {
        TokenOperation changed = operation.transition(
                operation.version(), target, "local-ethereum-worker", reason,
                now(), List.of(evidence));
        operations.save(changed, operation.version());
        return changed;
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock result")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private static EvidenceRef evidence(TokenOperation operation, String kind) {
        return new EvidenceRef(
                "internal:local-ethereum:" + kind + ":" + operation.operationId());
    }

    public record Policy(
            KeyAlias keyAlias,
            String expectedSignerReference,
            Duration signingLifetime,
            String finalityPolicyVersion) {

        public Policy {
            Objects.requireNonNull(keyAlias, "keyAlias");
            requireText(expectedSignerReference, "expectedSignerReference");
            Objects.requireNonNull(signingLifetime, "signingLifetime");
            if (signingLifetime.isZero() || signingLifetime.isNegative()
                    || signingLifetime.compareTo(Duration.ofHours(1)) > 0) {
                throw new IllegalArgumentException(
                        "signingLifetime must be positive and at most one hour");
            }
            requireText(finalityPolicyVersion, "finalityPolicyVersion");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
        }
    }
}
