package io.github.johnwhitton.digitalbanking.domain.signing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;

/** Immutable signing authority request with append-only provider-attempt evidence. */
public final class SigningRequest {

    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SAFE_CODE = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");

    private final SigningRequestId requestId;
    private final Correlation correlation;
    private final Optional<Lineage> lineage;
    private final PayloadIdentity payloadIdentity;
    private final KeyContext keyContext;
    private final AuthorityContext authorityContext;
    private final int intentCanonicalizationVersion;
    private final String intentDigest;
    private final int requestCanonicalizationVersion;
    private final String requestDigest;
    private final Status status;
    private final long version;
    private final Instant createdAt;
    private final List<Attempt> attempts;
    private final List<Transition> transitions;

    private SigningRequest(
            SigningRequestId requestId,
            Correlation correlation,
            Optional<Lineage> lineage,
            PayloadIdentity payloadIdentity,
            KeyContext keyContext,
            AuthorityContext authorityContext,
            int intentCanonicalizationVersion,
            String intentDigest,
            int requestCanonicalizationVersion,
            String requestDigest,
            Status status,
            long version,
            Instant createdAt,
            List<Attempt> attempts,
            List<Transition> transitions) {
        this.requestId = Objects.requireNonNull(requestId, "requestId");
        this.correlation = Objects.requireNonNull(correlation, "correlation");
        this.lineage = Objects.requireNonNull(lineage, "lineage");
        this.payloadIdentity = Objects.requireNonNull(payloadIdentity, "payloadIdentity");
        this.keyContext = Objects.requireNonNull(keyContext, "keyContext");
        this.authorityContext = Objects.requireNonNull(authorityContext, "authorityContext");
        if (intentCanonicalizationVersion < 1 || requestCanonicalizationVersion < 1) {
            throw new IllegalArgumentException("canonicalization versions must be positive");
        }
        this.intentCanonicalizationVersion = intentCanonicalizationVersion;
        this.intentDigest = requireSha256(intentDigest, "intentDigest");
        this.requestCanonicalizationVersion = requestCanonicalizationVersion;
        this.requestDigest = requireSha256(requestDigest, "requestDigest");
        this.status = Objects.requireNonNull(status, "status");
        if (version < 0) {
            throw new IllegalArgumentException("signing request version must be non-negative");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.attempts = List.copyOf(attempts);
        this.transitions = List.copyOf(transitions);
        validateShape();
    }

    public static SigningRequest requested(
            SigningRequestId requestId,
            Correlation correlation,
            Optional<Lineage> lineage,
            PayloadIdentity payloadIdentity,
            KeyContext keyContext,
            AuthorityContext authorityContext,
            int intentCanonicalizationVersion,
            String intentDigest,
            int requestCanonicalizationVersion,
            String requestDigest,
            Instant createdAt,
            EvidenceRef evidence) {
        Transition initial = new Transition(
                0, Optional.empty(), Status.REQUESTED, "REQUESTED", createdAt, evidence);
        return new SigningRequest(
                requestId, correlation, lineage, payloadIdentity, keyContext, authorityContext,
                intentCanonicalizationVersion, intentDigest, requestCanonicalizationVersion,
                requestDigest, Status.REQUESTED, 0, createdAt, List.of(), List.of(initial));
    }

    public static SigningRequest rehydrate(
            SigningRequestId requestId,
            Correlation correlation,
            Optional<Lineage> lineage,
            PayloadIdentity payloadIdentity,
            KeyContext keyContext,
            AuthorityContext authorityContext,
            int intentCanonicalizationVersion,
            String intentDigest,
            int requestCanonicalizationVersion,
            String requestDigest,
            Status status,
            long version,
            Instant createdAt,
            List<Attempt> attempts,
            List<Transition> transitions) {
        return new SigningRequest(
                requestId, correlation, lineage, payloadIdentity, keyContext, authorityContext,
                intentCanonicalizationVersion, intentDigest, requestCanonicalizationVersion,
                requestDigest, status, version, createdAt, attempts, transitions);
    }

    public SigningRequest awaitAuthorization(
            long expectedVersion, Instant occurredAt, EvidenceRef evidence) {
        requireState(expectedVersion, Set.of(Status.REQUESTED, Status.RETRYABLE_NO_SIGNATURE));
        return changed(
                Status.AWAITING_AUTHORIZATION, "AWAITING_AUTHORIZATION", occurredAt,
                evidence, attempts);
    }

    public SigningRequest authorize(
            long expectedVersion, Instant occurredAt, EvidenceRef evidence) {
        requireState(expectedVersion, Set.of(Status.AWAITING_AUTHORIZATION));
        return changed(Status.AUTHORIZED, "AUTHORIZED", occurredAt, evidence, attempts);
    }

    public SigningRequest deny(
            long expectedVersion, String safeCode, Instant occurredAt, EvidenceRef evidence) {
        requireState(expectedVersion, Set.of(
                Status.REQUESTED, Status.AWAITING_AUTHORIZATION, Status.AUTHORIZED));
        requireSafeCode(safeCode);
        return changed(Status.DENIED, safeCode, occurredAt, evidence, attempts);
    }

    public SigningRequest expire(
            long expectedVersion, Instant occurredAt, EvidenceRef evidence) {
        requireState(expectedVersion, Set.of(
                Status.REQUESTED, Status.AWAITING_AUTHORIZATION, Status.AUTHORIZED,
                Status.RETRYABLE_NO_SIGNATURE));
        return changed(Status.EXPIRED, "EXPIRED", occurredAt, evidence, attempts);
    }

    public SigningRequest revoke(
            long expectedVersion, Instant occurredAt, EvidenceRef evidence) {
        requireState(expectedVersion, Set.of(
                Status.REQUESTED, Status.AWAITING_AUTHORIZATION, Status.AUTHORIZED));
        return changed(Status.REVOKED, "REVOKED", occurredAt, evidence, attempts);
    }

    public SigningRequest manualReview(
            long expectedVersion, String safeCode, Instant occurredAt, EvidenceRef evidence) {
        requireVersion(expectedVersion);
        if (status == Status.SIGNED) {
            throw new IllegalStateException("signed request cannot enter manual review");
        }
        requireSafeCode(safeCode);
        return changed(Status.MANUAL_REVIEW, safeCode, occurredAt, evidence, attempts);
    }

    public SigningRequest persistProviderRequest(
            long expectedVersion,
            SigningAttemptId attemptId,
            ProviderRequestId providerRequestId,
            Instant occurredAt,
            EvidenceRef evidence) {
        requireState(expectedVersion, Set.of(Status.AUTHORIZED));
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(providerRequestId, "providerRequestId");
        if (attempts.stream().anyMatch(value -> value.attemptId().equals(attemptId)
                || value.providerRequestId().equals(providerRequestId))) {
            throw new IllegalArgumentException("signing attempt identity is already present");
        }
        requireOrderedTime(occurredAt);
        Optional<SigningAttemptId> predecessor = attempts.isEmpty()
                ? Optional.empty() : Optional.of(attempts.getLast().attemptId());
        Attempt attempt = Attempt.persisted(
                attemptId, predecessor, providerRequestId, occurredAt, evidence);
        return changed(
                Status.PROVIDER_REQUEST_PERSISTED, "PROVIDER_REQUEST_PERSISTED",
                occurredAt, evidence, appended(attempts, attempt));
    }

    public SigningRequest recordProviderOutcome(
            long expectedVersion,
            SigningAttemptId attemptId,
            ProviderOutcome outcome,
            Instant occurredAt) {
        requireState(expectedVersion, Set.of(
                Status.PROVIDER_REQUEST_PERSISTED, Status.AMBIGUOUS));
        Objects.requireNonNull(attemptId, "attemptId");
        Objects.requireNonNull(outcome, "outcome");
        if (attempts.isEmpty() || !attempts.getLast().attemptId().equals(attemptId)) {
            throw new IllegalStateException("provider outcome does not match the active attempt");
        }
        requireOrderedTime(occurredAt);
        List<Attempt> changedAttempts = new ArrayList<>(attempts);
        changedAttempts.set(changedAttempts.size() - 1,
                attempts.getLast().withOutcome(outcome, occurredAt));
        return changed(
                outcome.status(), outcome.reason(), occurredAt, outcome.evidence(),
                changedAttempts);
    }

    private SigningRequest changed(
            Status next,
            String reason,
            Instant occurredAt,
            EvidenceRef evidence,
            List<Attempt> changedAttempts) {
        requireOrderedTime(occurredAt);
        Transition transition = new Transition(
                version + 1, Optional.of(status), next, reason, occurredAt, evidence);
        return new SigningRequest(
                requestId, correlation, lineage, payloadIdentity, keyContext, authorityContext,
                intentCanonicalizationVersion, intentDigest, requestCanonicalizationVersion,
                requestDigest, next, version + 1, createdAt, changedAttempts,
                appended(transitions, transition));
    }

    private void requireState(long expectedVersion, Set<Status> allowed) {
        requireVersion(expectedVersion);
        if (!allowed.contains(status)) {
            throw new IllegalStateException("signing request transition is not allowed");
        }
    }

    private void requireVersion(long expectedVersion) {
        if (version != expectedVersion) {
            throw new IllegalStateException("signing request version conflict");
        }
    }

    private void requireOrderedTime(Instant occurredAt) {
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (occurredAt.isBefore(transitions.getLast().occurredAt())) {
            throw new IllegalArgumentException("signing request time cannot move backward");
        }
    }

    private void validateShape() {
        if (payloadIdentity.algorithm() != keyContext.algorithm()
                || authorityContext.network() != keyContext.network()) {
            throw new IllegalArgumentException("payload, authority, and key context do not match");
        }
        if (transitions.size() != version + 1
                || transitions.getFirst().version() != 0
                || transitions.getFirst().from().isPresent()
                || transitions.getFirst().to() != Status.REQUESTED
                || transitions.getLast().version() != version
                || transitions.getLast().to() != status) {
            throw new IllegalArgumentException("signing transition history is invalid");
        }
        Instant last = createdAt;
        for (int index = 0; index < transitions.size(); index++) {
            Transition transition = transitions.get(index);
            if (transition.version() != index || transition.occurredAt().isBefore(last)) {
                throw new IllegalArgumentException("signing transition order is invalid");
            }
            if (index > 0 && transition.from().orElseThrow() != transitions.get(index - 1).to()) {
                throw new IllegalArgumentException("signing transition lineage is invalid");
            }
            last = transition.occurredAt();
        }
        Set<SigningAttemptId> attemptIds = new HashSet<>();
        Set<ProviderRequestId> providerIds = new HashSet<>();
        for (int index = 0; index < attempts.size(); index++) {
            Attempt attempt = attempts.get(index);
            Optional<SigningAttemptId> expected = index == 0
                    ? Optional.empty() : Optional.of(attempts.get(index - 1).attemptId());
            if (!attempt.predecessor().equals(expected)
                    || !attemptIds.add(attempt.attemptId())
                    || !providerIds.add(attempt.providerRequestId())) {
                throw new IllegalArgumentException("signing attempt lineage is invalid");
            }
        }
        if (status == Status.PROVIDER_REQUEST_PERSISTED && attempts.isEmpty()) {
            throw new IllegalArgumentException("provider state requires an attempt");
        }
    }

    public SigningRequestId requestId() {
        return requestId;
    }

    public Correlation correlation() {
        return correlation;
    }

    public Optional<Lineage> lineage() {
        return lineage;
    }

    public PayloadIdentity payloadIdentity() {
        return payloadIdentity;
    }

    public KeyContext keyContext() {
        return keyContext;
    }

    public AuthorityContext authorityContext() {
        return authorityContext;
    }

    public int intentCanonicalizationVersion() {
        return intentCanonicalizationVersion;
    }

    public String intentDigest() {
        return intentDigest;
    }

    public int requestCanonicalizationVersion() {
        return requestCanonicalizationVersion;
    }

    public String requestDigest() {
        return requestDigest;
    }

    public Status status() {
        return status;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public List<Attempt> attempts() {
        return attempts;
    }

    public List<Transition> transitions() {
        return transitions;
    }

    @Override
    public String toString() {
        return "SigningRequest[requestId=" + requestId
                + ", status=" + status
                + ", version=" + version
                + ", context=[BOUND_AND_REDACTED]"
                + ", attempts=" + attempts.size() + "]";
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }

    private static String requireSafeCode(String value) {
        if (value == null || !SAFE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException("safe outcome code is invalid");
        }
        return value;
    }

    private static <T> List<T> appended(List<T> values, T value) {
        List<T> changed = new ArrayList<>(values);
        changed.add(Objects.requireNonNull(value, "value"));
        return List.copyOf(changed);
    }

    public record Correlation(
            OperationId operationId,
            AttemptId operationAttemptId,
            Optional<TransferId> transferId,
            Optional<TransferEffect.Id> effectId) {

        public Correlation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(operationAttemptId, "operationAttemptId");
            transferId = Objects.requireNonNull(transferId, "transferId");
            effectId = Objects.requireNonNull(effectId, "effectId");
            if (transferId.isPresent() != effectId.isPresent()) {
                throw new IllegalArgumentException(
                        "transfer and effect correlation must be present together");
            }
        }
    }

    public record Lineage(
            SigningRequestId requestId,
            SigningAttemptId attemptId,
            EvidenceRef authorizationEvidence) {

        public Lineage {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(authorizationEvidence, "authorizationEvidence");
        }
    }

    public record PayloadIdentity(
            Mode mode,
            Algorithm algorithm,
            String sha256,
            int length,
            PayloadEncoding encoding) {

        public PayloadIdentity {
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(algorithm, "algorithm");
            requireSha256(sha256, "payload sha256");
            Objects.requireNonNull(encoding, "encoding");
            switch (mode) {
                case EVM_DIGEST -> {
                    if (algorithm != Algorithm.SECP256K1 || length != 32
                            || encoding != PayloadEncoding.RAW_32_BYTE_DIGEST) {
                        throw new IllegalArgumentException(
                                "EVM mode requires a 32-byte secp256k1 digest");
                    }
                }
                case SOLANA_MESSAGE -> {
                    if (algorithm != Algorithm.ED25519 || length < 1 || length > 65_536
                            || encoding != PayloadEncoding.SOLANA_SERIALIZED_MESSAGE) {
                        throw new IllegalArgumentException(
                                "Solana mode requires bounded Ed25519 message bytes");
                    }
                }
            }
        }
    }

    public record KeyContext(
            KeyAlias alias,
            String registryVersion,
            Optional<String> keyVersion,
            KeyRole role,
            Algorithm algorithm,
            SettlementNetwork network,
            KeyStatus status,
            Set<KeyRole> allowedRoles,
            Set<Algorithm> allowedAlgorithms,
            Set<SettlementNetwork> allowedNetworks,
            Instant validFrom,
            Optional<Instant> expiresAt) {

        public KeyContext {
            Objects.requireNonNull(alias, "alias");
            registryVersion = requireText(registryVersion, "registryVersion");
            keyVersion = Objects.requireNonNull(keyVersion, "keyVersion")
                    .map(value -> requireText(value, "keyVersion"));
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(status, "status");
            allowedRoles = Set.copyOf(allowedRoles);
            allowedAlgorithms = Set.copyOf(allowedAlgorithms);
            allowedNetworks = Set.copyOf(allowedNetworks);
            Objects.requireNonNull(validFrom, "validFrom");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (expiresAt.filter(value -> !value.isAfter(validFrom)).isPresent()) {
                throw new IllegalArgumentException("key expiry must follow validity start");
            }
            if (status == KeyStatus.NOT_FOUND) {
                if (keyVersion.isPresent() || !allowedRoles.isEmpty()
                        || !allowedAlgorithms.isEmpty() || !allowedNetworks.isEmpty()) {
                    throw new IllegalArgumentException("unknown key cannot expose metadata");
                }
            } else if (keyVersion.isEmpty()) {
                throw new IllegalArgumentException("resolved key requires a version");
            }
        }
    }

    public record AuthorityContext(
            Action action,
            SettlementNetwork network,
            TokenQuantity quantity,
            String sourceReference,
            String destinationReference,
            String nativeActionIdentity,
            String lifetimeContextDigest,
            String feeLimit,
            String nativeConstraintDigest,
            String policyVersion,
            List<EvidenceRef> approvalEvidence,
            Instant issuedAt,
            Instant expiresAt) {

        public AuthorityContext {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(quantity, "quantity");
            sourceReference = requireText(sourceReference, "sourceReference");
            destinationReference = requireText(destinationReference, "destinationReference");
            nativeActionIdentity = requireText(nativeActionIdentity, "nativeActionIdentity");
            lifetimeContextDigest = requireSha256(
                    lifetimeContextDigest, "lifetimeContextDigest");
            feeLimit = requireText(feeLimit, "feeLimit");
            nativeConstraintDigest = requireSha256(
                    nativeConstraintDigest, "nativeConstraintDigest");
            policyVersion = requireText(policyVersion, "policyVersion");
            approvalEvidence = List.copyOf(approvalEvidence);
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("request expiry must follow issuance");
            }
        }
    }

    public record SignatureEvidence(
            String sha256,
            int length,
            String encoding,
            EvidenceRef evidence,
            EvidenceOrigin origin) {

        public SignatureEvidence {
            requireSha256(sha256, "signature sha256");
            if (length < 1 || length > 65_536) {
                throw new IllegalArgumentException("signature length is invalid");
            }
            encoding = requireText(encoding, "signature encoding");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(origin, "origin");
        }
    }

    public record ProviderOutcome(
            Status status,
            Optional<SignatureEvidence> signatureEvidence,
            Optional<String> safeFailureCode,
            EvidenceRef evidence) {

        public ProviderOutcome {
            if (!Set.of(Status.SIGNED, Status.DENIED, Status.RETRYABLE_NO_SIGNATURE,
                    Status.AMBIGUOUS, Status.MANUAL_REVIEW).contains(status)) {
                throw new IllegalArgumentException("provider outcome status is invalid");
            }
            signatureEvidence = Objects.requireNonNull(
                    signatureEvidence, "signatureEvidence");
            safeFailureCode = Objects.requireNonNull(
                    safeFailureCode, "safeFailureCode").map(SigningRequest::requireSafeCode);
            Objects.requireNonNull(evidence, "evidence");
            if ((status == Status.SIGNED) != signatureEvidence.isPresent()) {
                throw new IllegalArgumentException("only a signed outcome has signature evidence");
            }
            boolean codeRequired = status == Status.DENIED
                    || status == Status.RETRYABLE_NO_SIGNATURE
                    || status == Status.MANUAL_REVIEW;
            if (codeRequired != safeFailureCode.isPresent()) {
                throw new IllegalArgumentException("provider failure code does not match outcome");
            }
        }

        public static ProviderOutcome signed(SignatureEvidence signature) {
            return new ProviderOutcome(
                    Status.SIGNED, Optional.of(signature), Optional.empty(), signature.evidence());
        }

        public static ProviderOutcome denied(String safeCode, EvidenceRef evidence) {
            return new ProviderOutcome(
                    Status.DENIED, Optional.empty(), Optional.of(safeCode), evidence);
        }

        public static ProviderOutcome retryableNoSignature(
                String safeCode, EvidenceRef evidence) {
            return new ProviderOutcome(
                    Status.RETRYABLE_NO_SIGNATURE, Optional.empty(),
                    Optional.of(safeCode), evidence);
        }

        public static ProviderOutcome ambiguous(EvidenceRef evidence) {
            return new ProviderOutcome(
                    Status.AMBIGUOUS, Optional.empty(), Optional.empty(), evidence);
        }

        public static ProviderOutcome manualReview(String safeCode, EvidenceRef evidence) {
            return new ProviderOutcome(
                    Status.MANUAL_REVIEW, Optional.empty(), Optional.of(safeCode), evidence);
        }

        private String reason() {
            return safeFailureCode.orElse(status.name());
        }
    }

    public record Attempt(
            SigningAttemptId attemptId,
            Optional<SigningAttemptId> predecessor,
            ProviderRequestId providerRequestId,
            Status status,
            Instant createdAt,
            Instant updatedAt,
            List<EvidenceRef> evidence,
            Optional<SignatureEvidence> signatureEvidence,
            Optional<String> safeFailureCode) {

        public Attempt {
            Objects.requireNonNull(attemptId, "attemptId");
            predecessor = Objects.requireNonNull(predecessor, "predecessor");
            Objects.requireNonNull(providerRequestId, "providerRequestId");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(updatedAt, "updatedAt");
            evidence = List.copyOf(evidence);
            signatureEvidence = Objects.requireNonNull(signatureEvidence, "signatureEvidence");
            safeFailureCode = Objects.requireNonNull(
                    safeFailureCode, "safeFailureCode").map(SigningRequest::requireSafeCode);
            if (updatedAt.isBefore(createdAt) || evidence.isEmpty()) {
                throw new IllegalArgumentException("signing attempt history is invalid");
            }
            if ((status == Status.SIGNED) != signatureEvidence.isPresent()) {
                throw new IllegalArgumentException("attempt signature evidence is invalid");
            }
        }

        static Attempt persisted(
                SigningAttemptId attemptId,
                Optional<SigningAttemptId> predecessor,
                ProviderRequestId providerRequestId,
                Instant occurredAt,
                EvidenceRef evidence) {
            return new Attempt(
                    attemptId, predecessor, providerRequestId,
                    Status.PROVIDER_REQUEST_PERSISTED, occurredAt, occurredAt,
                    List.of(evidence), Optional.empty(), Optional.empty());
        }

        Attempt withOutcome(ProviderOutcome outcome, Instant occurredAt) {
            if (occurredAt.isBefore(updatedAt)) {
                throw new IllegalArgumentException("provider outcome cannot move backward");
            }
            return new Attempt(
                    attemptId, predecessor, providerRequestId, outcome.status(), createdAt,
                    occurredAt, appended(evidence, outcome.evidence()),
                    outcome.signatureEvidence(), outcome.safeFailureCode());
        }
    }

    public record Transition(
            long version,
            Optional<Status> from,
            Status to,
            String reason,
            Instant occurredAt,
            EvidenceRef evidence) {

        public Transition {
            if (version < 0) {
                throw new IllegalArgumentException("transition version is invalid");
            }
            from = Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            reason = reason != null && SAFE_CODE.matcher(reason.toLowerCase(java.util.Locale.ROOT)
                    .replace('_', '-')).matches() ? reason : requireText(reason, "reason");
            Objects.requireNonNull(occurredAt, "occurredAt");
            Objects.requireNonNull(evidence, "evidence");
        }
    }

    public enum Action {
        MINT,
        TRANSFER,
        BURN
    }

    public enum KeyRole {
        FEE_PAYER,
        MINT_AUTHORITY,
        TRANSFER_AUTHORITY,
        BURN_AUTHORITY
    }

    public enum Algorithm {
        SECP256K1,
        ED25519
    }

    public enum Mode {
        EVM_DIGEST,
        SOLANA_MESSAGE
    }

    public enum PayloadEncoding {
        RAW_32_BYTE_DIGEST,
        SOLANA_SERIALIZED_MESSAGE
    }

    public enum KeyStatus {
        ACTIVE,
        DISABLED,
        REVOKED,
        NOT_FOUND
    }

    public enum EvidenceOrigin {
        PROVIDER,
        SYNTHETIC_TEST
    }

    public enum Status {
        REQUESTED,
        AWAITING_AUTHORIZATION,
        AUTHORIZED,
        PROVIDER_REQUEST_PERSISTED,
        SIGNED,
        DENIED,
        RETRYABLE_NO_SIGNATURE,
        AMBIGUOUS,
        EXPIRED,
        REVOKED,
        MANUAL_REVIEW
    }
}
