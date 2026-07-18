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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.IdempotencyConflictException;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
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
        jdbc.sql("DELETE FROM ethereum_wallet_transfer_observation").update();
        jdbc.sql("DELETE FROM ethereum_wallet_transfer_attempt").update();
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
        assertEquals(6, jdbc.sql(
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
        AssetUnit unit = new AssetUnit(
                "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
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
                source, destination, SettlementNetwork.ETHEREUM,
                "0x" + "a".repeat(40), "local-token-v1", "local-finality-v1",
                id(seed + 30, AttemptId::new), WalletTransferOperation.Status.ACCEPTED,
                0, NOW, NOW, List.of(new EvidenceRef(
                        "internal:wallet-transfer:accepted:" + seed)),
                WalletTransferOperation.initialFinalities());
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
