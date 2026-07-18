package io.github.johnwhitton.digitalbanking.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import org.junit.jupiter.api.Test;

class WalletTransferAcceptanceServiceTest {

    private static final AssetUnit USD = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final ParticipantScope PARTICIPANT = new ParticipantScope("tenant-a", "party-a");
    private static final WalletReference SOURCE = wallet("USER_WALLET_1");
    private static final WalletReference DESTINATION = wallet("USER_WALLET_2");

    @Test
    void resolvesAndRetainsExactServerOwnedUserWalletContext() {
        InMemoryRepository repository = new InMemoryRepository();
        Map<WalletReference, WalletIdentityRegistry.WalletIdentity> identities = new HashMap<>();
        identities.put(SOURCE,
                identity(SOURCE, "0x1111111111111111111111111111111111111111"));
        identities.put(DESTINATION,
                identity(DESTINATION, "0x2222222222222222222222222222222222222222"));
        WalletTransferAcceptanceService service = service(repository, identities);

        var accepted = service.accept(
                PARTICIPANT, new IdempotencyKey("wallet-transfer-1"),
                request("100", SOURCE, DESTINATION));

        assertEquals(new BigInteger("10000"), accepted.operation().quantity().atomicUnits());
        assertEquals(SOURCE, accepted.operation().source().reference());
        assertEquals(DESTINATION, accepted.operation().destination().reference());
        assertEquals(WalletTransferOperation.Status.ACCEPTED, accepted.operation().status());
        assertTrue(accepted.operation().finalityHistories().values().stream()
                .allMatch(history -> history.size() == 1));

        identities.clear();
        var replay = service.accept(
                PARTICIPANT, new IdempotencyKey("wallet-transfer-1"),
                request("100", SOURCE, DESTINATION));
        assertTrue(replay.replayed());
        assertEquals(accepted.operation(), replay.operation());
    }

    @Test
    void rejectsConflictsAndNonUserOrSameWalletAuthority() {
        InMemoryRepository repository = new InMemoryRepository();
        WalletReference bank = wallet("BANK_1_SETTLEMENT");
        Map<WalletReference, WalletIdentityRegistry.WalletIdentity> identities = new HashMap<>();
        identities.put(SOURCE, identity(SOURCE, "0x1111111111111111111111111111111111111111"));
        identities.put(DESTINATION, identity(DESTINATION, "0x2222222222222222222222222222222222222222"));
        identities.put(bank, new WalletIdentityRegistry.WalletIdentity(
                bank, Set.of(), WalletIdentityRegistry.OwnerCategory.BANK_SETTLEMENT,
                SettlementNetwork.ETHEREUM,
                "0x3333333333333333333333333333333333333333",
                new KeyAlias("local-demo:BANK_1_SETTLEMENT"), "registry-v1", "key-v1",
                Set.of(WalletIdentityRegistry.Purpose.BANK_SETTLEMENT_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED));
        WalletTransferAcceptanceService service = service(repository, identities);

        service.accept(PARTICIPANT, new IdempotencyKey("wallet-transfer-2"),
                request("100", SOURCE, DESTINATION));
        assertThrows(IdempotencyConflictException.class, () -> service.accept(
                PARTICIPANT, new IdempotencyKey("wallet-transfer-2"),
                request("99", SOURCE, DESTINATION)));
        assertThrows(InvalidRequestException.class, () -> service.accept(
                PARTICIPANT, new IdempotencyKey("wallet-transfer-3"),
                request("100", SOURCE, SOURCE)));
        assertThrows(InvalidRequestException.class, () -> service.accept(
                PARTICIPANT, new IdempotencyKey("wallet-transfer-4"),
                request("100", bank, DESTINATION)));
    }

    @Test
    void rejectsInvalidQuantityAndDisabledWrongPurposeNetworkOrAddress() {
        assertInvalid(identity(
                SOURCE, "0x1111111111111111111111111111111111111111"),
                new WalletIdentityRegistry.WalletIdentity(
                        DESTINATION, Set.of(),
                        WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                        SettlementNetwork.ETHEREUM,
                        "0x2222222222222222222222222222222222222222",
                        new KeyAlias("local-demo:USER_WALLET_2"),
                        "registry-v1", "key-v1",
                        Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                        WalletIdentityRegistry.Status.DISABLED));
        assertInvalid(new WalletIdentityRegistry.WalletIdentity(
                        SOURCE, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                        SettlementNetwork.ETHEREUM,
                        "0x1111111111111111111111111111111111111111",
                        new KeyAlias("local-demo:USER_WALLET_1"),
                        "registry-v1", "key-v1",
                        Set.of(WalletIdentityRegistry.Purpose.BANK_SETTLEMENT_TRANSFER),
                        WalletIdentityRegistry.Status.ENABLED),
                identity(DESTINATION,
                        "0x2222222222222222222222222222222222222222"));
        assertInvalid(new WalletIdentityRegistry.WalletIdentity(
                        SOURCE, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                        SettlementNetwork.SOLANA, "11111111111111111111111111111111",
                        new KeyAlias("local-demo:USER_WALLET_1"),
                        "registry-v1", "key-v1",
                        Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                        WalletIdentityRegistry.Status.ENABLED),
                identity(DESTINATION,
                        "0x2222222222222222222222222222222222222222"));
        assertInvalid(new WalletIdentityRegistry.WalletIdentity(
                        SOURCE, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                        SettlementNetwork.ETHEREUM, "not-an-address",
                        new KeyAlias("local-demo:USER_WALLET_1"),
                        "registry-v1", "key-v1",
                        Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                        WalletIdentityRegistry.Status.ENABLED),
                identity(DESTINATION,
                        "0x2222222222222222222222222222222222222222"));

        WalletTransferAcceptanceService service = service(
                new InMemoryRepository(), Map.of(
                        SOURCE, identity(SOURCE,
                                "0x1111111111111111111111111111111111111111"),
                        DESTINATION, identity(DESTINATION,
                                "0x2222222222222222222222222222222222222222")));
        for (String invalid : List.of("0", "-1", "1.001", "10000000000.01")) {
            assertThrows(InvalidRequestException.class, () -> service.accept(
                    PARTICIPANT, new IdempotencyKey("invalid-" + invalid),
                    request(invalid, SOURCE, DESTINATION)));
        }
    }

    private static void assertInvalid(
            WalletIdentityRegistry.WalletIdentity source,
            WalletIdentityRegistry.WalletIdentity destination) {
        WalletTransferAcceptanceService service = service(
                new InMemoryRepository(), Map.of(
                        SOURCE, source, DESTINATION, destination));
        assertThrows(InvalidRequestException.class, () -> service.accept(
                PARTICIPANT, new IdempotencyKey("invalid-wallet-" + UUID.randomUUID()),
                request("100", SOURCE, DESTINATION)));
    }

    private static WalletTransferAcceptanceService service(
            InMemoryRepository repository,
            Map<WalletReference, WalletIdentityRegistry.WalletIdentity> identities) {
        IdGenerator ids = new IdGenerator() {
            @Override public OperationId nextOperationId() {
                return new OperationId(UUID.randomUUID());
            }
            @Override public AttemptId nextAttemptId() {
                return new AttemptId(UUID.randomUUID());
            }
        };
        TransferIdentityGenerator transferIds = new TransferIdentityGenerator() {
            @Override public TransferId nextTransferId() {
                return new TransferId(UUID.randomUUID());
            }
            @Override public TransferEffect.Id nextEffectId() {
                return new TransferEffect.Id(UUID.randomUUID());
            }
            @Override public TransferTransition.Id nextTransitionId() {
                return new TransferTransition.Id(UUID.randomUUID());
            }
        };
        WalletIdentityRegistry registry = new WalletIdentityRegistry() {
            @Override public WalletIdentity resolve(WalletReference reference) {
                WalletIdentity value = identities.get(reference);
                if (value == null) {
                    throw new IllegalArgumentException("unknown wallet");
                }
                return value;
            }
            @Override public List<WalletIdentity> identities() {
                return List.copyOf(identities.values());
            }
        };
        return new WalletTransferAcceptanceService(
                repository,
                (assetId, unitId, version) -> assetId.equals(USD.assetId())
                        && unitId.equals(USD.unitId()) && version == USD.version()
                        ? Optional.of(USD) : Optional.empty(),
                registry, () -> Instant.parse("2026-07-17T12:00:00Z"), ids, transferIds,
                new WalletTransferAcceptanceService.Policy(
                        "0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                        "local-usdzelle-v1", "local-transfer-finality-v1"));
    }

    private static WalletTransferAcceptanceService.Request request(
            String amount, WalletReference source, WalletReference destination) {
        return new WalletTransferAcceptanceService.Request(
                amount, USD.assetId(), USD.unitId(), USD.version(), source, destination);
    }

    private static WalletIdentityRegistry.WalletIdentity identity(
            WalletReference reference, String address) {
        return new WalletIdentityRegistry.WalletIdentity(
                reference, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.ETHEREUM, address,
                new KeyAlias("local-demo:" + reference.value().substring("synthetic-wallet:".length())),
                "registry-v1", "key-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
    }

    private static WalletReference wallet(String name) {
        return new WalletReference("synthetic-wallet:" + name);
    }

    private static final class InMemoryRepository implements WalletTransferRepository {
        private final Map<String, WalletTransferOperation> byKey = new HashMap<>();

        @Override public Acceptance accept(WalletTransferOperation proposed) {
            String scope = proposed.participant().tenantId() + ":"
                    + proposed.participant().participantId() + ":"
                    + proposed.idempotencyKeyDigest();
            WalletTransferOperation existing = byKey.get(scope);
            if (existing == null) {
                byKey.put(scope, proposed);
                return new Acceptance(proposed, false);
            }
            if (!existing.commandDigest().equals(proposed.commandDigest())) {
                throw new IdempotencyConflictException();
            }
            return new Acceptance(existing, true);
        }

        @Override public Optional<WalletTransferOperation> findById(OperationId operationId) {
            return byKey.values().stream()
                    .filter(value -> value.operationId().equals(operationId)).findFirst();
        }

        @Override public Optional<WalletTransferOperation> findByIdempotency(
                ParticipantScope participant, String idempotencyKeyDigest) {
            return Optional.ofNullable(byKey.get(participant.tenantId() + ":"
                    + participant.participantId() + ":" + idempotencyKeyDigest));
        }

        @Override public StartResult startDelivery(
                UUID deliveryId, OperationId operationId, Instant startedAt) {
            throw new UnsupportedOperationException();
        }

        @Override public void save(WalletTransferOperation operation, long expectedVersion) {
            throw new UnsupportedOperationException();
        }
    }
}
