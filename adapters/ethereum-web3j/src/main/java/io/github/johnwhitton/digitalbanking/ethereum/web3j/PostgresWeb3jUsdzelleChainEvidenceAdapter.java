package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.UsdzelleChainEvidencePort;
import io.github.johnwhitton.digitalbanking.domain.accounting.ReserveAccounting;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.http.HttpService;

/** Reads canonical local-chain state and binds it to authoritative child evidence. */
public final class PostgresWeb3jUsdzelleChainEvidenceAdapter
        implements UsdzelleChainEvidencePort, AutoCloseable {

    private static final Pattern ADDRESS = Pattern.compile("0x[0-9a-f]{40}");

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;
    private final Web3j client;
    private final EthereumTokenStateReader state;
    private final EthereumRedemptionBalanceStore balances;
    private final Configuration configuration;
    private final Clock clock;

    public PostgresWeb3jUsdzelleChainEvidenceAdapter(
            DataSource dataSource,
            String rpcUrl,
            boolean composeEnvironment,
            Configuration configuration,
            Clock clock) {
        Objects.requireNonNull(dataSource, "dataSource");
        if (rpcUrl == null
                || !rpcUrl.matches(composeEnvironment
                        ? "http://(127\\.0\\.0\\.1|localhost|anvil):[0-9]{1,5}"
                        : "http://(127\\.0\\.0\\.1|localhost):[0-9]{1,5}")) {
            throw new IllegalArgumentException(
                    "workflow chain evidence requires a local Anvil RPC");
        }
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(dataSource));
        this.client = Web3j.build(new HttpService(rpcUrl));
        this.state = new EthereumTokenStateReader(client);
        this.balances = new EthereumRedemptionBalanceStore(dataSource);
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
        EthereumRedemptionBalanceStore.Context context = stateContext(workflow);
        Optional<Retained> retained = findRetained(workflow, effect, childOperationId);
        if (retained.isPresent()) {
            revalidateRetained(context, retained.orElseThrow());
            return new ReserveAccounting.EvidenceIdentity(
                    retained.orElseThrow().evidenceReference());
        }
        NativeEvidence nativeEvidence = loadNative(effect, workflow, childOperationId);
        EthereumRedemptionBalanceStore.Snapshot snapshot;
        try {
            snapshot = state.at(
                    context, nativeEvidence.blockNumber(), Instant.now(clock));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "canonical workflow chain-state inquiry failed", failure);
        }
        if (!snapshot.blockHash().equals(nativeEvidence.blockHash())) {
            throw new IllegalStateException(
                    "workflow child observation is no longer canonical");
        }
        return Objects.requireNonNull(transactions.execute(status -> persist(
                effect, workflow, childOperationId, nativeEvidence, snapshot)));
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
        if (custody.blockNumber().signum() <= 0) {
            throw new IllegalStateException("custody observation has no predecessor block");
        }
        EthereumRedemptionBalanceStore.Context stateContext = stateContext(workflow);
        Instant observedAt = Instant.now(clock);
        EthereumRedemptionBalanceStore.Snapshot before;
        EthereumRedemptionBalanceStore.Snapshot after;
        try {
            before = state.at(
                    stateContext, custody.blockNumber().subtract(BigInteger.ONE), observedAt);
            after = state.at(stateContext, custody.blockNumber(), observedAt);
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "payout-gated burn custody inquiry failed", failure);
        }
        BigInteger quantity = workflow.context().tokenQuantity().atomicUnits();
        if (!after.blockHash().equals(custody.blockHash())
                || !before.sourceBalance().subtract(quantity)
                        .equals(after.sourceBalance())
                || !before.adminBalance().add(quantity).equals(after.adminBalance())
                || !before.totalSupply().equals(after.totalSupply())
                || !retainedCustodyStateMatches(
                        workflow, custodyOperationId, custody, after)) {
            throw new IllegalStateException(
                    "confirmed custody state does not authorize the later burn");
        }
        Objects.requireNonNull(transactions.execute(status -> {
            insertBurnBinding(
                    workflow, custodyOperationId, burnOperationId, custody, observedAt);
            EthereumRedemptionBalanceStore.Context retained = balances
                    .findByBurn(burnOperationId).orElseThrow();
            UserConfiguration user = configuration.user(workflow);
            if (!retained.sourceAddress().equals(user.address())
                    || !retained.adminAddress().equals(configuration.adminAddress())
                    || !retained.contractAddress().equals(configuration.contractAddress())
                    || !retained.quantity().equals(quantity)) {
                throw new IllegalStateException(
                        "retained payout-gated burn context conflicts with workflow");
            }
            balances.record(EthereumRedemptionBalanceStore.Stage.BEFORE_CUSTODY,
                    retained, before);
            balances.record(EthereumRedemptionBalanceStore.Stage.AFTER_CUSTODY,
                    retained, after);
            return Boolean.TRUE;
        }));
    }

    private boolean retainedCustodyStateMatches(
            UsdzelleWorkflow workflow,
            OperationId custodyOperationId,
            NativeEvidence custody,
            EthereumRedemptionBalanceStore.Snapshot after) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM usdzelle_chain_state_observation
                            WHERE workflow_id = :workflowId
                              AND effect_type = 'REDEMPTION_CUSTODY'
                              AND child_operation_id = :custodyId
                              AND evidence_reference = :evidence
                              AND block_number = :blockNumber
                              AND block_hash = :blockHash
                              AND user_balance_atomic = :userBalance
                              AND admin_balance_atomic = :adminBalance
                              AND total_supply_atomic = :totalSupply)
                        """)
                .param("workflowId", workflow.id().value())
                .param("custodyId", custodyOperationId.value())
                .param("evidence", custody.evidenceReference())
                .param("blockNumber", after.blockNumber())
                .param("blockHash", after.blockHash())
                .param("userBalance", after.sourceBalance())
                .param("adminBalance", after.adminBalance())
                .param("totalSupply", after.totalSupply())
                .query(Boolean.class).single();
    }

    private void insertBurnBinding(
            UsdzelleWorkflow workflow,
            OperationId custodyOperationId,
            OperationId burnOperationId,
            NativeEvidence custody,
            Instant recordedAt) {
        int inserted = jdbc.sql("""
                        INSERT INTO ethereum_redemption_correlation (
                            correlation_id, burn_operation_id, custody_operation_id,
                            custody_effect_id, custody_attempt_id, correlation_status,
                            custody_evidence_ref, created_at, updated_at)
                        SELECT transfer.transfer_id, burn.operation_id,
                               transfer.operation_id, transfer.effect_id,
                               transfer.attempt_id, 'CUSTODY_CONFIRMED',
                               :evidence, :createdAt, :recordedAt
                        FROM token_operation burn
                        JOIN wallet_transfer_operation transfer
                          ON transfer.operation_id = :custodyId
                        WHERE burn.operation_id = :burnId
                          AND burn.operation_kind = 'BURN'
                          AND transfer.transfer_purpose = 'REDEMPTION_CUSTODY'
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
                              AND correlation_status = 'CUSTODY_CONFIRMED'
                              AND custody_evidence_ref = :evidence)
                        """)
                .param("burnId", burnOperationId.value())
                .param("custodyId", custodyOperationId.value())
                .param("evidence", custody.evidenceReference())
                .query(Boolean.class).single()) {
            throw new IllegalStateException(
                    "payout-gated burn binding conflicts with retained custody");
        }
    }

    private ReserveAccounting.EvidenceIdentity persist(
            Effect effect,
            UsdzelleWorkflow workflow,
            OperationId child,
            NativeEvidence nativeEvidence,
            EthereumRedemptionBalanceStore.Snapshot snapshot) {
        int inserted = jdbc.sql("""
                        INSERT INTO usdzelle_chain_state_observation (
                            workflow_id, step_id, effect_type, child_operation_id,
                            evidence_reference, block_number, block_hash,
                            user_balance_atomic, admin_balance_atomic,
                            total_supply_atomic, observed_at)
                        VALUES (
                            :workflowId, :stepId, :effectType, :childId,
                            :evidenceReference, :blockNumber, :blockHash,
                            :userBalance, :adminBalance, :totalSupply, :observedAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("workflowId", workflow.id().value())
                .param("stepId", workflow.currentStep().id().value())
                .param("effectType", effect.name())
                .param("childId", child.value())
                .param("evidenceReference", nativeEvidence.evidenceReference())
                .param("blockNumber", snapshot.blockNumber())
                .param("blockHash", snapshot.blockHash())
                .param("userBalance", snapshot.sourceBalance())
                .param("adminBalance", snapshot.adminBalance())
                .param("totalSupply", snapshot.totalSupply())
                .param("observedAt", utc(snapshot.observedAt()))
                .update();
        if (inserted == 0 && findRetained(workflow, effect, child).isEmpty()) {
            throw new IllegalStateException(
                    "workflow chain evidence conflicts with retained evidence");
        }
        int pointer = jdbc.sql("""
                        INSERT INTO accounting_confirmed_evidence (
                            evidence_id, evidence_type, operation_id, attempt_id,
                            observation_sequence, observed_supply_cents, recorded_at)
                        VALUES (
                            :evidenceId, :evidenceType, :operationId, :attemptId,
                            :sequence, :supply, :recordedAt)
                        ON CONFLICT DO NOTHING
                        """)
                .param("evidenceId", nativeEvidence.evidenceReference())
                .param("evidenceType", effect.name())
                .param("operationId", child.value())
                .param("attemptId", nativeEvidence.attemptId())
                .param("sequence", nativeEvidence.observationSequence())
                .param("supply", snapshot.totalSupply())
                .param("recordedAt", utc(snapshot.observedAt()))
                .update();
        if (pointer == 0 && !pointerMatches(effect, child, nativeEvidence, snapshot)) {
            throw new IllegalStateException(
                    "accounting chain-evidence pointer conflicts with retained evidence");
        }
        return new ReserveAccounting.EvidenceIdentity(nativeEvidence.evidenceReference());
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
                          AND effect_type = :effectType
                          AND child_operation_id = :childId
                        """)
                .param("workflowId", workflow.id().value())
                .param("stepId", workflow.currentStep().id().value())
                .param("effectType", effect.name())
                .param("childId", child.value())
                .query((row, number) -> new Retained(
                        row.getString("evidence_reference"),
                        exact(row, "block_number"), row.getString("block_hash"),
                        exact(row, "user_balance_atomic"),
                        exact(row, "admin_balance_atomic"),
                        exact(row, "total_supply_atomic")))
                .optional();
    }

    private void revalidateRetained(
            EthereumRedemptionBalanceStore.Context context, Retained retained) {
        EthereumRedemptionBalanceStore.Snapshot current;
        try {
            current = state.at(context, retained.blockNumber(), Instant.now(clock));
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(
                    "retained workflow chain-state inquiry failed", failure);
        }
        if (!retained.blockHash().equals(current.blockHash())
                || !retained.userBalance().equals(current.sourceBalance())
                || !retained.adminBalance().equals(current.adminBalance())
                || !retained.totalSupply().equals(current.totalSupply())) {
            throw new IllegalStateException(
                    "retained workflow chain evidence is no longer canonical");
        }
    }

    private EthereumRedemptionBalanceStore.Context stateContext(
            UsdzelleWorkflow workflow) {
        return new EthereumRedemptionBalanceStore.Context(
                workflow.id().value(), configuration.user(workflow).address(),
                configuration.adminAddress(), configuration.contractAddress(),
                workflow.context().tokenQuantity().atomicUnits());
    }

    private void validateAcceptedContext(UsdzelleWorkflow workflow) {
        UsdzelleWorkflow.AcceptedContext accepted = workflow.context();
        UserConfiguration user = configuration.user(workflow);
        if (!accepted.contractReference().equals(configuration.contractAddress())
                || !accepted.userWallet().value().equals(user.walletReference())
                || !accepted.userWalletMetadataVersion().equals(
                        user.walletMetadataVersion())
                || !accepted.adminWallet().value().equals(
                        configuration.adminWalletReference())
                || !accepted.adminWalletMetadataVersion().equals(
                        configuration.adminWalletMetadataVersion())) {
            throw new IllegalStateException(
                    "workflow chain configuration differs from accepted context");
        }
    }

    private boolean pointerMatches(
            Effect effect,
            OperationId child,
            NativeEvidence evidence,
            EthereumRedemptionBalanceStore.Snapshot snapshot) {
        return jdbc.sql("""
                        SELECT EXISTS (
                            SELECT 1 FROM accounting_confirmed_evidence
                            WHERE evidence_id = :evidenceId
                              AND evidence_type = :evidenceType
                              AND operation_id = :operationId
                              AND attempt_id = :attemptId
                              AND observation_sequence = :sequence
                              AND observed_supply_cents = :supply)
                        """)
                .param("evidenceId", evidence.evidenceReference())
                .param("evidenceType", effect.name())
                .param("operationId", child.value())
                .param("attemptId", evidence.attemptId())
                .param("sequence", evidence.observationSequence())
                .param("supply", snapshot.totalSupply())
                .query(Boolean.class).single();
    }

    private NativeEvidence loadNative(
            Effect effect, UsdzelleWorkflow workflow, OperationId child) {
        String sql = switch (effect) {
            case MINT -> """
                    SELECT o.operation_attempt_id AS attempt_id,
                           o.observation_sequence, o.block_number, o.block_hash,
                           o.evidence_ref
                    FROM token_operation op
                    JOIN ethereum_mint_attempt a ON a.operation_id = op.operation_id
                    JOIN ethereum_mint_observation o
                      ON o.operation_id = a.operation_id
                     AND o.operation_attempt_id = a.operation_attempt_id
                    JOIN LATERAL (
                        SELECT finality_status FROM operation_finality
                        WHERE operation_id = op.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1) f ON true
                    WHERE op.operation_id = :operationId
                      AND op.operation_kind = 'MINT'
                      AND op.tenant_id = :tenantId
                      AND op.participant_id = :participantId
                      AND op.asset_id = :assetId
                      AND op.unit_id = :unitId
                      AND op.unit_version = :unitVersion
                      AND op.unit_scale = :unitScale
                      AND op.quantity_atomic = :quantity
                      AND op.lifecycle_state = 'COMPLETED'
                      AND a.attempt_status = 'CONFIRMED'
                      AND o.observation_status = 'CONFIRMED'
                      AND o.mint_recipient_address = :userAddress
                      AND o.observed_contract_address = :contractAddress
                      AND f.finality_status = 'REACHED'
                    ORDER BY o.observation_sequence DESC LIMIT 1
                    """;
            case REDEMPTION_CUSTODY -> """
                    SELECT o.attempt_id, o.observation_sequence,
                           o.block_number, o.block_hash, o.evidence_ref
                    FROM wallet_transfer_operation w
                    JOIN ethereum_wallet_transfer_attempt a
                      ON a.operation_id = w.operation_id
                    JOIN ethereum_wallet_transfer_observation o
                      ON o.operation_id = a.operation_id
                     AND o.attempt_id = a.attempt_id
                    JOIN LATERAL (
                        SELECT finality_status FROM wallet_transfer_finality
                        WHERE operation_id = w.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1) f ON true
                    WHERE w.operation_id = :operationId
                      AND w.transfer_purpose = 'REDEMPTION_CUSTODY'
                      AND w.tenant_id = :tenantId
                      AND w.participant_id = :participantId
                      AND w.asset_id = :assetId
                      AND w.unit_id = :unitId
                      AND w.unit_version = :unitVersion
                      AND w.unit_scale = :unitScale
                      AND w.quantity_atomic = :quantity
                      AND w.operation_status = 'COMPLETED'
                      AND a.attempt_status = 'CONFIRMED'
                      AND o.observation_status = 'CONFIRMED'
                      AND o.event_source_address = :userAddress
                      AND o.event_destination_address = :adminAddress
                      AND o.observed_contract_address = :contractAddress
                      AND f.finality_status = 'REACHED'
                    ORDER BY o.observation_sequence DESC LIMIT 1
                    """;
            case BURN -> """
                    SELECT o.operation_attempt_id AS attempt_id,
                           o.observation_sequence, o.block_number, o.block_hash,
                           o.evidence_ref
                    FROM token_operation op
                    JOIN ethereum_burn_attempt a ON a.operation_id = op.operation_id
                    JOIN ethereum_burn_observation o
                      ON o.operation_id = a.operation_id
                     AND o.operation_attempt_id = a.operation_attempt_id
                    JOIN LATERAL (
                        SELECT finality_status FROM operation_finality
                        WHERE operation_id = op.operation_id
                          AND finality_type = 'BLOCKCHAIN'
                        ORDER BY history_order DESC LIMIT 1) f ON true
                    WHERE op.operation_id = :operationId
                      AND op.operation_kind = 'BURN'
                      AND op.tenant_id = :tenantId
                      AND op.participant_id = :participantId
                      AND op.asset_id = :assetId
                      AND op.unit_id = :unitId
                      AND op.unit_version = :unitVersion
                      AND op.unit_scale = :unitScale
                      AND op.quantity_atomic = :quantity
                      AND op.lifecycle_state = 'COMPLETED'
                      AND a.attempt_status = 'CONFIRMED'
                      AND o.observation_status = 'CONFIRMED'
                      AND o.event_source_address = :adminAddress
                      AND o.observed_contract_address = :contractAddress
                      AND f.finality_status = 'REACHED'
                    ORDER BY o.observation_sequence DESC LIMIT 1
                    """;
        };
        return jdbc.sql(sql)
                .param("operationId", child.value())
                .param("tenantId", workflow.participant().tenantId())
                .param("participantId", workflow.participant().participantId())
                .param("assetId", workflow.context().tokenQuantity().unit().assetId())
                .param("unitId", workflow.context().tokenQuantity().unit().unitId())
                .param("unitVersion", workflow.context().tokenQuantity().unit().version())
                .param("unitScale", workflow.context().tokenQuantity().unit().scale())
                .param("quantity", workflow.context().tokenQuantity().atomicUnits())
                .param("userAddress", configuration.user(workflow).address())
                .param("adminAddress", configuration.adminAddress())
                .param("contractAddress", configuration.contractAddress())
                .query(PostgresWeb3jUsdzelleChainEvidenceAdapter::mapNative)
                .optional()
                .orElseThrow(() -> new IllegalStateException(
                        "confirmed canonical child evidence is unavailable"));
    }

    private static NativeEvidence mapNative(ResultSet row, int number) throws SQLException {
        return new NativeEvidence(
                row.getObject("attempt_id", UUID.class),
                row.getInt("observation_sequence"),
                exact(row, "block_number"), row.getString("block_hash"),
                row.getString("evidence_ref"));
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
                    "chain evidence does not match the current workflow step");
        }
    }

    private static BigInteger exact(ResultSet row, String column) throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static OffsetDateTime utc(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    @Override
    public void close() {
        client.shutdown();
    }

    public record Configuration(
            String contractAddress,
            java.util.Map<String, UserConfiguration> users,
            String adminAddress,
            String adminWalletReference,
            String adminWalletMetadataVersion) {
        public Configuration {
            contractAddress = address(contractAddress, "contractAddress");
            String normalizedAdminAddress = address(adminAddress, "adminAddress");
            java.util.Map<String, UserConfiguration> normalized =
                    new java.util.LinkedHashMap<>();
            Objects.requireNonNull(users, "users").forEach((reference, user) -> {
                String normalizedReference = value(reference, "userWalletReference");
                UserConfiguration normalizedUser = new UserConfiguration(
                        address(user.address(), "userAddress"),
                        value(user.walletReference(), "userWalletReference"),
                        value(user.walletMetadataVersion(),
                                "userWalletMetadataVersion"));
                if (!normalizedReference.equals(normalizedUser.walletReference())
                        || normalized.put(normalizedReference, normalizedUser) != null) {
                    throw new IllegalArgumentException(
                            "workflow user configuration is inconsistent");
                }
            });
            users = java.util.Map.copyOf(normalized);
            adminWalletReference = value(adminWalletReference, "adminWalletReference");
            adminWalletMetadataVersion = value(
                    adminWalletMetadataVersion, "adminWalletMetadataVersion");
            if (users.isEmpty()
                    || users.values().stream().map(UserConfiguration::address)
                        .distinct().count() != users.size()
                    || users.values().stream()
                        .anyMatch(user -> user.address().equals(normalizedAdminAddress))) {
                throw new IllegalArgumentException(
                        "workflow user and ADMIN addresses must be distinct");
            }
            adminAddress = normalizedAdminAddress;
        }

        public Configuration(
                String contractAddress,
                String userAddress,
                String adminAddress,
                String userWalletReference,
                String userWalletMetadataVersion,
                String adminWalletReference,
                String adminWalletMetadataVersion) {
            this(contractAddress,
                    java.util.Map.of(userWalletReference,
                            new UserConfiguration(
                                    userAddress, userWalletReference,
                                    userWalletMetadataVersion)),
                    adminAddress, adminWalletReference,
                    adminWalletMetadataVersion);
        }

        UserConfiguration user(UsdzelleWorkflow workflow) {
            return Optional.ofNullable(users.get(
                            workflow.context().userWallet().value()))
                    .orElseThrow(() -> new IllegalStateException(
                            "workflow user wallet is not configured"));
        }

        private static String address(String value, String name) {
            String normalized = Objects.requireNonNull(value, name)
                    .toLowerCase(Locale.ROOT);
            if (!ADDRESS.matcher(normalized).matches()) {
                throw new IllegalArgumentException(name + " is not an EVM address");
            }
            return normalized;
        }

        private static String value(String value, String name) {
            if (value == null
                    || !value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) {
                throw new IllegalArgumentException(name + " is invalid");
            }
            return value;
        }
    }

    public record UserConfiguration(
            String address,
            String walletReference,
            String walletMetadataVersion) {
        public UserConfiguration {
            Objects.requireNonNull(address, "address");
            Objects.requireNonNull(walletReference, "walletReference");
            Objects.requireNonNull(walletMetadataVersion, "walletMetadataVersion");
        }
    }

    private record NativeEvidence(
            UUID attemptId,
            int observationSequence,
            BigInteger blockNumber,
            String blockHash,
            String evidenceReference) {
    }

    private record Retained(
            String evidenceReference,
            BigInteger blockNumber,
            String blockHash,
            BigInteger userBalance,
            BigInteger adminBalance,
            BigInteger totalSupply) {
    }
}
