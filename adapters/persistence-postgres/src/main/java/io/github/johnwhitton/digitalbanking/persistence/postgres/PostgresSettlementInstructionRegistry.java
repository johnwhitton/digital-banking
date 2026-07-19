package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionRegistry;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import org.springframework.jdbc.core.simple.JdbcClient;

/** Read-only PostgreSQL adapter for server-owned settlement instructions. */
public final class PostgresSettlementInstructionRegistry
        implements SettlementInstructionRegistry {

    private final JdbcClient jdbc;

    public PostgresSettlementInstructionRegistry(DataSource dataSource) {
        jdbc = JdbcClient.create(dataSource);
    }

    @Override
    public Optional<Instruction> findSender(
            ParticipantScope participant,
            BankAccountReference bankAccount,
            String currency,
            SettlementNetwork network,
            Instant at) {
        List<Instruction> matches = jdbc.sql("""
                        SELECT * FROM settlement_instruction
                        WHERE tenant_id = :tenantId
                          AND participant_id = :participantId
                          AND bank_account_reference = :bankAccount
                          AND instruction_mode = 'ACQUISITION'
                          AND currency = :currency
                          AND settlement_network = :network
                          AND enabled
                          AND effective_at <= :at
                          AND (expires_at IS NULL OR expires_at > :at)
                        ORDER BY effective_at DESC, instruction_version DESC,
                                 instruction_id DESC
                        LIMIT 2
                        """)
                .param("tenantId", participant.tenantId())
                .param("participantId", participant.participantId())
                .param("bankAccount", bankAccount.value())
                .param("currency", currency)
                .param("network", network.name())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(PostgresSettlementInstructionRegistry::map).list();
        return unique(matches);
    }

    @Override
    public Optional<Instruction> findRecipient(
            BankAccountReference bankAccount,
            String currency,
            SettlementNetwork network,
            Instant at) {
        List<Instruction> matches = jdbc.sql("""
                        SELECT * FROM settlement_instruction
                        WHERE bank_account_reference = :bankAccount
                          AND instruction_mode = 'AUTO_REDEEM'
                          AND currency = :currency
                          AND settlement_network = :network
                          AND enabled
                          AND effective_at <= :at
                          AND (expires_at IS NULL OR expires_at > :at)
                        ORDER BY effective_at DESC, instruction_version DESC,
                                 instruction_id DESC
                        LIMIT 2
                        """)
                .param("bankAccount", bankAccount.value())
                .param("currency", currency)
                .param("network", network.name())
                .param("at", at.atOffset(ZoneOffset.UTC))
                .query(PostgresSettlementInstructionRegistry::map).list();
        return unique(matches);
    }

    private static Optional<Instruction> unique(List<Instruction> matches) {
        if (matches.size() > 1) {
            throw new IllegalStateException(
                    "settlement instruction routing is ambiguous");
        }
        return matches.stream().findFirst();
    }

    private static Instruction map(ResultSet row, int number) throws SQLException {
        OffsetDateTime expires = row.getObject("expires_at", OffsetDateTime.class);
        return new Instruction(
                row.getString("instruction_id"),
                row.getString("instruction_version"),
                new ParticipantScope(
                        row.getString("tenant_id"), row.getString("participant_id")),
                new SyntheticBankAccount.BankId(row.getString("bank_id")),
                new SyntheticBankAccount.AccountId(
                        row.getString("bank_account_id")),
                new BankAccountReference(row.getString("bank_account_reference")),
                new WalletReference(row.getString("wallet_reference")),
                SettlementTransfer.InstructionMode.valueOf(
                        row.getString("instruction_mode")),
                row.getString("currency"),
                SettlementNetwork.valueOf(row.getString("settlement_network")),
                row.getBoolean("enabled"),
                row.getObject("effective_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(expires).map(OffsetDateTime::toInstant));
    }
}
