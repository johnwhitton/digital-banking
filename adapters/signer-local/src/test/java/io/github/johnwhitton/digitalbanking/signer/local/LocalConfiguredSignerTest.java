package io.github.johnwhitton.digitalbanking.signer.local;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.port.SignerPort;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry.OwnerCategory;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry.Purpose;
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
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.ec.CustomNamedCurves;
import org.bouncycastle.jcajce.provider.digest.Keccak;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalConfiguredSignerTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void resolvesRequiredCollectionWithStableAliasesPurposesAndVersions() {
        List<TestWallet> wallets = requiredWallets();
        LocalConfiguredSigner first = signer(wallets);
        LocalConfiguredSigner reconstructed = signer(wallets);

        assertEquals(10, first.identities().size());
        assertEquals(10, first.identities().stream()
                .map(WalletIdentityRegistry.WalletIdentity::normalizedAddress)
                .distinct().count());
        assertEquals(first.identities(), reconstructed.identities());

        WalletIdentityRegistry.WalletIdentity owner = first.resolve(ref("CONTRACT_OWNER"));
        assertSame(owner, first.resolve(ref("CONTRACT_DEPLOYER")));
        assertEquals(OwnerCategory.ADMIN, owner.ownerCategory());
        assertEquals(Set.of(
                Purpose.CONTRACT_ADMIN,
                Purpose.CONTRACT_DEPLOYMENT,
                Purpose.ROLE_ADMINISTRATION), owner.allowedPurposes());

        WalletIdentityRegistry.WalletIdentity admin = first.resolve(ref("ADMIN"));
        assertSame(admin, first.resolve(ref("ADMIN_REDEMPTION")));
        assertEquals(Set.of(
                Purpose.MINT_AUTHORITY,
                Purpose.BURN_AUTHORITY,
                Purpose.REDEMPTION_CUSTODY), admin.allowedPurposes());
        assertNotEquals(owner.normalizedAddress(), admin.normalizedAddress());

        for (int index = 1; index <= 4; index++) {
            assertEquals(OwnerCategory.BANK_SETTLEMENT,
                    first.resolve(ref("BANK_" + index + "_SETTLEMENT")).ownerCategory());
            assertEquals(Set.of(Purpose.BANK_SETTLEMENT_TRANSFER),
                    first.resolve(ref("BANK_" + index + "_SETTLEMENT"))
                            .allowedPurposes());
            assertEquals(OwnerCategory.USER_CUSTODY,
                    first.resolve(ref("USER_WALLET_" + index)).ownerCategory());
            assertEquals(Set.of(Purpose.USER_CUSTODY_TRANSFER),
                    first.resolve(ref("USER_WALLET_" + index)).allowedPurposes());
        }

        assertEquals(SigningRequest.KeyStatus.ACTIVE, first.resolve(
                admin.keyReference(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, first.resolve(
                admin.keyReference(), SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, first.resolve(
                owner.keyReference(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());
    }

    @Test
    void signsOnlyForTheResolvedWalletPurposeAddressNetworkAndVersion() {
        List<TestWallet> wallets = requiredWallets();
        LocalConfiguredSigner signer = signer(wallets);
        WalletIdentityRegistry.WalletIdentity bankOne = signer.resolve(
                ref("BANK_1_SETTLEMENT"));
        WalletIdentityRegistry.WalletIdentity bankTwo = signer.resolve(
                ref("BANK_2_SETTLEMENT"));
        WalletIdentityRegistry.WalletIdentity userOne = signer.resolve(
                ref("USER_WALLET_1"));
        SigningRequest.KeyContext key = signer.resolve(
                bankOne.keyReference(), SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM);
        SigningRequest.KeyContext userKey = signer.resolve(
                userOne.keyReference(), SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM);
        byte[] digest = randomDigest();

        assertInstanceOf(SignerPort.Signed.class, signer.signEvmDigest(command(
                key, SigningRequest.Action.TRANSFER, bankOne.normalizedAddress(),
                digest, "provider-correct")));
        assertInstanceOf(SignerPort.Conflict.class, signer.signEvmDigest(command(
                key, SigningRequest.Action.TRANSFER, bankTwo.normalizedAddress(),
                digest, "provider-wrong-wallet")));
        assertInstanceOf(SignerPort.Conflict.class, signer.signEvmDigest(command(
                key, SigningRequest.Action.TRANSFER, userOne.normalizedAddress(),
                digest, "provider-bank-as-user")));
        assertInstanceOf(SignerPort.Signed.class, signer.signEvmDigest(command(
                userKey, SigningRequest.Action.TRANSFER, userOne.normalizedAddress(),
                digest, "provider-user-correct")));
        assertInstanceOf(SignerPort.Conflict.class, signer.signEvmDigest(command(
                userKey, SigningRequest.Action.TRANSFER, bankOne.normalizedAddress(),
                digest, "provider-user-as-bank")));
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                bankOne.keyReference(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM).status());
        assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                bankOne.keyReference(), SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.SOLANA).status());
        assertThrows(IllegalArgumentException.class,
                () -> signer.resolve(ref("BANK_9_SETTLEMENT")));
    }

    @Test
    void changedKeyChangesPublicIdentityAndFencesTheStaleRequest() {
        List<TestWallet> original = requiredWallets();
        LocalConfiguredSigner before = signer(original);
        WalletIdentityRegistry.WalletIdentity originalAdmin = before.resolve(ref("ADMIN"));
        SigningRequest.KeyContext stale = before.resolve(
                originalAdmin.keyReference(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM);

        List<TestWallet> rotated = new ArrayList<>(original);
        rotated.set(1, generatedWallet(
                "ADMIN", Set.of("ADMIN_REDEMPTION"), OwnerCategory.ADMIN,
                Set.of(Purpose.MINT_AUTHORITY, Purpose.BURN_AUTHORITY,
                        Purpose.REDEMPTION_CUSTODY)));
        LocalConfiguredSigner after = signer(rotated);
        WalletIdentityRegistry.WalletIdentity changedAdmin = after.resolve(ref("ADMIN"));

        assertNotEquals(originalAdmin.normalizedAddress(), changedAdmin.normalizedAddress());
        assertNotEquals(originalAdmin.keyVersion(), changedAdmin.keyVersion());
        assertInstanceOf(SignerPort.Conflict.class, after.signEvmDigest(command(
                stale, SigningRequest.Action.MINT, originalAdmin.normalizedAddress(),
                randomDigest(), "provider-stale")));
    }

    @Test
    void restartWithTheSameKeyHoldsAnUnknownProviderOutcomeForManualReview() {
        List<TestWallet> wallets = requiredWallets();
        LocalConfiguredSigner beforeRestart = signer(wallets);
        LocalConfiguredSigner afterRestart = signer(wallets);
        WalletIdentityRegistry.WalletIdentity admin = beforeRestart.resolve(ref("ADMIN"));
        SigningRequest.KeyContext key = beforeRestart.resolve(
                admin.keyReference(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Algorithm.SECP256K1, SettlementNetwork.ETHEREUM);
        SignerPort.EvmDigestCommand command = command(
                key, SigningRequest.Action.MINT, admin.normalizedAddress(),
                randomDigest(), "provider-before-restart");

        assertInstanceOf(SignerPort.Signed.class, beforeRestart.signEvmDigest(command));
        SignerPort.Conflict held = assertInstanceOf(
                SignerPort.Conflict.class,
                afterRestart.inquire(new SignerPort.Inquiry(
                        command.context(), command.digest())));
        assertEquals("local-provider-outcome-unknown", held.safeCode());
    }

    @Test
    void rejectsInvalidOrMismatchedSecretsWithoutDisclosingThem() {
        TestWallet valid = generatedWallet(
                "ADMIN", Set.of("ADMIN_REDEMPTION"), OwnerCategory.ADMIN,
                Set.of(Purpose.MINT_AUTHORITY));
        List<char[]> invalid = List.of(
                new char[0],
                " ".toCharArray(),
                "0x".toCharArray(),
                "z".repeat(64).toCharArray(),
                "0".repeat(64).toCharArray(),
                HEX.formatHex(unsigned32(CURVE.getN())).toCharArray(),
                ("0x" + HEX.formatHex(randomScalar()) + " ").toCharArray());

        for (char[] secret : invalid) {
            LocalConfiguredSigner.ConfiguredWallet configured = configured(valid, secret,
                    valid.expectedAddress());
            IllegalArgumentException failure = assertThrows(
                    IllegalArgumentException.class,
                    () -> signerConfigured(List.of(configured)));
            if (secret.length >= 32) {
                assertFalse(failure.getMessage().contains(new String(secret)));
                assertFalse(configured.toString().contains(new String(secret)));
            }
        }

        char[] secret = valid.privateKey().clone();
        String wrongAddress = address(randomScalar());
        LocalConfiguredSigner.ConfiguredWallet mismatched = configured(
                valid, secret, wrongAddress);
        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> signerConfigured(List.of(mismatched)));
        assertFalse(failure.getMessage().contains(new String(secret)));
        assertFalse(mismatched.toString().contains(new String(secret)));
    }

    private static LocalConfiguredSigner signer(List<TestWallet> wallets) {
        return signerConfigured(
                wallets.stream().map(LocalConfiguredSignerTest::configured).toList());
    }

    private static LocalConfiguredSigner signerConfigured(
            List<LocalConfiguredSigner.ConfiguredWallet> wallets) {
        return new LocalConfiguredSigner(
                new LocalConfiguredSigner.Configuration(31337, wallets),
                new SecureRandom());
    }

    private static List<TestWallet> requiredWallets() {
        return List.of(
                generatedWallet("CONTRACT_OWNER", Set.of("CONTRACT_DEPLOYER"),
                        OwnerCategory.ADMIN, Set.of(Purpose.CONTRACT_ADMIN,
                                Purpose.CONTRACT_DEPLOYMENT,
                                Purpose.ROLE_ADMINISTRATION)),
                generatedWallet("ADMIN", Set.of("ADMIN_REDEMPTION"),
                        OwnerCategory.ADMIN, Set.of(Purpose.MINT_AUTHORITY,
                                Purpose.BURN_AUTHORITY, Purpose.REDEMPTION_CUSTODY)),
                generatedWallet("BANK_1_SETTLEMENT", Set.of(),
                        OwnerCategory.BANK_SETTLEMENT,
                        Set.of(Purpose.BANK_SETTLEMENT_TRANSFER)),
                generatedWallet("BANK_2_SETTLEMENT", Set.of(),
                        OwnerCategory.BANK_SETTLEMENT,
                        Set.of(Purpose.BANK_SETTLEMENT_TRANSFER)),
                generatedWallet("BANK_3_SETTLEMENT", Set.of(),
                        OwnerCategory.BANK_SETTLEMENT,
                        Set.of(Purpose.BANK_SETTLEMENT_TRANSFER)),
                generatedWallet("BANK_4_SETTLEMENT", Set.of(),
                        OwnerCategory.BANK_SETTLEMENT,
                        Set.of(Purpose.BANK_SETTLEMENT_TRANSFER)),
                generatedWallet("USER_WALLET_1", Set.of(), OwnerCategory.USER_CUSTODY,
                        Set.of(Purpose.USER_CUSTODY_TRANSFER)),
                generatedWallet("USER_WALLET_2", Set.of(), OwnerCategory.USER_CUSTODY,
                        Set.of(Purpose.USER_CUSTODY_TRANSFER)),
                generatedWallet("USER_WALLET_3", Set.of(), OwnerCategory.USER_CUSTODY,
                        Set.of(Purpose.USER_CUSTODY_TRANSFER)),
                generatedWallet("USER_WALLET_4", Set.of(), OwnerCategory.USER_CUSTODY,
                        Set.of(Purpose.USER_CUSTODY_TRANSFER)));
    }

    private static TestWallet generatedWallet(
            String identity,
            Set<String> aliases,
            OwnerCategory owner,
            Set<Purpose> purposes) {
        byte[] scalar = randomScalar();
        return new TestWallet(identity, aliases, owner, purposes,
                HEX.formatHex(scalar).toCharArray(), address(scalar));
    }

    private static LocalConfiguredSigner.ConfiguredWallet configured(TestWallet wallet) {
        return configured(wallet, wallet.privateKey(), wallet.expectedAddress());
    }

    private static LocalConfiguredSigner.ConfiguredWallet configured(
            TestWallet wallet, char[] privateKey, String expectedAddress) {
        return new LocalConfiguredSigner.ConfiguredWallet(
                ref(wallet.identity()),
                wallet.aliases().stream().map(LocalConfiguredSignerTest::ref).collect(
                        java.util.stream.Collectors.toUnmodifiableSet()),
                wallet.owner(), SettlementNetwork.ETHEREUM,
                new KeyAlias("local-demo:" + wallet.identity()), privateKey,
                expectedAddress, wallet.purposes(), true);
    }

    private static SignerPort.EvmDigestCommand command(
            SigningRequest.KeyContext key,
            SigningRequest.Action action,
            String source,
            byte[] digest,
            String providerId) {
        SigningRequest request = SigningRequest.requested(
                new SigningRequestId(UUID.randomUUID()),
                new SigningRequest.Correlation(
                        new OperationId(UUID.randomUUID()), new AttemptId(UUID.randomUUID()),
                        Optional.empty(), Optional.empty()),
                Optional.empty(),
                new SigningRequest.PayloadIdentity(
                        SigningRequest.Mode.EVM_DIGEST,
                        SigningRequest.Algorithm.SECP256K1, sha256(digest), 32,
                        SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST),
                key,
                new SigningRequest.AuthorityContext(
                        action, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("1", UNIT), source, "local-destination",
                        "local-native-action", sha256("lifetime".getBytes(StandardCharsets.UTF_8)),
                        "0", sha256("constraint".getBytes(StandardCharsets.UTF_8)),
                        "local-policy-v1", List.of(new EvidenceRef("local:approval")),
                        NOW, NOW.plusSeconds(60)),
                1, sha256(("intent-" + providerId).getBytes(StandardCharsets.UTF_8)),
                1, sha256(("request-" + providerId).getBytes(StandardCharsets.UTF_8)),
                NOW, new EvidenceRef("local:requested"));
        request = request.awaitAuthorization(
                request.version(), NOW.plusNanos(1_000), new EvidenceRef("local:awaiting"));
        request = request.authorize(
                request.version(), NOW.plusNanos(2_000), new EvidenceRef("local:authorized"));
        request = request.persistProviderRequest(
                request.version(), new SigningAttemptId(UUID.randomUUID()),
                new ProviderRequestId(providerId), NOW.plusNanos(3_000),
                new EvidenceRef("local:provider-request"));
        SigningRequest.Attempt attempt = request.attempts().getLast();
        return new SignerPort.EvmDigestCommand(
                new SignerPort.ProviderContext(
                        request, attempt.attemptId(), attempt.providerRequestId()), digest);
    }

    private static WalletReference ref(String value) {
        return new WalletReference("synthetic-wallet:" + value);
    }

    private static byte[] randomScalar() {
        SecureRandom random = new SecureRandom();
        BigInteger value;
        do {
            value = new BigInteger(256, random);
        } while (value.signum() == 0 || value.compareTo(CURVE.getN()) >= 0);
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        System.arraycopy(raw, Math.max(0, raw.length - 32), result,
                Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return result;
    }

    private static byte[] unsigned32(BigInteger value) {
        byte[] raw = value.toByteArray();
        byte[] result = new byte[32];
        System.arraycopy(raw, Math.max(0, raw.length - 32), result,
                Math.max(0, 32 - raw.length), Math.min(32, raw.length));
        return result;
    }

    private static String address(byte[] scalar) {
        byte[] publicKey = CURVE.getG().multiply(new BigInteger(1, scalar))
                .normalize().getEncoded(false);
        byte[] digest = new Keccak.Digest256().digest(
                java.util.Arrays.copyOfRange(publicKey, 1, publicKey.length));
        return "0x" + HEX.formatHex(
                java.util.Arrays.copyOfRange(digest, digest.length - 20, digest.length))
                .toLowerCase(Locale.ROOT);
    }

    private static byte[] randomDigest() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return value;
    }

    private static String sha256(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private record TestWallet(
            String identity,
            Set<String> aliases,
            OwnerCategory owner,
            Set<Purpose> purposes,
            char[] privateKey,
            String expectedAddress) {
    }
}
