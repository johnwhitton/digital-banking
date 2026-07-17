package io.github.johnwhitton.digitalbanking.application;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Durable internal signing use case. It exposes no HTTP or runtime fallback. */
public final class SigningAuthorityService {

    private final SigningRequestRepository requests;
    private final SigningKeyRegistry keys;
    private final SigningAuthorizationPort authorization;
    private final SignerPort provider;
    private final SigningIdentityGenerator identities;
    private final ClockPort clock;

    public SigningAuthorityService(
            SigningRequestRepository requests,
            SigningKeyRegistry keys,
            SigningAuthorizationPort authorization,
            SignerPort provider,
            SigningIdentityGenerator identities,
            ClockPort clock) {
        this.requests = Objects.requireNonNull(requests, "requests");
        this.keys = Objects.requireNonNull(keys, "keys");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.provider = Objects.requireNonNull(provider, "provider");
        this.identities = Objects.requireNonNull(identities, "identities");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public Result sign(Request input) {
        Objects.requireNonNull(input, "input");
        SigningRequest.PayloadIdentity payload = input.payloadIdentity();
        String intentDigest = SigningRequestCanonicalizer.intent(input, payload);
        Optional<SigningRequest> existing = requests.findById(input.requestId());
        if (existing.isPresent()) {
            SigningRequest retained = existing.orElseThrow();
            if (retained.intentCanonicalizationVersion()
                            != SigningRequestCanonicalizer.VERSION
                    || !retained.intentDigest().equals(intentDigest)) {
                return new Conflict(retained, false);
            }
            verifyMaterial(retained, input.signableMaterial());
            return resume(retained, input.signableMaterial(), true);
        }

        SigningRequest.KeyContext key = keys.resolve(
                input.keyAlias(), input.keyRole(), input.algorithm(), input.network());
        if (!key.alias().equals(input.keyAlias())
                || key.role() != input.keyRole()
                || key.algorithm() != input.algorithm()
                || key.network() != input.network()) {
            throw new IllegalStateException(
                    "key registry returned mismatched identity or authority metadata");
        }
        String requestDigest = SigningRequestCanonicalizer.resolved(intentDigest, key);
        Instant createdAt = clock.now().truncatedTo(ChronoUnit.MICROS);
        SigningRequest proposed = SigningRequest.requested(
                input.requestId(), input.correlation(), input.lineage(), payload, key,
                input.authorityContext(), SigningRequestCanonicalizer.VERSION, intentDigest,
                SigningRequestCanonicalizer.VERSION, requestDigest, createdAt,
                evidence("request", input.requestId()));
        try {
            SigningRequestRepository.Acceptance accepted = requests.accept(proposed);
            return accepted.replayed()
                    ? resume(accepted.request(), input.signableMaterial(), true)
                    : resume(accepted.request(), input.signableMaterial(), false);
        } catch (SigningRequestConflictException conflict) {
            return new Conflict(conflict.existing(), false);
        }
    }

    public Result retry(
            SigningRequestId requestId,
            byte[] signableMaterial,
            EvidenceRef retryEvidence) {
        SigningRequest request = requests.findById(
                        Objects.requireNonNull(requestId, "requestId"))
                .orElseThrow(() -> new IllegalArgumentException("signing request was not found"));
        verifyMaterial(request, signableMaterial);
        Objects.requireNonNull(retryEvidence, "retryEvidence");
        if (request.status() != SigningRequest.Status.RETRYABLE_NO_SIGNATURE) {
            throw new IllegalStateException("signing request is not safe to retry");
        }
        Optional<Result> expired = expireIfInvalid(request, false);
        if (expired.isPresent()) {
            return expired.orElseThrow();
        }
        SigningRequest awaiting = request.awaitAuthorization(
                request.version(), now(), retryEvidence);
        save(request, awaiting);
        return authorize(awaiting, signableMaterial, false);
    }

    private Result resume(SigningRequest request, byte[] material, boolean replayed) {
        return switch (request.status()) {
            case REQUESTED -> validateKeyAndAuthorize(request, material, replayed);
            case AWAITING_AUTHORIZATION -> resumeAuthorization(request, material, replayed);
            case AUTHORIZED -> resumeAuthorized(request, material, replayed);
            case PROVIDER_REQUEST_PERSISTED, AMBIGUOUS -> inquire(request, replayed);
            case SIGNED -> new Signed(request, replayed);
            case DENIED -> new Denied(request, replayed);
            case RETRYABLE_NO_SIGNATURE -> new RetryableNoSignature(request, replayed);
            case EXPIRED -> new Expired(request, replayed);
            case REVOKED -> new Revoked(request, replayed);
            case MANUAL_REVIEW -> new ManualReview(request, replayed);
        };
    }

    private Result validateKeyAndAuthorize(
            SigningRequest request, byte[] material, boolean replayed) {
        Optional<Result> expired = expireIfInvalid(request, replayed);
        if (expired.isPresent()) {
            return expired.orElseThrow();
        }
        Instant now = now();
        SigningRequest.KeyContext key = request.keyContext();
        if (key.status() == SigningRequest.KeyStatus.REVOKED) {
            SigningRequest revoked = request.revoke(
                    request.version(), now(), evidence("key-revoked", request.requestId()));
            save(request, revoked);
            return new Revoked(revoked, replayed);
        }
        if (key.status() != SigningRequest.KeyStatus.ACTIVE
                || key.keyVersion().isEmpty()
                || !key.allowedRoles().contains(key.role())
                || !key.allowedAlgorithms().contains(key.algorithm())
                || !key.allowedNetworks().contains(key.network())
                || !roleMatchesAction(key.role(), request.authorityContext().action())) {
            SigningRequest denied = request.deny(
                    request.version(), "key-metadata-mismatch", now(),
                    evidence("key-denied", request.requestId()));
            save(request, denied);
            return new Denied(denied, replayed);
        }
        SigningRequest awaiting = request.awaitAuthorization(
                request.version(), now(), evidence("authorization-pending", request.requestId()));
        save(request, awaiting);
        return authorize(awaiting, material, replayed);
    }

    private Result resumeAuthorization(
            SigningRequest request, byte[] material, boolean replayed) {
        return expireIfInvalid(request, replayed)
                .orElseGet(() -> authorize(request, material, replayed));
    }

    private Result resumeAuthorized(
            SigningRequest request, byte[] material, boolean replayed) {
        return expireIfInvalid(request, replayed)
                .orElseGet(() -> invokeNewAttempt(
                        request, material,
                        evidence("provider-request", request.requestId()), replayed));
    }

    private Optional<Result> expireIfInvalid(SigningRequest request, boolean replayed) {
        Instant current = now();
        SigningRequest.KeyContext key = request.keyContext();
        if (current.isBefore(request.authorityContext().expiresAt())
                && !current.isBefore(key.validFrom())
                && key.expiresAt().filter(expiry -> !current.isBefore(expiry)).isEmpty()) {
            return Optional.empty();
        }
        SigningRequest expired = request.expire(
                request.version(), current, evidence("expired", request.requestId()));
        save(request, expired);
        return Optional.of(new Expired(expired, replayed));
    }

    private Result authorize(SigningRequest request, byte[] material, boolean replayed) {
        if (request.authorityContext().approvalEvidence().isEmpty()) {
            return new ApprovalRequired(request, replayed);
        }
        return switch (authorization.evaluate(request)) {
            case SigningAuthorizationPort.Authorized approved -> {
                SigningRequest authorized = request.authorize(
                        request.version(), now(), approved.evidence());
                save(request, authorized);
                yield invokeNewAttempt(
                        authorized, material,
                        evidence("provider-request", request.requestId()), replayed);
            }
            case SigningAuthorizationPort.AwaitingApproval pending ->
                    new ApprovalRequired(request, replayed);
            case SigningAuthorizationPort.Denied denied -> {
                SigningRequest rejected = request.deny(
                        request.version(), denied.safeCode(), now(), denied.evidence());
                save(request, rejected);
                yield new Denied(rejected, replayed);
            }
        };
    }

    private Result invokeNewAttempt(
            SigningRequest request,
            byte[] material,
            EvidenceRef attemptEvidence,
            boolean replayed) {
        SigningAttemptId attemptId = identities.nextAttemptId();
        SigningRequest persisted = request.persistProviderRequest(
                request.version(), attemptId, identities.nextProviderRequestId(),
                now(), attemptEvidence);
        save(request, persisted);

        SignerPort.ProviderContext context = new SignerPort.ProviderContext(
                persisted, attemptId, persisted.attempts().getLast().providerRequestId());
        SignerPort.ProviderResult result = switch (persisted.payloadIdentity().mode()) {
            case EVM_DIGEST -> provider.signEvmDigest(
                    new SignerPort.EvmDigestCommand(context, material));
            case SOLANA_MESSAGE -> provider.signSolanaMessage(
                    new SignerPort.SolanaMessageCommand(context, material));
        };
        return recordProviderResult(persisted, attemptId, result, replayed);
    }

    private Result inquire(SigningRequest request, boolean replayed) {
        SigningRequest.Attempt attempt = request.attempts().getLast();
        SignerPort.ProviderContext context = new SignerPort.ProviderContext(
                request, attempt.attemptId(), attempt.providerRequestId());
        return recordProviderResult(
                request, attempt.attemptId(), provider.inquire(new SignerPort.Inquiry(context)),
                replayed);
    }

    private Result recordProviderResult(
            SigningRequest request,
            SigningAttemptId attemptId,
            SignerPort.ProviderResult providerResult,
            boolean replayed) {
        SigningRequest.ProviderOutcome outcome = switch (providerResult) {
            case SignerPort.Signed signed -> {
                byte[] signature = signed.signature();
                yield SigningRequest.ProviderOutcome.signed(
                        new SigningRequest.SignatureEvidence(
                                SigningRequestCanonicalizer.sha256(signature), signature.length,
                                signed.encoding(), signed.evidence(), signed.origin()));
            }
            case SignerPort.Denied denied -> SigningRequest.ProviderOutcome.denied(
                    denied.safeCode(), denied.evidence());
            case SignerPort.RetryableNoSignature retryable ->
                    SigningRequest.ProviderOutcome.retryableNoSignature(
                            retryable.safeCode(), retryable.evidence());
            case SignerPort.Ambiguous ambiguous ->
                    SigningRequest.ProviderOutcome.ambiguous(ambiguous.evidence());
            case SignerPort.Conflict conflict -> SigningRequest.ProviderOutcome.manualReview(
                    conflict.safeCode(), conflict.evidence());
        };
        SigningRequest changed = request.recordProviderOutcome(
                request.version(), attemptId, outcome, now());
        save(request, changed);
        return switch (changed.status()) {
            case SIGNED -> new Signed(changed, replayed);
            case DENIED -> new Denied(changed, replayed);
            case RETRYABLE_NO_SIGNATURE -> new RetryableNoSignature(changed, replayed);
            case AMBIGUOUS -> new Ambiguous(changed, replayed);
            case MANUAL_REVIEW -> new ManualReview(changed, replayed);
            default -> throw new IllegalStateException("provider result produced invalid status");
        };
    }

    private void save(SigningRequest before, SigningRequest changed) {
        requests.save(changed, before.version());
    }

    private Instant now() {
        return clock.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static EvidenceRef evidence(String kind, SigningRequestId requestId) {
        return new EvidenceRef("internal:signing:" + kind + ":" + requestId.value());
    }

    private static boolean roleMatchesAction(
            SigningRequest.KeyRole role, SigningRequest.Action action) {
        return switch (action) {
            case MINT -> role == SigningRequest.KeyRole.MINT_AUTHORITY;
            case TRANSFER -> role == SigningRequest.KeyRole.TRANSFER_AUTHORITY;
            case BURN -> role == SigningRequest.KeyRole.BURN_AUTHORITY;
        };
    }

    private static void verifyMaterial(SigningRequest request, byte[] material) {
        byte[] copy = Objects.requireNonNull(material, "signableMaterial").clone();
        if (copy.length != request.payloadIdentity().length()
                || !SigningRequestCanonicalizer.sha256(copy)
                        .equals(request.payloadIdentity().sha256())) {
            throw new IllegalArgumentException(
                    "signable material does not match the durable signing request");
        }
    }

    public record Request(
            SigningRequestId requestId,
            SigningRequest.Correlation correlation,
            Optional<SigningRequest.Lineage> lineage,
            SigningRequest.Action action,
            SettlementNetwork network,
            TokenQuantity quantity,
            String sourceReference,
            String destinationReference,
            String nativeActionIdentity,
            String lifetimeContextDigest,
            String feeLimit,
            String nativeConstraintDigest,
            KeyAlias keyAlias,
            SigningRequest.KeyRole keyRole,
            SigningRequest.Mode mode,
            SigningRequest.Algorithm algorithm,
            byte[] signableMaterial,
            String policyVersion,
            List<EvidenceRef> approvalEvidence,
            Instant issuedAt,
            Instant expiresAt) {

        public Request {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(correlation, "correlation");
            lineage = Objects.requireNonNull(lineage, "lineage");
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(quantity, "quantity");
            requireText(sourceReference, "sourceReference");
            requireText(destinationReference, "destinationReference");
            requireText(nativeActionIdentity, "nativeActionIdentity");
            requireDigest(lifetimeContextDigest, "lifetimeContextDigest");
            requireText(feeLimit, "feeLimit");
            requireDigest(nativeConstraintDigest, "nativeConstraintDigest");
            Objects.requireNonNull(keyAlias, "keyAlias");
            Objects.requireNonNull(keyRole, "keyRole");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(algorithm, "algorithm");
            signableMaterial = Objects.requireNonNull(
                    signableMaterial, "signableMaterial").clone();
            requireText(policyVersion, "policyVersion");
            approvalEvidence = List.copyOf(approvalEvidence);
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)
                    || issuedAt.getNano() % 1_000 != 0
                    || expiresAt.getNano() % 1_000 != 0) {
                throw new IllegalArgumentException(
                        "signing request times must be ordered UTC microsecond instants");
            }
            SigningRequest.PayloadEncoding encoding = switch (mode) {
                case EVM_DIGEST -> SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST;
                case SOLANA_MESSAGE ->
                        SigningRequest.PayloadEncoding.SOLANA_SERIALIZED_MESSAGE;
            };
            new SigningRequest.PayloadIdentity(
                    mode, algorithm, SigningRequestCanonicalizer.sha256(signableMaterial),
                    signableMaterial.length, encoding);
        }

        @Override
        public byte[] signableMaterial() {
            return signableMaterial.clone();
        }

        SigningRequest.PayloadIdentity payloadIdentity() {
            SigningRequest.PayloadEncoding encoding = switch (mode) {
                case EVM_DIGEST -> SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST;
                case SOLANA_MESSAGE ->
                        SigningRequest.PayloadEncoding.SOLANA_SERIALIZED_MESSAGE;
            };
            return new SigningRequest.PayloadIdentity(
                    mode, algorithm, SigningRequestCanonicalizer.sha256(signableMaterial),
                    signableMaterial.length, encoding);
        }

        SigningRequest.AuthorityContext authorityContext() {
            return new SigningRequest.AuthorityContext(
                    action, network, quantity, sourceReference, destinationReference,
                    nativeActionIdentity, lifetimeContextDigest, feeLimit,
                    nativeConstraintDigest, policyVersion, approvalEvidence, issuedAt, expiresAt);
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 256) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
        }

        private static void requireDigest(String value, String field) {
            if (value == null || !value.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
            }
        }

        @Override
        public String toString() {
            return "SigningAuthorityRequest[requestId=" + requestId
                    + ", context=[BOUND_AND_REDACTED], material=[REDACTED]]";
        }
    }

    public sealed interface Result permits
            Signed, ApprovalRequired, Denied, RetryableNoSignature, Ambiguous,
            Expired, Revoked, ManualReview, Conflict {

        SigningRequest request();

        boolean replayed();
    }

    public record Signed(SigningRequest request, boolean replayed) implements Result {
        public Signed { Objects.requireNonNull(request, "request"); }
    }

    public record ApprovalRequired(
            SigningRequest request, boolean replayed) implements Result {
        public ApprovalRequired { Objects.requireNonNull(request, "request"); }
    }

    public record Denied(SigningRequest request, boolean replayed) implements Result {
        public Denied { Objects.requireNonNull(request, "request"); }
    }

    public record RetryableNoSignature(
            SigningRequest request, boolean replayed) implements Result {
        public RetryableNoSignature { Objects.requireNonNull(request, "request"); }
    }

    public record Ambiguous(SigningRequest request, boolean replayed) implements Result {
        public Ambiguous { Objects.requireNonNull(request, "request"); }
    }

    public record Expired(SigningRequest request, boolean replayed) implements Result {
        public Expired { Objects.requireNonNull(request, "request"); }
    }

    public record Revoked(SigningRequest request, boolean replayed) implements Result {
        public Revoked { Objects.requireNonNull(request, "request"); }
    }

    public record ManualReview(SigningRequest request, boolean replayed) implements Result {
        public ManualReview { Objects.requireNonNull(request, "request"); }
    }

    public record Conflict(SigningRequest request, boolean replayed) implements Result {
        public Conflict { Objects.requireNonNull(request, "request"); }
    }
}
