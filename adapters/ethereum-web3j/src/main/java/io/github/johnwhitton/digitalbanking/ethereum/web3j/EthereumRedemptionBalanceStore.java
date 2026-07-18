package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Persists block-bound balances without making RPC state business truth. */
final class EthereumRedemptionBalanceStore {

    private final JdbcClient jdbc;

    EthereumRedemptionBalanceStore(DataSource dataSource) {
        jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    }

    Optional<Context> findByCustody(OperationId custodyOperationId) {
        return find("correlation.custody_operation_id", custodyOperationId);
    }

    Optional<Context> findByBurn(OperationId burnOperationId) {
        return find("correlation.burn_operation_id", burnOperationId);
    }

    private Optional<Context> find(String column, OperationId operationId) {
        return jdbc.sql("""
                SELECT correlation.correlation_id, transfer.source_address,
                       transfer.destination_address AS admin_address,
                       transfer.contract_address, transfer.quantity_atomic
                FROM ethereum_redemption_correlation correlation
                JOIN wallet_transfer_operation transfer
                  ON transfer.operation_id = correlation.custody_operation_id
                WHERE """ + " " + column + " = :operationId")
                .param("operationId", operationId.value())
                .query((row, number) -> new Context(
                        row.getObject("correlation_id", UUID.class),
                        row.getString("source_address"), row.getString("admin_address"),
                        row.getString("contract_address"), integer(row, "quantity_atomic")))
                .optional();
    }

    void record(Stage stage, Context context, Snapshot snapshot) {
        int inserted = jdbc.sql("""
                INSERT INTO ethereum_redemption_balance_observation (
                    correlation_id, observation_stage, block_number, block_hash,
                    source_address, admin_address, contract_address,
                    source_balance, admin_balance, total_supply,
                    observed_at, evidence_ref)
                VALUES (
                    :correlationId, :stage, :blockNumber, :blockHash,
                    :source, :admin, :contract, :sourceBalance, :adminBalance,
                    :totalSupply, :observedAt, :evidence)
                ON CONFLICT (correlation_id, observation_stage) DO NOTHING
                """)
                .param("correlationId", context.correlationId())
                .param("stage", stage.name())
                .param("blockNumber", snapshot.blockNumber())
                .param("blockHash", snapshot.blockHash())
                .param("source", context.sourceAddress())
                .param("admin", context.adminAddress())
                .param("contract", context.contractAddress())
                .param("sourceBalance", snapshot.sourceBalance())
                .param("adminBalance", snapshot.adminBalance())
                .param("totalSupply", snapshot.totalSupply())
                .param("observedAt", snapshot.observedAt().atOffset(ZoneOffset.UTC))
                .param("evidence", "internal:ethereum-redemption-balance:"
                        + stage.name().toLowerCase(java.util.Locale.ROOT) + ":"
                        + context.correlationId())
                .update();
        if (inserted == 0 && !matchesRetained(stage, context, snapshot)) {
            throw new IllegalStateException(
                    "retained redemption balance evidence does not match replay");
        }
    }

    private boolean matchesRetained(Stage stage, Context context, Snapshot snapshot) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM ethereum_redemption_balance_observation
                    WHERE correlation_id = :correlationId
                      AND observation_stage = :stage
                      AND block_number = :blockNumber
                      AND block_hash = :blockHash
                      AND source_address = :source
                      AND admin_address = :admin
                      AND contract_address = :contract
                      AND source_balance = :sourceBalance
                      AND admin_balance = :adminBalance
                      AND total_supply = :totalSupply)
                """)
                .param("correlationId", context.correlationId())
                .param("stage", stage.name())
                .param("blockNumber", snapshot.blockNumber())
                .param("blockHash", snapshot.blockHash())
                .param("source", context.sourceAddress())
                .param("admin", context.adminAddress())
                .param("contract", context.contractAddress())
                .param("sourceBalance", snapshot.sourceBalance())
                .param("adminBalance", snapshot.adminBalance())
                .param("totalSupply", snapshot.totalSupply())
                .query(Boolean.class).single();
    }

    boolean custodyDeltaMatches(UUID correlationId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM ethereum_redemption_correlation correlation
                    JOIN wallet_transfer_operation transfer
                      ON transfer.operation_id = correlation.custody_operation_id
                    JOIN ethereum_redemption_balance_observation before_state
                      ON before_state.correlation_id = correlation.correlation_id
                     AND before_state.observation_stage = 'BEFORE_CUSTODY'
                    JOIN ethereum_redemption_balance_observation after_state
                      ON after_state.correlation_id = correlation.correlation_id
                     AND after_state.observation_stage = 'AFTER_CUSTODY'
                    WHERE correlation.correlation_id = :correlationId
                      AND before_state.source_balance - transfer.quantity_atomic =
                          after_state.source_balance
                      AND before_state.admin_balance + transfer.quantity_atomic =
                          after_state.admin_balance
                      AND before_state.total_supply = after_state.total_supply)
                """)
                .param("correlationId", correlationId)
                .query(Boolean.class).single();
    }

    boolean burnDeltaMatches(UUID correlationId) {
        return jdbc.sql("""
                SELECT EXISTS (
                    SELECT 1
                    FROM ethereum_redemption_correlation correlation
                    JOIN wallet_transfer_operation transfer
                      ON transfer.operation_id = correlation.custody_operation_id
                    JOIN ethereum_redemption_balance_observation custody_state
                      ON custody_state.correlation_id = correlation.correlation_id
                     AND custody_state.observation_stage = 'AFTER_CUSTODY'
                    JOIN ethereum_redemption_balance_observation burn_state
                      ON burn_state.correlation_id = correlation.correlation_id
                     AND burn_state.observation_stage = 'AFTER_BURN'
                    WHERE correlation.correlation_id = :correlationId
                      AND custody_state.source_balance = burn_state.source_balance
                      AND custody_state.admin_balance - transfer.quantity_atomic =
                          burn_state.admin_balance
                      AND custody_state.total_supply - transfer.quantity_atomic =
                          burn_state.total_supply)
                """)
                .param("correlationId", correlationId)
                .query(Boolean.class).single();
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        return row.getBigDecimal(column).toBigIntegerExact();
    }

    record Context(
            UUID correlationId, String sourceAddress, String adminAddress,
            String contractAddress, BigInteger quantity) { }

    record Snapshot(
            BigInteger blockNumber, String blockHash,
            BigInteger sourceBalance, BigInteger adminBalance,
            BigInteger totalSupply, Instant observedAt) { }

    enum Stage {
        BEFORE_CUSTODY,
        AFTER_CUSTODY,
        AFTER_BURN
    }
}
