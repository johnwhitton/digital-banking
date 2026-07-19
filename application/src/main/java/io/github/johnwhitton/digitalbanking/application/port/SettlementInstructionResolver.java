package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Resolves immutable two-party settlement context without caller-owned authority fields. */
public interface SettlementInstructionResolver {

    Optional<Resolution> resolve(
            ParticipantScope sender,
            BankAccountReference source,
            BankAccountReference destination,
            String currency,
            SettlementNetwork network,
            Instant acceptedAt);

    default boolean required() {
        return false;
    }

    default void verifyAccepted(SettlementTransfer transfer) {
        Objects.requireNonNull(transfer, "transfer");
    }

    record Resolution(
            SettlementTransfer.RouteSnapshot sender,
            SettlementTransfer.RouteSnapshot recipient,
            io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference adminWallet,
            String adminWalletMetadataVersion,
            String workflowVersion,
            String contractReference,
            String payoutPolicyVersion,
            String conversionPolicyVersion,
            String accountingPolicyVersion,
            String feePolicyVersion,
            String finalityPolicyVersion,
            String reconciliationPolicyVersion) {

        public Resolution {
            Objects.requireNonNull(sender, "sender");
            Objects.requireNonNull(recipient, "recipient");
            Objects.requireNonNull(adminWallet, "adminWallet");
            Objects.requireNonNull(
                    adminWalletMetadataVersion, "adminWalletMetadataVersion");
            Objects.requireNonNull(workflowVersion, "workflowVersion");
            Objects.requireNonNull(contractReference, "contractReference");
            Objects.requireNonNull(payoutPolicyVersion, "payoutPolicyVersion");
            Objects.requireNonNull(conversionPolicyVersion, "conversionPolicyVersion");
            Objects.requireNonNull(accountingPolicyVersion, "accountingPolicyVersion");
            Objects.requireNonNull(feePolicyVersion, "feePolicyVersion");
            Objects.requireNonNull(finalityPolicyVersion, "finalityPolicyVersion");
            Objects.requireNonNull(
                    reconciliationPolicyVersion, "reconciliationPolicyVersion");
        }
    }
}
