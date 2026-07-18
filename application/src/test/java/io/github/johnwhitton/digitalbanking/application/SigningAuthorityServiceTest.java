package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningKeyRegistry;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SigningAuthorityServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-17T21:00:00Z");
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));

    @Test
    void signsEvmDigestAfterDurableKeyAndPolicyAuthorizationThenReplaysExactly() {
        Fixture fixture = new Fixture();
        byte[] digest = bytes(32, 7);
        SigningAuthorityService.Request request = fixture.request(
                1, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                digest, List.of(new EvidenceRef("evidence:approval:four-eyes")));

        SigningAuthorityService.Result first = fixture.service.sign(request);
        digest[0] = 99;
        fixture.keyStatus = SigningRequest.KeyStatus.REVOKED;
        SigningAuthorityService.Result replay = fixture.service.sign(request);

        SigningAuthorityService.Signed firstSigned = assertInstanceOf(
                SigningAuthorityService.Signed.class, first);
        SigningAuthorityService.Signed replaySigned = assertInstanceOf(
                SigningAuthorityService.Signed.class, replay);
        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(SigningRequest.Status.SIGNED, first.request().status());
        assertEquals(1, fixture.signer.evmCalls());
        assertEquals(0, fixture.signer.solanaCalls());
        assertEquals(1, fixture.registryCalls);
        assertEquals(7, request.signableMaterial()[0]);
        assertEquals(List.of(
                SigningRequest.Status.REQUESTED,
                SigningRequest.Status.AWAITING_AUTHORIZATION,
                SigningRequest.Status.AUTHORIZED,
                SigningRequest.Status.PROVIDER_REQUEST_PERSISTED,
                SigningRequest.Status.SIGNED), fixture.repository.savedStatuses);
        assertEquals(SigningRequest.EvidenceOrigin.SYNTHETIC_TEST,
                first.request().attempts().getLast().signatureEvidence()
                        .orElseThrow().origin());
        assertEquals(83, firstSigned.signatureMaterial().orElseThrow().bytes()[0]);
        byte[] defensive = firstSigned.signatureMaterial().orElseThrow().bytes();
        defensive[0] = 0;
        assertEquals(83, firstSigned.signatureMaterial().orElseThrow().bytes()[0]);
        assertTrue(replaySigned.signatureMaterial().isEmpty());
    }

    @Test
    void solanaMessageUsesEd25519MessageMethodAndDefensiveCopies() {
        Fixture fixture = new Fixture();
        byte[] message = bytes(96, 11);
        SigningAuthorityService.Request request = fixture.request(
                2, SigningRequest.Mode.SOLANA_MESSAGE, SigningRequest.Algorithm.ED25519,
                message, List.of(new EvidenceRef("evidence:approval:four-eyes")));

        SigningAuthorityService.Result result = fixture.service.sign(request);
        message[0] = 88;

        assertInstanceOf(SigningAuthorityService.Signed.class, result);
        assertEquals(0, fixture.signer.evmCalls());
        assertEquals(1, fixture.signer.solanaCalls());
        assertEquals(11, request.signableMaterial()[0]);
    }

    @Test
    void missingApprovalWaitsWithoutInvokingProvider() {
        Fixture fixture = new Fixture();
        SigningAuthorityService.Result result = fixture.service.sign(fixture.request(
                3, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of()));

        assertInstanceOf(SigningAuthorityService.ApprovalRequired.class, result);
        assertEquals(SigningRequest.Status.AWAITING_AUTHORIZATION, result.request().status());
        assertEquals(0, fixture.signer.evmCalls());
        assertEquals(0, fixture.authorizationCalls);
    }

    @Test
    void rejectsUnsafeKeyMetadataBeforeProviderInvocation() {
        for (SigningRequest.KeyStatus status : List.of(
                SigningRequest.KeyStatus.DISABLED,
                SigningRequest.KeyStatus.REVOKED,
                SigningRequest.KeyStatus.NOT_FOUND)) {
            Fixture fixture = new Fixture();
            fixture.keyStatus = status;
            SigningAuthorityService.Result result = fixture.service.sign(fixture.request(
                    10 + status.ordinal(), SigningRequest.Mode.EVM_DIGEST,
                    SigningRequest.Algorithm.SECP256K1, bytes(32, 1),
                    List.of(new EvidenceRef("evidence:approval"))));

            if (status == SigningRequest.KeyStatus.REVOKED) {
                assertInstanceOf(SigningAuthorityService.Revoked.class, result);
            } else {
                assertInstanceOf(SigningAuthorityService.Denied.class, result);
            }
            assertEquals(0, fixture.signer.evmCalls());
        }

        Fixture expired = new Fixture();
        expired.keyExpiresAt = Optional.of(NOW.minusSeconds(1));
        assertInstanceOf(SigningAuthorityService.Expired.class,
                expired.service.sign(expired.request(
                        20, SigningRequest.Mode.EVM_DIGEST,
                        SigningRequest.Algorithm.SECP256K1, bytes(32, 1),
                        List.of(new EvidenceRef("evidence:approval")))));
        assertEquals(0, expired.signer.evmCalls());

        Fixture wrongRole = new Fixture();
        wrongRole.allowedRoles = Set.of(SigningRequest.KeyRole.BURN_AUTHORITY);
        assertInstanceOf(SigningAuthorityService.Denied.class,
                wrongRole.service.sign(wrongRole.request(
                        21, SigningRequest.Mode.EVM_DIGEST,
                        SigningRequest.Algorithm.SECP256K1, bytes(32, 1),
                        List.of(new EvidenceRef("evidence:approval")))));
        assertEquals(0, wrongRole.signer.evmCalls());

        Fixture wrongAlgorithm = new Fixture();
        wrongAlgorithm.allowedAlgorithms = Optional.of(
                Set.of(SigningRequest.Algorithm.ED25519));
        assertInstanceOf(SigningAuthorityService.Denied.class,
                wrongAlgorithm.service.sign(wrongAlgorithm.request(
                        22, SigningRequest.Mode.EVM_DIGEST,
                        SigningRequest.Algorithm.SECP256K1, bytes(32, 1),
                        List.of(new EvidenceRef("evidence:approval")))));
        assertEquals(0, wrongAlgorithm.signer.evmCalls());

        Fixture wrongNetwork = new Fixture();
        wrongNetwork.allowedNetworks = Optional.of(Set.of(SettlementNetwork.SOLANA));
        assertInstanceOf(SigningAuthorityService.Denied.class,
                wrongNetwork.service.sign(wrongNetwork.request(
                        23, SigningRequest.Mode.EVM_DIGEST,
                        SigningRequest.Algorithm.SECP256K1, bytes(32, 1),
                        List.of(new EvidenceRef("evidence:approval")))));
        assertEquals(0, wrongNetwork.signer.evmCalls());
    }

    @Test
    void changedBoundPayloadUnderSameRequestIdentityConflicts() {
        Fixture fixture = new Fixture();
        SigningAuthorityService.Request first = fixture.request(
                30, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of());
        SigningAuthorityService.Request changed = fixture.request(
                30, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 2), List.of());

        assertInstanceOf(SigningAuthorityService.ApprovalRequired.class,
                fixture.service.sign(first));
        SigningAuthorityService.Result conflict = fixture.service.sign(changed);

        assertInstanceOf(SigningAuthorityService.Conflict.class, conflict);
        assertEquals(0, fixture.signer.evmCalls());
        assertEquals(1, fixture.registryCalls);
    }

    @Test
    void ambiguousReplayInquiresOriginalProviderIdentityWithoutBlindRetry() {
        Fixture fixture = new Fixture();
        fixture.signer.scenario(SyntheticSigner.Scenario.AMBIGUOUS);
        fixture.signer.inquiryScenario(SyntheticSigner.Scenario.SIGNED);
        SigningAuthorityService.Request request = fixture.request(
                40, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of(new EvidenceRef("evidence:approval")));

        SigningAuthorityService.Result first = fixture.service.sign(request);
        SigningAuthorityService.Result replay = fixture.service.sign(request);

        assertInstanceOf(SigningAuthorityService.Ambiguous.class, first);
        assertInstanceOf(SigningAuthorityService.Signed.class, replay);
        assertEquals(1, fixture.signer.evmCalls());
        assertEquals(1, fixture.signer.inquiryCalls());
        assertEquals(first.request().attempts().getLast().providerRequestId(),
                replay.request().attempts().getLast().providerRequestId());
    }

    @Test
    void provenNoSignatureAllowsOneLinkedRetryWithTheExactOriginalPayload() {
        Fixture fixture = new Fixture();
        fixture.signer.scenario(SyntheticSigner.Scenario.RETRYABLE_NO_SIGNATURE);
        SigningAuthorityService.Request request = fixture.request(
                50, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of(new EvidenceRef("evidence:approval")));

        SigningAuthorityService.Result first = fixture.service.sign(request);
        fixture.signer.scenario(SyntheticSigner.Scenario.SIGNED);
        SigningAuthorityService.Result retried = fixture.service.retry(
                request.requestId(), request.signableMaterial(),
                new EvidenceRef("evidence:retry-after-no-signature"));

        assertInstanceOf(SigningAuthorityService.RetryableNoSignature.class, first);
        assertInstanceOf(SigningAuthorityService.Signed.class, retried);
        assertEquals(2, retried.request().attempts().size());
        assertEquals(2, fixture.authorizationCalls);
        assertEquals(Optional.of(first.request().attempts().getFirst().attemptId()),
                retried.request().attempts().getLast().predecessor());
        assertThrows(IllegalArgumentException.class, () -> fixture.service.retry(
                request.requestId(), bytes(32, 9),
                new EvidenceRef("evidence:changed-payload")));
    }

    @Test
    void providerIdentityConflictRoutesToManualReviewWithSyntheticEvidence() {
        Fixture fixture = new Fixture();
        fixture.reuseProviderIdentity = true;
        SigningAuthorityService.Result first = fixture.service.sign(fixture.request(
                60, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of(new EvidenceRef("evidence:approval"))));
        SigningAuthorityService.Result second = fixture.service.sign(fixture.request(
                61, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 2), List.of(new EvidenceRef("evidence:approval"))));

        assertInstanceOf(SigningAuthorityService.Signed.class, first);
        assertInstanceOf(SigningAuthorityService.ManualReview.class, second);
        assertEquals(SigningRequest.Status.MANUAL_REVIEW, second.request().status());
        assertTrue(second.request().attempts().getLast().evidence().stream()
                .anyMatch(value -> value.value().startsWith("synthetic:")));
    }

    @Test
    void providerDenialIsExplicitAndUnexpectedFailureResumesByInquiry() {
        Fixture denied = new Fixture();
        denied.signer.scenario(SyntheticSigner.Scenario.DENIED);
        SigningAuthorityService.Result deniedResult = denied.service.sign(denied.request(
                70, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of(new EvidenceRef("evidence:approval"))));

        assertInstanceOf(SigningAuthorityService.Denied.class, deniedResult);
        assertEquals(SigningRequest.Status.DENIED, deniedResult.request().status());

        Fixture failed = new Fixture();
        SigningAuthorityService.Request request = failed.request(
                71, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 2), List.of(new EvidenceRef("evidence:approval")));
        failed.signer.scenario(SyntheticSigner.Scenario.INFRASTRUCTURE_FAILURE);
        assertThrows(IllegalStateException.class, () -> failed.service.sign(request));
        assertEquals(SigningRequest.Status.PROVIDER_REQUEST_PERSISTED,
                failed.repository.findById(request.requestId()).orElseThrow().status());

        failed.signer.inquiryScenario(SyntheticSigner.Scenario.SIGNED);
        SigningAuthorityService.Result recovered = failed.service.sign(request);

        assertInstanceOf(SigningAuthorityService.Signed.class, recovered);
        assertEquals(1, failed.signer.evmCalls());
        assertEquals(1, failed.signer.inquiryCalls());
    }

    @Test
    void recoveredAwaitingAndAuthorizedRequestsRecheckExpiryBeforeProviderUse() {
        Fixture awaiting = new Fixture();
        awaiting.keyExpiresAt = Optional.of(NOW.plusSeconds(10));
        SigningAuthorityService.Request awaitingInput = awaiting.request(
                80, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of());
        assertInstanceOf(SigningAuthorityService.ApprovalRequired.class,
                awaiting.service.sign(awaitingInput));
        awaiting.currentTime = NOW.plusSeconds(11);

        assertInstanceOf(SigningAuthorityService.Expired.class,
                awaiting.service.sign(awaitingInput));
        assertEquals(0, awaiting.signer.evmCalls());

        Fixture authorized = new Fixture();
        SigningAuthorityService.Request authorizedInput = authorized.request(
                81, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of());
        SigningRequest pending = authorized.service.sign(authorizedInput).request();
        SigningRequest orphaned = pending.authorize(
                pending.version(), NOW.plusSeconds(1),
                new EvidenceRef("evidence:authorized-before-restart"));
        authorized.repository.save(orphaned, pending.version());
        authorized.currentTime = NOW.plusSeconds(301);

        assertInstanceOf(SigningAuthorityService.Expired.class,
                authorized.service.sign(authorizedInput));
        assertEquals(0, authorized.signer.evmCalls());
    }

    @Test
    void rejectsRegistryAliasOrRoleSubstitutionBeforePersistenceAndAuthorization() {
        Fixture alias = new Fixture();
        alias.resolvedAlias = Optional.of(new KeyAlias("substituted-key"));
        SigningAuthorityService.Request aliasInput = alias.request(
                82, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of(new EvidenceRef("evidence:approval")));

        assertThrows(IllegalStateException.class, () -> alias.service.sign(aliasInput));
        assertTrue(alias.repository.findById(aliasInput.requestId()).isEmpty());
        assertEquals(0, alias.authorizationCalls);
        assertEquals(0, alias.signer.evmCalls());

        Fixture role = new Fixture();
        role.resolvedRole = Optional.of(SigningRequest.KeyRole.BURN_AUTHORITY);
        SigningAuthorityService.Request roleInput = role.request(
                83, SigningRequest.Mode.EVM_DIGEST, SigningRequest.Algorithm.SECP256K1,
                bytes(32, 1), List.of(new EvidenceRef("evidence:approval")));

        assertThrows(IllegalStateException.class, () -> role.service.sign(roleInput));
        assertTrue(role.repository.findById(roleInput.requestId()).isEmpty());
        assertEquals(0, role.authorizationCalls);
        assertEquals(0, role.signer.evmCalls());
    }

    private static byte[] bytes(int size, int value) {
        byte[] bytes = new byte[size];
        java.util.Arrays.fill(bytes, (byte) value);
        return bytes;
    }

    private static final class Fixture {

        private final InMemorySigningRequestRepository repository =
                new InMemorySigningRequestRepository();
        private final SyntheticSigner signer = new SyntheticSigner();
        private final AtomicLong identities = new AtomicLong(100);
        private Instant currentTime = NOW;
        private SigningRequest.KeyStatus keyStatus = SigningRequest.KeyStatus.ACTIVE;
        private Set<SigningRequest.KeyRole> allowedRoles =
                Set.of(SigningRequest.KeyRole.MINT_AUTHORITY);
        private Optional<Set<SigningRequest.Algorithm>> allowedAlgorithms = Optional.empty();
        private Optional<Set<SettlementNetwork>> allowedNetworks = Optional.empty();
        private Optional<KeyAlias> resolvedAlias = Optional.empty();
        private Optional<SigningRequest.KeyRole> resolvedRole = Optional.empty();
        private Optional<Instant> keyExpiresAt = Optional.of(NOW.plusSeconds(3600));
        private int registryCalls;
        private int authorizationCalls;
        private boolean reuseProviderIdentity;
        private final SigningAuthorityService service = new SigningAuthorityService(
                repository,
                keyRegistry(),
                authorization(),
                signer,
                identities(),
                () -> currentTime);

        private SigningKeyRegistry keyRegistry() {
            return (alias, role, algorithm, network) -> {
                registryCalls++;
                if (keyStatus == SigningRequest.KeyStatus.NOT_FOUND) {
                    return new SigningRequest.KeyContext(
                            alias, "registry-v1", Optional.empty(), role, algorithm, network,
                            keyStatus, Set.of(), Set.of(), Set.of(), NOW.minusSeconds(3600),
                            Optional.empty());
                }
                return new SigningRequest.KeyContext(
                        resolvedAlias.orElse(alias), "registry-v1",
                        Optional.of("key-version-7"), resolvedRole.orElse(role), algorithm,
                        network, keyStatus, allowedRoles,
                        allowedAlgorithms.orElseGet(() -> Set.of(algorithm)),
                        allowedNetworks.orElseGet(() -> Set.of(network)),
                        NOW.minusSeconds(3600), keyExpiresAt);
            };
        }

        private SigningAuthorizationPort authorization() {
            return request -> {
                authorizationCalls++;
                return new SigningAuthorizationPort.Authorized(
                        new EvidenceRef("evidence:signing-authorized"));
            };
        }

        private SigningIdentityGenerator identities() {
            return new SigningIdentityGenerator() {
                @Override
                public SigningAttemptId nextAttemptId() {
                    return new SigningAttemptId(new UUID(7, identities.incrementAndGet()));
                }

                @Override
                public ProviderRequestId nextProviderRequestId() {
                    long value = reuseProviderIdentity ? 999 : identities.incrementAndGet();
                    return new ProviderRequestId("synthetic-provider-request-" + value);
                }
            };
        }

        private SigningAuthorityService.Request request(
                long seed,
                SigningRequest.Mode mode,
                SigningRequest.Algorithm algorithm,
                byte[] material,
                List<EvidenceRef> approvals) {
            SettlementNetwork network = mode == SigningRequest.Mode.EVM_DIGEST
                    ? SettlementNetwork.ETHEREUM : SettlementNetwork.SOLANA;
            return new SigningAuthorityService.Request(
                    new SigningRequestId(new UUID(1, seed)),
                    new SigningRequest.Correlation(
                            new OperationId(new UUID(2, seed)),
                            new AttemptId(new UUID(3, seed)),
                            Optional.empty(), Optional.empty()),
                    Optional.empty(),
                    SigningRequest.Action.MINT,
                    network,
                    TokenQuantity.parse("12.34", UNIT),
                    "mint-authority-role",
                    "opaque-recipient-wallet",
                    "token-contract:mint",
                    A,
                    "fee-limit-policy-v1",
                    B,
                    new KeyAlias("institution-mint"),
                    SigningRequest.KeyRole.MINT_AUTHORITY,
                    mode,
                    algorithm,
                    material,
                    "policy-v5",
                    approvals,
                    NOW,
                    NOW.plusSeconds(300));
        }
    }

    private static final class InMemorySigningRequestRepository
            implements SigningRequestRepository {

        private final Map<SigningRequestId, SigningRequest> requests = new LinkedHashMap<>();
        private final List<SigningRequest.Status> savedStatuses = new ArrayList<>();

        @Override
        public synchronized Acceptance accept(SigningRequest proposed) {
            SigningRequest existing = requests.get(proposed.requestId());
            if (existing != null) {
                if (!existing.intentDigest().equals(proposed.intentDigest())
                        || !existing.requestDigest().equals(proposed.requestDigest())) {
                    throw new SigningRequestConflictException(existing);
                }
                return new Acceptance(existing, true);
            }
            requests.put(proposed.requestId(), proposed);
            savedStatuses.add(proposed.status());
            return new Acceptance(proposed, false);
        }

        @Override
        public synchronized Optional<SigningRequest> findById(SigningRequestId requestId) {
            return Optional.ofNullable(requests.get(requestId));
        }

        @Override
        public synchronized void save(SigningRequest request, long expectedVersion) {
            SigningRequest current = requests.get(request.requestId());
            if (current == null || current.version() != expectedVersion
                    || request.version() != expectedVersion + 1) {
                throw new IllegalStateException("signing request version conflict");
            }
            requests.put(request.requestId(), request);
            savedStatuses.add(request.status());
        }
    }
}
