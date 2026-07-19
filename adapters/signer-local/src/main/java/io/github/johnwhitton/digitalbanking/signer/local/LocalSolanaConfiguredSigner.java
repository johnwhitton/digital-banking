package io.github.johnwhitton.digitalbanking.signer.local;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.EdECPoint;
import java.security.spec.EdECPrivateKeySpec;
import java.security.spec.EdECPublicKeySpec;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;

/** Local-only Ed25519 signer isolated from the Solana SDK and application layer. */
public final class LocalSolanaConfiguredSigner
        implements SignerPort, SigningKeyRegistry, AutoCloseable {

    public static final String SIGNATURE_ENCODING = "solana-ed25519-64-byte";
    private static final Instant VALID_FROM = Instant.EPOCH;
    private static final Set<SigningRequest.KeyRole> SUPPORTED_ROLES = Set.of(
            SigningRequest.KeyRole.FEE_PAYER,
            SigningRequest.KeyRole.MINT_AUTHORITY,
            SigningRequest.KeyRole.TRANSFER_AUTHORITY);
    private static final Set<SigningRequest.Algorithm> ALGORITHMS = Set.of(
            SigningRequest.Algorithm.ED25519);
    private static final Set<SettlementNetwork> NETWORKS = Set.of(
            SettlementNetwork.SOLANA);

    private final Map<KeyAlias, ActiveKey> keys;
    private final Path resultsRoot;
    private final Map<ProviderRequestId, RecordedResult> results =
            new ConcurrentHashMap<>();

    public LocalSolanaConfiguredSigner(Configuration configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Path root = validatedRoot(configuration.runtimeRoot());
        resultsRoot = validatedResultsRoot(root);
        EnumMap<SigningRequest.KeyRole, Boolean> roles =
                new EnumMap<>(SigningRequest.KeyRole.class);
        java.util.LinkedHashMap<KeyAlias, ActiveKey> loaded = new java.util.LinkedHashMap<>();
        try {
            for (ConfiguredKey configured : configuration.keys()) {
                if (!SUPPORTED_ROLES.contains(configured.role())
                        || roles.putIfAbsent(configured.role(), Boolean.TRUE) != null) {
                    throw new IllegalArgumentException(
                            "local Solana signer roles must be unique and supported");
                }
                ActiveKey key = load(root, configured);
                if (loaded.putIfAbsent(configured.alias(), key) != null) {
                    key.destroy();
                    throw new IllegalArgumentException("local Solana key alias is duplicated");
                }
            }
            if (!roles.keySet().equals(SUPPORTED_ROLES)) {
                throw new IllegalArgumentException(
                        "fee-payer, mint-authority, and transfer-authority keys are required");
            }
            keys = Map.copyOf(loaded);
        } catch (RuntimeException failure) {
            loaded.values().forEach(ActiveKey::destroy);
            throw failure;
        }
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
        ActiveKey key = keys.get(alias);
        if (key == null || key.destroyed() || key.role() != role
                || algorithm != SigningRequest.Algorithm.ED25519
                || network != SettlementNetwork.SOLANA) {
            return missing(alias, role, algorithm, network);
        }
        return new SigningRequest.KeyContext(
                alias, key.registryVersion(), Optional.of(key.keyVersion()), role,
                algorithm, network, SigningRequest.KeyStatus.ACTIVE,
                Set.of(role), ALGORITHMS, NETWORKS, VALID_FROM, Optional.empty());
    }

    @Override
    public ProviderResult signEvmDigest(EvmDigestCommand command) {
        Objects.requireNonNull(command, "command");
        return new Denied("local-solana-ed25519-only",
                evidence(command.context(), "unsupported", null));
    }

    @Override
    public synchronized ProviderResult signSolanaMessage(SolanaMessageCommand command) {
        Objects.requireNonNull(command, "command");
        ProviderContext context = command.context();
        ActiveKey key = keys.get(context.request().keyContext().alias());
        if (!matches(context.request(), key)) {
            return conflict(context, "local-solana-key-context-mismatch", key);
        }
        String binding = binding(context);
        RecordedResult retained = results.get(context.providerRequestId());
        if (retained != null) {
            return retained.binding().equals(binding)
                    ? retained.result()
                    : conflict(context, "local-solana-provider-identity-conflict", key);
        }
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key.privateKey());
            signer.update(command.message());
            byte[] signature = signer.sign();
            if (signature.length != 64 || !verify(key.publicKey(), command.message(), signature)) {
                throw new IllegalStateException("local Solana signature verification failed");
            }
            ProviderResult result = new Signed(
                    signature, SIGNATURE_ENCODING,
                    evidence(context, "signed", key),
                    SigningRequest.EvidenceOrigin.PROVIDER);
            persistResult(context, binding, signature);
            results.put(context.providerRequestId(), new RecordedResult(binding, result));
            return result;
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Ed25519 signing is unavailable", failure);
        }
    }

    @Override
    public synchronized ProviderResult inquire(Inquiry command) {
        Objects.requireNonNull(command, "command");
        ProviderContext context = command.context();
        ActiveKey key = keys.get(context.request().keyContext().alias());
        if (!matches(context.request(), key)) {
            return conflict(context, "local-solana-key-context-mismatch", key);
        }
        RecordedResult retained = results.get(context.providerRequestId());
        if (retained == null) {
            retained = readResult(context, key, binding(context), command.signableMaterial());
            if (retained == null) {
                return new Conflict("local-solana-provider-outcome-unknown",
                        evidence(context, "unknown", key));
            }
            results.put(context.providerRequestId(), retained);
        }
        return retained.binding().equals(binding(context))
                ? retained.result()
                : conflict(context, "local-solana-provider-identity-conflict", key);
    }

    @Override
    public void close() {
        keys.values().forEach(ActiveKey::destroy);
        results.clear();
    }

    private static boolean matches(SigningRequest request, ActiveKey key) {
        if (key == null || key.destroyed()
                || request.payloadIdentity().mode() != SigningRequest.Mode.SOLANA_MESSAGE
                || request.payloadIdentity().algorithm() != SigningRequest.Algorithm.ED25519
                || request.authorityContext().network() != SettlementNetwork.SOLANA
                || request.keyContext().role() != key.role()
                || !actionMatches(key.role(), request.authorityContext().action())
                || !request.authorityContext().sourceReference().equals(key.publicKeyBase58())) {
            return false;
        }
        SigningRequest.KeyContext context = request.keyContext();
        return context.alias().equals(key.alias())
                && context.registryVersion().equals(key.registryVersion())
                && context.keyVersion().equals(Optional.of(key.keyVersion()))
                && context.status() == SigningRequest.KeyStatus.ACTIVE
                && context.allowedRoles().equals(Set.of(key.role()))
                && context.allowedAlgorithms().equals(ALGORITHMS)
                && context.allowedNetworks().equals(NETWORKS);
    }

    private static boolean actionMatches(
            SigningRequest.KeyRole role, SigningRequest.Action action) {
        return switch (role) {
            case FEE_PAYER -> action == SigningRequest.Action.MINT
                    || action == SigningRequest.Action.TRANSFER;
            case MINT_AUTHORITY -> action == SigningRequest.Action.MINT;
            case TRANSFER_AUTHORITY -> action == SigningRequest.Action.TRANSFER;
            case BURN_AUTHORITY -> false;
        };
    }

    private static ActiveKey load(Path root, ConfiguredKey configured) {
        try {
            Path supplied = configured.keypairFile().toAbsolutePath().normalize();
            Path path = supplied.getParent().toRealPath().resolve(supplied.getFileName());
            Path parent = path.getParent().toRealPath();
            if (!path.startsWith(root) || Files.isSymbolicLink(supplied)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalArgumentException(
                        "local Solana key file must be a regular file under the runtime root");
            }
            if (!parent.startsWith(root)
                    || !Files.getPosixFilePermissions(parent).equals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE))
                    || !Files.getPosixFilePermissions(path).equals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE))) {
                throw new IllegalArgumentException(
                        "local Solana key file must be private mode 0600");
            }
            byte[] json = Files.readAllBytes(path);
            byte[] keypair = null;
            try {
                if (json.length == 0 || json.length > 4096) {
                    throw new IllegalArgumentException("local Solana key file is invalid");
                }
                keypair = parseKeypair(json);
            } finally {
                Arrays.fill(json, (byte) 0);
            }
            try {
                byte[] seed = Arrays.copyOfRange(keypair, 0, 32);
                byte[] publicBytes = Arrays.copyOfRange(keypair, 32, 64);
                try {
                    KeyFactory factory = KeyFactory.getInstance("Ed25519");
                    PrivateKey privateKey = factory.generatePrivate(
                            new EdECPrivateKeySpec(NamedParameterSpec.ED25519, seed));
                    PublicKey publicKey = factory.generatePublic(new EdECPublicKeySpec(
                            NamedParameterSpec.ED25519, point(publicBytes)));
                    String address = base58(publicBytes);
                    if (!address.equals(configured.expectedPublicKey())) {
                        throw new IllegalArgumentException(
                                "local Solana key does not match its expected public identity");
                    }
                    verifyPair(privateKey, publicKey);
                    String fingerprint = sha256(publicBytes);
                    String registryVersion = "local-solana-registry-v1:"
                            + sha256((configured.alias().value() + "\n" + configured.role()
                                    + "\n" + configured.keyVersion() + "\n" + address)
                                    .getBytes(StandardCharsets.UTF_8)).substring(0, 32);
                    return new ActiveKey(
                            configured.alias(), configured.role(), configured.keyVersion(),
                            address, registryVersion, fingerprint, privateKey, publicKey);
                } finally {
                    Arrays.fill(seed, (byte) 0);
                    Arrays.fill(publicBytes, (byte) 0);
                }
            } finally {
                Arrays.fill(keypair, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalArgumentException("local Solana key cannot be loaded", failure);
        }
    }

    private static Path validatedRoot(Path configured) {
        Objects.requireNonNull(configured, "runtimeRoot");
        try {
            Path normalized = configured.toAbsolutePath().normalize();
            if (Files.isSymbolicLink(normalized)
                    || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(normalized).equals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE))) {
                throw new IllegalArgumentException(
                        "local Solana runtime root must be private mode 0700");
            }
            return normalized.toRealPath();
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "local Solana runtime root cannot be validated", failure);
        }
    }

    private static Path validatedResultsRoot(Path root) {
        Path results = root.resolve("signing-results");
        try {
            if (!Files.exists(results, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectory(results);
                Files.setPosixFilePermissions(results, Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE));
            }
            if (Files.isSymbolicLink(results)
                    || !Files.isDirectory(results, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(results).equals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE,
                            PosixFilePermission.OWNER_EXECUTE))) {
                throw new IllegalArgumentException(
                        "local Solana signing-result directory must be private mode 0700");
            }
            Path canonical = results.toRealPath();
            if (!canonical.getParent().equals(root)) {
                throw new IllegalArgumentException(
                        "local Solana signing-result directory escaped its runtime root");
            }
            return canonical;
        } catch (IOException failure) {
            throw new IllegalArgumentException(
                    "local Solana signing-result directory cannot be validated", failure);
        }
    }

    private void persistResult(
            ProviderContext context, String binding, byte[] signature) {
        byte[] content = resultContent(binding, signature);
        Path target = resultFile(context.providerRequestId());
        Path temporary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                requireMatchingResult(target, content);
                return;
            }
            temporary = Files.createTempFile(resultsRoot, ".result-", ".tmp");
            Files.setPosixFilePermissions(temporary, Set.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE));
            Files.write(temporary, content, StandardOpenOption.TRUNCATE_EXISTING);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                temporary = null;
            } catch (java.nio.file.FileAlreadyExistsException raced) {
                requireMatchingResult(target, content);
            }
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "local Solana signing result could not be retained", failure);
        } finally {
            Arrays.fill(content, (byte) 0);
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // The private runtime directory remains ignored and mode restricted.
                }
            }
        }
    }

    private RecordedResult readResult(
            ProviderContext context, ActiveKey key,
            String expectedBinding, byte[] signableMaterial) {
        Path path = resultFile(context.providerRequestId());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            return null;
        }
        try {
            if (Files.isSymbolicLink(path)
                    || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                    || !Files.getPosixFilePermissions(path).equals(Set.of(
                            PosixFilePermission.OWNER_READ,
                            PosixFilePermission.OWNER_WRITE))) {
                throw new IllegalStateException(
                        "local Solana signing result is not a private regular file");
            }
            byte[] content = Files.readAllBytes(path);
            try {
                if (content.length != 129 || content[64] != '\n') {
                    throw new IllegalStateException(
                            "local Solana signing result is malformed");
                }
                String retainedBinding = new String(
                        content, 0, 64, StandardCharsets.US_ASCII);
                byte[] signature = Arrays.copyOfRange(content, 65, 129);
                try {
                    if (!expectedBinding.equals(retainedBinding)
                            || !verify(key.publicKey(), signableMaterial, signature)) {
                        throw new IllegalStateException(
                                "local Solana signing result does not match its durable identity");
                    }
                    ProviderResult result = new Signed(
                            signature, SIGNATURE_ENCODING,
                            evidence(context, "recovered", key),
                            SigningRequest.EvidenceOrigin.PROVIDER);
                    return new RecordedResult(retainedBinding, result);
                } finally {
                    Arrays.fill(signature, (byte) 0);
                }
            } finally {
                Arrays.fill(content, (byte) 0);
            }
        } catch (IOException | GeneralSecurityException failure) {
            throw new IllegalStateException(
                    "local Solana signing result could not be recovered", failure);
        }
    }

    private Path resultFile(ProviderRequestId providerRequestId) {
        return resultsRoot.resolve(sha256(providerRequestId.value()
                .getBytes(StandardCharsets.UTF_8)) + ".result");
    }

    private static byte[] resultContent(String binding, byte[] signature) {
        if (!binding.matches("[0-9a-f]{64}") || signature.length != 64) {
            throw new IllegalArgumentException("local Solana signing result is invalid");
        }
        byte[] content = new byte[129];
        System.arraycopy(binding.getBytes(StandardCharsets.US_ASCII), 0, content, 0, 64);
        content[64] = '\n';
        System.arraycopy(signature, 0, content, 65, 64);
        return content;
    }

    private static void requireMatchingResult(Path target, byte[] expected)
            throws IOException {
        if (Files.isSymbolicLink(target)
                || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                || !Files.getPosixFilePermissions(target).equals(Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE))) {
            throw new IllegalStateException(
                    "local Solana signing result is not a private regular file");
        }
        byte[] retained = Files.readAllBytes(target);
        try {
            if (!MessageDigest.isEqual(retained, expected)) {
                throw new IllegalStateException(
                        "local Solana signing result conflicts with durable provider identity");
            }
        } finally {
            Arrays.fill(retained, (byte) 0);
        }
    }

    private static byte[] parseKeypair(byte[] json) {
        byte[] keypair = new byte[64];
        int cursor = skipWhitespace(json, 0);
        try {
            if (cursor >= json.length || json[cursor++] != '[') {
                throw new IllegalArgumentException();
            }
            for (int index = 0; index < keypair.length; index++) {
                cursor = skipWhitespace(json, cursor);
                int value = 0;
                int digits = 0;
                while (cursor < json.length && json[cursor] >= '0'
                        && json[cursor] <= '9') {
                    value = value * 10 + json[cursor++] - '0';
                    digits++;
                    if (digits > 3 || value > 255) {
                        throw new IllegalArgumentException();
                    }
                }
                if (digits == 0) {
                    throw new IllegalArgumentException();
                }
                keypair[index] = (byte) value;
                cursor = skipWhitespace(json, cursor);
                byte separator = index == keypair.length - 1 ? (byte) ']' : (byte) ',';
                if (cursor >= json.length || json[cursor++] != separator) {
                    throw new IllegalArgumentException();
                }
            }
            if (skipWhitespace(json, cursor) != json.length) {
                throw new IllegalArgumentException();
            }
            return keypair;
        } catch (IllegalArgumentException invalid) {
            Arrays.fill(keypair, (byte) 0);
            throw new IllegalArgumentException("local Solana key file is invalid");
        }
    }

    private static int skipWhitespace(byte[] bytes, int cursor) {
        while (cursor < bytes.length && (bytes[cursor] == ' ' || bytes[cursor] == '\t'
                || bytes[cursor] == '\r' || bytes[cursor] == '\n')) {
            cursor++;
        }
        return cursor;
    }

    private static EdECPoint point(byte[] compressed) {
        byte[] yLittleEndian = compressed.clone();
        boolean xOdd = (yLittleEndian[31] & 0x80) != 0;
        yLittleEndian[31] &= 0x7f;
        reverse(yLittleEndian);
        return new EdECPoint(xOdd, new BigInteger(1, yLittleEndian));
    }

    private static void verifyPair(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        byte[] probe = "digital-banking-local-solana-key-check"
                .getBytes(StandardCharsets.US_ASCII);
        Signature signer = Signature.getInstance("Ed25519");
        signer.initSign(privateKey);
        signer.update(probe);
        if (!verify(publicKey, probe, signer.sign())) {
            throw new IllegalArgumentException(
                    "local Solana private and public key bytes do not match");
        }
    }

    private static boolean verify(PublicKey key, byte[] message, byte[] signature)
            throws GeneralSecurityException {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(key);
        verifier.update(message);
        return verifier.verify(signature);
    }

    private static SigningRequest.KeyContext missing(
            KeyAlias alias, SigningRequest.KeyRole role,
            SigningRequest.Algorithm algorithm, SettlementNetwork network) {
        return new SigningRequest.KeyContext(
                alias, "local-solana-registry-v1:not-found", Optional.empty(), role,
                algorithm, network, SigningRequest.KeyStatus.NOT_FOUND,
                Set.of(), Set.of(), Set.of(), VALID_FROM, Optional.empty());
    }

    private static ProviderResult conflict(
            ProviderContext context, String code, ActiveKey key) {
        return new Conflict(code, evidence(context, "conflict", key));
    }

    private static EvidenceRef evidence(
            ProviderContext context, String outcome, ActiveKey key) {
        String fingerprint = key == null ? "unresolved" : key.fingerprint().substring(0, 16);
        String identity = sha256((context.providerRequestId().value() + ":"
                + context.request().requestDigest()).getBytes(StandardCharsets.UTF_8))
                .substring(0, 24);
        return new EvidenceRef("internal:local-solana-signer:v1:" + outcome
                + ":ed25519:" + fingerprint + ":" + identity);
    }

    private static String binding(ProviderContext context) {
        SigningRequest request = context.request();
        return sha256((context.providerRequestId().value() + "\n"
                + context.attemptId().value() + "\n" + request.requestId().value()
                + "\n" + request.requestDigest() + "\n"
                + request.payloadIdentity().sha256() + "\n"
                + request.keyContext().registryVersion() + "\n"
                + request.keyContext().keyVersion().orElse("missing"))
                .getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static String base58(byte[] value) {
        if (value.length == 0) {
            return "";
        }
        byte[] input = value.clone();
        char[] encoded = new char[value.length * 2];
        int output = encoded.length;
        int zeros = 0;
        while (zeros < input.length && input[zeros] == 0) {
            zeros++;
        }
        int start = zeros;
        while (start < input.length) {
            int remainder = 0;
            for (int index = start; index < input.length; index++) {
                int digit = Byte.toUnsignedInt(input[index]);
                int temporary = remainder * 256 + digit;
                input[index] = (byte) (temporary / 58);
                remainder = temporary % 58;
            }
            encoded[--output] = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz"
                    .charAt(remainder);
            while (start < input.length && input[start] == 0) {
                start++;
            }
        }
        while (zeros-- > 0) {
            encoded[--output] = '1';
        }
        Arrays.fill(input, (byte) 0);
        return new String(encoded, output, encoded.length - output);
    }

    private static void reverse(byte[] value) {
        for (int low = 0, high = value.length - 1; low < high; low++, high--) {
            byte swap = value[low];
            value[low] = value[high];
            value[high] = swap;
        }
    }

    public record Configuration(Path runtimeRoot, List<ConfiguredKey> keys) {
        public Configuration {
            Objects.requireNonNull(runtimeRoot, "runtimeRoot");
            keys = List.copyOf(keys);
        }
    }

    public record ConfiguredKey(
            KeyAlias alias,
            SigningRequest.KeyRole role,
            Path keypairFile,
            String expectedPublicKey,
            String keyVersion) {
        public ConfiguredKey {
            Objects.requireNonNull(alias, "alias");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(keypairFile, "keypairFile");
            if (expectedPublicKey == null
                    || !expectedPublicKey.matches("[1-9A-HJ-NP-Za-km-z]{32,44}")) {
                throw new IllegalArgumentException("expected Solana public key is invalid");
            }
            if (keyVersion == null || keyVersion.isBlank() || keyVersion.length() > 256) {
                throw new IllegalArgumentException("local Solana key version is invalid");
            }
        }
    }

    private record RecordedResult(String binding, ProviderResult result) {
    }

    private static final class ActiveKey {
        private final KeyAlias alias;
        private final SigningRequest.KeyRole role;
        private final String keyVersion;
        private final String publicKeyBase58;
        private final String registryVersion;
        private final String fingerprint;
        private PrivateKey privateKey;
        private PublicKey publicKey;

        private ActiveKey(
                KeyAlias alias, SigningRequest.KeyRole role, String keyVersion,
                String publicKeyBase58, String registryVersion, String fingerprint,
                PrivateKey privateKey, PublicKey publicKey) {
            this.alias = alias;
            this.role = role;
            this.keyVersion = keyVersion;
            this.publicKeyBase58 = publicKeyBase58;
            this.registryVersion = registryVersion;
            this.fingerprint = fingerprint;
            this.privateKey = privateKey;
            this.publicKey = publicKey;
        }

        KeyAlias alias() { return alias; }
        SigningRequest.KeyRole role() { return role; }
        String keyVersion() { return keyVersion; }
        String publicKeyBase58() { return publicKeyBase58; }
        String registryVersion() { return registryVersion; }
        String fingerprint() { return fingerprint; }
        PrivateKey privateKey() { return privateKey; }
        PublicKey publicKey() { return publicKey; }
        boolean destroyed() { return privateKey == null; }

        void destroy() {
            privateKey = null;
            publicKey = null;
        }
    }
}
