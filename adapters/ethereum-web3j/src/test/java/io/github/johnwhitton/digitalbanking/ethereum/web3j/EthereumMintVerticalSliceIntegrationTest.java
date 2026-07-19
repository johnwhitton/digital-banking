package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.OperationAcceptance;
import io.github.johnwhitton.digitalbanking.application.SigningAuthorityService;
import io.github.johnwhitton.digitalbanking.application.TokenOperationService;
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.MintCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.port.IdGenerator;
import io.github.johnwhitton.digitalbanking.application.port.SigningAuthorizationPort;
import io.github.johnwhitton.digitalbanking.application.port.SigningIdentityGenerator;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityStatus;
import io.github.johnwhitton.digitalbanking.domain.operation.FinalityType;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalEphemeralSigner;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Bool;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

class EthereumMintVerticalSliceIntegrationTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant NOW = Instant.parse("2026-07-17T22:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final ParticipantScope PARTICIPANT =
            new ParticipantScope("tenant-a", "participant-a");

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static AnvilNode anvil;

    @BeforeAll
    static void startInfrastructure() throws Exception {
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
        pool.setMaximumPoolSize(12);
        dataSource = new HikariDataSource(pool);
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        anvil = AnvilNode.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        if (anvil != null) {
            anvil.close();
        }
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void acceptedMintUsesPhase4SignerAndIndependentObservationThenDeduplicates() throws Exception {
        try (Scenario scenario = Scenario.create(true, false)) {
            OperationAcceptance acceptance = scenario.accept("mint-e2e", "12.34");
            OperationDelivery delivery = scenario.delivery(acceptance.operation().operationId());

            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    scenario.handler.handle(delivery).classification());
            assertEquals(BigInteger.valueOf(1_234),
                    scenario.balanceOf(scenario.recipient));
            assertEquals(BigInteger.valueOf(1_234), scenario.totalSupply());

            var completed = scenario.operations.findById(
                    acceptance.operation().operationId()).orElseThrow();
            assertEquals(OperationState.COMPLETED, completed.state());
            assertEquals(FinalityStatus.REACHED,
                    completed.finalities().get(FinalityType.BLOCKCHAIN).status());
            assertEquals(FinalityStatus.NOT_ASSESSED,
                    completed.finalities().get(FinalityType.ACCOUNTING).status());

            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    scenario.handler.handle(delivery).classification());
            assertEquals(DeliveryOutcome.Classification.DUPLICATE,
                    scenario.restartedHandler().handle(delivery).classification());
            assertEquals(BigInteger.valueOf(1_234), scenario.totalSupply());
            assertEquals(1, scenario.mintLogCount());
            assertEquals(0, scenario.restartedSubmissionCalls());
            assertEquals(1, jdbc.sql("""
                    SELECT count(*)
                    FROM ethereum_mint_observation observation
                    JOIN ethereum_mint_attempt attempt
                      USING (operation_id, operation_attempt_id)
                    WHERE observation.operation_id = :operationId
                      AND observation.observation_status = 'CONFIRMED'
                      AND observation.observation_source = 'LOCAL_ANVIL_RPC'
                      AND observation.finality_policy_version = 'local-ethereum-mint-v1'
                      AND observation.required_confirmations = 1
                      AND observation.observed_confirmations >= 1
                      AND observation.receipt_success
                      AND observation.receipt_evidence_sha256 ~ '^[0-9a-f]{64}$'
                      AND observation.observed_sender_address = attempt.signing_address
                      AND observation.observed_contract_address = attempt.contract_address
                      AND observation.observed_nonce = attempt.nonce
                      AND observation.observed_calldata_sha256 = attempt.calldata_sha256
                      AND observation.mint_recipient_address = attempt.recipient_address
                      AND observation.mint_atomic_amount = 1234
                      AND observation.mint_log_evidence_sha256 ~ '^[0-9a-f]{64}$'
                    """)
                    .param("operationId", acceptance.operation().operationId().value())
                    .query(Integer.class).single());
        }
    }

    @Test
    void concurrentMintsReserveDifferentDurableNoncesForOneSigner() throws Exception {
        try (Scenario scenario = Scenario.create(true, false)) {
            OperationAcceptance first = scenario.accept("mint-concurrent-a", "1");
            OperationAcceptance second = scenario.accept("mint-concurrent-b", "2");
            OperationDelivery firstDelivery = scenario.delivery(first.operation().operationId());
            OperationDelivery secondDelivery = scenario.delivery(second.operation().operationId());

            try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
                Future<DeliveryOutcome> firstResult = executor.submit(
                        () -> scenario.handler.handle(firstDelivery));
                Future<DeliveryOutcome> secondResult = executor.submit(
                        () -> scenario.handler.handle(secondDelivery));
                assertEquals(DeliveryOutcome.Classification.DELIVERED,
                        firstResult.get().classification());
                assertEquals(DeliveryOutcome.Classification.DELIVERED,
                        secondResult.get().classification());
            }

            List<BigInteger> nonces = jdbc.sql("""
                    SELECT nonce FROM ethereum_mint_attempt
                    WHERE operation_id IN (:first, :second)
                    ORDER BY nonce
                    """)
                    .param("first", first.operation().operationId().value())
                    .param("second", second.operation().operationId().value())
                    .query(BigInteger.class).list();
            assertEquals(2, nonces.size());
            assertNotEquals(nonces.get(0), nonces.get(1));
            assertEquals(BigInteger.valueOf(300), scenario.totalSupply());
        }
    }

    @Test
    void lostSendResponseIsRecoveredByPrecomputedHashWithoutResubmission() throws Exception {
        try (Scenario scenario = Scenario.create(true, true)) {
            OperationAcceptance acceptance = scenario.accept("mint-response-loss", "3.21");
            OperationDelivery delivery = scenario.delivery(acceptance.operation().operationId());

            assertEquals(DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT,
                    scenario.handler.handle(delivery).classification());
            assertEquals(OperationState.SUBMISSION_AMBIGUOUS,
                    scenario.operations.findById(acceptance.operation().operationId())
                            .orElseThrow().state());
            assertEquals(1, scenario.submissionCalls());

            assertEquals(DeliveryOutcome.Classification.DELIVERED,
                    scenario.restartedHandler().handle(delivery).classification());
            assertEquals(1, scenario.submissionCalls());
            assertEquals(0, scenario.restartedSubmissionCalls());
            assertEquals(BigInteger.valueOf(321), scenario.totalSupply());
            assertEquals(1, scenario.mintLogCount());
            assertEquals("local-ethereum-mint-v1", jdbc.sql("""
                    SELECT finality_policy_version
                    FROM ethereum_mint_observation
                    WHERE operation_id = :operationId
                    ORDER BY observation_sequence DESC LIMIT 1
                    """)
                    .param("operationId", acceptance.operation().operationId().value())
                    .query(String.class).single());
            assertEquals(1, jdbc.sql("""
                    SELECT required_confirmations
                    FROM ethereum_mint_observation
                    WHERE operation_id = :operationId
                    ORDER BY observation_sequence DESC LIMIT 1
                    """)
                    .param("operationId", acceptance.operation().operationId().value())
                    .query(Integer.class).single());
        }
    }

    @Test
    void submissionPreflightFailureIsRetryableWithoutSendingBytes() throws Exception {
        try (Scenario scenario = Scenario.create(true, false, true)) {
            OperationAcceptance acceptance = scenario.accept("mint-preflight-unavailable", "5.67");

            assertEquals(DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT,
                    scenario.handler.handle(
                            scenario.delivery(acceptance.operation().operationId()))
                            .classification());
            assertEquals(OperationState.SUBMISSION_PENDING,
                    scenario.operations.findById(acceptance.operation().operationId())
                            .orElseThrow().state());
            assertEquals(0, scenario.submissionCalls());
            assertEquals(BigInteger.ZERO, scenario.totalSupply());
            assertEquals("SIGNED", jdbc.sql("""
                    SELECT attempt_status FROM ethereum_mint_attempt
                    WHERE operation_id = :operationId
                    """)
                    .param("operationId", acceptance.operation().operationId().value())
                    .query(String.class).single());
        }
    }

    @Test
    void revertedMintNeverAdvancesBlockchainFinalityOrSuccessfulBalance() throws Exception {
        try (Scenario scenario = Scenario.create(false, false)) {
            OperationAcceptance acceptance = scenario.accept("mint-revert", "4.56");
            OperationDelivery delivery = scenario.delivery(acceptance.operation().operationId());

            assertEquals(DeliveryOutcome.Classification.TERMINAL_NO_EFFECT,
                    scenario.handler.handle(delivery).classification());
            var retained = scenario.operations.findById(
                    acceptance.operation().operationId()).orElseThrow();
            assertEquals(OperationState.MANUAL_REVIEW, retained.state());
            assertEquals(FinalityStatus.NOT_ASSESSED,
                    retained.finalities().get(FinalityType.BLOCKCHAIN).status());
            assertEquals(BigInteger.ZERO, scenario.totalSupply());
            assertEquals(0, scenario.mintLogCount());
        }
    }

    @Test
    void migrationsCreateNonceAttemptAndObservationTables() {
        assertEquals(10, jdbc.sql("""
                SELECT count(*) FROM flyway_schema_history WHERE success
                """).query(Integer.class).single());
        assertEquals(3, jdbc.sql("""
                SELECT count(*) FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN (
                    'ethereum_nonce_cursor', 'ethereum_mint_attempt',
                    'ethereum_mint_observation')
                """).query(Integer.class).single());
    }

    private static final class Scenario implements AutoCloseable {

        private final Web3j submission;
        private final Web3j observation;
        private final String admin;
        private final String recipient;
        private final String contract;
        private final LocalEphemeralSigner signer;
        private final PostgresOperationRepository operations;
        private final TokenOperationAcceptedDeliveryHandler handler;
        private final List<AutoCloseable> restartResources = new ArrayList<>();
        private int[] submissionCounter;
        private int[] restartedSubmissionCounter = {0};

        private Scenario(
                Web3j submission,
                Web3j observation,
                String admin,
                String recipient,
                String contract,
                LocalEphemeralSigner signer,
                PostgresOperationRepository operations,
                TokenOperationAcceptedDeliveryHandler handler) {
            this.submission = submission;
            this.observation = observation;
            this.admin = admin;
            this.recipient = recipient;
            this.contract = contract;
            this.signer = signer;
            this.operations = operations;
            this.handler = handler;
        }

        static Scenario create(boolean grantMinter, boolean loseFirstResponse) throws Exception {
            return create(grantMinter, loseFirstResponse, false);
        }

        static Scenario create(
                boolean grantMinter,
                boolean loseFirstResponse,
                boolean failSubmissionReadiness) throws Exception {
            Web3j submission = anvil.newClient();
            Web3j observation = anvil.newClient();
            List<String> accounts = submission.ethAccounts().send().getAccounts();
            String admin = accounts.get(0).toLowerCase(java.util.Locale.ROOT);
            String recipient = accounts.get(1).toLowerCase(java.util.Locale.ROOT);

            LocalEphemeralSigner signer = newSigner();
            LocalEphemeralSigner.KeyMetadata evmKey = evmKey(signer);
            EthereumTransactionCodec codec = new EthereumTransactionCodec();
            String signingAddress = codec.addressFromPublicKey(signer.publicKey(evmKey.alias()));
            anvil.setBalance(signingAddress, new BigInteger("100000000000000000000"));

            String contract = deployReferenceToken(submission, admin);
            if (grantMinter) {
                grantMinter(submission, admin, contract, signingAddress);
            }

            PostgresOperationRepository operations = new PostgresOperationRepository(dataSource);
            SigningAuthorityService signing = signingService(signer);

            Web3jEthereumMintChainAdapter.Configuration configuration =
                    configuration(contract, recipient, signingAddress, evmKey);
            int[] calls = {0};
            Web3jEthereumMintChainAdapter.SubmissionTransport transport = raw -> {
                calls[0]++;
                EthSendTransaction response = submission.ethSendRawTransaction(raw).send();
                if (loseFirstResponse && calls[0] == 1 && !response.hasError()) {
                    throw new IOException("synthetic response loss after Anvil acceptance");
                }
                return response;
            };
            Web3jEthereumMintChainAdapter chain = new Web3jEthereumMintChainAdapter(
                    dataSource, submission, observation, transport,
                    () -> {
                        if (failSubmissionReadiness) {
                            throw new IOException("synthetic pre-submission RPC outage");
                        }
                        if (!submission.ethChainId().send().getChainId()
                                .equals(BigInteger.valueOf(31_337))) {
                            throw new IllegalStateException("unexpected local chain");
                        }
                    },
                    configuration, CLOCK);
            TokenOperationAcceptedDeliveryHandler handler = new TokenOperationAcceptedDeliveryHandler(
                    operations, chain, signing, CLOCK::instant, ids(),
                    new TokenOperationAcceptedDeliveryHandler.Policy(
                            evmKey.alias(), signingAddress, Duration.ofMinutes(5),
                            "anvil-one-confirmation-v1"));
            Scenario scenario = new Scenario(
                    submission, observation, admin, recipient, contract,
                    signer, operations, handler);
            scenario.submissionCounter = calls;
            return scenario;
        }

        OperationAcceptance accept(String key, String quantity) {
            IdGenerator ids = new IdGenerator() {
                @Override
                public OperationId nextOperationId() {
                    return new OperationId(UUID.randomUUID());
                }

                @Override
                public AttemptId nextAttemptId() {
                    return new AttemptId(UUID.randomUUID());
                }
            };
            TokenOperationService service = new TokenOperationService(
                    operations, CLOCK::instant, ids,
                    (canonical, participant) -> new EvidenceRef(
                            "participant:test:acceptance:" + UUID.randomUUID()));
            OperationAcceptance acceptance = service.accept(
                    new MintCommand(1, PARTICIPANT, TokenQuantity.parse(quantity, UNIT),
                            "local-mint-" + key),
                    IdempotencyKey.of(key));
            return acceptance;
        }

        int submissionCalls() {
            return submissionCounter[0];
        }

        int restartedSubmissionCalls() {
            return restartedSubmissionCounter[0];
        }

        TokenOperationAcceptedDeliveryHandler restartedHandler() throws IOException {
            LocalEphemeralSigner replacementSigner = newSigner();
            LocalEphemeralSigner.KeyMetadata replacementKey = evmKey(replacementSigner);
            EthereumTransactionCodec codec = new EthereumTransactionCodec();
            String replacementAddress = codec.addressFromPublicKey(
                    replacementSigner.publicKey(replacementKey.alias()));
            Web3j restartedSubmission = anvil.newClient();
            Web3j restartedObservation = anvil.newClient();
            restartedSubmissionCounter = new int[]{0};
            Web3jEthereumMintChainAdapter restartedChain =
                    new Web3jEthereumMintChainAdapter(
                            dataSource, restartedSubmission, restartedObservation,
                            raw -> {
                                restartedSubmissionCounter[0]++;
                                return restartedSubmission.ethSendRawTransaction(raw).send();
                            },
                            configuration(
                                    contract, recipient, replacementAddress, replacementKey,
                                    5, "changed-after-acceptance"),
                            CLOCK);
            restartResources.add(restartedChain);
            restartResources.add(replacementSigner);
            return new TokenOperationAcceptedDeliveryHandler(
                    new PostgresOperationRepository(dataSource), restartedChain,
                    signingService(replacementSigner), CLOCK::instant, ids(),
                    new TokenOperationAcceptedDeliveryHandler.Policy(
                            replacementKey.alias(), replacementAddress,
                            Duration.ofMinutes(5), "anvil-one-confirmation-v1"));
        }

        OperationDelivery delivery(OperationId operationId) {
            UUID eventId = jdbc.sql("""
                    SELECT event_id FROM operation_outbox WHERE operation_id = :operationId
                    """).param("operationId", operationId.value()).query(UUID.class).single();
            return new OperationDelivery(
                    eventId, operationId, 1, 1, UUID.randomUUID(), "integration-worker", 1);
        }

        BigInteger balanceOf(String account) throws IOException {
            Function function = new Function(
                    "balanceOf", List.of(new Address(account)),
                    List.of(new TypeReference<Uint256>() { }));
            return callUint(function);
        }

        BigInteger totalSupply() throws IOException {
            Function function = new Function(
                    "totalSupply", List.of(), List.of(new TypeReference<Uint256>() { }));
            return callUint(function);
        }

        int mintLogCount() {
            return jdbc.sql("""
                    SELECT count(*) FROM ethereum_mint_observation
                    WHERE observation_status = 'CONFIRMED'
                      AND operation_id IN (
                          SELECT operation_id FROM ethereum_mint_attempt
                          WHERE contract_address = :contract)
                    """).param("contract", contract).query(Integer.class).single();
        }

        private BigInteger callUint(Function function) throws IOException {
            String value = observation.ethCall(
                            org.web3j.protocol.core.methods.request.Transaction
                                    .createEthCallTransaction(admin, contract,
                                            FunctionEncoder.encode(function)),
                            DefaultBlockParameterName.LATEST)
                    .send().getValue();
            return (BigInteger) FunctionReturnDecoder.decode(
                    value, function.getOutputParameters()).getFirst().getValue();
        }

        @Override
        public void close() {
            for (AutoCloseable resource : restartResources.reversed()) {
                try {
                    resource.close();
                } catch (Exception failure) {
                    throw new IllegalStateException("restart resource cleanup failed", failure);
                }
            }
            signer.close();
            submission.shutdown();
            observation.shutdown();
        }
    }

    private static LocalEphemeralSigner newSigner() {
        return new LocalEphemeralSigner(
                new LocalEphemeralSigner.Configuration(
                        Set.of(SigningRequest.KeyRole.MINT_AUTHORITY),
                        Set.of(SigningRequest.KeyRole.BURN_AUTHORITY), 1024),
                CLOCK, new SecureRandom());
    }

    private static LocalEphemeralSigner.KeyMetadata evmKey(LocalEphemeralSigner signer) {
        return signer.keys().stream()
                .filter(key -> key.algorithm() == SigningRequest.Algorithm.SECP256K1)
                .findFirst().orElseThrow();
    }

    private static SigningAuthorityService signingService(LocalEphemeralSigner signer) {
        SigningAuthorizationPort authorization = request ->
                new SigningAuthorizationPort.Authorized(new EvidenceRef(
                        "internal:test:signing-authorized:" + request.requestId().value()));
        SigningIdentityGenerator signingIds = new SigningIdentityGenerator() {
            @Override
            public SigningAttemptId nextAttemptId() {
                return new SigningAttemptId(UUID.randomUUID());
            }

            @Override
            public ProviderRequestId nextProviderRequestId() {
                return new ProviderRequestId("local-provider:" + UUID.randomUUID());
            }
        };
        return new SigningAuthorityService(
                new PostgresSigningRequestRepository(dataSource), signer,
                authorization, signer, signingIds, CLOCK::instant);
    }

    private static IdGenerator ids() {
        return new IdGenerator() {
            @Override
            public OperationId nextOperationId() {
                return new OperationId(UUID.randomUUID());
            }

            @Override
            public AttemptId nextAttemptId() {
                return new AttemptId(UUID.randomUUID());
            }
        };
    }

    private static Web3jEthereumMintChainAdapter.Configuration configuration(
            String contract,
            String recipient,
            String signingAddress,
            LocalEphemeralSigner.KeyMetadata key) {
        return configuration(
                contract, recipient, signingAddress, key,
                1, "local-ethereum-mint-v1");
    }

    private static Web3jEthereumMintChainAdapter.Configuration configuration(
            String contract,
            String recipient,
            String signingAddress,
            LocalEphemeralSigner.KeyMetadata key,
            int confirmations,
            String policyVersion) {
        return new Web3jEthereumMintChainAdapter.Configuration(
                31_337L, contract, recipient, signingAddress,
                key.alias().value(), key.keyVersion(),
                BigInteger.valueOf(1_000_000_000L),
                BigInteger.valueOf(2_000_000_000L),
                BigInteger.valueOf(180_000), confirmations,
                UNIT.assetId(), UNIT.unitId(), UNIT.version(), UNIT.scale(),
                policyVersion);
    }

    static String deployReferenceToken(Web3j client, String admin) throws Exception {
        String bytecode = forgeBytecode();
        String constructor = FunctionEncoder.encodeConstructor(List.of(new Address(admin)));
        EthSendTransaction sent = client.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createContractTransaction(
                        admin, null, BigInteger.valueOf(2_000_000_000L),
                        BigInteger.valueOf(6_000_000), BigInteger.ZERO,
                        bytecode + Numeric.cleanHexPrefix(constructor))).send();
        if (sent.hasError()) {
            throw new IllegalStateException("local contract deployment was rejected");
        }
        TransactionReceipt receipt = waitForReceipt(client, sent.getTransactionHash());
        assertTrue(receipt.isStatusOK());
        String contract = receipt.getContractAddress().toLowerCase(java.util.Locale.ROOT);
        if (!hasRole(client, admin, contract, new byte[32], admin)) {
            throw new IllegalStateException("deployed contract did not retain its configured admin");
        }
        return contract;
    }

    static void grantMinter(
            Web3j client, String admin, String contract, String signingAddress) throws Exception {
        byte[] role = Numeric.hexStringToByteArray(Hash.sha3String("MINTER_ROLE"));
        Function grant = new Function(
                "grantRole", List.of(new Bytes32(role), new Address(signingAddress)), List.of());
        EthSendTransaction sent = client.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction
                        .createFunctionCallTransaction(
                                admin, null, BigInteger.valueOf(2_000_000_000L),
                                BigInteger.valueOf(200_000), contract, BigInteger.ZERO,
                                FunctionEncoder.encode(grant))).send();
        if (sent.hasError()) {
            throw new IllegalStateException(
                    "local minter role grant RPC code " + sent.getError().getCode());
        }
        TransactionReceipt receipt = waitForReceipt(client, sent.getTransactionHash());
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException(
                    "local minter role grant receipt status " + receipt.getStatus());
        }
    }

    private static boolean hasRole(
            Web3j client, String caller, String contract, byte[] role, String account)
            throws IOException {
        Function function = new Function(
                "hasRole", List.of(new Bytes32(role), new Address(account)),
                List.of(new TypeReference<Bool>() { }));
        String value = client.ethCall(
                        org.web3j.protocol.core.methods.request.Transaction
                                .createEthCallTransaction(
                                        caller, contract, FunctionEncoder.encode(function)),
                        DefaultBlockParameterName.LATEST)
                .send().getValue();
        return (Boolean) FunctionReturnDecoder.decode(
                value, function.getOutputParameters()).getFirst().getValue();
    }

    static TransactionReceipt waitForReceipt(Web3j client, String hash) throws Exception {
        for (int attempt = 0; attempt < 100; attempt++) {
            Optional<TransactionReceipt> receipt = client.ethGetTransactionReceipt(hash)
                    .send().getTransactionReceipt();
            if (receipt.isPresent()) {
                return receipt.orElseThrow();
            }
            Thread.sleep(25);
        }
        throw new IllegalStateException("local transaction receipt was not observed");
    }

    private static String forgeBytecode() throws Exception {
        Process process = new ProcessBuilder(
                "forge", "inspect", "LocalReferenceToken", "bytecode", "--offline")
                .directory(Path.of(System.getProperty("user.dir"))
                        .resolve("../../contracts/evm").normalize().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
                .trim();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)
                || process.exitValue() != 0 || !output.matches("0x[0-9a-fA-F]+")) {
            process.destroyForcibly();
            throw new IllegalStateException("Foundry did not produce reference token bytecode");
        }
        return output;
    }

    static final class AnvilNode implements AutoCloseable {

        private final Process process;
        private final String endpoint;

        private AnvilNode(Process process, String endpoint) {
            this.process = process;
            this.endpoint = endpoint;
        }

        static AnvilNode start() throws Exception {
            int port;
            try (ServerSocket socket = new ServerSocket(0)) {
                port = socket.getLocalPort();
            }
            Process process = new ProcessBuilder(
                    "anvil", "--port", Integer.toString(port),
                    "--chain-id", "31337", "--accounts", "4",
                    "--auto-impersonate", "--mnemonic-random")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            AnvilNode node = new AnvilNode(process, "http://127.0.0.1:" + port);
            for (int attempt = 0; attempt < 100; attempt++) {
                try (Web3j client = node.newClient()) {
                    if (client.ethChainId().send().getChainId()
                            .equals(BigInteger.valueOf(31_337))) {
                        return node;
                    }
                } catch (Exception ignored) {
                    // The loop is bounded and the process output is intentionally discarded.
                }
                Thread.sleep(25);
            }
            node.close();
            throw new IllegalStateException("Anvil did not start on the loopback endpoint");
        }

        Web3j newClient() {
            return Web3j.build(new HttpService(endpoint));
        }

        void setBalance(String address, BigInteger balance) throws IOException {
            HttpService service = new HttpService(endpoint);
            try {
                Request<Object, Response<String>> request = new Request<>(
                        "anvil_setBalance",
                        List.of(address, Numeric.encodeQuantity(balance)),
                        service,
                        castResponseType());
                Response<String> response = request.send();
                if (response.hasError()) {
                    throw new IllegalStateException("Anvil balance setup failed");
                }
            } finally {
                service.close();
            }
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private static Class<Response<String>> castResponseType() {
            return (Class) Response.class;
        }

        @Override
        public void close() {
            process.destroy();
            try {
                if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
