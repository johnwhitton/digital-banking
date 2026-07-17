package io.github.johnwhitton.digitalbanking.application.port;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortContractTest {

    @Test
    void signerRequestBindsExactEffectWithoutPrivateKeyMaterial() {
        byte[] unsigned = {1, 2, 3};
        SignerPort.SigningRequest request = new SignerPort.SigningRequest(
                "signer-request-001",
                OperationId.from("9ecbbdb1-cf29-4f35-b762-1212a5727c38"),
                AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182"),
                OperationKind.MINT,
                "ethereum-local",
                "route-v1",
                new AssetUnit("USDC", "USD", 7, 6, BigInteger.TEN.pow(18)),
                TokenQuantity.parse("1", new AssetUnit(
                        "USDC", "USD", 7, 6, BigInteger.TEN.pow(18))),
                "mint-authority-ref",
                "opaque-destination",
                "contract-and-method-ref",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "fee-ceiling-ref",
                "allowlist-v3",
                unsigned,
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                Instant.parse("2026-07-16T22:59:00Z"),
                Instant.parse("2026-07-16T23:00:00Z"),
                "policy-v5",
                new EvidenceRef("evidence:approval"),
                new EvidenceRef("evidence:simulation"));

        unsigned[0] = 9;
        assertEquals("signer-request-001", request.signerRequestIdentity());
        assertEquals("ethereum-local", request.chainIdentity());
        assertEquals("route-v1", request.routeVersion());
        assertEquals("mint-authority-ref", request.sourceAuthority());
        assertEquals("opaque-destination", request.opaqueDestination());
        assertEquals("contract-and-method-ref", request.actionIdentity());
        assertEquals("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                request.nativeConstraintDigest());
        assertEquals("fee-ceiling-ref", request.feeCeiling());
        assertEquals("allowlist-v3", request.allowlistReference());
        assertEquals("policy-v5", request.policyVersion());
        assertEquals(new EvidenceRef("evidence:simulation"), request.simulationEvidence());
        assertEquals(1, request.unsignedPayload()[0]);
        assertNotSame(request.unsignedPayload(), request.unsignedPayload());
        assertFalse(request.toString().contains("private"));
        assertFalse(request.toString().contains("[1, 2, 3]"));
    }

    @Test
    void signerRejectsPayloadDigestTamperingAndInvalidLifetime() {
        assertThrows(IllegalArgumentException.class, () -> signingRequest(
                new byte[] {1, 2, 4},
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                Instant.parse("2026-07-16T22:59:00Z"),
                Instant.parse("2026-07-16T23:00:00Z")));
        assertThrows(IllegalArgumentException.class, () -> signingRequest(
                new byte[] {1, 2, 3},
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                Instant.parse("2026-07-16T23:00:00Z"),
                Instant.parse("2026-07-16T23:00:00Z")));
    }

    @Test
    void rejectedSigningDecisionCannotCarrySignedBytes() {
        assertThrows(IllegalArgumentException.class, () -> new SignerPort.SigningDecision(
                SignerPort.Decision.REJECTED,
                "provider-request-001",
                "key-ref",
                new byte[] {1},
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                "policy rejected",
                "signer-policy-v1",
                new EvidenceRef("evidence:signer-rejection")));
    }

    @Test
    void signingDecisionDiagnosticsRedactProviderAndPolicyDetails() {
        SignerPort.SigningDecision decision = new SignerPort.SigningDecision(
                SignerPort.Decision.REJECTED,
                "provider-request-sensitive",
                "key-reference-sensitive",
                new byte[0],
                "039058c6f2c0cb492c533b0a4d14ef77cc0f78abccced5287d84a1a2011cfb81",
                "sensitive rejection reason",
                "signer-policy-sensitive",
                new EvidenceRef("evidence:sensitive-provider-decision"));

        String diagnostic = decision.toString();
        assertFalse(diagnostic.contains("provider-request-sensitive"));
        assertFalse(diagnostic.contains("key-reference-sensitive"));
        assertFalse(diagnostic.contains("sensitive rejection reason"));
        assertFalse(diagnostic.contains("signer-policy-sensitive"));
        assertFalse(diagnostic.contains("evidence:sensitive-provider-decision"));
    }

    @Test
    void chainPortSeparatesPrepareSubmitInquiryAndObservation() {
        ChainPort port = new RecordingChainPort();
        OperationId operationId = OperationId.from("9ecbbdb1-cf29-4f35-b762-1212a5727c38");
        AttemptId attemptId = AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182");
        ChainPort.AttemptIdentity attemptIdentity = new ChainPort.AttemptIdentity(
                operationId, attemptId, Optional.empty());
        ChainPort.ObservationRequest observationRequest = new ChainPort.ObservationRequest(
                operationId, attemptId, new ChainPort.NativeIdentity("native-opaque"),
                "finality-v1");

        assertEquals("prepared", port.prepare(null, null).evidenceReference().value());
        assertEquals(ChainPort.SubmissionClassification.AMBIGUOUS,
                port.submitOnce(null).classification());
        assertEquals(ChainPort.RetrySafety.REQUIRES_OBSERVATION,
                port.inquire(attemptIdentity).retrySafety());
        assertEquals(attemptId, port.inquire(attemptIdentity).attemptId());
        assertEquals(operationId, port.observe(observationRequest).operationId());
        assertEquals("native-opaque", port.observe(observationRequest).nativeIdentity().value());
    }

    private static SignerPort.SigningRequest signingRequest(
            byte[] payload, String digest, Instant issuedAt, Instant expiresAt) {
        AssetUnit unit = new AssetUnit("USDC", "USD", 7, 6, BigInteger.TEN.pow(18));
        return new SignerPort.SigningRequest(
                "signer-request-001",
                OperationId.from("9ecbbdb1-cf29-4f35-b762-1212a5727c38"),
                AttemptId.from("89fb3189-2e23-48a7-b77f-7e7a0e1ff182"),
                OperationKind.MINT,
                "ethereum-local",
                "route-v1",
                unit,
                TokenQuantity.parse("1", unit),
                "mint-authority-ref",
                "opaque-destination",
                "contract-and-method-ref",
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "fee-ceiling-ref",
                "allowlist-v3",
                payload,
                digest,
                issuedAt,
                expiresAt,
                "policy-v5",
                new EvidenceRef("evidence:approval"),
                new EvidenceRef("evidence:simulation"));
    }

    private static final class RecordingChainPort implements ChainPort {
        @Override
        public ChainCapabilities capabilities(String routeVersion) {
            return new ChainCapabilities(true, true, true);
        }

        @Override
        public PreparedAttempt prepare(
                io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation operation,
                io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt attempt) {
            return new PreparedAttempt(new byte[] {1}, "digest", new EvidenceRef("prepared"));
        }

        @Override
        public SubmissionResult submitOnce(SignedAttempt signedAttempt) {
            return new SubmissionResult(
                    SubmissionClassification.AMBIGUOUS, null,
                    new EvidenceRef("evidence:submit-timeout"));
        }

        @Override
        public InquiryResult inquire(AttemptIdentity attemptIdentity) {
            return new InquiryResult(
                    attemptIdentity.operationId(), attemptIdentity.attemptId(), Optional.empty(),
                    RetrySafety.REQUIRES_OBSERVATION,
                    new EvidenceRef("evidence:inquiry"));
        }

        @Override
        public Observation observe(ObservationRequest request) {
            return new Observation(
                    request.operationId(), request.attemptId(), request.nativeIdentity(),
                    "PENDING", request.policyVersion(),
                    Instant.parse("2026-07-16T23:00:00Z"),
                    List.of(new EvidenceRef("evidence:observation")));
        }
    }
}
