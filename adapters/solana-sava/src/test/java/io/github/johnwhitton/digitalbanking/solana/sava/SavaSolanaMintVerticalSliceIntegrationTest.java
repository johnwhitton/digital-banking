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
import io.github.johnwhitton.digitalbanking.application.command.IdempotencyKey;
import io.github.johnwhitton.digitalbanking.application.command.MintCommand;
import io.github.johnwhitton.digitalbanking.application.command.ParticipantScope;
import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.TokenOperationAcceptedDeliveryHandler;
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
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationState;
import io.github.johnwhitton.digitalbanking.domain.operation.RetryAuthorization;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationRepository;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresSigningRequestRepository;
import io.github.johnwhitton.digitalbanking.signer.local.LocalSolanaConfiguredSigner;
import org.flywaydb.core.Flyway;
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

    private Scenario scenario(boolean loseResponse) throws Exception {
        TestKey fee = key(temporary.resolve("fee-" + loseResponse + ".json"),
                "local-solana:fee-" + loseResponse);
        TestKey authority = key(
                temporary.resolve("authority-" + loseResponse + ".json"),
                "local-solana:authority-" + loseResponse);
        Files.setPosixFilePermissions(temporary, Set.of(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.OWNER_EXECUTE));
        LocalSolanaConfiguredSigner signer = new LocalSolanaConfiguredSigner(
                new LocalSolanaConfiguredSigner.Configuration(temporary, List.of(
                        configured(fee, SigningRequest.KeyRole.FEE_PAYER, "fee-v1"),
                        configured(authority, SigningRequest.KeyRole.MINT_AUTHORITY,
                                "authority-v1"))));
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
                authorization, signer, signingIds, CLOCK::instant);
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
        private final PublicKey ata;
        private byte[] signedTransaction;
        private BigInteger supply = BigInteger.ZERO;
        private BigInteger balance = BigInteger.ZERO;
        private int submissionCalls;

        RpcFixture(PublicKey feePayer, PublicKey authority) throws IOException {
            this.feePayer = feePayer;
            this.authority = authority;
            this.ata = SolanaMintTransactionCodec.associatedTokenAddress(
                    DESTINATION_OWNER, mint);
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
        int submissionCalls() { return submissionCalls; }

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
                result = "100";
            } else if (request.contains("getBalance")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":100},"
                        + "\"value\":1000000000}";
            } else if (request.contains("sendTransaction")) {
                Matcher matcher = SEND.matcher(request);
                if (!matcher.find()) throw new IOException("signed transaction was absent");
                signedTransaction = Base64.getDecoder().decode(matcher.group(1));
                submissionCalls++;
                BigInteger amount = amount(signedTransaction);
                supply = supply.add(amount);
                balance = balance.add(amount);
                result = quote(Transaction.getBase58Id(signedTransaction));
            } else if (request.contains("getSignatureStatuses")) {
                result = "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":201},"
                        + "\"value\":[{\"slot\":200,\"confirmations\":null,"
                        + "\"err\":null,\"confirmationStatus\":\"finalized\"}]}";
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
            } else if (request.contains(ata.toBase58()) && signedTransaction != null) {
                TokenAccount data = new TokenAccount(
                        ata, mint, DESTINATION_OWNER, balance.longValue(), 0, null,
                        AccountState.Initialized, 0, 0L, 0L, 0, null);
                bytes = new byte[TokenAccount.BYTES];
                data.write(bytes, 0);
                space = TokenAccount.BYTES;
            } else {
                return "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":100},"
                        + "\"value\":null}";
            }
            return "{\"context\":{\"apiVersion\":\"4.1.2\",\"slot\":200},"
                    + "\"value\":{\"data\":[\""
                    + Base64.getEncoder().encodeToString(bytes)
                    + "\",\"base64\"],\"executable\":false,\"lamports\":1,"
                    + "\"owner\":\"" + SolanaMintTransactionCodec.TOKEN_PROGRAM.toBase58()
                    + "\",\"rentEpoch\":0,\"space\":" + space + "}}";
        }

        private String transaction() {
            return "{\"slot\":200,\"blockTime\":1784472000,"
                    + "\"meta\":{\"err\":null,\"fee\":5000,"
                    + "\"preBalances\":[1],\"postBalances\":[1],"
                    + "\"preTokenBalances\":[],\"postTokenBalances\":[],"
                    + "\"innerInstructions\":[],\"logMessages\":[],"
                    + "\"rewards\":[],\"loadedAddresses\":{\"readonly\":[],"
                    + "\"writable\":[]},\"computeUnitsConsumed\":100},"
                    + "\"transaction\":[\""
                    + Base64.getEncoder().encodeToString(signedTransaction)
                    + "\",\"base64\"],\"version\":\"legacy\"}";
        }

        private static BigInteger amount(byte[] transaction) {
            var skeleton = software.sava.core.tx.TransactionSkeleton
                    .deserializeSkeleton(transaction);
            byte[] data = skeleton.parseLegacyInstructions()[1].copyData();
            byte[] littleEndian = Arrays.copyOfRange(data, 1, 9);
            reverse(littleEndian);
            return new BigInteger(1, littleEndian);
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
    }
}
