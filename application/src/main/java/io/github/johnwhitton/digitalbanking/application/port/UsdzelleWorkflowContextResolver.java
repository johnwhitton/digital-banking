package io.github.johnwhitton.digitalbanking.application.port;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;

/** Resolves the complete server-owned workflow context for first acceptance only. */
@FunctionalInterface
public interface UsdzelleWorkflowContextResolver {

    Resolution resolve(
            UsdzelleWorkflow.Kind kind,
            ParticipantScope participant,
            BankAccountReference bankAccountReference,
            String currency,
            Optional<SettlementNetwork> requestedNetwork);

    /** Fails closed when current local configuration no longer matches acceptance. */
    default void verifyAccepted(UsdzelleWorkflow workflow) {
        Objects.requireNonNull(workflow, "workflow");
        UsdzelleWorkflow.AcceptedContext accepted = workflow.context();
        Resolution current = resolve(
                workflow.kind(),
                new ParticipantScope(
                        workflow.participant().tenantId(),
                        workflow.participant().participantId()),
                new BankAccountReference(
                        "synthetic-bank:" + accepted.bankAccountId().value()),
                "USD", Optional.of(accepted.network()));
        if (!current.assetUnit().equals(accepted.tokenQuantity().unit())
                || !current.bankId().equals(accepted.bankId())
                || !current.bankAccountId().equals(accepted.bankAccountId())
                || !current.userWallet().equals(accepted.userWallet())
                || !current.userWalletMetadataVersion().equals(
                        accepted.userWalletMetadataVersion())
                || !current.adminWallet().equals(accepted.adminWallet())
                || !current.adminWalletMetadataVersion().equals(
                        accepted.adminWalletMetadataVersion())
                || current.network() != accepted.network()
                || !current.contractReference().equals(accepted.contractReference())
                || !current.payoutPolicyVersion().equals(
                        accepted.payoutPolicyVersion())
                || !current.workflowVersion().equals(accepted.workflowVersion())
                || !current.conversionPolicyVersion().equals(
                        accepted.conversionPolicyVersion())
                || !current.accountingPolicyVersion().equals(
                        accepted.accountingPolicyVersion())
                || !current.feePolicyVersion().equals(accepted.feePolicyVersion())
                || !current.finalityPolicyVersion().equals(
                        accepted.finalityPolicyVersion())
                || !current.reconciliationPolicyVersion().equals(
                        accepted.reconciliationPolicyVersion())) {
            throw new IllegalStateException(
                    "accepted workflow context no longer matches local authority");
        }
    }

    record Resolution(
            AssetUnit assetUnit,
            SyntheticBankAccount.BankId bankId,
            SyntheticBankAccount.AccountId bankAccountId,
            WalletReference userWallet,
            String userWalletMetadataVersion,
            WalletReference adminWallet,
            String adminWalletMetadataVersion,
            SettlementNetwork network,
            String contractReference,
            String payoutPolicyVersion,
            String workflowVersion,
            String conversionPolicyVersion,
            String accountingPolicyVersion,
            String feePolicyVersion,
            String finalityPolicyVersion,
            String reconciliationPolicyVersion,
            Instant acceptedAt) {
        public Resolution {
            Objects.requireNonNull(assetUnit, "assetUnit");
            Objects.requireNonNull(bankId, "bankId");
            Objects.requireNonNull(bankAccountId, "bankAccountId");
            Objects.requireNonNull(userWallet, "userWallet");
            Objects.requireNonNull(adminWallet, "adminWallet");
            Objects.requireNonNull(network, "network");
            Objects.requireNonNull(acceptedAt, "acceptedAt");
        }
    }
}
