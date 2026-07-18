package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.UsdzelleWorkflowAcceptance;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit PostgreSQL mapping for the durable USDZELLE parent workflow. */
public final class PostgresUsdzelleWorkflowRepository implements UsdzelleWorkflowRepository {

    private static final int ACCEPTANCE_EVENT_VERSION = 1;

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresUsdzelleWorkflowRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public UsdzelleWorkflowAcceptance accept(
            ParticipantScope participant,
            UsdzelleWorkflow.Kind kind,
            IdempotencyKey key,
            String requestDigest,
            Supplier<UsdzelleWorkflow> workflowFactory) {
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        requireDigest(requestDigest, "requestDigest");
        Objects.requireNonNull(workflowFactory, "workflowFactory");
        return Objects.requireNonNull(transactions.execute(status -> {
            String keyDigest = key.sha256();
            lockAcceptance(participant, kind, keyDigest);
            Optional<Binding> binding = findBinding(participant, kind, keyDigest);
            if (binding.isPresent()) {
                return replay(binding.orElseThrow(), requestDigest);
            }
            UsdzelleWorkflow proposed = Objects.requireNonNull(
                    workflowFactory.get(), "workflowFactory result");
            verifyProposed(proposed, participant, kind, keyDigest);
            insertWorkflow(proposed);
            insertSteps(proposed);
            insertTransitions(proposed, -1);
            insertBinding(participant, kind, keyDigest, requestDigest, proposed);
            insertOutbox(proposed);
            return new UsdzelleWorkflowAcceptance(proposed, false);
        }));
    }

    @Override
    public Optional<UsdzelleWorkflow> findById(UsdzelleWorkflow.Id workflowId) {
        Objects.requireNonNull(workflowId, "workflowId");
        return Objects.requireNonNull(transactions.execute(status ->
                findSeed(workflowId, Optional.empty()).map(this::hydrate)));
    }

    @Override
    public Optional<UsdzelleWorkflow> findById(
            UsdzelleWorkflow.Id workflowId, ParticipantScope participant) {
        Objects.requireNonNull(workflowId, "workflowId");
        Objects.requireNonNull(participant, "participant");
        return Objects.requireNonNull(transactions.execute(status ->
                findSeed(workflowId, Optional.of(participant)).map(this::hydrate)));
    }

    @Override
    public void save(UsdzelleWorkflow workflow, long expectedVersion) {
        Objects.requireNonNull(workflow, "workflow");
        if (expectedVersion < 0 || workflow.version() <= expectedVersion) {
            throw new IllegalArgumentException("workflow version advance is invalid");
        }
        transactions.executeWithoutResult(status -> {
            int updated = jdbc.sql("""
                            UPDATE usdzelle_workflow
                            SET workflow_status = :workflowStatus,
                                aggregate_version = :newVersion,
                                updated_at = :updatedAt
                            WHERE workflow_id = :workflowId
                              AND aggregate_version = :expectedVersion
                            """)
                    .param("workflowStatus", workflow.status().name())
                    .param("newVersion", workflow.version())
                    .param("updatedAt", utc(workflow.transitions().getLast().recordedAt()))
                    .param("workflowId", workflow.id().value())
                    .param("expectedVersion", expectedVersion)
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("workflow version conflict");
            }
            updateSteps(workflow);
            insertTransitions(workflow, expectedVersion);
            insertChildren(workflow);
            insertConclusion(workflow);
        });
    }

    private void lockAcceptance(
            ParticipantScope participant, UsdzelleWorkflow.Kind kind, String keyDigest) {
        String scope = participant.tenantId() + '\u001f' + participant.participantId()
                + '\u001f' + kind.name() + '\u001f' + keyDigest;
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(:scope, 0)) IS NULL")
                .param("scope", scope).query(Boolean.class).single();
    }

    private Optional<Binding> findBinding(
            ParticipantScope participant,
            UsdzelleWorkflow.Kind kind,
            String keyDigest) {
        return jdbc.sql("""
                        SELECT request_digest, workflow_id
                        FROM usdzelle_workflow_idempotency
                        WHERE tenant_id = :tenantId
                          AND participant_id = :participantId
                          AND workflow_kind = :workflowKind
                          AND idempotency_key_digest = :keyDigest
                        """)
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .param("workflowKind", kind.name())
                .param("keyDigest", keyDigest)
                .query((row, number) -> new Binding(
                        row.getString("request_digest"),
                        new UsdzelleWorkflow.Id(
                                row.getObject("workflow_id", UUID.class))))
                .optional();
    }

    private UsdzelleWorkflowAcceptance replay(Binding binding, String requestDigest) {
        if (!binding.requestDigest().equals(requestDigest)) {
            throw new IdempotencyConflictException();
        }
        UsdzelleWorkflow workflow = findSeed(binding.workflowId(), Optional.empty())
                .map(this::hydrate)
                .orElseThrow(() -> new IllegalStateException(
                        "workflow idempotency binding has no parent"));
        return new UsdzelleWorkflowAcceptance(workflow, true);
    }

    private static void verifyProposed(
            UsdzelleWorkflow workflow,
            ParticipantScope participant,
            UsdzelleWorkflow.Kind kind,
            String keyDigest) {
        if (workflow.kind() != kind
                || workflow.status() != UsdzelleWorkflow.Status.ACCEPTED
                || workflow.version() != 0
                || workflow.transitions().size() != 1
                || !workflow.participant().tenantId().equals(participant.tenantId())
                || !workflow.participant().participantId().equals(participant.participantId())
                || !workflow.context().idempotencyKeyDigest().equals(keyDigest)) {
            throw new IllegalArgumentException(
                    "proposed workflow does not match its acceptance identity");
        }
    }

    private void insertWorkflow(UsdzelleWorkflow workflow) {
        UsdzelleWorkflow.AcceptedContext context = workflow.context();
        AssetUnit unit = context.tokenQuantity().unit();
        jdbc.sql("""
                        INSERT INTO usdzelle_workflow (
                            workflow_id, workflow_kind, tenant_id, participant_id,
                            workflow_policy_version, amount_cents,
                            asset_id, unit_id, unit_version, unit_scale,
                            unit_max_atomic, quantity_atomic,
                            bank_id, bank_account_id,
                            user_wallet_reference, user_wallet_metadata_version,
                            admin_wallet_reference, admin_wallet_metadata_version,
                            settlement_network, contract_reference,
                            payout_policy_version,
                            conversion_policy_version, accounting_policy_version,
                            fee_policy_version, finality_policy_version,
                            reconciliation_policy_version,
                            idempotency_key_digest, command_digest,
                            workflow_status, aggregate_version, accepted_at, updated_at)
                        VALUES (
                            :workflowId, :workflowKind, :tenantId, :participantId,
                            :workflowPolicyVersion, :amountCents,
                            :assetId, :unitId, :unitVersion, :unitScale,
                            :unitMaxAtomic, :quantityAtomic,
                            :bankId, :bankAccountId,
                            :userWallet, :userWalletVersion,
                            :adminWallet, :adminWalletVersion,
                            :network, :contractReference,
                            :payoutPolicyVersion,
                            :conversionPolicyVersion, :accountingPolicyVersion,
                            :feePolicyVersion, :finalityPolicyVersion,
                            :reconciliationPolicyVersion,
                            :keyDigest, :commandDigest,
                            :workflowStatus, 0, :acceptedAt, :acceptedAt)
                        """)
                .param("workflowId", workflow.id().value())
                .param("workflowKind", workflow.kind().name())
                .param("tenantId", workflow.participant().tenantId())
                .param("participantId", workflow.participant().participantId())
                .param("workflowPolicyVersion", context.workflowVersion())
                .param("amountCents", context.usdAmount().value())
                .param("assetId", unit.assetId())
                .param("unitId", unit.unitId())
                .param("unitVersion", unit.version())
                .param("unitScale", unit.scale())
                .param("unitMaxAtomic", unit.maxAtomicUnits())
                .param("quantityAtomic", context.tokenQuantity().atomicUnits())
                .param("bankId", context.bankId().value())
                .param("bankAccountId", context.bankAccountId().value())
                .param("userWallet", context.userWallet().value())
                .param("userWalletVersion", context.userWalletMetadataVersion())
                .param("adminWallet", context.adminWallet().value())
                .param("adminWalletVersion", context.adminWalletMetadataVersion())
                .param("network", context.network().name())
                .param("contractReference", context.contractReference())
                .param("payoutPolicyVersion", context.payoutPolicyVersion())
                .param("conversionPolicyVersion", context.conversionPolicyVersion())
                .param("accountingPolicyVersion", context.accountingPolicyVersion())
                .param("feePolicyVersion", context.feePolicyVersion())
                .param("finalityPolicyVersion", context.finalityPolicyVersion())
                .param("reconciliationPolicyVersion", context.reconciliationPolicyVersion())
                .param("keyDigest", context.idempotencyKeyDigest())
                .param("commandDigest", context.commandDigest())
                .param("workflowStatus", workflow.status().name())
                .param("acceptedAt", utc(context.acceptedAt()))
                .update();
    }

    private void insertSteps(UsdzelleWorkflow workflow) {
        for (UsdzelleWorkflow.Step step : workflow.steps()) {
            jdbc.sql("""
                            INSERT INTO usdzelle_workflow_step (
                                workflow_id, step_sequence, step_id, step_kind, step_status,
                                child_reference, evidence_reference)
                            VALUES (
                                :workflowId, :sequence, :stepId, :kind, :status,
                                :childReference, :evidenceReference)
                            """)
                    .param("workflowId", workflow.id().value())
                    .param("sequence", step.sequence())
                    .param("stepId", step.id().value())
                    .param("kind", step.kind().name())
                    .param("status", step.status().name())
                    .param("childReference",
                            step.childReference().map(UsdzelleWorkflow.ChildReference::value)
                                    .orElse(null))
                    .param("evidenceReference",
                            step.evidenceReference().map(EvidenceRef::value).orElse(null))
                    .update();
        }
    }

    private void updateSteps(UsdzelleWorkflow workflow) {
        for (UsdzelleWorkflow.Step step : workflow.steps()) {
            int updated = jdbc.sql("""
                            UPDATE usdzelle_workflow_step
                            SET step_status = :status,
                                child_reference = :childReference,
                                evidence_reference = :evidenceReference
                            WHERE workflow_id = :workflowId
                              AND step_sequence = :sequence
                              AND step_id = :stepId
                              AND step_kind = :kind
                            """)
                    .param("status", step.status().name())
                    .param("childReference",
                            step.childReference().map(UsdzelleWorkflow.ChildReference::value)
                                    .orElse(null))
                    .param("evidenceReference",
                            step.evidenceReference().map(EvidenceRef::value).orElse(null))
                    .param("workflowId", workflow.id().value())
                    .param("sequence", step.sequence())
                    .param("stepId", step.id().value())
                    .param("kind", step.kind().name())
                    .update();
            if (updated != 1) {
                throw new IllegalStateException("workflow step identity changed");
            }
        }
    }

    private void insertTransitions(UsdzelleWorkflow workflow, long afterVersion) {
        workflow.transitions().stream()
                .filter(transition -> transition.version() > afterVersion)
                .forEach(transition -> {
                    jdbc.sql("""
                                    INSERT INTO usdzelle_workflow_transition (
                                        workflow_id, aggregate_version, transition_id,
                                        from_status, to_status, step_id,
                                        evidence_reference, recorded_at)
                                    VALUES (
                                        :workflowId, :version, :transitionId,
                                        :fromStatus, :toStatus, :stepId,
                                        :evidenceReference, :recordedAt)
                                    """)
                            .param("workflowId", workflow.id().value())
                            .param("version", transition.version())
                            .param("transitionId", transition.id().value())
                            .param("fromStatus", transition.from().name())
                            .param("toStatus", transition.to().name())
                            .param("stepId", transition.stepId()
                                    .map(UsdzelleWorkflow.StepId::value).orElse(null))
                            .param("evidenceReference", transition.evidence().value())
                            .param("recordedAt", utc(transition.recordedAt()))
                            .update();
                    jdbc.sql("""
                                    INSERT INTO usdzelle_workflow_evidence (
                                        workflow_id, aggregate_version, evidence_reference)
                                    VALUES (:workflowId, :version, :evidenceReference)
                                    """)
                            .param("workflowId", workflow.id().value())
                            .param("version", transition.version())
                            .param("evidenceReference", transition.evidence().value())
                            .update();
                });
    }

    private void insertChildren(UsdzelleWorkflow workflow) {
        for (UsdzelleWorkflow.Step step : workflow.steps()) {
            step.childReference().ifPresent(child -> jdbc.sql("""
                            INSERT INTO usdzelle_workflow_child (
                                workflow_id, step_id, child_reference, created_at)
                            VALUES (:workflowId, :stepId, :childReference, :createdAt)
                            ON CONFLICT (workflow_id, step_id) DO NOTHING
                            """)
                    .param("workflowId", workflow.id().value())
                    .param("stepId", step.id().value())
                    .param("childReference", child.value())
                    .param("createdAt", utc(workflow.transitions().getLast().recordedAt()))
                    .update());
        }
    }

    private void insertConclusion(UsdzelleWorkflow workflow) {
        workflow.reconciliationConclusion().ifPresent(conclusion -> jdbc.sql("""
                        INSERT INTO usdzelle_workflow_conclusion (
                            workflow_id, reconciliation_status, completed,
                            evidence_reference, recorded_at)
                        VALUES (
                            :workflowId, :status, :completed,
                            :evidenceReference, :recordedAt)
                        """)
                .param("workflowId", workflow.id().value())
                .param("status", conclusion.name())
                .param("completed", conclusion
                        == io.github.johnwhitton.digitalbanking.domain.accounting
                            .ReserveAccounting.ReconciliationStatus.RECONCILED)
                .param("evidenceReference",
                        workflow.transitions().getLast().evidence().value())
                .param("recordedAt", utc(workflow.transitions().getLast().recordedAt()))
                .update());
    }

    private void insertBinding(
            ParticipantScope participant,
            UsdzelleWorkflow.Kind kind,
            String keyDigest,
            String requestDigest,
            UsdzelleWorkflow workflow) {
        jdbc.sql("""
                        INSERT INTO usdzelle_workflow_idempotency (
                            tenant_id, participant_id, workflow_kind,
                            idempotency_key_digest, request_digest,
                            workflow_id, created_at)
                        VALUES (
                            :tenantId, :participantId, :workflowKind,
                            :keyDigest, :requestDigest, :workflowId, :createdAt)
                        """)
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .param("workflowKind", kind.name())
                .param("keyDigest", keyDigest)
                .param("requestDigest", requestDigest)
                .param("workflowId", workflow.id().value())
                .param("createdAt", utc(workflow.context().acceptedAt()))
                .update();
    }

    private void insertOutbox(UsdzelleWorkflow workflow) {
        jdbc.sql("""
                        INSERT INTO operation_outbox (
                            event_id, workflow_id, event_type, event_version,
                            payload_schema_version, payload, status,
                            created_at, available_at, updated_at)
                        VALUES (
                            :eventId, :workflowId, 'UsdzelleWorkflowAccepted',
                            :eventVersion, 1,
                            jsonb_build_object(
                                'workflowId', CAST(:workflowId AS text),
                                'workflowKind', :workflowKind,
                                'aggregateVersion', 0),
                            'PENDING', :createdAt, :createdAt, :createdAt)
                        """)
                .param("eventId", UUID.randomUUID())
                .param("workflowId", workflow.id().value())
                .param("eventVersion", ACCEPTANCE_EVENT_VERSION)
                .param("workflowKind", workflow.kind().name())
                .param("createdAt", utc(workflow.context().acceptedAt()))
                .update();
    }

    private Optional<Seed> findSeed(
            UsdzelleWorkflow.Id workflowId, Optional<ParticipantScope> participant) {
        String owner = participant.isPresent()
                ? " AND tenant_id = :tenantId AND participant_id = :participantId" : "";
        JdbcClient.StatementSpec statement = jdbc.sql("""
                        SELECT * FROM usdzelle_workflow
                        WHERE workflow_id = :workflowId
                        """ + owner)
                .param("workflowId", workflowId.value());
        if (participant.isPresent()) {
            statement = statement
                    .param("tenantId", participant.orElseThrow().tenantId())
                    .param("participantId", participant.orElseThrow().participantId());
        }
        return statement.query(this::mapSeed).optional();
    }

    private Seed mapSeed(ResultSet row, int rowNumber) throws SQLException {
        AssetUnit unit = new AssetUnit(
                row.getString("asset_id"), row.getString("unit_id"),
                row.getInt("unit_version"), row.getInt("unit_scale"),
                exactInteger(row, "unit_max_atomic"));
        UsdzelleWorkflow.AcceptedContext context = new UsdzelleWorkflow.AcceptedContext(
                row.getString("workflow_policy_version"),
                UsdCents.positive(exactInteger(row, "amount_cents")),
                TokenQuantity.ofAtomic(exactInteger(row, "quantity_atomic"), unit),
                new SyntheticBankAccount.BankId(row.getString("bank_id")),
                new SyntheticBankAccount.AccountId(row.getString("bank_account_id")),
                new WalletReference(row.getString("user_wallet_reference")),
                row.getString("user_wallet_metadata_version"),
                new WalletReference(row.getString("admin_wallet_reference")),
                row.getString("admin_wallet_metadata_version"),
                SettlementNetwork.valueOf(row.getString("settlement_network")),
                row.getString("contract_reference"),
                row.getString("payout_policy_version"),
                row.getString("conversion_policy_version"),
                row.getString("accounting_policy_version"),
                row.getString("fee_policy_version"),
                row.getString("finality_policy_version"),
                row.getString("reconciliation_policy_version"),
                row.getString("idempotency_key_digest"),
                row.getString("command_digest"), instant(row, "accepted_at"));
        return new Seed(
                new UsdzelleWorkflow.Id(row.getObject("workflow_id", UUID.class)),
                UsdzelleWorkflow.Kind.valueOf(row.getString("workflow_kind")),
                new UsdzelleWorkflow.Participant(
                        row.getString("tenant_id"), row.getString("participant_id")),
                context,
                UsdzelleWorkflow.Status.valueOf(row.getString("workflow_status")),
                row.getLong("aggregate_version"));
    }

    private UsdzelleWorkflow hydrate(Seed seed) {
        List<UsdzelleWorkflow.Step> steps = jdbc.sql("""
                        SELECT step_sequence, step_id, step_kind, step_status,
                               child_reference, evidence_reference
                        FROM usdzelle_workflow_step
                        WHERE workflow_id = :workflowId
                        ORDER BY step_sequence
                        """)
                .param("workflowId", seed.id().value())
                .query((row, number) -> new UsdzelleWorkflow.Step(
                        new UsdzelleWorkflow.StepId(
                                row.getObject("step_id", UUID.class)),
                        row.getInt("step_sequence"),
                        UsdzelleWorkflow.StepKind.valueOf(row.getString("step_kind")),
                        UsdzelleWorkflow.StepStatus.valueOf(row.getString("step_status")),
                        Optional.ofNullable(row.getString("child_reference"))
                                .map(UsdzelleWorkflow.ChildReference::new),
                        Optional.ofNullable(row.getString("evidence_reference"))
                                .map(EvidenceRef::new)))
                .list();
        List<UsdzelleWorkflow.Transition> transitions = jdbc.sql("""
                        SELECT aggregate_version, transition_id, from_status,
                               to_status, step_id, evidence_reference, recorded_at
                        FROM usdzelle_workflow_transition
                        WHERE workflow_id = :workflowId
                        ORDER BY aggregate_version
                        """)
                .param("workflowId", seed.id().value())
                .query((row, number) -> {
                    UUID stepId = row.getObject("step_id", UUID.class);
                    return new UsdzelleWorkflow.Transition(
                            new UsdzelleWorkflow.TransitionId(
                                    row.getObject("transition_id", UUID.class)),
                            row.getLong("aggregate_version"),
                            UsdzelleWorkflow.Status.valueOf(row.getString("from_status")),
                            UsdzelleWorkflow.Status.valueOf(row.getString("to_status")),
                            Optional.ofNullable(stepId).map(UsdzelleWorkflow.StepId::new),
                            new EvidenceRef(row.getString("evidence_reference")),
                            instant(row, "recorded_at"));
                }).list();
        Optional<io.github.johnwhitton.digitalbanking.domain.accounting
                .ReserveAccounting.ReconciliationStatus> conclusion = jdbc.sql("""
                        SELECT reconciliation_status
                        FROM usdzelle_workflow_conclusion
                        WHERE workflow_id = :workflowId
                        """)
                .param("workflowId", seed.id().value())
                .query(String.class).optional()
                .map(io.github.johnwhitton.digitalbanking.domain.accounting
                        .ReserveAccounting.ReconciliationStatus::valueOf);
        return UsdzelleWorkflow.rehydrate(
                seed.id(), seed.kind(), seed.participant(), seed.context(),
                seed.status(), seed.version(), steps, transitions, conclusion);
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static java.math.BigInteger exactInteger(ResultSet row, String column)
            throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "instant").atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private record Binding(String requestDigest, UsdzelleWorkflow.Id workflowId) {
    }

    private record Seed(
            UsdzelleWorkflow.Id id,
            UsdzelleWorkflow.Kind kind,
            UsdzelleWorkflow.Participant participant,
            UsdzelleWorkflow.AcceptedContext context,
            UsdzelleWorkflow.Status status,
            long version) {
    }
}
