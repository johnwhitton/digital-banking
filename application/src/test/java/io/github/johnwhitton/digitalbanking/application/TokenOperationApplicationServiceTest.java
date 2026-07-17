package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.TokenOperationApplicationService.AcceptanceRequest;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyScope;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.AssetUnitCatalog;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TokenOperationApplicationServiceTest {

    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");
    private static final ParticipantScope OTHER_PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-b");
    private static final AssetUnit UNIT = new AssetUnit(
            "REFERENCE_ASSET", "REFERENCE_UNIT", 3, 2, new BigInteger("1000000"));
    private static final Instant NOW = Instant.parse("2026-07-16T23:00:00Z");

    private InMemoryRepository repository;
    private TokenOperationApplicationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRepository();
        TokenOperationService lifecycle = new TokenOperationService(
                repository,
                () -> NOW,
                new SequentialIds(),
                (canonical, participant) -> new EvidenceRef(
                        "evidence:acceptance:" + canonical.sha256().substring(0, 12)));
        AssetUnitCatalog catalog = (assetId, unitId, version) ->
                assetId.equals(UNIT.assetId())
                        && unitId.equals(UNIT.unitId())
                        && version == UNIT.version()
                        ? Optional.of(UNIT) : Optional.empty();
        service = new TokenOperationApplicationService(lifecycle, repository, catalog, 1);
    }

    @Test
    void resolvesTheServerOwnedUnitAndAcceptsCanonicalQuantity() {
        OperationAcceptance accepted = service.accept(
                OperationKind.MINT,
                PARTICIPANT,
                new AcceptanceRequest(
                        1, "REFERENCE_ASSET", "REFERENCE_UNIT", 3, "12.34", "corr-001"),
                IdempotencyKey.of("request-001"));

        assertEquals("12.34", accepted.operation().quantity().toCanonicalString());
        assertEquals(UNIT, accepted.operation().quantity().unit());
        assertEquals(PARTICIPANT.tenantId(),
                accepted.operation().acceptanceContext().tenantId());
    }

    @Test
    void rejectsUnsupportedContractAndAssetUnitVersions() {
        AcceptanceRequest request = new AcceptanceRequest(
                2, "REFERENCE_ASSET", "REFERENCE_UNIT", 3, "1", "corr-001");
        assertThrows(UnsupportedRequestContractException.class, () -> service.accept(
                OperationKind.MINT, PARTICIPANT, request, IdempotencyKey.of("request-002")));

        AcceptanceRequest unknown = new AcceptanceRequest(
                1, "REFERENCE_ASSET", "REFERENCE_UNIT", 4, "1", "corr-001");
        assertThrows(UnknownAssetUnitException.class, () -> service.accept(
                OperationKind.MINT, PARTICIPANT, unknown, IdempotencyKey.of("request-003")));
    }

    @Test
    void rejectsNonCanonicalQuantityBeforeCreatingAnOperation() {
        AcceptanceRequest request = new AcceptanceRequest(
                1, "REFERENCE_ASSET", "REFERENCE_UNIT", 3, "1.0", "corr-001");

        InvalidRequestException failure = assertThrows(InvalidRequestException.class,
                () -> service.accept(
                OperationKind.BURN, PARTICIPANT, request, IdempotencyKey.of("request-004")));

        assertEquals(IllegalArgumentException.class, failure.getCause().getClass());
        assertEquals(0, repository.size());
    }

    @Test
    void statusLookupIsScopedToTheAuthenticatedParticipant() {
        TokenOperation operation = service.accept(
                OperationKind.MINT,
                PARTICIPANT,
                new AcceptanceRequest(
                        1, "REFERENCE_ASSET", "REFERENCE_UNIT", 3, "1", "corr-001"),
                IdempotencyKey.of("request-005")).operation();

        assertEquals(operation, service.find(operation.operationId(), PARTICIPANT));
        assertThrows(OperationNotFoundException.class,
                () -> service.find(operation.operationId(), OTHER_PARTICIPANT));
        assertThrows(OperationNotFoundException.class,
                () -> service.find(new OperationId(new UUID(9, 9)), PARTICIPANT));
    }

    @Test
    void internalPersistenceFailureIsNotReclassifiedAsCallerValidation() {
        IllegalArgumentException internalFailure =
                new IllegalArgumentException("synthetic persistence invariant failure");
        repository.acceptFailure = internalFailure;

        IllegalArgumentException observed = assertThrows(IllegalArgumentException.class,
                () -> service.accept(
                        OperationKind.MINT,
                        PARTICIPANT,
                        new AcceptanceRequest(
                                1, "REFERENCE_ASSET", "REFERENCE_UNIT", 3,
                                "1", "corr-001"),
                        IdempotencyKey.of("request-internal-failure")));

        assertSame(internalFailure, observed);
    }

    private static final class SequentialIds
            implements io.github.johnwhitton.digitalbanking.application.port.IdGenerator {
        private long operationSequence;
        private long attemptSequence;

        @Override
        public OperationId nextOperationId() {
            return new OperationId(new UUID(0, ++operationSequence));
        }

        @Override
        public AttemptId nextAttemptId() {
            return new AttemptId(new UUID(1, ++attemptSequence));
        }
    }

    private static final class InMemoryRepository implements OperationRepository {
        private final Map<ScopedKey, StoredAcceptance> acceptances = new HashMap<>();
        private final Map<OperationId, TokenOperation> operations = new HashMap<>();
        private RuntimeException acceptFailure;

        @Override
        public synchronized OperationAcceptance accept(
                IdempotencyScope scope,
                IdempotencyKey key,
                CanonicalCommandMetadata canonicalCommand,
                Supplier<TokenOperation> operationFactory) {
            if (acceptFailure != null) {
                throw acceptFailure;
            }
            ScopedKey scopedKey = new ScopedKey(scope, key);
            StoredAcceptance existing = acceptances.get(scopedKey);
            if (existing != null) {
                if (!existing.canonicalCommand().equals(canonicalCommand)) {
                    throw new IdempotencyConflictException();
                }
                return new OperationAcceptance(existing.operation(), true);
            }
            TokenOperation operation = operationFactory.get();
            acceptances.put(scopedKey, new StoredAcceptance(canonicalCommand, operation));
            operations.put(operation.operationId(), operation);
            return new OperationAcceptance(operation, false);
        }

        @Override
        public synchronized Optional<TokenOperation> findById(OperationId operationId) {
            return Optional.ofNullable(operations.get(operationId));
        }

        @Override
        public synchronized Optional<TokenOperation> findById(
                OperationId operationId, ParticipantScope participant) {
            return findById(operationId).filter(operation ->
                    operation.acceptanceContext().tenantId().equals(participant.tenantId())
                            && operation.acceptanceContext().participantId()
                            .equals(participant.participantId()));
        }

        @Override
        public synchronized void save(TokenOperation operation, long expectedVersion) {
            TokenOperation current = operations.get(operation.operationId());
            if (current == null || current.version() != expectedVersion) {
                throw new IllegalStateException("operation version conflict");
            }
            operations.put(operation.operationId(), operation);
        }

        int size() {
            return operations.size();
        }

        private record ScopedKey(IdempotencyScope scope, IdempotencyKey key) {
        }

        private record StoredAcceptance(
                CanonicalCommandMetadata canonicalCommand, TokenOperation operation) {
        }
    }
}
