package io.github.johnwhitton.digitalbanking.signer.local;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class LocalConfiguredSigningAuthorityTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final X9ECParameters CURVE = CustomNamedCurves.getByName("secp256k1");
    private static final HexFormat HEX = HexFormat.of();

    @Test
    void changedConfiguredKeyMovesAnOutstandingAuthorizedRequestToManualReview() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalConfiguredSigner original = signer(randomScalar());
        WalletIdentityRegistry.WalletIdentity admin = original.identities().getFirst();
        SigningAuthorityService.Request request = request(admin);
        SigningAuthorityService awaiting = service(
                durable, original,
                signing -> new SigningAuthorizationPort.AwaitingApproval(
                        new EvidenceRef("local:approval-pending")));

        assertInstanceOf(SigningAuthorityService.ApprovalRequired.class,
                awaiting.sign(request));

        LocalConfiguredSigner rotated = signer(randomScalar());
        SigningAuthorityService resumed = service(
                durable, rotated,
                signing -> new SigningAuthorizationPort.Authorized(
                        new EvidenceRef("local:approved")));
        SigningAuthorityService.ManualReview held = assertInstanceOf(
                SigningAuthorityService.ManualReview.class,
                resumed.sign(request));

        assertEquals(SigningRequest.Status.MANUAL_REVIEW, held.request().status());
        assertEquals("local-wallet-stale",
                held.request().attempts().getLast().safeFailureCode().orElseThrow());
    }

    private static LocalConfiguredSigner signer(byte[] scalar) {
        String address = address(scalar);
        LocalConfiguredSigner.ConfiguredWallet admin =
                new LocalConfiguredSigner.ConfiguredWallet(
                        new WalletReference("synthetic-wallet:ADMIN"),
                        Set.of(new WalletReference("synthetic-wallet:ADMIN_REDEMPTION")),
                        WalletIdentityRegistry.OwnerCategory.ADMIN,
                        SettlementNetwork.ETHEREUM, new KeyAlias("local-demo:ADMIN"),
                        HEX.formatHex(scalar).toCharArray(), address,
                        Set.of(WalletIdentityRegistry.Purpose.MINT_AUTHORITY,
                                WalletIdentityRegistry.Purpose.BURN_AUTHORITY,
                                WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY),
                        true);
        return new LocalConfiguredSigner(
                new LocalConfiguredSigner.Configuration(31337, List.of(admin)),
                new SecureRandom());
    }

    private static SigningAuthorityService.Request request(
            WalletIdentityRegistry.WalletIdentity admin) {
        byte[] material = new byte[32];
        new SecureRandom().nextBytes(material);
        return new SigningAuthorityService.Request(
                new SigningRequestId(UUID.randomUUID()),
                new SigningRequest.Correlation(
                        new OperationId(UUID.randomUUID()), new AttemptId(UUID.randomUUID()),
                        Optional.empty(), Optional.empty()),
                Optional.empty(), SigningRequest.Action.MINT,
                SettlementNetwork.ETHEREUM, TokenQuantity.parse("1", UNIT),
                admin.normalizedAddress(), "local-destination", "local-mint",
                sha256("lifetime".getBytes(StandardCharsets.UTF_8)), "0",
                sha256("constraints".getBytes(StandardCharsets.UTF_8)),
                admin.keyReference(), SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                material, "local-policy-v1", List.of(new EvidenceRef("local:approval")),
                NOW, NOW.plusSeconds(60));
    }

    private static SigningAuthorityService service(
            InMemoryRequests requests,
            LocalConfiguredSigner signer,
            SigningAuthorizationPort authorization) {
        AtomicLong sequence = new AtomicLong();
        SigningIdentityGenerator ids = new SigningIdentityGenerator() {
            @Override
            public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(new UUID(40, sequence.incrementAndGet()));
            }

            @Override
            public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-demo-provider-"
                        + sequence.incrementAndGet());
            }
        };
        return new SigningAuthorityService(
                requests, signer, authorization, signer, ids, () -> NOW);
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

    private static String address(byte[] scalar) {
        byte[] publicKey = CURVE.getG().multiply(new BigInteger(1, scalar))
                .normalize().getEncoded(false);
        byte[] digest = new Keccak.Digest256().digest(
                Arrays.copyOfRange(publicKey, 1, publicKey.length));
        return "0x" + HEX.formatHex(
                Arrays.copyOfRange(digest, digest.length - 20, digest.length));
    }

    private static String sha256(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static final class InMemoryRequests implements SigningRequestRepository {

        private final Map<SigningRequestId, SigningRequest> values = new HashMap<>();

        @Override
        public synchronized Acceptance accept(SigningRequest proposed) {
            SigningRequest retained = values.putIfAbsent(proposed.requestId(), proposed);
            return new Acceptance(retained == null ? proposed : retained, retained != null);
        }

        @Override
        public synchronized Optional<SigningRequest> findById(SigningRequestId requestId) {
            return Optional.ofNullable(values.get(requestId));
        }

        @Override
        public synchronized void save(SigningRequest request, long expectedVersion) {
            SigningRequest current = values.get(request.requestId());
            if (current == null || current.version() != expectedVersion) {
                throw new IllegalStateException("test repository version conflict");
            }
            values.put(request.requestId(), request);
        }
    }
}
