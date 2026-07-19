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
    void realValidatorMintsThenRedeemsAndBurnsWithRestartReplayAndFinalizedEvidence()
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

        PublicKey redemptionOwner = PublicKey.fromBase58Encoded(runtime.redemptionOwner());
        PublicKey redemptionAta = SolanaMintTransactionCodec.associatedTokenAddress(
                redemptionOwner, mint);
        AccountInfo<byte[]> initialRedemption = observation.getAccountInfo(
                Commitment.FINALIZED, redemptionAta).join();
        assertTrue(initialRedemption == null || initialRedemption.data() == null
                || unsigned(TokenAccount.read(
                        redemptionAta, initialRedemption.data()).amount()).signum() == 0);

        PostgresWalletTransferRepository transfers =
                new PostgresWalletTransferRepository(dataSource);
        WalletReference sourceRef = new WalletReference("synthetic-wallet:USER_1");
        WalletReference adminRef = new WalletReference(
                "synthetic-wallet:ADMIN_REDEMPTION");
        WalletIdentityRegistry wallets = walletRegistry(
                runtime, sourceRef, adminRef);
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
        OperationAcceptance burn = service.accept(new BurnCommand(
                1, PARTICIPANT, TokenQuantity.parse("100", UNIT),
                "real-local-solana-redemption"),
                IdempotencyKey.of("real-solana-redemption"));
        OperationDelivery burnDelivery = delivery(burn.operation().operationId());

        AtomicInteger custodySubmissions = new AtomicInteger();
        AtomicBoolean loseCustodyResponse = new AtomicBoolean(true);
        try (LocalSolanaConfiguredSigner signer = runtime.signer()) {
            SolanaRpcClient custodySubmission = client(runtime.rpc());
            SavaSolanaMintChainAdapter custodyLoss = new SavaSolanaMintChainAdapter(
                    dataSource, custodySubmission, client(runtime.rpc()), base64 -> {
                        custodySubmissions.incrementAndGet();
                        String signature = custodySubmission.sendTransaction(base64, 0).join();
                        if (loseCustodyResponse.compareAndSet(true, false)) {
                            throw new IllegalStateException(
                                    "synthetic response loss after redemption custody");
                        }
                        return signature;
                    }, configuration, CLOCK);
            RedemptionAcceptedDeliveryHandler redemption = redemption(
                    operations, transfers, acceptance, custodyLoss,
                    signer, wallets, sourceRef, adminRef, runtime);
            assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    redemption.handle(burnDelivery).classification());
            OperationId custodyId = new OperationId(jdbc.sql("""
                    SELECT custody_operation_id FROM ethereum_redemption_correlation
                    WHERE burn_operation_id = :burnOperationId
                    """).param("burnOperationId", burn.operation().operationId().value())
                    .query(UUID.class).single());
            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    redemption.handle(walletDelivery(custodyId)).classification());
        }
        assertEquals(1, custodySubmissions.get());

        OperationId custodyId = new OperationId(jdbc.sql("""
                SELECT custody_operation_id FROM ethereum_redemption_correlation
                WHERE burn_operation_id = :burnOperationId
                """).param("burnOperationId", burn.operation().operationId().value())
                .query(UUID.class).single());
        OperationDelivery custodyDelivery = walletDelivery(custodyId);
        try (LocalSolanaConfiguredSigner signer = runtime.signer()) {
            RedemptionAcceptedDeliveryHandler restarted = redemption(
                    new PostgresOperationRepository(dataSource),
                    new PostgresWalletTransferRepository(dataSource), acceptance,
                    new SavaSolanaMintChainAdapter(
                            dataSource, client(runtime.rpc()), client(runtime.rpc()),
                            configuration, CLOCK), signer, wallets,
                    sourceRef, adminRef, runtime);
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    await(restarted, custodyDelivery).classification());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    restarted.handle(custodyDelivery).classification());
        }
        assertEquals(1, custodySubmissions.get());
        AccountInfo<byte[]> afterCustodyMint = observation.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        AccountInfo<byte[]> afterCustodySource = observation.getAccountInfo(
                Commitment.FINALIZED, ata).join();
        AccountInfo<byte[]> afterCustodyAdmin = observation.getAccountInfo(
                Commitment.FINALIZED, redemptionAta).join();
        assertEquals(BigInteger.valueOf(10_000),
                unsigned(Mint.read(mint, afterCustodyMint.data()).supply()));
        assertEquals(BigInteger.ZERO,
                unsigned(TokenAccount.read(ata, afterCustodySource.data()).amount()));
        assertEquals(BigInteger.valueOf(10_000), unsigned(TokenAccount.read(
                redemptionAta, afterCustodyAdmin.data()).amount()));

        AtomicInteger burnSubmissions = new AtomicInteger();
        AtomicBoolean loseBurnResponse = new AtomicBoolean(true);
        try (LocalSolanaConfiguredSigner signer = runtime.signer()) {
            SolanaRpcClient burnSubmission = client(runtime.rpc());
            SavaSolanaMintChainAdapter burnLoss = new SavaSolanaMintChainAdapter(
                    dataSource, burnSubmission, client(runtime.rpc()), base64 -> {
                        burnSubmissions.incrementAndGet();
                        String signature = burnSubmission.sendTransaction(base64, 0).join();
                        if (loseBurnResponse.compareAndSet(true, false)) {
                            throw new IllegalStateException(
                                    "synthetic response loss after ADMIN burn");
                        }
                        return signature;
                    }, configuration, CLOCK);
            RedemptionAcceptedDeliveryHandler redemption = redemption(
                    operations, transfers, acceptance, burnLoss,
                    signer, wallets, sourceRef, adminRef, runtime);
            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    redemption.handle(burnDelivery).classification());
        }
        assertEquals(1, burnSubmissions.get());
        try (LocalSolanaConfiguredSigner signer = runtime.signer()) {
            RedemptionAcceptedDeliveryHandler restarted = redemption(
                    new PostgresOperationRepository(dataSource),
                    new PostgresWalletTransferRepository(dataSource), acceptance,
                    new SavaSolanaMintChainAdapter(
                            dataSource, client(runtime.rpc()), client(runtime.rpc()),
                            configuration, CLOCK), signer, wallets,
                    sourceRef, adminRef, runtime);
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    await(restarted, burnDelivery).classification());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    restarted.handle(burnDelivery).classification());
        }
        assertEquals(1, burnSubmissions.get());

        AccountInfo<byte[]> finalBurnMint = observation.getAccountInfo(
                Commitment.FINALIZED, mint).join();
        AccountInfo<byte[]> finalBurnSource = observation.getAccountInfo(
                Commitment.FINALIZED, ata).join();
        AccountInfo<byte[]> finalBurnAdmin = observation.getAccountInfo(
                Commitment.FINALIZED, redemptionAta).join();
        assertEquals(BigInteger.ZERO,
                unsigned(Mint.read(mint, finalBurnMint.data()).supply()));
        assertEquals(BigInteger.ZERO,
                unsigned(TokenAccount.read(ata, finalBurnSource.data()).amount()));
        assertEquals(BigInteger.ZERO, unsigned(TokenAccount.read(
                redemptionAta, finalBurnAdmin.data()).amount()));
        assertEquals(OperationState.COMPLETED, operations.findById(
                burn.operation().operationId()).orElseThrow().state());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM solana_mint_observation
                WHERE operation_id = :operationId
                  AND effect_kind = 'TRANSFER'
                  AND observation_status = 'CONFIRMED'
                  AND mint_delta = 0
                  AND source_delta = 10000
                  AND destination_delta = 10000
                """).param("operationId", custodyId.value())
                .query(Integer.class).single());
        assertEquals(List.of("FEE_PAYER", "BURN_AUTHORITY"), jdbc.sql("""
                SELECT key_role FROM solana_mint_signature
                WHERE operation_id = :operationId ORDER BY signer_order
                """).param("operationId", burn.operation().operationId().value())
                .query(String.class).list());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM solana_mint_observation
                WHERE operation_id = :operationId
                  AND effect_kind = 'BURN'
                  AND observation_status = 'CONFIRMED'
                  AND commitment = 'finalized'
                  AND expected_instructions
                  AND observed_mint_supply = 0
                  AND observed_source_balance = 0
                  AND transaction_pre_source_balance = 10000
                  AND transaction_post_source_balance = 0
                  AND mint_delta = 10000
                  AND source_delta = 10000
                """).param("operationId", burn.operation().operationId().value())
                .query(Integer.class).single());
        assertEquals(1, jdbc.sql("""
                SELECT count(*) FROM ethereum_redemption_correlation
                WHERE burn_operation_id = :operationId
                  AND custody_operation_id = :custodyId
                  AND correlation_status = 'CONSUMED'
                  AND custody_evidence_ref IS NOT NULL
                  AND consumed_by_burn_attempt_id IS NOT NULL
                  AND consumed_at IS NOT NULL
                """).param("operationId", burn.operation().operationId().value())
                .param("custodyId", custodyId.value()).query(Integer.class).single());
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

    private static RedemptionAcceptedDeliveryHandler redemption(
            PostgresOperationRepository operations,
            PostgresWalletTransferRepository transfers,
            WalletTransferAcceptanceService acceptance,
            SavaSolanaMintChainAdapter chain,
            LocalSolanaConfiguredSigner signer,
            WalletIdentityRegistry wallets,
            WalletReference source,
            WalletReference admin,
            RuntimeConfiguration runtime) {
        SigningAuthorityService signing = signingService(signer);
        var burn = new TokenOperationAcceptedDeliveryHandler(
                operations, chain, signing, CLOCK::instant, ids(),
                new TokenOperationAcceptedDeliveryHandler.Policy(
                        new KeyAlias("local-solana:burn-authority"),
                        runtime.redemptionOwner(), Duration.ofMinutes(5),
                        "local-solana-mint-v1", OperationKind.BURN,
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

    private static DeliveryOutcome await(
            RedemptionAcceptedDeliveryHandler handler,
            OperationDelivery delivery) throws Exception {
        DeliveryOutcome outcome = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            outcome = handler.handle(delivery);
            if (outcome.classification() == DeliveryOutcome.Classification.DELIVERED) {
                return outcome;
            }
            Thread.sleep(100);
        }
        return outcome;
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
            WalletReference adminRef) {
        var source = new WalletIdentityRegistry.WalletIdentity(
                sourceRef, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.SOLANA, runtime.destinationOwner(),
                new KeyAlias("local-solana:transfer-authority"),
                "registry-v1", "transfer-v1",
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER),
                WalletIdentityRegistry.Status.ENABLED);
        var admin = new WalletIdentityRegistry.WalletIdentity(
                adminRef, Set.of(new WalletReference("synthetic-wallet:ADMIN")),
                WalletIdentityRegistry.OwnerCategory.ADMIN,
                SettlementNetwork.SOLANA, runtime.redemptionOwner(),
                new KeyAlias("local-solana:burn-authority"),
                "registry-v1", "burn-v1",
                Set.of(WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY,
                        WalletIdentityRegistry.Purpose.BURN_AUTHORITY),
                WalletIdentityRegistry.Status.ENABLED);
        return new WalletIdentityRegistry() {
            @Override public WalletIdentity resolve(WalletReference reference) {
                if (sourceRef.equals(reference)) return source;
                if (adminRef.equals(reference)) return admin;
                throw new IllegalArgumentException("unknown real-gate wallet");
            }
            @Override public List<WalletIdentity> identities() {
                return List.of(source, admin);
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
            String redemptionOwner,
            String feePayer,
            String mintAuthority,
            Path runtimeRoot,
            Path feePayerFile,
            Path mintAuthorityFile,
            Path burnAuthorityFile) {

        static RuntimeConfiguration environment() {
            Path root = Path.of(System.getenv().getOrDefault(
                    "LOCAL_SOLANA_RUNTIME_ROOT", ".solana-runtime"));
            return new RuntimeConfiguration(
                    URI.create(required("LOCAL_SOLANA_RPC_URI")),
                    required("LOCAL_SOLANA_CLUSTER_IDENTITY"),
                    required("LOCAL_SOLANA_MINT_ADDRESS"),
                    required("LOCAL_SOLANA_DESTINATION_OWNER"),
                    required("LOCAL_SOLANA_TRANSFER_DESTINATION_OWNER"),
                    required("LOCAL_SOLANA_REDEMPTION_OWNER"),
                    required("LOCAL_SOLANA_FEE_PAYER_PUBLIC_KEY"),
                    required("LOCAL_SOLANA_MINT_AUTHORITY_PUBLIC_KEY"), root,
                    root.resolve("keys/fee-payer.json"),
                    root.resolve("keys/admin-mint-authority.json"),
                    root.resolve("keys/admin-redemption-owner.json"));
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
                                    destinationOwner, "transfer-v1"),
                            new LocalSolanaConfiguredSigner.ConfiguredKey(
                                    new KeyAlias("local-solana:burn-authority"),
                                    SigningRequest.KeyRole.BURN_AUTHORITY,
                                    burnAuthorityFile, redemptionOwner, "burn-v1"))));
        }

        SavaSolanaMintChainAdapter.Configuration adapterConfiguration() {
            return new SavaSolanaMintChainAdapter.Configuration(
                    rpc, cluster, mint, destinationOwner,
                    feePayer, "local-solana:fee-payer", "fee-v1",
                    mintAuthority, "local-solana:mint-authority", "authority-v1",
                    transferDestinationOwner, "local-solana:transfer-authority",
                    "transfer-v1",
                    redemptionOwner, "local-solana:burn-authority", "burn-v1",
                    "registry-v1",
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
