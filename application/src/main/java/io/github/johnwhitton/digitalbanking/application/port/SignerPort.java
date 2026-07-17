package io.github.johnwhitton.digitalbanking.application.port;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;

public interface SignerPort {

    SigningDecision sign(SigningRequest request);

    record SigningRequest(
            String signerRequestIdentity,
            OperationId operationId,
            AttemptId attemptId,
            OperationKind kind,
            String chainIdentity,
            String routeVersion,
            AssetUnit unit,
            TokenQuantity quantity,
            String sourceAuthority,
            String opaqueDestination,
            String actionIdentity,
            String nativeConstraintDigest,
            String feeCeiling,
            String allowlistReference,
            byte[] unsignedPayload,
            String unsignedPayloadSha256,
            Instant issuedAt,
            Instant expiresAt,
            String policyVersion,
            EvidenceRef approvalEvidence,
            EvidenceRef simulationEvidence) {

        public SigningRequest {
            signerRequestIdentity = requireText(
                    signerRequestIdentity, "signerRequestIdentity");
            Objects.requireNonNull(operationId, "operationId");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(kind, "kind");
            chainIdentity = requireText(chainIdentity, "chainIdentity");
            routeVersion = requireText(routeVersion, "routeVersion");
            Objects.requireNonNull(unit, "unit");
            Objects.requireNonNull(quantity, "quantity");
            if (!quantity.unit().equals(unit)) {
                throw new IllegalArgumentException("signing quantity and unit do not match");
            }
            sourceAuthority = requireText(sourceAuthority, "sourceAuthority");
            opaqueDestination = requireText(opaqueDestination, "opaqueDestination");
            actionIdentity = requireText(actionIdentity, "actionIdentity");
            nativeConstraintDigest = requireSha256(
                    nativeConstraintDigest, "nativeConstraintDigest");
            feeCeiling = requireText(feeCeiling, "feeCeiling");
            allowlistReference = requireText(allowlistReference, "allowlistReference");
            unsignedPayload = Objects.requireNonNull(unsignedPayload, "unsignedPayload").clone();
            if (unsignedPayload.length == 0) {
                throw new IllegalArgumentException("unsigned payload is required");
            }
            unsignedPayloadSha256 = requireSha256(
                    unsignedPayloadSha256, "unsignedPayloadSha256");
            if (!unsignedPayloadSha256.equals(sha256(unsignedPayload))) {
                throw new IllegalArgumentException(
                        "unsigned payload does not match its SHA-256 digest");
            }
            Objects.requireNonNull(issuedAt, "issuedAt");
            Objects.requireNonNull(expiresAt, "expiresAt");
            if (!expiresAt.isAfter(issuedAt)) {
                throw new IllegalArgumentException("signing request expiry must follow issuance");
            }
            policyVersion = requireText(policyVersion, "policyVersion");
            Objects.requireNonNull(approvalEvidence, "approvalEvidence");
            Objects.requireNonNull(simulationEvidence, "simulationEvidence");
        }

        @Override
        public byte[] unsignedPayload() {
            return unsignedPayload.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SigningRequest that
                    && signerRequestIdentity.equals(that.signerRequestIdentity)
                    && operationId.equals(that.operationId)
                    && attemptId.equals(that.attemptId)
                    && kind == that.kind
                    && chainIdentity.equals(that.chainIdentity)
                    && routeVersion.equals(that.routeVersion)
                    && unit.equals(that.unit)
                    && quantity.equals(that.quantity)
                    && sourceAuthority.equals(that.sourceAuthority)
                    && opaqueDestination.equals(that.opaqueDestination)
                    && actionIdentity.equals(that.actionIdentity)
                    && nativeConstraintDigest.equals(that.nativeConstraintDigest)
                    && feeCeiling.equals(that.feeCeiling)
                    && allowlistReference.equals(that.allowlistReference)
                    && Arrays.equals(unsignedPayload, that.unsignedPayload)
                    && unsignedPayloadSha256.equals(that.unsignedPayloadSha256)
                    && issuedAt.equals(that.issuedAt)
                    && expiresAt.equals(that.expiresAt)
                    && policyVersion.equals(that.policyVersion)
                    && approvalEvidence.equals(that.approvalEvidence)
                    && simulationEvidence.equals(that.simulationEvidence);
        }

        @Override
        public int hashCode() {
            int result = Objects.hash(
                    signerRequestIdentity, operationId, attemptId, kind, chainIdentity,
                    routeVersion, unit, quantity, sourceAuthority, opaqueDestination,
                    actionIdentity, nativeConstraintDigest, feeCeiling, allowlistReference,
                    unsignedPayloadSha256, issuedAt, expiresAt, policyVersion,
                    approvalEvidence, simulationEvidence);
            return 31 * result + Arrays.hashCode(unsignedPayload);
        }

        @Override
        public String toString() {
            return "SigningRequest[signerRequestIdentity=[REDACTED]"
                    + ", operationId=" + operationId
                    + ", attemptId=" + attemptId
                    + ", kind=" + kind
                    + ", exactEffect=[BOUND]"
                    + ", unsignedPayloadLength=" + unsignedPayload.length
                    + ", unsignedPayloadSha256=" + unsignedPayloadSha256
                    + ", issuedAt=" + issuedAt
                    + ", expiresAt=" + expiresAt
                    + ", policy=[REDACTED]"
                    + ", evidence=[REDACTED]]";
        }
    }

    record SigningDecision(
            Decision decision,
            String providerRequestIdentity,
            String keyReference,
            byte[] signedPayload,
            String digestReference,
            String reason,
            String signerPolicyVersion,
            EvidenceRef authorizationEvidence) {

        public SigningDecision {
            Objects.requireNonNull(decision, "decision");
            providerRequestIdentity = requireText(
                    providerRequestIdentity, "providerRequestIdentity");
            keyReference = requireText(keyReference, "keyReference");
            signedPayload = Objects.requireNonNull(signedPayload, "signedPayload").clone();
            digestReference = requireSha256(digestReference, "digestReference");
            reason = requireText(reason, "reason");
            signerPolicyVersion = requireText(signerPolicyVersion, "signerPolicyVersion");
            Objects.requireNonNull(authorizationEvidence, "authorizationEvidence");
            if (decision == Decision.APPROVED && signedPayload.length == 0) {
                throw new IllegalArgumentException("approved signing decision requires a payload");
            }
            if (decision == Decision.REJECTED && signedPayload.length != 0) {
                throw new IllegalArgumentException(
                        "rejected signing decision cannot contain a signed payload");
            }
        }

        @Override
        public byte[] signedPayload() {
            return signedPayload.clone();
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof SigningDecision that
                    && decision == that.decision
                    && providerRequestIdentity.equals(that.providerRequestIdentity)
                    && keyReference.equals(that.keyReference)
                    && Arrays.equals(signedPayload, that.signedPayload)
                    && digestReference.equals(that.digestReference)
                    && reason.equals(that.reason)
                    && signerPolicyVersion.equals(that.signerPolicyVersion)
                    && authorizationEvidence.equals(that.authorizationEvidence);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(
                    decision, keyReference, digestReference, reason,
                    providerRequestIdentity, signerPolicyVersion, authorizationEvidence)
                    + Arrays.hashCode(signedPayload);
        }

        @Override
        public String toString() {
            return "SigningDecision[decision=" + decision
                    + ", providerRequestIdentity=[REDACTED]"
                    + ", keyReference=[REDACTED]"
                    + ", signedPayloadLength=" + signedPayload.length
                    + ", digestReference=" + digestReference
                    + ", decisionDetails=[REDACTED]"
                    + ", authorizationEvidence=[REDACTED]]";
        }
    }

    enum Decision {
        APPROVED,
        REJECTED
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }

    private static String requireSha256(String value, String field) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(field + " must be lowercase SHA-256 hex");
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
