package io.github.johnwhitton.digitalbanking.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionRegistry;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionResolver;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;

/** Local policy resolver for durable sender and recipient settlement instructions. */
public final class RegisteredSettlementInstructionResolver
        implements SettlementInstructionResolver {

    private final SettlementInstructionRegistry instructions;
    private final WalletIdentityRegistry wallets;
    private final MockBankApplicationService banks;
    private final Policy policy;
    private final ClockPort clock;

    public RegisteredSettlementInstructionResolver(
            SettlementInstructionRegistry instructions,
            WalletIdentityRegistry wallets,
            MockBankApplicationService banks,
            Policy policy,
            ClockPort clock) {
        this.instructions = Objects.requireNonNull(instructions, "instructions");
        this.wallets = Objects.requireNonNull(wallets, "wallets");
        this.banks = Objects.requireNonNull(banks, "banks");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public Optional<Resolution> resolve(
            ParticipantScope sender,
            BankAccountReference source,
            BankAccountReference destination,
            String currency,
            SettlementNetwork network,
            Instant acceptedAt) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(destination, "destination");
        Objects.requireNonNull(currency, "currency");
        Objects.requireNonNull(network, "network");
        Objects.requireNonNull(acceptedAt, "acceptedAt");
        Optional<SettlementInstructionRegistry.Instruction> senderInstruction =
                instructions.findSender(
                        sender, source, currency, network, acceptedAt);
        Optional<SettlementInstructionRegistry.Instruction> recipientInstruction =
                instructions.findRecipient(
                        destination, currency, network, acceptedAt);
        if (senderInstruction.isEmpty() || recipientInstruction.isEmpty()) {
            return Optional.empty();
        }
        SettlementInstructionRegistry.Instruction senderRoute =
                senderInstruction.orElseThrow();
        SettlementInstructionRegistry.Instruction recipientRoute =
                recipientInstruction.orElseThrow();
        if (senderRoute.mode() != SettlementTransfer.InstructionMode.ACQUISITION
                || recipientRoute.mode()
                        != SettlementTransfer.InstructionMode.AUTO_REDEEM
                || !senderRoute.participant().equals(sender)
                || !senderRoute.bankAccountReference().equals(source)
                || !recipientRoute.bankAccountReference().equals(destination)
                || !senderRoute.currency().equals(currency)
                || !recipientRoute.currency().equals(currency)
                || senderRoute.network() != network
                || recipientRoute.network() != network
                || senderRoute.participant().equals(recipientRoute.participant())
                || !senderRoute.enabled() || !recipientRoute.enabled()) {
            throw new IllegalArgumentException(
                    "registered settlement instruction is not executable");
        }
        var senderAccount = banks.findAccount(
                senderRoute.participant(), senderRoute.bankId(),
                senderRoute.bankAccountId());
        var recipientAccount = banks.findAccount(
                recipientRoute.participant(), recipientRoute.bankId(),
                recipientRoute.bankAccountId());
        if (!senderAccount.enabled() || !recipientAccount.enabled()) {
            throw new IllegalArgumentException(
                    "registered settlement account is disabled");
        }
        SettlementTransfer.RouteSnapshot senderSnapshot = snapshot(senderRoute);
        SettlementTransfer.RouteSnapshot recipientSnapshot = snapshot(recipientRoute);
        if (senderSnapshot.wallet().equals(recipientSnapshot.wallet())) {
            throw new IllegalArgumentException(
                    "settlement sender and recipient wallets must differ");
        }
        WalletIdentityRegistry.WalletIdentity admin =
                wallets.resolve(policy.adminWallet());
        if (admin.ownerCategory() != WalletIdentityRegistry.OwnerCategory.ADMIN
                || admin.network() != network
                || admin.status() != WalletIdentityRegistry.Status.ENABLED
                || !admin.allowedPurposes().contains(
                        WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY)) {
            throw new IllegalArgumentException(
                    "settlement ADMIN identity is unavailable");
        }
        return Optional.of(new Resolution(
                senderSnapshot, recipientSnapshot, policy.adminWallet(),
                admin.registryVersion() + ':' + admin.keyVersion(),
                policy.workflowVersion(),
                policy.contractReference(), policy.payoutPolicyVersion(),
                policy.conversionPolicyVersion(), policy.accountingPolicyVersion(),
                policy.feePolicyVersion(), policy.finalityPolicyVersion(),
                policy.reconciliationPolicyVersion()));
    }

    @Override
    public boolean required() {
        return true;
    }

    @Override
    public void verifyAccepted(SettlementTransfer transfer) {
        SettlementTransfer.AcceptedContext accepted = transfer.context();
        Optional<Resolution> current = resolve(
                new ParticipantScope(
                        accepted.sender().participant().tenantId(),
                        accepted.sender().participant().participantId()),
                accepted.sender().bankAccountReference(),
                accepted.recipient().bankAccountReference(),
                "USD", accepted.network(), clock.now());
        Resolution resolved = current.orElseThrow(() -> new IllegalStateException(
                "accepted settlement instructions are no longer available"));
        if (!resolved.sender().equals(accepted.sender())
                || !resolved.recipient().equals(accepted.recipient())
                || !resolved.adminWallet().equals(accepted.adminWallet())
                || !resolved.adminWalletMetadataVersion().equals(
                        accepted.adminWalletMetadataVersion())
                || !resolved.workflowVersion().equals(accepted.workflowVersion())
                || !resolved.contractReference().equals(accepted.contractReference())
                || !resolved.payoutPolicyVersion().equals(
                        accepted.payoutPolicyVersion())
                || !resolved.conversionPolicyVersion().equals(
                        accepted.conversionPolicyVersion())
                || !resolved.accountingPolicyVersion().equals(
                        accepted.accountingPolicyVersion())
                || !resolved.feePolicyVersion().equals(
                        accepted.feePolicyVersion())
                || !resolved.finalityPolicyVersion().equals(
                        accepted.finalityPolicyVersion())
                || !resolved.reconciliationPolicyVersion().equals(
                        accepted.reconciliationPolicyVersion())) {
            throw new IllegalStateException(
                    "settlement configuration differs from accepted context");
        }
    }

    private SettlementTransfer.RouteSnapshot snapshot(
            SettlementInstructionRegistry.Instruction instruction) {
        WalletIdentityRegistry.WalletIdentity wallet =
                wallets.resolve(instruction.wallet());
        if (!wallet.reference().equals(instruction.wallet())
                || wallet.ownerCategory()
                        != WalletIdentityRegistry.OwnerCategory.USER_CUSTODY
                || wallet.network() != instruction.network()
                || wallet.status() != WalletIdentityRegistry.Status.ENABLED
                || !wallet.allowedPurposes().contains(
                        WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER)) {
            throw new IllegalArgumentException(
                    "settlement instruction wallet is not an enabled user identity");
        }
        return new SettlementTransfer.RouteSnapshot(
                instruction.instructionId(), instruction.instructionVersion(),
                new SettlementTransfer.Participant(
                        instruction.participant().tenantId(),
                        instruction.participant().participantId()),
                instruction.bankId(), instruction.bankAccountId(),
                instruction.bankAccountReference(), instruction.wallet(),
                wallet.registryVersion() + ':' + wallet.keyVersion(),
                instruction.mode());
    }

    public record Policy(
            io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference adminWallet,
            String workflowVersion,
            String contractReference,
            String payoutPolicyVersion,
            String conversionPolicyVersion,
            String accountingPolicyVersion,
            String feePolicyVersion,
            String finalityPolicyVersion,
            String reconciliationPolicyVersion) {
        public Policy {
            Objects.requireNonNull(adminWallet, "adminWallet");
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
