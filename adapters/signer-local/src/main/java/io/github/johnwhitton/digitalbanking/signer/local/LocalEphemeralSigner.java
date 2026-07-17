package io.github.johnwhitton.digitalbanking.signer.local;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.generators.ECKeyPairGenerator;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECKeyGenerationParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ParametersWithRandom;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;

/**
 * Explicitly local-development signer. Keys live only in this object and are never
 * exported through the application ports or persisted.
 */
public final class LocalEphemeralSigner
        implements SignerPort, SigningKeyRegistry, AutoCloseable {

    public static final String CLASSIFICATION = "LOCAL_EPHEMERAL";
    public static final String EVM_ENCODING = "LOCAL_EVM_SECP256K1_RSV_LOW_S_V1";
    public static final String SOLANA_ENCODING = "LOCAL_SOLANA_ED25519_V1";

    private static final X9ECParameters SECP256K1 =
            CustomNamedCurves.getByName("secp256k1");
    private static final ECDomainParameters EVM_DOMAIN = new ECDomainParameters(
            SECP256K1.getCurve(), SECP256K1.getG(), SECP256K1.getN(),
            SECP256K1.getH());

    private final Configuration configuration;
    private final Clock clock;
    private final SecureRandom random;
    private final AtomicReference<KeySet> keySet;
    private final Map<ProviderRequestId, RecordedResult> providerResults =
            new ConcurrentHashMap<>();
    private final AtomicLong signingInvocations = new AtomicLong();
    private TestOutcome nextOutcome = TestOutcome.NORMAL;

    public LocalEphemeralSigner(
            Configuration configuration, Clock clock, SecureRandom random) {
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        this.keySet = new AtomicReference<>(generateKeys());
    }

    public List<KeyMetadata> keys() {
        KeySet current = keySet.get();
        return current == null
                ? List.of()
                : List.of(current.evm.metadata(), current.solana.metadata());
    }

    /** Returns a defensive copy of non-secret public-key encoding. */
    public byte[] publicKey(KeyAlias alias) {
        KeyMaterial key = activeKey(alias);
        if (key == null) {
            throw new IllegalArgumentException("local key alias is not active");
        }
        return key.publicKey().clone();
    }

    @Override
    public SigningRequest.KeyContext resolve(
            KeyAlias alias,
            SigningRequest.KeyRole role,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network) {
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(algorithm, "algorithm");
        Objects.requireNonNull(network, "network");
        KeyMaterial key = activeKey(alias);
        if (key == null || key.metadata().algorithm() != algorithm
                || key.metadata().network() != network
                || !key.metadata().roles().contains(role)) {
            return missing(alias, role, algorithm, network);
        }
        KeyMetadata metadata = key.metadata();
        return new SigningRequest.KeyContext(
                alias, metadata.registryVersion(), Optional.of(metadata.keyVersion()), role,
                algorithm, network, SigningRequest.KeyStatus.ACTIVE, metadata.roles(),
                Set.of(algorithm), Set.of(network), metadata.createdAt(), Optional.empty());
    }

    @Override
    public synchronized ProviderResult signEvmDigest(EvmDigestCommand command) {
        Objects.requireNonNull(command, "command");
        return sign(
                command.context(), command.digest(), SigningRequest.Algorithm.SECP256K1,
                SettlementNetwork.ETHEREUM, EVM_ENCODING, this::signEvm);
    }

    @Override
    public synchronized ProviderResult signSolanaMessage(SolanaMessageCommand command) {
        Objects.requireNonNull(command, "command");
        if (command.message().length > configuration.maxSolanaMessageBytes()) {
            return retainOutcome(
                    command.context(), SigningRequest.Algorithm.ED25519,
                    new Denied("local-message-too-large",
                            evidence(command.context(), "message-too-large", null)));
        }
        return sign(
                command.context(), command.message(), SigningRequest.Algorithm.ED25519,
                SettlementNetwork.SOLANA, SOLANA_ENCODING, this::signSolana);
    }

    @Override
    public synchronized ProviderResult inquire(Inquiry command) {
        Objects.requireNonNull(command, "command");
        SigningRequest request = command.context().request();
        SigningRequest.Algorithm algorithm = request.payloadIdentity().algorithm();
        SettlementNetwork network = request.authorityContext().network();
        KeyMaterial active = activeKey(request.keyContext().alias());
        if (active == null || !matches(
                request.keyContext(), active, algorithm, network)) {
            return conflict(command.context(), "local-key-stale");
        }
        RecordedResult retained = providerResults.get(
                command.context().providerRequestId());
        if (retained == null) {
            return new RetryableNoSignature(
                    "local-provider-request-unknown",
                    evidence(command.context(), "unknown", null));
        }
        if (!retained.binding().equals(binding(command.context()))) {
            return conflict(command.context(), "local-provider-identity-conflict");
        }
        return retained.result();
    }

    private ProviderResult sign(
            ProviderContext context,
            byte[] material,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network,
            String encoding,
            SignatureOperation operation) {
        KeyMaterial key = activeKey(context.request().keyContext().alias());
        if (key == null || !matches(context.request().keyContext(), key, algorithm, network)) {
            return conflict(context, "local-key-stale");
        }

        String binding = binding(context);
        RecordedResult retained = providerResults.get(context.providerRequestId());
        if (retained != null) {
            return retained.binding().equals(binding)
                    ? retained.result()
                    : conflict(context, "local-provider-identity-conflict");
        }

        TestOutcome selected = consumeNextOutcome();
        if (selected == TestOutcome.RETRYABLE_NO_SIGNATURE) {
            ProviderResult result = new RetryableNoSignature(
                    "local-no-signature", evidence(context, "no-signature", key));
            providerResults.put(context.providerRequestId(),
                    new RecordedResult(binding, result));
            return result;
        }

        signingInvocations.incrementAndGet();
        byte[] signature = operation.sign(key, material);
        ProviderResult signed = new Signed(
                signature, encoding, evidence(context, "signed", key),
                SigningRequest.EvidenceOrigin.PROVIDER);
        providerResults.put(context.providerRequestId(),
                new RecordedResult(binding, signed));
        if (selected == TestOutcome.AMBIGUOUS_AFTER_SIGNATURE) {
            return new Ambiguous(evidence(context, "ambiguous", key));
        }
        return signed;
    }

    private ProviderResult retainOutcome(
            ProviderContext context,
            SigningRequest.Algorithm algorithm,
            ProviderResult outcome) {
        KeyMaterial key = activeKey(context.request().keyContext().alias());
        SettlementNetwork expected = algorithm == SigningRequest.Algorithm.SECP256K1
                ? SettlementNetwork.ETHEREUM : SettlementNetwork.SOLANA;
        if (key == null || !matches(
                context.request().keyContext(), key, algorithm, expected)) {
            return conflict(context, "local-key-stale");
        }
        String binding = binding(context);
        RecordedResult retained = providerResults.get(context.providerRequestId());
        if (retained != null) {
            return retained.binding().equals(binding)
                    ? retained.result()
                    : conflict(context, "local-provider-identity-conflict");
        }
        providerResults.put(context.providerRequestId(),
                new RecordedResult(binding, outcome));
        return outcome;
    }

    private byte[] signEvm(KeyMaterial key, byte[] digest) {
        EcdsaKey evm = (EcdsaKey) key;
        ECDSASigner signer = new ECDSASigner();
        signer.init(true, new ParametersWithRandom(evm.privateKey(), random));
        BigInteger[] components = signer.generateSignature(digest);
        BigInteger r = components[0];
        BigInteger s = components[1];
        BigInteger halfOrder = SECP256K1.getN().shiftRight(1);
        if (s.compareTo(halfOrder) > 0) {
            s = SECP256K1.getN().subtract(s);
        }
        int recoveryId = recoveryId(evm.publicPoint(), digest, r, s);
        byte[] signature = new byte[65];
        System.arraycopy(fixed32(r), 0, signature, 0, 32);
        System.arraycopy(fixed32(s), 0, signature, 32, 32);
        signature[64] = (byte) recoveryId;
        return signature;
    }

    private byte[] signSolana(KeyMaterial key, byte[] message) {
        try {
            Ed25519Key solana = (Ed25519Key) key;
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(solana.privateKey(), random);
            signer.update(message);
            byte[] signature = signer.sign();
            if (signature.length != 64) {
                throw new IllegalStateException("Ed25519 provider returned invalid length");
            }
            return signature;
        } catch (Exception failure) {
            throw new IllegalStateException("local Ed25519 signing failed", failure);
        }
    }

    private KeySet generateKeys() {
        Instant createdAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        String session = HexFormat.of().formatHex(randomBytes(16));

        ECKeyPairGenerator evmGenerator = new ECKeyPairGenerator();
        evmGenerator.init(new ECKeyGenerationParameters(EVM_DOMAIN, random));
        AsymmetricCipherKeyPair evmPair = evmGenerator.generateKeyPair();
        ECPrivateKeyParameters evmPrivate =
                (ECPrivateKeyParameters) evmPair.getPrivate();
        ECPublicKeyParameters evmPublic =
                (ECPublicKeyParameters) evmPair.getPublic();
        byte[] evmPublicBytes = evmPublic.getQ().getEncoded(false);
        KeyMetadata evmMetadata = metadata(
                session, "evm", SigningRequest.Algorithm.SECP256K1,
                SettlementNetwork.ETHEREUM, configuration.evmRoles(),
                evmPublicBytes, createdAt);

        try {
            KeyPairGenerator solanaGenerator = KeyPairGenerator.getInstance("Ed25519");
            solanaGenerator.initialize(NamedParameterSpec.ED25519, random);
            KeyPair solanaPair = solanaGenerator.generateKeyPair();
            byte[] solanaPublicBytes = solanaPair.getPublic().getEncoded();
            KeyMetadata solanaMetadata = metadata(
                    session, "solana", SigningRequest.Algorithm.ED25519,
                    SettlementNetwork.SOLANA, configuration.solanaRoles(),
                    solanaPublicBytes, createdAt);
            return new KeySet(
                    new EcdsaKey(evmMetadata, evmPublicBytes, evmPrivate,
                            evmPublic.getQ().normalize()),
                    new Ed25519Key(solanaMetadata, solanaPublicBytes,
                            solanaPair.getPrivate()));
        } catch (Exception failure) {
            throw new IllegalStateException("JDK Ed25519 is unavailable", failure);
        }
    }

    private static KeyMetadata metadata(
            String session,
            String mode,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network,
            Set<SigningRequest.KeyRole> roles,
            byte[] publicKey,
            Instant createdAt) {
        String fingerprint = sha256(publicKey);
        return new KeyMetadata(
                new KeyAlias("local-ephemeral:" + mode + ":" + session),
                "local-ephemeral-v1:" + session,
                "local-session:" + session + ":" + fingerprint.substring(0, 16),
                algorithm, network, roles, fingerprint, createdAt, CLASSIFICATION);
    }

    private KeyMaterial activeKey(KeyAlias alias) {
        KeySet current = keySet.get();
        if (current == null) {
            return null;
        }
        if (current.evm.metadata().alias().equals(alias)) {
            return current.evm;
        }
        return current.solana.metadata().alias().equals(alias) ? current.solana : null;
    }

    private static boolean matches(
            SigningRequest.KeyContext requested,
            KeyMaterial active,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network) {
        KeyMetadata metadata = active.metadata();
        return metadata.algorithm() == algorithm
                && metadata.network() == network
                && requested.alias().equals(metadata.alias())
                && requested.registryVersion().equals(metadata.registryVersion())
                && requested.keyVersion().equals(Optional.of(metadata.keyVersion()))
                && requested.algorithm() == algorithm
                && requested.network() == network
                && requested.status() == SigningRequest.KeyStatus.ACTIVE
                && metadata.roles().contains(requested.role())
                && requested.allowedRoles().equals(metadata.roles())
                && requested.allowedAlgorithms().equals(Set.of(algorithm))
                && requested.allowedNetworks().equals(Set.of(network));
    }

    private SigningRequest.KeyContext missing(
            KeyAlias alias,
            SigningRequest.KeyRole role,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network) {
        return new SigningRequest.KeyContext(
                alias, "local-ephemeral-v1:not-found", Optional.empty(), role,
                algorithm, network, SigningRequest.KeyStatus.NOT_FOUND,
                Set.of(), Set.of(), Set.of(),
                clock.instant().truncatedTo(ChronoUnit.MICROS), Optional.empty());
    }

    private ProviderResult conflict(ProviderContext context, String safeCode) {
        return new Conflict(safeCode, evidence(context, "conflict", null));
    }

    private EvidenceRef evidence(
            ProviderContext context, String outcome, KeyMaterial key) {
        String fingerprint = key == null
                ? "unresolved" : key.metadata().publicKeyFingerprint().substring(0, 16);
        String identity = sha256((context.providerRequestId().value()
                + ":" + context.request().requestDigest())
                .getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        String algorithm = key == null
                ? context.request().payloadIdentity().algorithm().name().toLowerCase()
                : key.metadata().algorithm().name().toLowerCase();
        return new EvidenceRef("internal:local-signer:v1:" + outcome + ":"
                + algorithm + ":" + fingerprint + ":" + identity);
    }

    private static String binding(ProviderContext context) {
        SigningRequest request = context.request();
        return sha256((context.providerRequestId().value()
                + "\n" + context.attemptId().value()
                + "\n" + request.requestId().value()
                + "\n" + request.intentDigest()
                + "\n" + request.requestDigest()
                + "\n" + request.payloadIdentity().sha256()
                + "\n" + request.keyContext().registryVersion()
                + "\n" + request.keyContext().keyVersion().orElse("missing"))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static int recoveryId(
            ECPoint expected, byte[] digest, BigInteger r, BigInteger s) {
        for (int candidate = 0; candidate < 4; candidate++) {
            ECPoint recovered = recover(digest, r, s, candidate);
            if (recovered != null && recovered.normalize().equals(expected.normalize())) {
                return candidate;
            }
        }
        throw new IllegalStateException("secp256k1 recovery identity is unavailable");
    }

    private static ECPoint recover(
            byte[] digest, BigInteger r, BigInteger s, int recoveryId) {
        BigInteger n = SECP256K1.getN();
        BigInteger x = r.add(n.multiply(BigInteger.valueOf(recoveryId / 2L)));
        if (x.compareTo(SECP256K1.getCurve().getField().getCharacteristic()) >= 0) {
            return null;
        }
        byte[] compressed = new byte[33];
        compressed[0] = (byte) ((recoveryId & 1) == 0 ? 0x02 : 0x03);
        System.arraycopy(fixed32(x), 0, compressed, 1, 32);
        ECPoint point;
        try {
            point = SECP256K1.getCurve().decodePoint(compressed);
        } catch (IllegalArgumentException invalidPoint) {
            return null;
        }
        if (!point.multiply(n).isInfinity()) {
            return null;
        }
        BigInteger inverseR = r.modInverse(n);
        BigInteger eInverse = new BigInteger(1, digest)
                .negate().mod(n).multiply(inverseR).mod(n);
        BigInteger sOverR = s.multiply(inverseR).mod(n);
        return ECAlgorithms.sumOfTwoMultiplies(
                SECP256K1.getG(), eInverse, point, sOverR).normalize();
    }

    private byte[] randomBytes(int length) {
        byte[] value = new byte[length];
        random.nextBytes(value);
        return value;
    }

    private static byte[] fixed32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        int copy = Math.min(raw.length, result.length);
        System.arraycopy(raw, raw.length - copy, result, result.length - copy, copy);
        return result;
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private TestOutcome consumeNextOutcome() {
        TestOutcome selected = nextOutcome;
        nextOutcome = TestOutcome.NORMAL;
        return selected;
    }

    void nextOutcomeForTest(TestOutcome outcome) {
        nextOutcome = Objects.requireNonNull(outcome, "outcome");
    }

    long signingInvocationCount() {
        return signingInvocations.get();
    }

    @Override
    public void close() {
        KeySet removed = keySet.getAndSet(null);
        providerResults.clear();
        if (removed != null) {
            removed.solana.destroy();
        }
    }

    public record Configuration(
            Set<SigningRequest.KeyRole> evmRoles,
            Set<SigningRequest.KeyRole> solanaRoles,
            int maxSolanaMessageBytes) {

        public Configuration {
            evmRoles = Set.copyOf(Objects.requireNonNull(evmRoles, "evmRoles"));
            solanaRoles = Set.copyOf(Objects.requireNonNull(solanaRoles, "solanaRoles"));
            if (evmRoles.isEmpty() || solanaRoles.isEmpty()) {
                throw new IllegalArgumentException("local signer role allowlists are required");
            }
            if (maxSolanaMessageBytes < 1 || maxSolanaMessageBytes > 65_536) {
                throw new IllegalArgumentException(
                        "local Solana message limit must be between 1 and 65536 bytes");
            }
        }
    }

    public record KeyMetadata(
            KeyAlias alias,
            String registryVersion,
            String keyVersion,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network,
            Set<SigningRequest.KeyRole> roles,
            String publicKeyFingerprint,
            Instant createdAt,
            String classification) {

        public KeyMetadata {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(registryVersion, "registryVersion");
            Objects.requireNonNull(keyVersion, "keyVersion");
            Objects.requireNonNull(algorithm, "algorithm");
            Objects.requireNonNull(network, "network");
            roles = Set.copyOf(roles);
            Objects.requireNonNull(publicKeyFingerprint, "publicKeyFingerprint");
            Objects.requireNonNull(createdAt, "createdAt");
            Objects.requireNonNull(classification, "classification");
        }

        @Override
        public String toString() {
            return "KeyMetadata[algorithm=" + algorithm + ", network=" + network
                    + ", classification=" + classification + ", alias=[REDACTED]]";
        }
    }

    enum TestOutcome {
        NORMAL,
        RETRYABLE_NO_SIGNATURE,
        AMBIGUOUS_AFTER_SIGNATURE
    }

    @FunctionalInterface
    private interface SignatureOperation {
        byte[] sign(KeyMaterial key, byte[] material);
    }

    private sealed interface KeyMaterial permits EcdsaKey, Ed25519Key {
        KeyMetadata metadata();
        byte[] publicKey();
    }

    private record EcdsaKey(
            KeyMetadata metadata,
            byte[] publicKey,
            ECPrivateKeyParameters privateKey,
            ECPoint publicPoint) implements KeyMaterial {

        private EcdsaKey {
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }
    }

    private record Ed25519Key(
            KeyMetadata metadata,
            byte[] publicKey,
            PrivateKey privateKey) implements KeyMaterial {

        private Ed25519Key {
            publicKey = publicKey.clone();
        }

        @Override
        public byte[] publicKey() {
            return publicKey.clone();
        }

        private void destroy() {
            if (privateKey instanceof Destroyable destroyable) {
                try {
                    destroyable.destroy();
                } catch (DestroyFailedException ignored) {
                    // Provider objects may not support physical zeroization; references are released.
                }
            }
        }
    }

    private record KeySet(EcdsaKey evm, Ed25519Key solana) { }

    private record RecordedResult(String binding, ProviderResult result) { }
}
