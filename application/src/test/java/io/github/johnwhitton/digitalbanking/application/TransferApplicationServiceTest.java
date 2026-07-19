package io.github.johnwhitton.digitalbanking.application;

import java.math.BigInteger;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import io.github.johnwhitton.digitalbanking.application.command.CanonicalCommandMetadata;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferRepository;
import io.github.johnwhitton.digitalbanking.application.port.TransferRouteCatalog;
import io.github.johnwhitton.digitalbanking.application.port.WalletRoleResolver;
import io.github.johnwhitton.digitalbanking.application.port.SettlementInstructionResolver;
import io.github.johnwhitton.digitalbanking.application.port.SettlementTransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.domain.accounting.SyntheticBankAccount;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.transfer.BankAccountReference;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.Transfer;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.domain.workflow.SettlementTransfer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TransferApplicationServiceTest {

    @Test
    void resolvesRouteAssetAndWalletsServerSideAndReplaysWithoutReresolution() {
        InMemoryRepository repository = new InMemoryRepository();
        AtomicInteger routeCalls = new AtomicInteger();
        AtomicInteger walletCalls = new AtomicInteger();
        TransferRouteCatalog routes = (currency, requested) -> {
            routeCalls.incrementAndGet();
            return Optional.of(new TransferRouteCatalog.Route(
                    currency,
                    requested.orElse(SettlementNetwork.SOLANA),
                    new AssetUnit("USD_STABLE", "USD", 4, 2,
                            new BigInteger("1000000000000")),
                    "route-v3"));
        };
        WalletRoleResolver wallets = (participant, route) -> {
            walletCalls.incrementAndGet();
            return new WalletRoleResolver.Resolution(
                    new WalletReference("synthetic-wallet:treasury-sol"),
                    new WalletReference("synthetic-wallet:recipient-sol"),
                    "wallet-policy-v2");
        };
        TransferApplicationService service = new TransferApplicationService(
                repository, routes, wallets,
                () -> Instant.parse("2026-07-17T18:00:00Z"), new SequentialIds());
        ParticipantScope participant = new ParticipantScope("tenant-a", "participant-a");
        TransferApplicationService.AcceptanceRequest request =
                new TransferApplicationService.AcceptanceRequest(
                        "12.34", "USD", "synthetic-bank:source-001",
                        "synthetic-bank:destination-001", null);

        TransferAcceptance first = service.accept(
                participant, request, IdempotencyKey.of("transfer-key-1"));
        TransferAcceptance replay = service.accept(
                participant, request, IdempotencyKey.of("transfer-key-1"));

        assertEquals(false, first.replayed());
        assertEquals(true, replay.replayed());
        assertEquals(first.transfer(), replay.transfer());
        assertEquals(SettlementNetwork.SOLANA,
                first.transfer().acceptanceContext().settlementNetwork());
        assertEquals("synthetic-wallet:treasury-sol",
                first.transfer().acceptanceContext().senderWallet().value());
        assertEquals(1, routeCalls.get());
        assertEquals(1, walletCalls.get());
        assertEquals(5, first.transfer().effects().size());
    }

    @Test
    void requestFieldsCannotOverrideWalletResolutionAndConflictingReplayFails() {
        InMemoryRepository repository = new InMemoryRepository();
        TransferApplicationService service = new TransferApplicationService(
                repository,
                (currency, requested) -> Optional.of(new TransferRouteCatalog.Route(
                        currency, SettlementNetwork.ETHEREUM,
                        new AssetUnit("USD_STABLE", "USD", 1, 2,
                                new BigInteger("1000000")), "route-v1")),
                (participant, route) -> new WalletRoleResolver.Resolution(
                        new WalletReference("synthetic-wallet:server-sender"),
                        new WalletReference("synthetic-wallet:server-recipient"),
                        "wallet-v1"),
                () -> Instant.parse("2026-07-17T18:00:00Z"), new SequentialIds());
        ParticipantScope participant = new ParticipantScope("tenant-a", "participant-a");
        IdempotencyKey key = IdempotencyKey.of("transfer-key-2");

        Transfer accepted = service.accept(participant,
                new TransferApplicationService.AcceptanceRequest(
                        "1", "USD", "synthetic-bank:source",
                        "synthetic-bank:destination", "ETHEREUM"), key).transfer();

        assertEquals("synthetic-wallet:server-sender",
                accepted.acceptanceContext().senderWallet().value());
        assertThrows(IdempotencyConflictException.class, () -> service.accept(
                participant,
                new TransferApplicationService.AcceptanceRequest(
                        "2", "USD", "synthetic-bank:source",
                        "synthetic-bank:destination", "ETHEREUM"), key));
        assertThrows(InvalidRequestException.class, () -> service.accept(
                participant,
                new TransferApplicationService.AcceptanceRequest(
                        "1", "USD", "synthetic-bank:source",
                        "synthetic-bank:destination", "POLYGON"),
                IdempotencyKey.of("transfer-key-3")));
        assertThrows(InvalidRequestException.class, () -> service.accept(
                participant,
                new TransferApplicationService.AcceptanceRequest(
                        "1", "USD", "synthetic-bank:same",
                        "synthetic-bank:same", "ETHEREUM"),
                IdempotencyKey.of("transfer-key-4")));
    }

    @Test
    void trustedWalletResolutionFailureIsNotMisclassifiedAsCallerInput() {
        TransferApplicationService service = new TransferApplicationService(
                new InMemoryRepository(),
                (currency, requested) -> Optional.of(new TransferRouteCatalog.Route(
                        currency, SettlementNetwork.ETHEREUM,
                        new AssetUnit("USD_STABLE", "USD", 1, 2,
                                new BigInteger("1000000")), "route-v1")),
                (participant, route) -> {
                    throw new IllegalArgumentException("trusted-wallet-policy-defect");
                },
                () -> Instant.parse("2026-07-17T18:00:00Z"), new SequentialIds());

        IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class, () -> service.accept(
                        new ParticipantScope("tenant-a", "participant-a"),
                        new TransferApplicationService.AcceptanceRequest(
                                "1", "USD", "synthetic-bank:source",
                                "synthetic-bank:destination", "ETHEREUM"),
                        IdempotencyKey.of("trusted-failure")));
        assertEquals("trusted-wallet-policy-defect", failure.getMessage());
    }

    @Test
    void snapshotsRegisteredCrossParticipantInstructionsAndReplaysThemExactly() {
        InMemoryRepository repository = new InMemoryRepository();
        AtomicInteger resolutionCalls = new AtomicInteger();
        SettlementInstructionResolver instructions = new SettlementInstructionResolver() {
            @Override
            public Optional<Resolution> resolve(
                    ParticipantScope sender, BankAccountReference source,
                    BankAccountReference destination, String currency,
                    SettlementNetwork network, Instant acceptedAt) {
                resolutionCalls.incrementAndGet();
                return Optional.of(new Resolution(
                        settlementRoute(
                                "sender", "USER_1", "BANK_1",
                                "USER_1_BANK_ACCOUNT", "USER_WALLET_1",
                                SettlementTransfer.InstructionMode.ACQUISITION),
                        settlementRoute(
                                "recipient", "USER_2", "BANK_2",
                                "USER_2_BANK_ACCOUNT", "USER_WALLET_2",
                                SettlementTransfer.InstructionMode.AUTO_REDEEM),
                        new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"),
                        "admin-v1", "phase-6c-v1", "local-token-v1",
                        "payout-before-burn-v1", "conversion-v1",
                        "accounting-v1", "fee-v1", "finality-v1",
                        "reconciliation-v1"));
            }

            @Override
            public boolean required() {
                return true;
            }
        };
        AtomicInteger settlementIds = new AtomicInteger(100);
        SettlementTransferIdentityGenerator settlementIdentity =
                new SettlementTransferIdentityGenerator() {
                    @Override
                    public SettlementTransfer.BoundaryId nextBoundaryId() {
                        return new SettlementTransfer.BoundaryId(
                                new UUID(10, settlementIds.incrementAndGet()));
                    }

                    @Override
                    public SettlementTransfer.TransitionId nextTransitionId() {
                        return new SettlementTransfer.TransitionId(
                                new UUID(11, settlementIds.incrementAndGet()));
                    }
                };
        TransferApplicationService service = new TransferApplicationService(
                repository,
                (currency, requested) -> Optional.of(new TransferRouteCatalog.Route(
                        "USD", SettlementNetwork.ETHEREUM,
                        new AssetUnit("USD_STABLE", "USD", 1, 2,
                                new BigInteger("1000000000000")), "route-v1")),
                (participant, route) -> { throw new AssertionError(
                        "legacy wallet roles must not resolve settlement instructions"); },
                () -> Instant.parse("2026-07-18T20:00:00Z"), new SequentialIds(),
                instructions, settlementIdentity);
        var request = new TransferApplicationService.AcceptanceRequest(
                "100", "USD", "synthetic-bank:USER_1_BANK_ACCOUNT",
                "synthetic-bank:USER_2_BANK_ACCOUNT", "ETHEREUM");
        ParticipantScope sender = new ParticipantScope("local-demo", "USER_1");

        TransferAcceptance accepted = service.accept(
                sender, request, IdempotencyKey.of("settlement-acceptance"));
        TransferAcceptance replay = service.accept(
                sender, request, IdempotencyKey.of("settlement-acceptance"));

        SettlementTransfer settlement = accepted.settlement().orElseThrow();
        assertEquals(new BigInteger("10000"),
                settlement.context().usdAmount().value());
        assertEquals("USER_2",
                settlement.context().recipient().participant().participantId());
        assertEquals(SettlementTransfer.InstructionMode.AUTO_REDEEM,
                settlement.context().recipient().mode());
        assertEquals(accepted.settlement(), replay.settlement());
        assertEquals(1, resolutionCalls.get());
    }

    private static SettlementTransfer.RouteSnapshot settlementRoute(
            String instruction, String participant, String bank,
            String account, String wallet, SettlementTransfer.InstructionMode mode) {
        return new SettlementTransfer.RouteSnapshot(
                instruction + "-instruction", "instruction-v1",
                new SettlementTransfer.Participant("local-demo", participant),
                new SyntheticBankAccount.BankId(bank),
                new SyntheticBankAccount.AccountId(account),
                new BankAccountReference("synthetic-bank:" + account),
                new WalletReference("synthetic-wallet:" + wallet),
                wallet.toLowerCase() + "-v1", mode);
    }

    private static final class InMemoryRepository implements TransferRepository {
        private String keyDigest;
        private CanonicalCommandMetadata request;
        private Transfer transfer;
        private SettlementTransfer settlement;

        @Override
        public TransferAcceptance accept(
                ParticipantScope participant,
                IdempotencyKey key,
                CanonicalCommandMetadata requestCommand,
                Supplier<TransferAcceptancePlan> factory) {
            if (transfer != null) {
                if (!key.sha256().equals(keyDigest)
                        || request.canonicalizationVersion()
                        != requestCommand.canonicalizationVersion()
                        || !request.digest().equals(requestCommand.digest())) {
                    throw new IdempotencyConflictException();
                }
                return new TransferAcceptance(
                        transfer, Optional.ofNullable(settlement), true);
            }
            keyDigest = key.sha256();
            request = requestCommand;
            TransferAcceptancePlan plan = factory.get();
            transfer = plan.transfer();
            settlement = plan.settlement().orElse(null);
            return new TransferAcceptance(transfer, plan.settlement(), false);
        }

        @Override
        public Optional<Transfer> findById(TransferId transferId, ParticipantScope participant) {
            return Optional.ofNullable(transfer)
                    .filter(value -> value.transferId().equals(transferId));
        }

        @Override
        public Optional<SettlementTransfer> findSettlementById(
                TransferId transferId, ParticipantScope participant) {
            return Optional.ofNullable(settlement)
                    .filter(value -> value.transferId().equals(transferId));
        }

        @Override
        public PreparationResult prepareFirstWithdrawal(
                UUID deliveryId, TransferId transferId,
                TransferTransition.Id transitionId, Instant preparedAt) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class SequentialIds implements TransferIdentityGenerator {
        private final AtomicInteger sequence = new AtomicInteger();

        private UUID next() {
            return new UUID(9, sequence.incrementAndGet());
        }

        @Override
        public TransferId nextTransferId() {
            return new TransferId(next());
        }

        @Override
        public TransferEffect.Id nextEffectId() {
            return new TransferEffect.Id(next());
        }

        @Override
        public TransferTransition.Id nextTransitionId() {
            return new TransferTransition.Id(next());
        }
    }
}
