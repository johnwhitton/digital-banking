package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Durable lookup boundary for versioned server-owned settlement instructions. */
public interface SettlementInstructionRegistry {

    Optional<Instruction> findSender(
            ParticipantScope participant,
            BankAccountReference bankAccount,
            String currency,
            SettlementNetwork network,
            Instant at);

    Optional<Instruction> findRecipient(
            BankAccountReference bankAccount,
            String currency,
            SettlementNetwork network,
            Instant at);

    record Instruction(
            String instructionId,
            String instructionVersion,
            ParticipantScope participant,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId bankAccountId,
            BankAccountReference bankAccountReference,
            WalletReference wallet,
            SettlementTransfer.InstructionMode mode,
            String currency,
            SettlementNetwork network,
            boolean enabled,
            Instant effectiveAt,
            Optional<Instant> expiresAt) {

        public Instruction {
            Objects.requireNonNull(instructionId, "instructionId");
            Objects.requireNonNull(instructionVersion, "instructionVersion");
            Objects.requireNonNull(participant, "participant");
            Objects.requireNonNull(bankId, "bankId");
            Objects.requireNonNull(bankAccountId, "bankAccountId");
            Objects.requireNonNull(bankAccountReference, "bankAccountReference");
            Objects.requireNonNull(wallet, "wallet");
            Objects.requireNonNull(mode, "mode");
            Objects.requireNonNull(currency, "currency");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(effectiveAt, "effectiveAt");
            expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
            if (expiresAt.filter(value -> !value.isAfter(effectiveAt)).isPresent()) {
                throw new IllegalArgumentException(
                        "settlement instruction expiry must follow its effective time");
            }
        }
    }
}
