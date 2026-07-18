package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

public interface ChainPort {

    ChainCapabilities capabilities(String routeVersion);

    PreparedAttempt prepare(
            UUID deliveryId, TokenOperation operation, OperationAttempt attempt);

    Optional<SignedAttempt> findSignedAttempt(AttemptIdentity attemptIdentity);

    SignedAttempt attachSignature(
            AttemptIdentity attemptIdentity, AuthorizedSignature signature);

    SubmissionResult submitOnce(SignedAttempt signedAttempt);

    InquiryResult inquire(AttemptIdentity attemptIdentity);

    Observation observe(ObservationRequest request);

    record ChainCapabilities(
            boolean supportsMint,
            boolean supportsBurn,
            boolean supportsIndependentInquiry) {
    }

    record PreparedAttempt(
            byte[] signableMaterial,
            String digestReference,
            String sourceReference,
            String destinationReference,
            String nativeActionIdentity,
            String lifetimeContextDigest,
            String feeLimit,
            String nativeConstraintDigest,
            String policyVersion,
            EvidenceRef evidenceReference) {

        public PreparedAttempt {
            signableMaterial = Objects.requireNonNull(
                    signableMaterial, "signableMaterial").clone();
            if (signableMaterial.length == 0) {
                throw new IllegalArgumentException("signable material is required");
            }
            digestReference = requireText(digestReference, "digestReference");
            sourceReference = requireText(sourceReference, "sourceReference");
            destinationReference = requireText(destinationReference, "destinationReference");
            nativeActionIdentity = requireText(nativeActionIdentity, "nativeActionIdentity");
            lifetimeContextDigest = requireDigest(
                    lifetimeContextDigest, "lifetimeContextDigest");
            feeLimit = requireText(feeLimit, "feeLimit");
            nativeConstraintDigest = requireDigest(
                    nativeConstraintDigest, "nativeConstraintDigest");
            policyVersion = requireText(policyVersion, "policyVersion");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }

        @Override
        public byte[] signableMaterial() {
            return signableMaterial.clone();
        }
    }

    record SignedAttempt(
            OperationId operationId,
            AttemptId attemptId,
            NativeIdentity nativeIdentity,
            EvidenceRef evidenceReference) {

        public SignedAttempt {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(nativeIdentity, "nativeIdentity");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }
    }

    record AuthorizedSignature(
            byte[] bytes,
            String encoding,
            String expectedSignerReference) {

        public AuthorizedSignature {
            bytes = Objects.requireNonNull(bytes, "bytes").clone();
            if (bytes.length == 0 || bytes.length > 65_536) {
                throw new IllegalArgumentException("signature bytes are invalid");
            }
            encoding = requireText(encoding, "encoding");
            expectedSignerReference = requireText(
                    expectedSignerReference, "expectedSignerReference");
        }

        @Override
        public byte[] bytes() {
            return bytes.clone();
        }
    }

    record AttemptIdentity(
            OperationId operationId,
            AttemptId attemptId,
            Optional<NativeIdentity> nativeIdentity) {

        public AttemptIdentity {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            nativeIdentity = Objects.requireNonNull(nativeIdentity, "nativeIdentity");
        }
    }

    record NativeIdentity(String value) {
        public NativeIdentity {
            value = requireText(value, "nativeIdentity");
        }
    }

    record SubmissionResult(
            SubmissionClassification classification,
            NativeIdentity nativeIdentity,
            EvidenceRef evidenceReference) {

        public SubmissionResult {
            Objects.requireNonNull(classification, "classification");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
            if (classification == SubmissionClassification.ACCEPTED && nativeIdentity == null) {
                throw new IllegalArgumentException("accepted submission requires native identity");
            }
        }
    }

    record InquiryResult(
            OperationId operationId,
            AttemptId attemptId,
            Optional<NativeIdentity> nativeIdentity,
            RetrySafety retrySafety,
            EvidenceRef evidenceReference) {

        public InquiryResult {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            nativeIdentity = Objects.requireNonNull(nativeIdentity, "nativeIdentity");
            Objects.requireNonNull(retrySafety, "retrySafety");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }
    }

    record ObservationRequest(
            OperationId operationId,
            AttemptId attemptId,
            NativeIdentity nativeIdentity,
            String policyVersion) {

        public ObservationRequest {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(nativeIdentity, "nativeIdentity");
            policyVersion = requireText(policyVersion, "policyVersion");
        }
    }

    record Observation(
            OperationId operationId,
            AttemptId attemptId,
            NativeIdentity nativeIdentity,
            ObservationClassification classification,
            String policyVersion,
            Instant observedAt,
            List<EvidenceRef> evidenceReferences) {

        public Observation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(nativeIdentity, "nativeIdentity");
            Objects.requireNonNull(classification, "classification");
            policyVersion = requireText(policyVersion, "policyVersion");
            Objects.requireNonNull(observedAt, "observedAt");
            evidenceReferences = List.copyOf(evidenceReferences);
            if (evidenceReferences.isEmpty()) {
                throw new IllegalArgumentException("observation evidence is required");
            }
        }
    }

    enum SubmissionClassification {
        ACCEPTED,
        DEFINITIVELY_REJECTED,
        RETRYABLE_NO_EFFECT,
        AMBIGUOUS
    }

    enum ObservationClassification {
        ABSENT_OR_PENDING,
        CONFIRMED,
        REVERTED,
        MISMATCHED,
        ORPHANED
    }

    enum RetrySafety {
        SAFE_TO_RESUBMIT_EXACT_BYTES,
        SAFE_REPLACEMENT_RELATIONSHIP,
        NO_EFFECT_PROVEN,
        REQUIRES_OBSERVATION,
        UNSAFE
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }

    private static String requireDigest(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be a lowercase SHA-256 digest");
        }
        return value;
    }
}
