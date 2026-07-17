package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;

public interface ChainPort {

    ChainCapabilities capabilities(String routeVersion);

    PreparedAttempt prepare(TokenOperation operation, OperationAttempt attempt);

    SubmissionResult submitOnce(SignedAttempt signedAttempt);

    InquiryResult inquire(AttemptIdentity attemptIdentity);

    Observation observe(ObservationRequest request);

    record ChainCapabilities(
            boolean supportsMint,
            boolean supportsBurn,
            boolean supportsIndependentInquiry) {
    }

    record PreparedAttempt(
            byte[] unsignedPayload,
            String digestReference,
            EvidenceRef evidenceReference) {

        public PreparedAttempt {
            unsignedPayload = Objects.requireNonNull(unsignedPayload, "unsignedPayload").clone();
            if (unsignedPayload.length == 0) {
                throw new IllegalArgumentException("prepared payload is required");
            }
            digestReference = requireText(digestReference, "digestReference");
            Objects.requireNonNull(evidenceReference, "evidenceReference");
        }

        @Override
        public byte[] unsignedPayload() {
            return unsignedPayload.clone();
        }
    }

    record SignedAttempt(
            OperationId operationId,
            AttemptId attemptId,
            byte[] signedPayload,
            String digestReference) {

        public SignedAttempt {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            signedPayload = Objects.requireNonNull(signedPayload, "signedPayload").clone();
            if (signedPayload.length == 0) {
                throw new IllegalArgumentException("signed payload is required");
            }
            digestReference = requireText(digestReference, "digestReference");
        }

        @Override
        public byte[] signedPayload() {
            return signedPayload.clone();
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
            String nativeStatus,
            String policyVersion,
            Instant observedAt,
            List<EvidenceRef> evidenceReferences) {

        public Observation {
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(nativeIdentity, "nativeIdentity");
            nativeStatus = requireText(nativeStatus, "nativeStatus");
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
        AMBIGUOUS
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
}
