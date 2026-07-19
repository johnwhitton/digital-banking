package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.BankIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.MockBankPort;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.accounting.BankOperation;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.accounting.UsdCents;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

class RegisteredSettlementInstructionResolverTest {

    private static final Instant ACCEPTED = Instant.parse("2026-07-18T20:00:00Z");
    private static final Instant EXPIRES = ACCEPTED.plusSeconds(60);
    private static final ParticipantScope SENDER =
            new ParticipantScope("local-demo", "USER_1");
    private static final ParticipantScope RECIPIENT =
            new ParticipantScope("local-demo", "USER_2");
    private static final BankAccountReference SOURCE =
            new BankAccountReference("synthetic-bank:USER_1_BANK_ACCOUNT");
    private static final BankAccountReference DESTINATION =
            new BankAccountReference("synthetic-bank:USER_2_BANK_ACCOUNT");
    private static final WalletReference SENDER_WALLET =
            new WalletReference("synthetic-wallet:USER_WALLET_1");
    private static final WalletReference RECIPIENT_WALLET =
            new WalletReference("synthetic-wallet:USER_WALLET_2");
    private static final WalletReference ADMIN =
            new WalletReference("synthetic-wallet:ADMIN_REDEMPTION");

    @Test
    void acceptedInstructionThatExpiresBeforeDeliveryFailsClosed() {
        AtomicReference<Instant> now = new AtomicReference<>(ACCEPTED);
        RegisteredSettlementInstructionResolver resolver =
                new RegisteredSettlementInstructionResolver(
                        new ExpiringInstructions(), wallets(), banks(), policy(), now::get);
        var resolution = resolver.resolve(
                SENDER, SOURCE, DESTINATION, "USD",
                SettlementNetwork.ETHEREUM, ACCEPTED).orElseThrow();
        SettlementTransfer transfer = accepted(resolution);

        now.set(EXPIRES);

        assertThrows(IllegalStateException.class,
                () -> resolver.verifyAccepted(transfer));
    }

    private static RegisteredSettlementInstructionResolver.Policy policy() {
        return new RegisteredSettlementInstructionResolver.Policy(
                ADMIN, "phase-6c-v1",
                "0x0000000000000000000000000000000000000001",
                "payout-v1", "conversion-v1", "accounting-v1", "fee-v1",
                "finality-v1", "reconciliation-v1");
    }

    private static SettlementTransfer accepted(
            io.github.johnwhitton.digitalbanking.application.port
                    .SettlementInstructionResolver.Resolution resolution) {
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
        SettlementTransfer.AcceptedContext context =
                new SettlementTransfer.AcceptedContext(
                        resolution.workflowVersion(),
                        UsdCents.positive(new BigInteger("10000")),
                        TokenQuantity.ofAtomic(new BigInteger("10000"), unit),
                        resolution.sender(), resolution.recipient(),
                        resolution.adminWallet(),
                        resolution.adminWalletMetadataVersion(),
                        SettlementNetwork.ETHEREUM,
                        resolution.contractReference(),
                        resolution.payoutPolicyVersion(),
                        resolution.conversionPolicyVersion(),
                        resolution.accountingPolicyVersion(),
                        resolution.feePolicyVersion(),
                        resolution.finalityPolicyVersion(),
                        resolution.reconciliationPolicyVersion(),
                        "a".repeat(64), "b".repeat(64), ACCEPTED);
        return SettlementTransfer.accepted(
                new TransferId(new UUID(1, 1)), context,
                List.of(
                        new SettlementTransfer.BoundaryId(new UUID(2, 1)),
                        new SettlementTransfer.BoundaryId(new UUID(2, 2)),
                        new SettlementTransfer.BoundaryId(new UUID(2, 3)),
                        new SettlementTransfer.BoundaryId(new UUID(2, 4))),
                new SettlementTransfer.TransitionId(new UUID(3, 1)),
                new EvidenceRef("participant:settlement-transfer:accepted"));
    }

    private static WalletIdentityRegistry wallets() {
        Map<WalletReference, WalletIdentityRegistry.WalletIdentity> identities = Map.of(
                SENDER_WALLET, user(SENDER_WALLET, "1"),
                RECIPIENT_WALLET, user(RECIPIENT_WALLET, "2"),
                ADMIN, new WalletIdentityRegistry.WalletIdentity(
                        ADMIN, Set.of(), WalletIdentityRegistry.OwnerCategory.ADMIN,
                        SettlementNetwork.ETHEREUM,
                        "0x0000000000000000000000000000000000000003",
                        new KeyAlias("local-demo:admin"), "registry-v1", "key-v1",
                        Set.of(WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY),
                        WalletIdentityRegistry.Status.ENABLED));
        return new WalletIdentityRegistry() {
            @Override
            public WalletIdentity resolve(WalletReference reference) {
                return Optional.ofNullable(identities.get(reference)).orElseThrow();
            }

            @Override
            public List<WalletIdentity> identities() {
                return List.copyOf(identities.values());
            }
        };
    }

    private static WalletIdentityRegistry.WalletIdentity user(
            WalletReference reference, String suffix) {
        return new WalletIdentityRegistry.WalletIdentity(
                reference, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.ETHEREUM,
                "0x000000000000000000000000000000000000000" + suffix,
                new KeyAlias("local-demo:user-" + suffix),
                "registry-v1", "key-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
    }

    private static MockBankApplicationService banks() {
        MockBankPort bank = new MockBankPort() {
            @Override
            public Outcome request(Command command) {
                return Outcome.rejectedNoEffect("unused");
            }

            @Override
            public Optional<SyntheticBankAccount> findAccount(
                    SyntheticBankAccount.BankId bankId,
                    SyntheticBankAccount.AccountId accountId,
                    ParticipantScope participant) {
                if ((!participant.equals(SENDER) && !participant.equals(RECIPIENT))
                        || !accountId.value().equals(
                                participant.participantId() + "_BANK_ACCOUNT")) {
                    return Optional.empty();
                }
                return Optional.of(new SyntheticBankAccount(
                        bankId, accountId,
                        new SyntheticBankAccount.Owner(
                                participant.tenantId(), participant.participantId()),
                        new UsdCents(BigInteger.valueOf(10_000)), true, 0,
                        new SyntheticBankAccount.FixtureVersion("fixture-v1"),
                        new UsdCents(BigInteger.valueOf(10_000)), ACCEPTED, ACCEPTED));
            }
        };
        BankIdentityGenerator ids = new BankIdentityGenerator() {
            @Override
            public BankOperation.Id nextOperationId() {
                return new BankOperation.Id(new UUID(4, 1));
            }

            @Override
            public BankOperation.EvidenceId nextEvidenceId() {
                return new BankOperation.EvidenceId(new UUID(4, 2));
            }
        };
        return new MockBankApplicationService(
                bank, () -> ACCEPTED, ids,
                new BankOperation.PolicyVersion("bank-v1"), 1);
    }

    private static final class ExpiringInstructions
            implements SettlementInstructionRegistry {

        @Override
        public Optional<Instruction> findSender(
                ParticipantScope participant, BankAccountReference account,
                String currency, SettlementNetwork network, Instant at) {
            return active(at) ? Optional.of(instruction(
                    "sender", SENDER, "BANK_1", "USER_1_BANK_ACCOUNT",
                    SOURCE, SENDER_WALLET,
                    SettlementTransfer.InstructionMode.ACQUISITION)) : Optional.empty();
        }

        @Override
        public Optional<Instruction> findRecipient(
                BankAccountReference account, String currency,
                SettlementNetwork network, Instant at) {
            return active(at) ? Optional.of(instruction(
                    "recipient", RECIPIENT, "BANK_2", "USER_2_BANK_ACCOUNT",
                    DESTINATION, RECIPIENT_WALLET,
                    SettlementTransfer.InstructionMode.AUTO_REDEEM)) : Optional.empty();
        }

        private static boolean active(Instant at) {
            return at.isBefore(EXPIRES);
        }

        private static Instruction instruction(
                String id, ParticipantScope participant, String bank,
                String accountId, BankAccountReference account,
                WalletReference wallet, SettlementTransfer.InstructionMode mode) {
            return new Instruction(
                    id, "phase-6c-v1", participant,
                    new SyntheticBankAccount.BankId(bank),
                    new SyntheticBankAccount.AccountId(accountId),
                    account, wallet, mode, "USD", SettlementNetwork.ETHEREUM,
                    true, ACCEPTED.minusSeconds(1), Optional.of(EXPIRES));
        }
    }
}
