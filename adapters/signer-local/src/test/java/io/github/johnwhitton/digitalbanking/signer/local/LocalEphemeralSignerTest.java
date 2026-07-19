package io.github.johnwhitton.digitalbanking.signer.local;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
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
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalEphemeralSignerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final X9ECParameters SECP256K1 =
            CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters EVM_DOMAIN = new ECDomainParameters(
            SECP256K1.getCurve(), SECP256K1.getG(), SECP256K1.getN(),
            SECP256K1.getH());

    @Test
    void signsExactEvmDigestWithLowSCompactRecoveryAndNoSecondHash() throws Exception {
        LocalEphemeralSigner signer = signer(65_536);
        LocalEphemeralSigner.KeyMetadata key = signer.keys().stream()
                .filter(candidate -> candidate.algorithm() == SigningRequest.Algorithm.SECP256K1)
                .findFirst().orElseThrow();
        byte[] digest = bytes(32, 7);
        SignerPort.EvmDigestCommand command = evmCommand(signer, key, digest, "evm-provider-1");

        SignerPort.Signed result = assertInstanceOf(
                SignerPort.Signed.class, signer.signEvmDigest(command));
        byte[] signature = result.signature();

        assertEquals("LOCAL_EVM_SECP256K1_RSV_LOW_S_V1", result.encoding());
        assertEquals(SigningRequest.EvidenceOrigin.PROVIDER, result.origin());
        assertEquals(65, signature.length);
        BigInteger r = unsigned(signature, 0);
        BigInteger s = unsigned(signature, 32);
        int recoveryId = signature[64] & 0xff;
        assertTrue(s.compareTo(SECP256K1.getN().shiftRight(1)) <= 0);
        assertTrue(recoveryId >= 0 && recoveryId <= 3);

        ECPoint expected = SECP256K1.getCurve()
                .decodePoint(signer.publicKey(key.alias())).normalize();
        assertTrue(verifies(expected, digest, r, s));
        assertEquals(expected, recover(digest, r, s, recoveryId));
        assertFalse(verifies(expected, MessageDigest.getInstance("SHA-256").digest(digest), r, s));
        assertEquals(1, signer.signingInvocationCount());
    }

    @Test
    void rejectsEvmLengthAndModeOrAlgorithmInterchangeBeforeSigning() {
        LocalEphemeralSigner signer = signer(65_536);
        LocalEphemeralSigner.KeyMetadata evm = key(signer, SigningRequest.Algorithm.SECP256K1);
        LocalEphemeralSigner.KeyMetadata solana = key(signer, SigningRequest.Algorithm.ED25519);
        byte[] digest = bytes(32, 3);
        SignerPort.EvmDigestCommand command = evmCommand(signer, evm, digest, "evm-provider-2");

        assertThrows(IllegalArgumentException.class, () ->
                new SignerPort.EvmDigestCommand(command.context(), Arrays.copyOf(digest, 31)));
        assertThrows(IllegalArgumentException.class, () ->
                new SignerPort.SolanaMessageCommand(command.context(), digest));

        SigningRequest.KeyContext solanaContext = signer.resolve(
                solana.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.ED25519, SettlementNetwork.SOLANA);
        assertThrows(IllegalArgumentException.class, () -> persistedRequest(
                solanaContext, SigningRequest.Mode.EVM_DIGEST,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM,
                digest, "evm-provider-crossed"));
        assertEquals(0, signer.signingInvocationCount());
    }

    @Test
    void signsExactSolanaMessageWithJdkEd25519AndDefensiveCopies() throws Exception {
        LocalEphemeralSigner signer = signer(128);
        LocalEphemeralSigner.KeyMetadata key = key(signer, SigningRequest.Algorithm.ED25519);
        byte[] message = "exact-solana-message".getBytes(StandardCharsets.US_ASCII);
        byte[] original = message.clone();
        SignerPort.SolanaMessageCommand command =
                solanaCommand(signer, key, message, "solana-provider-1");
        message[0] ^= 1;

        SignerPort.Signed result = assertInstanceOf(
                SignerPort.Signed.class, signer.signSolanaMessage(command));
        byte[] first = result.signature();
        byte[] second = result.signature();
        first[0] ^= 1;

        assertEquals("LOCAL_SOLANA_ED25519_V1", result.encoding());
        assertEquals(64, second.length);
        assertNotSame(first, second);
        PublicKey publicKey = KeyFactory.getInstance("Ed25519").generatePublic(
                new X509EncodedKeySpec(signer.publicKey(key.alias())));
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(publicKey);
        verifier.update(original);
        assertTrue(verifier.verify(second));
        verifier.initVerify(publicKey);
        verifier.update(message);
        assertFalse(verifier.verify(second));
    }

    @Test
    void rejectsEmptyAndOverPolicySolanaMessages() {
        LocalEphemeralSigner signer = signer(8);
        LocalEphemeralSigner.KeyMetadata key = key(signer, SigningRequest.Algorithm.ED25519);
        byte[] one = new byte[] {1};
        SignerPort.SolanaMessageCommand oneByte =
                solanaCommand(signer, key, one, "solana-provider-small");
        assertThrows(IllegalArgumentException.class, () ->
                new SignerPort.SolanaMessageCommand(oneByte.context(), new byte[0]));

        byte[] tooLong = bytes(9, 4);
        SignerPort.ProviderResult result = signer.signSolanaMessage(
                solanaCommand(signer, key, tooLong, "solana-provider-large"));
        SignerPort.Denied denied = assertInstanceOf(SignerPort.Denied.class, result);
        assertEquals("local-message-too-large", denied.safeCode());
        assertEquals(0, signer.signingInvocationCount());
    }

    @Test
    void stableProviderIdentityReplaysLocallyAndConflictsOnChangedContext() {
        LocalEphemeralSigner signer = signer(128);
        LocalEphemeralSigner.KeyMetadata key = key(signer, SigningRequest.Algorithm.SECP256K1);
        byte[] digest = bytes(32, 5);
        SignerPort.EvmDigestCommand first = evmCommand(signer, key, digest, "stable-provider");
        SignerPort.Signed signed = assertInstanceOf(
                SignerPort.Signed.class, signer.signEvmDigest(first));
        SignerPort.Signed replay = assertInstanceOf(
                SignerPort.Signed.class, signer.signEvmDigest(first));

        assertArrayEquals(signed.signature(), replay.signature());
        assertEquals(1, signer.signingInvocationCount());

        byte[] changed = bytes(32, 6);
        SignerPort.EvmDigestCommand conflict = evmCommand(
                signer, key, changed, "stable-provider");
        assertInstanceOf(SignerPort.Conflict.class, signer.signEvmDigest(conflict));
        assertEquals(1, signer.signingInvocationCount());
    }

    @Test
    void inquiryRecoversAmbiguousSignatureAndNoSignatureIsExplicit() {
        LocalEphemeralSigner signer = signer(128);
        LocalEphemeralSigner.KeyMetadata key = key(signer, SigningRequest.Algorithm.SECP256K1);
        SignerPort.EvmDigestCommand ambiguous = evmCommand(
                signer, key, bytes(32, 8), "ambiguous-provider");
        signer.nextOutcomeForTest(LocalEphemeralSigner.TestOutcome.AMBIGUOUS_AFTER_SIGNATURE);

        assertInstanceOf(SignerPort.Ambiguous.class, signer.signEvmDigest(ambiguous));
        assertEquals(1, signer.signingInvocationCount());
        SignerPort.Signed recovered = assertInstanceOf(
                SignerPort.Signed.class,
                signer.inquire(new SignerPort.Inquiry(
                        ambiguous.context(), ambiguous.digest())));
        assertEquals(65, recovered.signature().length);
        assertEquals(1, signer.signingInvocationCount());

        SignerPort.EvmDigestCommand noSignature = evmCommand(
                signer, key, bytes(32, 9), "no-signature-provider");
        signer.nextOutcomeForTest(LocalEphemeralSigner.TestOutcome.RETRYABLE_NO_SIGNATURE);
        SignerPort.RetryableNoSignature failure = assertInstanceOf(
                SignerPort.RetryableNoSignature.class, signer.signEvmDigest(noSignature));
        assertEquals("local-no-signature", failure.safeCode());
        assertEquals(1, signer.signingInvocationCount());
    }

    @Test
    void restartChangesAliasesAndVersionsAndStaleSessionFailsClosed() {
        LocalEphemeralSigner first = signer(128);
        LocalEphemeralSigner.KeyMetadata oldKey = key(
                first, SigningRequest.Algorithm.SECP256K1);
        SignerPort.EvmDigestCommand pending = evmCommand(
                first, oldKey, bytes(32, 10), "stale-provider");
        LocalEphemeralSigner restarted = signer(128);
        LocalEphemeralSigner.KeyMetadata newKey = key(
                restarted, SigningRequest.Algorithm.SECP256K1);

        assertNotEquals(oldKey.alias(), newKey.alias());
        assertNotEquals(oldKey.keyVersion(), newKey.keyVersion());
        assertInstanceOf(SignerPort.Conflict.class, restarted.signEvmDigest(pending));
        assertEquals(0, restarted.signingInvocationCount());
    }

    @Test
    void registryIsRoleNetworkAlgorithmBoundAndCloseDisablesKeys() {
        LocalEphemeralSigner signer = signer(128);
        LocalEphemeralSigner.KeyMetadata evm = key(signer, SigningRequest.Algorithm.SECP256K1);
        SigningRequest.KeyContext active = signer.resolve(
                evm.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM);

        assertEquals(SigningRequest.KeyStatus.ACTIVE, active.status());
        assertEquals(Set.of(SettlementNetwork.ETHEREUM), active.allowedNetworks());
        assertEquals(Set.of(SigningRequest.Algorithm.SECP256K1), active.allowedAlgorithms());
        assertTrue(active.registryVersion().startsWith("local-ephemeral-v1:"));
        assertFalse(evm.publicKeyFingerprint().isBlank());
        assertEquals("LOCAL_EPHEMERAL", evm.classification());

        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                evm.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.ED25519, SettlementNetwork.ETHEREUM).status());
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                evm.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.SOLANA).status());
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                new KeyAlias("local-ephemeral:unknown"),
                SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());

        LocalEphemeralSigner limited = new LocalEphemeralSigner(
                new LocalEphemeralSigner.Configuration(
                        Set.of(SigningRequest.KeyRole.MINT_AUTHORITY),
                        Set.of(SigningRequest.KeyRole.BURN_AUTHORITY), 128),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
        LocalEphemeralSigner.KeyMetadata limitedEvm = key(
                limited, SigningRequest.Algorithm.SECP256K1);
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, limited.resolve(
                limitedEvm.alias(), SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());

        signer.close();
        assertTrue(signer.keys().isEmpty());
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                evm.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());
    }

    private static LocalEphemeralSigner signer(int maxSolanaMessageBytes) {
        return new LocalEphemeralSigner(
                new LocalEphemeralSigner.Configuration(
                        Set.of(SigningRequest.KeyRole.MINT_AUTHORITY,
                                SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                SigningRequest.KeyRole.BURN_AUTHORITY),
                        Set.of(SigningRequest.KeyRole.MINT_AUTHORITY,
                                SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                SigningRequest.KeyRole.BURN_AUTHORITY),
                        maxSolanaMessageBytes),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    private static LocalEphemeralSigner.KeyMetadata key(
            LocalEphemeralSigner signer, SigningRequest.Algorithm algorithm) {
        return signer.keys().stream().filter(key -> key.algorithm() == algorithm)
                .findFirst().orElseThrow();
    }

    private static SignerPort.EvmDigestCommand evmCommand(
            LocalEphemeralSigner signer,
            LocalEphemeralSigner.KeyMetadata key,
            byte[] digest,
            String providerId) {
        SigningRequest.KeyContext context = signer.resolve(
                key.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM);
        SigningRequest request = persistedRequest(
                context, SigningRequest.Mode.EVM_DIGEST,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM,
                digest, providerId);
        return new SignerPort.EvmDigestCommand(context(request), digest);
    }

    private static SignerPort.SolanaMessageCommand solanaCommand(
            LocalEphemeralSigner signer,
            LocalEphemeralSigner.KeyMetadata key,
            byte[] message,
            String providerId) {
        SigningRequest.KeyContext context = signer.resolve(
                key.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.ED25519, SettlementNetwork.SOLANA);
        SigningRequest request = persistedRequest(
                context, SigningRequest.Mode.SOLANA_MESSAGE,
                SigningRequest.Algorithm.ED25519, SettlementNetwork.SOLANA,
                message, providerId);
        return new SignerPort.SolanaMessageCommand(context(request), message);
    }

    private static SignerPort.ProviderContext context(SigningRequest request) {
        SigningRequest.Attempt attempt = request.attempts().getLast();
        return new SignerPort.ProviderContext(
                request, attempt.attemptId(), attempt.providerRequestId());
    }

    private static SigningRequest persistedRequest(
            SigningRequest.KeyContext key,
            SigningRequest.Mode mode,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network,
            byte[] material,
            String providerId) {
        String payloadDigest = sha256(material);
        SigningRequest request = SigningRequest.requested(
                new SigningRequestId(UUID.nameUUIDFromBytes(
                        (providerId + payloadDigest).getBytes(StandardCharsets.UTF_8))),
                new SigningRequest.Correlation(
                        new OperationId(UUID.nameUUIDFromBytes(("op-" + providerId)
                                .getBytes(StandardCharsets.UTF_8))),
                        new AttemptId(UUID.nameUUIDFromBytes(("attempt-" + providerId)
                                .getBytes(StandardCharsets.UTF_8))),
                        Optional.empty(), Optional.empty()),
                Optional.empty(),
                new SigningRequest.PayloadIdentity(
                        mode, algorithm, payloadDigest, material.length,
                        mode == SigningRequest.Mode.EVM_DIGEST
                                ? SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST
                                : SigningRequest.PayloadEncoding.SOLANA_SERIALIZED_MESSAGE),
                key,
                new SigningRequest.AuthorityContext(
                        SigningRequest.Action.MINT, network,
                        TokenQuantity.parse("1",
                                new AssetUnit("USD_STABLE", "USD", 1, 2,
                                        new BigInteger("1000000000000"))),
                        "local-source", "local-destination", "local-native-action",
                        sha256("lifetime".getBytes(StandardCharsets.UTF_8)), "0",
                        sha256("constraint".getBytes(StandardCharsets.UTF_8)),
                        "local-policy-v1", List.of(new EvidenceRef("local:approval")),
                        NOW, NOW.plusSeconds(60)),
                1, sha256(("intent-" + providerId).getBytes(StandardCharsets.UTF_8)),
                1, sha256(("request-" + providerId).getBytes(StandardCharsets.UTF_8)),
                NOW, new EvidenceRef("local:requested"));
        request = request.awaitAuthorization(
                request.version(), NOW.plusNanos(1_000),
                new EvidenceRef("local:awaiting"));
        request = request.authorize(
                request.version(), NOW.plusNanos(2_000),
                new EvidenceRef("local:authorized"));
        return request.persistProviderRequest(
                request.version(),
                new SigningAttemptId(UUID.nameUUIDFromBytes(("signing-" + providerId)
                        .getBytes(StandardCharsets.UTF_8))),
                new ProviderRequestId(providerId), NOW.plusNanos(3_000),
                new EvidenceRef("local:provider-request"));
    }

    private static boolean verifies(
            ECPoint publicPoint, byte[] digest, BigInteger r, BigInteger s) {
        ECDSASigner verifier = new ECDSASigner();
        verifier.init(false, new ECPublicKeyParameters(publicPoint, EVM_DOMAIN));
        return verifier.verifySignature(digest, r, s);
    }

    private static ECPoint recover(
            byte[] digest, BigInteger r, BigInteger s, int recoveryId) {
        BigInteger n = SECP256K1.getN();
        BigInteger x = r.add(n.multiply(BigInteger.valueOf(recoveryId / 2L)));
        if (x.compareTo(SECP256K1.getCurve().getField().getCharacteristic()) >= 0) {
            throw new IllegalArgumentException("recovery x is outside the field");
        }
        byte[] compressed = new byte[33];
        compressed[0] = (byte) ((recoveryId & 1) == 0 ? 0x02 : 0x03);
        byte[] encodedX = fixed32(x);
        System.arraycopy(encodedX, 0, compressed, 1, encodedX.length);
        ECPoint point = SECP256K1.getCurve().decodePoint(compressed);
        if (!point.multiply(n).isInfinity()) {
            throw new IllegalArgumentException("recovery point has invalid order");
        }
        BigInteger e = new BigInteger(1, digest);
        BigInteger inverseR = r.modInverse(n);
        BigInteger eInverse = e.negate().mod(n).multiply(inverseR).mod(n);
        BigInteger sOverR = s.multiply(inverseR).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(
                SECP256K1.getG(), eInverse, point, sOverR).normalize();
    }

    private static BigInteger unsigned(byte[] signature, int offset) {
        return new BigInteger(1, Arrays.copyOfRange(signature, offset, offset + 32));
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int copy = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - copy, result, result.length - copy, copy);
        return result;
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }
}
