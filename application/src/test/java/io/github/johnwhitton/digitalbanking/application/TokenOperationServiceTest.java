package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommand;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.CommandCanonicalizer;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyScope;
import io.github.johnwhitton.digitalbanking.application.command.MintCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.EvidenceReferencePort;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenOperationServiceTest {

    private static final AssetUnit UNIT = new AssetUnit(
            "USDC", "USD", 7, 6, new BigInteger("1000000000000"));
    private static final Instant NOW = Instant.parse("2026-07-16T22:00:00Z");

    private InMemoryOperationRepository repository;
    private CountingIds ids;
    private TokenOperationService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryOperationRepository();
        ids = new CountingIds();
        ClockPort clock = () -> NOW;
        EvidenceReferencePort evidence = (canonical, participant) ->
                new EvidenceRef("evidence:acceptance:" + canonical.sha256().substring(0, 12));
        service = new TokenOperationService(repository, clock, ids, evidence);
    }

    @Test
    void sameScopeKeyAndDigestReplaysWithoutCreatingASecondOperation() {
        MintCommand command = command("tenant-a", "participant-a", "1", "corr-001");
        IdempotencyKey key = IdempotencyKey.of("request-001");

        OperationAcceptance first = service.accept(command, key);
        OperationAcceptance replay = service.accept(command, key);

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertEquals(first.operation().operationId(), replay.operation().operationId());
        assertEquals(1, repository.size());
        assertEquals(1, ids.operationIdsIssued());
        assertEquals("tenant-a", first.operation().acceptanceContext().tenantId());
        assertEquals("participant-a", first.operation().acceptanceContext().participantId());
        assertEquals("corr-001", first.operation().acceptanceContext().businessCorrelation());
        assertEquals(1, first.operation().acceptanceContext().requestContractVersion());
        assertEquals(1, first.operation().acceptanceContext().canonicalizationVersion());
    }

    @Test
    void canonicalizationVersionIsPartOfTheStoredIdempotencyIdentity() {
        MintCommand command = command("tenant-a", "participant-a", "1", "corr-001");
        IdempotencyKey key = IdempotencyKey.of("request-versioned");
        CanonicalCommand canonical = CommandCanonicalizer.canonicalize(command);
        service.accept(command, key);

        assertThrows(IdempotencyConflictException.class, () -> repository.accept(
                command.idempotencyScope(), key,
                new CanonicalCommandMetadata(
                        canonical.canonicalizationVersion() + 1, canonical.digest()),
                () -> {
                    throw new AssertionError("conflicting metadata must not create an operation");
                }));
    }

    @Test
    void sameScopeAndKeyWithDifferentDigestConflictsWithoutLeakingKey() {
        IdempotencyKey key = IdempotencyKey.of("sensitive-client-key");
        service.accept(command("tenant-a", "participant-a", "1", "corr-001"), key);

        IdempotencyConflictException conflict = assertThrows(
                IdempotencyConflictException.class,
                () -> service.accept(
                        command("tenant-a", "participant-a", "2", "corr-001"), key));

        assertFalse(conflict.getMessage().contains(key.value()));
        assertEquals(1, repository.size());
        assertEquals(1, ids.operationIdsIssued());
    }

    @Test
    void isolatesKeysAcrossTenantAndParticipantScopes() {
        IdempotencyKey key = IdempotencyKey.of("shared-key");

        OperationAcceptance tenantA = service.accept(
                command("tenant-a", "participant-a", "1", "corr-001"), key);
        OperationAcceptance tenantB = service.accept(
                command("tenant-b", "participant-a", "1", "corr-001"), key);
        OperationAcceptance participantB = service.accept(
                command("tenant-a", "participant-b", "1", "corr-001"), key);

        assertNotEquals(tenantA.operation().operationId(), tenantB.operation().operationId());
        assertNotEquals(tenantA.operation().operationId(), participantB.operation().operationId());
        assertEquals(3, repository.size());
    }

    @Test
    void acceptsCommandValidationBoundariesWithoutNarrowingThemInTheDomainContext() {
        String supplementaryCorrelation = "\uD83D\uDE00".repeat(128);
        MintCommand command = command(
                "tenant-", "participant-", "1", supplementaryCorrelation);

        OperationAcceptance accepted = service.accept(
                command, IdempotencyKey.of("boundary-request"));

        assertEquals("tenant-", accepted.operation().acceptanceContext().tenantId());
        assertEquals("participant-", accepted.operation().acceptanceContext().participantId());
        assertEquals(128, accepted.operation().acceptanceContext().businessCorrelation()
                .codePointCount(0, supplementaryCorrelation.length()));
    }

    @Test
    void coordinatesGuardedLifecycleUsingRepositoryVersion() {
        OperationAcceptance accepted = service.accept(
                command("tenant-a", "participant-a", "1", "corr-001"),
                IdempotencyKey.of("request-002"));

        TokenOperation validated = service.transition(
                accepted.operation().operationId(), accepted.operation().version(),
                OperationState.VALIDATED, "validation-worker", "validated",
                new EvidenceRef("evidence:validation"));

        assertEquals(OperationState.VALIDATED, validated.state());
        assertEquals(validated,
                repository.findById(validated.operationId()).orElseThrow());
        assertThrows(IllegalStateException.class, () -> service.transition(
                validated.operationId(), 0, OperationState.POLICY_PENDING,
                "policy-worker", "stale", new EvidenceRef("evidence:stale")));
    }

    private static MintCommand command(
            String tenant, String participant, String quantity, String correlation) {
        return new MintCommand(
                1,
                new ParticipantScope(tenant, participant),
                TokenQuantity.parse(quantity, UNIT),
                correlation);
    }

    private static final class CountingIds implements IdGenerator {
        private int operationSequence;
        private int attemptSequence;

        @Override
        public OperationId nextOperationId() {
            operationSequence++;
            return new OperationId(new UUID(0, operationSequence));
        }

        @Override
        public AttemptId nextAttemptId() {
            attemptSequence++;
            return new AttemptId(new UUID(1, attemptSequence));
        }

        int operationIdsIssued() {
            return operationSequence;
        }
    }

    private static final class InMemoryOperationRepository implements OperationRepository {
        private final Map<ScopedKey, StoredAcceptance> acceptances = new HashMap<>();
        private final Map<OperationId, TokenOperation> operations = new HashMap<>();

        @Override
        public synchronized OperationAcceptance accept(
                IdempotencyScope scope,
                IdempotencyKey key,
                CanonicalCommandMetadata canonicalCommand,
                Supplier<TokenOperation> operationFactory) {
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
        public synchronized void save(TokenOperation operation, long expectedVersion) {
            TokenOperation current = operations.get(operation.operationId());
            if (current == null || current.version() != expectedVersion) {
                throw new IllegalStateException("operation version conflict");
            }
            operations.put(operation.operationId(), operation);
            acceptances.replaceAll((key, value) -> value.operation().operationId()
                    .equals(operation.operationId())
                    ? new StoredAcceptance(value.canonicalCommand(), operation)
                    : value);
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
