package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigInteger;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.github.johnwhitton.digitalbanking.application.SigningRequestConflictException;
import io.github.johnwhitton.digitalbanking.application.port.SigningRequestRepository;
import io.github.johnwhitton.digitalbanking.domain.asset.AssetUnit;
import io.github.johnwhitton.digitalbanking.domain.asset.TokenQuantity;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.ProviderRequestId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningAttemptId;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequestId;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionSystemException;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostgresSigningRequestRepositoryTest {

    private static final String IMAGE = "postgres:17.10-alpine3.23";
    private static final Instant NOW = Instant.parse("2026-07-17T21:30:00.123456Z");
    private static final AssetUnit UNIT = new AssetUnit(
            "USD_STABLE", "USD", 1, 2, new BigInteger("1000000000000"));
    private static final String A = "a".repeat(64);
    private static final String B = "b".repeat(64);

    private static PostgreSQLContainer postgres;
    private static HikariDataSource dataSource;
    private static JdbcClient jdbc;
    private static PostgresSigningRequestRepository repository;

    @BeforeAll
    static void startPostgres() {
        postgres = new PostgreSQLContainer(IMAGE)
                .withDatabaseName("digital_banking_signing")
                .withUsername("digital_banking_test")
                .withPassword("fixture-only-password")
                .withStartupTimeout(Duration.ofSeconds(60));
        postgres.start();
        dataSource = newDataSource();
        Flyway.configure().dataSource(dataSource).cleanDisabled(true).load().migrate();
        jdbc = JdbcClient.create(dataSource);
        repository = new PostgresSigningRequestRepository(dataSource);
    }

    @AfterAll
    static void stopPostgres() {
        if (dataSource != null) {
            dataSource.close();
        }
        if (postgres != null) {
            postgres.stop();
        }
    }

    @BeforeEach
    void clearData() {
        jdbc.sql("DELETE FROM signing_attempt_evidence").update();
        jdbc.sql("DELETE FROM signing_transition").update();
        jdbc.sql("DELETE FROM signing_request_approval_evidence").update();
        jdbc.sql("DELETE FROM signing_attempt").update();
        jdbc.sql("DELETE FROM signing_request").update();
    }

    @Test
    void v4MigratesEmptyDatabaseAndPersistsOnlyRedactedSigningEvidence() {
        SigningRequest accepted = repository.accept(request(1, A, B, Optional.empty())).request();

        assertEquals(7, jdbc.sql(
                "SELECT count(*) FROM flyway_schema_history WHERE success")
                .query(Integer.class).single());
        assertEquals(5, jdbc.sql("""
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'public'
                          AND table_name IN (
                            'signing_request', 'signing_request_approval_evidence',
                            'signing_attempt', 'signing_attempt_evidence',
                            'signing_transition')
                        """).query(Integer.class).single());
        List<String> unsafeColumns = jdbc.sql("""
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name LIKE 'signing_%'
                          AND column_name ~ '(private|secret|credential|seed|mnemonic|raw|bytes)'
                        ORDER BY column_name
                        """).query(String.class).list();
        String stored = jdbc.sql("""
                        SELECT to_jsonb(value)::text
                        FROM signing_request AS value
                        WHERE request_id = :requestId
                        """).param("requestId", accepted.requestId().value())
                .query(String.class).single();

        assertEquals(List.of(), unsafeColumns);
        assertFalse(stored.contains("private"));
        assertFalse(stored.contains("signature"));
        assertEquals(1L, count("signing_request"));
        assertEquals(1L, count("signing_request_approval_evidence"));
        assertEquals(1L, count("signing_transition"));
        assertEquals(0L, count("signing_attempt"));
    }

    @Test
    void exactReplayConflictAndRestartAreDeterministic() {
        SigningRequest proposed = request(2, A, B, Optional.empty());
        SigningRequestRepository.Acceptance first = repository.accept(proposed);
        SigningRequestRepository.Acceptance replay = repository.accept(
                request(2, A, B, Optional.empty(), "mint-authority-role", NOW.plusSeconds(1)));

        assertFalse(first.replayed());
        assertTrue(replay.replayed());
        assertRequestEquals(first.request(), replay.request());
        assertThrows(SigningRequestConflictException.class,
                () -> repository.accept(request(2, B, A, Optional.empty())));
        assertThrows(SigningRequestConflictException.class,
                () -> repository.accept(request(
                        2, A, B, Optional.empty(), "different-source-role")));
        try (HikariDataSource restarted = newDataSource()) {
            SigningRequest loaded = new PostgresSigningRequestRepository(restarted)
                    .findById(proposed.requestId()).orElseThrow();
            assertRequestEquals(proposed, loaded);
        }
        assertEquals(1L, count("signing_request"));
        assertEquals(1L, count("signing_transition"));
    }

    @Test
    void parallelDuplicateAndConflictRacesSelectOneDurableRequest() throws Exception {
        CyclicBarrier duplicateBarrier = new CyclicBarrier(2);
        SigningRequest duplicate = request(3, A, B, Optional.empty());
        SigningRequest independentlyCreatedDuplicate = request(
                3, A, B, Optional.empty(), "mint-authority-role", NOW.plusSeconds(1));
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<SigningRequestRepository.Acceptance> first = executor.submit(() -> {
                duplicateBarrier.await(10, TimeUnit.SECONDS);
                return new PostgresSigningRequestRepository(dataSource)
                        .accept(independentlyCreatedDuplicate);
            });
            Future<SigningRequestRepository.Acceptance> second = executor.submit(() -> {
                duplicateBarrier.await(10, TimeUnit.SECONDS);
                return new PostgresSigningRequestRepository(dataSource).accept(duplicate);
            });
            List<SigningRequestRepository.Acceptance> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter(SigningRequestRepository.Acceptance::replayed)
                    .count());
        }
        assertEquals(1L, count("signing_request"));

        CyclicBarrier conflictBarrier = new CyclicBarrier(2);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(() -> raceAccept(
                    conflictBarrier, request(4, A, B, Optional.empty())));
            Future<String> second = executor.submit(() -> raceAccept(
                    conflictBarrier, request(4, B, A, Optional.empty())));
            List<String> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            assertEquals(1L, outcomes.stream().filter("accepted"::equals).count());
            assertEquals(1L, outcomes.stream().filter("conflict"::equals).count());
        }
        assertEquals(2L, count("signing_request"));
    }

    @Test
    void lifecycleAttemptsAmbiguityInquiryAndSignatureReconstructAfterRestart() {
        SigningRequest request = repository.accept(request(5, A, B, Optional.empty())).request();
        request = save(request, request.awaitAuthorization(
                0, NOW.plusSeconds(1), new EvidenceRef("evidence:awaiting")));
        request = save(request, request.authorize(
                1, NOW.plusSeconds(2), new EvidenceRef("evidence:authorized")));
        SigningAttemptId firstAttempt = attemptId(50);
        request = save(request, request.persistProviderRequest(
                2, firstAttempt, providerId(50), NOW.plusSeconds(3),
                new EvidenceRef("evidence:provider-request")));
        request = save(request, request.recordProviderOutcome(
                3, firstAttempt,
                SigningRequest.ProviderOutcome.ambiguous(
                        new EvidenceRef("evidence:provider-ambiguous")),
                NOW.plusSeconds(4)));

        try (HikariDataSource restarted = newDataSource()) {
            SigningRequest ambiguous = new PostgresSigningRequestRepository(restarted)
                    .findById(request.requestId()).orElseThrow();
            assertRequestEquals(request, ambiguous);
            assertEquals(providerId(50), ambiguous.attempts().getLast().providerRequestId());
        }

        request = save(request, request.recordProviderOutcome(
                4, firstAttempt,
                SigningRequest.ProviderOutcome.retryableNoSignature(
                        "provider-proved-no-signature",
                        new EvidenceRef("evidence:inquiry-no-signature")),
                NOW.plusSeconds(5)));
        request = save(request, request.awaitAuthorization(
                5, NOW.plusSeconds(6), new EvidenceRef("evidence:retry-policy-pending")));
        request = save(request, request.authorize(
                6, NOW.plusSeconds(7), new EvidenceRef("evidence:retry-authorized")));
        SigningAttemptId retry = attemptId(51);
        request = save(request, request.persistProviderRequest(
                7, retry, providerId(51), NOW.plusSeconds(8),
                new EvidenceRef("evidence:retry-authorized")));
        SigningRequest.SignatureEvidence signature = new SigningRequest.SignatureEvidence(
                A, 64, "SYNTHETIC_SIGNATURE_V1",
                new EvidenceRef("synthetic:provider:signed"),
                SigningRequest.EvidenceOrigin.SYNTHETIC_TEST);
        request = save(request, request.recordProviderOutcome(
                8, retry, SigningRequest.ProviderOutcome.signed(signature),
                NOW.plusSeconds(9)));

        try (HikariDataSource restarted = newDataSource()) {
            SigningRequest loaded = new PostgresSigningRequestRepository(restarted)
                    .findById(request.requestId()).orElseThrow();
            assertRequestEquals(request, loaded);
            assertEquals(2, loaded.attempts().size());
            assertEquals(Optional.of(firstAttempt), loaded.attempts().getLast().predecessor());
            assertEquals(SigningRequest.Status.SIGNED, loaded.status());
            assertEquals(SigningRequest.EvidenceOrigin.SYNTHETIC_TEST,
                    loaded.attempts().getLast().signatureEvidence().orElseThrow().origin());
        }
    }

    @Test
    void optimisticConflictAndAuthorityConstraintsLeaveHistoryUnchanged() {
        SigningRequest accepted = repository.accept(request(6, A, B, Optional.empty())).request();
        SigningRequest awaiting = accepted.awaitAuthorization(
                0, NOW.plusSeconds(1), new EvidenceRef("evidence:awaiting"));
        repository.save(awaiting, 0);

        assertThrows(IllegalStateException.class, () -> repository.save(
                accepted.awaitAuthorization(
                        0, NOW.plusSeconds(2), new EvidenceRef("evidence:stale")), 0));
        assertEquals(2L, jdbc.sql("""
                        SELECT count(*) FROM signing_transition
                        WHERE request_id = :requestId
                        """).param("requestId", accepted.requestId().value())
                .query(Long.class).single());
        assertThrows(DataIntegrityViolationException.class, () -> jdbc.sql("""
                        UPDATE signing_request
                        SET key_role = 'BURN_AUTHORITY'
                        WHERE request_id = :requestId
                        """).param("requestId", accepted.requestId().value()).update());
    }

    @Test
    void invalidLineageRollsBackRequestApprovalAndInitialTransition() {
        SigningRequest.Lineage missing = new SigningRequest.Lineage(
                new SigningRequestId(new UUID(99, 1)), attemptId(99),
                new EvidenceRef("evidence:missing-lineage"));

        assertThrows(TransactionSystemException.class,
                () -> repository.accept(request(7, A, B, Optional.of(missing))));
        assertEquals(0L, count("signing_request"));
        assertEquals(0L, count("signing_request_approval_evidence"));
        assertEquals(0L, count("signing_transition"));
    }

    private static SigningRequest save(SigningRequest before, SigningRequest changed) {
        repository.save(changed, before.version());
        return changed;
    }

    private static String raceAccept(CyclicBarrier barrier, SigningRequest request)
            throws Exception {
        barrier.await(10, TimeUnit.SECONDS);
        try {
            new PostgresSigningRequestRepository(dataSource).accept(request);
            return "accepted";
        } catch (SigningRequestConflictException expected) {
            return "conflict";
        }
    }

    private static SigningRequest request(
            long seed,
            String intentDigest,
            String requestDigest,
            Optional<SigningRequest.Lineage> lineage) {
        return request(seed, intentDigest, requestDigest, lineage, "mint-authority-role");
    }

    private static SigningRequest request(
            long seed,
            String intentDigest,
            String requestDigest,
            Optional<SigningRequest.Lineage> lineage,
            String sourceReference) {
        return request(seed, intentDigest, requestDigest, lineage, sourceReference, NOW);
    }

    private static SigningRequest request(
            long seed,
            String intentDigest,
            String requestDigest,
            Optional<SigningRequest.Lineage> lineage,
            String sourceReference,
            Instant createdAt) {
        return SigningRequest.requested(
                new SigningRequestId(new UUID(1, seed)),
                new SigningRequest.Correlation(
                        new OperationId(new UUID(2, seed)),
                        new AttemptId(new UUID(3, seed)),
                        Optional.empty(), Optional.empty()),
                lineage,
                new SigningRequest.PayloadIdentity(
                        SigningRequest.Mode.EVM_DIGEST,
                        SigningRequest.Algorithm.SECP256K1,
                        A, 32, SigningRequest.PayloadEncoding.RAW_32_BYTE_DIGEST),
                new SigningRequest.KeyContext(
                        new KeyAlias("institution-mint"), "registry-v1",
                        Optional.of("key-version-7"),
                        SigningRequest.KeyRole.MINT_AUTHORITY,
                        SigningRequest.Algorithm.SECP256K1,
                        SettlementNetwork.ETHEREUM,
                        SigningRequest.KeyStatus.ACTIVE,
                        Set.of(SigningRequest.KeyRole.MINT_AUTHORITY),
                        Set.of(SigningRequest.Algorithm.SECP256K1),
                        Set.of(SettlementNetwork.ETHEREUM),
                        NOW.minusSeconds(60), Optional.of(NOW.plusSeconds(3600))),
                new SigningRequest.AuthorityContext(
                        SigningRequest.Action.MINT, SettlementNetwork.ETHEREUM,
                        TokenQuantity.parse("12.34", UNIT), sourceReference,
                        "opaque-recipient-wallet", "token-contract:mint", A,
                        "fee-limit-policy-v1", B, "policy-v5",
                        List.of(new EvidenceRef("evidence:approval:four-eyes")),
                        NOW, NOW.plusSeconds(300)),
                1, intentDigest, 1, requestDigest, createdAt,
                new EvidenceRef("evidence:signing-requested"));
    }

    private static SigningAttemptId attemptId(long seed) {
        return new SigningAttemptId(new UUID(4, seed));
    }

    private static ProviderRequestId providerId(long seed) {
        return new ProviderRequestId("provider-request-" + seed);
    }

    private static long count(String table) {
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static HikariDataSource newDataSource() {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(postgres.getJdbcUrl());
        config.setUsername(postgres.getUsername());
        config.setPassword(postgres.getPassword());
        config.setMaximumPoolSize(8);
        return new HikariDataSource(config);
    }

    private static void assertRequestEquals(SigningRequest expected, SigningRequest actual) {
        assertEquals(expected.requestId(), actual.requestId());
        assertEquals(expected.correlation(), actual.correlation());
        assertEquals(expected.lineage(), actual.lineage());
        assertEquals(expected.payloadIdentity(), actual.payloadIdentity());
        assertEquals(expected.keyContext(), actual.keyContext());
        assertEquals(expected.authorityContext(), actual.authorityContext());
        assertEquals(expected.intentCanonicalizationVersion(),
                actual.intentCanonicalizationVersion());
        assertEquals(expected.intentDigest(), actual.intentDigest());
        assertEquals(expected.requestCanonicalizationVersion(),
                actual.requestCanonicalizationVersion());
        assertEquals(expected.requestDigest(), actual.requestDigest());
        assertEquals(expected.status(), actual.status());
        assertEquals(expected.version(), actual.version());
        assertEquals(expected.createdAt(), actual.createdAt());
        assertEquals(expected.attempts(), actual.attempts());
        assertEquals(expected.transitions(), actual.transitions());
    }
}
