package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyScope;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationTransition;
import io.github.johnwhitton.digitalbanking.domain.operation.RetryAuthorization;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit PostgreSQL mapping for atomic acceptance and append-only aggregate events. */
public final class PostgresOperationRepository implements OperationRepository {

    private static final int ACCEPTANCE_EVENT_VERSION = 1;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresOperationRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public OperationAcceptance accept(
            IdempotencyScope scope,
            IdempotencyKey key,
            CanonicalCommandMetadata canonicalCommand,
            Supplier<TokenOperation> operationFactory) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(canonicalCommand, "canonicalCommand");
        Objects.requireNonNull(operationFactory, "operationFactory");
        OperationAcceptance result = transactions.execute(status ->
                acceptInTransaction(scope, key, canonicalCommand, operationFactory));
        return Objects.requireNonNull(result, "acceptance transaction result");
    }

    @Override
    public Optional<TokenOperation> findById(OperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return findSeed(operationId, Optional.empty()).map(this::hydrate);
    }

    @Override
    public Optional<TokenOperation> findById(
            OperationId operationId, ParticipantScope participant) {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(participant, "participant");
        return findSeed(operationId, Optional.of(participant)).map(this::hydrate);
    }

    @Override
    public void save(TokenOperation operation, long expectedVersion) {
        Objects.requireNonNull(operation, "operation");
        transactions.executeWithoutResult(status ->
                saveInTransaction(operation, expectedVersion));
    }

    private OperationAcceptance acceptInTransaction(
            IdempotencyScope scope,
            IdempotencyKey key,
            CanonicalCommandMetadata canonicalCommand,
            Supplier<TokenOperation> operationFactory) {
        String keyDigest = key.sha256();
        Optional<Binding> committed = findBinding(scope, keyDigest);
        if (committed.isPresent()) {
            return replay(committed.orElseThrow(), canonicalCommand);
        }

        TokenOperation proposed = Objects.requireNonNull(
                operationFactory.get(), "operationFactory result");
        verifyProposed(proposed, scope, keyDigest, canonicalCommand);
        int claimed = insertBinding(scope, keyDigest, canonicalCommand, proposed);
        if (claimed == 0) {
            Binding winner = findBinding(scope, keyDigest)
                    .orElseThrow(() -> new IllegalStateException(
                            "idempotency winner was not visible"));
            return replay(winner, canonicalCommand);
        }

        insertOperation(proposed);
        insertAcceptanceAudit(proposed);
        insertInitialFinalities(proposed);
        insertOutbox(proposed);
        return new OperationAcceptance(proposed, false);
    }

    private OperationAcceptance replay(
            Binding binding, CanonicalCommandMetadata canonicalCommand) {
        if (binding.canonicalizationVersion() != canonicalCommand.canonicalizationVersion()
                || !binding.commandDigest().equals(canonicalCommand.digest().value())) {
            throw new IdempotencyConflictException();
        }
        TokenOperation operation = findById(binding.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency binding has no operation"));
        return new OperationAcceptance(operation, true);
    }

    private Optional<Binding> findBinding(IdempotencyScope scope, String keyDigest) {
        return jdbc.sql("""
                        SELECT canonicalization_version, command_digest, operation_id
                        FROM operation_idempotency
                        WHERE tenant_id = :tenantId
                          AND participant_id = :participantId
                          AND idempotency_resource = :resource
                          AND operation_kind = :operationKind
                          AND idempotency_key_digest = :keyDigest
                        """)
                .param("tenantId", scope.participant().tenantId())
                .param("participantId", scope.participant().participantId())
                .param("resource", scope.resource().name())
                .param("operationKind", scope.operationKind().name())
                .param("keyDigest", keyDigest)
                .query((row, rowNumber) -> new Binding(
                        row.getInt("canonicalization_version"),
                        row.getString("command_digest"),
                        new OperationId(row.getObject("operation_id", UUID.class))))
                .optional();
    }

    private int insertBinding(
            IdempotencyScope scope,
            String keyDigest,
            CanonicalCommandMetadata canonicalCommand,
            TokenOperation proposed) {
        return jdbc.sql("""
                        INSERT INTO operation_idempotency (
                            tenant_id, participant_id, idempotency_resource, operation_kind,
                            idempotency_key_digest, canonicalization_version, command_digest,
                            operation_id, created_at)
                        VALUES (
                            :tenantId, :participantId, :resource, :operationKind,
                            :keyDigest, :canonicalizationVersion, :commandDigest,
                            :operationId, :createdAt)
                        ON CONFLICT (
                            tenant_id, participant_id, idempotency_resource,
                            operation_kind, idempotency_key_digest)
                        DO NOTHING
                        """)
                .param("tenantId", scope.participant().tenantId())
                .param("participantId", scope.participant().participantId())
                .param("resource", scope.resource().name())
                .param("operationKind", scope.operationKind().name())
                .param("keyDigest", keyDigest)
                .param("canonicalizationVersion", canonicalCommand.canonicalizationVersion())
                .param("commandDigest", canonicalCommand.digest().value())
                .param("operationId", proposed.operationId().value())
                .param("createdAt", utc(proposed.createdAt()))
                .update();
    }

    private static void verifyProposed(
            TokenOperation operation,
            IdempotencyScope scope,
            String keyDigest,
            CanonicalCommandMetadata canonicalCommand) {
        OperationAcceptanceContext context = operation.acceptanceContext();
        if (operation.state() != OperationState.REQUESTED || operation.version() != 0
                || operation.kind() != scope.operationKind()
                || !context.tenantId().equals(scope.participant().tenantId())
                || !context.participantId().equals(scope.participant().participantId())
                || !context.idempotencyResource().equals(scope.resource().name())
                || !context.idempotencyKeyDigest().equals(keyDigest)
                || context.canonicalizationVersion()
                != canonicalCommand.canonicalizationVersion()
                || !context.commandDigest().equals(canonicalCommand.digest().value())
                || operation.evidenceReferences().size() != 1) {
            throw new IllegalArgumentException(
                    "proposed operation does not match its acceptance identity");
        }
    }

    private void insertOperation(TokenOperation operation) {
        OperationAcceptanceContext context = operation.acceptanceContext();
        AssetUnit unit = operation.quantity().unit();
        jdbc.sql("""
                        INSERT INTO token_operation (
                            operation_id, tenant_id, participant_id, idempotency_resource,
                            operation_kind, idempotency_key_digest, request_contract_version,
                            canonicalization_version, command_digest, business_correlation,
                            asset_id, unit_id, unit_version, unit_scale, unit_max_atomic,
                            quantity_atomic, lifecycle_state, aggregate_version,
                            acceptance_evidence_ref, created_at, updated_at)
                        VALUES (
                            :operationId, :tenantId, :participantId, :resource,
                            :operationKind, :keyDigest, :requestContractVersion,
                            :canonicalizationVersion, :commandDigest, :businessCorrelation,
                            :assetId, :unitId, :unitVersion, :unitScale, :unitMaxAtomic,
                            :quantityAtomic, :state, :aggregateVersion,
                            :acceptanceEvidence, :createdAt, :updatedAt)
                        """)
                .param("operationId", operation.operationId().value())
                .param("tenantId", context.tenantId())
                .param("participantId", context.participantId())
                .param("resource", context.idempotencyResource())
                .param("operationKind", operation.kind().name())
                .param("keyDigest", context.idempotencyKeyDigest())
                .param("requestContractVersion", context.requestContractVersion())
                .param("canonicalizationVersion", context.canonicalizationVersion())
                .param("commandDigest", context.commandDigest())
                .param("businessCorrelation", context.businessCorrelation())
                .param("assetId", unit.assetId())
                .param("unitId", unit.unitId())
                .param("unitVersion", unit.version())
                .param("unitScale", unit.scale())
                .param("unitMaxAtomic", unit.maxAtomicUnits())
                .param("quantityAtomic", operation.quantity().atomicUnits())
                .param("state", operation.state().name())
                .param("aggregateVersion", operation.version())
                .param("acceptanceEvidence",
                        operation.evidenceReferences().getFirst().value())
                .param("createdAt", utc(operation.createdAt()))
                .param("updatedAt", utc(operation.createdAt()))
                .update();
    }

    private void insertAcceptanceAudit(TokenOperation operation) {
        jdbc.sql("""
                        INSERT INTO operation_transition (
                            operation_id, transition_sequence, aggregate_version,
                            from_state, to_state, actor, reason, occurred_at)
                        VALUES (
                            :operationId, 0, 0, NULL, 'REQUESTED',
                            'acceptance-api', 'accepted', :occurredAt)
                        """)
                .param("operationId", operation.operationId().value())
                .param("occurredAt", utc(operation.createdAt()))
                .update();
        insertTransitionEvidence(
                operation.operationId(), 0, operation.evidenceReferences());
    }

    private void insertInitialFinalities(TokenOperation operation) {
        for (FinalityType type : FinalityType.values()) {
            FinalityRecord initial = operation.finalityHistory(type).getFirst();
            jdbc.sql("""
                            INSERT INTO operation_finality (
                                operation_id, finality_type, history_order,
                                aggregate_version, finality_status, authority,
                                policy_version, updated_at)
                            VALUES (
                                :operationId, :finalityType, 0, 0, :status,
                                :authority, :policyVersion, :updatedAt)
                            """)
                    .param("operationId", operation.operationId().value())
                    .param("finalityType", type.name())
                    .param("status", initial.status().name())
                    .param("authority", initial.authority())
                    .param("policyVersion", initial.policyVersion())
                    .param("updatedAt", utc(initial.updatedAt()))
                    .update();
        }
    }

    private void insertOutbox(TokenOperation operation) {
        jdbc.sql("""
                        INSERT INTO operation_outbox (
                            event_id, operation_id, event_type, event_version,
                            payload_schema_version, payload, status, created_at, available_at,
                            updated_at)
                        VALUES (
                            :eventId, :operationId, 'TokenOperationAccepted', :eventVersion,
                            1, jsonb_build_object(
                                'operationId', CAST(:operationId AS text),
                                'operationKind', :operationKind,
                                'aggregateVersion', 0),
                            'PENDING', :createdAt, :availableAt, :updatedAt)
                        """)
                .param("eventId", UUID.randomUUID())
                .param("operationId", operation.operationId().value())
                .param("eventVersion", ACCEPTANCE_EVENT_VERSION)
                .param("operationKind", operation.kind().name())
                .param("createdAt", utc(operation.createdAt()))
                .param("availableAt", utc(operation.createdAt()))
                .param("updatedAt", utc(operation.createdAt()))
                .update();
    }

    private Optional<OperationSeed> findSeed(
            OperationId operationId, Optional<ParticipantScope> participant) {
        String participantClause = participant.isPresent()
                ? " AND tenant_id = :tenantId AND participant_id = :participantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT operation_id, tenant_id, participant_id, idempotency_resource,
                               operation_kind, idempotency_key_digest, request_contract_version,
                               canonicalization_version, command_digest, business_correlation,
                               asset_id, unit_id, unit_version, unit_scale, unit_max_atomic,
                               quantity_atomic, lifecycle_state, aggregate_version,
                               acceptance_evidence_ref, created_at
                        FROM token_operation
                        WHERE operation_id = :operationId
                        """ + participantClause)
                .param("operationId", operationId.value());
        if (participant.isPresent()) {
            ParticipantScope scope = participant.orElseThrow();
            statement = statement.param("tenantId", scope.tenantId())
                    .param("participantId", scope.participantId());
        }
        return statement.query(this::mapSeed).optional();
    }

    private OperationSeed mapSeed(ResultSet row, int rowNumber) throws SQLException {
        OperationAcceptanceContext context = new OperationAcceptanceContext(
                row.getString("tenant_id"),
                row.getString("participant_id"),
                row.getString("idempotency_resource"),
                row.getString("idempotency_key_digest"),
                row.getInt("request_contract_version"),
                row.getInt("canonicalization_version"),
                row.getString("command_digest"),
                row.getString("business_correlation"));
        AssetUnit unit = new AssetUnit(
                row.getString("asset_id"),
                row.getString("unit_id"),
                row.getInt("unit_version"),
                row.getInt("unit_scale"),
                exactInteger(row, "unit_max_atomic"));
        return new OperationSeed(
                new OperationId(row.getObject("operation_id", UUID.class)),
                context,
                OperationKind.valueOf(row.getString("operation_kind")),
                TokenQuantity.ofAtomic(exactInteger(row, "quantity_atomic"), unit),
                OperationState.valueOf(row.getString("lifecycle_state")),
                row.getLong("aggregate_version"),
                instant(row, "created_at"),
                new EvidenceRef(row.getString("acceptance_evidence_ref")));
    }

    private TokenOperation hydrate(OperationSeed seed) {
        validateInitialRows(seed.operationId());
        TokenOperation operation = TokenOperation.requested(
                seed.operationId(), seed.acceptanceContext(), seed.kind(), seed.quantity(),
                seed.createdAt(), seed.acceptanceEvidence());
        Map<Long, StoredEvent> events = new TreeMap<>();
        loadTransitions(seed, events);
        loadAttempts(seed, events);
        loadFinalities(seed, events);
        for (long version = 1; version <= seed.version(); version++) {
            StoredEvent event = events.remove(version);
            if (event == null) {
                throw new IllegalStateException("operation history has a version gap");
            }
            operation = event.apply(operation);
        }
        if (!events.isEmpty() || operation.version() != seed.version()
                || operation.state() != seed.state()) {
            throw new IllegalStateException("operation history does not match its snapshot");
        }
        return operation;
    }

    private void validateInitialRows(OperationId operationId) {
        Integer auditCount = jdbc.sql("""
                        SELECT count(*)
                        FROM operation_transition
                        WHERE operation_id = :operationId
                          AND transition_sequence = 0
                          AND aggregate_version = 0
                          AND from_state IS NULL
                          AND to_state = 'REQUESTED'
                        """)
                .param("operationId", operationId.value())
                .query(Integer.class).single();
        Integer finalityCount = jdbc.sql("""
                        SELECT count(*)
                        FROM operation_finality
                        WHERE operation_id = :operationId
                          AND history_order = 0
                          AND aggregate_version = 0
                          AND finality_status = 'NOT_ASSESSED'
                        """)
                .param("operationId", operationId.value())
                .query(Integer.class).single();
        if (auditCount != 1 || finalityCount != FinalityType.values().length) {
            throw new IllegalStateException("operation acceptance history is incomplete");
        }
    }

    private void loadTransitions(OperationSeed seed, Map<Long, StoredEvent> events) {
        jdbc.sql("""
                        SELECT transition_sequence, aggregate_version, from_state, to_state,
                               actor, reason, occurred_at
                        FROM operation_transition
                        WHERE operation_id = :operationId
                          AND aggregate_version BETWEEN 1 AND :maximumVersion
                        ORDER BY aggregate_version
                        """)
                .param("operationId", seed.operationId().value())
                .param("maximumVersion", seed.version())
                .query((row, rowNumber) -> new TransitionEvent(
                        row.getLong("aggregate_version"),
                        OperationState.valueOf(row.getString("from_state")),
                        OperationState.valueOf(row.getString("to_state")),
                        row.getString("actor"),
                        row.getString("reason"),
                        instant(row, "occurred_at"),
                        transitionEvidence(
                                seed.operationId(), row.getLong("transition_sequence"))))
                .list().forEach(event -> putEvent(events, event));
    }

    private void loadAttempts(OperationSeed seed, Map<Long, StoredEvent> events) {
        jdbc.sql("""
                        SELECT attempt_order, attempt_id, predecessor_attempt_id,
                               retry_basis, retry_policy_version,
                               authorization_evidence_ref, aggregate_version, created_at
                        FROM operation_attempt
                        WHERE operation_id = :operationId
                          AND aggregate_version <= :maximumVersion
                        ORDER BY aggregate_version
                        """)
                .param("operationId", seed.operationId().value())
                .param("maximumVersion", seed.version())
                .query((row, rowNumber) -> {
                    UUID predecessor = row.getObject("predecessor_attempt_id", UUID.class);
                    return new AttemptEvent(
                            row.getLong("aggregate_version"),
                            row.getInt("attempt_order"),
                            new AttemptId(row.getObject("attempt_id", UUID.class)),
                            predecessor == null ? Optional.empty()
                                    : Optional.of(new AttemptId(predecessor)),
                            row.getString("retry_basis"),
                            row.getString("retry_policy_version"),
                            new EvidenceRef(row.getString("authorization_evidence_ref")),
                            instant(row, "created_at"));
                })
                .list().forEach(event -> putEvent(events, event));
    }

    private void loadFinalities(OperationSeed seed, Map<Long, StoredEvent> events) {
        jdbc.sql("""
                        SELECT finality_type, history_order, aggregate_version,
                               finality_status, authority, policy_version, updated_at
                        FROM operation_finality
                        WHERE operation_id = :operationId
                          AND aggregate_version BETWEEN 1 AND :maximumVersion
                        ORDER BY aggregate_version
                        """)
                .param("operationId", seed.operationId().value())
                .param("maximumVersion", seed.version())
                .query((row, rowNumber) -> {
                    FinalityType type = FinalityType.valueOf(row.getString("finality_type"));
                    int historyOrder = row.getInt("history_order");
                    return new FinalityEvent(
                            row.getLong("aggregate_version"),
                            historyOrder,
                            FinalityRecord.assessed(
                                    type,
                                    FinalityStatus.valueOf(row.getString("finality_status")),
                                    row.getString("authority"),
                                    row.getString("policy_version"),
                                    instant(row, "updated_at"),
                                    finalityEvidence(
                                            seed.operationId(), type, historyOrder)));
                })
                .list().forEach(event -> putEvent(events, event));
    }

    private static void putEvent(Map<Long, StoredEvent> events, StoredEvent event) {
        if (events.put(event.aggregateVersion(), event) != null) {
            throw new IllegalStateException("operation history duplicates a version");
        }
    }

    private List<EvidenceRef> transitionEvidence(
            OperationId operationId, long transitionSequence) {
        return jdbc.sql("""
                        SELECT evidence_ref
                        FROM operation_transition_evidence
                        WHERE operation_id = :operationId
                          AND transition_sequence = :transitionSequence
                        ORDER BY evidence_order
                        """)
                .param("operationId", operationId.value())
                .param("transitionSequence", transitionSequence)
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
    }

    private List<EvidenceRef> finalityEvidence(
            OperationId operationId, FinalityType type, int historyOrder) {
        return jdbc.sql("""
                        SELECT evidence_ref
                        FROM operation_finality_evidence
                        WHERE operation_id = :operationId
                          AND finality_type = :finalityType
                          AND history_order = :historyOrder
                        ORDER BY evidence_order
                        """)
                .param("operationId", operationId.value())
                .param("finalityType", type.name())
                .param("historyOrder", historyOrder)
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
    }

    private void saveInTransaction(TokenOperation operation, long expectedVersion) {
        TokenOperation current = findById(operation.operationId())
                .orElseThrow(() -> new IllegalStateException("operation was not found"));
        if (current.version() != expectedVersion
                || operation.version() != expectedVersion + 1) {
            throw new IllegalStateException("operation version conflict");
        }
        requireSameIdentity(current, operation);
        EventDelta delta = findDelta(current, operation);
        int updated = jdbc.sql("""
                        UPDATE token_operation
                        SET lifecycle_state = :state,
                            aggregate_version = :newVersion,
                            updated_at = :updatedAt
                        WHERE operation_id = :operationId
                          AND aggregate_version = :expectedVersion
                        """)
                .param("state", operation.state().name())
                .param("newVersion", operation.version())
                .param("updatedAt", utc(delta.occurredAt()))
                .param("operationId", operation.operationId().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("operation version conflict");
        }
        persistDelta(operation.operationId(), operation.version(), delta);
    }

    private static void requireSameIdentity(
            TokenOperation current, TokenOperation changed) {
        if (!current.operationId().equals(changed.operationId())
                || !current.acceptanceContext().equals(changed.acceptanceContext())
                || current.kind() != changed.kind()
                || !current.quantity().equals(changed.quantity())
                || !current.createdAt().equals(changed.createdAt())) {
            throw new IllegalArgumentException("immutable operation identity changed");
        }
    }

    private static EventDelta findDelta(
            TokenOperation current, TokenOperation changed) {
        List<EventDelta> deltas = new ArrayList<>();
        if (hasOneAppended(current.transitions(), changed.transitions())) {
            deltas.add(new TransitionDelta(
                    changed.transitions().size(), changed.transitions().getLast()));
        } else if (!current.transitions().equals(changed.transitions())) {
            throw new IllegalArgumentException("transition history is not append-only");
        }
        if (hasOneAppended(current.attempts(), changed.attempts())) {
            deltas.add(new AttemptDelta(
                    changed.attempts().size() - 1, changed.attempts().getLast()));
        } else if (!current.attempts().equals(changed.attempts())) {
            throw new IllegalArgumentException("attempt history is not append-only");
        }
        for (FinalityType type : FinalityType.values()) {
            List<FinalityRecord> before = current.finalityHistory(type);
            List<FinalityRecord> after = changed.finalityHistory(type);
            if (hasOneAppended(before, after)) {
                deltas.add(new FinalityDelta(type, after.size() - 1, after.getLast()));
            } else if (!before.equals(after)) {
                throw new IllegalArgumentException("finality history is not append-only");
            }
        }
        if (deltas.size() != 1) {
            throw new IllegalArgumentException(
                    "one aggregate version must append exactly one event");
        }
        return deltas.getFirst();
    }

    private static <T> boolean hasOneAppended(List<T> before, List<T> after) {
        return after.size() == before.size() + 1
                && after.subList(0, before.size()).equals(before);
    }

    private void persistDelta(
            OperationId operationId, long aggregateVersion, EventDelta delta) {
        switch (delta) {
            case TransitionDelta transition ->
                    insertTransition(operationId, aggregateVersion, transition);
            case AttemptDelta attempt ->
                    insertAttempt(operationId, aggregateVersion, attempt);
            case FinalityDelta finality ->
                    insertFinality(operationId, aggregateVersion, finality);
        }
    }

    private void insertTransition(
            OperationId operationId,
            long aggregateVersion,
            TransitionDelta delta) {
        OperationTransition transition = delta.transition();
        jdbc.sql("""
                        INSERT INTO operation_transition (
                            operation_id, transition_sequence, aggregate_version,
                            from_state, to_state, actor, reason, occurred_at)
                        VALUES (
                            :operationId, :sequence, :aggregateVersion,
                            :fromState, :toState, :actor, :reason, :occurredAt)
                        """)
                .param("operationId", operationId.value())
                .param("sequence", delta.sequence())
                .param("aggregateVersion", aggregateVersion)
                .param("fromState", transition.from().name())
                .param("toState", transition.to().name())
                .param("actor", transition.actor())
                .param("reason", transition.reason())
                .param("occurredAt", utc(transition.occurredAt()))
                .update();
        insertTransitionEvidence(operationId, delta.sequence(), transition.evidenceRefs());
    }

    private void insertTransitionEvidence(
            OperationId operationId, long sequence, List<EvidenceRef> evidence) {
        for (int index = 0; index < evidence.size(); index++) {
            jdbc.sql("""
                            INSERT INTO operation_transition_evidence (
                                operation_id, transition_sequence, evidence_order, evidence_ref)
                            VALUES (:operationId, :sequence, :evidenceOrder, :evidenceRef)
                            """)
                    .param("operationId", operationId.value())
                    .param("sequence", sequence)
                    .param("evidenceOrder", index)
                    .param("evidenceRef", evidence.get(index).value())
                    .update();
        }
    }

    private void insertAttempt(
            OperationId operationId, long aggregateVersion, AttemptDelta delta) {
        OperationAttempt attempt = delta.attempt();
        RetryAuthorization retry = attempt.retryAuthorization().orElse(null);
        jdbc.sql("""
                        INSERT INTO operation_attempt (
                            operation_id, attempt_order, attempt_id, predecessor_attempt_id,
                            retry_basis, retry_policy_version, authorization_evidence_ref,
                            aggregate_version, created_at)
                        VALUES (
                            :operationId, :attemptOrder, :attemptId, :predecessorAttemptId,
                            :retryBasis, :retryPolicyVersion, :authorizationEvidence,
                            :aggregateVersion, :createdAt)
                        """)
                .param("operationId", operationId.value())
                .param("attemptOrder", delta.order())
                .param("attemptId", attempt.attemptId().value())
                .param("predecessorAttemptId",
                        attempt.predecessor().map(AttemptId::value).orElse(null))
                .param("retryBasis", retry == null ? null : retry.basis().name())
                .param("retryPolicyVersion", retry == null ? null : retry.policyVersion())
                .param("authorizationEvidence", attempt.authorizationEvidence().value())
                .param("aggregateVersion", aggregateVersion)
                .param("createdAt", utc(attempt.createdAt()))
                .update();
    }

    private void insertFinality(
            OperationId operationId, long aggregateVersion, FinalityDelta delta) {
        FinalityRecord finality = delta.finality();
        jdbc.sql("""
                        INSERT INTO operation_finality (
                            operation_id, finality_type, history_order, aggregate_version,
                            finality_status, authority, policy_version, updated_at)
                        VALUES (
                            :operationId, :finalityType, :historyOrder, :aggregateVersion,
                            :status, :authority, :policyVersion, :updatedAt)
                        """)
                .param("operationId", operationId.value())
                .param("finalityType", delta.type().name())
                .param("historyOrder", delta.historyOrder())
                .param("aggregateVersion", aggregateVersion)
                .param("status", finality.status().name())
                .param("authority", finality.authority())
                .param("policyVersion", finality.policyVersion())
                .param("updatedAt", utc(finality.updatedAt()))
                .update();
        for (int index = 0; index < finality.evidenceRefs().size(); index++) {
            jdbc.sql("""
                            INSERT INTO operation_finality_evidence (
                                operation_id, finality_type, history_order,
                                evidence_order, evidence_ref)
                            VALUES (
                                :operationId, :finalityType, :historyOrder,
                                :evidenceOrder, :evidenceRef)
                            """)
                    .param("operationId", operationId.value())
                    .param("finalityType", delta.type().name())
                    .param("historyOrder", delta.historyOrder())
                    .param("evidenceOrder", index)
                    .param("evidenceRef", finality.evidenceRefs().get(index).value())
                    .update();
        }
    }

    private static java.math.BigInteger exactInteger(
            ResultSet row, String column) throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "value").atOffset(ZoneOffset.UTC);
    }

    private record Binding(
            int canonicalizationVersion,
            String commandDigest,
            OperationId operationId) {
    }

    private record OperationSeed(
            OperationId operationId,
            OperationAcceptanceContext acceptanceContext,
            OperationKind kind,
            TokenQuantity quantity,
            OperationState state,
            long version,
            Instant createdAt,
            EvidenceRef acceptanceEvidence) {
    }

    private sealed interface StoredEvent
            permits TransitionEvent, AttemptEvent, FinalityEvent {

        long aggregateVersion();

        TokenOperation apply(TokenOperation operation);
    }

    private record TransitionEvent(
            long aggregateVersion,
            OperationState from,
            OperationState to,
            String actor,
            String reason,
            Instant occurredAt,
            List<EvidenceRef> evidence) implements StoredEvent {

        @Override
        public TokenOperation apply(TokenOperation operation) {
            if (operation.state() != from) {
                throw new IllegalStateException("persisted transition source does not match");
            }
            return operation.transition(
                    operation.version(), to, actor, reason, occurredAt, evidence);
        }
    }

    private record AttemptEvent(
            long aggregateVersion,
            int order,
            AttemptId attemptId,
            Optional<AttemptId> predecessor,
            String retryBasis,
            String retryPolicyVersion,
            EvidenceRef evidence,
            Instant createdAt) implements StoredEvent {

        @Override
        public TokenOperation apply(TokenOperation operation) {
            if (order != operation.attempts().size()) {
                throw new IllegalStateException("persisted attempt order does not match");
            }
            if (order == 0) {
                return operation.addInitialAttempt(
                        operation.version(), attemptId, evidence, createdAt);
            }
            AttemptId predecessorId = predecessor.orElseThrow(() ->
                    new IllegalStateException("follow-up attempt has no predecessor"));
            RetryAuthorization retry = new RetryAuthorization(
                    predecessorId,
                    RetryAuthorization.Basis.valueOf(retryBasis),
                    retryPolicyVersion,
                    evidence);
            return operation.addFollowUpAttempt(
                    operation.version(), attemptId, retry, createdAt);
        }
    }

    private record FinalityEvent(
            long aggregateVersion,
            int historyOrder,
            FinalityRecord finality) implements StoredEvent {

        @Override
        public TokenOperation apply(TokenOperation operation) {
            if (historyOrder != operation.finalityHistory(finality.type()).size()) {
                throw new IllegalStateException("persisted finality order does not match");
            }
            return operation.recordFinality(operation.version(), finality);
        }
    }

    private sealed interface EventDelta
            permits TransitionDelta, AttemptDelta, FinalityDelta {

        Instant occurredAt();
    }

    private record TransitionDelta(
            long sequence, OperationTransition transition) implements EventDelta {

        @Override
        public Instant occurredAt() {
            return transition.occurredAt();
        }
    }

    private record AttemptDelta(
            int order, OperationAttempt attempt) implements EventDelta {

        @Override
        public Instant occurredAt() {
            return attempt.createdAt();
        }
    }

    private record FinalityDelta(
            FinalityType type,
            int historyOrder,
            FinalityRecord finality) implements EventDelta {

        @Override
        public Instant occurredAt() {
            return finality.updatedAt();
        }
    }
}
