package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.TransferAcceptance;
import io.github.johnwhitton.digitalbanking.application.TransferAcceptancePlan;
import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityRecord;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferAcceptanceContext;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferParticipant;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferStatus;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit PostgreSQL mapping for atomic transfer acceptance and inbox preparation. */
public final class PostgresTransferRepository implements TransferRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final PostgresSettlementTransferRepository settlements;

    public PostgresTransferRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.settlements = new PostgresSettlementTransferRepository(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public TransferAcceptance accept(
            ParticipantScope participant,
            IdempotencyKey key,
            CanonicalCommandMetadata requestCommand,
            Supplier<TransferAcceptancePlan> transferFactory) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(requestCommand, "requestCommand");
        Objects.requireNonNull(transferFactory, "transferFactory");
        return Objects.requireNonNull(transactions.execute(status ->
                acceptInTransaction(participant, key, requestCommand, transferFactory)));
    }

    @Override
    public Optional<Transfer> findById(
            TransferId transferId, ParticipantScope participant) {
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(participant, "participant");
        return Objects.requireNonNull(transactions.execute(status -> jdbc.sql("""
                        SELECT transfer_id, tenant_id, participant_id,
                               idempotency_key_digest,
                               request_canonicalization_version, request_digest,
                               resolved_canonicalization_version, resolved_digest,
                               currency, source_bank_account_ref,
                               destination_bank_account_ref, sender_wallet_ref,
                               recipient_wallet_ref, settlement_network,
                               route_version, wallet_policy_version,
                               asset_id, unit_id, unit_version, unit_scale,
                               unit_max_atomic, quantity_atomic, transfer_status,
                               aggregate_version, acceptance_evidence_ref, created_at
                        FROM banking_transfer
                        WHERE transfer_id = :transferId
                          AND tenant_id = :tenantId
                          AND participant_id = :participantId
                        FOR SHARE
                        """)
                .param("transferId", transferId.value())
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .query(this::mapSeed)
                .optional()
                .map(this::hydrate)));
    }

    @Override
    public Optional<SettlementTransfer> findSettlementById(
            TransferId transferId, ParticipantScope participant) {
        return settlements.findById(transferId, participant);
    }

    @Override
    public PreparationResult prepareFirstWithdrawal(
            UUID deliveryId,
            TransferId transferId,
            TransferTransition.Id transitionId,
            Instant preparedAt) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        Objects.requireNonNull(transferId, "transferId");
        Objects.requireNonNull(transitionId, "transitionId");
        Instant canonicalTime = canonical(preparedAt);
        return Objects.requireNonNull(transactions.execute(status -> {
            Seed seed = jdbc.sql("""
                            SELECT transfer_id, tenant_id, participant_id,
                                   idempotency_key_digest,
                                   request_canonicalization_version, request_digest,
                                   resolved_canonicalization_version, resolved_digest,
                                   currency, source_bank_account_ref,
                                   destination_bank_account_ref, sender_wallet_ref,
                                   recipient_wallet_ref, settlement_network,
                                   route_version, wallet_policy_version,
                                   asset_id, unit_id, unit_version, unit_scale,
                                   unit_max_atomic, quantity_atomic, transfer_status,
                                   aggregate_version, acceptance_evidence_ref, created_at
                            FROM banking_transfer
                            WHERE transfer_id = :transferId
                            FOR UPDATE
                            """)
                    .param("transferId", transferId.value())
                    .query(this::mapSeed).optional()
                    .orElseThrow(() -> new IllegalStateException("transfer was not found"));
            boolean ownsDelivery = jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM operation_outbox
                                WHERE event_id = :deliveryId
                                  AND operation_id IS NULL
                                  AND transfer_id = :transferId
                                  AND event_type = 'TransferAccepted')
                            """)
                    .param("deliveryId", deliveryId)
                    .param("transferId", transferId.value())
                    .query(Boolean.class).single();
            if (!ownsDelivery) {
                throw new IllegalStateException(
                        "delivery identity does not belong to transfer");
            }
            Optional<UUID> applied = jdbc.sql("""
                            SELECT transfer_id FROM transfer_handler_inbox
                            WHERE delivery_id = :deliveryId
                            """)
                    .param("deliveryId", deliveryId)
                    .query(UUID.class).optional();
            if (applied.isPresent()) {
                if (!applied.orElseThrow().equals(transferId.value())) {
                    throw new IllegalStateException(
                            "delivery identity is bound to another transfer");
                }
                return PreparationResult.DUPLICATE;
            }
            Transfer current = hydrate(seed);
            TransferEffect first = current.effects().getFirst();
            EvidenceRef evidence = new EvidenceRef(
                    "internal:transfer-delivery:" + deliveryId);
            Transfer changed = current.prepareEffect(
                    current.version(), first.effectId(), transitionId,
                    evidence, canonicalTime);

            requireOne(jdbc.sql("""
                            UPDATE banking_transfer
                            SET transfer_status = :status,
                                aggregate_version = :newVersion,
                                updated_at = :updatedAt
                            WHERE transfer_id = :transferId
                              AND aggregate_version = :expectedVersion
                            """)
                    .param("status", changed.status().name())
                    .param("newVersion", changed.version())
                    .param("updatedAt", utc(canonicalTime))
                    .param("transferId", transferId.value())
                    .param("expectedVersion", current.version()).update(),
                    "transfer preparation version fence");
            requireOne(jdbc.sql("""
                            UPDATE transfer_effect
                            SET effect_status = 'PREPARED'
                            WHERE transfer_id = :transferId
                              AND effect_sequence = 1
                              AND effect_status = 'PLANNED'
                            """)
                    .param("transferId", transferId.value()).update(),
                    "withdrawal preparation");
            jdbc.sql("""
                            INSERT INTO transfer_effect_evidence (
                                transfer_id, effect_sequence, evidence_order, evidence_ref)
                            VALUES (:transferId, 1, 0, :evidence)
                            """)
                    .param("transferId", transferId.value())
                    .param("evidence", evidence.value()).update();
            insertTransition(changed.transitions().getLast(), transferId, 1);
            jdbc.sql("""
                            INSERT INTO transfer_handler_inbox (
                                delivery_id, transfer_id, result, applied_at)
                            VALUES (
                                :deliveryId, :transferId, 'WITHDRAWAL_PREPARED', :appliedAt)
                            """)
                    .param("deliveryId", deliveryId)
                    .param("transferId", transferId.value())
                    .param("appliedAt", utc(canonicalTime)).update();
            return PreparationResult.APPLIED;
        }));
    }

    private TransferAcceptance acceptInTransaction(
            ParticipantScope participant,
            IdempotencyKey key,
            CanonicalCommandMetadata requestCommand,
            Supplier<TransferAcceptancePlan> transferFactory) {
        String keyDigest = key.sha256();
        Optional<Binding> committed = findBinding(participant, keyDigest);
        if (committed.isPresent()) {
            return replay(committed.orElseThrow(), requestCommand, participant);
        }
        TransferAcceptancePlan proposedPlan = Objects.requireNonNull(
                transferFactory.get(), "transferFactory result");
        Transfer proposed = proposedPlan.transfer();
        verifyProposed(proposed, participant, keyDigest, requestCommand);
        int claimed = jdbc.sql("""
                        INSERT INTO transfer_idempotency (
                            tenant_id, participant_id, idempotency_resource,
                            operation_kind, idempotency_key_digest,
                            request_canonicalization_version, request_digest,
                            transfer_id, created_at)
                        VALUES (
                            :tenantId, :participantId, 'TRANSFER', 'TRANSFER', :keyDigest,
                            :canonicalizationVersion, :requestDigest,
                            :transferId, :createdAt)
                        ON CONFLICT (
                            tenant_id, participant_id, idempotency_resource,
                            operation_kind, idempotency_key_digest)
                        DO NOTHING
                        """)
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .param("keyDigest", keyDigest)
                .param("canonicalizationVersion",
                        requestCommand.canonicalizationVersion())
                .param("requestDigest", requestCommand.digest().value())
                .param("transferId", proposed.transferId().value())
                .param("createdAt", utc(proposed.createdAt())).update();
        if (claimed == 0) {
            Binding winner = findBinding(participant, keyDigest)
                    .orElseThrow(() -> new IllegalStateException(
                            "transfer idempotency winner was not visible"));
            return replay(winner, requestCommand, participant);
        }
        insertTransfer(proposed);
        insertEffects(proposed);
        insertTransition(proposed.transitions().getFirst(), proposed.transferId(), 0);
        insertFinalities(proposed);
        proposedPlan.settlement().ifPresent(
                settlements::insertAcceptedInCurrentTransaction);
        insertOutbox(proposed, proposedPlan.settlement().isPresent());
        return new TransferAcceptance(proposed, proposedPlan.settlement(), false);
    }

    private Optional<Binding> findBinding(
            ParticipantScope participant, String keyDigest) {
        return jdbc.sql("""
                        SELECT request_canonicalization_version, request_digest, transfer_id
                        FROM transfer_idempotency
                        WHERE tenant_id = :tenantId
                          AND participant_id = :participantId
                          AND idempotency_resource = 'TRANSFER'
                          AND operation_kind = 'TRANSFER'
                          AND idempotency_key_digest = :keyDigest
                        """)
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .param("keyDigest", keyDigest)
                .query((row, number) -> new Binding(
                        row.getInt("request_canonicalization_version"),
                        row.getString("request_digest"),
                        new TransferId(row.getObject("transfer_id", UUID.class))))
                .optional();
    }

    private TransferAcceptance replay(
            Binding binding,
            CanonicalCommandMetadata requestCommand,
            ParticipantScope participant) {
        if (binding.canonicalizationVersion()
                != requestCommand.canonicalizationVersion()
                || !binding.requestDigest().equals(requestCommand.digest().value())) {
            throw new IdempotencyConflictException();
        }
        Transfer transfer = findById(binding.transferId(), participant)
                .orElseThrow(() -> new IllegalStateException(
                        "transfer idempotency binding has no aggregate"));
        return new TransferAcceptance(
                transfer,
                settlements.findInCurrentTransaction(
                        binding.transferId(), Optional.of(participant)),
                true);
    }

    private static void verifyProposed(
            Transfer transfer,
            ParticipantScope participant,
            String keyDigest,
            CanonicalCommandMetadata requestCommand) {
        TransferAcceptanceContext context = transfer.acceptanceContext();
        if (transfer.status() != TransferStatus.ACCEPTED || transfer.version() != 0
                || !transfer.participant().tenantId().equals(participant.tenantId())
                || !transfer.participant().participantId().equals(participant.participantId())
                || !context.idempotencyKeyDigest().equals(keyDigest)
                || context.requestCanonicalizationVersion()
                    != requestCommand.canonicalizationVersion()
                || !context.requestDigest().equals(requestCommand.digest().value())
                || transfer.effects().size() != 5
                || transfer.transitions().size() != 1) {
            throw new IllegalArgumentException(
                    "proposed transfer does not match its acceptance identity");
        }
    }

    private void insertTransfer(Transfer transfer) {
        TransferAcceptanceContext context = transfer.acceptanceContext();
        AssetUnit unit = transfer.quantity().unit();
        jdbc.sql("""
                        INSERT INTO banking_transfer (
                            transfer_id, tenant_id, participant_id,
                            idempotency_key_digest,
                            request_canonicalization_version, request_digest,
                            resolved_canonicalization_version, resolved_digest,
                            currency, source_bank_account_ref,
                            destination_bank_account_ref, sender_wallet_ref,
                            recipient_wallet_ref, settlement_network,
                            route_version, wallet_policy_version,
                            asset_id, unit_id, unit_version, unit_scale,
                            unit_max_atomic, quantity_atomic, transfer_status,
                            aggregate_version, acceptance_evidence_ref,
                            created_at, updated_at)
                        VALUES (
                            :transferId, :tenantId, :participantId, :keyDigest,
                            :requestVersion, :requestDigest,
                            :resolvedVersion, :resolvedDigest,
                            :currency, :sourceAccount, :destinationAccount,
                            :senderWallet, :recipientWallet, :network,
                            :routeVersion, :walletPolicyVersion,
                            :assetId, :unitId, :unitVersion, :unitScale,
                            :unitMaxAtomic, :quantityAtomic, :status,
                            :aggregateVersion, :acceptanceEvidence,
                            :createdAt, :updatedAt)
                        """)
                .param("transferId", transfer.transferId().value())
                .param("tenantId", transfer.participant().tenantId())
                .param("participantId", transfer.participant().participantId())
                .param("keyDigest", context.idempotencyKeyDigest())
                .param("requestVersion", context.requestCanonicalizationVersion())
                .param("requestDigest", context.requestDigest())
                .param("resolvedVersion", context.resolvedCanonicalizationVersion())
                .param("resolvedDigest", context.resolvedDigest())
                .param("currency", context.currency())
                .param("sourceAccount", context.sourceBankAccount().value())
                .param("destinationAccount", context.destinationBankAccount().value())
                .param("senderWallet", context.senderWallet().value())
                .param("recipientWallet", context.recipientWallet().value())
                .param("network", context.settlementNetwork().name())
                .param("routeVersion", context.routeVersion())
                .param("walletPolicyVersion", context.walletPolicyVersion())
                .param("assetId", unit.assetId())
                .param("unitId", unit.unitId())
                .param("unitVersion", unit.version())
                .param("unitScale", unit.scale())
                .param("unitMaxAtomic", unit.maxAtomicUnits())
                .param("quantityAtomic", transfer.quantity().atomicUnits())
                .param("status", transfer.status().name())
                .param("aggregateVersion", transfer.version())
                .param("acceptanceEvidence", transfer.transitions().getFirst()
                        .evidenceReferences().getFirst().value())
                .param("createdAt", utc(transfer.createdAt()))
                .param("updatedAt", utc(transfer.createdAt())).update();
    }

    private void insertEffects(Transfer transfer) {
        for (TransferEffect effect : transfer.effects()) {
            jdbc.sql("""
                            INSERT INTO transfer_effect (
                                transfer_id, effect_sequence, effect_id, effect_kind,
                                predecessor_effect_id, effect_status, current_attempt_id)
                            VALUES (
                                :transferId, :sequence, :effectId, :kind,
                                :predecessor, :status, NULL)
                            """)
                    .param("transferId", transfer.transferId().value())
                    .param("sequence", effect.sequence())
                    .param("effectId", effect.effectId().value())
                    .param("kind", effect.kind().name())
                    .param("predecessor", effect.expectedPredecessor()
                            .map(TransferEffect.Id::value).orElse(null))
                    .param("status", effect.status().name()).update();
        }
    }

    private void insertTransition(
            TransferTransition transition, TransferId transferId, long sequence) {
        jdbc.sql("""
                        INSERT INTO transfer_transition (
                            transfer_id, transition_sequence, transition_id,
                            aggregate_version, from_status, to_status,
                            effect_id, action, occurred_at)
                        VALUES (
                            :transferId, :sequence, :transitionId,
                            :aggregateVersion, :fromStatus, :toStatus,
                            :effectId, :action, :occurredAt)
                        """)
                .param("transferId", transferId.value())
                .param("sequence", sequence)
                .param("transitionId", transition.transitionId().value())
                .param("aggregateVersion", transition.version())
                .param("fromStatus", transition.from().map(Enum::name).orElse(null))
                .param("toStatus", transition.to().name())
                .param("effectId", transition.effectId()
                        .map(TransferEffect.Id::value).orElse(null))
                .param("action", transition.action())
                .param("occurredAt", utc(transition.occurredAt())).update();
        for (int index = 0; index < transition.evidenceReferences().size(); index++) {
            jdbc.sql("""
                            INSERT INTO transfer_transition_evidence (
                                transfer_id, transition_sequence,
                                evidence_order, evidence_ref)
                            VALUES (:transferId, :sequence, :order, :evidence)
                            """)
                    .param("transferId", transferId.value())
                    .param("sequence", sequence)
                    .param("order", index)
                    .param("evidence", transition.evidenceReferences().get(index).value())
                    .update();
        }
    }

    private void insertFinalities(Transfer transfer) {
        for (FinalityType type : FinalityType.values()) {
            FinalityRecord finality = transfer.finalityHistory(type).getFirst();
            jdbc.sql("""
                            INSERT INTO transfer_finality (
                                transfer_id, finality_type, history_order,
                                finality_status, authority, policy_version, updated_at)
                            VALUES (
                                :transferId, :type, 0,
                                :status, :authority, :policyVersion, :updatedAt)
                            """)
                    .param("transferId", transfer.transferId().value())
                    .param("type", type.name())
                    .param("status", finality.status().name())
                    .param("authority", finality.authority())
                    .param("policyVersion", finality.policyVersion())
                    .param("updatedAt", utc(finality.updatedAt())).update();
        }
    }

    private void insertOutbox(Transfer transfer, boolean settlement) {
        String eventType = settlement
                ? "SettlementTransferAccepted" : "TransferAccepted";
        jdbc.sql("""
                        INSERT INTO operation_outbox (
                            event_id, operation_id, transfer_id, event_type,
                            event_version, payload_schema_version, payload,
                            status, created_at, available_at, updated_at)
                        VALUES (
                            :eventId, NULL, :transferId, :eventType,
                            1, 1, jsonb_build_object(
                                'transferId', CAST(:transferId AS text),
                                'aggregateVersion', 0),
                            'PENDING', :createdAt, :createdAt, :createdAt)
                        """)
                .param("eventId", UUID.randomUUID())
                .param("transferId", transfer.transferId().value())
                .param("eventType", eventType)
                .param("createdAt", utc(transfer.createdAt())).update();
    }

    private Seed mapSeed(ResultSet row, int number) throws SQLException {
        TransferAcceptanceContext context = new TransferAcceptanceContext(
                new BankAccountReference(row.getString("source_bank_account_ref")),
                new BankAccountReference(row.getString("destination_bank_account_ref")),
                new WalletReference(row.getString("sender_wallet_ref")),
                new WalletReference(row.getString("recipient_wallet_ref")),
                SettlementNetwork.valueOf(row.getString("settlement_network")),
                row.getString("currency"), row.getString("route_version"),
                row.getString("wallet_policy_version"),
                row.getInt("request_canonicalization_version"),
                row.getString("request_digest"),
                row.getInt("resolved_canonicalization_version"),
                row.getString("resolved_digest"),
                row.getString("idempotency_key_digest"));
        AssetUnit unit = new AssetUnit(
                row.getString("asset_id"), row.getString("unit_id"),
                row.getInt("unit_version"), row.getInt("unit_scale"),
                exactInteger(row, "unit_max_atomic"));
        return new Seed(
                new TransferId(row.getObject("transfer_id", UUID.class)),
                new TransferParticipant(
                        row.getString("tenant_id"), row.getString("participant_id")),
                context, TokenQuantity.ofAtomic(exactInteger(row, "quantity_atomic"), unit),
                TransferStatus.valueOf(row.getString("transfer_status")),
                row.getLong("aggregate_version"), instant(row, "created_at"),
                new EvidenceRef(row.getString("acceptance_evidence_ref")));
    }

    private Transfer hydrate(Seed seed) {
        List<TransferEffect> effects = jdbc.sql("""
                        SELECT effect_sequence, effect_id, effect_kind,
                               predecessor_effect_id, effect_status, current_attempt_id
                        FROM transfer_effect
                        WHERE transfer_id = :transferId
                        ORDER BY effect_sequence
                        """)
                .param("transferId", seed.transferId().value())
                .query((row, number) -> {
                    UUID predecessor = row.getObject("predecessor_effect_id", UUID.class);
                    UUID attempt = row.getObject("current_attempt_id", UUID.class);
                    if (attempt != null) {
                        throw new IllegalStateException(
                                "attempt persistence is outside the Phase 3C acceptance slice");
                    }
                    int sequence = row.getInt("effect_sequence");
                    return new TransferEffect(
                            new TransferEffect.Id(row.getObject("effect_id", UUID.class)),
                            sequence,
                            TransferEffect.Kind.valueOf(row.getString("effect_kind")),
                            predecessor == null ? Optional.empty()
                                    : Optional.of(new TransferEffect.Id(predecessor)),
                            TransferEffect.Status.valueOf(row.getString("effect_status")),
                            List.of(), effectEvidence(seed.transferId(), sequence));
                }).list();
        List<TransferTransition> transitions = jdbc.sql("""
                        SELECT transition_sequence, transition_id, aggregate_version,
                               from_status, to_status, effect_id, action, occurred_at
                        FROM transfer_transition
                        WHERE transfer_id = :transferId
                        ORDER BY transition_sequence
                        """)
                .param("transferId", seed.transferId().value())
                .query((row, number) -> {
                    String from = row.getString("from_status");
                    UUID effectId = row.getObject("effect_id", UUID.class);
                    long sequence = row.getLong("transition_sequence");
                    return new TransferTransition(
                            new TransferTransition.Id(
                                    row.getObject("transition_id", UUID.class)),
                            row.getLong("aggregate_version"),
                            from == null ? Optional.empty()
                                    : Optional.of(TransferStatus.valueOf(from)),
                            TransferStatus.valueOf(row.getString("to_status")),
                            effectId == null ? Optional.empty()
                                    : Optional.of(new TransferEffect.Id(effectId)),
                            row.getString("action"), instant(row, "occurred_at"),
                            transitionEvidence(seed.transferId(), sequence));
                }).list();
        Map<FinalityType, List<FinalityRecord>> finalities =
                new EnumMap<>(FinalityType.class);
        for (FinalityType type : FinalityType.values()) {
            List<FinalityRecord> history = jdbc.sql("""
                            SELECT history_order, finality_status, authority,
                                   policy_version, updated_at
                            FROM transfer_finality
                            WHERE transfer_id = :transferId AND finality_type = :type
                            ORDER BY history_order
                            """)
                    .param("transferId", seed.transferId().value())
                    .param("type", type.name())
                    .query((row, number) -> {
                        int historyOrder = row.getInt("history_order");
                        FinalityStatus status = FinalityStatus.valueOf(
                                row.getString("finality_status"));
                        return status == FinalityStatus.NOT_ASSESSED
                                ? FinalityRecord.notAssessed(type)
                                : FinalityRecord.assessed(
                                        type, status, row.getString("authority"),
                                        row.getString("policy_version"),
                                        instant(row, "updated_at"), finalityEvidence(
                                                seed.transferId(), type, historyOrder));
                    }).list();
            finalities.put(type, history);
        }
        return Transfer.rehydrate(
                seed.transferId(), seed.participant(), seed.context(), seed.quantity(),
                seed.status(), seed.version(), seed.createdAt(), effects,
                transitions, finalities);
    }

    private List<EvidenceRef> effectEvidence(TransferId transferId, int sequence) {
        return jdbc.sql("""
                        SELECT evidence_ref FROM transfer_effect_evidence
                        WHERE transfer_id = :transferId AND effect_sequence = :sequence
                        ORDER BY evidence_order
                        """)
                .param("transferId", transferId.value())
                .param("sequence", sequence)
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
    }

    private List<EvidenceRef> transitionEvidence(TransferId transferId, long sequence) {
        return jdbc.sql("""
                        SELECT evidence_ref FROM transfer_transition_evidence
                        WHERE transfer_id = :transferId AND transition_sequence = :sequence
                        ORDER BY evidence_order
                        """)
                .param("transferId", transferId.value())
                .param("sequence", sequence)
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
    }

    private List<EvidenceRef> finalityEvidence(
            TransferId transferId, FinalityType type, int historyOrder) {
        return jdbc.sql("""
                        SELECT evidence_ref FROM transfer_finality_evidence
                        WHERE transfer_id = :transferId
                          AND finality_type = :type
                          AND history_order = :historyOrder
                        ORDER BY evidence_order
                        """)
                .param("transferId", transferId.value())
                .param("type", type.name())
                .param("historyOrder", historyOrder)
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
    }

    private static java.math.BigInteger exactInteger(
            ResultSet row, String column) throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Instant canonical(Instant value) {
        return Objects.requireNonNull(value, "instant").truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "instant").atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int updated, String action) {
        if (updated != 1) {
            throw new IllegalStateException(action + " did not affect exactly one row");
        }
    }

    private record Binding(
            int canonicalizationVersion,
            String requestDigest,
            TransferId transferId) { }

    private record Seed(
            TransferId transferId,
            TransferParticipant participant,
            TransferAcceptanceContext context,
            TokenQuantity quantity,
            TransferStatus status,
            long version,
            Instant createdAt,
            EvidenceRef acceptanceEvidence) { }
}
