package io.github.johnwhitton.digitalbanking.signer.local;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;

/** Opt-in local-EVM signer backed only by startup configuration. */
public final class LocalConfiguredSigner
        implements SignerPort, SigningKeyRegistry, WalletIdentityRegistry, AutoCloseable {

    public static final String EVM_ENCODING = LocalEphemeralSigner.EVM_ENCODING;

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-fA-F]{40}");
    private static final Instant VALID_FROM = Instant.EPOCH;

    private final SecureRandom random;
    private final AtomicReference<Registry> registry;
    private final Map<ProviderRequestId, RecordedResult> providerResults =
            new ConcurrentHashMap<>();

    public LocalConfiguredSigner(Configuration configuration, SecureRandom random) {
        Configuration required = Objects.requireNonNull(configuration, "configuration");
        this.random = Objects.requireNonNull(random, "random");
        if (required.chainId() != 31337) {
            throw new IllegalArgumentException(
                    "configured local signer requires local chain ID 31337");
        }
        this.registry = new AtomicReference<>(build(required.wallets()));
    }

    @Override
    public WalletIdentity resolve(WalletReference reference) {
        Objects.requireNonNull(reference, "reference");
        Registry current = registry.get();
        ActiveWallet wallet = current == null ? null : current.byReference().get(reference);
        if (wallet == null) {
            throw new IllegalArgumentException("configured wallet identity is unavailable");
        }
        return wallet.identity();
    }

    @Override
    public List<WalletIdentity> identities() {
        Registry current = registry.get();
        return current == null ? List.of() : current.identities();
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
        ActiveWallet wallet = activeWallet(alias);
        if (wallet == null || algorithm != SigningRequest.Algorithm.SECP256K1
                || network != SettlementNetwork.ETHEREUM
                || !wallet.keyRoles().contains(role)) {
            return missing(alias, role, algorithm, network);
        }
        WalletIdentity identity = wallet.identity();
        SigningRequest.KeyStatus status = identity.status() == Status.ENABLED
                ? SigningRequest.KeyStatus.ACTIVE : SigningRequest.KeyStatus.DISABLED;
        return new SigningRequest.KeyContext(
                identity.keyReference(), identity.registryVersion(),
                Optional.of(identity.keyVersion()), role, algorithm, network, status,
                wallet.keyRoles(), Set.of(SigningRequest.Algorithm.SECP256K1),
                Set.of(SettlementNetwork.ETHEREUM), VALID_FROM, Optional.empty());
    }

    @Override
    public synchronized ProviderResult signEvmDigest(EvmDigestCommand command) {
        Objects.requireNonNull(command, "command");
        ProviderContext context = command.context();
        ActiveWallet wallet = activeWallet(context.request().keyContext().alias());
        if (!matches(context.request(), wallet)) {
            return conflict(context, "local-wallet-stale");
        }
        String binding = binding(context);
        RecordedResult retained = providerResults.get(context.providerRequestId());
        if (retained != null) {
            return retained.binding().equals(binding)
                    ? retained.result()
                    : conflict(context, "local-provider-identity-conflict");
        }
        byte[] signature = wallet.key().sign(command.digest(), random);
        ProviderResult result = new Signed(
                signature, EVM_ENCODING, evidence(context, "signed", wallet),
                SigningRequest.EvidenceOrigin.PROVIDER);
        providerResults.put(context.providerRequestId(), new RecordedResult(binding, result));
        return result;
    }

    @Override
    public ProviderResult signSolanaMessage(SolanaMessageCommand command) {
        Objects.requireNonNull(command, "command");
        return new Denied(
                "local-configured-evm-only",
                evidence(command.context(), "unsupported", null));
    }

    @Override
    public synchronized ProviderResult inquire(Inquiry command) {
        Objects.requireNonNull(command, "command");
        ProviderContext context = command.context();
        ActiveWallet wallet = activeWallet(context.request().keyContext().alias());
        if (!matches(context.request(), wallet)) {
            return conflict(context, "local-wallet-stale");
        }
        RecordedResult retained = providerResults.get(context.providerRequestId());
        if (retained == null) {
            return new Conflict(
                    "local-provider-outcome-unknown",
                    evidence(context, "unknown", wallet));
        }
        if (!retained.binding().equals(binding(context))) {
            return conflict(context, "local-provider-identity-conflict");
        }
        return retained.result();
    }

    @Override
    public void close() {
        registry.set(null);
        providerResults.clear();
    }

    private Registry build(List<ConfiguredWallet> configuredWallets) {
        if (configuredWallets.isEmpty()) {
            throw new IllegalArgumentException("configured local wallets are required");
        }
        Map<WalletReference, ActiveWallet> byReference = new LinkedHashMap<>();
        Map<KeyAlias, ActiveWallet> byKey = new LinkedHashMap<>();
        Map<String, ActiveWallet> byAddress = new LinkedHashMap<>();
        List<ActiveWallet> wallets = new ArrayList<>();

        for (ConfiguredWallet configured : configuredWallets) {
            Secp256k1LocalKey key;
            char[] secret = configured.privateKey();
            try {
                key = Secp256k1LocalKey.configured(secret);
            } finally {
                Arrays.fill(secret, '\0');
                configured.destroy();
            }
            if (configured.network() != SettlementNetwork.ETHEREUM) {
                throw new IllegalArgumentException(
                        "configured local wallets support only the local EVM network");
            }
            String address = key.address();
            if (!configured.expectedAddress().isBlank()
                    && !address.equals(normalizeAddress(configured.expectedAddress()))) {
                throw new IllegalArgumentException(
                        "configured key does not match its expected local address");
            }
            Set<SigningRequest.KeyRole> roles = keyRoles(configured.allowedPurposes());
            String fingerprint = sha256(key.publicKey());
            String keyVersion = "local-demo-key-v1:" + fingerprint.substring(0, 32);
            String registryVersion = registryVersion(configured, address, keyVersion);
            WalletIdentity identity = new WalletIdentity(
                    configured.reference(), configured.aliases(), configured.ownerCategory(),
                    configured.network(), address, configured.keyReference(), registryVersion,
                    keyVersion, configured.allowedPurposes(), configured.enabled()
                            ? Status.ENABLED : Status.DISABLED);
            ActiveWallet wallet = new ActiveWallet(identity, roles, key, fingerprint);
            putUnique(byKey, configured.keyReference(), wallet, "key reference");
            putUnique(byAddress, address, wallet, "wallet address");
            putUnique(byReference, configured.reference(), wallet, "wallet reference");
            configured.aliases().forEach(alias ->
                    putUnique(byReference, alias, wallet, "wallet alias"));
            wallets.add(wallet);
        }

        List<WalletIdentity> identities = wallets.stream()
                .map(ActiveWallet::identity)
                .sorted(Comparator.comparing(value -> value.reference().value()))
                .toList();
        return new Registry(Map.copyOf(byReference), Map.copyOf(byKey), identities);
    }

    private ActiveWallet activeWallet(KeyAlias alias) {
        Registry current = registry.get();
        return current == null ? null : current.byKey().get(alias);
    }

    private boolean matches(SigningRequest request, ActiveWallet wallet) {
        if (wallet == null || wallet.identity().status() != Status.ENABLED
                || request.payloadIdentity().algorithm() != SigningRequest.Algorithm.SECP256K1
                || request.payloadIdentity().mode() != SigningRequest.Mode.EVM_DIGEST
                || request.authorityContext().network() != SettlementNetwork.ETHEREUM
                || !purposeMatchesAction(
                        request.keyContext().role(), request.authorityContext().action(),
                        wallet.identity().allowedPurposes())
                || !keyContextMatches(request.keyContext(), wallet)) {
            return false;
        }
        try {
            return wallet.identity().normalizedAddress().equals(
                    normalizeAddress(request.authorityContext().sourceReference()));
        } catch (IllegalArgumentException invalidAddress) {
            return false;
        }
    }

    private static boolean keyContextMatches(
            SigningRequest.KeyContext requested, ActiveWallet wallet) {
        WalletIdentity identity = wallet.identity();
        return requested.alias().equals(identity.keyReference())
                && requested.registryVersion().equals(identity.registryVersion())
                && requested.keyVersion().equals(Optional.of(identity.keyVersion()))
                && requested.algorithm() == SigningRequest.Algorithm.SECP256K1
                && requested.network() == SettlementNetwork.ETHEREUM
                && requested.status() == SigningRequest.KeyStatus.ACTIVE
                && wallet.keyRoles().contains(requested.role())
                && requested.allowedRoles().equals(wallet.keyRoles())
                && requested.allowedAlgorithms().equals(
                        Set.of(SigningRequest.Algorithm.SECP256K1))
                && requested.allowedNetworks().equals(Set.of(SettlementNetwork.ETHEREUM));
    }

    private static boolean purposeMatchesAction(
            SigningRequest.KeyRole role,
            SigningRequest.Action action,
            Set<Purpose> purposes) {
        return switch (action) {
            case MINT -> role == SigningRequest.KeyRole.MINT_AUTHORITY
                    && purposes.contains(Purpose.MINT_AUTHORITY);
            case TRANSFER -> role == SigningRequest.KeyRole.TRANSFER_AUTHORITY
                    && (purposes.contains(Purpose.BANK_SETTLEMENT_TRANSFER)
                            || purposes.contains(Purpose.USER_CUSTODY_TRANSFER));
            case BURN -> role == SigningRequest.KeyRole.BURN_AUTHORITY
                    && purposes.contains(Purpose.BURN_AUTHORITY);
        };
    }

    private SigningRequest.KeyContext missing(
            KeyAlias alias,
            SigningRequest.KeyRole role,
            SigningRequest.Algorithm algorithm,
            SettlementNetwork network) {
        return new SigningRequest.KeyContext(
                alias, "local-demo-registry-v1:not-found", Optional.empty(), role,
                algorithm, network, SigningRequest.KeyStatus.NOT_FOUND,
                Set.of(), Set.of(), Set.of(),
                VALID_FROM, Optional.empty());
    }

    private ProviderResult conflict(ProviderContext context, String safeCode) {
        return new Conflict(safeCode, evidence(context, "conflict", null));
    }

    private static EvidenceRef evidence(
            ProviderContext context, String outcome, ActiveWallet wallet) {
        String fingerprint = wallet == null
                ? "unresolved" : wallet.fingerprint().substring(0, 16);
        String identity = sha256((context.providerRequestId().value()
                + ":" + context.request().requestDigest())
                .getBytes(StandardCharsets.UTF_8)).substring(0, 24);
        return new EvidenceRef("internal:local-demo-signer:v1:" + outcome
                + ":secp256k1:" + fingerprint + ":" + identity);
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

    private static Set<SigningRequest.KeyRole> keyRoles(Set<Purpose> purposes) {
        java.util.EnumSet<SigningRequest.KeyRole> roles =
                java.util.EnumSet.noneOf(SigningRequest.KeyRole.class);
        if (purposes.contains(Purpose.MINT_AUTHORITY)) {
            roles.add(SigningRequest.KeyRole.MINT_AUTHORITY);
        }
        if (purposes.contains(Purpose.BURN_AUTHORITY)) {
            roles.add(SigningRequest.KeyRole.BURN_AUTHORITY);
        }
        if (purposes.contains(Purpose.BANK_SETTLEMENT_TRANSFER)
                || purposes.contains(Purpose.USER_CUSTODY_TRANSFER)) {
            roles.add(SigningRequest.KeyRole.TRANSFER_AUTHORITY);
        }
        return Set.copyOf(roles);
    }

    private static String registryVersion(
            ConfiguredWallet configured, String address, String keyVersion) {
        String aliases = configured.aliases().stream().map(WalletReference::value)
                .sorted().collect(java.util.stream.Collectors.joining(","));
        String purposes = configured.allowedPurposes().stream().map(Enum::name)
                .sorted().collect(java.util.stream.Collectors.joining(","));
        String canonical = configured.reference().value() + "\n" + aliases + "\n"
                + configured.ownerCategory() + "\n" + configured.network() + "\n"
                + address + "\n" + configured.keyReference().value() + "\n"
                + keyVersion + "\n" + purposes + "\n" + configured.enabled();
        return "local-demo-registry-v1:"
                + sha256(canonical.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    private static String normalizeAddress(String value) {
        if (value == null || !ADDRESS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "configured local address must be a 20-byte hexadecimal value");
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static <T> void putUnique(
            Map<T, ActiveWallet> values, T key, ActiveWallet value, String kind) {
        if (values.putIfAbsent(key, value) != null) {
            throw new IllegalArgumentException("configured " + kind + " is duplicated");
        }
    }

    public record Configuration(long chainId, List<ConfiguredWallet> wallets) {

        public Configuration {
            wallets = List.copyOf(Objects.requireNonNull(wallets, "wallets"));
        }
    }

    public record ConfiguredWallet(
            WalletReference reference,
            Set<WalletReference> aliases,
            OwnerCategory ownerCategory,
            SettlementNetwork network,
            KeyAlias keyReference,
            char[] privateKey,
            String expectedAddress,
            Set<Purpose> allowedPurposes,
            boolean enabled) {

        public ConfiguredWallet {
            Objects.requireNonNull(reference, "reference");
            aliases = Set.copyOf(Objects.requireNonNull(aliases, "aliases"));
            Objects.requireNonNull(ownerCategory, "ownerCategory");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(keyReference, "keyReference");
            privateKey = Objects.requireNonNull(privateKey, "privateKey").clone();
            Objects.requireNonNull(expectedAddress, "expectedAddress");
            allowedPurposes = Set.copyOf(
                    Objects.requireNonNull(allowedPurposes, "allowedPurposes"));
            if (aliases.contains(reference)) {
                throw new IllegalArgumentException(
                        "configured wallet aliases must not repeat the primary reference");
            }
            if (allowedPurposes.isEmpty()) {
                throw new IllegalArgumentException(
                        "configured wallet purposes are required");
            }
        }

        @Override
        public char[] privateKey() {
            return privateKey.clone();
        }

        private void destroy() {
            Arrays.fill(privateKey, '\0');
        }

        @Override
        public String toString() {
            return "ConfiguredWallet[reference=" + reference
                    + ", privateKey=[REDACTED]]";
        }
    }

    private record ActiveWallet(
            WalletIdentity identity,
            Set<SigningRequest.KeyRole> keyRoles,
            Secp256k1LocalKey key,
            String fingerprint) {
    }

    private record Registry(
            Map<WalletReference, ActiveWallet> byReference,
            Map<KeyAlias, ActiveWallet> byKey,
            List<WalletIdentity> identities) {
    }

    private record RecordedResult(String binding, ProviderResult result) {
    }
}
