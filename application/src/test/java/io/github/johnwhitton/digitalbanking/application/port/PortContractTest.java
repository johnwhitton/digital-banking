package io.github.johnwhitton.digitalbanking.application.port;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortContractTest {

    private static final Instant START = Instant.parse("2026-07-17T21:00:00Z");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @Test
    void signerPortSeparatesNativeModesAndDefensivelyCopiesSensitiveBytes() {
        byte[] digest = new byte[32];
        java.util.Arrays.fill(digest, (byte) 7);
        SigningRequest request = persistedRequest(digest);
        SigningRequest.Attempt attempt = request.attempts().getLast();
        SignerPort.ProviderContext context = new SignerPort.ProviderContext(
                request, attempt.attemptId(), attempt.providerRequestId());

        SignerPort.EvmDigestCommand command = new SignerPort.EvmDigestCommand(context, digest);
        digest[0] = 99;
        assertEquals(7, command.digest()[0]);
        assertNotSame(command.digest(), command.digest());
        assertThrows(IllegalArgumentException.class,
                () -> new SignerPort.SolanaMessageCommand(context, new byte[] {1}));

        byte[] signature = {1, 2, 3};
        SignerPort.Signed result = new SignerPort.Signed(
                signature, "SYNTHETIC_SIGNATURE_V1",
                new EvidenceRef("synthetic:provider:signed"),
                SigningRequest.EvidenceOrigin.SYNTHETIC_TEST);
        signature[0] = 9;
        assertEquals(1, result.signature()[0]);
        assertNotSame(result.signature(), result.signature());
        assertFalse(command.toString().contains(sha256(command.digest())));
        assertFalse(result.toString().contains("[1, 2, 3]"));
        assertFalse(context.toString().contains("provider-request-sensitive"));
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

        assertEquals("prepared", port.prepare(new UUID(0, 1), null, null)
                .evidenceReference().value());
        assertEquals(ChainPort.SubmissionClassification.AMBIGUOUS,
                port.submitOnce(null).classification());
        assertEquals(ChainPort.RetrySafety.REQUIRES_OBSERVATION,
                port.inquire(attemptIdentity).retrySafety());
        assertEquals(attemptId, port.inquire(attemptIdentity).attemptId());
        assertEquals(operationId, port.observe(observationRequest).operationId());
        assertEquals("native-opaque", port.observe(observationRequest).nativeIdentity().value());
    }

    private static SigningRequest persistedRequest(byte[] digest) {
        SigningRequest request = SigningRequest.requested(
                new SigningRequestId(new UUID(1, 1)),
                new SigningRequest.Correlation(
                        new OperationId(new UUID(2, 1)), new AttemptId(new UUID(3, 1)),
                        Optional.empty(), Optional.empty()),
                Optional.empty(),
                new SigningRequest.PayloadIdentity(
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        sha256(digest), digest.length,
                        SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST),
                new SigningRequest.KeyContext(
                        new KeyAlias("institution-mint"), "registry-v1",
                        Optional.of("key-version-7"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM,
                        SigningRequest.KeyStatus.ACTIVE,
                        Set.of(SigningRequest.KeyRole.MINT_AUTHORITY),
                        Set.of(SigningRequest.Algorithm.SECP256K1),
                        Set.of(SettlementNetwork.ETHEREUM), START.minusSeconds(60),
                        Optional.of(START.plusSeconds(3600))),
                new SigningRequest.AuthorityContext(
                        SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("1", UNIT), "mint-authority-role",
                        "opaque-recipient-wallet", "token-contract:mint",
                        "a".repeat(64), "fee-policy-v1", "b".repeat(64), "policy-v1",
                        List.of(new EvidenceRef("evidence:approval")), START,
                        START.plusSeconds(300)),
                1, "c".repeat(64), 1, "d".repeat(64), START,
                new EvidenceRef("evidence:requested"));
        request = request.awaitAuthorization(
                0, START.plusSeconds(1), new EvidenceRef("evidence:awaiting"));
        request = request.authorize(
                1, START.plusSeconds(2), new EvidenceRef("evidence:authorized"));
        return request.persistProviderRequest(
                2, new SigningAttemptId(new UUID(4, 1)),
                new ProviderRequestId("provider-request-sensitive"), START.plusSeconds(3),
                new EvidenceRef("evidence:provider-request"));
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class RecordingChainPort implements ChainPort {
        @Override
        public ChainCapabilities capabilities(String routeVersion) {
            return new ChainCapabilities(true, true, true);
        }

        @Override
        public PreparedAttempt prepare(
                UUID deliveryId,
                io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation operation,
                io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt attempt) {
            return new PreparedAttempt(
                    new byte[] {1}, "digest", "source", "destination", "native-action",
                    "a".repeat(64), "fee-limit", "b".repeat(64), "policy-v1",
                    new EvidenceRef("prepared"));
        }

        @Override
        public Optional<SignedAttempt> findSignedAttempt(AttemptIdentity attemptIdentity) {
            return Optional.empty();
        }

        @Override
        public SignedAttempt attachSignature(
                AttemptIdentity attemptIdentity, AuthorizedSignature signature) {
            return new SignedAttempt(
                    attemptIdentity.operationId(), attemptIdentity.attemptId(),
                    new NativeIdentity("native-opaque"), new EvidenceRef("evidence:signed"));
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
                    ObservationClassification.ABSENT_OR_PENDING,
                    request.policyVersion(), START,
                    List.of(new EvidenceRef("evidence:observation")));
        }
    }
}
