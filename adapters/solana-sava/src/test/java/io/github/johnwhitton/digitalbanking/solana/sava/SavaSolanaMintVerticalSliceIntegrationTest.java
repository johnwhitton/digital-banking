package io.github.johnwhitton.digitalbanking.solana.sava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.EdECPrivateKey;
import java.security.spec.NamedParameterSpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.command.BurnCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.MintCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.RedemptionAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.WalletTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferChainPort;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.RetryAuthorization;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferTransition;
import io.github.johnwhitton.digitalbanking.domain.transfer.WalletReference;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresWalletTransferRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalSolanaConfiguredSigner;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.AccountState;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.core.tx.Transaction;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;

class SavaSolanaMintVerticalSliceIntegrationTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant NOW = Instant.parse("2026-07-19T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-solana", "participant-solana");
    private static final PublicKey DESTINATION_OWNER = PublicKey.fromBase58Encoded(
            "5FN9G4Lm7ffMX3Uun11thakD29iuQgxBJHmFCiwYVWVG");
    private static final PublicKey TRANSFER_DESTINATION_OWNER = PublicKey.fromBase58Encoded(
            "86Cud6zB3MZRYcCBgYftqoZRZw1jVqQfDkobchgk9vir");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    @TempDir
    Path temporary;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("digital_banking")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(postgres.getJdbcUrl());
        pool.setUsername(postgres.getUsername());
        pool.setPassword(postgres.getPassword());
        pool.setMaximumPoolSize(8);
        dataSource = new HikariDataSource(pool);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (dataSource != null) dataSource.close();
        if (postgres != null) postgres.stop();
    }

    @Test
    void mintsExactQuantityThroughTwoSignersThenReplaysWithoutAnotherEffect()
            throws Exception {
        try (Scenario scenario = scenario(false)) {
            OperationAcceptance first = scenario.accept("solana-mint-e2e", "100");
            OperationAcceptance replay = scenario.accept("solana-mint-e2e", "100");
            assertEquals(first.operation().operationId(), replay.operation().operationId());
            assertTrue(replay.replayed());

            OperationDelivery delivery = scenario.delivery(first.operation().operationId());
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    scenario.handler().handle(delivery).classification());
            assertEquals(BigInteger.valueOf(10_000), scenario.rpc().supply());
            assertEquals(BigInteger.valueOf(10_000), scenario.rpc().balance());
            assertEquals(1, scenario.rpc().submissionCalls());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    scenario.handler().handle(delivery).classification());
            assertEquals(1, scenario.rpc().submissionCalls());

            var completed = scenario.operations().findById(
                    first.operation().operationId()).orElseThrow();
            assertEquals(OperationState.COMPLETED, completed.state());
            assertEquals(FinalityStatus.REACHED,
                    completed.finalities().get(FinalityType.BLOCKCHAIN).status());
            assertEquals(FinalityStatus.NOT_ASSESSED,
                    completed.finalities().get(FinalityType.ACCOUNTING).status());
            SavaSolanaMintChainAdapter.Configuration rotated = new SavaSolanaMintChainAdapter.Configuration(
                    scenario.configuration().rpcUri(), scenario.configuration().clusterIdentity(),
                    scenario.configuration().mintAddress(),
                    scenario.configuration().destinationOwner(),
                    scenario.configuration().feePayerPublicKey(),
                    scenario.configuration().feePayerKeyAlias(),
                    scenario.configuration().feePayerKeyVersion(),
                    scenario.configuration().mintAuthorityPublicKey(),
                    scenario.configuration().mintAuthorityKeyAlias(),
                    scenario.configuration().mintAuthorityKeyVersion(),
                    scenario.configuration().assetId(), scenario.configuration().unitId(),
                    scenario.configuration().unitVersion(), scenario.configuration().decimals(),
                    "local-solana-mint-v2",
                    scenario.configuration().preparationCommitment(),
                    scenario.configuration().observationCommitment(),
                    scenario.configuration().minimumFeePayerLamports(),
                    BigInteger.valueOf(50_000), scenario.configuration().requestTimeout());
            var retained = new SavaSolanaMintChainAdapter(
                    dataSource, client(scenario.rpc().endpoint()),
                    client(scenario.rpc().endpoint()), rotated, CLOCK).prepare(
                            delivery.deliveryId(), completed, completed.attempts().getLast());
            assertEquals("local-solana-mint-v1", retained.policyVersion());
            assertEquals("max-lamports=100000", retained.feeLimit());
            assertEquals(2, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_signature
                    WHERE operation_id = :operationId
                    """).param("operationId", first.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId
                      AND amount_atomic = 10000
                      AND decimals = 2
                      AND attempt_status = 'CONFIRMED'
                      AND transaction_signature IS NOT NULL
                    """).param("operationId", first.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_observation
                    WHERE operation_id = :operationId
                      AND observation_status = 'CONFIRMED'
                      AND commitment = 'finalized'
                      AND expected_instructions
                      AND observed_mint_supply = 10000
                      AND observed_destination_balance = 10000
                      AND mint_delta = 10000
                      AND destination_delta = 10000
                    """).param("operationId", first.operation().operationId().value())
                    .query(Integer.class).single());
        }
    }

    @Test
    void transfersExactQuantityThroughTwoSignersAndRecoversLostResponse()
            throws Exception {
        TestKey fee = key(temporary.resolve("transfer-fee.json"),
                "local-solana:transfer-fee");
        TestKey mintAuthority = key(temporary.resolve("transfer-mint.json"),
                "local-solana:transfer-mint");
        TestKey source = key(temporary.resolve("transfer-source.json"),
                "local-solana:transfer-source");
        TestKey burnAuthority = key(temporary.resolve("transfer-burn.json"),
                "local-solana:transfer-burn");
        Files.setPosixFilePermissions(temporary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        try (LocalSolanaConfiguredSigner signer = new LocalSolanaConfiguredSigner(
                    new LocalSolanaConfiguredSigner.Configuration(temporary, List.of(
                            configured(fee, SigningRequest.KeyRole.FEE_PAYER, "fee-v1"),
                            configured(mintAuthority,
                                    SigningRequest.KeyRole.MINT_AUTHORITY, "mint-v1"),
                            configured(source,
                                    SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                    "source-v1"),
                            configured(burnAuthority,
                                    SigningRequest.KeyRole.BURN_AUTHORITY,
                                    "burn-v1"))));
                RpcFixture rpc = new RpcFixture(
                        fee.publicKey(), mintAuthority.publicKey(), source.publicKey(),
                        TRANSFER_DESTINATION_OWNER, BigInteger.valueOf(10_000),
                        BigInteger.valueOf(10_000))) {
            SavaSolanaMintChainAdapter.Configuration configuration =
                    new SavaSolanaMintChainAdapter.Configuration(
                            rpc.endpoint(), rpc.genesis(), rpc.mint().toBase58(),
                            source.address(), fee.address(), fee.alias().value(), "fee-v1",
                            mintAuthority.address(), mintAuthority.alias().value(), "mint-v1",
                            TRANSFER_DESTINATION_OWNER.toBase58(), source.alias().value(),
                            "source-v1", burnAuthority.address(),
                            burnAuthority.alias().value(), "burn-v1",
                            "registry-v1",
                            UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                            UNIT.scale(), "local-solana-transfer-v1",
                            SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                            SavaSolanaMintChainAdapter.CommitmentLevel.FINALIZED,
                            BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000),
                            Duration.ofSeconds(2));
            SolanaRpcClient submission = client(rpc.endpoint());
            AtomicBoolean lose = new AtomicBoolean(true);
            SavaSolanaMintChainAdapter chain = new SavaSolanaMintChainAdapter(
                    dataSource, submission, client(rpc.endpoint()), base64 -> {
                        String signature = submission.sendTransaction(base64, 0).join();
                        if (lose.compareAndSet(true, false)) {
                            throw new IllegalStateException(
                                    "synthetic response loss after transfer acceptance");
                        }
                        return signature;
                    }, configuration, CLOCK);
            PostgresWalletTransferRepository transfers =
                    new PostgresWalletTransferRepository(dataSource);
            WalletReference sourceRef = new WalletReference("synthetic-wallet:USER_1");
            WalletReference destinationRef =
                    new WalletReference("synthetic-wallet:USER_2");
            WalletIdentityRegistry registry = walletRegistry(
                    sourceRef, destinationRef, source, configuration);
            WalletTransferAcceptanceService acceptance =
                    new WalletTransferAcceptanceService(
                            transfers, (asset, unit, version) ->
                                    asset.equals(UNIT.assetId())
                                            && unit.equals(UNIT.unitId())
                                            && version == UNIT.version()
                                            ? Optional.of(UNIT) : Optional.empty(),
                            registry, CLOCK::instant, ids(), transferIds(),
                            new WalletTransferAcceptanceService.Policy(
                                    configuration.mintAddress(), "local-solana-token-v1",
                                    configuration.policyVersion()));
            var accepted = acceptance.accept(
                    PARTICIPANT, new IdempotencyKey("solana-transfer-e2e"),
                    new WalletTransferAcceptanceService.Request(
                            "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                            sourceRef, destinationRef));
            OperationAcceptance burn = new TokenOperationService(
                    new PostgresOperationRepository(dataSource), CLOCK::instant, ids(),
                    (canonical, participant) -> new EvidenceRef(
                            "participant:test:solana-routing-burn"))
                    .accept(new BurnCommand(
                                    1, PARTICIPANT, TokenQuantity.parse("100", UNIT),
                                    "solana-routing-burn"),
                            IdempotencyKey.of("solana-routing-burn"));
            WalletReference ethereumSource =
                    new WalletReference("synthetic-wallet:ETH_USER_1");
            WalletReference ethereumDestination =
                    new WalletReference("synthetic-wallet:ETH_USER_2");
            WalletIdentityRegistry ethereumRegistry = walletRegistry(
                    new WalletIdentityRegistry.WalletIdentity(
                            ethereumSource, Set.of(),
                            WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                            SettlementNetwork.ETHEREUM,
                            "0x1111111111111111111111111111111111111111",
                            new KeyAlias("local-ethereum:test-source"),
                            "registry-v1", "source-v1",
                            Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                            WalletIdentityRegistry.Status.ENABLED),
                    new WalletIdentityRegistry.WalletIdentity(
                            ethereumDestination, Set.of(),
                            WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                            SettlementNetwork.ETHEREUM,
                            "0x2222222222222222222222222222222222222222",
                            new KeyAlias("local-ethereum:test-destination"),
                            "registry-v1", "destination-v1",
                            Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                            WalletIdentityRegistry.Status.ENABLED));
            var ethereumTransfer = new WalletTransferAcceptanceService(
                    transfers, (asset, unit, version) ->
                            asset.equals(UNIT.assetId()) && unit.equals(UNIT.unitId())
                                    && version == UNIT.version()
                                    ? Optional.of(UNIT) : Optional.empty(),
                    ethereumRegistry, CLOCK::instant, ids(), transferIds(),
                    new WalletTransferAcceptanceService.Policy(
                            "0x3333333333333333333333333333333333333333",
                            "local-ethereum-token-v1", "local-ethereum-transfer-v1"))
                    .accept(PARTICIPANT,
                            new IdempotencyKey("ethereum-transfer-routing-control"),
                            new WalletTransferAcceptanceService.Request(
                                    "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                                    ethereumSource, ethereumDestination));
            WalletTransferAcceptedDeliveryHandler preparationHandler =
                    new WalletTransferAcceptedDeliveryHandler(
                            transfers, chain, signingService(signer), registry,
                            CLOCK::instant, Duration.ofMinutes(5));
            OperationDelivery delivery = walletDelivery(
                    accepted.operation().operationId());
            assertEquals("IN_PROGRESS", jdbc.sql("""
                    SELECT status FROM operation_outbox WHERE operation_id = :operationId
                    """).param("operationId", burn.operation().operationId().value())
                    .query(String.class).single());
            assertEquals("PENDING", jdbc.sql("""
                    SELECT status FROM operation_outbox
                    WHERE wallet_transfer_id = :operationId
                    """).param("operationId",
                            ethereumTransfer.operation().operationId().value())
                    .query(String.class).single());

            for (RpcFixture.AccountFault fault : RpcFixture.AccountFault.values()) {
                if (fault == RpcFixture.AccountFault.NONE) continue;
                rpc.accountFault(fault);
                assertThrows(IllegalStateException.class,
                        () -> preparationHandler.handle(delivery));
                assertEquals(0, jdbc.sql("""
                        SELECT count(*) FROM solana_mint_attempt
                        WHERE operation_id = :operationId
                        """).param("operationId", accepted.operation().operationId().value())
                        .query(Integer.class).single());
            }
            rpc.accountFault(RpcFixture.AccountFault.NONE);
            rpc.balance(BigInteger.valueOf(9_999));
            assertThrows(IllegalStateException.class,
                    () -> preparationHandler.handle(delivery));
            rpc.balance(BigInteger.valueOf(10_000));

            WalletTransferAcceptedDeliveryHandler crashingHandler =
                    new WalletTransferAcceptedDeliveryHandler(
                            transfers, new FailFirstSignatureAttachment(chain),
                            signingService(signer), registry, CLOCK::instant,
                            Duration.ofMinutes(5));
            assertThrows(IllegalStateException.class,
                    () -> crashingHandler.handle(delivery));
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM signing_request
                    WHERE operation_id = :operationId AND signing_status = 'SIGNED'
                    """).param("operationId", accepted.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(0, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_signature
                    WHERE operation_id = :operationId
                    """).param("operationId", accepted.operation().operationId().value())
                    .query(Integer.class).single());
            Clock restartedClock = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
            WalletTransferAcceptedDeliveryHandler handler =
                    new WalletTransferAcceptedDeliveryHandler(
                    transfers, chain, signingService(signer, restartedClock), registry,
                    restartedClock::instant, Duration.ofMinutes(5));
            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    handler.handle(delivery).classification());
            SavaSolanaMintChainAdapter restarted = new SavaSolanaMintChainAdapter(
                    dataSource, client(rpc.endpoint()), client(rpc.endpoint()),
                    configuration, CLOCK);
            WalletTransferAcceptedDeliveryHandler restartedHandler =
                    new WalletTransferAcceptedDeliveryHandler(
                            new PostgresWalletTransferRepository(dataSource), restarted,
                            signingService(signer, restartedClock), registry,
                            restartedClock::instant,
                            Duration.ofMinutes(5));
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    restartedHandler.handle(delivery).classification());
            assertEquals(BigInteger.ZERO, rpc.balance());
            assertEquals(BigInteger.valueOf(10_000), rpc.destinationBalance());
            assertEquals(BigInteger.valueOf(10_000), rpc.supply());
            assertEquals(1, rpc.submissionCalls());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    restartedHandler.handle(delivery).classification());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId
                      AND effect_kind = 'TRANSFER'
                      AND attempt_status = 'CONFIRMED'
                      AND pre_source_balance = 10000
                    """).param("operationId", accepted.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(List.of("FEE_PAYER", "TRANSFER_AUTHORITY"), jdbc.sql("""
                    SELECT key_role FROM solana_mint_signature
                    WHERE operation_id = :operationId ORDER BY signer_order
                    """).param("operationId", accepted.operation().operationId().value())
                    .query(String.class).list());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_observation
                    WHERE operation_id = :operationId
                      AND observation_status = 'CONFIRMED'
                      AND observed_mint_supply = 10000
                      AND observed_source_balance = 0
                      AND observed_destination_balance = 10000
                      AND transaction_pre_source_balance = 10000
                      AND transaction_post_source_balance = 0
                      AND transaction_pre_destination_balance = 0
                      AND transaction_post_destination_balance = 10000
                      AND mint_delta = 0
                      AND source_delta = 10000
                      AND destination_delta = 10000
                    """).param("operationId", accepted.operation().operationId().value())
                    .query(Integer.class).single());

            rpc.balance(BigInteger.valueOf(10_000));
            rpc.destinationBalance(BigInteger.ZERO);
            rpc.transactionBalanceMismatch(true);
            var mismatchedBalances = acceptance.accept(
                    PARTICIPANT, new IdempotencyKey("solana-transfer-tx-balance-mismatch"),
                    new WalletTransferAcceptanceService.Request(
                            "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                            sourceRef, destinationRef));
            DeliveryOutcome mismatched = restartedHandler.handle(walletDelivery(
                    mismatchedBalances.operation().operationId()));
            assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                    mismatched.classification());
            assertEquals(FinalityStatus.NOT_ASSESSED, transfers.findById(
                    mismatchedBalances.operation().operationId()).orElseThrow()
                    .finalityHistories().get(FinalityType.BLOCKCHAIN)
                    .getLast().status());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt a
                    JOIN solana_mint_observation o
                      USING (operation_id, operation_attempt_id, effect_kind)
                    WHERE a.operation_id = :operationId
                      AND a.attempt_status = 'MISMATCHED'
                      AND o.transaction_pre_source_balance = 9999
                    """).param("operationId",
                            mismatchedBalances.operation().operationId().value())
                    .query(Integer.class).single());
            jdbc.sql("""
                    UPDATE solana_mint_attempt SET attempt_status = 'REJECTED'
                    WHERE operation_id = :operationId
                    """).param("operationId",
                            mismatchedBalances.operation().operationId().value()).update();
            rpc.transactionBalanceMismatch(false);

            rpc.balance(BigInteger.valueOf(10_000));
            rpc.destinationBalance(BigInteger.ZERO);
            rpc.finalizedFailure(true);
            var finalizedFailure = acceptance.accept(
                    PARTICIPANT, new IdempotencyKey("solana-transfer-finalized-failure"),
                    new WalletTransferAcceptanceService.Request(
                            "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                            sourceRef, destinationRef));
            DeliveryOutcome failed = restartedHandler.handle(walletDelivery(
                    finalizedFailure.operation().operationId()));
            assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                    failed.classification());
            assertEquals(FinalityStatus.NOT_ASSESSED, transfers.findById(
                    finalizedFailure.operation().operationId()).orElseThrow()
                    .finalityHistories().get(FinalityType.BLOCKCHAIN)
                    .getLast().status());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId
                      AND effect_kind = 'TRANSFER'
                      AND attempt_status = 'REVERTED'
                    """).param("operationId",
                            finalizedFailure.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(BigInteger.valueOf(10_000), rpc.balance());
            assertEquals(BigInteger.ZERO, rpc.destinationBalance());

            rpc.finalizedFailure(false);
            rpc.expired(true);
            var expired = acceptance.accept(
                    PARTICIPANT, new IdempotencyKey("solana-transfer-expired"),
                    new WalletTransferAcceptanceService.Request(
                            "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                            sourceRef, destinationRef));
            DeliveryOutcome expiredOutcome = restartedHandler.handle(walletDelivery(
                    expired.operation().operationId()));
            assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                    expiredOutcome.classification());
            assertEquals(FinalityStatus.NOT_ASSESSED, transfers.findById(
                    expired.operation().operationId()).orElseThrow()
                    .finalityHistories().get(FinalityType.BLOCKCHAIN)
                    .getLast().status());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId
                      AND effect_kind = 'TRANSFER'
                      AND attempt_status = 'EXPIRED'
                    """).param("operationId", expired.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(3, rpc.submissionCalls());
        }
    }

    @Test
    void burnsOnlyExactFinalizedCustodyOnceAcrossRestartAndConfigurationChange()
            throws Exception {
        TestKey fee = key(temporary.resolve("burn-fee.json"),
                "local-solana:burn-fee");
        TestKey mintAuthority = key(temporary.resolve("burn-mint.json"),
                "local-solana:burn-mint");
        TestKey source = key(temporary.resolve("burn-source.json"),
                "local-solana:burn-source");
        TestKey burnAuthority = key(temporary.resolve("burn-admin.json"),
                "local-solana:burn-admin");
        Files.setPosixFilePermissions(temporary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        try (LocalSolanaConfiguredSigner signer = new LocalSolanaConfiguredSigner(
                    new LocalSolanaConfiguredSigner.Configuration(temporary, List.of(
                            configured(fee, SigningRequest.KeyRole.FEE_PAYER, "fee-v1"),
                            configured(mintAuthority,
                                    SigningRequest.KeyRole.MINT_AUTHORITY, "mint-v1"),
                            configured(source,
                                    SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                    "source-v1"),
                            configured(burnAuthority,
                                    SigningRequest.KeyRole.BURN_AUTHORITY,
                                    "burn-v1"))));
                RpcFixture rpc = new RpcFixture(
                        fee.publicKey(), mintAuthority.publicKey(), source.publicKey(),
                        burnAuthority.publicKey(), BigInteger.valueOf(10_000),
                        BigInteger.valueOf(10_000))) {
            SavaSolanaMintChainAdapter.Configuration configuration =
                    new SavaSolanaMintChainAdapter.Configuration(
                            rpc.endpoint(), rpc.genesis(), rpc.mint().toBase58(),
                            source.address(), fee.address(), fee.alias().value(), "fee-v1",
                            mintAuthority.address(), mintAuthority.alias().value(), "mint-v1",
                            TRANSFER_DESTINATION_OWNER.toBase58(), source.alias().value(),
                            "source-v1", burnAuthority.address(),
                            burnAuthority.alias().value(), "burn-v1",
                            "registry-v1",
                            UNIT.assetId(), UNIT.unitId(), UNIT.version(), UNIT.scale(),
                            "local-solana-redemption-v1",
                            SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                            SavaSolanaMintChainAdapter.CommitmentLevel.FINALIZED,
                            BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000),
                            Duration.ofSeconds(2));
            PostgresOperationRepository operations =
                    new PostgresOperationRepository(dataSource);
            PostgresWalletTransferRepository transfers =
                    new PostgresWalletTransferRepository(dataSource);
            WalletReference sourceRef = new WalletReference("synthetic-wallet:USER_1");
            WalletReference adminRef =
                    new WalletReference("synthetic-wallet:ADMIN_REDEMPTION");
            WalletIdentityRegistry wallets = walletRegistry(
                    new WalletIdentityRegistry.WalletIdentity(
                            sourceRef, Set.of(),
                            WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                            SettlementNetwork.SOLANA, source.address(), source.alias(),
                            "registry-v1", "source-v1",
                            Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                            WalletIdentityRegistry.Status.ENABLED),
                    new WalletIdentityRegistry.WalletIdentity(
                            adminRef, Set.of(new WalletReference("synthetic-wallet:ADMIN")),
                            WalletIdentityRegistry.OwnerCategory.ADMIN,
                            SettlementNetwork.SOLANA, burnAuthority.address(),
                            burnAuthority.alias(), "registry-v1", "burn-v1",
                            Set.of(WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY,
                                    WalletIdentityRegistry.Purpose.BURN_AUTHORITY),
                            WalletIdentityRegistry.Status.ENABLED));
            WalletTransferAcceptanceService acceptance =
                    new WalletTransferAcceptanceService(
                            transfers, (asset, unit, version) ->
                                    asset.equals(UNIT.assetId())
                                            && unit.equals(UNIT.unitId())
                                            && version == UNIT.version()
                                            ? Optional.of(UNIT) : Optional.empty(),
                            wallets, CLOCK::instant, ids(), transferIds(),
                            new WalletTransferAcceptanceService.Policy(
                                    configuration.mintAddress(), "local-solana-token-v1",
                                    configuration.policyVersion()));
            OperationAcceptance burn = new TokenOperationService(
                    operations, CLOCK::instant, ids(),
                    (canonical, participant) -> new EvidenceRef(
                            "participant:test:solana-burn:" + canonical.sha256()))
                    .accept(new BurnCommand(
                                    1, PARTICIPANT, TokenQuantity.parse("100", UNIT),
                                    "solana-burn-recovery"),
                            IdempotencyKey.of("solana-burn-recovery"));
            OperationDelivery burnDelivery = tokenDelivery(
                    burn.operation().operationId());
            SavaSolanaMintChainAdapter chain = new SavaSolanaMintChainAdapter(
                    dataSource, client(rpc.endpoint()), client(rpc.endpoint()),
                    configuration, CLOCK);
            RedemptionAcceptedDeliveryHandler initialHandler = redemption(
                    operations, transfers, acceptance, chain, signer, wallets,
                    sourceRef, adminRef, configuration);

            assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    initialHandler.handle(burnDelivery).classification());
            OperationId custodyId = new OperationId(jdbc.sql("""
                    SELECT custody_operation_id FROM ethereum_redemption_correlation
                    WHERE burn_operation_id = :burnOperationId
                    """).param("burnOperationId", burn.operation().operationId().value())
                    .query(UUID.class).single());
            assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    initialHandler.handle(burnDelivery).classification());
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    initialHandler.handle(walletDelivery(custodyId)).classification());

            jdbc.sql("""
                    UPDATE solana_mint_observation SET expected_instructions = false
                    WHERE operation_id = :operationId AND effect_kind = 'TRANSFER'
                    """).param("operationId", custodyId.value()).update();
            assertThrows(IllegalStateException.class,
                    () -> initialHandler.handle(burnDelivery));
            assertEquals(0, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId AND effect_kind = 'BURN'
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());
            jdbc.sql("""
                    UPDATE solana_mint_observation SET expected_instructions = true
                    WHERE operation_id = :operationId AND effect_kind = 'TRANSFER'
                    """).param("operationId", custodyId.value()).update();

            jdbc.sql("""
                    UPDATE solana_mint_attempt SET cluster_identity = :cluster
                    WHERE operation_id = :operationId AND effect_kind = 'TRANSFER'
                    """).param("cluster", TRANSFER_DESTINATION_OWNER.toBase58())
                    .param("operationId", custodyId.value()).update();
            assertThrows(IllegalStateException.class,
                    () -> initialHandler.handle(burnDelivery));
            jdbc.sql("""
                    UPDATE solana_mint_attempt SET cluster_identity = :cluster
                    WHERE operation_id = :operationId AND effect_kind = 'TRANSFER'
                    """).param("cluster", rpc.genesis())
                    .param("operationId", custodyId.value()).update();

            jdbc.sql("""
                    UPDATE wallet_transfer_operation SET destination_registry_version = 'registry-v2'
                    WHERE operation_id = :operationId
                    """).param("operationId", custodyId.value()).update();
            assertThrows(IllegalStateException.class,
                    () -> initialHandler.handle(burnDelivery));
            jdbc.sql("""
                    UPDATE wallet_transfer_operation SET destination_registry_version = 'registry-v1'
                    WHERE operation_id = :operationId
                    """).param("operationId", custodyId.value()).update();

            TokenOperation pendingBurn = operations.findById(
                    burn.operation().operationId()).orElseThrow();
            CountDownLatch prepareStart = new CountDownLatch(1);
            ExecutorService prepareWorkers = Executors.newFixedThreadPool(2);
            try {
                var prepareLeft = prepareWorkers.submit(() -> {
                    prepareStart.await();
                    return chain.prepare(burnDelivery.deliveryId(), pendingBurn,
                            pendingBurn.attempts().getLast());
                });
                var prepareRight = prepareWorkers.submit(() -> {
                    prepareStart.await();
                    return chain.prepare(burnDelivery.deliveryId(), pendingBurn,
                            pendingBurn.attempts().getLast());
                });
                prepareStart.countDown();
                int prepared = 0;
                for (Future<?> result : List.of(prepareLeft, prepareRight)) {
                    try {
                        result.get();
                        prepared++;
                    } catch (java.util.concurrent.ExecutionException fenced) {
                        assertTrue(fenced.getCause() instanceof IllegalStateException);
                    }
                }
                assertTrue(prepared >= 1);
            } finally {
                prepareWorkers.shutdownNow();
            }
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId AND effect_kind = 'BURN'
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM ethereum_redemption_correlation
                    WHERE burn_operation_id = :operationId
                      AND correlation_status = 'CONSUMED'
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());

            rpc.expired(true);
            client(rpc.endpoint()).getBlockHeight(Commitment.CONFIRMED).join();
            assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    initialHandler.handle(burnDelivery).classification());
            rpc.expired(false);

            AtomicBoolean loseBurnResponse = new AtomicBoolean(true);
            SolanaRpcClient burnSubmission = client(rpc.endpoint());
            SavaSolanaMintChainAdapter responseLoss = new SavaSolanaMintChainAdapter(
                    dataSource, burnSubmission, client(rpc.endpoint()), base64 -> {
                        String signature = burnSubmission.sendTransaction(base64, 0).join();
                        if (loseBurnResponse.compareAndSet(true, false)) {
                            throw new IllegalStateException(
                                    "synthetic response loss after ADMIN burn");
                        }
                        return signature;
                    }, configuration, CLOCK);
            RedemptionAcceptedDeliveryHandler lossHandler = redemption(
                    operations, transfers, acceptance, responseLoss, signer, wallets,
                    sourceRef, adminRef, configuration);
            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    lossHandler.handle(burnDelivery).classification());

            SavaSolanaMintChainAdapter.Configuration rotatedMintAuthority =
                    new SavaSolanaMintChainAdapter.Configuration(
                            configuration.rpcUri(), configuration.clusterIdentity(),
                            configuration.mintAddress(), configuration.destinationOwner(),
                            configuration.feePayerPublicKey(),
                            configuration.feePayerKeyAlias(),
                            configuration.feePayerKeyVersion(), publicKey((byte) 20),
                            configuration.mintAuthorityKeyAlias(), "mint-v2",
                            configuration.transferDestinationOwner(),
                            configuration.transferAuthorityKeyAlias(),
                            configuration.transferAuthorityKeyVersion(),
                            configuration.redemptionOwner(),
                            configuration.burnAuthorityKeyAlias(),
                            configuration.burnAuthorityKeyVersion(),
                            configuration.walletRegistryVersion(),
                            configuration.assetId(), configuration.unitId(),
                            configuration.unitVersion(), configuration.decimals(),
                            configuration.policyVersion(),
                            configuration.preparationCommitment(),
                            configuration.observationCommitment(),
                            configuration.minimumFeePayerLamports(),
                            configuration.maximumFeeLamports(),
                            configuration.requestTimeout());
            RedemptionAcceptedDeliveryHandler restartedHandler = redemption(
                    new PostgresOperationRepository(dataSource),
                    new PostgresWalletTransferRepository(dataSource), acceptance,
                    new SavaSolanaMintChainAdapter(
                            dataSource, client(rpc.endpoint()), client(rpc.endpoint()),
                            rotatedMintAuthority, CLOCK),
                    signer, wallets, sourceRef, adminRef, rotatedMintAuthority);
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    restartedHandler.handle(burnDelivery).classification());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    restartedHandler.handle(burnDelivery).classification());
            assertEquals(BigInteger.ZERO, rpc.supply());
            assertEquals(BigInteger.ZERO, rpc.destinationBalance());
            assertEquals(2, rpc.submissionCalls());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM ethereum_redemption_correlation
                    JOIN solana_mint_attempt consumed
                      ON consumed.operation_attempt_id =
                          ethereum_redemption_correlation.consumed_by_burn_attempt_id
                     AND consumed.operation_id =
                          ethereum_redemption_correlation.burn_operation_id
                    WHERE burn_operation_id = :operationId
                      AND correlation_status = 'CONSUMED'
                      AND consumed.effect_kind = 'BURN'
                      AND consumed.replacement_sequence = 0
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId AND effect_kind = 'BURN'
                      AND attempt_status = 'CONFIRMED'
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(2, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId AND effect_kind = 'BURN'
                      AND redemption_correlation_id IS NOT NULL
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId AND effect_kind = 'BURN'
                      AND replacement_parent_id IS NOT NULL
                      AND replacement_sequence = 1
                    """).param("operationId", burn.operation().operationId().value())
                    .query(Integer.class).single());
        }
    }

    @Test
    void v13MigratesAnExistingV11Schema() {
        String schema = "phase7c_v11_" + UUID.randomUUID().toString().replace("-", "");
        Flyway v11 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(true)
                .target(MigrationVersion.fromVersion("11"))
                .cleanDisabled(true)
                .load();
        v11.migrate();
        assertEquals("11", v11.info().current().getVersion().getVersion());

        UUID operationId = UUID.randomUUID();
        UUID attemptId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        UUID nativeAttemptId = UUID.randomUUID();
        jdbc.sql(("""
                INSERT INTO %s.token_operation (
                    operation_id, tenant_id, participant_id, idempotency_resource,
                    operation_kind, idempotency_key_digest, request_contract_version,
                    canonicalization_version, command_digest, business_correlation,
                    asset_id, unit_id, unit_version, unit_scale, unit_max_atomic,
                    quantity_atomic, lifecycle_state, aggregate_version,
                    acceptance_evidence_ref, created_at, updated_at)
                VALUES (:operationId, 'tenant-v11', 'participant-v11',
                    'TOKEN_OPERATION', 'MINT', repeat('a', 64), 1, 1,
                    repeat('b', 64), 'v11-retained-mint', 'USD_STABLE', 'USD',
                    1, 2, 1000000, 100, 'COMPLETED', 3,
                    'internal:test:v11-acceptance', :now, :now)
                """).formatted(schema))
                .param("operationId", operationId)
                .param("now", NOW.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql(("""
                INSERT INTO %s.operation_attempt (
                    operation_id, attempt_order, attempt_id,
                    authorization_evidence_ref, aggregate_version, created_at)
                VALUES (:operationId, 0, :attemptId,
                    'internal:test:v11-attempt', 1, :now)
                """).formatted(schema))
                .param("operationId", operationId).param("attemptId", attemptId)
                .param("now", NOW.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql(("""
                INSERT INTO %s.operation_outbox (
                    event_id, operation_id, event_type, event_version,
                    payload_schema_version, payload, status, created_at,
                    available_at, updated_at)
                VALUES (:eventId, :operationId, 'TokenOperationAccepted', 1, 1,
                    '{}'::jsonb, 'PENDING', :now, :now, :now)
                """).formatted(schema))
                .param("eventId", eventId).param("operationId", operationId)
                .param("now", NOW.atOffset(ZoneOffset.UTC)).update();
        jdbc.sql(("""
                INSERT INTO %s.solana_mint_attempt (
                    operation_id, operation_attempt_id, delivery_id,
                    native_attempt_id, replacement_parent_id, replacement_sequence,
                    network, cluster_identity, route_snapshot_ref, token_program_id,
                    ata_program_id, mint_address, destination_owner, destination_ata,
                    ata_existed, decimals, amount_atomic, pre_mint_supply,
                    pre_destination_balance, fee_payer_public_key,
                    fee_payer_key_alias, fee_payer_key_version,
                    mint_authority_public_key, mint_authority_key_alias,
                    mint_authority_key_version, policy_version,
                    maximum_fee_lamports, commitment, recent_blockhash,
                    last_valid_block_height, unsigned_transaction, message_sha256,
                    instruction_sha256, transaction_signature, attempt_status,
                    submit_fence, submission_started_at, submission_recorded_at,
                    submission_code, aggregate_version, created_at, updated_at)
                VALUES (:operationId, :attemptId, :eventId, :nativeAttemptId,
                    NULL, 0, 'LOCAL_SOLANA', repeat('2', 32),
                    'internal:test:v11-route', repeat('3', 32), repeat('4', 32),
                    repeat('5', 32), repeat('6', 32), repeat('7', 32),
                    false, 2, 100, 0, 0, repeat('8', 32), 'v11-fee', 'fee-v1',
                    repeat('9', 32), 'v11-mint', 'mint-v1', 'v11-policy',
                    100000, 'finalized', repeat('A', 32), 500,
                    decode('01', 'hex'), repeat('c', 64), repeat('d', 64),
                    repeat('C', 88), 'CONFIRMED', 1, :now, :now,
                    'rpc-accepted', 3, :now, :now)
                """).formatted(schema))
                .param("operationId", operationId).param("attemptId", attemptId)
                .param("eventId", eventId).param("nativeAttemptId", nativeAttemptId)
                .param("now", NOW.atOffset(ZoneOffset.UTC)).update();
        for (int order = 0; order < 2; order++) {
            jdbc.sql(("""
                    INSERT INTO %s.solana_mint_signature (
                        operation_id, operation_attempt_id, signer_order, key_role,
                        key_alias, key_version, public_key, signature_bytes,
                        signature_sha256, signature_encoding, retained_at)
                    VALUES (:operationId, :attemptId, :signerOrder, :keyRole,
                        :keyAlias, :keyVersion, :publicKey,
                        decode(repeat(:signatureByte, 64), 'hex'),
                        repeat(:hashCharacter, 64), 'solana-ed25519-64-byte', :now)
                    """).formatted(schema))
                    .param("operationId", operationId).param("attemptId", attemptId)
                    .param("signerOrder", order)
                    .param("keyRole", order == 0 ? "FEE_PAYER" : "MINT_AUTHORITY")
                    .param("keyAlias", order == 0 ? "v11-fee" : "v11-mint")
                    .param("keyVersion", order == 0 ? "fee-v1" : "mint-v1")
                    .param("publicKey", String.valueOf(order == 0 ? '8' : '9').repeat(32))
                    .param("signatureByte", order == 0 ? "01" : "02")
                    .param("hashCharacter", order == 0 ? "e" : "f")
                    .param("now", NOW.atOffset(ZoneOffset.UTC)).update();
        }
        jdbc.sql(("""
                INSERT INTO %s.solana_mint_observation (
                    operation_id, operation_attempt_id, observation_sequence,
                    observation_status, transaction_signature, commitment, slot,
                    block_time, expected_instructions, observed_mint_supply,
                    observed_destination_balance, mint_delta, destination_delta,
                    evidence_ref, observed_at)
                VALUES (:operationId, :attemptId, 1, 'CONFIRMED', repeat('C', 88),
                    'finalized', 200, 1784472000, true, 100, 100, 100, 100,
                    'internal:test:v11-observation', :now)
                """).formatted(schema))
                .param("operationId", operationId).param("attemptId", attemptId)
                .param("now", NOW.atOffset(ZoneOffset.UTC)).update();

        Flyway v12 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .target(MigrationVersion.fromVersion("12"))
                .cleanDisabled(true)
                .load();
        v12.migrate();
        assertEquals("12", v12.info().current().getVersion().getVersion());

        Flyway v13 = Flyway.configure()
                .dataSource(dataSource)
                .schemas(schema)
                .defaultSchema(schema)
                .createSchemas(false)
                .cleanDisabled(true)
                .load();
        v13.migrate();

        assertEquals("13", v13.info().current().getVersion().getVersion());
        assertEquals(List.of("effect_kind", "pre_source_balance",
                        "redemption_correlation_id", "source_ata",
                        "source_owner", "token_operation_id",
                        "wallet_transfer_operation_id"),
                jdbc.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = :schema
                          AND table_name = 'solana_mint_attempt'
                          AND column_name IN (
                              'effect_kind', 'pre_source_balance', 'source_ata',
                              'source_owner', 'redemption_correlation_id',
                              'token_operation_id',
                              'wallet_transfer_operation_id')
                        ORDER BY column_name
                        """).param("schema", schema).query(String.class).list());
        assertEquals(List.of("transaction_post_destination_balance",
                        "transaction_post_source_balance",
                        "transaction_pre_destination_balance",
                        "transaction_pre_source_balance"),
                jdbc.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = :schema
                          AND table_name = 'solana_mint_observation'
                          AND column_name LIKE 'transaction_%_balance'
                        ORDER BY column_name
                        """).param("schema", schema).query(String.class).list());
        assertEquals("MINT", jdbc.sql(("""
                SELECT effect_kind FROM %s.solana_mint_attempt
                WHERE operation_id = :operationId
                  AND token_operation_id = :operationId
                  AND wallet_transfer_operation_id IS NULL
                """).formatted(schema)).param("operationId", operationId)
                .query(String.class).single());
        assertEquals(List.of("FEE_PAYER:MINT", "MINT_AUTHORITY:MINT"),
                jdbc.sql(("""
                        SELECT key_role || ':' || effect_kind
                        FROM %s.solana_mint_signature
                        WHERE operation_id = :operationId ORDER BY signer_order
                        """).formatted(schema)).param("operationId", operationId)
                        .query(String.class).list());
        assertEquals("CONFIRMED:MINT", jdbc.sql(("""
                SELECT observation_status || ':' || effect_kind
                FROM %s.solana_mint_observation
                WHERE operation_id = :operationId
                """).formatted(schema)).param("operationId", operationId)
                .query(String.class).single());
        assertEquals(3, jdbc.sql("""
                SELECT count(*) FROM pg_constraint
                WHERE connamespace = CAST(:schema AS regnamespace)
                  AND conname IN ('fk_solana_native_token_operation',
                      'fk_solana_mint_signature_attempt',
                      'fk_solana_mint_observation_attempt')
                  AND convalidated
                """).param("schema", schema).query(Integer.class).single());
    }

    @Test
    void lostSubmitResponseRecoversByKnownSignatureWithoutSecondSubmission()
            throws Exception {
        try (Scenario scenario = scenario(true)) {
            OperationAcceptance acceptance = scenario.accept("solana-lost-response", "7.25");
            OperationDelivery delivery = scenario.delivery(
                    acceptance.operation().operationId());

            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    scenario.handler().handle(delivery).classification());
            assertEquals(1, scenario.rpc().submissionCalls());
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    scenario.restartedHandler().handle(delivery).classification());
            assertEquals(1, scenario.rpc().submissionCalls());
            assertEquals(BigInteger.valueOf(725), scenario.rpc().supply());
            assertEquals(BigInteger.valueOf(725), scenario.rpc().balance());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*) FROM solana_mint_attempt
                    WHERE operation_id = :operationId
                      AND attempt_status = 'CONFIRMED'
                      AND submit_fence = 1
                      AND submission_code = 'rpc-response-unavailable'
                    """).param("operationId", acceptance.operation().operationId().value())
                    .query(Integer.class).single());
        }
    }

    @Test
    void replacementLineagePartialRestartRollbackAndConcurrentSubmitFence()
            throws Exception {
        try (Scenario scenario = scenario(false)) {
            OperationAcceptance acceptance = scenario.accept(
                    "solana-store-recovery", "1");
            TokenOperation operation = submissionPending(
                    scenario.operations(), acceptance.operation());
            UUID eventId = jdbc.sql("""
                    SELECT event_id FROM operation_outbox WHERE operation_id = :operationId
                    """).param("operationId", operation.operationId().value())
                    .query(UUID.class).single();
            SolanaMintAttemptStore firstStore = new SolanaMintAttemptStore(dataSource);
            SolanaMintAttemptStore.AttemptRow first = firstStore.prepare(
                    draft(operation, operation.attempts().getFirst().attemptId(), eventId,
                            UUID.randomUUID(), Optional.empty(), 0), NOW);
            first = firstStore.attachSignature(
                    first, signature(0, SigningRequest.KeyRole.FEE_PAYER, (byte) 1),
                    "1".repeat(88), NOW);
            first = firstStore.attachSignature(
                    first, signature(1, SigningRequest.KeyRole.MINT_AUTHORITY, (byte) 2),
                    null, NOW);
            SolanaMintAttemptStore.SubmissionClaim firstClaim =
                    firstStore.claimSubmission(first, NOW);
            assertTrue(firstClaim.claimed());
            firstStore.recordSubmission(
                    firstClaim.attempt(), SolanaMintAttemptStore.AttemptStatus.EXPIRED,
                    "blockhash-expired-before-submit", NOW);

            AttemptId replacementId = new AttemptId(UUID.randomUUID());
            RetryAuthorization retry = new RetryAuthorization(
                    operation.attempts().getFirst().attemptId(),
                    RetryAuthorization.Basis.NATIVE_SAFE_REPLACEMENT,
                    "local-solana-replacement-v1", evidence("replacement-authorized"));
            TokenOperation withReplacement = operation.addFollowUpAttempt(
                    operation.version(), replacementId, retry, NOW);
            scenario.operations().save(withReplacement, operation.version());

            SolanaMintAttemptStore restarted = new SolanaMintAttemptStore(dataSource);
            SolanaMintAttemptStore.AttemptRow replacement = restarted.prepare(
                    draft(withReplacement, replacementId, eventId, UUID.randomUUID(),
                            Optional.of(first.nativeAttemptId()), 1), NOW);
            SolanaMintAttemptStore.AttemptRow retainedReplacement = replacement;
            assertThrows(IllegalStateException.class, () -> restarted.attachSignature(
                    retainedReplacement,
                    signature(1, SigningRequest.KeyRole.MINT_AUTHORITY, (byte) 3),
                    null, NOW));
            assertTrue(restarted.signatures(
                    withReplacement.operationId(), replacementId).isEmpty());
            assertEquals(SolanaMintAttemptStore.AttemptStatus.PREPARED,
                    restarted.find(withReplacement.operationId(), replacementId)
                            .orElseThrow().status());

            replacement = restarted.attachSignature(
                    replacement, signature(0, SigningRequest.KeyRole.FEE_PAYER, (byte) 4),
                    "2".repeat(88), NOW);
            SolanaMintAttemptStore afterProcessRestart =
                    new SolanaMintAttemptStore(dataSource);
            assertEquals(Set.of(0), afterProcessRestart.signatureOrders(
                    withReplacement.operationId(), replacementId));
            replacement = afterProcessRestart.attachSignature(
                    afterProcessRestart.find(
                            withReplacement.operationId(), replacementId).orElseThrow(),
                    signature(1, SigningRequest.KeyRole.MINT_AUTHORITY, (byte) 5),
                    null, NOW);

            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                SolanaMintAttemptStore.AttemptRow signed = replacement;
                Future<SolanaMintAttemptStore.SubmissionClaim> left = workers.submit(() -> {
                    start.await();
                    return new SolanaMintAttemptStore(dataSource)
                            .claimSubmission(signed, NOW);
                });
                Future<SolanaMintAttemptStore.SubmissionClaim> right = workers.submit(() -> {
                    start.await();
                    return new SolanaMintAttemptStore(dataSource)
                            .claimSubmission(signed, NOW);
                });
                start.countDown();
                int claims = (left.get().claimed() ? 1 : 0)
                        + (right.get().claimed() ? 1 : 0);
                assertEquals(1, claims);
            } finally {
                workers.shutdownNow();
            }
            SolanaMintAttemptStore.AttemptRow fenced = afterProcessRestart.find(
                    withReplacement.operationId(), replacementId).orElseThrow();
            assertEquals(SolanaMintAttemptStore.AttemptStatus.SUBMISSION_STARTED,
                    fenced.status());
            assertEquals(1, fenced.submitFence());
            assertEquals(Optional.of(first.nativeAttemptId()),
                    fenced.replacementParentId());
            assertEquals(1, fenced.replacementSequence());
            assertFalse(first.nativeAttemptId().equals(fenced.nativeAttemptId()));
            afterProcessRestart.recordSubmission(
                    fenced, SolanaMintAttemptStore.AttemptStatus.EXPIRED,
                    "test-route-released", NOW);
        }
    }

    @Test
    void serializesPreparationSnapshotsAcrossTwoDistinctMintOperations()
            throws Exception {
        try (Scenario scenario = scenario(false)) {
            TokenOperation firstOperation = submissionPending(
                    scenario.operations(), scenario.accept(
                            "solana-lane-first", "1").operation());
            TokenOperation secondOperation = submissionPending(
                    scenario.operations(), scenario.accept(
                            "solana-lane-second", "1").operation());
            UUID firstEvent = eventId(firstOperation.operationId());
            UUID secondEvent = eventId(secondOperation.operationId());
            SolanaMintAttemptStore store = new SolanaMintAttemptStore(dataSource);
            SolanaMintAttemptStore.AttemptRow first = store.prepare(
                    draft(firstOperation, firstOperation.attempts().getFirst().attemptId(),
                            firstEvent, UUID.randomUUID(), Optional.empty(), 0), NOW);

            assertThrows(DataIntegrityViolationException.class, () -> store.prepare(
                    draft(secondOperation,
                            secondOperation.attempts().getFirst().attemptId(),
                            secondEvent, UUID.randomUUID(), Optional.empty(), 0), NOW));

            first = store.attachSignature(
                    first, signature(0, SigningRequest.KeyRole.FEE_PAYER, (byte) 6),
                    "3".repeat(88), NOW);
            first = store.attachSignature(
                    first, signature(1, SigningRequest.KeyRole.MINT_AUTHORITY, (byte) 7),
                    null, NOW);
            SolanaMintAttemptStore.SubmissionClaim claim = store.claimSubmission(first, NOW);
            first = store.recordSubmission(
                    claim.attempt(), SolanaMintAttemptStore.AttemptStatus.ACCEPTED,
                    "rpc-accepted", NOW);
            store.recordObservation(first, new SolanaMintAttemptStore.ObservationDraft(
                    SolanaMintAttemptStore.ObservationStatus.CONFIRMED, "finalized",
                    Optional.of(200L), Optional.of(1784472000L), Optional.empty(), true,
                    Optional.of(BigInteger.valueOf(100)),
                    Optional.of(BigInteger.valueOf(100)),
                    Optional.of(BigInteger.valueOf(100)),
                    Optional.of(BigInteger.valueOf(100)),
                    evidence("first-route-confirmed").value()), NOW);

            SolanaMintAttemptStore.AttemptRow second = store.prepare(
                    draft(secondOperation,
                            secondOperation.attempts().getFirst().attemptId(),
                            secondEvent, UUID.randomUUID(), Optional.empty(), 0), NOW);
            assertEquals(SolanaMintAttemptStore.AttemptStatus.PREPARED, second.status());
            second = store.attachSignature(
                    second, signature(0, SigningRequest.KeyRole.FEE_PAYER, (byte) 8),
                    "4".repeat(88), NOW);
            second = store.attachSignature(
                    second, signature(1, SigningRequest.KeyRole.MINT_AUTHORITY, (byte) 9),
                    null, NOW);
            SolanaMintAttemptStore.SubmissionClaim secondClaim =
                    store.claimSubmission(second, NOW);
            store.recordSubmission(
                    secondClaim.attempt(), SolanaMintAttemptStore.AttemptStatus.EXPIRED,
                    "blockhash-expired-before-submit", NOW);
        }
    }

    private static UUID eventId(OperationId operationId) {
        return jdbc.sql("""
                SELECT event_id FROM operation_outbox WHERE operation_id = :operationId
                """).param("operationId", operationId.value()).query(UUID.class).single();
    }

    private static OperationDelivery tokenDelivery(OperationId operationId) {
        return new OperationDelivery(
                eventId(operationId), operationId, 1, 1, UUID.randomUUID(),
                "solana-redemption-worker", 1);
    }

    private static TokenOperation submissionPending(
            PostgresOperationRepository operations, TokenOperation operation) {
        for (OperationState target : List.of(
                OperationState.VALIDATED, OperationState.POLICY_PENDING,
                OperationState.APPROVAL_PENDING, OperationState.AUTHORIZED)) {
            TokenOperation changed = operation.transition(
                    operation.version(), target, "solana-store-test",
                    "advance-" + target.name().toLowerCase(), NOW,
                    List.of(evidence("transition-" + target.name().toLowerCase())));
            operations.save(changed, operation.version());
            operation = changed;
        }
        TokenOperation withAttempt = operation.addInitialAttempt(
                operation.version(), new AttemptId(UUID.randomUUID()),
                evidence("initial-attempt"), NOW);
        operations.save(withAttempt, operation.version());
        operation = withAttempt;
        for (OperationState target : List.of(
                OperationState.SIGNING, OperationState.SUBMISSION_PENDING)) {
            TokenOperation changed = operation.transition(
                    operation.version(), target, "solana-store-test",
                    "advance-" + target.name().toLowerCase(), NOW,
                    List.of(evidence("transition-" + target.name().toLowerCase())));
            operations.save(changed, operation.version());
            operation = changed;
        }
        return operation;
    }

    private static SolanaMintAttemptStore.Draft draft(
            TokenOperation operation, AttemptId attemptId, UUID deliveryId,
            UUID nativeAttemptId, Optional<UUID> parent, int sequence) {
        return new SolanaMintAttemptStore.Draft(
                operation.operationId(), attemptId, deliveryId, nativeAttemptId,
                parent, sequence, publicKey((byte) 9), "route:test:solana-store",
                SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58(),
                SolanaMintTransactionCodec.ATA_PROGRAM.toBase58(),
                publicKey((byte) 7), publicKey((byte) 6), publicKey((byte) 5),
                false, 2, BigInteger.valueOf(100), BigInteger.ZERO, BigInteger.ZERO,
                new SolanaMintAttemptStore.SignerContext(
                        new KeyAlias("local-solana:test-fee"),
                        SigningRequest.KeyRole.FEE_PAYER, "fee-v1", publicKey((byte) 4)),
                new SolanaMintAttemptStore.SignerContext(
                        new KeyAlias("local-solana:test-authority"),
                        SigningRequest.KeyRole.MINT_AUTHORITY,
                        "authority-v1", publicKey((byte) 3)),
                "local-solana-mint-v1", BigInteger.valueOf(100_000),
                publicKey((byte) 2), 500, new byte[] {1, 2, 3},
                "a".repeat(64), "b".repeat(64));
    }

    private static SolanaMintAttemptStore.SignatureDraft signature(
            int order, SigningRequest.KeyRole role, byte value) {
        byte[] bytes = new byte[64];
        Arrays.fill(bytes, value);
        return new SolanaMintAttemptStore.SignatureDraft(
                order, role, new KeyAlias("local-solana:test-" + order),
                "key-v1", publicKey((byte) (10 + order)), bytes,
                order == 0 ? "c".repeat(64) : "d".repeat(64),
                LocalSolanaConfiguredSigner.SIGNATURE_ENCODING);
    }

    private static String publicKey(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return PublicKey.createPubKey(bytes).toBase58();
    }

    private static EvidenceRef evidence(String kind) {
        return new EvidenceRef("internal:test:solana-store:" + kind);
    }

    private static RedemptionAcceptedDeliveryHandler redemption(
            PostgresOperationRepository operations,
            PostgresWalletTransferRepository transfers,
            WalletTransferAcceptanceService acceptance,
            SavaSolanaMintChainAdapter chain,
            LocalSolanaConfiguredSigner signer,
            WalletIdentityRegistry wallets,
            WalletReference source,
            WalletReference admin,
            SavaSolanaMintChainAdapter.Configuration configuration) {
        SigningAuthorityService signing = signingService(signer);
        var burn = new TokenOperationAcceptedDeliveryHandler(
                operations, chain, signing, CLOCK::instant, ids(),
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        new KeyAlias(configuration.burnAuthorityKeyAlias()),
                        configuration.redemptionOwner(), Duration.ofMinutes(5),
                        configuration.policyVersion(), OperationKind.BURN,
                        SigningRequest.Action.BURN,
                        SigningRequest.KeyRole.BURN_AUTHORITY,
                        SettlementNetwork.SOLANA,
                        SigningRequest.Mode.SOLANA_MESSAGE,
                        SigningRequest.Algorithm.ED25519));
        var custody = new WalletTransferAcceptedDeliveryHandler(
                transfers, chain, signing, wallets,
                CLOCK::instant, Duration.ofMinutes(5));
        return new RedemptionAcceptedDeliveryHandler(
                operations, transfers, acceptance, burn, custody, source, admin);
    }

    private Scenario scenario(boolean loseResponse) throws Exception {
        TestKey fee = key(temporary.resolve("fee-" + loseResponse + ".json"),
                "local-solana:fee-" + loseResponse);
        TestKey authority = key(
                temporary.resolve("authority-" + loseResponse + ".json"),
                "local-solana:authority-" + loseResponse);
        TestKey transfer = key(
                temporary.resolve("transfer-" + loseResponse + ".json"),
                "local-solana:transfer-" + loseResponse);
        TestKey burn = key(
                temporary.resolve("burn-" + loseResponse + ".json"),
                "local-solana:burn-" + loseResponse);
        Files.setPosixFilePermissions(temporary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        LocalSolanaConfiguredSigner signer = new LocalSolanaConfiguredSigner(
                new LocalSolanaConfiguredSigner.Configuration(temporary, List.of(
                        configured(fee, SigningRequest.KeyRole.FEE_PAYER, "fee-v1"),
                        configured(authority, SigningRequest.KeyRole.MINT_AUTHORITY,
                                "authority-v1"),
                        configured(transfer, SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                "transfer-v1"),
                        configured(burn, SigningRequest.KeyRole.BURN_AUTHORITY,
                                "burn-v1"))));
        RpcFixture rpc = new RpcFixture(fee.publicKey(), authority.publicKey());
        SolanaRpcClient submission = client(rpc.endpoint());
        SolanaRpcClient observation = client(rpc.endpoint());
        AtomicBoolean lose = new AtomicBoolean(loseResponse);
        SavaSolanaMintChainAdapter.Configuration configuration =
                configuration(rpc, fee, authority);
        SavaSolanaMintChainAdapter chain = new SavaSolanaMintChainAdapter(
                dataSource, submission, observation, base64 -> {
                    String signature = submission.sendTransaction(base64, 0).join();
                    if (lose.compareAndSet(true, false)) {
                        throw new IllegalStateException(
                                "synthetic response loss after validator acceptance");
                    }
                    return signature;
                }, configuration, CLOCK);
        PostgresOperationRepository operations = new PostgresOperationRepository(dataSource);
        TokenOperationAcceptedDeliveryHandler handler =
                new TokenOperationAcceptedDeliveryHandler(
                        operations, chain, signingService(signer), CLOCK::instant, ids(),
                        new TokenOperationAcceptedDeliveryHandler.Policy(
                                fee.alias(), fee.address(), Duration.ofMinutes(5),
                                "local-solana-mint-v1", OperationKind.MINT,
                                SigningRequest.Action.MINT,
                                SigningRequest.KeyRole.FEE_PAYER,
                                SettlementNetwork.SOLANA,
                                SigningRequest.Mode.SOLANA_MESSAGE,
                                SigningRequest.Algorithm.ED25519));
        return new Scenario(rpc, signer, operations, handler, configuration, fee);
    }

    private static SavaSolanaMintChainAdapter.Configuration configuration(
            RpcFixture rpc, TestKey fee, TestKey authority) {
        return new SavaSolanaMintChainAdapter.Configuration(
                rpc.endpoint(), rpc.genesis(), rpc.mint().toBase58(),
                DESTINATION_OWNER.toBase58(), fee.address(), fee.alias().value(), "fee-v1",
                authority.address(), authority.alias().value(), "authority-v1",
                UNIT.assetId(), UNIT.unitId(), UNIT.version(), UNIT.scale(),
                "local-solana-mint-v1",
                SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                SavaSolanaMintChainAdapter.CommitmentLevel.FINALIZED,
                BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000),
                Duration.ofSeconds(2));
    }

    private static SolanaRpcClient client(URI endpoint) {
        return SolanaRpcClient.build().endpoint(endpoint)
                .requestTimeout(Duration.ofSeconds(2))
                .defaultCommitment(Commitment.CONFIRMED).createClient();
    }

    private static SigningAuthorityService signingService(
            LocalSolanaConfiguredSigner signer) {
        return signingService(signer, CLOCK);
    }

    private static SigningAuthorityService signingService(
            LocalSolanaConfiguredSigner signer, Clock clock) {
        SigningAuthorizationPort authorization = request ->
                new SigningAuthorizationPort.Authorized(new EvidenceRef(
                        "internal:test:solana-signing-authorized:"
                                + request.requestId().value()));
        SigningIdentityGenerator signingIds = new SigningIdentityGenerator() {
            @Override public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }
            @Override public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-solana-provider:" + UUID.randomUUID());
            }
        };
        return new SigningAuthorityService(
                new PostgresSigningRequestRepository(dataSource), signer,
                authorization, signer, signingIds, clock::instant);
    }

    private static IdGenerator ids() {
        return new IdGenerator() {
            @Override public OperationId nextOperationId() {
                return new OperationId(UUID.randomUUID());
            }
            @Override public AttemptId nextAttemptId() {
                return new AttemptId(UUID.randomUUID());
            }
        };
    }

    private static TransferIdentityGenerator transferIds() {
        return new TransferIdentityGenerator() {
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
    }

    private static WalletIdentityRegistry walletRegistry(
            WalletReference sourceRef,
            WalletReference destinationRef,
            TestKey source,
            SavaSolanaMintChainAdapter.Configuration configuration) {
        var sourceIdentity = new WalletIdentityRegistry.WalletIdentity(
                sourceRef, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.SOLANA, source.address(), source.alias(),
                "registry-v1", "source-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
        var destinationIdentity = new WalletIdentityRegistry.WalletIdentity(
                destinationRef, Set.of(),
                WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.SOLANA, configuration.transferDestinationOwner(),
                new KeyAlias("local-solana:destination-public"),
                "registry-v1", "destination-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
        return new WalletIdentityRegistry() {
            @Override public WalletIdentity resolve(WalletReference reference) {
                if (sourceRef.equals(reference)) return sourceIdentity;
                if (destinationRef.equals(reference)) return destinationIdentity;
                throw new IllegalArgumentException("unknown test wallet");
            }
            @Override public List<WalletIdentity> identities() {
                return List.of(sourceIdentity, destinationIdentity);
            }
        };
    }

    private static WalletIdentityRegistry walletRegistry(
            WalletIdentityRegistry.WalletIdentity source,
            WalletIdentityRegistry.WalletIdentity destination) {
        return new WalletIdentityRegistry() {
            @Override public WalletIdentity resolve(WalletReference reference) {
                if (source.reference().equals(reference)) return source;
                if (destination.reference().equals(reference)) return destination;
                throw new IllegalArgumentException("unknown routing-control wallet");
            }
            @Override public List<WalletIdentity> identities() {
                return List.of(source, destination);
            }
        };
    }

    private static OperationDelivery walletDelivery(OperationId operationId) {
        var claimed = PostgresOperationDeliveryQueue.localSolana(dataSource).claim(
                "solana-transfer-worker", NOW, Duration.ofMinutes(1), 100);
        assertTrue(claimed.deliveries().stream().allMatch(delivery ->
                TokenOperationAcceptedDeliveryHandler.EVENT_TYPE.equals(delivery.eventType())
                        || WalletTransferAcceptedDeliveryHandler.EVENT_TYPE.equals(
                                delivery.eventType())));
        return claimed.deliveries().stream()
                .filter(delivery -> delivery.aggregateId().equals(operationId.value()))
                .findFirst().orElseThrow();
    }

    private static LocalSolanaConfiguredSigner.ConfiguredKey configured(
            TestKey key, SigningRequest.KeyRole role, String version) {
        return new LocalSolanaConfiguredSigner.ConfiguredKey(
                key.alias(), role, key.file(), key.address(), version);
    }

    private static TestKey key(Path file, String alias) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("Ed25519");
        generator.initialize(NamedParameterSpec.ED25519);
        KeyPair pair = generator.generateKeyPair();
        byte[] seed = ((EdECPrivateKey) pair.getPrivate()).getBytes().orElseThrow();
        byte[] encoded = pair.getPublic().getEncoded();
        byte[] publicBytes = Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        byte[] keypair = new byte[64];
        System.arraycopy(seed, 0, keypair, 0, 32);
        System.arraycopy(publicBytes, 0, keypair, 32, 32);
        String json = "[" + java.util.stream.IntStream.range(0, 64)
                .map(index -> Byte.toUnsignedInt(keypair[index]))
                .mapToObj(Integer::toString)
                .collect(java.util.stream.Collectors.joining(",")) + "]";
        Files.writeString(file, json, StandardCharsets.US_ASCII);
        Files.setPosixFilePermissions(file, Set.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Arrays.fill(seed, (byte) 0);
        Arrays.fill(keypair, (byte) 0);
        PublicKey publicKey = PublicKey.createPubKey(publicBytes);
        return new TestKey(new KeyAlias(alias), file, publicKey.toBase58(), publicKey);
    }

    private record TestKey(KeyAlias alias, Path file, String address, PublicKey publicKey) {
    }

    private static final class FailFirstSignatureAttachment
            implements WalletTransferChainPort {

        private final WalletTransferChainPort delegate;
        private final AtomicBoolean fail = new AtomicBoolean(true);

        private FailFirstSignatureAttachment(WalletTransferChainPort delegate) {
            this.delegate = delegate;
        }

        @Override
        public ChainPort.PreparedAttempt prepare(
                UUID deliveryId, WalletTransferOperation operation) {
            return delegate.prepare(deliveryId, operation);
        }

        @Override
        public Optional<ChainPort.SignedAttempt> findSignedAttempt(
                ChainPort.AttemptIdentity identity) {
            return delegate.findSignedAttempt(identity);
        }

        @Override
        public List<ChainPort.SigningRequirement> requiredSigners(
                ChainPort.AttemptIdentity identity) {
            return delegate.requiredSigners(identity);
        }

        @Override
        public Set<Integer> retainedSignatureOrders(
                ChainPort.AttemptIdentity identity) {
            return delegate.retainedSignatureOrders(identity);
        }

        @Override
        public ChainPort.SignedAttempt attachSignature(
                ChainPort.AttemptIdentity identity,
                ChainPort.AuthorizedSignature signature) {
            if (fail.compareAndSet(true, false)) {
                throw new IllegalStateException(
                        "synthetic crash after durable signing result");
            }
            return delegate.attachSignature(identity, signature);
        }

        @Override
        public ChainPort.SubmissionResult submitOnce(
                ChainPort.SignedAttempt signedAttempt) {
            return delegate.submitOnce(signedAttempt);
        }

        @Override
        public ChainPort.InquiryResult inquire(ChainPort.AttemptIdentity identity) {
            return delegate.inquire(identity);
        }

        @Override
        public ChainPort.Observation observe(ChainPort.ObservationRequest request) {
            return delegate.observe(request);
        }
    }

    private record Scenario(
            RpcFixture rpc,
            LocalSolanaConfiguredSigner signer,
            PostgresOperationRepository operations,
            TokenOperationAcceptedDeliveryHandler handler,
            SavaSolanaMintChainAdapter.Configuration configuration,
            TestKey fee) implements AutoCloseable {

        OperationAcceptance accept(String key, String quantity) {
            TokenOperationService service = new TokenOperationService(
                    operations, CLOCK::instant, ids(),
                    (canonical, participant) -> new EvidenceRef(
                            "participant:test:solana-acceptance:" + UUID.randomUUID()));
            return service.accept(new MintCommand(
                    1, PARTICIPANT, TokenQuantity.parse(quantity, UNIT),
                    "local-solana-" + key), IdempotencyKey.of(key));
        }

        OperationDelivery delivery(OperationId operationId) {
            UUID event = jdbc.sql("""
                    SELECT event_id FROM operation_outbox WHERE operation_id = :operationId
                    """).param("operationId", operationId.value()).query(UUID.class).single();
            return new OperationDelivery(
                    event, operationId, 1, 1, UUID.randomUUID(),
                    "solana-integration-worker", 1);
        }

        TokenOperationAcceptedDeliveryHandler restartedHandler() {
            SavaSolanaMintChainAdapter restarted = new SavaSolanaMintChainAdapter(
                    dataSource, client(rpc.endpoint()), client(rpc.endpoint()),
                    configuration, CLOCK);
            return new TokenOperationAcceptedDeliveryHandler(
                    new PostgresOperationRepository(dataSource), restarted,
                    signingService(signer), CLOCK::instant, ids(),
                    new TokenOperationAcceptedDeliveryHandler.Policy(
                            fee.alias(), fee.address(), Duration.ofMinutes(5),
                            "local-solana-mint-v1", OperationKind.MINT,
                            SigningRequest.Action.MINT,
                            SigningRequest.KeyRole.FEE_PAYER,
                            SettlementNetwork.SOLANA,
                            SigningRequest.Mode.SOLANA_MESSAGE,
                            SigningRequest.Algorithm.ED25519));
        }

        @Override public void close() {
            signer.close();
            rpc.close();
        }
    }

    private static final class RpcFixture implements AutoCloseable {
        private static final Pattern SEND = Pattern.compile(
                "\\\"params\\\":\\[\\\"([A-Za-z0-9+/=]+)\\\"");
        private final HttpServer server;
        private final String genesis = PublicKey.createPubKey(fill((byte) 9)).toBase58();
        private final PublicKey mint = PublicKey.createPubKey(fill((byte) 7));
        private final PublicKey feePayer;
        private final PublicKey authority;
        private final PublicKey sourceOwner;
        private final PublicKey destinationOwner;
        private final PublicKey sourceAta;
        private final PublicKey destinationAta;
        private byte[] signedTransaction;
        private BigInteger supply;
        private BigInteger balance;
        private BigInteger destinationBalance = BigInteger.ZERO;
        private BigInteger transactionPreSource = BigInteger.ZERO;
        private BigInteger transactionPreDestination = BigInteger.ZERO;
        private AccountFault accountFault = AccountFault.NONE;
        private boolean expired;
        private boolean finalizedFailure;
        private boolean transactionBalanceMismatch;
        private int blockHeightCalls;
        private int submissionCalls;

        RpcFixture(PublicKey feePayer, PublicKey authority) throws IOException {
            this(feePayer, authority, DESTINATION_OWNER,
                    TRANSFER_DESTINATION_OWNER, BigInteger.ZERO, BigInteger.ZERO);
        }

        RpcFixture(
                PublicKey feePayer,
                PublicKey authority,
                PublicKey sourceOwner,
                PublicKey destinationOwner,
                BigInteger supply,
                BigInteger balance) throws IOException {
            this.feePayer = feePayer;
            this.authority = authority;
            this.sourceOwner = sourceOwner;
            this.destinationOwner = destinationOwner;
            this.sourceAta = SolanaMintTransactionCodec.associatedTokenAddress(
                    sourceOwner, mint);
            this.destinationAta = SolanaMintTransactionCodec.associatedTokenAddress(
                    destinationOwner, mint);
            this.supply = supply;
            this.balance = balance;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::respond);
            server.start();
        }

        URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }
        String genesis() { return genesis; }
        PublicKey mint() { return mint; }
        BigInteger supply() { return supply; }
        BigInteger balance() { return balance; }
        BigInteger destinationBalance() { return destinationBalance; }
        int submissionCalls() { return submissionCalls; }
        void balance(BigInteger value) { balance = value; }
        void destinationBalance(BigInteger value) { destinationBalance = value; }
        void accountFault(AccountFault value) { accountFault = value; }
        void expired(boolean value) {
            expired = value;
            blockHeightCalls = 0;
        }
        void finalizedFailure(boolean value) { finalizedFailure = value; }
        void transactionBalanceMismatch(boolean value) {
            transactionBalanceMismatch = value;
        }

        private synchronized void respond(HttpExchange exchange) throws IOException {
            String request = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String result;
            if (request.contains("getGenesisHash")) {
                result = quote(genesis);
            } else if (request.contains("getLatestBlockhash")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":100},"
                        + "\"value\":{\"blockhash\":\""
                        + PublicKey.createPubKey(new byte[32]).toBase58()
                        + "\",\"lastValidBlockHeight\":500}}";
            } else if (request.contains("getBlockHeight")) {
                blockHeightCalls++;
                result = expired && blockHeightCalls > 1 ? "501" : "100";
            } else if (request.contains("getBalance")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":100},"
                        + "\"value\":1000000000}";
            } else if (request.contains("sendTransaction")) {
                Matcher matcher = SEND.matcher(request);
                if (!matcher.find()) throw new IOException("signed transaction was absent");
                signedTransaction = Base64.getDecoder().decode(matcher.group(1));
                submissionCalls++;
                BigInteger amount = amount(signedTransaction);
                int opcode = opcode(signedTransaction);
                transactionPreSource = opcode == 15 ? destinationBalance : balance;
                transactionPreDestination = destinationBalance;
                if (finalizedFailure) {
                    // The deterministic node seam retains the failed transaction identity
                    // without applying token-state effects.
                } else if (opcode == 14) {
                    supply = supply.add(amount);
                    balance = balance.add(amount);
                } else if (opcode == 12) {
                    balance = balance.subtract(amount);
                    destinationBalance = destinationBalance.add(amount);
                } else if (opcode == 15) {
                    supply = supply.subtract(amount);
                    destinationBalance = destinationBalance.subtract(amount);
                } else {
                    throw new IOException("unexpected classic SPL instruction");
                }
                result = quote(Transaction.getBase58Id(signedTransaction));
            } else if (request.contains("getSignatureStatuses")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":201},"
                        + "\"value\":[{\"slot\":200,\"confirmations\":null,"
                        + "\"err\":" + (finalizedFailure
                                ? "{\"InstructionError\":[0,{\"Custom\":1}]}"
                                : "null")
                        + ",\"confirmationStatus\":\"finalized\"}]}";
            } else if (request.contains("getTransaction")) {
                result = signedTransaction == null ? "null" : transaction();
            } else if (request.contains("getAccountInfo")) {
                result = accountInfo(request);
            } else {
                throw new IOException("unexpected local RPC method");
            }
            byte[] body = ("{\"jsonrpc\":\"2.0\",\"result\":" + result
                    + ",\"id\":1}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        private String accountInfo(String request) {
            byte[] bytes;
            int space;
            if (request.contains(mint.toBase58())) {
                Mint data = new Mint(mint, authority, supply.longValue(), 2, true, PublicKey.NONE);
                bytes = new byte[Mint.BYTES];
                data.write(bytes, 0);
                space = Mint.BYTES;
            } else if (request.contains(sourceAta.toBase58())
                    && accountFault == AccountFault.SOURCE_MISSING) {
                return missingAccount();
            } else if (request.contains(sourceAta.toBase58())
                    && (balance.signum() > 0 || signedTransaction != null)) {
                PublicKey accountMint = accountFault == AccountFault.SOURCE_WRONG_MINT
                        ? PublicKey.createPubKey(fill((byte) 8)) : mint;
                PublicKey accountOwner = accountFault == AccountFault.SOURCE_WRONG_OWNER
                        ? PublicKey.createPubKey(fill((byte) 6)) : sourceOwner;
                AccountState state = accountFault == AccountFault.SOURCE_FROZEN
                        ? AccountState.Frozen : AccountState.Initialized;
                TokenAccount data = new TokenAccount(
                        sourceAta, accountMint, accountOwner, balance.longValue(), 0, null,
                        state, 0, 0L, 0L, 0, null);
                bytes = new byte[TokenAccount.BYTES];
                data.write(bytes, 0);
                space = TokenAccount.BYTES;
            } else if (request.contains(destinationAta.toBase58())
                    && (destinationBalance.signum() > 0
                        || (signedTransaction != null && opcode(signedTransaction) == 15)
                        || accountFault.name().startsWith("DESTINATION_"))) {
                PublicKey accountMint = accountFault == AccountFault.DESTINATION_WRONG_MINT
                        ? PublicKey.createPubKey(fill((byte) 8)) : mint;
                PublicKey accountOwner = accountFault == AccountFault.DESTINATION_WRONG_OWNER
                        ? PublicKey.createPubKey(fill((byte) 6)) : destinationOwner;
                AccountState state = accountFault == AccountFault.DESTINATION_FROZEN
                        ? AccountState.Frozen : AccountState.Initialized;
                TokenAccount data = new TokenAccount(
                        destinationAta, accountMint, accountOwner,
                        destinationBalance.longValue(), 0, null,
                        state, 0, 0L, 0L, 0, null);
                bytes = new byte[TokenAccount.BYTES];
                data.write(bytes, 0);
                space = TokenAccount.BYTES;
            } else {
                return missingAccount();
            }
            String ownerProgram = (accountFault == AccountFault.SOURCE_WRONG_PROGRAM
                            && request.contains(sourceAta.toBase58()))
                    || (accountFault == AccountFault.DESTINATION_WRONG_PROGRAM
                            && request.contains(destinationAta.toBase58()))
                    ? SolanaMintTransactionCodec.SYSTEM_PROGRAM.toBase58()
                    : SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58();
            return "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":200},"
                    + "\"value\":{\"data\":[\""
                    + Base64.getEncoder().encodeToString(bytes)
                    + "\",\"base64\"],\"executable\":false,\"lamports\":1,"
                    + "\"owner\":\"" + ownerProgram
                    + "\",\"rentEpoch\":0,\"space\":" + space + "}}";
        }

        private static String missingAccount() {
            return "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":100},"
                    + "\"value\":null}";
        }

        private String transaction() {
            int opcode = opcode(signedTransaction);
            String tokenBalances = opcode == 12
                    ? transferTokenBalances()
                    : opcode == 15 ? burnTokenBalances()
                    : "\"preTokenBalances\":[],\"postTokenBalances\":[],";
            return "{\"slot\":200,\"blockTime\":1784472000,"
                    + "\"meta\":{\"err\":" + (finalizedFailure
                            ? "{\"InstructionError\":[0,{\"Custom\":1}]}"
                    : "null") + ",\"fee\":5000,"
                    + "\"preBalances\":[1],\"postBalances\":[1],"
                    + tokenBalances
                    + "\"innerInstructions\":[],\"logMessages\":[],"
                    + "\"rewards\":[],\"loadedAddresses\":{\"readonly\":[],"
                    + "\"writable\":[]},\"computeUnitsConsumed\":100},"
                    + "\"transaction\":[\""
                    + Base64.getEncoder().encodeToString(signedTransaction)
                    + "\",\"base64\"],\"version\":\"legacy\"}";
        }

        private String transferTokenBalances() {
            var accounts = software.sava.core.tx.TransactionSkeleton
                    .deserializeSkeleton(signedTransaction).parseAccounts();
            int sourceIndex = accountIndex(accounts, sourceAta);
            int destinationIndex = accountIndex(accounts, destinationAta);
            BigInteger preSource = transactionBalanceMismatch
                    ? transactionPreSource.subtract(BigInteger.ONE)
                    : transactionPreSource;
            return "\"preTokenBalances\":["
                    + tokenBalance(sourceIndex, sourceOwner, preSource) + "],"
                    + "\"postTokenBalances\":["
                    + tokenBalance(sourceIndex, sourceOwner, balance) + ","
                    + tokenBalance(destinationIndex, destinationOwner,
                            destinationBalance) + "],";
        }

        private String burnTokenBalances() {
            var accounts = software.sava.core.tx.TransactionSkeleton
                    .deserializeSkeleton(signedTransaction).parseAccounts();
            int adminIndex = accountIndex(accounts, destinationAta);
            return "\"preTokenBalances\":["
                    + tokenBalance(adminIndex, destinationOwner, transactionPreSource)
                    + "],\"postTokenBalances\":["
                    + tokenBalance(adminIndex, destinationOwner, destinationBalance)
                    + "],";
        }

        private String tokenBalance(int accountIndex, PublicKey owner, BigInteger amount) {
            return "{\"accountIndex\":" + accountIndex
                    + ",\"mint\":\"" + mint.toBase58()
                    + "\",\"owner\":\"" + owner.toBase58()
                    + "\",\"programId\":\""
                    + SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58()
                    + "\",\"uiTokenAmount\":{\"amount\":\"" + amount
                    + "\",\"decimals\":2,\"uiAmount\":null,"
                    + "\"uiAmountString\":\"" + amount + "\"}}";
        }

        private static int accountIndex(
                software.sava.core.accounts.meta.AccountMeta[] accounts,
                PublicKey address) {
            for (int index = 0; index < accounts.length; index++) {
                if (accounts[index].publicKey().equals(address)) return index;
            }
            throw new IllegalStateException("transaction account was not found");
        }

        private static BigInteger amount(byte[] transaction) {
            var skeleton = software.sava.core.tx.TransactionSkeleton
                    .deserializeSkeleton(transaction);
            var instructions = skeleton.parseLegacyInstructions();
            byte[] data = instructions[instructions.length - 1].copyData();
            byte[] littleEndian = Arrays.copyOfRange(data, 1, 9);
            reverse(littleEndian);
            return new BigInteger(1, littleEndian);
        }

        private static int opcode(byte[] transaction) {
            var instructions = software.sava.core.tx.TransactionSkeleton
                    .deserializeSkeleton(transaction).parseLegacyInstructions();
            return Byte.toUnsignedInt(
                    instructions[instructions.length - 1].copyData()[0]);
        }

        private static String quote(String value) {
            return "\"" + value + "\"";
        }

        private static byte[] fill(byte value) {
            byte[] bytes = new byte[32];
            Arrays.fill(bytes, value);
            return bytes;
        }

        private static void reverse(byte[] bytes) {
            for (int low = 0, high = bytes.length - 1; low < high; low++, high--) {
                byte swap = bytes[low];
                bytes[low] = bytes[high];
                bytes[high] = swap;
            }
        }

        @Override public void close() { server.stop(0); }

        enum AccountFault {
            NONE,
            SOURCE_MISSING,
            SOURCE_WRONG_OWNER,
            SOURCE_WRONG_MINT,
            SOURCE_WRONG_PROGRAM,
            SOURCE_FROZEN,
            DESTINATION_WRONG_OWNER,
            DESTINATION_WRONG_MINT,
            DESTINATION_WRONG_PROGRAM,
            DESTINATION_FROZEN
        }
    }
}
