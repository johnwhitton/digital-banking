package io.github.johnwhitton.digitalbanking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigInteger;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryRetryPolicy;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryWorker;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import io.github.johnwhitton.digitalbanking.persistence.postgres.PostgresOperationDeliveryQueue;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.postgresql.PostgreSQLContainer;
import software.sava.core.accounts.PublicKey;
import software.sava.core.accounts.token.Mint;
import software.sava.core.accounts.token.TokenAccount;
import software.sava.rpc.json.http.client.SolanaRpcClient;
import software.sava.rpc.json.http.request.Commitment;
import software.sava.rpc.json.http.response.AccountInfo;

/** One opt-in real PostgreSQL/Agave gate for the reused Phase 6B/6C parents. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local-demo", "local-solana"})
@EnabledIfEnvironmentVariable(named = "LOCAL_SOLANA_REAL_GATE", matches = "true")
class SolanaProductPathOrchestrationIntegrationTest {

    private static final Pattern WORKFLOW_ID = Pattern.compile(
            "\\\"workflowId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final Pattern TRANSFER_ID = Pattern.compile(
            "\\\"transferId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:17.10-alpine3.23")
                    .withDatabaseName("solana_product_path")
                    .withUsername("solana_product_path_test")
                    .withPassword("fixture-only-password")
                    .withStartupTimeout(Duration.ofSeconds(60));

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.clean-disabled", () -> "true");
        registry.add("digital-banking.delivery-worker.enabled", () -> "false");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private OperationDeliveryQueue queue;

    @Autowired
    private OperationDeliveryHandler handler;

    @Autowired
    private ClockPort clock;

    @AfterAll
    static void stopPostgres() {
        POSTGRES.stop();
    }

    @Test
    void orchestratesUserHeldAndSettlementOnlyPathsThroughOneSolanaWorker()
            throws Exception {
        String acquisition = acceptWorkflow(
                "acquisitions", "usdzelle:acquire", "phase-7e-acquisition");

        // Reconstruct the queue after acceptance to prove the parent and child work is durable.
        driveWorkflow(acquisition, PostgresOperationDeliveryQueue.localSolana(dataSource));
        assertEquals(new BigInteger("10000"), totalSupply());
        assertEquals(new BigInteger("10000"), tokenBalance(userOne()));
        assertEquals(BigInteger.ZERO, bankBalance("BANK_1", "USER_1_BANK_ACCOUNT"));

        MvcResult acquisitionReplay = mvc.perform(post("/v1/usdzelle/acquisitions")
                        .with(participant("usdzelle:acquire"))
                        .header("Idempotency-Key", "phase-7e-acquisition")
                        .contentType("application/json").content(workflowRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value(acquisition))
                .andReturn();
        assertEquals(acquisition, id(acquisitionReplay, WORKFLOW_ID));

        String redemption = acceptWorkflow(
                "redemptions", "usdzelle:redeem", "phase-7e-redemption");
        driveWorkflow(redemption, queue);
        assertEquals(BigInteger.ZERO, totalSupply());
        assertEquals(BigInteger.ZERO, tokenBalance(userOne()));
        assertEquals(BigInteger.ZERO, tokenBalance(admin()));
        assertEquals(new BigInteger("10000"),
                bankBalance("BANK_1", "USER_1_BANK_ACCOUNT"));
        assertTrue(transitionVersion(redemption, "PAYOUT_ACCOUNTED")
                < transitionVersion(redemption, "BURN_CONFIRMED"));
        assertEquals("RECONCILED", workflowConclusion(redemption));

        long effectsBeforeSettlement = count("solana_mint_attempt");
        String settlement = acceptSettlement();
        driveSettlement(settlement, PostgresOperationDeliveryQueue.localSolana(dataSource));

        assertEquals(BigInteger.ZERO, totalSupply());
        assertEquals(BigInteger.ZERO, tokenBalance(userOne()));
        assertEquals(BigInteger.ZERO, tokenBalance(userTwo()));
        assertEquals(BigInteger.ZERO, tokenBalance(admin()));
        assertEquals(BigInteger.ZERO, bankBalance("BANK_1", "USER_1_BANK_ACCOUNT"));
        assertEquals(new BigInteger("10000"),
                bankBalance("BANK_2", "USER_2_BANK_ACCOUNT"));
        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM accounting_ledger_account
                WHERE balance_cents <> 0
                """).query(Long.class).single());
        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM accounting_operational_position
                WHERE quantity_cents <> 0
                """).query(Long.class).single());
        assertEquals(effectsBeforeSettlement + 4, count("solana_mint_attempt"));
        assertEquals(7L, jdbc.sql("""
                SELECT count(*) FROM solana_mint_attempt
                WHERE attempt_status = 'CONFIRMED' AND amount_atomic = 10000
                """).query(Long.class).single());
        assertEquals(7L, jdbc.sql("""
                SELECT count(DISTINCT (operation_id, operation_attempt_id))
                FROM solana_mint_observation
                WHERE observation_status = 'CONFIRMED'
                  AND commitment = 'finalized'
                  AND expected_instructions
                """).query(Long.class).single());
        assertEquals(2L, jdbc.sql("""
                SELECT count(*) FROM synthetic_bank_operation
                WHERE operation_kind = 'WITHDRAWAL' AND operation_status = 'SUCCEEDED'
                """).query(Long.class).single());
        assertEquals(2L, jdbc.sql("""
                SELECT count(*) FROM synthetic_bank_operation
                WHERE operation_kind = 'DEPOSIT' AND operation_status = 'SUCCEEDED'
                """).query(Long.class).single());
        assertEquals(3L, jdbc.sql("""
                SELECT count(*) FROM settlement_transfer_boundary
                WHERE transfer_id = :transferId AND child_reference IS NOT NULL
                """).param("transferId", UUID.fromString(settlement))
                .query(Long.class).single());
        assertEquals("RECONCILED", jdbc.sql("""
                SELECT reconciliation_status FROM settlement_transfer_conclusion
                WHERE transfer_id = :transferId
                """).param("transferId", UUID.fromString(settlement))
                .query(String.class).single());

        mvc.perform(get("/v1/transfers/{transferId}", settlement)
                        .with(participant("transfer:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementOrchestration.status").value("COMPLETED"))
                .andExpect(jsonPath("$.settlementOrchestration.reconciliationStatus")
                        .value("RECONCILED"))
                .andExpect(jsonPath("$.settlementOrchestration.senderWallet").doesNotExist())
                .andExpect(jsonPath("$.settlementOrchestration.recipientParticipant")
                        .doesNotExist());
    }

    private String acceptWorkflow(String resource, String authority, String key)
            throws Exception {
        MvcResult result = mvc.perform(post("/v1/usdzelle/" + resource)
                        .with(participant(authority))
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(workflowRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        return id(result, WORKFLOW_ID);
    }

    private String acceptSettlement() throws Exception {
        MvcResult result = mvc.perform(post("/v1/transfers")
                        .with(participant("transfer:create"))
                        .header("Idempotency-Key", "phase-7e-settlement")
                        .contentType("application/json").content(settlementRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        return id(result, TRANSFER_ID);
    }

    private void driveWorkflow(String workflowId, OperationDeliveryQueue durableQueue)
            throws Exception {
        drive(durableQueue, """
                SELECT workflow_status FROM usdzelle_workflow
                WHERE workflow_id = :id
                """, UUID.fromString(workflowId), "workflow");
    }

    private void driveSettlement(String transferId, OperationDeliveryQueue durableQueue)
            throws Exception {
        drive(durableQueue, """
                SELECT parent_status FROM settlement_transfer
                WHERE transfer_id = :id
                """, UUID.fromString(transferId), "settlement transfer");
    }

    private void drive(
            OperationDeliveryQueue durableQueue, String statusSql, UUID id, String label)
            throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        OperationDeliveryWorker worker = new OperationDeliveryWorker(
                durableQueue, handler,
                (delivery, thrown) -> failure.compareAndSet(null, thrown), clock,
                new DeliveryRetryPolicy(
                        100, Duration.ofMillis(100), Duration.ofSeconds(2)),
                Duration.ofSeconds(5), "phase-7e-real-gate", 10);
        long deadline = System.nanoTime() + Duration.ofSeconds(150).toNanos();
        while (System.nanoTime() < deadline) {
            worker.poll();
            assertNull(failure.get(), () -> "delivery failure: " + failure.get());
            String statusValue = jdbc.sql(statusSql).param("id", id)
                    .query(String.class).single();
            if ("COMPLETED".equals(statusValue)) return;
            if ("MANUAL_REVIEW".equals(statusValue)
                    || "FAILED_NO_EFFECT".equals(statusValue)) {
                throw new AssertionError(label + " stopped in " + statusValue
                        + ": " + queueFailures());
            }
            Thread.sleep(5);
        }
        throw new AssertionError(label + " did not complete before the bounded deadline");
    }

    private List<String> queueFailures() {
        return jdbc.sql("""
                SELECT event_type || ':' || status || ':'
                    || COALESCE(last_failure_code, 'none')
                FROM operation_outbox
                WHERE status <> 'DELIVERED'
                ORDER BY created_at, event_id
                """).query(String.class).list();
    }

    private BigInteger totalSupply() {
        PublicKey mint = PublicKey.fromBase58Encoded(required("LOCAL_SOLANA_MINT_ADDRESS"));
        AccountInfo<byte[]> account = rpc().getAccountInfo(Commitment.FINALIZED, mint).join();
        return unsigned(Mint.read(mint, account.data()).supply());
    }

    private BigInteger tokenBalance(String owner) {
        List<String> tokenAccounts = jdbc.sql("""
                SELECT DISTINCT CASE
                    WHEN source_owner = :owner THEN source_ata ELSE destination_ata END
                FROM solana_mint_attempt
                WHERE source_owner = :owner OR destination_owner = :owner
                """).param("owner", owner).query(String.class).list();
        BigInteger total = BigInteger.ZERO;
        for (String address : tokenAccounts) {
            PublicKey key = PublicKey.fromBase58Encoded(address);
            AccountInfo<byte[]> account = rpc().getAccountInfo(Commitment.FINALIZED, key).join();
            if (account != null && account.data() != null) {
                total = total.add(unsigned(TokenAccount.read(key, account.data()).amount()));
            }
        }
        return total;
    }

    private SolanaRpcClient rpc() {
        return SolanaRpcClient.build()
                .endpoint(URI.create(required("LOCAL_SOLANA_RPC_URI")))
                .requestTimeout(Duration.ofSeconds(5))
                .defaultCommitment(Commitment.CONFIRMED).createClient();
    }

    private BigInteger bankBalance(String bank, String account) {
        return jdbc.sql("""
                SELECT balance_cents FROM synthetic_bank_account
                WHERE bank_id = :bank AND account_id = :account
                """).param("bank", bank).param("account", account)
                .query(BigInteger.class).single();
    }

    private long transitionVersion(String workflowId, String status) {
        return jdbc.sql("""
                SELECT aggregate_version FROM usdzelle_workflow_transition
                WHERE workflow_id = :workflowId AND to_status = :status
                """).param("workflowId", UUID.fromString(workflowId))
                .param("status", status).query(Long.class).single();
    }

    private String workflowConclusion(String workflowId) {
        return jdbc.sql("""
                SELECT reconciliation_status FROM usdzelle_workflow_conclusion
                WHERE workflow_id = :workflowId
                """).param("workflowId", UUID.fromString(workflowId))
                .query(String.class).single();
    }

    private long count(String table) {
        if (!"solana_mint_attempt".equals(table)) {
            throw new IllegalArgumentException("unsupported table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private static String workflowRequest() {
        return """
                {
                  "amount": "100",
                  "currency": "USD",
                  "bankAccountReference": "synthetic-bank:USER_1_BANK_ACCOUNT",
                  "settlementNetwork": "SOLANA"
                }
                """;
    }

    private static String settlementRequest() {
        return """
                {
                  "amount": "100",
                  "currency": "USD",
                  "sourceBankAccountReference": "synthetic-bank:USER_1_BANK_ACCOUNT",
                  "destinationBankAccountReference": "synthetic-bank:USER_2_BANK_ACCOUNT",
                  "settlementNetwork": "SOLANA"
                }
                """;
    }

    private static RequestPostProcessor participant(String authority) {
        ParticipantPrincipal principal = new ParticipantPrincipal("local-demo", "USER_1");
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private static String id(MvcResult result, Pattern pattern) throws Exception {
        Matcher matcher = pattern.matcher(result.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private static BigInteger unsigned(long value) {
        return new BigInteger(Long.toUnsignedString(value));
    }

    private static String userOne() {
        return required("LOCAL_SOLANA_DESTINATION_OWNER");
    }

    private static String userTwo() {
        return required("LOCAL_SOLANA_TRANSFER_DESTINATION_OWNER");
    }

    private static String admin() {
        return required("LOCAL_SOLANA_REDEMPTION_OWNER");
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is required for the real local gate");
        }
        return value;
    }
}
