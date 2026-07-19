package io.github.johnwhitton.digitalbanking.solana.sava;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigInteger;
import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.MintCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.WalletTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
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
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresWalletTransferRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalSolanaConfiguredSigner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

@EnabledIfEnvironmentVariable(named = "LOCAL_SOLANA_REAL_GATE", matches = "true")
class SavaSolanaPrivateValidatorIntegrationTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-07-19T14:00:00Z"), ZoneOffset.UTC);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-real-solana", "participant-real-solana");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer("postgres:17.10-alpine3.23")
                .withDatabaseName("digital_banking")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(postgres.getJdbcUrl());
        pool.setUsername(postgres.getUsername());
        pool.setPassword(postgres.getPassword());
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
    void realValidatorMintsThenTransfersWithRestartReplayAndExactFinalizedEvidence()
            throws Exception {
        RuntimeConfiguration runtime = RuntimeConfiguration.environment();
        SolanaRpcClient submission = client(runtime.rpc());
        SolanaRpcClient observation = client(runtime.rpc());
        PublicKey mint = PublicKey.fromBase58Encoded(runtime.mint());
        PublicKey owner = PublicKey.fromBase58Encoded(runtime.destinationOwner());
        PublicKey ata = SolanaMintTransactionCodec.associatedTokenAddress(owner, mint);

        AccountInfo<byte[]> initialMint = observation.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        assertEquals(BigInteger.ZERO,
                unsigned(Mint.read(mint, initialMint.data()).supply()));
        AccountInfo<byte[]> initialAta = observation.getAccountInfo(
                Commitment.FINALIZED, ata).join();
        assertTrue(initialAta == null || initialAta.data() == null
                || unsigned(TokenAccount.read(ata, initialAta.data()).amount()).signum() == 0);

        PostgresOperationRepository operations = new PostgresOperationRepository(dataSource);
        SavaSolanaMintChainAdapter.Configuration configuration = runtime.adapterConfiguration();
        AtomicBoolean loseResponse = new AtomicBoolean(true);
        AtomicInteger submissions = new AtomicInteger();
        LocalSolanaConfiguredSigner firstSigner = runtime.signer();
        SavaSolanaMintChainAdapter firstChain = new SavaSolanaMintChainAdapter(
                dataSource, submission, observation, base64 -> {
                    submissions.incrementAndGet();
                    String signature = submission.sendTransaction(base64, 0).join();
                    if (loseResponse.compareAndSet(true, false)) {
                        throw new IllegalStateException(
                                "synthetic response loss after private-validator acceptance");
                    }
                    return signature;
                }, configuration, CLOCK);
        TokenOperationAcceptedDeliveryHandler firstHandler = handler(
                operations, firstChain, firstSigner, runtime);

        TokenOperationService service = new TokenOperationService(
                operations, CLOCK::instant, ids(),
                (canonical, participant) -> new EvidenceRef(
                        "participant:test:real-solana-acceptance:" + UUID.randomUUID()));
        OperationAcceptance accepted = service.accept(new MintCommand(
                1, PARTICIPANT, TokenQuantity.parse("100", UNIT),
                "real-local-solana-mint"), IdempotencyKey.of("real-solana-mint"));
        OperationAcceptance replay = service.accept(new MintCommand(
                1, PARTICIPANT, TokenQuantity.parse("100", UNIT),
                "real-local-solana-mint"), IdempotencyKey.of("real-solana-mint"));
        assertTrue(replay.replayed());
        assertEquals(accepted.operation().operationId(), replay.operation().operationId());
        OperationDelivery delivery = delivery(accepted.operation().operationId());

        assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                firstHandler.handle(delivery).classification());
        assertEquals(1, submissions.get());
        firstSigner.close();

        try (LocalSolanaConfiguredSigner restartedSigner = runtime.signer()) {
            TokenOperationAcceptedDeliveryHandler restarted = handler(
                    new PostgresOperationRepository(dataSource),
                    new SavaSolanaMintChainAdapter(
                            dataSource, client(runtime.rpc()), client(runtime.rpc()),
                            configuration, CLOCK), restartedSigner, runtime);
            DeliveryOutcome outcome = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                outcome = restarted.handle(delivery);
                if (outcome.classification() == DeliveryOutcome.Classification.DELIVERED) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    outcome == null ? null : outcome.classification());
        }

        assertEquals(1, submissions.get());
        AccountInfo<byte[]> finalMint = observation.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        AccountInfo<byte[]> finalAta = observation.getAccountInfo(
                Commitment.FINALIZED, ata).join();
        assertEquals(BigInteger.valueOf(10_000),
                unsigned(Mint.read(mint, finalMint.data()).supply()));
        assertEquals(BigInteger.valueOf(10_000),
                unsigned(TokenAccount.read(ata, finalAta.data()).amount()));
        assertEquals(OperationState.COMPLETED, operations.findById(
                accepted.operation().operationId()).orElseThrow().state());
        assertEquals(FinalityStatus.REACHED, operations.findById(
                accepted.operation().operationId()).orElseThrow()
                .finalities().get(FinalityType.BLOCKCHAIN).status());
        assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                firstHandler.handle(delivery).classification());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM solana_mint_attempt
                WHERE operation_id = :operationId
                  AND submit_fence = 1
                  AND amount_atomic = 10000
                  AND attempt_status = 'CONFIRMED'
                """).param("operationId", accepted.operation().operationId().value())
                .query(Integer.class).single());

        PublicKey transferDestination = PublicKey.fromBase58Encoded(
                runtime.transferDestinationOwner());
        PublicKey transferDestinationAta = SolanaMintTransactionCodec.associatedTokenAddress(
                transferDestination, mint);
        AccountInfo<byte[]> initialTransferDestination = observation.getAccountInfo(
                Commitment.FINALIZED, transferDestinationAta).join();
        assertTrue(initialTransferDestination == null
                || initialTransferDestination.data() == null
                || unsigned(TokenAccount.read(
                        transferDestinationAta,
                        initialTransferDestination.data()).amount()).signum() == 0);

        PostgresWalletTransferRepository transfers =
                new PostgresWalletTransferRepository(dataSource);
        WalletReference sourceRef = new WalletReference("synthetic-wallet:USER_1");
        WalletReference destinationRef = new WalletReference("synthetic-wallet:USER_2");
        WalletIdentityRegistry wallets = walletRegistry(
                runtime, sourceRef, destinationRef);
        WalletTransferAcceptanceService acceptance =
                new WalletTransferAcceptanceService(
                        transfers, (asset, unit, version) ->
                                asset.equals(UNIT.assetId())
                                        && unit.equals(UNIT.unitId())
                                        && version == UNIT.version()
                                        ? Optional.of(UNIT) : Optional.empty(),
                        wallets, CLOCK::instant, ids(), transferIds(),
                        new WalletTransferAcceptanceService.Policy(
                                runtime.mint(), "local-solana-token-v1",
                                "local-solana-mint-v1"));
        var transfer = acceptance.accept(
                PARTICIPANT, new IdempotencyKey("real-solana-transfer"),
                new WalletTransferAcceptanceService.Request(
                        "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                        sourceRef, destinationRef));
        assertTrue(acceptance.accept(
                PARTICIPANT, new IdempotencyKey("real-solana-transfer"),
                new WalletTransferAcceptanceService.Request(
                        "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                        sourceRef, destinationRef)).replayed());
        AtomicInteger transferSubmissions = new AtomicInteger();
        AtomicBoolean loseTransferResponse = new AtomicBoolean(true);
        OperationDelivery transferDelivery = walletDelivery(
                transfer.operation().operationId());
        try (LocalSolanaConfiguredSigner transferSigner = runtime.signer()) {
            SolanaRpcClient transferSubmission = client(runtime.rpc());
            SavaSolanaMintChainAdapter transferChain = new SavaSolanaMintChainAdapter(
                    dataSource, transferSubmission, client(runtime.rpc()), base64 -> {
                        transferSubmissions.incrementAndGet();
                        String signature = transferSubmission.sendTransaction(base64, 0).join();
                        if (loseTransferResponse.compareAndSet(true, false)) {
                            throw new IllegalStateException(
                                    "synthetic response loss after transfer acceptance");
                        }
                        return signature;
                    }, configuration, CLOCK);
            WalletTransferAcceptedDeliveryHandler transferHandler =
                    new WalletTransferAcceptedDeliveryHandler(
                            transfers, transferChain, signingService(transferSigner), wallets,
                            CLOCK::instant, Duration.ofMinutes(5));
            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    transferHandler.handle(transferDelivery).classification());
        }
        assertEquals(1, transferSubmissions.get());
        try (LocalSolanaConfiguredSigner transferSigner = runtime.signer()) {
            WalletTransferAcceptedDeliveryHandler transferHandler =
                    new WalletTransferAcceptedDeliveryHandler(
                            new PostgresWalletTransferRepository(dataSource),
                            new SavaSolanaMintChainAdapter(
                                    dataSource, client(runtime.rpc()), client(runtime.rpc()),
                                    configuration, CLOCK),
                            signingService(transferSigner), wallets,
                            CLOCK::instant, Duration.ofMinutes(5));
            DeliveryOutcome outcome = null;
            long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
            while (System.nanoTime() < deadline) {
                outcome = transferHandler.handle(transferDelivery);
                if (outcome.classification() == DeliveryOutcome.Classification.DELIVERED) {
                    break;
                }
                Thread.sleep(100);
            }
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    outcome == null ? null : outcome.classification());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    transferHandler.handle(transferDelivery).classification());
        }
        assertEquals(1, transferSubmissions.get());
        AccountInfo<byte[]> afterTransferMint = observation.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        AccountInfo<byte[]> afterTransferSource = observation.getAccountInfo(
                Commitment.FINALIZED, ata).join();
        AccountInfo<byte[]> afterTransferDestination = observation.getAccountInfo(
                Commitment.FINALIZED, transferDestinationAta).join();
        assertEquals(BigInteger.valueOf(10_000),
                unsigned(Mint.read(mint, afterTransferMint.data()).supply()));
        assertEquals(BigInteger.ZERO,
                unsigned(TokenAccount.read(ata, afterTransferSource.data()).amount()));
        assertEquals(BigInteger.valueOf(10_000), unsigned(TokenAccount.read(
                transferDestinationAta, afterTransferDestination.data()).amount()));
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM solana_mint_observation
                WHERE operation_id = :operationId
                  AND effect_kind = 'TRANSFER'
                  AND observation_status = 'CONFIRMED'
                  AND mint_delta = 0
                  AND source_delta = 10000
                  AND destination_delta = 10000
                """).param("operationId", transfer.operation().operationId().value())
                .query(Integer.class).single());

    }

    private static TokenOperationAcceptedDeliveryHandler handler(
            PostgresOperationRepository operations,
            SavaSolanaMintChainAdapter chain,
            LocalSolanaConfiguredSigner signer,
            RuntimeConfiguration runtime) {
        return new TokenOperationAcceptedDeliveryHandler(
                operations, chain, signingService(signer), CLOCK::instant, ids(),
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        new KeyAlias("local-solana:fee-payer"), runtime.feePayer(),
                        Duration.ofMinutes(5), "local-solana-mint-v1",
                        OperationKind.MINT, SigningRequest.Action.MINT,
                        SigningRequest.KeyRole.FEE_PAYER, SettlementNetwork.SOLANA,
                        SigningRequest.Mode.SOLANA_MESSAGE,
                        SigningRequest.Algorithm.ED25519));
    }

    private static SigningAuthorityService signingService(
            LocalSolanaConfiguredSigner signer) {
        SigningAuthorizationPort authorization = request ->
                new SigningAuthorizationPort.Authorized(new EvidenceRef(
                        "internal:test:real-solana-authorization:"
                                + request.requestId().value()));
        SigningIdentityGenerator identities = new SigningIdentityGenerator() {
            @Override public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }
            @Override public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("real-solana-provider:" + UUID.randomUUID());
            }
        };
        return new SigningAuthorityService(
                new PostgresSigningRequestRepository(dataSource), signer,
                authorization, signer, identities, CLOCK::instant);
    }

    private static OperationDelivery delivery(OperationId operationId) {
        UUID event = jdbc.sql("""
                SELECT event_id FROM operation_outbox WHERE operation_id = :operationId
                """).param("operationId", operationId.value()).query(UUID.class).single();
        return new OperationDelivery(
                event, operationId, 1, 1, UUID.randomUUID(), "real-solana-worker", 1);
    }

    private static OperationDelivery walletDelivery(OperationId operationId) {
        UUID event = jdbc.sql("""
                SELECT event_id FROM operation_outbox
                WHERE wallet_transfer_id = :operationId
                """).param("operationId", operationId.value())
                .query(UUID.class).single();
        return new OperationDelivery(
                event, operationId.value(),
                WalletTransferAcceptedDeliveryHandler.EVENT_TYPE,
                1, 1, UUID.randomUUID(), "real-solana-transfer-worker", 1);
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
            RuntimeConfiguration runtime,
            WalletReference sourceRef,
            WalletReference destinationRef) {
        var source = new WalletIdentityRegistry.WalletIdentity(
                sourceRef, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.SOLANA, runtime.destinationOwner(),
                new KeyAlias("local-solana:transfer-authority"),
                "registry-v1", "transfer-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
        var destination = new WalletIdentityRegistry.WalletIdentity(
                destinationRef, Set.of(),
                WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.SOLANA, runtime.transferDestinationOwner(),
                new KeyAlias("local-solana:user-2-public"),
                "registry-v1", "user-2-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
        return new WalletIdentityRegistry() {
            @Override public WalletIdentity resolve(WalletReference reference) {
                if (sourceRef.equals(reference)) return source;
                if (destinationRef.equals(reference)) return destination;
                throw new IllegalArgumentException("unknown real-gate wallet");
            }
            @Override public List<WalletIdentity> identities() {
                return List.of(source, destination);
            }
        };
    }

    private static SolanaRpcClient client(URI endpoint) {
        return SolanaRpcClient.build().endpoint(endpoint)
                .requestTimeout(Duration.ofSeconds(5))
                .defaultCommitment(Commitment.CONFIRMED).createClient();
    }

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }

    private record RuntimeConfiguration(
            URI rpc,
            String cluster,
            String mint,
            String destinationOwner,
            String transferDestinationOwner,
            String feePayer,
            String mintAuthority,
            Path runtimeRoot,
            Path feePayerFile,
            Path mintAuthorityFile) {

        static RuntimeConfiguration environment() {
            Path root = Path.of(System.getenv().getOrDefault(
                    "LOCAL_SOLANA_RUNTIME_ROOT", ".solana-runtime"));
            return new RuntimeConfiguration(
                    URI.create(required("LOCAL_SOLANA_RPC_URI")),
                    required("LOCAL_SOLANA_CLUSTER_IDENTITY"),
                    required("LOCAL_SOLANA_MINT_ADDRESS"),
                    required("LOCAL_SOLANA_DESTINATION_OWNER"),
                    required("LOCAL_SOLANA_TRANSFER_DESTINATION_OWNER"),
                    required("LOCAL_SOLANA_FEE_PAYER_PUBLIC_KEY"),
                    required("LOCAL_SOLANA_MINT_AUTHORITY_PUBLIC_KEY"), root,
                    root.resolve("keys/fee-payer.json"),
                    root.resolve("keys/admin-mint-authority.json"));
        }

        LocalSolanaConfiguredSigner signer() {
            return new LocalSolanaConfiguredSigner(
                    new LocalSolanaConfiguredSigner.Configuration(runtimeRoot, List.of(
                            new LocalSolanaConfiguredSigner.ConfiguredKey(
                                    new KeyAlias("local-solana:fee-payer"),
                                    SigningRequest.KeyRole.FEE_PAYER, feePayerFile,
                                    feePayer, "fee-v1"),
                            new LocalSolanaConfiguredSigner.ConfiguredKey(
                                    new KeyAlias("local-solana:mint-authority"),
                                    SigningRequest.KeyRole.MINT_AUTHORITY,
                                    mintAuthorityFile, mintAuthority, "authority-v1"),
                            new LocalSolanaConfiguredSigner.ConfiguredKey(
                                    new KeyAlias("local-solana:transfer-authority"),
                                    SigningRequest.KeyRole.TRANSFER_AUTHORITY,
                                    runtimeRoot.resolve("keys/user-1.json"),
                                    destinationOwner, "transfer-v1"))));
        }

        SavaSolanaMintChainAdapter.Configuration adapterConfiguration() {
            return new SavaSolanaMintChainAdapter.Configuration(
                    rpc, cluster, mint, destinationOwner,
                    feePayer, "local-solana:fee-payer", "fee-v1",
                    mintAuthority, "local-solana:mint-authority", "authority-v1",
                    transferDestinationOwner, "local-solana:transfer-authority",
                    "transfer-v1",
                    UNIT.assetId(), UNIT.unitId(), UNIT.version(), UNIT.scale(),
                    "local-solana-mint-v1",
                    SavaSolanaMintChainAdapter.CommitmentLevel.CONFIRMED,
                    SavaSolanaMintChainAdapter.CommitmentLevel.FINALIZED,
                    BigInteger.valueOf(1_000_000), BigInteger.valueOf(100_000),
                    Duration.ofSeconds(5));
        }

        private static String required(String name) {
            String value = System.getenv(name);
            if (value == null || value.isBlank()) {
                throw new IllegalStateException(name + " is required for the real local gate");
            }
            return value;
        }
    }
}
