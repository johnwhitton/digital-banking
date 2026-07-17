package io.github.johnwhitton.digitalbanking.domain.signing;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest.Action;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest.Algorithm;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest.KeyRole;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest.KeyStatus;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest.Mode;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest.Status;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SigningRequestTest {

    private static final Instant START = Instant.parse("2026-07-17T20:00:00Z");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @Test
    void keepsEvmDigestAndSolanaMessageModesDistinct() {
        SigningRequest.PayloadIdentity evm = new SigningRequest.PayloadIdentity(
                Mode.EVM_DIGEST, Algorithm.SECP256K1, A, 32,
                SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST);
        SigningRequest.PayloadIdentity solana = new SigningRequest.PayloadIdentity(
                Mode.SOLANA_MESSAGE, Algorithm.ED25519, B, 128,
                SigningRequest.PayloadEncoding.SOLANA_SERIALIZED_MESSAGE);

        assertEquals(Mode.EVM_DIGEST, evm.mode());
        assertEquals(Mode.SOLANA_MESSAGE, solana.mode());
        assertThrows(IllegalArgumentException.class, () -> new SigningRequest.PayloadIdentity(
                Mode.EVM_DIGEST, Algorithm.ED25519, A, 32,
                SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST));
        assertThrows(IllegalArgumentException.class, () -> new SigningRequest.PayloadIdentity(
                Mode.SOLANA_MESSAGE, Algorithm.ED25519, B, 0,
                SigningRequest.PayloadEncoding.SOLANA_SERIALIZED_MESSAGE));
        assertThrows(IllegalArgumentException.class, () -> new SigningRequest.PayloadIdentity(
                Mode.SOLANA_MESSAGE, Algorithm.ED25519, B, 32,
                SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST));
    }

    @Test
    void bindsCompleteImmutableContextAndRedactsSensitiveDiagnostics() {
        SigningRequest request = requested(1, Optional.empty(), payload(A));

        assertEquals(Action.MINT, request.authorityContext().action());
        assertEquals("institution-mint-v7", request.keyContext().keyVersion().orElseThrow());
        assertEquals("policy-v5", request.authorityContext().policyVersion());
        assertEquals(List.of(new EvidenceRef("evidence:approval:four-eyes")),
                request.authorityContext().approvalEvidence());
        assertEquals(A, request.payloadIdentity().sha256());
        assertEquals(Status.REQUESTED, request.status());
        assertEquals(0, request.version());
        assertFalse(request.toString().contains("institution-mint"));
        assertFalse(request.toString().contains("policy-v5"));
        assertFalse(request.toString().contains(A));
        assertFalse(request.toString().contains("opaque-recipient-wallet"));
    }

    @Test
    void fencesAmbiguityUntilInquiryProvesNoSignatureThenLinksRetry() {
        SigningRequest request = requested(2, Optional.empty(), payload(A));
        request = request.awaitAuthorization(
                0, START.plusSeconds(1), new EvidenceRef("evidence:policy-pending"));
        request = request.authorize(
                1, START.plusSeconds(2), new EvidenceRef("evidence:authorized"));
        SigningAttemptId firstAttempt = signingAttempt(20);
        request = request.persistProviderRequest(
                2, firstAttempt, providerRequest(20), START.plusSeconds(3),
                new EvidenceRef("evidence:provider-request-persisted"));
        request = request.recordProviderOutcome(
                3, firstAttempt,
                SigningRequest.ProviderOutcome.ambiguous(
                        new EvidenceRef("evidence:provider-timeout")),
                START.plusSeconds(4));

        SigningRequest ambiguous = request;
        assertEquals(Status.AMBIGUOUS, ambiguous.status());
        assertThrows(IllegalStateException.class, () -> ambiguous.persistProviderRequest(
                ambiguous.version(), signingAttempt(21), providerRequest(21),
                START.plusSeconds(5), new EvidenceRef("evidence:blind-retry")));

        request = request.recordProviderOutcome(
                4, firstAttempt,
                SigningRequest.ProviderOutcome.retryableNoSignature(
                        "provider-proved-no-signature",
                        new EvidenceRef("evidence:provider-inquiry-no-signature")),
                START.plusSeconds(5));
        SigningRequest retryable = request;
        assertThrows(IllegalStateException.class, () -> retryable.persistProviderRequest(
                retryable.version(), signingAttempt(21), providerRequest(21),
                START.plusSeconds(6), new EvidenceRef("evidence:unapproved-retry")));
        request = request.awaitAuthorization(
                5, START.plusSeconds(6), new EvidenceRef("evidence:retry-policy-pending"));
        request = request.authorize(
                6, START.plusSeconds(7), new EvidenceRef("evidence:retry-authorized"));
        SigningAttemptId retryAttempt = signingAttempt(21);
        request = request.persistProviderRequest(
                7, retryAttempt, providerRequest(21), START.plusSeconds(8),
                new EvidenceRef("evidence:retry-authorized"));

        assertEquals(Status.PROVIDER_REQUEST_PERSISTED, request.status());
        assertEquals(2, request.attempts().size());
        assertEquals(Optional.of(firstAttempt), request.attempts().getLast().predecessor());
        assertEquals(8, request.version());
    }

    @Test
    void changedPayloadUsesNewLinkedRequestAndCannotMutateAuthorizedHistory() {
        SigningRequest original = requested(3, Optional.empty(), payload(A))
                .awaitAuthorization(
                        0, START.plusSeconds(1), new EvidenceRef("evidence:policy-pending"))
                .authorize(1, START.plusSeconds(2), new EvidenceRef("evidence:authorized"))
                .persistProviderRequest(
                        2, signingAttempt(30), providerRequest(30), START.plusSeconds(3),
                        new EvidenceRef("evidence:provider-request-persisted"));
        SigningRequest linked = requested(
                4,
                Optional.of(new SigningRequest.Lineage(
                        original.requestId(), signingAttempt(30),
                        new EvidenceRef("evidence:changed-native-payload-authorized"))),
                payload(B));

        assertEquals(A, original.payloadIdentity().sha256());
        assertEquals(B, linked.payloadIdentity().sha256());
        assertEquals(original.requestId(), linked.lineage().orElseThrow().requestId());
        assertEquals(signingAttempt(30), linked.lineage().orElseThrow().attemptId());
    }

    @Test
    void rejectsOptimisticConflictAndBackdatedTransitions() {
        SigningRequest request = requested(5, Optional.empty(), payload(A));

        assertThrows(IllegalStateException.class, () -> request.awaitAuthorization(
                1, START.plusSeconds(1), new EvidenceRef("evidence:wrong-version")));
        assertThrows(IllegalArgumentException.class, () -> request.awaitAuthorization(
                0, START.minusSeconds(1), new EvidenceRef("evidence:backdated")));
    }

    private static SigningRequest requested(
            long seed,
            Optional<SigningRequest.Lineage> lineage,
            SigningRequest.PayloadIdentity payload) {
        OperationId operationId = new OperationId(new UUID(1, seed));
        AttemptId operationAttemptId = new AttemptId(new UUID(2, seed));
        TransferId transferId = new TransferId(new UUID(3, seed));
        TransferEffect.Id effectId = new TransferEffect.Id(new UUID(4, seed));
        KeyAlias alias = new KeyAlias("institution-mint");
        SigningRequest.KeyContext key = new SigningRequest.KeyContext(
                alias,
                "registry-v3",
                Optional.of("institution-mint-v7"),
                KeyRole.MINT_AUTHORITY,
                Algorithm.SECP256K1,
                SettlementNetwork.ETHEREUM,
                KeyStatus.ACTIVE,
                Set.of(KeyRole.MINT_AUTHORITY),
                Set.of(Algorithm.SECP256K1),
                Set.of(SettlementNetwork.ETHEREUM),
                START.minusSeconds(60),
                Optional.of(START.plusSeconds(3600)));
        SigningRequest.AuthorityContext authority = new SigningRequest.AuthorityContext(
                Action.MINT,
                SettlementNetwork.ETHEREUM,
                TokenQuantity.parse("12.34", UNIT),
                "mint-authority-role",
                "opaque-recipient-wallet",
                "token-contract:mint",
                A,
                "fee-limit-policy-v1",
                B,
                "policy-v5",
                List.of(new EvidenceRef("evidence:approval:four-eyes")),
                START,
                START.plusSeconds(300));
        return SigningRequest.requested(
                new SigningRequestId(new UUID(5, seed)),
                new SigningRequest.Correlation(
                        operationId, operationAttemptId,
                        Optional.of(transferId), Optional.of(effectId)),
                lineage,
                payload,
                key,
                authority,
                1,
                A,
                1,
                B,
                START,
                new EvidenceRef("evidence:signing-requested"));
    }

    private static SigningRequest.PayloadIdentity payload(String digest) {
        return new SigningRequest.PayloadIdentity(
                Mode.EVM_DIGEST, Algorithm.SECP256K1, digest, 32,
                SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST);
    }

    private static SigningAttemptId signingAttempt(long seed) {
        return new SigningAttemptId(new UUID(6, seed));
    }

    private static ProviderRequestId providerRequest(long seed) {
        return new ProviderRequestId("provider-request-" + seed);
    }
}
