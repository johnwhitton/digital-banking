package io.github.johnwhitton.digitalbanking.signer.local;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
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
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalSigningAuthorityIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-17T12:00:00Z");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @Test
    void authorizedRequestSignsOnceAndCompletedReplaySurvivesSignerRestart() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalEphemeralSigner first = signer();
        SigningAuthorityService firstService = service(durable, first);
        SigningAuthorityService.Request request = request(
                first, "replay", bytes(32, 1), List.of(new EvidenceRef("local:approval")),
                Optional.empty());

        SigningAuthorityService.Signed signed = assertInstanceOf(
                SigningAuthorityService.Signed.class, firstService.sign(request));
        assertEquals(1, first.signingInvocationCount());
        assertEquals(SigningRequest.Status.SIGNED, signed.request().status());
        assertEquals(65, signed.request().attempts().getLast().signatureEvidence()
                .orElseThrow().length());
        assertTrue(signed.request().attempts().getLast().signatureEvidence()
                .orElseThrow().evidence().value().startsWith("internal:local-signer:v1:"));

        LocalEphemeralSigner restarted = signer();
        SigningAuthorityService restartedService = service(durable, restarted);
        SigningAuthorityService.Signed replay = assertInstanceOf(
                SigningAuthorityService.Signed.class, restartedService.sign(request));

        assertTrue(replay.replayed());
        assertEquals(signed.request(), replay.request());
        assertEquals(0, restarted.signingInvocationCount());
        assertNotEquals(first.keys().getFirst().keyVersion(),
                restarted.keys().getFirst().keyVersion());
    }

    @Test
    void approvalPendingNeverInvokesProvider() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalEphemeralSigner signer = signer();
        SigningAuthorityService service = service(durable, signer);

        SigningAuthorityService.ApprovalRequired result = assertInstanceOf(
                SigningAuthorityService.ApprovalRequired.class,
                service.sign(request(signer, "pending", bytes(32, 2),
                        List.of(), Optional.empty())));

        assertEquals(SigningRequest.Status.AWAITING_AUTHORIZATION,
                result.request().status());
        assertEquals(0, signer.signingInvocationCount());
    }

    @Test
    void ambiguityUsesInquiryWithoutResigning() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalEphemeralSigner signer = signer();
        SigningAuthorityService service = service(durable, signer);
        SigningAuthorityService.Request request = request(
                signer, "ambiguous", bytes(32, 3),
                List.of(new EvidenceRef("local:approval")), Optional.empty());
        signer.nextOutcomeForTest(
                LocalEphemeralSigner.TestOutcome.AMBIGUOUS_AFTER_SIGNATURE);

        assertInstanceOf(SigningAuthorityService.Ambiguous.class, service.sign(request));
        assertEquals(1, signer.signingInvocationCount());
        SigningAuthorityService.Signed recovered = assertInstanceOf(
                SigningAuthorityService.Signed.class, service.sign(request));

        assertTrue(recovered.replayed());
        assertEquals(1, signer.signingInvocationCount());
    }

    @Test
    void pendingRequestFromPriorSessionRoutesToManualReview() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalEphemeralSigner first = signer();
        SigningAuthorityService firstService = service(durable, first);
        SigningAuthorityService.Request request = request(
                first, "stale-pending", bytes(32, 9),
                List.of(new EvidenceRef("local:approval")), Optional.empty());
        first.nextOutcomeForTest(
                LocalEphemeralSigner.TestOutcome.AMBIGUOUS_AFTER_SIGNATURE);
        assertInstanceOf(SigningAuthorityService.Ambiguous.class,
                firstService.sign(request));

        LocalEphemeralSigner restarted = signer();
        SigningAuthorityService restartedService = service(durable, restarted);
        SigningAuthorityService.ManualReview held = assertInstanceOf(
                SigningAuthorityService.ManualReview.class,
                restartedService.sign(request));

        assertEquals(SigningRequest.Status.MANUAL_REVIEW, held.request().status());
        assertEquals(0, restarted.signingInvocationCount());
    }

    @Test
    void provenNoSignatureRequiresReauthorizationBeforeBoundedRetry() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalEphemeralSigner signer = signer();
        SigningAuthorityService service = service(durable, signer);
        SigningAuthorityService.Request request = request(
                signer, "retry", bytes(32, 4),
                List.of(new EvidenceRef("local:approval")), Optional.empty());
        signer.nextOutcomeForTest(LocalEphemeralSigner.TestOutcome.RETRYABLE_NO_SIGNATURE);

        SigningAuthorityService.RetryableNoSignature failed = assertInstanceOf(
                SigningAuthorityService.RetryableNoSignature.class, service.sign(request));
        assertEquals(0, signer.signingInvocationCount());
        SigningAuthorityService.Signed retried = assertInstanceOf(
                SigningAuthorityService.Signed.class,
                service.retry(request.requestId(), request.signableMaterial(),
                        new EvidenceRef("local:retry-authorized")));

        assertEquals(1, signer.signingInvocationCount());
        assertEquals(2, retried.request().attempts().size());
        assertEquals(Optional.of(failed.request().attempts().getLast().attemptId()),
                retried.request().attempts().getLast().predecessor());
    }

    @Test
    void changedPayloadUsesSeparatelyAuthorizedLinkedRequest() {
        InMemoryRequests durable = new InMemoryRequests();
        LocalEphemeralSigner signer = signer();
        SigningAuthorityService service = service(durable, signer);
        SigningAuthorityService.Request original = request(
                signer, "original", bytes(32, 5),
                List.of(new EvidenceRef("local:approval")), Optional.empty());
        SigningAuthorityService.Signed first = assertInstanceOf(
                SigningAuthorityService.Signed.class, service.sign(original));
        SigningRequest.Attempt attempt = first.request().attempts().getLast();
        SigningRequest.Lineage lineage = new SigningRequest.Lineage(
                first.request().requestId(), attempt.attemptId(),
                new EvidenceRef("local:linked-authorization"));

        SigningAuthorityService.Request changed = request(
                signer, "changed", bytes(32, 6),
                List.of(new EvidenceRef("local:approval")), Optional.of(lineage));
        SigningAuthorityService.Signed second = assertInstanceOf(
                SigningAuthorityService.Signed.class, service.sign(changed));

        assertNotEquals(first.request().requestId(), second.request().requestId());
        assertEquals(Optional.of(lineage), second.request().lineage());
        assertEquals(2, signer.signingInvocationCount());
        assertEquals(SigningRequest.Status.SIGNED, second.request().status());
    }

    private static SigningAuthorityService service(
            InMemoryRequests requests, LocalEphemeralSigner signer) {
        AtomicLong sequence = new AtomicLong();
        SigningIdentityGenerator ids = new SigningIdentityGenerator() {
            @Override
            public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(new UUID(20, sequence.incrementAndGet()));
            }

            @Override
            public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-provider-" + sequence.incrementAndGet());
            }
        };
        SigningAuthorizationPort authorization = signingRequest ->
                new SigningAuthorizationPort.Authorized(
                        new EvidenceRef("local:authorization:" +
                                signingRequest.requestId().value()));
        return new SigningAuthorityService(
                requests, signer, authorization, signer, ids, () -> NOW);
    }

    private static LocalEphemeralSigner signer() {
        Set<SigningRequest.KeyRole> roles = Set.of(
                SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                SigningRequest.KeyRole.BURN_AUTHORITY);
        return new LocalEphemeralSigner(
                new LocalEphemeralSigner.Configuration(roles, roles, 65_536),
                Clock.fixed(NOW, ZoneOffset.UTC), new SecureRandom());
    }

    private static SigningAuthorityService.Request request(
            LocalEphemeralSigner signer,
            String seed,
            byte[] material,
            List<EvidenceRef> approvals,
            Optional<SigningRequest.Lineage> lineage) {
        LocalEphemeralSigner.KeyMetadata key = signer.keys().stream()
                .filter(metadata ->
                        metadata.algorithm() == SigningRequest.Algorithm.SECP256K1)
                .findFirst().orElseThrow();
        return new SigningAuthorityService.Request(
                new SigningRequestId(UUID.nameUUIDFromBytes(
                        ("request-" + seed).getBytes(StandardCharsets.UTF_8))),
                new SigningRequest.Correlation(
                        new OperationId(UUID.nameUUIDFromBytes(
                                ("operation-" + seed).getBytes(StandardCharsets.UTF_8))),
                        new AttemptId(UUID.nameUUIDFromBytes(
                                ("attempt-" + seed).getBytes(StandardCharsets.UTF_8))),
                        Optional.empty(), Optional.empty()),
                lineage,
                SigningRequest.Action.MINT,
                SettlementNetwork.ETHEREUM,
                TokenQuantity.parse("1", UNIT),
                "local-source",
                "local-destination",
                "local-mint",
                hex(seed + "-lifetime"),
                "0",
                hex(seed + "-constraints"),
                key.alias(),
                SigningRequest.KeyRole.MINT_AUTHORITY,
                SigningRequest.Mode.EVM_DIGEST,
                SigningRequest.Algorithm.SECP256K1,
                material,
                "local-policy-v1",
                approvals,
                NOW,
                NOW.plusSeconds(60));
    }

    private static byte[] bytes(int length, int seed) {
        byte[] value = new byte[length];
        for (int index = 0; index < value.length; index++) {
            value[index] = (byte) (seed + index);
        }
        return value;
    }

    private static String hex(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
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
