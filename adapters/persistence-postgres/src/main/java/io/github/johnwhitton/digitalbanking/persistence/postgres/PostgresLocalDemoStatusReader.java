package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import javax.sql.DataSource;

import org.springframework.jdbc.core.simple.JdbcClient;

/** Fixed-fixture, read-only database evidence used only by the local demo status API. */
public final class PostgresLocalDemoStatusReader {

    private final JdbcClient jdbc;

    public PostgresLocalDemoStatusReader(DataSource dataSource) {
        this.jdbc = JdbcClient.create(Objects.requireNonNull(dataSource, "dataSource"));
    }

    public Snapshot snapshot() {
        return new Snapshot(
                keyedAmounts("""
                        SELECT account_id AS key, balance_cents AS amount
                        FROM synthetic_bank_account
                        WHERE (bank_id, account_id) IN (
                            ('BANK_1', 'USER_1_BANK_ACCOUNT'),
                            ('BANK_2', 'USER_2_BANK_ACCOUNT'))
                        ORDER BY account_id
                        """),
                keyedAmounts("""
                        SELECT account_type AS key, balance_cents AS amount
                        FROM accounting_ledger_account ORDER BY account_type
                        """),
                keyedAmounts("""
                        SELECT position_type AS key, quantity_cents AS amount
                        FROM accounting_operational_position ORDER BY position_type
                        """),
                effects(), latestWorkflow("ACQUISITION"), latestWorkflow("REDEMPTION"),
                latestSettlement(), payoutBeforeBurn());
    }

    private Map<String, BigInteger> keyedAmounts(String query) {
        LinkedHashMap<String, BigInteger> values = new LinkedHashMap<>();
        jdbc.sql(query).query((row, rowNumber) -> {
            values.put(row.getString("key"), row.getBigDecimal("amount").toBigIntegerExact());
            return rowNumber;
        }).list();
        return Map.copyOf(values);
    }

    private EffectCounts effects() {
        return jdbc.sql("""
                SELECT
                    (SELECT count(*) FROM synthetic_bank_operation
                     WHERE operation_kind = 'WITHDRAWAL'
                       AND operation_status = 'SUCCEEDED') AS withdrawals,
                    (SELECT count(DISTINCT operation.operation_id)
                     FROM token_operation operation
                     JOIN ethereum_mint_observation observation
                       ON observation.operation_id = operation.operation_id
                     WHERE operation.operation_kind = 'MINT'
                       AND operation.lifecycle_state = 'COMPLETED'
                       AND observation.observation_status = 'CONFIRMED') AS mints,
                    (SELECT count(DISTINCT operation.operation_id)
                     FROM wallet_transfer_operation operation
                     JOIN ethereum_wallet_transfer_observation observation
                       ON observation.operation_id = operation.operation_id
                     WHERE operation.transfer_purpose = 'USER_TRANSFER'
                       AND operation.operation_status = 'COMPLETED'
                       AND observation.observation_status = 'CONFIRMED') AS user_transfers,
                    (SELECT count(DISTINCT operation.operation_id)
                     FROM wallet_transfer_operation operation
                     JOIN ethereum_wallet_transfer_observation observation
                       ON observation.operation_id = operation.operation_id
                     WHERE operation.transfer_purpose = 'REDEMPTION_CUSTODY'
                       AND operation.operation_status = 'COMPLETED'
                       AND observation.observation_status = 'CONFIRMED') AS custody_transfers,
                    (SELECT count(*) FROM synthetic_bank_operation
                     WHERE operation_kind = 'DEPOSIT'
                       AND operation_status = 'SUCCEEDED') AS payouts,
                    (SELECT count(DISTINCT operation.operation_id)
                     FROM token_operation operation
                     JOIN ethereum_burn_observation observation
                       ON observation.operation_id = operation.operation_id
                     WHERE operation.operation_kind = 'BURN'
                       AND operation.lifecycle_state = 'COMPLETED'
                       AND observation.observation_status = 'CONFIRMED') AS burns
                """).query((row, rowNumber) -> new EffectCounts(
                        row.getLong("withdrawals"), row.getLong("mints"),
                        row.getLong("user_transfers"), row.getLong("custody_transfers"),
                        row.getLong("payouts"), row.getLong("burns"))).single();
    }

    private Optional<ParentStatus> latestWorkflow(String kind) {
        return jdbc.sql("""
                SELECT workflow.workflow_id::text AS parent_id,
                       workflow.workflow_status AS parent_status,
                       conclusion.reconciliation_status
                FROM usdzelle_workflow workflow
                LEFT JOIN usdzelle_workflow_conclusion conclusion
                  ON conclusion.workflow_id = workflow.workflow_id
                WHERE workflow.workflow_kind = :kind
                ORDER BY workflow.updated_at DESC, workflow.workflow_id DESC
                LIMIT 1
                """).param("kind", kind).query((row, rowNumber) -> new ParentStatus(
                        row.getString("parent_id"), row.getString("parent_status"),
                        row.getString("reconciliation_status"))).optional();
    }

    private Optional<ParentStatus> latestSettlement() {
        return jdbc.sql("""
                SELECT settlement.transfer_id::text AS parent_id,
                       settlement.parent_status,
                       conclusion.reconciliation_status
                FROM settlement_transfer settlement
                LEFT JOIN settlement_transfer_conclusion conclusion
                  ON conclusion.transfer_id = settlement.transfer_id
                ORDER BY settlement.updated_at DESC, settlement.transfer_id DESC
                LIMIT 1
                """).query((row, rowNumber) -> new ParentStatus(
                        row.getString("parent_id"), row.getString("parent_status"),
                        row.getString("reconciliation_status"))).optional();
    }

    private Optional<Boolean> payoutBeforeBurn() {
        return jdbc.sql("""
                WITH latest AS (
                    SELECT workflow_id FROM usdzelle_workflow
                    WHERE workflow_kind = 'REDEMPTION'
                    ORDER BY updated_at DESC, workflow_id DESC LIMIT 1
                )
                SELECT payout.aggregate_version < burn.aggregate_version AS ordered
                FROM latest
                JOIN usdzelle_workflow_transition payout
                  ON payout.workflow_id = latest.workflow_id
                 AND payout.to_status = 'PAYOUT_ACCOUNTED'
                JOIN usdzelle_workflow_transition burn
                  ON burn.workflow_id = latest.workflow_id
                 AND burn.to_status = 'BURN_CONFIRMED'
                """).query(Boolean.class).optional();
    }

    public record Snapshot(
            Map<String, BigInteger> bankBalancesCents,
            Map<String, BigInteger> ledgerBalancesCents,
            Map<String, BigInteger> operationalPositionsCents,
            EffectCounts confirmedEffects,
            Optional<ParentStatus> latestAcquisition,
            Optional<ParentStatus> latestRedemption,
            Optional<ParentStatus> latestSettlement,
            Optional<Boolean> payoutBeforeBurn) {
        public Snapshot {
            bankBalancesCents = Map.copyOf(bankBalancesCents);
            ledgerBalancesCents = Map.copyOf(ledgerBalancesCents);
            operationalPositionsCents = Map.copyOf(operationalPositionsCents);
            Objects.requireNonNull(confirmedEffects, "confirmedEffects");
            Objects.requireNonNull(latestAcquisition, "latestAcquisition");
            Objects.requireNonNull(latestRedemption, "latestRedemption");
            Objects.requireNonNull(latestSettlement, "latestSettlement");
            Objects.requireNonNull(payoutBeforeBurn, "payoutBeforeBurn");
        }
    }

    public record EffectCounts(
            long withdrawals,
            long mints,
            long userTransfers,
            long custodyTransfers,
            long payouts,
            long burns) {
    }

    public record ParentStatus(String id, String status, String reconciliation) {
        public ParentStatus {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(status, "status");
        }
    }
}
