package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.command.BurnCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresWalletTransferRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;

class PostgresWalletTransferRepositoryTest {

    private static final Instant NOW = Instant.parse("2026-07-17T20:00:00.123456Z");
    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresWalletTransferRepository repository;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:17.10-alpine3.23")
                .withDatabaseName("wallet_transfer")
                .withUsername("wallet_transfer_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        dataSource = dataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new PostgresWalletTransferRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (dataSource != null) dataSource.close();
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearData() {
        jdbc.sql("DELETE FROM operation_delivery_attempt").update();
        jdbc.sql("DELETE FROM ethereum_redemption_balance_observation").update();
        jdbc.sql("DELETE FROM ethereum_burn_observation").update();
        jdbc.sql("DELETE FROM ethereum_burn_attempt").update();
        jdbc.sql("DELETE FROM ethereum_redemption_correlation").update();
        jdbc.sql("DELETE FROM ethereum_wallet_transfer_observation").update();
        jdbc.sql("DELETE FROM ethereum_wallet_transfer_attempt").update();
        jdbc.sql("DELETE FROM ethereum_nonce_cursor").update();
        jdbc.sql("DELETE FROM wallet_transfer_handler_inbox").update();
        jdbc.sql("DELETE FROM operation_outbox").update();
        jdbc.sql("DELETE FROM wallet_transfer_finality").update();
        jdbc.sql("DELETE FROM wallet_transfer_transition").update();
        jdbc.sql("DELETE FROM wallet_transfer_operation").update();
    }

    @Test
    void v6AcceptanceReplayClaimInboxAndRestartAreDurable() {
        WalletTransferOperation proposed = operation(1, "a".repeat(64));
        var accepted = repository.accept(proposed);

        assertFalse(accepted.replayed());
        assertEquals(10, jdbc.sql(
                "SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single());
        assertEquals(1L, count("wallet_transfer_operation"));
        assertEquals(1L, count("wallet_transfer_transition"));
        assertEquals(4L, count("wallet_transfer_finality"));
        assertEquals("WalletTransferAccepted", jdbc.sql(
                "SELECT event_type FROM operation_outbox").query(String.class).single());

        assertTrue(repository.accept(operation(2, "a".repeat(64))).replayed());
        assertThrows(IdempotencyConflictException.class,
                () -> repository.accept(operation(3, "b".repeat(64))));

        OperationDelivery delivery = PostgresOperationDeliveryQueue
                .walletTransfersOnly(dataSource)
                .claim("wallet-transfer-worker", NOW, Duration.ofSeconds(30), 1)
                .deliveries().getFirst();
        assertEquals(proposed.operationId().value(), delivery.aggregateId());
        var started = repository.startDelivery(
                delivery.deliveryId(), proposed.operationId(), NOW.plusSeconds(1));
        assertFalse(started.duplicate());
        assertEquals(WalletTransferOperation.Status.SIGNING, started.operation().status());
        assertTrue(repository.startDelivery(
                delivery.deliveryId(), proposed.operationId(), NOW.plusSeconds(2)).duplicate());

        try (HikariDataSource restarted = dataSource()) {
            WalletTransferOperation loaded = new PostgresWalletTransferRepository(restarted)
                    .findById(proposed.operationId()).orElseThrow();
            assertEquals(started.operation(), loaded);
        }
        assertEquals(1L, count("wallet_transfer_handler_inbox"));
        assertEquals(2L, count("wallet_transfer_transition"));
    }

    @Test
    void v7PersistsOneRedemptionCustodyCorrelationAcrossRestart() {
        var operations = new PostgresOperationRepository(dataSource);
        TokenOperationService lifecycle = new TokenOperationService(
                operations, () -> NOW, ids(700),
                (command, participant) -> new EvidenceRef(
                        "internal:test:burn-acceptance:" + command.sha256()));
        var burn = lifecycle.accept(
                new BurnCommand(1, new ParticipantScope("tenant-a", "participant-a"),
                        TokenQuantity.ofAtomic(BigInteger.valueOf(10_000), unit()),
                        "redemption-correlation"),
                new IdempotencyKey("burn-redemption-1")).operation();
        WalletTransferOperation custody = redemptionOperation(701, burn.operationId());

        var accepted = repository.acceptRedemption(custody, burn.operationId());

        assertFalse(accepted.replayed());
        assertEquals(custody, repository.findRedemptionByBurn(
                burn.operationId()).orElseThrow());
        assertEquals(1L, count("ethereum_redemption_correlation"));
        assertEquals(0, jdbc.sql("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = current_schema()
                  AND table_name = 'ethereum_burn_attempt'
                  AND column_name = 'signed_transaction'
                """).query(Integer.class).single());
        try (HikariDataSource restarted = dataSource()) {
            assertEquals(custody,
                    new PostgresWalletTransferRepository(restarted)
                            .findRedemptionByBurn(burn.operationId()).orElseThrow());
        }
    }

    @Test
    void v7ConsumesOnlyExactConfirmedCustodyEvidenceForOneBurnAttempt() {
        var operations = new PostgresOperationRepository(dataSource);
        TokenOperationService lifecycle = new TokenOperationService(
                operations, () -> NOW, ids(800),
                (command, participant) -> new EvidenceRef(
                        "internal:test:burn-acceptance:" + command.sha256()));
        var burn = lifecycle.accept(
                new BurnCommand(1, new ParticipantScope("tenant-a", "participant-a"),
                        TokenQuantity.ofAtomic(BigInteger.valueOf(10_000), unit()),
                        "redemption-consumption"),
                new IdempotencyKey("burn-redemption-2")).operation();
        WalletTransferOperation custody = redemptionOperation(801, burn.operationId());
        repository.acceptRedemption(custody, burn.operationId());

        EthereumWalletTransferAttemptStore transferStore =
                new EthereumWalletTransferAttemptStore(dataSource);
        var transferAttempt = transferStore.prepare(
                delivery(custody), custody, 31_337L, BigInteger.valueOf(4), NOW,
                nonce -> draft(custody));
        transferAttempt = transferStore.attachSignature(
                transferAttempt, "d".repeat(64), "fixture", "0x02",
                "0x" + "e".repeat(64), NOW);
        var claimed = transferStore.claimSubmission(transferAttempt, NOW);
        transferAttempt = transferStore.recordSubmission(
                claimed.attempt(), EthereumWalletTransferAttemptStore.AttemptStatus.ACCEPTED,
                "rpc-accepted", NOW);
        transferStore.recordObservation(
                transferAttempt,
                new EthereumWalletTransferAttemptStore.ObservationDraft(
                        io.github.johnwhitton.digitalbanking.application.port.ChainPort
                                .ObservationClassification.CONFIRMED,
                        Optional.of(BigInteger.TEN), Optional.of("0x" + "1".repeat(64)),
                        Optional.of(BigInteger.ONE), Optional.of(true),
                        Optional.of("2".repeat(64)),
                        Optional.of(custody.source().normalizedAddress()),
                        Optional.of(custody.contractAddress()),
                        Optional.of(transferAttempt.nonce()),
                        Optional.of(transferAttempt.calldataSha256()),
                        Optional.of(custody.source().normalizedAddress()),
                        Optional.of(custody.destination().normalizedAddress()),
                        Optional.of(custody.quantity().atomicUnits()),
                        Optional.of(1), Optional.of("3".repeat(64))),
                "internal:test:custody-confirmed", NOW);
        jdbc.sql("""
                UPDATE wallet_transfer_operation
                SET operation_status = 'COMPLETED', aggregate_version = 7
                WHERE operation_id = :operationId
                """).param("operationId", custody.operationId().value()).update();
        EthereumRedemptionBalanceStore balanceStore =
                new EthereumRedemptionBalanceStore(dataSource);
        EthereumRedemptionBalanceStore.Context context = balanceStore
                .findByCustody(custody.operationId()).orElseThrow();
        balanceStore.record(EthereumRedemptionBalanceStore.Stage.BEFORE_CUSTODY,
                context, new EthereumRedemptionBalanceStore.Snapshot(
                        BigInteger.valueOf(9), "0x" + "4".repeat(64),
                        BigInteger.valueOf(20_000), BigInteger.ZERO,
                        BigInteger.valueOf(20_000), NOW));
        assertThrows(IllegalStateException.class, () -> balanceStore.record(
                EthereumRedemptionBalanceStore.Stage.BEFORE_CUSTODY,
                context, new EthereumRedemptionBalanceStore.Snapshot(
                        BigInteger.valueOf(9), "0x" + "4".repeat(64),
                        BigInteger.valueOf(19_999), BigInteger.ZERO,
                        BigInteger.valueOf(20_000), NOW)));
        balanceStore.record(EthereumRedemptionBalanceStore.Stage.AFTER_CUSTODY,
                context, new EthereumRedemptionBalanceStore.Snapshot(
                        BigInteger.TEN, "0x" + "1".repeat(64),
                        BigInteger.valueOf(10_000), BigInteger.valueOf(10_000),
                        BigInteger.valueOf(20_000), NOW));

        EthereumBurnAttemptStore burnStore = new EthereumBurnAttemptStore(dataSource);
        AttemptId burnAttempt = id(850, AttemptId::new);
        UUID burnDelivery = jdbc.sql("""
                SELECT event_id FROM operation_outbox
                WHERE operation_id = :operationId
                """).param("operationId", burn.operationId().value())
                .query(UUID.class).single();
        assertThrows(IllegalStateException.class, () -> burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_337L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(5), NOW,
                preparation -> burnDraft(custody, preparation.nonce())));
        jdbc.sql("""
                INSERT INTO wallet_transfer_finality (
                    operation_id, finality_type, history_order, finality_status,
                    authority, policy_version, updated_at, evidence_ref)
                VALUES (
                    :operationId, 'BLOCKCHAIN', 1, 'REACHED',
                    'independent-ethereum-observer', :policyVersion, :updatedAt,
                    'internal:test:custody-confirmed')
                """)
                .param("operationId", custody.operationId().value())
                .param("policyVersion", custody.finalityPolicyVersion())
                .param("updatedAt", NOW.atOffset(java.time.ZoneOffset.UTC))
                .update();
        assertThrows(IllegalStateException.class, () -> burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_337L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(5), NOW,
                preparation -> new EthereumBurnAttemptStore.AttemptDraft(
                        "0x" + "d".repeat(40),
                        custody.destination().keyReference().value(),
                        custody.destination().registryVersion(),
                        custody.destination().keyVersion(),
                        "local-ethereum-redemption-v1", 1, BigInteger.ONE,
                        BigInteger.TWO, BigInteger.valueOf(120_000),
                        new EthereumTransactionCodec().burnCalldata(
                                custody.quantity().atomicUnits()),
                        "a".repeat(64), "0x02", "b".repeat(64))));
        assertThrows(IllegalStateException.class, () -> burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_337L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(5), NOW,
                preparation -> new EthereumBurnAttemptStore.AttemptDraft(
                        custody.contractAddress(),
                        custody.destination().keyReference().value(),
                        "changed-registry-version", custody.destination().keyVersion(),
                        "local-ethereum-redemption-v1", 1, BigInteger.ONE,
                        BigInteger.TWO, BigInteger.valueOf(120_000),
                        new EthereumTransactionCodec().burnCalldata(
                                custody.quantity().atomicUnits()),
                        "a".repeat(64), "0x02", "b".repeat(64))));
        var retained = burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_337L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(5), NOW,
                preparation -> burnDraft(custody, preparation.nonce()));

        assertEquals(burnAttempt, retained.attemptId());
        assertEquals("CONSUMED", jdbc.sql("""
                SELECT correlation_status FROM ethereum_redemption_correlation
                WHERE burn_operation_id = :operationId
                """).param("operationId", burn.operationId().value())
                .query(String.class).single());
        assertEquals(retained, burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_337L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(5), NOW,
                preparation -> burnDraft(custody, preparation.nonce())));
        assertThrows(IllegalStateException.class, () -> burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_338L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(5), NOW,
                preparation -> burnDraft(custody, preparation.nonce())));
        assertThrows(IllegalStateException.class, () -> burnStore.prepare(
                burnDelivery, burn, burnAttempt, 31_337L,
                "0x" + "f".repeat(40), BigInteger.valueOf(5), NOW,
                preparation -> burnDraft(custody, preparation.nonce())));
        assertThrows(IllegalStateException.class, () -> burnStore.prepare(
                burnDelivery, burn, id(851, AttemptId::new), 31_337L,
                custody.destination().normalizedAddress(), BigInteger.valueOf(6), NOW,
                preparation -> burnDraft(custody, preparation.nonce())));
    }

    @Test
    void nonceAllocationIsSerializedPerSourceAndIndependentAcrossSources()
            throws Exception {
        WalletTransferOperation first = operation(
                101, "d".repeat(64), "1".repeat(40), "2".repeat(40));
        WalletTransferOperation second = operation(
                102, "e".repeat(64), "1".repeat(40), "3".repeat(40));
        WalletTransferOperation otherSource = operation(
                103, "f".repeat(64), "4".repeat(40), "5".repeat(40));
        repository.accept(first);
        repository.accept(second);
        repository.accept(otherSource);
        EthereumWalletTransferAttemptStore store =
                new EthereumWalletTransferAttemptStore(dataSource);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.prepare(delivery(first), first, 31_337L,
                        BigInteger.valueOf(7), NOW, nonce -> draft(first));
            });
            var secondResult = executor.submit(() -> {
                ready.countDown();
                start.await();
                return store.prepare(delivery(second), second, 31_337L,
                        BigInteger.valueOf(7), NOW, nonce -> draft(second));
            });
            ready.await();
            start.countDown();
            assertEquals(Set.of(BigInteger.valueOf(7), BigInteger.valueOf(8)),
                    Set.of(firstResult.get().nonce(), secondResult.get().nonce()));
        }
        assertEquals(BigInteger.valueOf(4), store.prepare(
                delivery(otherSource), otherSource, 31_337L, BigInteger.valueOf(4),
                NOW, nonce -> draft(otherSource)).nonce());
    }

    private static WalletTransferOperation operation(long seed, String commandDigest) {
        return operation(seed, commandDigest, "1".repeat(40), "2".repeat(40),
                "c".repeat(64));
    }

    private static WalletTransferOperation operation(
            long seed, String commandDigest, String sourceAddress,
            String destinationAddress) {
        return operation(seed, commandDigest, sourceAddress, destinationAddress,
                String.format("%064x", seed));
    }

    private static WalletTransferOperation operation(
            long seed, String commandDigest, String sourceAddress,
            String destinationAddress, String keyDigest) {
        AssetUnit unit = unit();
        WalletTransferOperation.WalletSnapshot source = snapshot(
                "USER_WALLET_" + seed + "_SOURCE", sourceAddress);
        WalletTransferOperation.WalletSnapshot destination = snapshot(
                "USER_WALLET_" + seed + "_DESTINATION", destinationAddress);
        return new WalletTransferOperation(
                id(seed, OperationId::new), id(seed + 10, TransferId::new),
                id(seed + 20, TransferEffect.Id::new),
                new ParticipantScope("tenant-a", "participant-a"),
                keyDigest, 1, commandDigest,
                TokenQuantity.ofAtomic(BigInteger.valueOf(10_000), unit),
                WalletTransferOperation.Purpose.USER_TRANSFER,
                source, destination, SettlementNetwork.ETHEREUM,
                "0x" + "a".repeat(40), "local-token-v1", "local-finality-v1",
                id(seed + 30, AttemptId::new), WalletTransferOperation.Status.ACCEPTED,
                0, NOW, NOW, List.of(new EvidenceRef(
                        "internal:wallet-transfer:accepted:" + seed)),
                WalletTransferOperation.initialFinalities());
    }

    private static WalletTransferOperation redemptionOperation(
            long seed, OperationId burnOperationId) {
        WalletTransferOperation.WalletSnapshot source = snapshot(
                "USER_WALLET_2", "1".repeat(40));
        WalletTransferOperation.WalletSnapshot admin = new WalletTransferOperation.WalletSnapshot(
                new WalletReference("synthetic-wallet:ADMIN_REDEMPTION"),
                WalletIdentityRegistry.OwnerCategory.ADMIN,
                SettlementNetwork.ETHEREUM, "0x" + "a".repeat(40),
                new KeyAlias("local-demo:ADMIN"), "registry-v1", "admin-key-v1",
                Set.of(WalletIdentityRegistry.Purpose.MINT_AUTHORITY,
                        WalletIdentityRegistry.Purpose.BURN_AUTHORITY,
                        WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY),
                WalletIdentityRegistry.Status.ENABLED);
        return new WalletTransferOperation(
                id(seed, OperationId::new), id(seed + 10, TransferId::new),
                id(seed + 20, TransferEffect.Id::new),
                new ParticipantScope("tenant-a", "participant-a"),
                String.format("%064x", seed), 1, "b".repeat(64),
                TokenQuantity.ofAtomic(BigInteger.valueOf(10_000), unit()),
                WalletTransferOperation.Purpose.REDEMPTION_CUSTODY,
                source, admin, SettlementNetwork.ETHEREUM,
                "0x" + "c".repeat(40), "local-token-v1", "local-finality-v1",
                id(seed + 30, AttemptId::new), WalletTransferOperation.Status.ACCEPTED,
                0, NOW, NOW, List.of(new EvidenceRef(
                        "internal:redemption-custody:accepted:" + burnOperationId)),
                WalletTransferOperation.initialFinalities());
    }

    private static AssetUnit unit() {
        return new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    }

    private static io.github.johnwhitton.digitalbanking.application.port.IdGenerator ids(
            long seed) {
        return new io.github.johnwhitton.digitalbanking.application.port.IdGenerator() {
            private long value = seed;
            @Override public OperationId nextOperationId() {
                return id(value++, OperationId::new);
            }
            @Override public AttemptId nextAttemptId() {
                return id(value++, AttemptId::new);
            }
        };
    }

    private static UUID delivery(WalletTransferOperation operation) {
        return jdbc.sql("""
                SELECT event_id FROM operation_outbox
                WHERE wallet_transfer_id = :operationId
                """).param("operationId", operation.operationId().value())
                .query(UUID.class).single();
    }

    private static EthereumWalletTransferAttemptStore.AttemptDraft draft(
            WalletTransferOperation operation) {
        String calldata = new EthereumTransactionCodec().transferCalldata(
                operation.destination().normalizedAddress(),
                operation.quantity().atomicUnits()).toLowerCase(java.util.Locale.ROOT);
        return new EthereumWalletTransferAttemptStore.AttemptDraft(
                1, BigInteger.ONE, BigInteger.TWO, BigInteger.valueOf(120_000),
                calldata, "a".repeat(64), "0x02", "b".repeat(64));
    }

    private static EthereumBurnAttemptStore.AttemptDraft burnDraft(
            WalletTransferOperation custody, BigInteger nonce) {
        String calldata = new EthereumTransactionCodec().burnCalldata(
                custody.quantity().atomicUnits()).toLowerCase(java.util.Locale.ROOT);
        return new EthereumBurnAttemptStore.AttemptDraft(
                custody.contractAddress(), custody.destination().keyReference().value(),
                custody.destination().registryVersion(),
                custody.destination().keyVersion(),
                "local-ethereum-redemption-v1", 1, BigInteger.ONE,
                BigInteger.TWO, BigInteger.valueOf(120_000), calldata,
                "a".repeat(64), "0x02", "b".repeat(64));
    }

    private static WalletTransferOperation.WalletSnapshot snapshot(
            String reference, String address) {
        return new WalletTransferOperation.WalletSnapshot(
                new WalletReference("synthetic-wallet:" + reference),
                WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.ETHEREUM, "0x" + address,
                new KeyAlias("local-demo:" + reference), "registry-v1", "key-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
    }

    private static <T> T id(long seed, java.util.function.Function<UUID, T> factory) {
        return factory.apply(new UUID(0, seed));
    }

    private static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static HikariDataSource dataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        return new HikariDataSource(config);
    }
}
