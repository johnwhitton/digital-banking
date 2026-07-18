package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.math.BigInteger;
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
import java.util.Set;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit JDBC acceptance, replay, inbox, lifecycle, and finality store. */
public final class PostgresWalletTransferRepository implements WalletTransferRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    public PostgresWalletTransferRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Override
    public Acceptance accept(WalletTransferOperation proposed) {
        Objects.requireNonNull(proposed, "proposed");
        if (proposed.purpose() != WalletTransferOperation.Purpose.USER_TRANSFER) {
            throw new IllegalArgumentException("redemption custody requires its burn correlation");
        }
        return accept(proposed, null);
    }

    @Override
    public Acceptance acceptRedemption(
            WalletTransferOperation proposed, OperationId burnOperationId) {
        Objects.requireNonNull(proposed, "proposed");
        Objects.requireNonNull(burnOperationId, "burnOperationId");
        if (proposed.purpose() != WalletTransferOperation.Purpose.REDEMPTION_CUSTODY) {
            throw new IllegalArgumentException("redemption custody purpose is required");
        }
        return accept(proposed, burnOperationId);
    }

    private Acceptance accept(
            WalletTransferOperation proposed, OperationId burnOperationId) {
        return Objects.requireNonNull(transaction.execute(status -> {
            int inserted = insertOperation(proposed);
            if (inserted == 0) {
                WalletTransferOperation retained = findByScope(proposed).orElseThrow();
                if (retained.commandVersion() != proposed.commandVersion()
                        || !retained.commandDigest().equals(proposed.commandDigest())) {
                    throw new IdempotencyConflictException();
                }
                if (burnOperationId != null && !findRedemptionByBurn(burnOperationId)
                        .map(WalletTransferOperation::operationId)
                        .filter(retained.operationId()::equals).isPresent()) {
                    throw new IdempotencyConflictException();
                }
                return new Acceptance(retained, true);
            }
            if (burnOperationId != null) {
                insertRedemptionCorrelation(proposed, burnOperationId);
            }
            insertTransition(proposed, null);
            for (FinalityType type : FinalityType.values()) {
                insertFinality(proposed.operationId(), 0,
                        proposed.finalityHistories().get(type).getFirst());
            }
            jdbc.sql("""
                    INSERT INTO operation_outbox (
                        event_id, operation_id, transfer_id, wallet_transfer_id,
                        event_type, event_version, payload_schema_version, payload,
                        status, created_at, available_at, delivery_attempt_count, updated_at)
                    VALUES (
                        :eventId, NULL, NULL, :operationId,
                        'WalletTransferAccepted', 1, 1,
                        jsonb_build_object('transferPurpose', :transferPurpose),
                        'PENDING', :createdAt, :createdAt, 0, :createdAt)
                    """)
                    .param("eventId", UUID.randomUUID())
                    .param("operationId", proposed.operationId().value())
                    .param("transferPurpose", proposed.purpose().name())
                    .param("createdAt", utc(proposed.createdAt()))
                    .update();
            return new Acceptance(proposed, false);
        }));
    }

    @Override
    public Optional<WalletTransferOperation> findRedemptionByBurn(
            OperationId burnOperationId) {
        Objects.requireNonNull(burnOperationId, "burnOperationId");
        return jdbc.sql(selectOperation() + """
                WHERE operation_id = (
                    SELECT custody_operation_id
                    FROM ethereum_redemption_correlation
                    WHERE burn_operation_id = :burnOperationId)
                """)
                .param("burnOperationId", burnOperationId.value())
                .query(this::map).optional().map(this::hydrate);
    }

    @Override
    public Optional<WalletTransferOperation> findById(OperationId operationId) {
        Objects.requireNonNull(operationId, "operationId");
        return jdbc.sql(selectOperation() + " WHERE operation_id = :operationId")
                .param("operationId", operationId.value())
                .query(this::map)
                .optional()
                .map(this::hydrate);
    }

    @Override
    public Optional<WalletTransferOperation> findByIdempotency(
            ParticipantScope participant, String idempotencyKeyDigest) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(idempotencyKeyDigest, "idempotencyKeyDigest");
        return jdbc.sql(selectOperation() + """
                WHERE tenant_id = :tenantId AND participant_id = :participantId
                  AND idempotency_key_digest = :keyDigest
                """)
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .param("keyDigest", idempotencyKeyDigest)
                .query(this::map).optional().map(this::hydrate);
    }

    @Override
    public StartResult startDelivery(
            UUID deliveryId, OperationId operationId, Instant startedAt) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(startedAt, "startedAt");
        return Objects.requireNonNull(transaction.execute(status -> {
            boolean ownedDelivery = jdbc.sql("""
                    SELECT EXISTS (
                        SELECT 1 FROM operation_outbox
                        WHERE event_id = :deliveryId
                          AND wallet_transfer_id = :operationId
                          AND event_type = 'WalletTransferAccepted')
                    """)
                    .param("deliveryId", deliveryId)
                    .param("operationId", operationId.value())
                    .query(Boolean.class).single();
            if (!ownedDelivery) {
                throw new IllegalArgumentException(
                        "delivery does not belong to the wallet transfer");
            }
            int inserted = jdbc.sql("""
                    INSERT INTO wallet_transfer_handler_inbox (
                        delivery_id, operation_id, result, applied_at)
                    VALUES (:deliveryId, :operationId, 'ATTEMPT_STARTED', :startedAt)
                    ON CONFLICT (delivery_id) DO NOTHING
                    """)
                    .param("deliveryId", deliveryId)
                    .param("operationId", operationId.value())
                    .param("startedAt", utc(startedAt))
                    .update();
            WalletTransferOperation current = findById(operationId).orElseThrow();
            if (inserted == 0) {
                return new StartResult(current, true);
            }
            if (current.status() != WalletTransferOperation.Status.ACCEPTED) {
                throw new IllegalStateException("wallet transfer delivery is not accepted");
            }
            WalletTransferOperation changed = current.transition(
                    current.version(), WalletTransferOperation.Status.SIGNING,
                    new EvidenceRef("internal:wallet-transfer:attempt-started:"
                            + current.attemptId()), startedAt);
            update(changed, current.version());
            insertTransition(changed, current.status());
            return new StartResult(changed, false);
        }));
    }

    @Override
    public void save(WalletTransferOperation operation, long expectedVersion) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(transaction.execute(status -> {
            WalletTransferOperation current = findById(operation.operationId()).orElseThrow();
            if (current.version() != expectedVersion || operation.version() != expectedVersion + 1) {
                throw new IllegalStateException("wallet transfer version conflict");
            }
            update(operation, expectedVersion);
            insertTransition(operation, current.status());
            List<FinalityRecord> before = current.finalityHistories()
                    .get(FinalityType.BLOCKCHAIN);
            List<FinalityRecord> after = operation.finalityHistories()
                    .get(FinalityType.BLOCKCHAIN);
            if (after.size() == before.size() + 1) {
                insertFinality(operation.operationId(), before.size(), after.getLast());
            } else if (after.size() != before.size()) {
                throw new IllegalStateException("wallet transfer finality history is invalid");
            }
            return Boolean.TRUE;
        }));
    }

    private int insertOperation(WalletTransferOperation value) {
        var source = value.source();
        var destination = value.destination();
        var unit = value.quantity().unit();
        return jdbc.sql("""
                INSERT INTO wallet_transfer_operation (
                    operation_id, transfer_id, effect_id, tenant_id, participant_id,
                    idempotency_key_digest, command_version, command_digest,
                    asset_id, unit_id, unit_version, unit_scale, unit_max_atomic,
                    quantity_atomic, source_wallet_ref, source_address,
                    source_key_alias, source_registry_version, source_key_version,
                    destination_wallet_ref, destination_address, destination_key_alias,
                    destination_registry_version, destination_key_version, transfer_purpose, network,
                    contract_address, contract_version, finality_policy_version,
                    attempt_id, operation_status, aggregate_version, created_at, updated_at)
                VALUES (
                    :operationId, :transferId, :effectId, :tenantId, :participantId,
                    :keyDigest, :commandVersion, :commandDigest,
                    :assetId, :unitId, :unitVersion, :unitScale, :unitMaximum,
                    :quantity, :sourceRef, :sourceAddress, :sourceKeyAlias,
                    :sourceRegistryVersion, :sourceKeyVersion, :destinationRef,
                    :destinationAddress, :destinationKeyAlias,
                    :destinationRegistryVersion, :destinationKeyVersion, :purpose, :network,
                    :contractAddress, :contractVersion, :policyVersion,
                    :attemptId, :operationStatus, :aggregateVersion, :createdAt, :updatedAt)
                ON CONFLICT (tenant_id, participant_id, idempotency_key_digest) DO NOTHING
                """)
                .param("operationId", value.operationId().value())
                .param("transferId", value.transferId().value())
                .param("effectId", value.effectId().value())
                .param("tenantId", value.participant().tenantId())
                .param("participantId", value.participant().participantId())
                .param("keyDigest", value.idempotencyKeyDigest())
                .param("commandVersion", value.commandVersion())
                .param("commandDigest", value.commandDigest())
                .param("assetId", unit.assetId()).param("unitId", unit.unitId())
                .param("unitVersion", unit.version()).param("unitScale", unit.scale())
                .param("unitMaximum", unit.maxAtomicUnits())
                .param("quantity", value.quantity().atomicUnits())
                .param("sourceRef", source.reference().value())
                .param("sourceAddress", source.normalizedAddress())
                .param("sourceKeyAlias", source.keyReference().value())
                .param("sourceRegistryVersion", source.registryVersion())
                .param("sourceKeyVersion", source.keyVersion())
                .param("destinationRef", destination.reference().value())
                .param("destinationAddress", destination.normalizedAddress())
                .param("destinationKeyAlias", destination.keyReference().value())
                .param("destinationRegistryVersion", destination.registryVersion())
                .param("destinationKeyVersion", destination.keyVersion())
                .param("purpose", value.purpose().name())
                .param("network", value.network().name())
                .param("contractAddress", value.contractAddress())
                .param("contractVersion", value.contractVersion())
                .param("policyVersion", value.finalityPolicyVersion())
                .param("attemptId", value.attemptId().value())
                .param("operationStatus", value.status().name())
                .param("aggregateVersion", value.version())
                .param("createdAt", utc(value.createdAt()))
                .param("updatedAt", utc(value.updatedAt()))
                .update();
    }

    private void insertRedemptionCorrelation(
            WalletTransferOperation custody, OperationId burnOperationId) {
        int inserted = jdbc.sql("""
                INSERT INTO ethereum_redemption_correlation (
                    correlation_id, burn_operation_id, custody_operation_id,
                    custody_effect_id, custody_attempt_id, correlation_status,
                    created_at, updated_at)
                SELECT :correlationId, burn.operation_id, custody.operation_id,
                       custody.effect_id, custody.attempt_id, 'AWAITING_CUSTODY',
                       :createdAt, :createdAt
                FROM token_operation burn
                JOIN wallet_transfer_operation custody
                  ON custody.operation_id = :custodyOperationId
                WHERE burn.operation_id = :burnOperationId
                  AND burn.operation_kind = 'BURN'
                  AND custody.transfer_purpose = 'REDEMPTION_CUSTODY'
                  AND burn.tenant_id = custody.tenant_id
                  AND burn.participant_id = custody.participant_id
                  AND burn.asset_id = custody.asset_id
                  AND burn.unit_id = custody.unit_id
                  AND burn.unit_version = custody.unit_version
                  AND burn.unit_scale = custody.unit_scale
                  AND burn.quantity_atomic = custody.quantity_atomic
                """)
                .param("correlationId", custody.transferId().value())
                .param("burnOperationId", burnOperationId.value())
                .param("custodyOperationId", custody.operationId().value())
                .param("createdAt", utc(custody.createdAt()))
                .update();
        if (inserted != 1) {
            throw new IllegalArgumentException(
                    "burn operation does not match redemption custody context");
        }
    }

    private Optional<WalletTransferOperation> findByScope(WalletTransferOperation proposed) {
        return findByIdempotency(proposed.participant(), proposed.idempotencyKeyDigest());
    }

    private void update(WalletTransferOperation value, long expectedVersion) {
        int changed = jdbc.sql("""
                UPDATE wallet_transfer_operation
                SET operation_status = :status, aggregate_version = :version,
                    updated_at = :updatedAt
                WHERE operation_id = :operationId AND aggregate_version = :expectedVersion
                """)
                .param("status", value.status().name())
                .param("version", value.version())
                .param("updatedAt", utc(value.updatedAt()))
                .param("operationId", value.operationId().value())
                .param("expectedVersion", expectedVersion)
                .update();
        if (changed != 1) {
            throw new IllegalStateException("wallet transfer update was fenced");
        }
    }

    private void insertTransition(
            WalletTransferOperation value, WalletTransferOperation.Status from) {
        jdbc.sql("""
                INSERT INTO wallet_transfer_transition (
                    operation_id, aggregate_version, from_status, to_status,
                    occurred_at, evidence_ref)
                VALUES (:operationId, :version, :fromStatus, :toStatus, :occurredAt, :evidence)
                """)
                .param("operationId", value.operationId().value())
                .param("version", value.version())
                .param("fromStatus", from == null ? null : from.name())
                .param("toStatus", value.status().name())
                .param("occurredAt", utc(value.updatedAt()))
                .param("evidence", value.evidence().getLast().value())
                .update();
    }

    private void insertFinality(
            OperationId operationId, int order, FinalityRecord value) {
        jdbc.sql("""
                INSERT INTO wallet_transfer_finality (
                    operation_id, finality_type, history_order, finality_status,
                    authority, policy_version, updated_at, evidence_ref)
                VALUES (:operationId, :type, :historyOrder, :status,
                    :authority, :policyVersion, :updatedAt, :evidence)
                """)
                .param("operationId", operationId.value())
                .param("type", value.type().name())
                .param("historyOrder", order)
                .param("status", value.status().name())
                .param("authority", value.authority())
                .param("policyVersion", value.policyVersion())
                .param("updatedAt", utc(value.updatedAt()))
                .param("evidence", value.evidenceRefs().isEmpty()
                        ? null : value.evidenceRefs().getLast().value())
                .update();
    }

    private WalletTransferOperation hydrate(Row row) {
        List<EvidenceRef> evidence = jdbc.sql("""
                SELECT evidence_ref FROM wallet_transfer_transition
                WHERE operation_id = :operationId ORDER BY aggregate_version
                """)
                .param("operationId", row.operationId().value())
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
        Map<FinalityType, List<FinalityRecord>> finalities = new EnumMap<>(FinalityType.class);
        List<FinalityRow> rows = jdbc.sql("""
                SELECT finality_type, finality_status, authority, policy_version,
                       updated_at, evidence_ref
                FROM wallet_transfer_finality
                WHERE operation_id = :operationId
                ORDER BY finality_type, history_order
                """)
                .param("operationId", row.operationId().value())
                .query((result, number) -> new FinalityRow(
                        FinalityType.valueOf(result.getString("finality_type")),
                        FinalityStatus.valueOf(result.getString("finality_status")),
                        result.getString("authority"), result.getString("policy_version"),
                        instant(result.getObject("updated_at", OffsetDateTime.class)),
                        result.getString("evidence_ref")))
                .list();
        for (FinalityType type : FinalityType.values()) {
            finalities.put(type, rows.stream().filter(value -> value.type() == type)
                    .map(FinalityRow::record).toList());
        }
        return row.operation(evidence, finalities);
    }

    private Row map(ResultSet result, int rowNumber) throws SQLException {
        AssetUnit unit = new AssetUnit(
                result.getString("asset_id"), result.getString("unit_id"),
                result.getInt("unit_version"), result.getInt("unit_scale"),
                decimal(result, "unit_max_atomic"));
        return new Row(
                new OperationId(result.getObject("operation_id", UUID.class)),
                new TransferId(result.getObject("transfer_id", UUID.class)),
                new TransferEffect.Id(result.getObject("effect_id", UUID.class)),
                new ParticipantScope(result.getString("tenant_id"),
                        result.getString("participant_id")),
                result.getString("idempotency_key_digest"),
                result.getInt("command_version"), result.getString("command_digest"),
                TokenQuantity.ofAtomic(decimal(result, "quantity_atomic"), unit),
                WalletTransferOperation.Purpose.valueOf(
                        result.getString("transfer_purpose")),
                snapshot(result, "source", false), snapshot(result, "destination",
                        "REDEMPTION_CUSTODY".equals(result.getString("transfer_purpose"))),
                SettlementNetwork.valueOf(result.getString("network")),
                result.getString("contract_address"), result.getString("contract_version"),
                result.getString("finality_policy_version"),
                new AttemptId(result.getObject("attempt_id", UUID.class)),
                WalletTransferOperation.Status.valueOf(result.getString("operation_status")),
                result.getLong("aggregate_version"),
                instant(result.getObject("created_at", OffsetDateTime.class)),
                instant(result.getObject("updated_at", OffsetDateTime.class)));
    }

    private static WalletTransferOperation.WalletSnapshot snapshot(
            ResultSet result, String prefix, boolean redemptionAdmin) throws SQLException {
        return new WalletTransferOperation.WalletSnapshot(
                new WalletReference(result.getString(prefix + "_wallet_ref")),
                redemptionAdmin ? WalletIdentityRegistry.OwnerCategory.ADMIN
                        : WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.ETHEREUM, result.getString(prefix + "_address"),
                new KeyAlias(result.getString(prefix + "_key_alias")),
                result.getString(prefix + "_registry_version"),
                result.getString(prefix + "_key_version"),
                redemptionAdmin
                        ? Set.of(WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY,
                                WalletIdentityRegistry.Purpose.MINT_AUTHORITY,
                                WalletIdentityRegistry.Purpose.BURN_AUTHORITY)
                        : Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
    }

    private static String selectOperation() {
        return """
                SELECT operation_id, transfer_id, effect_id, tenant_id, participant_id,
                       idempotency_key_digest, command_version, command_digest,
                       asset_id, unit_id, unit_version, unit_scale, unit_max_atomic,
                       quantity_atomic, source_wallet_ref, source_address,
                       source_key_alias, source_registry_version, source_key_version,
                       destination_wallet_ref, destination_address, destination_key_alias,
                       destination_registry_version, destination_key_version, transfer_purpose, network,
                       contract_address, contract_version, finality_policy_version,
                       attempt_id, operation_status, aggregate_version, created_at, updated_at
                FROM wallet_transfer_operation
                """;
    }

    private static BigInteger decimal(ResultSet result, String column) throws SQLException {
        return result.getBigDecimal(column).toBigIntegerExact();
    }

    private static OffsetDateTime utc(Instant value) {
        return OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }

    private record Row(
            OperationId operationId, TransferId transferId, TransferEffect.Id effectId,
            ParticipantScope participant, String idempotencyKeyDigest, int commandVersion,
            String commandDigest, TokenQuantity quantity,
            WalletTransferOperation.Purpose purpose,
            WalletTransferOperation.WalletSnapshot source,
            WalletTransferOperation.WalletSnapshot destination,
            SettlementNetwork network, String contractAddress, String contractVersion,
            String finalityPolicyVersion, AttemptId attemptId,
            WalletTransferOperation.Status status, long version,
            Instant createdAt, Instant updatedAt) {

        WalletTransferOperation operation(
                List<EvidenceRef> evidence,
                Map<FinalityType, List<FinalityRecord>> finalities) {
            return new WalletTransferOperation(
                    operationId, transferId, effectId, participant, idempotencyKeyDigest,
                    commandVersion, commandDigest, quantity, purpose, source, destination, network,
                    contractAddress, contractVersion, finalityPolicyVersion, attemptId,
                    status, version, createdAt, updatedAt, evidence, finalities);
        }
    }

    private record FinalityRow(
            FinalityType type, FinalityStatus status, String authority,
            String policyVersion, Instant updatedAt, String evidence) {

        FinalityRecord record() {
            return status == FinalityStatus.NOT_ASSESSED
                    ? FinalityRecord.notAssessed(type)
                    : FinalityRecord.assessed(
                            type, status, authority, policyVersion, updatedAt,
                            List.of(new EvidenceRef(evidence)));
        }
    }
}
