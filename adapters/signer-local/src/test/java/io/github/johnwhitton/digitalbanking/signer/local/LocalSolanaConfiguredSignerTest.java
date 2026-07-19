package io.github.johnwhitton.digitalbanking.signer.local;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.time.Instant;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalSolanaConfiguredSignerTest {

    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @TempDir
    Path temporary;

    @Test
    void loadsPrivateRoleFilesAndSignsTheExactSameMessageInTwoPurposes()
            throws Exception {
        Fixture fixture = fixture();
        byte[] message = "one serialized Solana message"
                .getBytes(StandardCharsets.UTF_8);
        SignerPort.SolanaMessageCommand fee;
        byte[] retainedFeeSignature;
        try (LocalSolanaConfiguredSigner signer = fixture.signer()) {
            fee = command(
                    signer, fixture.fee(), SigningRequest.KeyRole.FEE_PAYER,
                    message, "fee-provider");
            SignerPort.SolanaMessageCommand authority = command(
                    signer, fixture.authority(), SigningRequest.KeyRole.MINT_AUTHORITY,
                    message, "authority-provider");

            SignerPort.Signed feeResult = assertInstanceOf(
                    SignerPort.Signed.class, signer.signSolanaMessage(fee));
            SignerPort.Signed authorityResult = assertInstanceOf(
                    SignerPort.Signed.class, signer.signSolanaMessage(authority));
            assertEquals(LocalSolanaConfiguredSigner.SIGNATURE_ENCODING,
                    feeResult.encoding());
            assertTrue(verify(fixture.fee().pair(), message, feeResult.signature()));
            assertTrue(verify(
                    fixture.authority().pair(), message, authorityResult.signature()));
            assertArrayEquals(feeResult.signature(),
                    assertInstanceOf(SignerPort.Signed.class,
                            signer.signSolanaMessage(fee)).signature(),
                    "provider replay must return the retained signature");
            retainedFeeSignature = feeResult.signature();
            assertThrows(IllegalArgumentException.class,
                    () -> new SignerPort.EvmDigestCommand(fee.context(), new byte[32]));
        }
        try (LocalSolanaConfiguredSigner restarted = fixture.signer()) {
            SignerPort.Signed recovered = assertInstanceOf(
                    SignerPort.Signed.class, restarted.inquire(
                            new SignerPort.Inquiry(fee.context(), message)));
            assertArrayEquals(retainedFeeSignature, recovered.signature(),
                    "provider inquiry must recover the original public signature after restart");
        }
    }

    @Test
    void signsTransferOnlyWithTheConfiguredTransferAuthority() throws Exception {
        Fixture fixture = fixture();
        TestKey transfer = key(
                fixture.root().resolve("transfer-authority.json"),
                "local-solana:transfer-authority");
        byte[] message = "one exact transfer message".getBytes(StandardCharsets.UTF_8);
        try (LocalSolanaConfiguredSigner signer = new LocalSolanaConfiguredSigner(
                new LocalSolanaConfiguredSigner.Configuration(
                        fixture.root(), List.of(
                                configured(fixture.fee(), SigningRequest.KeyRole.FEE_PAYER,
                                        "fee-v1"),
                                configured(fixture.authority(),
                                        SigningRequest.KeyRole.MINT_AUTHORITY,
                                        "authority-v1"),
                                configured(transfer,
                                        SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                        "transfer-v1"),
                                configured(fixture.burn(),
                                        SigningRequest.KeyRole.BURN_AUTHORITY,
                                        "burn-v1"))))) {
            SignerPort.SolanaMessageCommand command = command(
                    signer, transfer, SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                    message, "transfer-provider", transfer.address(),
                    SigningRequest.Action.TRANSFER);
            SignerPort.Signed result = assertInstanceOf(
                    SignerPort.Signed.class, signer.signSolanaMessage(command));
            assertTrue(verify(transfer.pair(), message, result.signature()));
            SignerPort.SolanaMessageCommand unauthorizedMintAuthority = command(
                    signer, fixture.authority(), SigningRequest.KeyRole.MINT_AUTHORITY,
                    message, "unauthorized-transfer-provider",
                    fixture.authority().address(), SigningRequest.Action.TRANSFER);
            assertInstanceOf(SignerPort.Conflict.class,
                    signer.signSolanaMessage(unauthorizedMintAuthority));
            assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                    transfer.alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                    SigningRequest.Algorithm.ED25519,
                    SettlementNetwork.SOLANA).status());
        }
    }

    @Test
    void signsBurnOnlyWithTheConfiguredAdminRedemptionAuthority() throws Exception {
        Fixture fixture = fixture();
        byte[] message = "one exact burn message".getBytes(StandardCharsets.UTF_8);
        try (LocalSolanaConfiguredSigner signer = new LocalSolanaConfiguredSigner(
                new LocalSolanaConfiguredSigner.Configuration(
                        fixture.root(), List.of(
                                configured(fixture.fee(), SigningRequest.KeyRole.FEE_PAYER,
                                        "fee-v1"),
                                configured(fixture.authority(),
                                        SigningRequest.KeyRole.MINT_AUTHORITY,
                                        "authority-v1"),
                                configured(fixture.transfer(),
                                        SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                        "transfer-v1"),
                                configured(fixture.burn(),
                                        SigningRequest.KeyRole.BURN_AUTHORITY,
                                        "burn-v1"))))) {
            SignerPort.Signed result = assertInstanceOf(
                    SignerPort.Signed.class, signer.signSolanaMessage(command(
                            signer, fixture.burn(),
                            SigningRequest.KeyRole.BURN_AUTHORITY,
                            message, "burn-provider", fixture.burn().address(),
                            SigningRequest.Action.BURN)));
            assertTrue(verify(fixture.burn().pair(), message, result.signature()));
            assertInstanceOf(SignerPort.Conflict.class, signer.signSolanaMessage(command(
                    signer, fixture.transfer(),
                    SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                    message, "user-burn-provider", fixture.transfer().address(),
                    SigningRequest.Action.BURN)));
            assertInstanceOf(SignerPort.Conflict.class, signer.signSolanaMessage(command(
                    signer, fixture.authority(),
                    SigningRequest.KeyRole.MINT_AUTHORITY,
                    message, "mint-burn-provider", fixture.authority().address(),
                    SigningRequest.Action.BURN)));
        }
    }

    @Test
    void rejectsWrongRoleIdentityPermissionsAndSymlinks() throws Exception {
        Fixture fixture = fixture();
        try (LocalSolanaConfiguredSigner signer = fixture.signer()) {
            assertEquals(SigningRequest.KeyStatus.NOT_FOUND, signer.resolve(
                    fixture.fee().alias(), SigningRequest.KeyRole.MINT_AUTHORITY,
                    SigningRequest.Algorithm.ED25519, SettlementNetwork.SOLANA).status());
            byte[] message = "bound message".getBytes(StandardCharsets.UTF_8);
            SignerPort.SolanaMessageCommand wrongSource = command(
                    signer, fixture.fee(), SigningRequest.KeyRole.FEE_PAYER,
                    message, "wrong-source", fixture.authority().address());
            assertInstanceOf(SignerPort.Conflict.class,
                    signer.signSolanaMessage(wrongSource));
        }

        Files.setPosixFilePermissions(fixture.fee().file(), Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ));
        assertThrows(IllegalArgumentException.class, fixture::signer);
        Files.setPosixFilePermissions(fixture.fee().file(), filePermissions());

        Path link = fixture.root().resolve("linked.json");
        Files.createSymbolicLink(link, fixture.fee().file());
        LocalSolanaConfiguredSigner.ConfiguredKey linked =
                new LocalSolanaConfiguredSigner.ConfiguredKey(
                        fixture.fee().alias(), SigningRequest.KeyRole.FEE_PAYER,
                        link, fixture.fee().address(), "fee-v1");
        assertThrows(IllegalArgumentException.class, () -> new LocalSolanaConfiguredSigner(
                new LocalSolanaConfiguredSigner.Configuration(
                        fixture.root(), List.of(linked, configured(
                                fixture.authority(),
                                SigningRequest.KeyRole.MINT_AUTHORITY,
                                "authority-v1"), configured(
                                fixture.transfer(),
                                SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                "transfer-v1"), configured(
                                fixture.burn(), SigningRequest.KeyRole.BURN_AUTHORITY,
                                "burn-v1")))));
    }

    private Fixture fixture() throws Exception {
        Path root = temporary.resolve("solana-keys");
        Files.createDirectory(root);
        Files.setPosixFilePermissions(root, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        TestKey fee = key(root.resolve("fee-payer.json"), "local-solana:fee-payer");
        TestKey authority = key(
                root.resolve("mint-authority.json"), "local-solana:mint-authority");
        TestKey transfer = key(
                root.resolve("transfer-authority.json"),
                "local-solana:transfer-authority");
        TestKey burn = key(
                root.resolve("burn-authority.json"),
                "local-solana:burn-authority");
        return new Fixture(root, fee, authority, transfer, burn);
    }

    private static TestKey key(Path path, String alias) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519);
        KeyPair pair = generator.generateKeyPair();
        byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
        byte[] encoded = pair.getPublic().getEncoded();
        byte[] publicBytes = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        byte[] keypair = new byte[64];
        System.arraycopy(seed, 0, keypair, 0, 32);
        System.arraycopy(publicBytes, 0, keypair, 32, 32);
        String json = "[" + java.util.stream.IntStream.range(0, keypair.length)
                .map(index -> Byte.toUnsignedInt(keypair[index]))
                .mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        Files.writeString(path, json, StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(path, filePermissions());
        Arrays.fill(seed, (byte) 0);
        Arrays.fill(keypair, (byte) 0);
        return new TestKey(new KeyAlias(alias), path, base58(publicBytes), pair);
    }

    private static Set<PosixFilePermission> filePermissions() {
        return Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
    }

    private static SignerPort.SolanaMessageCommand command(
            LocalSolanaConfiguredSigner signer,
            TestKey key,
            SigningRequest.KeyRole role,
            byte[] message,
            String providerId) {
        return command(signer, key, role, message, providerId, key.address());
    }

    private static SignerPort.SolanaMessageCommand command(
            LocalSolanaConfiguredSigner signer,
            TestKey key,
            SigningRequest.KeyRole role,
            byte[] message,
            String providerId,
            String source) {
        return command(signer, key, role, message, providerId, source,
                SigningRequest.Action.MINT);
    }

    private static SignerPort.SolanaMessageCommand command(
            LocalSolanaConfiguredSigner signer,
            TestKey key,
            SigningRequest.KeyRole role,
            byte[] message,
            String providerId,
            String source,
            SigningRequest.Action action) {
        SigningRequest.KeyContext context = signer.resolve(
                key.alias(), role, SigningRequest.Algorithm.ED25519,
                SettlementNetwork.SOLANA);
        SigningRequest request = SigningRequest.requested(
                new SigningRequestId(UUID.randomUUID()),
                new SigningRequest.Correlation(
                        new OperationId(UUID.randomUUID()), new AttemptId(UUID.randomUUID()),
                        Optional.empty(), Optional.empty()), Optional.empty(),
                new SigningRequest.PayloadIdentity(
                        SigningRequest.Mode.SOLANA_MESSAGE,
                        SigningRequest.Algorithm.ED25519, sha256(message), message.length,
                        SigningRequest.PayloadEncoding.SOLANA_SERIALIZED_MESSAGE),
                context,
                new SigningRequest.AuthorityContext(
                        action, SettlementNetwork.SOLANA,
                        TokenQuantity.parse("100", UNIT), source, "destination-owner",
                        "native-attempt", sha256("lifetime".getBytes(StandardCharsets.UTF_8)),
                        "bounded-fee", sha256("constraints".getBytes(StandardCharsets.UTF_8)),
                        "local-solana-policy-v1", List.of(new EvidenceRef("local:approval")),
                        NOW, NOW.plusSeconds(60)),
                1, sha256("intent".getBytes(StandardCharsets.UTF_8)),
                1, sha256("request".getBytes(StandardCharsets.UTF_8)), NOW,
                new EvidenceRef("local:requested"));
        request = request.awaitAuthorization(
                request.version(), NOW, new EvidenceRef("local:awaiting"));
        request = request.authorize(
                request.version(), NOW, new EvidenceRef("local:authorized"));
        SigningAttemptId attempt = new SigningAttemptId(UUID.randomUUID());
        request = request.persistProviderRequest(
                request.version(), attempt, new ProviderRequestId(providerId),
                NOW, new EvidenceRef("local:provider-request"));
        return new SignerPort.SolanaMessageCommand(
                new SignerPort.ProviderContext(
                        request, attempt, request.attempts().getLast().providerRequestId()),
                message);
    }

    private static boolean verify(KeyPair pair, byte[] message, byte[] bytes) throws Exception {
        Signature verifier = Signature.getInstance("Ed25519");
        verifier.initVerify(pair.getPublic());
        verifier.update(message);
        return verifier.verify(bytes);
    }

    private static LocalSolanaConfiguredSigner.ConfiguredKey configured(
            TestKey key, SigningRequest.KeyRole role, String version) {
        return new LocalSolanaConfiguredSigner.ConfiguredKey(
                key.alias(), role, key.file(), key.address(), version);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static String base58(byte[] value) {
        BigInteger number = new BigInteger(1, value);
        String alphabet = "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz";
        StringBuilder encoded = new StringBuilder();
        while (number.signum() > 0) {
            BigInteger[] divided = number.divideAndRemainder(BigInteger.valueOf(58));
            encoded.append(alphabet.charAt(divided[1].intValueExact()));
            number = divided[0];
        }
        for (byte element : value) {
            if (element != 0) break;
            encoded.append('1');
        }
        return encoded.reverse().toString();
    }

    private record TestKey(KeyAlias alias, Path file, String address, KeyPair pair) {
    }

    private record Fixture(
            Path root, TestKey fee, TestKey authority, TestKey transfer, TestKey burn) {
        LocalSolanaConfiguredSigner signer() {
            return new LocalSolanaConfiguredSigner(
                    new LocalSolanaConfiguredSigner.Configuration(root, List.of(
                            configured(fee, SigningRequest.KeyRole.FEE_PAYER, "fee-v1"),
                            configured(authority, SigningRequest.KeyRole.MINT_AUTHORITY,
                                    "authority-v1"),
                            configured(transfer, SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                    "transfer-v1"),
                            configured(burn, SigningRequest.KeyRole.BURN_AUTHORITY,
                                    "burn-v1"))));
        }
    }
}
