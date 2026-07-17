package io.github.johnwhitton.digitalbanking.application.port;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;

/**
 * HSM/MPC/custody-neutral provider boundary. Implementations must honor the stable
 * provider identity, expose no raw key material, and support inquiry after ambiguity.
 */
public interface SignerPort {

    ProviderResult signEvmDigest(EvmDigestCommand command);

    ProviderResult signSolanaMessage(SolanaMessageCommand command);

    ProviderResult inquire(Inquiry command);

    record ProviderContext(
            SigningRequest request,
            SigningAttemptId attemptId,
            ProviderRequestId providerRequestId) {

        public ProviderContext {
            Objects.requireNonNull(request, "request");
            Objects.requireNonNull(attemptId, "attemptId");
            Objects.requireNonNull(providerRequestId, "providerRequestId");
            if (request.attempts().isEmpty()
                    || !request.attempts().getLast().attemptId().equals(attemptId)
                    || !request.attempts().getLast().providerRequestId()
                            .equals(providerRequestId)) {
                throw new IllegalArgumentException(
                        "provider context must identify the durable active attempt");
            }
        }

        @Override
        public String toString() {
            return "ProviderContext[request=" + request.requestId()
                    + ", provider=[REDACTED]]";
        }
    }

    record EvmDigestCommand(ProviderContext context, byte[] digest) {

        public EvmDigestCommand {
            Objects.requireNonNull(context, "context");
            digest = verifiedMaterial(
                    context.request(), digest, SigningRequest.Mode.EVM_DIGEST);
        }

        @Override
        public byte[] digest() {
            return digest.clone();
        }

        @Override
        public String toString() {
            return "EvmDigestCommand[context=" + context + ", digest=[REDACTED]]";
        }
    }

    record SolanaMessageCommand(ProviderContext context, byte[] message) {

        public SolanaMessageCommand {
            Objects.requireNonNull(context, "context");
            message = verifiedMaterial(
                    context.request(), message, SigningRequest.Mode.SOLANA_MESSAGE);
        }

        @Override
        public byte[] message() {
            return message.clone();
        }

        @Override
        public String toString() {
            return "SolanaMessageCommand[context=" + context + ", message=[REDACTED]]";
        }
    }

    record Inquiry(ProviderContext context) {

        public Inquiry {
            Objects.requireNonNull(context, "context");
        }

        @Override
        public String toString() {
            return "Inquiry[context=" + context + "]";
        }
    }

    sealed interface ProviderResult permits
            Signed, Denied, RetryableNoSignature, Ambiguous, Conflict {

        EvidenceRef evidence();
    }

    record Signed(
            byte[] signature,
            String encoding,
            EvidenceRef evidence,
            SigningRequest.EvidenceOrigin origin) implements ProviderResult {

        public Signed {
            signature = Objects.requireNonNull(signature, "signature").clone();
            if (signature.length == 0 || signature.length > 65_536) {
                throw new IllegalArgumentException("signature bytes are invalid");
            }
            encoding = requireText(encoding, "encoding");
            Objects.requireNonNull(evidence, "evidence");
            Objects.requireNonNull(origin, "origin");
        }

        @Override
        public byte[] signature() {
            return signature.clone();
        }

        @Override
        public String toString() {
            return "Signed[signature=[REDACTED], evidence=[REDACTED], origin="
                    + origin + "]";
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof Signed that
                    && Arrays.equals(signature, that.signature)
                    && encoding.equals(that.encoding)
                    && evidence.equals(that.evidence)
                    && origin == that.origin;
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(encoding, evidence, origin) + Arrays.hashCode(signature);
        }
    }

    record Denied(String safeCode, EvidenceRef evidence) implements ProviderResult {

        public Denied {
            requireSafeCode(safeCode);
            Objects.requireNonNull(evidence, "evidence");
        }

        @Override
        public String toString() {
            return "Denied[safeCode=" + safeCode + ", evidence=[REDACTED]]";
        }
    }

    record RetryableNoSignature(
            String safeCode, EvidenceRef evidence) implements ProviderResult {

        public RetryableNoSignature {
            requireSafeCode(safeCode);
            Objects.requireNonNull(evidence, "evidence");
        }

        @Override
        public String toString() {
            return "RetryableNoSignature[safeCode=" + safeCode
                    + ", evidence=[REDACTED]]";
        }
    }

    record Ambiguous(EvidenceRef evidence) implements ProviderResult {

        public Ambiguous {
            Objects.requireNonNull(evidence, "evidence");
        }

        @Override
        public String toString() {
            return "Ambiguous[evidence=[REDACTED]]";
        }
    }

    record Conflict(String safeCode, EvidenceRef evidence) implements ProviderResult {

        public Conflict {
            requireSafeCode(safeCode);
            Objects.requireNonNull(evidence, "evidence");
        }

        @Override
        public String toString() {
            return "Conflict[safeCode=" + safeCode + ", evidence=[REDACTED]]";
        }
    }

    private static byte[] verifiedMaterial(
            SigningRequest request, byte[] value, SigningRequest.Mode requiredMode) {
        byte[] copy = Objects.requireNonNull(value, "signable material").clone();
        SigningRequest.PayloadIdentity identity = request.payloadIdentity();
        if (identity.mode() != requiredMode
                || copy.length != identity.length()
                || !sha256(copy).equals(identity.sha256())) {
            throw new IllegalArgumentException(
                    "signable material does not match the durable payload identity");
        }
        return copy;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 128) {
            throw new IllegalArgumentException(field + " must be non-blank and bounded");
        }
        return value;
    }

    private static String requireSafeCode(String value) {
        Pattern safe = Pattern.compile("[a-z0-9][a-z0-9.-]{0,127}");
        if (value == null || !safe.matcher(value).matches()) {
            throw new IllegalArgumentException("provider outcome code is invalid");
        }
        return value;
    }
}
