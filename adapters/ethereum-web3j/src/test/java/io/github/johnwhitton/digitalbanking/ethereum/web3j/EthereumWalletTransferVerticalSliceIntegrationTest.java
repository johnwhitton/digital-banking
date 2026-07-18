package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferAcceptanceService;
import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.command.BurnCommand;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.RedemptionAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.WalletTransferAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.OperationRepository;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.TransferIdentityGenerator;
import io.github.johnwhitton.digitalbanking.application.port.WalletIdentityRegistry;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
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
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalConfiguredSigner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.web3j.utils.Numeric;

class EthereumWalletTransferVerticalSliceIntegrationTest {

    private static final Instant NOW = Instant.parse("2026-07-18T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final WalletReference SOURCE =
            new WalletReference("synthetic-wallet:USER_WALLET_1");
    private static final WalletReference DESTINATION =
            new WalletReference("synthetic-wallet:USER_WALLET_2");
    private static final WalletReference ADMIN_REDEMPTION =
            new WalletReference("synthetic-wallet:ADMIN_REDEMPTION");
    private static final WalletReference ADMIN =
            new WalletReference("synthetic-wallet:ADMIN");
    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static EthereumMintVerticalSliceIntegrationTest.AnvilNode anvil;

    @BeforeAll
    static void startInfrastructure() throws Exception {
        postgres = new PostgreSQLContainer("postgres:17.10-alpine3.23")
                .withDatabaseName("wallet_transfer_vertical")
                .withUsername("wallet_transfer_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        HikariConfig pool = new HikariConfig();
        pool.setJdbcUrl(postgres.getJdbcUrl());
        pool.setUsername(postgres.getUsername());
        pool.setPassword(postgres.getPassword());
        pool.setMaximumPoolSize(12);
        dataSource = new HikariDataSource(pool);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        anvil = EthereumMintVerticalSliceIntegrationTest.AnvilNode.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (anvil != null) anvil.close();
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
        jdbc.sql("DELETE FROM wallet_transfer_handler_inbox").update();
        jdbc.sql("DELETE FROM operation_outbox").update();
        jdbc.sql("DELETE FROM wallet_transfer_finality").update();
        jdbc.sql("DELETE FROM wallet_transfer_transition").update();
        jdbc.sql("DELETE FROM wallet_transfer_operation").update();
        jdbc.sql("DELETE FROM signing_attempt_evidence").update();
        jdbc.sql("DELETE FROM signing_attempt").update();
        jdbc.sql("DELETE FROM signing_request_approval_evidence").update();
        jdbc.sql("DELETE FROM signing_transition").update();
        jdbc.sql("DELETE FROM signing_request").update();
        jdbc.sql("DELETE FROM ethereum_nonce_cursor").update();
        jdbc.sql("DELETE FROM operation_finality_evidence").update();
        jdbc.sql("DELETE FROM operation_finality").update();
        jdbc.sql("DELETE FROM operation_attempt").update();
        jdbc.sql("DELETE FROM operation_transition_evidence").update();
        jdbc.sql("DELETE FROM operation_transition").update();
        jdbc.sql("DELETE FROM operation_idempotency").update();
        jdbc.sql("DELETE FROM token_operation").update();
    }

    @Test
    void transfersExactUserBalanceAndSupplyIsUnchanged() throws Exception {
        try (Scenario scenario = Scenario.create(false)) {
            WalletTransferRepository crashAfterFinality = new WalletTransferRepository() {
                private boolean crash = true;

                @Override public Acceptance accept(WalletTransferOperation operation) {
                    return scenario.repository.accept(operation);
                }
                @Override public Acceptance acceptRedemption(
                        WalletTransferOperation operation, OperationId burnOperationId) {
                    return scenario.repository.acceptRedemption(operation, burnOperationId);
                }
                @Override public Optional<WalletTransferOperation> findByIdempotency(
                        ParticipantScope participant, String keyDigest) {
                    return scenario.repository.findByIdempotency(participant, keyDigest);
                }
                @Override public Optional<WalletTransferOperation> findById(
                        OperationId operationId) {
                    return scenario.repository.findById(operationId);
                }
                @Override public Optional<WalletTransferOperation> findRedemptionByBurn(
                        OperationId burnOperationId) {
                    return scenario.repository.findRedemptionByBurn(burnOperationId);
                }
                @Override public StartResult startDelivery(
                        UUID deliveryId, OperationId operationId, Instant startedAt) {
                    return scenario.repository.startDelivery(
                            deliveryId, operationId, startedAt);
                }
                @Override public void save(
                        WalletTransferOperation operation, long expectedVersion) {
                    scenario.repository.save(operation, expectedVersion);
                    if (crash && operation.status()
                            == WalletTransferOperation.Status.CHAIN_FINALITY_REACHED) {
                        crash = false;
                        throw new IllegalStateException(
                                "synthetic crash after durable blockchain finality");
                    }
                }
            };
            WalletTransferAcceptedDeliveryHandler crashingHandler =
                    new WalletTransferAcceptedDeliveryHandler(
                            crashAfterFinality, scenario.chain, scenario.signing,
                            scenario.signer, CLOCK::instant, Duration.ofMinutes(5));

            assertThrows(IllegalStateException.class,
                    () -> crashingHandler.handle(scenario.delivery));
            assertEquals(WalletTransferOperation.Status.CHAIN_FINALITY_REACHED,
                    scenario.repository.findById(scenario.delivery.operationId())
                            .orElseThrow().status());
            DeliveryOutcome result = scenario.handler.handle(scenario.delivery);

            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    result.classification());
            assertEquals(BigInteger.ZERO, scenario.balanceOf(scenario.sourceAddress));
            assertEquals(BigInteger.valueOf(10_000),
                    scenario.balanceOf(scenario.destinationAddress));
            assertEquals(BigInteger.valueOf(10_000), scenario.totalSupply());
            assertEquals(1, scenario.submissionCalls[0]);
            assertEquals(1, jdbc.sql("""
                    SELECT event_count FROM ethereum_wallet_transfer_observation
                    WHERE observation_status = 'CONFIRMED'
                    """).query(Integer.class).single());
            assertEquals(scenario.sourceAddress, jdbc.sql("""
                    SELECT source_address FROM ethereum_wallet_transfer_attempt
                    """).query(String.class).single());
            assertEquals(1L, jdbc.sql("SELECT count(*) FROM signing_request")
                    .query(Long.class).single());

            var retained = scenario.repository.findById(
                    scenario.delivery.operationId()).orElseThrow();
            assertEquals(FinalityStatus.REACHED, retained.finalityHistories()
                    .get(FinalityType.BLOCKCHAIN).getLast().status());
            assertTrue(retained.finalityHistories().entrySet().stream()
                    .filter(entry -> entry.getKey() != FinalityType.BLOCKCHAIN)
                    .allMatch(entry -> entry.getValue().getLast().status()
                            == FinalityStatus.NOT_ASSESSED));
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    scenario.handler.handle(scenario.delivery).classification());
            assertEquals(1, scenario.submissionCalls[0]);

            jdbc.sql("""
                    UPDATE ethereum_wallet_transfer_attempt
                    SET quantity_atomic = 9999
                    """).update();
            assertThrows(IllegalStateException.class,
                    () -> scenario.chain.prepare(
                            scenario.delivery.deliveryId(), retained));
        }
    }

    @Test
    void lostSubmissionResponseIsInquiredAndNeverResubmitted() throws Exception {
        try (Scenario scenario = Scenario.create(true)) {
            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    scenario.handler.handle(scenario.delivery).classification());
            assertEquals(1, scenario.submissionCalls[0]);

            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    scenario.handler.handle(scenario.delivery).classification());
            assertEquals(1, scenario.submissionCalls[0]);
            assertEquals(BigInteger.valueOf(10_000),
                    scenario.balanceOf(scenario.destinationAddress));
        }
    }

    @Test
    void transfersToRedemptionCustodyThenBurnsExactAdminBalanceAndSupply()
            throws Exception {
        try (Scenario scenario = Scenario.create(false)) {
            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    scenario.handler.handle(scenario.delivery).classification());
            var admin = scenario.signer.resolve(ADMIN_REDEMPTION);
            anvil.setBalance(admin.normalizedAddress(),
                    new BigInteger("100000000000000000000"));
            grantBurner(scenario.submission, scenario.caller,
                    scenario.contract, admin.normalizedAddress());

            OperationRepository operations = new PostgresOperationRepository(dataSource);
            TokenOperationService tokens = new TokenOperationService(
                    operations, CLOCK::instant, ids(),
                    (command, participant) -> new EvidenceRef(
                            "internal:test:redemption-burn:" + command.sha256()));
            var burn = tokens.accept(
                    new BurnCommand(1,
                            new ParticipantScope("tenant-a", "participant-a"),
                            io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity
                                    .ofAtomic(BigInteger.valueOf(10_000), UNIT),
                            "local-redemption-integration"),
                    new IdempotencyKey("redemption-burn-" + UUID.randomUUID()))
                    .operation();
            OperationDelivery burnDelivery = PostgresOperationDeliveryQueue
                    .localEthereumDemo(dataSource)
                    .claim("redemption-burn-test", NOW, Duration.ofSeconds(30), 1)
                    .deliveries().getFirst();
            Web3j burnSubmission = anvil.newClient();
            Web3j burnObservation = anvil.newClient();
            int[] burnSubmissionCalls = {0};
            try (Web3jEthereumBurnChainAdapter burnChain =
                    new Web3jEthereumBurnChainAdapter(
                            dataSource, burnSubmission, burnObservation,
                            raw -> {
                                burnSubmissionCalls[0]++;
                                EthSendTransaction response = burnSubmission
                                        .ethSendRawTransaction(raw).send();
                                if (burnSubmissionCalls[0] == 1 && !response.hasError()) {
                                    throw new IOException(
                                            "synthetic burn response loss after acceptance");
                                }
                                return response;
                            },
                            () -> {
                                if (!burnSubmission.ethChainId().send().getChainId()
                                        .equals(BigInteger.valueOf(31_337))) {
                                    throw new IllegalStateException("unexpected burn chain");
                                }
                            },
                            new Web3jEthereumBurnChainAdapter.Configuration(
                                    31_337L, scenario.contract, admin.normalizedAddress(),
                                    admin.keyReference().value(),
                                    admin.registryVersion(), admin.keyVersion(),
                                    BigInteger.valueOf(1_000_000_000L),
                                    BigInteger.valueOf(2_000_000_000L),
                                    BigInteger.valueOf(180_000), 1,
                                    UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                                    UNIT.scale(), "local-ethereum-redemption-v1"), CLOCK)) {
                var burnHandler = new TokenOperationAcceptedDeliveryHandler(
                        operations, burnChain, scenario.signing, CLOCK::instant, ids(),
                        new TokenOperationAcceptedDeliveryHandler.Policy(
                                admin.keyReference(), admin.normalizedAddress(),
                                Duration.ofMinutes(5),
                                "local-ethereum-redemption-v1", OperationKind.BURN,
                                SigningRequest.Action.BURN,
                                SigningRequest.KeyRole.BURN_AUTHORITY));
                var redemption = new RedemptionAcceptedDeliveryHandler(
                        operations, scenario.repository, scenario.acceptance,
                        burnHandler, scenario.handler, DESTINATION, ADMIN_REDEMPTION);

                assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                        redemption.handle(burnDelivery).classification());
                OperationDelivery custodyDelivery = PostgresOperationDeliveryQueue
                        .walletTransfersOnly(dataSource)
                        .claim("redemption-custody-test", NOW,
                                Duration.ofSeconds(30), 1)
                        .deliveries().getFirst();
                assertEquals(DeliveryOutcome.Classification.DELIVERED,
                        redemption.handle(custodyDelivery).classification());
                assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                        redemption.handle(burnDelivery).classification());
                DeliveryOutcome burnOutcome = redemption.handle(burnDelivery);
                assertEquals(DeliveryOutcome.Classification.DELIVERED,
                        burnOutcome.classification(), burnOutcome.toString());
                assertEquals(1, burnSubmissionCalls[0]);
            }

            assertEquals(BigInteger.ZERO,
                    scenario.balanceOf(scenario.destinationAddress));
            assertEquals(BigInteger.ZERO,
                    scenario.balanceOf(admin.normalizedAddress()));
            assertEquals(BigInteger.ZERO, scenario.totalSupply());
            assertEquals(OperationState.COMPLETED,
                    operations.findById(burn.operationId()).orElseThrow().state());
            assertEquals(3L, jdbc.sql("""
                    SELECT count(*) FROM ethereum_redemption_balance_observation
                    """).query(Long.class).single());
            assertEquals(1L, jdbc.sql("""
                    SELECT count(*) FROM ethereum_burn_observation
                    WHERE observation_status = 'CONFIRMED'
                      AND event_source_address = :admin
                      AND event_destination_address =
                          '0x0000000000000000000000000000000000000000'
                      AND event_atomic_amount = 10000
                      AND event_count = 1
                    """).param("admin", admin.normalizedAddress())
                    .query(Long.class).single());
        }
    }

    @Test
    void changedSourceRegistryMetadataFencesSigningBeforeNativePreparation()
            throws Exception {
        try (Scenario scenario = Scenario.create(false)) {
            WalletIdentityRegistry stale = new WalletIdentityRegistry() {
                @Override public WalletIdentity resolve(WalletReference reference) {
                    WalletIdentity current = scenario.signer.resolve(reference);
                    if (!reference.equals(SOURCE)) return current;
                    return new WalletIdentity(
                            current.reference(), current.aliases(), current.ownerCategory(),
                            current.network(), current.normalizedAddress(),
                            current.keyReference(), current.registryVersion() + "-changed",
                            current.keyVersion(), current.allowedPurposes(), current.status());
                }
                @Override public List<WalletIdentity> identities() {
                    return scenario.signer.identities();
                }
            };
            WalletTransferAcceptedDeliveryHandler fenced =
                    new WalletTransferAcceptedDeliveryHandler(
                            scenario.repository, scenario.chain, scenario.signing,
                            stale, CLOCK::instant, Duration.ofMinutes(5));

            assertThrows(IllegalStateException.class,
                    () -> fenced.handle(scenario.delivery));
            assertEquals(0, scenario.submissionCalls[0]);
            assertEquals(0L, jdbc.sql(
                    "SELECT count(*) FROM ethereum_wallet_transfer_attempt")
                    .query(Long.class).single());
        }
    }

    private static final class Scenario implements AutoCloseable {
        private final Web3j submission;
        private final Web3j observation;
        private final LocalConfiguredSigner signer;
        private final Web3jEthereumWalletTransferChainAdapter chain;
        private final PostgresWalletTransferRepository repository;
        private final WalletTransferAcceptanceService acceptance;
        private final SigningAuthorityService signing;
        private final WalletTransferAcceptedDeliveryHandler handler;
        private final OperationDelivery delivery;
        private final String contract;
        private final String caller;
        private final String sourceAddress;
        private final String destinationAddress;
        private final int[] submissionCalls;

        private Scenario(
                Web3j submission, Web3j observation, LocalConfiguredSigner signer,
                Web3jEthereumWalletTransferChainAdapter chain,
                PostgresWalletTransferRepository repository,
                WalletTransferAcceptanceService acceptance,
                SigningAuthorityService signing,
                WalletTransferAcceptedDeliveryHandler handler,
                OperationDelivery delivery, String contract, String caller,
                String sourceAddress, String destinationAddress, int[] submissionCalls) {
            this.submission = submission;
            this.observation = observation;
            this.signer = signer;
            this.chain = chain;
            this.repository = repository;
            this.acceptance = acceptance;
            this.signing = signing;
            this.handler = handler;
            this.delivery = delivery;
            this.contract = contract;
            this.caller = caller;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.submissionCalls = submissionCalls;
        }

        static Scenario create(boolean loseFirstResponse) throws Exception {
            Web3j submission = anvil.newClient();
            Web3j observation = anvil.newClient();
            String caller = submission.ethAccounts().send().getAccounts().getFirst()
                    .toLowerCase(java.util.Locale.ROOT);
            LocalConfiguredSigner signer = configuredSigner();
            var source = signer.resolve(SOURCE);
            var destination = signer.resolve(DESTINATION);
            anvil.setBalance(source.normalizedAddress(),
                    new BigInteger("100000000000000000000"));
            anvil.setBalance(destination.normalizedAddress(),
                    new BigInteger("100000000000000000000"));
            String contract = EthereumMintVerticalSliceIntegrationTest
                    .deployReferenceToken(submission, caller);
            EthereumMintVerticalSliceIntegrationTest.grantMinter(
                    submission, caller, contract, caller);
            mint(submission, caller, contract, source.normalizedAddress(),
                    BigInteger.valueOf(10_000));

            PostgresWalletTransferRepository repository =
                    new PostgresWalletTransferRepository(dataSource);
            WalletTransferAcceptanceService acceptance = new WalletTransferAcceptanceService(
                    repository,
                    (asset, unit, version) -> asset.equals(UNIT.assetId())
                            && unit.equals(UNIT.unitId()) && version == UNIT.version()
                            ? Optional.of(UNIT) : Optional.empty(),
                    signer, CLOCK::instant, ids(), transferIds(),
                    new WalletTransferAcceptanceService.Policy(
                            contract, "local-usdzelle-v1",
                            "local-ethereum-wallet-transfer-v1"));
            var accepted = acceptance.accept(
                    new ParticipantScope("tenant-a", "participant-a"),
                    new IdempotencyKey("wallet-transfer-integration-" + UUID.randomUUID()),
                    new WalletTransferAcceptanceService.Request(
                            "100", UNIT.assetId(), UNIT.unitId(), UNIT.version(),
                            SOURCE, DESTINATION));
            OperationDelivery delivery = PostgresOperationDeliveryQueue
                    .walletTransfersOnly(dataSource)
                    .claim("wallet-transfer-test", NOW, Duration.ofSeconds(30), 1)
                    .deliveries().getFirst();
            int[] calls = {0};
            Web3jEthereumWalletTransferChainAdapter chain =
                    new Web3jEthereumWalletTransferChainAdapter(
                            dataSource, submission, observation,
                            raw -> {
                                calls[0]++;
                                EthSendTransaction response = submission
                                        .ethSendRawTransaction(raw).send();
                                if (loseFirstResponse && calls[0] == 1
                                        && !response.hasError()) {
                                    throw new IOException(
                                            "synthetic response loss after local acceptance");
                                }
                                return response;
                            },
                            () -> {
                                if (!submission.ethChainId().send().getChainId()
                                        .equals(BigInteger.valueOf(31_337))) {
                                    throw new IllegalStateException("unexpected local chain");
                                }
                            },
                            configuration(contract), CLOCK);
            SigningAuthorityService signing = signingService(signer);
            WalletTransferAcceptedDeliveryHandler handler =
                    new WalletTransferAcceptedDeliveryHandler(
                            repository, chain, signing, signer,
                            CLOCK::instant, Duration.ofMinutes(5));
            assertEquals(accepted.operation().operationId().value(), delivery.aggregateId());
            return new Scenario(
                    submission, observation, signer, chain, repository, acceptance,
                    signing, handler,
                    delivery, contract, caller, source.normalizedAddress(),
                    destination.normalizedAddress(), calls);
        }

        BigInteger balanceOf(String address) throws IOException {
            Function function = new Function(
                    "balanceOf", List.of(new Address(address)),
                    List.of(new TypeReference<Uint256>() { }));
            return callUint(function);
        }

        BigInteger totalSupply() throws IOException {
            return callUint(new Function(
                    "totalSupply", List.of(),
                    List.of(new TypeReference<Uint256>() { })));
        }

        private BigInteger callUint(Function function) throws IOException {
            String value = observation.ethCall(
                            org.web3j.protocol.core.methods.request.Transaction
                                    .createEthCallTransaction(
                                            caller, contract, FunctionEncoder.encode(function)),
                            DefaultBlockParameterName.LATEST)
                    .send().getValue();
            return (BigInteger) FunctionReturnDecoder.decode(
                    value, function.getOutputParameters()).getFirst().getValue();
        }

        @Override
        public void close() {
            chain.close();
            signer.close();
        }
    }

    private static LocalConfiguredSigner configuredSigner() throws Exception {
        ECKeyPair source = Keys.createEcKeyPair();
        ECKeyPair destination = Keys.createEcKeyPair();
        ECKeyPair admin = Keys.createEcKeyPair();
        return new LocalConfiguredSigner(
                new LocalConfiguredSigner.Configuration(31_337L, List.of(
                        wallet(SOURCE, source), wallet(DESTINATION, destination),
                        adminWallet(admin))),
                new SecureRandom());
    }

    private static LocalConfiguredSigner.ConfiguredWallet adminWallet(ECKeyPair key) {
        String privateKey = Numeric.toHexStringNoPrefixZeroPadded(
                key.getPrivateKey(), 64);
        String address = "0x" + Keys.getAddress(key.getPublicKey());
        return new LocalConfiguredSigner.ConfiguredWallet(
                ADMIN, Set.of(ADMIN_REDEMPTION),
                WalletIdentityRegistry.OwnerCategory.ADMIN,
                SettlementNetwork.ETHEREUM, new KeyAlias("local-demo:ADMIN"),
                privateKey.toCharArray(), address,
                Set.of(WalletIdentityRegistry.Purpose.MINT_AUTHORITY,
                        WalletIdentityRegistry.Purpose.BURN_AUTHORITY,
                        WalletIdentityRegistry.Purpose.REDEMPTION_CUSTODY), true);
    }

    private static LocalConfiguredSigner.ConfiguredWallet wallet(
            WalletReference reference, ECKeyPair key) {
        String privateKey = Numeric.toHexStringNoPrefixZeroPadded(
                key.getPrivateKey(), 64);
        String address = "0x" + Keys.getAddress(key.getPublicKey());
        return new LocalConfiguredSigner.ConfiguredWallet(
                reference, Set.of(), WalletIdentityRegistry.OwnerCategory.USER_CUSTODY,
                SettlementNetwork.ETHEREUM,
                new KeyAlias("local-demo:" + reference.value()
                        .substring("synthetic-wallet:".length())),
                privateKey.toCharArray(), address,
                Set.of(WalletIdentityRegistry.Purpose.USER_CUSTODY_TRANSFER), true);
    }

    private static void mint(
            Web3j client, String caller, String contract,
            String recipient, BigInteger amount) throws Exception {
        Function function = new Function(
                "mint", List.of(new Address(recipient), new Uint256(amount)), List.of());
        EthSendTransaction sent = client.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction
                        .createFunctionCallTransaction(
                                caller, null, BigInteger.valueOf(2_000_000_000L),
                                BigInteger.valueOf(180_000), contract, BigInteger.ZERO,
                                FunctionEncoder.encode(function))).send();
        if (sent.hasError()) {
            throw new IllegalStateException("local fixture mint was rejected");
        }
        TransactionReceipt receipt = EthereumMintVerticalSliceIntegrationTest
                .waitForReceipt(client, sent.getTransactionHash());
        assertTrue(receipt.isStatusOK());
    }

    private static void grantBurner(
            Web3j client, String caller, String contract, String burner) throws Exception {
        byte[] role = Numeric.hexStringToByteArray(Hash.sha3String("BURNER_ROLE"));
        Function function = new Function(
                "grantRole", List.of(new Bytes32(role), new Address(burner)), List.of());
        EthSendTransaction sent = client.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction
                        .createFunctionCallTransaction(
                                caller, null, BigInteger.valueOf(2_000_000_000L),
                                BigInteger.valueOf(200_000), contract, BigInteger.ZERO,
                                FunctionEncoder.encode(function))).send();
        if (sent.hasError()) {
            throw new IllegalStateException("local burner role grant was rejected");
        }
        assertTrue(EthereumMintVerticalSliceIntegrationTest
                .waitForReceipt(client, sent.getTransactionHash()).isStatusOK());
    }

    private static SigningAuthorityService signingService(LocalConfiguredSigner signer) {
        SigningAuthorizationPort authorization = request ->
                new SigningAuthorizationPort.Authorized(new EvidenceRef(
                        "internal:test:wallet-transfer-authorization:"
                                + request.requestId().value()));
        SigningIdentityGenerator identities = new SigningIdentityGenerator() {
            @Override public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }
            @Override public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-transfer:" + UUID.randomUUID());
            }
        };
        return new SigningAuthorityService(
                new PostgresSigningRequestRepository(dataSource), signer,
                authorization, signer, identities, CLOCK::instant);
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

    private static Web3jEthereumWalletTransferChainAdapter.Configuration configuration(
            String contract) {
        return new Web3jEthereumWalletTransferChainAdapter.Configuration(
                31_337L, contract, "local-usdzelle-v1",
                BigInteger.valueOf(1_000_000_000L),
                BigInteger.valueOf(2_000_000_000L), BigInteger.valueOf(180_000), 1,
                UNIT.assetId(), UNIT.unitId(), UNIT.version(), UNIT.scale(),
                "local-ethereum-wallet-transfer-v1");
    }
}
