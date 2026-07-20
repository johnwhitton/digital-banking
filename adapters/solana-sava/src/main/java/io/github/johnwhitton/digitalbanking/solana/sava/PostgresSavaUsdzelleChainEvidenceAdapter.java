package io.github.johnwhitton.digitalbanking.solana.sava;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.UsdzelleChainEvidencePort;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Binds finalized native Solana evidence to the existing workflow/accounting boundary. */
public final class PostgresSavaUsdzelleChainEvidenceAdapter
        implements UsdzelleChainEvidencePort {

    private static final Pattern PUBLIC_KEY =
            Pattern.compile("[1-9A-HJ-NP-Za-km-z]{32,88}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Configuration configuration;
    private final Clock clock;

    public PostgresSavaUsdzelleChainEvidenceAdapter(
            DataSource dataSource, Configuration configuration, Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public ReserveAccounting.EvidenceIdentity register(
            Effect effect, UsdzelleWorkflow workflow, OperationId childOperationId) {
        Objects.requireNonNull(effect, "effect");
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(childOperationId, "childOperationId");
        validateStep(effect, workflow.currentStep().kind());
        validateAcceptedContext(workflow);
        Optional<Retained> retained = findRetained(
                workflow, effect, childOperationId);
        if (retained.isPresent()) {
            NativeEvidence current = loadNative(effect, workflow, childOperationId);
            if (!retained.orElseThrow().matches(current)
                    || !pointerMatches(effect, childOperationId, current)) {
                throw new IllegalStateException(
                        "retained Solana workflow evidence no longer matches finalized evidence");
            }
            return new ReserveAccounting.EvidenceIdentity(
                    retained.orElseThrow().evidenceReference());
        }
        NativeEvidence evidence = loadNative(effect, workflow, childOperationId);
        return Objects.requireNonNull(transactions.execute(status -> {
            int observation = jdbc.sql("""
                            INSERT INTO usdzelle_chain_state_observation (
                                workflow_id, step_id, effect_type,
                                child_operation_id, settlement_network,
                                evidence_reference, block_number, block_hash,
                                user_balance_atomic, admin_balance_atomic,
                                total_supply_atomic, observed_at)
                            VALUES (:workflowId, :stepId, :effect,
                                :operationId, 'SOLANA', :evidenceId, :slot,
                                :signature, :userBalance, :adminBalance,
                                :supply, :observedAt)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("workflowId", workflow.id().value())
                    .param("stepId", workflow.currentStep().id().value())
                    .param("effect", effect.name())
                    .param("operationId", childOperationId.value())
                    .param("evidenceId", evidence.evidenceReference())
                    .param("slot", evidence.slot())
                    .param("signature", evidence.transactionSignature())
                    .param("userBalance", evidence.userBalance())
                    .param("adminBalance", evidence.adminBalance())
                    .param("supply", evidence.observedSupply())
                    .param("observedAt", utc(evidence.observedAt()))
                    .update();
            if (observation == 0 && findRetained(
                    workflow, effect, childOperationId)
                    .filter(value -> value.matches(evidence)).isEmpty()) {
                throw new IllegalStateException(
                        "Solana workflow evidence conflicts with retained evidence");
            }
            int inserted = jdbc.sql("""
                            INSERT INTO accounting_confirmed_evidence (
                                evidence_id, evidence_type, settlement_network,
                                operation_id, attempt_id, observation_sequence,
                                observed_supply_cents, recorded_at)
                            VALUES (:evidenceId, :effect, 'SOLANA', :operationId,
                                :attemptId, :sequence, :supply, :recordedAt)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("evidenceId", evidence.evidenceReference())
                    .param("effect", effect.name())
                    .param("operationId", childOperationId.value())
                    .param("attemptId", evidence.attemptId())
                    .param("sequence", evidence.observationSequence())
                    .param("supply", evidence.observedSupply())
                    .param("recordedAt", utc(evidence.observedAt()))
                    .update();
            if (inserted == 0 && !pointerMatches(
                    effect, childOperationId, evidence)) {
                throw new IllegalStateException(
                        "accounting Solana-evidence pointer conflicts with retained evidence");
            }
            return new ReserveAccounting.EvidenceIdentity(
                    evidence.evidenceReference());
        }));
    }

    @Override
    public void bindBurn(
            UsdzelleWorkflow workflow,
            OperationId custodyOperationId,
            OperationId burnOperationId) {
        Objects.requireNonNull(workflow, "workflow");
        Objects.requireNonNull(custodyOperationId, "custodyOperationId");
        Objects.requireNonNull(burnOperationId, "burnOperationId");
        validateAcceptedContext(workflow);
        if (workflow.currentStep().kind() != UsdzelleWorkflow.StepKind.BURN) {
            throw new IllegalArgumentException("only the payout-gated burn can be bound");
        }
        NativeEvidence custody = loadNative(
                Effect.REDEMPTION_CUSTODY, workflow, custodyOperationId);
        Instant recordedAt = Instant.now(clock);
        Objects.requireNonNull(transactions.execute(status -> {
            int inserted = jdbc.sql("""
                            INSERT INTO ethereum_redemption_correlation (
                                correlation_id, burn_operation_id,
                                custody_operation_id, custody_effect_id,
                                custody_attempt_id, correlation_status,
                                custody_evidence_ref, created_at, updated_at)
                            SELECT transfer.transfer_id, burn.operation_id,
                                   transfer.operation_id, transfer.effect_id,
                                   :custodyAttempt, 'CUSTODY_CONFIRMED',
                                   :evidence, :createdAt, :recordedAt
                            FROM token_operation burn
                            JOIN wallet_transfer_operation transfer
                              ON transfer.operation_id = :custodyId
                            WHERE burn.operation_id = :burnId
                              AND burn.operation_kind = 'BURN'
                              AND transfer.transfer_purpose = 'REDEMPTION_CUSTODY'
                              AND transfer.network = 'SOLANA'
                              AND transfer.operation_status = 'COMPLETED'
                              AND burn.tenant_id = transfer.tenant_id
                              AND burn.participant_id = transfer.participant_id
                              AND burn.asset_id = transfer.asset_id
                              AND burn.unit_id = transfer.unit_id
                              AND burn.unit_version = transfer.unit_version
                              AND burn.unit_scale = transfer.unit_scale
                              AND burn.quantity_atomic = transfer.quantity_atomic
                            ON CONFLICT DO NOTHING
                            """)
                    .param("custodyAttempt", custody.attemptId())
                    .param("evidence", custody.evidenceReference())
                    .param("createdAt", utc(workflow.context().acceptedAt()))
                    .param("recordedAt", utc(recordedAt))
                    .param("custodyId", custodyOperationId.value())
                    .param("burnId", burnOperationId.value())
                    .update();
            if (inserted == 0 && !jdbc.sql("""
                            SELECT EXISTS (
                                SELECT 1 FROM ethereum_redemption_correlation
                                WHERE burn_operation_id = :burnId
                                  AND custody_operation_id = :custodyId
                                  AND custody_attempt_id = :custodyAttempt
                                  AND correlation_status = 'CUSTODY_CONFIRMED'
                                  AND custody_evidence_ref = :evidence)
                            """)
                    .param("burnId", burnOperationId.value())
                    .param("custodyId", custodyOperationId.value())
                    .param("custodyAttempt", custody.attemptId())
                    .param("evidence", custody.evidenceReference())
                    .query(Boolean.class).single()) {
                throw new IllegalStateException(
                        "payout-gated Solana burn conflicts with retained custody");
            }
            return Boolean.TRUE;
        }));
    }

    private NativeEvidence loadNative(
            Effect effect, UsdzelleWorkflow workflow, OperationId child) {
        String sql = switch (effect) {
            case MINT -> """
                    SELECT o.operation_attempt_id AS attempt_id,
                           o.observation_sequence, o.evidence_ref,
                           o.transaction_signature, o.slot,
                           o.observed_destination_balance AS user_balance,
                           CAST(0 AS NUMERIC) AS admin_balance,
                           o.observed_mint_supply, o.observed_at
                    FROM token_operation op
                    JOIN solana_mint_attempt a
                      ON a.operation_id = op.operation_id
                     AND a.effect_kind = 'MINT'
                    JOIN solana_mint_observation o
                      ON o.operation_id = a.operation_id
                     AND o.operation_attempt_id = a.operation_attempt_id
                     AND o.effect_kind = a.effect_kind
                    JOIN LATERAL (
                        SELECT finality_status, policy_version, history_order
                        FROM operation_finality
                        WHERE operation_id = op.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1) f ON true
                    WHERE op.operation_id = :operationId
                      AND op.operation_kind = 'MINT'
                      AND op.lifecycle_state = 'COMPLETED'
                      AND op.tenant_id = :tenantId
                      AND op.participant_id = :participantId
                      AND op.asset_id = :assetId AND op.unit_id = :unitId
                      AND op.unit_version = :unitVersion
                      AND op.unit_scale = :unitScale
                      AND op.quantity_atomic = :quantity
                      AND a.network = 'LOCAL_SOLANA'
                      AND a.cluster_identity = :clusterIdentity
                      AND a.mint_address = :mintAddress
                      AND a.destination_owner = :userAddress
                      AND a.decimals = :unitScale
                      AND a.amount_atomic = :quantity
                      AND a.policy_version = :policyVersion
                      AND a.attempt_status = 'CONFIRMED'
                      AND o.observation_status = 'CONFIRMED'
                      AND o.commitment = 'finalized'
                      AND o.expected_instructions
                      AND o.slot IS NOT NULL
                      AND o.observed_mint_supply = a.pre_mint_supply + a.amount_atomic
                      AND o.observed_destination_balance =
                            a.pre_destination_balance + a.amount_atomic
                      AND o.mint_delta = a.amount_atomic
                      AND o.destination_delta = a.amount_atomic
                      AND f.finality_status = 'REACHED'
                      AND f.policy_version = a.policy_version
                      AND EXISTS (
                          SELECT 1 FROM operation_finality_evidence fe
                          WHERE fe.operation_id = op.operation_id
                            AND fe.finality_type = 'BLOCKCHAIN'
                            AND fe.history_order = f.history_order
                            AND fe.evidence_ref = o.evidence_ref)
                    ORDER BY o.observation_sequence DESC LIMIT 1
                    """;
            case REDEMPTION_CUSTODY -> """
                    SELECT o.operation_attempt_id AS attempt_id,
                           o.observation_sequence, o.evidence_ref,
                           o.transaction_signature, o.slot,
                           o.observed_source_balance AS user_balance,
                           o.observed_destination_balance AS admin_balance,
                           o.observed_mint_supply, o.observed_at
                    FROM wallet_transfer_operation w
                    JOIN solana_mint_attempt a
                      ON a.operation_id = w.operation_id
                     AND a.effect_kind = 'TRANSFER'
                    JOIN solana_mint_observation o
                      ON o.operation_id = a.operation_id
                     AND o.operation_attempt_id = a.operation_attempt_id
                     AND o.effect_kind = a.effect_kind
                    JOIN LATERAL (
                        SELECT finality_status, policy_version, evidence_ref
                        FROM wallet_transfer_finality
                        WHERE operation_id = w.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1) f ON true
                    WHERE w.operation_id = :operationId
                      AND w.transfer_purpose = 'REDEMPTION_CUSTODY'
                      AND w.operation_status = 'COMPLETED'
                      AND w.network = 'SOLANA'
                      AND w.tenant_id = :tenantId
                      AND w.participant_id = :participantId
                      AND w.asset_id = :assetId AND w.unit_id = :unitId
                      AND w.unit_version = :unitVersion
                      AND w.unit_scale = :unitScale
                      AND w.quantity_atomic = :quantity
                      AND w.source_wallet_ref = :userWallet
                      AND w.destination_wallet_ref = :adminWallet
                      AND w.source_address = :userAddress
                      AND w.destination_address = :adminAddress
                      AND w.contract_address = :mintAddress
                      AND a.network = 'LOCAL_SOLANA'
                      AND a.cluster_identity = :clusterIdentity
                      AND a.mint_address = :mintAddress
                      AND a.source_owner = :userAddress
                      AND a.destination_owner = :adminAddress
                      AND a.decimals = :unitScale
                      AND a.amount_atomic = :quantity
                      AND a.policy_version = :policyVersion
                      AND a.attempt_status = 'CONFIRMED'
                      AND o.observation_status = 'CONFIRMED'
                      AND o.commitment = 'finalized'
                      AND o.expected_instructions
                      AND o.slot IS NOT NULL
                      AND o.observed_mint_supply = a.pre_mint_supply
                      AND o.observed_source_balance =
                            a.pre_source_balance - a.amount_atomic
                      AND o.observed_destination_balance =
                            a.pre_destination_balance + a.amount_atomic
                      AND o.transaction_pre_source_balance = a.pre_source_balance
                      AND o.transaction_post_source_balance =
                            a.pre_source_balance - a.amount_atomic
                      AND o.transaction_pre_destination_balance =
                            a.pre_destination_balance
                      AND o.transaction_post_destination_balance =
                            a.pre_destination_balance + a.amount_atomic
                      AND o.mint_delta = 0
                      AND o.source_delta = a.amount_atomic
                      AND o.destination_delta = a.amount_atomic
                      AND f.finality_status = 'REACHED'
                      AND f.policy_version = a.policy_version
                      AND f.evidence_ref = o.evidence_ref
                    ORDER BY o.observation_sequence DESC LIMIT 1
                    """;
            case BURN -> """
                    SELECT o.operation_attempt_id AS attempt_id,
                           o.observation_sequence, o.evidence_ref,
                           o.transaction_signature, o.slot,
                           CAST(0 AS NUMERIC) AS user_balance,
                           o.observed_source_balance AS admin_balance,
                           o.observed_mint_supply, o.observed_at
                    FROM token_operation op
                    JOIN solana_mint_attempt a
                      ON a.operation_id = op.operation_id
                     AND a.effect_kind = 'BURN'
                    JOIN solana_mint_observation o
                      ON o.operation_id = a.operation_id
                     AND o.operation_attempt_id = a.operation_attempt_id
                     AND o.effect_kind = a.effect_kind
                    JOIN ethereum_redemption_correlation c
                      ON c.burn_operation_id = op.operation_id
                     AND c.correlation_id = a.redemption_correlation_id
                     AND c.consumed_by_burn_attempt_id = a.operation_attempt_id
                    JOIN LATERAL (
                        SELECT finality_status, policy_version, history_order
                        FROM operation_finality
                        WHERE operation_id = op.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1) f ON true
                    WHERE op.operation_id = :operationId
                      AND op.operation_kind = 'BURN'
                      AND op.lifecycle_state = 'COMPLETED'
                      AND op.tenant_id = :tenantId
                      AND op.participant_id = :participantId
                      AND op.asset_id = :assetId AND op.unit_id = :unitId
                      AND op.unit_version = :unitVersion
                      AND op.unit_scale = :unitScale
                      AND op.quantity_atomic = :quantity
                      AND c.correlation_status = 'CONSUMED'
                      AND a.network = 'LOCAL_SOLANA'
                      AND a.cluster_identity = :clusterIdentity
                      AND a.mint_address = :mintAddress
                      AND a.source_owner = :adminAddress
                      AND a.destination_owner = :adminAddress
                      AND a.decimals = :unitScale
                      AND a.amount_atomic = :quantity
                      AND a.policy_version = :policyVersion
                      AND a.attempt_status = 'CONFIRMED'
                      AND o.observation_status = 'CONFIRMED'
                      AND o.commitment = 'finalized'
                      AND o.expected_instructions
                      AND o.slot IS NOT NULL
                      AND o.observed_mint_supply = a.pre_mint_supply - a.amount_atomic
                      AND o.observed_source_balance =
                            a.pre_source_balance - a.amount_atomic
                      AND o.transaction_pre_source_balance = a.pre_source_balance
                      AND o.transaction_post_source_balance =
                            a.pre_source_balance - a.amount_atomic
                      AND o.mint_delta = a.amount_atomic
                      AND o.source_delta = a.amount_atomic
                      AND f.finality_status = 'REACHED'
                      AND f.policy_version = a.policy_version
                      AND EXISTS (
                          SELECT 1 FROM operation_finality_evidence fe
                          WHERE fe.operation_id = op.operation_id
                            AND fe.finality_type = 'BLOCKCHAIN'
                            AND fe.history_order = f.history_order
                            AND fe.evidence_ref = o.evidence_ref)
                    ORDER BY o.observation_sequence DESC LIMIT 1
                    """;
        };
        UserConfiguration user = configuration.user(workflow);
        return jdbc.sql(sql)
                .param("operationId", child.value())
                .param("tenantId", workflow.participant().tenantId())
                .param("participantId", workflow.participant().participantId())
                .param("assetId", workflow.context().tokenQuantity().unit().assetId())
                .param("unitId", workflow.context().tokenQuantity().unit().unitId())
                .param("unitVersion", workflow.context().tokenQuantity().unit().version())
                .param("unitScale", workflow.context().tokenQuantity().unit().scale())
                .param("quantity", workflow.context().tokenQuantity().atomicUnits())
                .param("clusterIdentity", configuration.clusterIdentity())
                .param("mintAddress", configuration.mintAddress())
                .param("policyVersion", configuration.policyVersion())
                .param("userWallet", user.walletReference())
                .param("adminWallet", configuration.adminWalletReference())
                .param("userAddress", user.address())
                .param("adminAddress", configuration.adminAddress())
                .query(PostgresSavaUsdzelleChainEvidenceAdapter::mapNative)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "finalized matching Solana child evidence is unavailable"));
    }

    private Optional<Retained> findRetained(
            UsdzelleWorkflow workflow, Effect effect, OperationId child) {
        return jdbc.sql("""
                        SELECT evidence_reference, block_number, block_hash,
                               user_balance_atomic, admin_balance_atomic,
                               total_supply_atomic
                        FROM usdzelle_chain_state_observation
                        WHERE workflow_id = :workflowId
                          AND step_id = :stepId
                          AND settlement_network = 'SOLANA'
                          AND effect_type = :effect
                          AND child_operation_id = :operationId
                        """)
                .param("workflowId", workflow.id().value())
                .param("stepId", workflow.currentStep().id().value())
                .param("effect", effect.name())
                .param("operationId", child.value())
                .query((row, number) -> new Retained(
                        row.getString("evidence_reference"),
                        exact(row, "block_number"), row.getString("block_hash"),
                        exact(row, "user_balance_atomic"),
                        exact(row, "admin_balance_atomic"),
                        exact(row, "total_supply_atomic")))
                .optional();
    }

    private boolean pointerMatches(
            Effect effect, OperationId child, NativeEvidence evidence) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM accounting_confirmed_evidence
                            WHERE evidence_id = :evidenceId
                              AND evidence_type = :effect
                              AND settlement_network = 'SOLANA'
                              AND operation_id = :operationId
                              AND attempt_id = :attemptId
                              AND observation_sequence = :sequence
                              AND observed_supply_cents = :supply)
                        """)
                .param("evidenceId", evidence.evidenceReference())
                .param("effect", effect.name())
                .param("operationId", child.value())
                .param("attemptId", evidence.attemptId())
                .param("sequence", evidence.observationSequence())
                .param("supply", evidence.observedSupply())
                .query(Boolean.class).single();
    }

    private void validateAcceptedContext(UsdzelleWorkflow workflow) {
        UsdzelleWorkflow.AcceptedContext accepted = workflow.context();
        UserConfiguration user = configuration.user(workflow);
        if (accepted.network() != SettlementNetwork.SOLANA
                || !accepted.contractReference().equals(configuration.mintAddress())
                || !accepted.userWallet().value().equals(user.walletReference())
                || !accepted.userWalletMetadataVersion().equals(
                        user.walletMetadataVersion())
                || !accepted.adminWallet().value().equals(
                        configuration.adminWalletReference())
                || !accepted.adminWalletMetadataVersion().equals(
                        configuration.adminWalletMetadataVersion())) {
            throw new IllegalStateException(
                    "Solana workflow configuration differs from accepted context");
        }
    }

    private static NativeEvidence mapNative(ResultSet row, int number)
            throws SQLException {
        return new NativeEvidence(
                row.getObject("attempt_id", UUID.class),
                row.getInt("observation_sequence"), row.getString("evidence_ref"),
                row.getString("transaction_signature"),
                BigInteger.valueOf(row.getLong("slot")),
                exact(row, "user_balance"), exact(row, "admin_balance"),
                exact(row, "observed_mint_supply"),
                row.getObject("observed_at", OffsetDateTime.class).toInstant());
    }

    private static void validateStep(Effect effect, UsdzelleWorkflow.StepKind step) {
        boolean matches = switch (effect) {
            case MINT -> step == UsdzelleWorkflow.StepKind.MINT
                    || step == UsdzelleWorkflow.StepKind.MINT_ACCOUNTING_POST;
            case REDEMPTION_CUSTODY ->
                    step == UsdzelleWorkflow.StepKind.CUSTODY_TRANSFER
                    || step == UsdzelleWorkflow.StepKind.CUSTODY_ACCOUNTING_POST;
            case BURN -> step == UsdzelleWorkflow.StepKind.BURN;
        };
        if (!matches) {
            throw new IllegalArgumentException(
                    "Solana evidence does not match the current workflow step");
        }
    }

    private static BigInteger exact(ResultSet row, String column) throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    public record Configuration(
            String clusterIdentity,
            String mintAddress,
            Map<String, UserConfiguration> users,
            String adminAddress,
            String adminWalletReference,
            String adminWalletMetadataVersion,
            String policyVersion) {
        public Configuration {
            clusterIdentity = publicKey(clusterIdentity, "clusterIdentity");
            mintAddress = publicKey(mintAddress, "mintAddress");
            String normalizedAdminAddress = publicKey(adminAddress, "adminAddress");
            adminAddress = normalizedAdminAddress;
            adminWalletReference = value(
                    adminWalletReference, "adminWalletReference");
            adminWalletMetadataVersion = value(
                    adminWalletMetadataVersion, "adminWalletMetadataVersion");
            policyVersion = value(policyVersion, "policyVersion");
            Map<String, UserConfiguration> normalized = new LinkedHashMap<>();
            Objects.requireNonNull(users, "users").forEach((reference, user) -> {
                String key = value(reference, "userWalletReference");
                UserConfiguration checked = new UserConfiguration(
                        publicKey(user.address(), "userAddress"),
                        value(user.walletReference(), "userWalletReference"),
                        value(user.walletMetadataVersion(), "userWalletMetadataVersion"));
                if (!key.equals(checked.walletReference())
                        || normalized.put(key, checked) != null
                        || checked.address().equals(normalizedAdminAddress)) {
                    throw new IllegalArgumentException(
                            "Solana workflow user configuration is inconsistent");
                }
            });
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException(
                        "at least one Solana workflow user is required");
            }
            users = Map.copyOf(normalized);
        }

        UserConfiguration user(UsdzelleWorkflow workflow) {
            return Optional.ofNullable(users.get(
                            workflow.context().userWallet().value()))
                    .orElseThrow(() -> new IllegalStateException(
                            "Solana workflow user wallet is not configured"));
        }

        private static String publicKey(String value, String name) {
            if (value == null || !PUBLIC_KEY.matcher(value).matches()) {
                throw new IllegalArgumentException(name + " is not a Solana public key");
            }
            return value;
        }

        private static String value(String value, String name) {
            if (value == null
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,255}")) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            return value;
        }
    }

    public record UserConfiguration(
            String address, String walletReference, String walletMetadataVersion) {
        public UserConfiguration {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(walletReference, "walletReference");
            Objects.requireNonNull(walletMetadataVersion, "walletMetadataVersion");
        }
    }

    private record NativeEvidence(
            UUID attemptId,
            int observationSequence,
            String evidenceReference,
            String transactionSignature,
            BigInteger slot,
            BigInteger userBalance,
            BigInteger adminBalance,
            BigInteger observedSupply,
            Instant observedAt) {
    }

    private record Retained(
            String evidenceReference,
            BigInteger slot,
            String transactionSignature,
            BigInteger userBalance,
            BigInteger adminBalance,
            BigInteger observedSupply) {
        boolean matches(NativeEvidence evidence) {
            return evidenceReference.equals(evidence.evidenceReference())
                    && slot.equals(evidence.slot())
                    && transactionSignature.equals(evidence.transactionSignature())
                    && userBalance.equals(evidence.userBalance())
                    && adminBalance.equals(evidence.adminBalance())
                    && observedSupply.equals(evidence.observedSupply());
        }
    }
}
