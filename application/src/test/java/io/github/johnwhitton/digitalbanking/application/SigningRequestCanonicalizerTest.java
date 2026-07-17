package io.github.johnwhitton.digitalbanking.application;

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
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SigningRequestCanonicalizerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T21:00:00Z");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @Test
    void versionOneGoldenIntentBindsEveryContextCategory() {
        SigningAuthorityService.Request base = request(
                1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                "token-contract:mint", A, "fee-limit-policy-v1", B,
                new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                NOW.plusSeconds(300), Optional.empty());
        String golden = intent(base);

        assertEquals(
                "a4e56ed5aebf78c534e75d477b8dd84746ce9d56e85dadedb4ffc2393d745219",
                golden);
        assertEquals(12, Set.of(
                golden,
                intent(request(2, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.35", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "changed-source",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "changed-native-action", B, "changed-fee-limit", A,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint-v2"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.TRANSFER, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "treasury-source",
                        "treasury-destination", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-transfer"),
                        SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.SOLANA,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-program:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.SOLANA_MESSAGE, SigningRequest.Algorithm.ED25519,
                        bytes(96, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 2), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v6", List.of(new EvidenceRef("evidence:approval-2")),
                        NOW.plusSeconds(300), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(301), Optional.empty())),
                intent(request(1, SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), "mint-authority-role",
                        "token-contract:mint", A, "fee-limit-policy-v1", B,
                        new KeyAlias("institution-mint"), SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                        bytes(32, 1), "policy-v5", List.of(new EvidenceRef("evidence:approval")),
                        NOW.plusSeconds(300), Optional.of(new SigningRequest.Lineage(
                                new SigningRequestId(new UUID(9, 1)),
                                new SigningAttemptId(new UUID(9, 2)),
                                new EvidenceRef("evidence:linked-retry")))))).size());
    }

    @Test
    void resolvedKeyVersionAndAuthorityMetadataChangeResolvedDigest() {
        SigningRequest.KeyContext first = key("registry-v1", "key-v1");
        SigningRequest.KeyContext second = key("registry-v2", "key-v2");

        assertNotEquals(
                SigningRequestCanonicalizer.resolved(A, first),
                SigningRequestCanonicalizer.resolved(A, second));
    }

    private static String intent(SigningAuthorityService.Request request) {
        return SigningRequestCanonicalizer.intent(request, request.payloadIdentity());
    }

    private static SigningRequest.KeyContext key(String registry, String version) {
        return new SigningRequest.KeyContext(
                new KeyAlias("institution-mint"), registry, Optional.of(version),
                SigningRequest.KeyRole.MINT_AUTHORITY, SigningRequest.Algorithm.SECP256K1,
                SettlementNetwork.ETHEREUM, SigningRequest.KeyStatus.ACTIVE,
                Set.of(SigningRequest.KeyRole.MINT_AUTHORITY),
                Set.of(SigningRequest.Algorithm.SECP256K1),
                Set.of(SettlementNetwork.ETHEREUM), NOW.minusSeconds(60),
                Optional.of(NOW.plusSeconds(3600)));
    }

    private static SigningAuthorityService.Request request(
            long seed,
            SigningRequest.Action action,
            SettlementNetwork network,
            TokenQuantity quantity,
            String source,
            String nativeAction,
            String lifetime,
            String feeLimit,
            String constraints,
            KeyAlias keyAlias,
            SigningRequest.KeyRole keyRole,
            SigningRequest.Mode mode,
            SigningRequest.Algorithm algorithm,
            byte[] material,
            String policy,
            List<EvidenceRef> approvals,
            Instant expiresAt,
            Optional<SigningRequest.Lineage> lineage) {
        return new SigningAuthorityService.Request(
                new SigningRequestId(new UUID(1, seed)),
                new SigningRequest.Correlation(
                        new OperationId(new UUID(2, seed)),
                        new AttemptId(new UUID(3, seed)), Optional.empty(), Optional.empty()),
                lineage, action, network, quantity, source, "opaque-destination",
                nativeAction, lifetime, feeLimit, constraints, keyAlias, keyRole,
                mode, algorithm, material, policy, approvals, NOW, expiresAt);
    }

    private static byte[] bytes(int size, int value) {
        byte[] result = new byte[size];
        java.util.Arrays.fill(result, (byte) value);
        return result;
    }
}
