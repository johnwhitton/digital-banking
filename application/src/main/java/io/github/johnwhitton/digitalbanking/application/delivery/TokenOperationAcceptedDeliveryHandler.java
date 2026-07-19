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
import io.github.johnwhitton.digitalbanking.domain.operation.RetryAuthorization;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Coordinates one accepted mint or burn through the generic chain and signing ports. */
public final class TokenOperationAcceptedDeliveryHandler
        implements OperationDeliveryHandler {

    public static final String EVENT_TYPE = "TokenOperationAccepted";

    private final OperationRepository operations;
    private final ChainPort chain;
    private final SigningAuthorityService signing;
    private final ClockPort clock;
    private final IdGenerator ids;
    private final Policy policy;

    public TokenOperationAcceptedDeliveryHandler(
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
            return DeliveryOutcome.terminalFailure("unsupported-" + action() + "-delivery");
        }
        TokenOperation operation = operations.findById(delivery.operationId())
                .orElseThrow(() -> new IllegalArgumentException("operation was not found"));
        if (operation.kind() != policy.operationKind()) {
            return DeliveryOutcome.terminalFailure("unsupported-token-operation-kind");
        }
        if (operation.state() == OperationState.COMPLETED) {
            return DeliveryOutcome.duplicate();
        }
        if (operation.state().isTerminal() || operation.state() == OperationState.MANUAL_REVIEW) {
            return DeliveryOutcome.terminalFailure(action() + "-operation-not-executable");
        }

        operation = advanceAcceptance(operation);
        AttemptId attemptId = operation.attempts().getLast().attemptId();
        ChainPort.AttemptIdentity identity = new ChainPort.AttemptIdentity(
                operation.operationId(), attemptId, Optional.empty());

        boolean replacementNeedsSigning = operation.kind() == OperationKind.BURN
                && operation.state() == OperationState.SUBMISSION_PENDING
                && chain.findSignedAttempt(identity).isEmpty();
        if (operation.state() == OperationState.SIGNING || replacementNeedsSigning) {
            OperationAttempt attempt = operation.attempts().getLast();
            ChainPort.PreparedAttempt prepared = chain.prepare(
                    delivery.deliveryId(), operation, attempt);
            List<ChainPort.SigningRequirement> requirements = signingRequirements(
                    identity, prepared);
            Optional<ChainPort.SignedAttempt> retained = chain.findSignedAttempt(identity);
            if (retained.isEmpty()) {
                Set<Integer> attached = chain.retainedSignatureOrders(identity);
                for (ChainPort.SigningRequirement requirement : requirements) {
                    if (attached.contains(requirement.order())) {
                        continue;
                    }
                    SigningAuthorityService.Result result = signing.sign(
                            signingRequest(operation, attemptId, prepared, requirement));
                    if (!(result instanceof SigningAuthorityService.Signed signed)) {
                        return signingFailure(operation, result);
                    }
                    if (!"resolved-by-signing-registry".equals(requirement.keyVersion())
                            && !signed.request().keyContext().keyVersion()
                            .equals(Optional.of(requirement.keyVersion()))) {
                        operation = transition(
                                operation, OperationState.MANUAL_REVIEW,
                                "signing-key-version-mismatch",
                                evidence(operation, "signing-key-version-mismatch"));
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
                        operation = transition(
                                operation, OperationState.MANUAL_REVIEW,
                                "signed-material-unavailable",
                                evidence(operation, "restart-fence"));
                        return DeliveryOutcome.terminalFailure(
                                "signed-material-unavailable");
                    }
                    SigningAuthorityService.SignatureMaterial signature =
                            material.orElseThrow();
                    chain.attachSignature(
                            identity,
                            new ChainPort.AuthorizedSignature(
                                    signature.bytes(), signature.encoding(),
                                    requirement.signerReference()));
                }
                retained = chain.findSignedAttempt(identity);
            }
            if (retained.isEmpty()) {
                operation = transition(
                        operation, OperationState.MANUAL_REVIEW,
                        "required-signatures-incomplete",
                        evidence(operation, "required-signatures-incomplete"));
                return DeliveryOutcome.terminalFailure("required-signatures-incomplete");
            }
            if (operation.state() == OperationState.SIGNING) {
                operation = transition(
                        operation, OperationState.SUBMISSION_PENDING,
                        "authorized-signature-attached",
                        retained.orElseThrow().evidenceReference());
            }
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
                    return DeliveryOutcome.terminalFailure(action() + "-submission-rejected");
                }
                case RETRYABLE_NO_EFFECT -> {
                    if (operation.kind() == OperationKind.BURN
                            && submission.nativeIdentity() != null) {
                        RetryAuthorization retry = new RetryAuthorization(
                                attemptId, RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                                policy.finalityPolicyVersion(),
                                submission.evidenceReference());
                        TokenOperation replacement = operation.addFollowUpAttempt(
                                operation.version(), ids.nextAttemptId(), retry, now());
                        operations.save(replacement, operation.version());
                    }
                    return DeliveryOutcome.retryableFailure(action() + "-submission-unavailable");
                }
                case AMBIGUOUS -> {
                    transition(operation, OperationState.SUBMISSION_AMBIGUOUS,
                            "submission-ambiguous", submission.evidenceReference());
                    return DeliveryOutcome.ambiguousAcknowledgement(
                            action() + "-submission-ambiguous");
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
                return DeliveryOutcome.terminalFailure(action() + "-no-effect-proven");
            } else {
                transition(operation, OperationState.MANUAL_REVIEW,
                        "ambiguous-submission-unresolved", inquiry.evidenceReference());
                return DeliveryOutcome.terminalFailure(action() + "-ambiguity-unresolved");
            }
        }

        if (operation.state() == OperationState.OBSERVING) {
            ChainPort.Observation observation = chain.observe(new ChainPort.ObservationRequest(
                    operation.operationId(), attemptId, signedAttempt.nativeIdentity(),
                    policy.finalityPolicyVersion()));
            switch (observation.classification()) {
                case ABSENT_OR_PENDING -> {
                    return DeliveryOutcome.ambiguousAcknowledgement(
                            action() + "-observation-pending");
                }
                case CONFIRMED -> operation = complete(operation, observation);
                case REVERTED, MISMATCHED, ORPHANED -> {
                    transition(operation, OperationState.MANUAL_REVIEW,
                            action() + "-observation-" + observation.classification().name()
                                    .toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                            observation.evidenceReferences().getLast());
                    return DeliveryOutcome.terminalFailure(action() + "-observation-unsafe");
                }
            }
        }

        if (operation.state() == OperationState.CHAIN_FINALITY_REACHED) {
            operation = transition(operation, OperationState.RECONCILING,
                    "blockchain-evidence-reconciled", evidence(operation, "reconciling"));
        }
        if (operation.state() == OperationState.RECONCILING) {
            operation = transition(operation, OperationState.COMPLETED,
                    action() + "-chain-slice-complete", evidence(operation, "completed"));
        }
        return operation.state() == OperationState.COMPLETED
                ? DeliveryOutcome.delivered()
                : DeliveryOutcome.ambiguousAcknowledgement(
                        action() + "-workflow-incomplete");
    }

    private TokenOperation advanceAcceptance(TokenOperation operation) {
        while (true) {
            operation = switch (operation.state()) {
                case REQUESTED -> transition(operation, OperationState.VALIDATED,
                        "accepted-" + action() + "-validated",
                        evidence(operation, "validated"));
                case VALIDATED -> transition(operation, OperationState.POLICY_PENDING,
                        "local-policy-selected", evidence(operation, "policy"));
                case POLICY_PENDING -> transition(operation, OperationState.APPROVAL_PENDING,
                        "approval-evidence-bound", evidence(operation, "approval-pending"));
                case APPROVAL_PENDING -> transition(operation, OperationState.AUTHORIZED,
                        "local-" + action() + "-authorized",
                        evidence(operation, "authorized"));
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
                action() + "-attempt-signing", evidence(operation, "signing"));
    }

    private SigningAuthorityService.Request signingRequest(
            TokenOperation operation,
            AttemptId attemptId,
            ChainPort.PreparedAttempt prepared,
            ChainPort.SigningRequirement requirement) {
        Instant issuedAt = now();
        UUID requestId = UUID.nameUUIDFromBytes(
                ((action() + "-signing-v2:") + operation.operationId() + ":" + attemptId
                        + ":" + requirement.order() + ":" + requirement.keyRole())
                        .getBytes(StandardCharsets.UTF_8));
        return new SigningAuthorityService.Request(
                new SigningRequestId(requestId),
                new SigningRequest.Correlation(
                        operation.operationId(), attemptId, Optional.empty(), Optional.empty()),
                Optional.empty(), policy.signingAction(), requirement.network(),
                operation.quantity(), requirement.signerReference(),
                prepared.destinationReference(),
                prepared.nativeActionIdentity(), prepared.lifetimeContextDigest(),
                prepared.feeLimit(), prepared.nativeConstraintDigest(), requirement.keyAlias(),
                requirement.keyRole(), requirement.mode(), requirement.algorithm(),
                prepared.signableMaterial(),
                prepared.policyVersion(), List.of(prepared.evidenceReference()), issuedAt,
                issuedAt.plus(policy.signingLifetime()));
    }

    private List<ChainPort.SigningRequirement> signingRequirements(
            ChainPort.AttemptIdentity identity,
            ChainPort.PreparedAttempt prepared) {
        if (policy.network() == SettlementNetwork.SOLANA
                && !policy.finalityPolicyVersion().equals(prepared.policyVersion())) {
            throw new IllegalStateException(
                    "prepared native attempt policy differs from active worker policy");
        }
        List<ChainPort.SigningRequirement> requirements = chain.requiredSigners(identity);
        if (requirements.isEmpty()) {
            requirements = List.of(new ChainPort.SigningRequirement(
                    0, policy.keyAlias(), policy.keyRole(), policy.network(),
                    policy.mode(), policy.algorithm(), policy.expectedSignerReference(),
                    "resolved-by-signing-registry"));
        }
        Set<Integer> orders = new HashSet<>();
        for (int index = 0; index < requirements.size(); index++) {
            ChainPort.SigningRequirement requirement = requirements.get(index);
            if (requirement.order() != index || !orders.add(requirement.order())
                    || requirement.network() != policy.network()
                    || requirement.mode() != policy.mode()
                    || requirement.algorithm() != policy.algorithm()) {
                throw new IllegalStateException(
                        "native signer requirements do not match server policy");
            }
        }
        ChainPort.SigningRequirement authority = policy.network() == SettlementNetwork.SOLANA
                && policy.operationKind() == OperationKind.BURN
                ? requirements.getLast() : requirements.getFirst();
        if (!prepared.sourceReference().equals(authority.signerReference())
                || !policy.keyAlias().equals(authority.keyAlias())
                || policy.keyRole() != authority.keyRole()
                || !policy.expectedSignerReference().equals(
                        authority.signerReference())) {
            throw new IllegalStateException("prepared signer does not match server policy");
        }
        if (policy.network() == SettlementNetwork.SOLANA
                && (requirements.size() != 2
                    || requirements.getFirst().keyRole()
                            != SigningRequest.KeyRole.FEE_PAYER
                    || requirements.getLast().keyRole()
                            == SigningRequest.KeyRole.FEE_PAYER)) {
            throw new IllegalStateException(
                    "Solana token operation requires fee payer then effect authority");
        }
        Set<Integer> attached = chain.retainedSignatureOrders(identity);
        if (!orders.containsAll(attached)) {
            throw new IllegalStateException("retained signature order is not required");
        }
        return List.copyOf(requirements);
    }

    private DeliveryOutcome signingFailure(
            TokenOperation operation, SigningAuthorityService.Result result) {
        if (result instanceof SigningAuthorityService.Ambiguous
                || result instanceof SigningAuthorityService.ApprovalRequired
                || result instanceof SigningAuthorityService.RetryableNoSignature) {
            return DeliveryOutcome.ambiguousAcknowledgement(
                    action() + "-signing-incomplete");
        }
        transition(operation, OperationState.MANUAL_REVIEW,
                action() + "-signing-rejected", evidence(operation, "signing-rejected"));
        return DeliveryOutcome.terminalFailure(action() + "-signing-rejected");
    }

    private TokenOperation complete(
            TokenOperation operation, ChainPort.Observation observation) {
        TokenOperation withFinality = operation.recordFinality(
                operation.version(), FinalityRecord.assessed(
                        FinalityType.BLOCKCHAIN, FinalityStatus.REACHED,
                        "independent-" + networkName() + "-observer",
                        observation.policyVersion(),
                        now(), observation.evidenceReferences()));
        operations.save(withFinality, operation.version());
        return transition(withFinality, OperationState.CHAIN_FINALITY_REACHED,
                "confirmed-" + action() + "-observed",
                observation.evidenceReferences().getLast());
    }

    private TokenOperation transition(
            TokenOperation operation,
            OperationState target,
            String reason,
            EvidenceRef evidence) {
        TokenOperation changed = operation.transition(
                operation.version(), target, "local-" + networkName() + "-worker", reason,
                now(), List.of(evidence));
        operations.save(changed, operation.version());
        return changed;
    }

    private Instant now() {
        return Objects.requireNonNull(clock.now(), "clock result")
                .truncatedTo(ChronoUnit.MICROS);
    }

    private String action() {
        return policy.operationKind().name().toLowerCase(java.util.Locale.ROOT);
    }

    private EvidenceRef evidence(TokenOperation operation, String kind) {
        return new EvidenceRef(
                "internal:local-" + networkName() + ":" + kind + ":"
                        + operation.operationId());
    }

    private String networkName() {
        return policy.network().name().toLowerCase(java.util.Locale.ROOT);
    }

    public record Policy(
            KeyAlias keyAlias,
            String expectedSignerReference,
            Duration signingLifetime,
            String finalityPolicyVersion,
            OperationKind operationKind,
            SigningRequest.Action signingAction,
            SigningRequest.KeyRole keyRole,
            SettlementNetwork network,
            SigningRequest.Mode mode,
            SigningRequest.Algorithm algorithm) {

        public Policy(
                KeyAlias keyAlias,
                String expectedSignerReference,
                Duration signingLifetime,
                String finalityPolicyVersion) {
            this(keyAlias, expectedSignerReference, signingLifetime,
                    finalityPolicyVersion, OperationKind.MINT,
                    SigningRequest.Action.MINT, SigningRequest.KeyRole.MINT_AUTHORITY,
                    SettlementNetwork.ETHEREUM, SigningRequest.Mode.EVM_DIGEST,
                    SigningRequest.Algorithm.SECP256K1);
        }

        public Policy(
                KeyAlias keyAlias,
                String expectedSignerReference,
                Duration signingLifetime,
                String finalityPolicyVersion,
                OperationKind operationKind,
                SigningRequest.Action signingAction,
                SigningRequest.KeyRole keyRole) {
            this(keyAlias, expectedSignerReference, signingLifetime,
                    finalityPolicyVersion, operationKind, signingAction, keyRole,
                    SettlementNetwork.ETHEREUM, SigningRequest.Mode.EVM_DIGEST,
                    SigningRequest.Algorithm.SECP256K1);
        }

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
            Objects.requireNonNull(operationKind, "operationKind");
            Objects.requireNonNull(signingAction, "signingAction");
            Objects.requireNonNull(keyRole, "keyRole");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(algorithm, "algorithm");
            if ((operationKind == OperationKind.MINT
                        && (signingAction != SigningRequest.Action.MINT
                            || (keyRole != SigningRequest.KeyRole.MINT_AUTHORITY
                                && keyRole != SigningRequest.KeyRole.FEE_PAYER)))
                    || (operationKind == OperationKind.BURN
                        && (signingAction != SigningRequest.Action.BURN
                            || keyRole != SigningRequest.KeyRole.BURN_AUTHORITY))) {
                throw new IllegalArgumentException(
                        "token operation policy action and key role do not match");
            }
            if ((network == SettlementNetwork.ETHEREUM
                        && (mode != SigningRequest.Mode.EVM_DIGEST
                            || algorithm != SigningRequest.Algorithm.SECP256K1
                            || keyRole == SigningRequest.KeyRole.FEE_PAYER))
                    || (network == SettlementNetwork.SOLANA
                        && (mode != SigningRequest.Mode.SOLANA_MESSAGE
                            || algorithm != SigningRequest.Algorithm.ED25519))) {
                throw new IllegalArgumentException(
                        "token operation policy network and signing mode do not match");
            }
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
        }
    }
}
