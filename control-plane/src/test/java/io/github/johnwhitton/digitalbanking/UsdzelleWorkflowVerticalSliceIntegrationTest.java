package io.github.johnwhitton.digitalbanking;

import java.io.IOException;
import java.math.BigInteger;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryRetryPolicy;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryHandler;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryWorker;
import io.github.johnwhitton.digitalbanking.application.port.ClockPort;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleChainEvidencePort;
import io.github.johnwhitton.digitalbanking.application.port.UsdzelleWorkflowRepository;
import io.github.johnwhitton.digitalbanking.controlplane.api.ParticipantPrincipal;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.workflow.UsdzelleWorkflow;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
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
import org.web3j.abi.FunctionEncoder;
import org.web3j.abi.FunctionReturnDecoder;
import org.web3j.abi.TypeReference;
import org.web3j.abi.datatypes.Address;
import org.web3j.abi.datatypes.Function;
import org.web3j.abi.datatypes.generated.Bytes32;
import org.web3j.abi.datatypes.generated.Uint256;
import org.web3j.crypto.ECKeyPair;
import org.web3j.crypto.Hash;
import org.web3j.crypto.Keys;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.Request;
import org.web3j.protocol.core.Response;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"local-demo", "local-ethereum", "local-demo-environment"})
class UsdzelleWorkflowVerticalSliceIntegrationTest {

    private static final Pattern WORKFLOW_ID = Pattern.compile(
            "\\\"workflowId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final Pattern TRANSFER_ID = Pattern.compile(
            "\\\"transferId\\\":\\\"([0-9a-f-]{36})\\\"");
    private static final List<Fixture> WALLETS = List.of(
            fixture("CONTRACT_OWNER"), fixture("ADMIN"),
            fixture("BANK_1"), fixture("BANK_2"),
            fixture("BANK_3"), fixture("BANK_4"),
            fixture("USER_WALLET_1"), fixture("USER_WALLET_2"),
            fixture("USER_WALLET_3"), fixture("USER_WALLET_4"));
    private static final PostgreSQLContainer POSTGRES;
    private static final LocalAnvil ANVIL;
    private static final String CONTRACT;
    private static final Path DEMO_SECRETS;
    private static final Path SENDER_TOKEN_FILE;
    private static final Path OPERATOR_TOKEN_FILE;
    private static final String SENDER_TOKEN = "a".repeat(64);
    private static final String OPERATOR_TOKEN = "b".repeat(64);

    static {
        try {
            DEMO_SECRETS = Files.createTempDirectory("phase-6d-test-secrets-");
            SENDER_TOKEN_FILE = DEMO_SECRETS.resolve("sender-token");
            OPERATOR_TOKEN_FILE = DEMO_SECRETS.resolve("operator-token");
            Files.writeString(SENDER_TOKEN_FILE, SENDER_TOKEN + System.lineSeparator());
            Files.writeString(OPERATOR_TOKEN_FILE, OPERATOR_TOKEN + System.lineSeparator());
            POSTGRES = new PostgreSQLContainer("postgres:17.10-alpine3.23")
                    .withDatabaseName("usdzelle_workflow_vertical")
                    .withUsername("usdzelle_workflow_test")
                    .withPassword("fixture-only-password")
                    .withStartupTimeout(Duration.ofSeconds(60));
            POSTGRES.start();
            ANVIL = LocalAnvil.start();
            try (Web3j client = ANVIL.client()) {
                String deployer = client.ethAccounts().send().getAccounts().getFirst()
                        .toLowerCase(java.util.Locale.ROOT);
                CONTRACT = deploy(client, deployer);
                Fixture admin = wallet("ADMIN");
                Fixture user = wallet("USER_WALLET_1");
                Fixture recipient = wallet("USER_WALLET_2");
                ANVIL.setBalance(admin.address(), new BigInteger("100000000000000000000"));
                ANVIL.setBalance(user.address(), new BigInteger("100000000000000000000"));
                ANVIL.setBalance(
                        recipient.address(), new BigInteger("100000000000000000000"));
                grant(client, deployer, CONTRACT, "MINTER_ROLE", admin.address());
                grant(client, deployer, CONTRACT, "BURNER_ROLE", admin.address());
            }
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }

    @DynamicPropertySource
    static void localProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.clean-disabled", () -> "true");
        registry.add("digital-banking.delivery-worker.enabled", () -> "false");
        registry.add("LOCAL_ETHEREUM_RPC_URL", ANVIL::endpoint);
        registry.add("LOCAL_ETHEREUM_CONTRACT_ADDRESS", () -> CONTRACT);
        registry.add("LOCAL_ETHEREUM_RECIPIENT_ADDRESS",
                () -> wallet("USER_WALLET_1").address());
        registry.add("digital-banking.local-demo-identity.sender-token-file",
                SENDER_TOKEN_FILE::toString);
        registry.add("digital-banking.local-demo-identity.operator-token-file",
                OPERATOR_TOKEN_FILE::toString);
        for (Fixture fixture : WALLETS) {
            registry.add("LOCAL_DEMO_" + fixture.name() + "_PRIVATE_KEY",
                    fixture::privateKey);
            registry.add("LOCAL_DEMO_" + fixture.name() + "_EXPECTED_ADDRESS",
                    fixture::address);
        }
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private OperationDeliveryQueue queue;

    @Autowired
    private OperationDeliveryHandler handler;

    @Autowired
    private ClockPort clock;

    @Autowired
    private UsdzelleWorkflowRepository workflows;

    @Autowired
    private UsdzelleChainEvidencePort chainEvidence;

    @AfterAll
    static void stopInfrastructure() {
        ANVIL.close();
        POSTGRES.stop();
        try {
            Files.deleteIfExists(SENDER_TOKEN_FILE);
            Files.deleteIfExists(OPERATOR_TOKEN_FILE);
            Files.deleteIfExists(DEMO_SECRETS);
        } catch (IOException ignored) {
            // Disposable test secrets contain no retained evidence.
        }
    }

    @Test
    void provesPhase6bChildrenAndTheConsolidatedSettlementOnlyTransfer()
            throws Exception {
        String acquisition = accept("acquisitions", "usdzelle:acquire", "phase-6b-acquire");
        driveUntilStep(acquisition, "MINT_ACCOUNTING_POST");
        assertRetainedChainEvidenceIsRevalidated(acquisition);
        driveToCompletion(acquisition);

        assertEquals(new BigInteger("1000"), balanceOf(wallet("USER_WALLET_1").address()));
        assertEquals(new BigInteger("1000"), totalSupply());
        assertEquals(new BigInteger("9000"), bankBalance());

        String redemption = accept("redemptions", "usdzelle:redeem", "phase-6b-redeem");
        driveToCompletion(redemption);

        assertEquals(BigInteger.ZERO, balanceOf(wallet("USER_WALLET_1").address()));
        assertEquals(BigInteger.ZERO, balanceOf(wallet("ADMIN").address()));
        assertEquals(BigInteger.ZERO, totalSupply());
        assertEquals(new BigInteger("10000"), bankBalance());
        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM synthetic_bank_operation
                WHERE operation_kind = 'DEPOSIT' AND operation_status = 'SUCCEEDED'
                """).query(Long.class).single());
        assertTrue(transitionVersion(redemption, "PAYOUT_ACCOUNTED")
                < transitionVersion(redemption, "BURN_CONFIRMED"));
        assertEquals("RECONCILED", jdbc.sql("""
                SELECT reconciliation_status FROM usdzelle_workflow_conclusion
                WHERE workflow_id = :workflowId
                """).param("workflowId", UUID.fromString(redemption))
                .query(String.class).single());

        MvcResult replay = mvc.perform(post("/v1/usdzelle/redemptions")
                        .with(participant("usdzelle:redeem"))
                        .header("Idempotency-Key", "phase-6b-redeem")
                        .contentType("application/json").content(request()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.workflowId").value(redemption))
                .andReturn();
        assertEquals(redemption, workflowId(replay));
        assertEquals(1L, jdbc.sql("""
                SELECT count(*) FROM synthetic_bank_operation
                WHERE operation_kind = 'DEPOSIT' AND operation_status = 'SUCCEEDED'
                """).query(Long.class).single());

        mvc.perform(get("/v1/usdzelle/redemptions/{workflowId}", redemption)
                        .with(participant("usdzelle:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.progress.reconciliationStatus")
                        .value("RECONCILED"))
                .andExpect(jsonPath("$.userWallet").doesNotExist())
                .andExpect(jsonPath("$.adminWallet").doesNotExist());

        long bankEffectsBefore = count("synthetic_bank_operation");
        long tokenOperationsBefore = count("token_operation");
        long walletTransfersBefore = count("wallet_transfer_operation");
        long mintObservationsBefore = count("ethereum_mint_observation");
        long transferObservationsBefore = count("ethereum_wallet_transfer_observation");
        long burnObservationsBefore = count("ethereum_burn_observation");

        String transfer = acceptSettlement();
        driveSettlementToCompletion(transfer);

        assertEquals(BigInteger.ZERO, bankBalance("BANK_1", "USER_1_BANK_ACCOUNT"));
        assertEquals(new BigInteger("10000"),
                bankBalance("BANK_2", "USER_2_BANK_ACCOUNT"));
        assertEquals(BigInteger.ZERO, balanceOf(wallet("USER_WALLET_1").address()));
        assertEquals(BigInteger.ZERO, balanceOf(wallet("USER_WALLET_2").address()));
        assertEquals(BigInteger.ZERO, balanceOf(wallet("ADMIN").address()));
        assertEquals(BigInteger.ZERO, totalSupply());
        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM accounting_ledger_account
                WHERE balance_cents <> 0
                """).query(Long.class).single());
        assertEquals(0L, jdbc.sql("""
                SELECT count(*) FROM accounting_operational_position
                WHERE quantity_cents <> 0
                """).query(Long.class).single());
        assertEquals(bankEffectsBefore + 2, count("synthetic_bank_operation"));
        assertEquals(tokenOperationsBefore + 2, count("token_operation"));
        assertEquals(walletTransfersBefore + 2, count("wallet_transfer_operation"));
        assertEquals(mintObservationsBefore + 1, count("ethereum_mint_observation"));
        assertEquals(transferObservationsBefore + 2,
                count("ethereum_wallet_transfer_observation"));
        assertEquals(burnObservationsBefore + 1, count("ethereum_burn_observation"));
        assertEquals(3L, jdbc.sql("""
                SELECT count(*) FROM settlement_transfer_boundary
                WHERE transfer_id = :transferId AND child_reference IS NOT NULL
                """).param("transferId", UUID.fromString(transfer))
                .query(Long.class).single());
        assertEquals("RECONCILED", jdbc.sql("""
                SELECT reconciliation_status FROM settlement_transfer_conclusion
                WHERE transfer_id = :transferId
                """).param("transferId", UUID.fromString(transfer))
                .query(String.class).single());

        mvc.perform(get("/v1/transfers/{transferId}", transfer)
                        .with(participant("transfer:read")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.settlementOrchestration.status")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.settlementOrchestration.bankStatus")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.settlementOrchestration.blockchainStatus")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.settlementOrchestration.accountingStatus")
                        .value("COMPLETED"))
                .andExpect(jsonPath("$.settlementOrchestration.reconciliationStatus")
                        .value("RECONCILED"))
                .andExpect(jsonPath("$.settlementOrchestration.manualReviewRequired")
                        .value(false))
                .andExpect(jsonPath("$.settlementOrchestration.senderWallet")
                        .doesNotExist())
                .andExpect(jsonPath("$.settlementOrchestration.recipientParticipant")
                        .doesNotExist());

        mvc.perform(get("/local/v1/demo/status"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/local/v1/demo/status")
                        .header("Authorization", "Bearer " + SENDER_TOKEN))
                .andExpect(status().isForbidden());
        mvc.perform(get("/local/v1/demo/status")
                        .header("Authorization", "Bearer " + OPERATOR_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.network").value("LOCAL_ANVIL"))
                .andExpect(jsonPath("$.chainId").value("31337"))
                .andExpect(jsonPath("$.contractAddress").value(CONTRACT))
                .andExpect(jsonPath("$.bankBalancesCents.USER_1_BANK_ACCOUNT")
                        .value("0"))
                .andExpect(jsonPath("$.bankBalancesCents.USER_2_BANK_ACCOUNT")
                        .value("10000"))
                .andExpect(jsonPath("$.ledgerBalancesCents.RESERVE_CASH_ASSET")
                        .value("0"))
                .andExpect(jsonPath("$.operationalPositionsCents.ADMIN_REDEMPTION_CUSTODY_PENDING_BURN")
                        .value("0"))
                .andExpect(jsonPath("$.tokenBalancesAtomic.userOne").value("0"))
                .andExpect(jsonPath("$.tokenBalancesAtomic.userTwo").value("0"))
                .andExpect(jsonPath("$.tokenBalancesAtomic.admin").value("0"))
                .andExpect(jsonPath("$.tokenBalancesAtomic.totalSupply").value("0"))
                .andExpect(jsonPath("$.confirmedEffects.withdrawals").value(2))
                .andExpect(jsonPath("$.confirmedEffects.mints").value(2))
                .andExpect(jsonPath("$.confirmedEffects.userTransfers").value(1))
                .andExpect(jsonPath("$.confirmedEffects.custodyTransfers").value(2))
                .andExpect(jsonPath("$.confirmedEffects.payouts").value(2))
                .andExpect(jsonPath("$.confirmedEffects.burns").value(2))
                .andExpect(jsonPath("$.latestAcquisition.reconciliation")
                        .value("RECONCILED"))
                .andExpect(jsonPath("$.latestRedemption.reconciliation")
                        .value("RECONCILED"))
                .andExpect(jsonPath("$.latestSettlement.reconciliation")
                        .value("RECONCILED"))
                .andExpect(jsonPath("$.payoutBeforeBurn").value(true))
                .andExpect(jsonPath("$.privateKey").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(jsonPath("$.rawTransaction").doesNotExist());
    }

    private String acceptSettlement() throws Exception {
        MvcResult result = mvc.perform(post("/v1/transfers")
                        .with(participant("transfer:create"))
                        .header("Idempotency-Key", "phase-6c-settlement")
                        .contentType("application/json").content(transferRequest()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.settlementOrchestration.status")
                        .value("ACCEPTED"))
                .andReturn();
        return transferId(result);
    }

    private void driveSettlementToCompletion(String transferId) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        OperationDeliveryWorker worker = worker(failure);
        long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
        while (System.nanoTime() < deadline) {
            worker.poll();
            assertNull(failure.get(), () -> "delivery failure: " + failure.get());
            String status = jdbc.sql("""
                    SELECT parent_status FROM settlement_transfer
                    WHERE transfer_id = :transferId
                    """).param("transferId", UUID.fromString(transferId))
                    .query(String.class).single();
            if ("COMPLETED".equals(status)) {
                return;
            }
            if ("MANUAL_REVIEW".equals(status) || "FAILED_NO_EFFECT".equals(status)) {
                throw new AssertionError("settlement transfer stopped in " + status);
            }
            Thread.sleep(2);
        }
        throw new AssertionError(
                "settlement transfer did not complete before the bounded deadline");
    }

    private String accept(String resource, String authority, String key) throws Exception {
        MvcResult result = mvc.perform(post("/v1/usdzelle/" + resource)
                        .with(participant(authority))
                        .header("Idempotency-Key", key)
                        .contentType("application/json").content(request()))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("ACCEPTED"))
                .andReturn();
        return workflowId(result);
    }

    private void driveToCompletion(String workflowId) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        OperationDeliveryWorker worker = worker(failure);
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            worker.poll();
            assertNull(failure.get(), () -> "delivery failure: " + failure.get());
            String status = jdbc.sql("""
                    SELECT workflow_status FROM usdzelle_workflow
                    WHERE workflow_id = :workflowId
                    """).param("workflowId", UUID.fromString(workflowId))
                    .query(String.class).single();
            if ("COMPLETED".equals(status)) {
                return;
            }
            if ("MANUAL_REVIEW".equals(status) || "FAILED_NO_EFFECT".equals(status)) {
                throw new AssertionError("workflow stopped in " + status);
            }
            Thread.sleep(2);
        }
        throw new AssertionError("workflow did not complete before the bounded deadline");
    }

    private void driveUntilStep(String workflowId, String expectedStep) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        OperationDeliveryWorker worker = worker(failure);
        long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
        while (System.nanoTime() < deadline) {
            String current = jdbc.sql("""
                    SELECT step_kind FROM usdzelle_workflow_step
                    WHERE workflow_id = :workflowId AND step_status <> 'COMPLETED'
                    ORDER BY step_sequence LIMIT 1
                    """).param("workflowId", UUID.fromString(workflowId))
                    .query(String.class).single();
            if (expectedStep.equals(current)) {
                return;
            }
            worker.poll();
            assertNull(failure.get(), () -> "delivery failure: " + failure.get());
            Thread.sleep(2);
        }
        throw new AssertionError("workflow did not reach " + expectedStep);
    }

    private void assertRetainedChainEvidenceIsRevalidated(String workflowId) {
        UsdzelleWorkflow workflow = workflows.findById(
                new UsdzelleWorkflow.Id(UUID.fromString(workflowId))).orElseThrow();
        OperationId mint = OperationId.from(workflow.steps().stream()
                .filter(step -> step.kind() == UsdzelleWorkflow.StepKind.MINT)
                .findFirst().orElseThrow().childReference().orElseThrow().value());
        chainEvidence.register(UsdzelleChainEvidencePort.Effect.MINT, workflow, mint);
        String retainedHash = jdbc.sql("""
                SELECT block_hash FROM usdzelle_chain_state_observation
                WHERE workflow_id = :workflowId AND effect_type = 'MINT'
                """).param("workflowId", UUID.fromString(workflowId))
                .query(String.class).single();
        jdbc.sql("""
                UPDATE usdzelle_chain_state_observation
                SET block_hash = :blockHash
                WHERE workflow_id = :workflowId AND effect_type = 'MINT'
                """).param("blockHash", "0x" + "0".repeat(64))
                .param("workflowId", UUID.fromString(workflowId)).update();
        assertThrows(IllegalStateException.class, () -> chainEvidence.register(
                UsdzelleChainEvidencePort.Effect.MINT, workflow, mint));
        jdbc.sql("""
                UPDATE usdzelle_chain_state_observation
                SET block_hash = :blockHash
                WHERE workflow_id = :workflowId AND effect_type = 'MINT'
                """).param("blockHash", retainedHash)
                .param("workflowId", UUID.fromString(workflowId)).update();
    }

    private OperationDeliveryWorker worker(AtomicReference<Exception> failure) {
        return new OperationDeliveryWorker(
                queue, handler, (delivery, thrown) ->
                        failure.compareAndSet(null, thrown), clock,
                new DeliveryRetryPolicy(
                        100, Duration.ofMillis(1), Duration.ofMillis(10)),
                Duration.ofSeconds(5), "phase-6b-proof", 10);
    }

    private static String request() {
        return """
                {
                  "amount": "10",
                  "currency": "USD",
                  "bankAccountReference": "synthetic-bank:USER_1_BANK_ACCOUNT",
                  "settlementNetwork": "ETHEREUM"
                }
                """;
    }

    private static String transferRequest() {
        return """
                {
                  "amount": "100",
                  "currency": "USD",
                  "sourceBankAccountReference": "synthetic-bank:USER_1_BANK_ACCOUNT",
                  "destinationBankAccountReference": "synthetic-bank:USER_2_BANK_ACCOUNT",
                  "settlementNetwork": "ETHEREUM"
                }
                """;
    }

    private static RequestPostProcessor participant(String authority) {
        ParticipantPrincipal principal = new ParticipantPrincipal("local-demo", "USER_1");
        return authentication(new UsernamePasswordAuthenticationToken(
                principal, null, List.of(new SimpleGrantedAuthority(authority))));
    }

    private static String workflowId(MvcResult result) throws Exception {
        Matcher matcher = WORKFLOW_ID.matcher(result.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private static String transferId(MvcResult result) throws Exception {
        Matcher matcher = TRANSFER_ID.matcher(result.getResponse().getContentAsString());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private BigInteger bankBalance() {
        return bankBalance("BANK_1", "USER_1_BANK_ACCOUNT");
    }

    private BigInteger bankBalance(String bankId, String accountId) {
        return jdbc.sql("""
                SELECT balance_cents FROM synthetic_bank_account
                WHERE bank_id = :bankId AND account_id = :accountId
                """).param("bankId", bankId).param("accountId", accountId)
                .query(BigInteger.class).single();
    }

    private long count(String table) {
        if (!List.of(
                "synthetic_bank_operation", "token_operation",
                "wallet_transfer_operation", "ethereum_mint_observation",
                "ethereum_wallet_transfer_observation", "ethereum_burn_observation")
                .contains(table)) {
            throw new IllegalArgumentException("unsupported count table");
        }
        return jdbc.sql("SELECT count(*) FROM " + table).query(Long.class).single();
    }

    private long transitionVersion(String workflowId, String status) {
        return jdbc.sql("""
                SELECT aggregate_version FROM usdzelle_workflow_transition
                WHERE workflow_id = :workflowId AND to_status = :status
                """).param("workflowId", UUID.fromString(workflowId))
                .param("status", status).query(Long.class).single();
    }

    private static BigInteger balanceOf(String address) throws Exception {
        return callUint(new Function(
                "balanceOf", List.of(new Address(address)),
                List.of(new TypeReference<Uint256>() { })));
    }

    private static BigInteger totalSupply() throws Exception {
        return callUint(new Function(
                "totalSupply", List.of(), List.of(new TypeReference<Uint256>() { })));
    }

    private static BigInteger callUint(Function function) throws Exception {
        try (Web3j client = ANVIL.client()) {
            String value = client.ethCall(
                            org.web3j.protocol.core.methods.request.Transaction
                                    .createEthCallTransaction(
                                            wallet("ADMIN").address(), CONTRACT,
                                            FunctionEncoder.encode(function)),
                            DefaultBlockParameterName.LATEST)
                    .send().getValue();
            return (BigInteger) FunctionReturnDecoder.decode(
                    value, function.getOutputParameters()).getFirst().getValue();
        }
    }

    private static String deploy(Web3j client, String deployer) throws Exception {
        String constructor = FunctionEncoder.encodeConstructor(List.of(new Address(deployer)));
        EthSendTransaction sent = client.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction.createContractTransaction(
                        deployer, null, BigInteger.valueOf(2_000_000_000L),
                        BigInteger.valueOf(6_000_000), BigInteger.ZERO,
                        forgeBytecode() + Numeric.cleanHexPrefix(constructor))).send();
        if (sent.hasError()) {
            throw new IllegalStateException("local reference contract deployment failed");
        }
        TransactionReceipt receipt = waitForReceipt(client, sent.getTransactionHash());
        if (!receipt.isStatusOK()) {
            throw new IllegalStateException("local reference contract deployment reverted");
        }
        return receipt.getContractAddress().toLowerCase(java.util.Locale.ROOT);
    }

    private static void grant(
            Web3j client, String deployer, String contract,
            String roleName, String address) throws Exception {
        Function function = new Function(
                "grantRole",
                List.of(new Bytes32(Numeric.hexStringToByteArray(
                        Hash.sha3String(roleName))), new Address(address)), List.of());
        EthSendTransaction sent = client.ethSendTransaction(
                org.web3j.protocol.core.methods.request.Transaction
                        .createFunctionCallTransaction(
                                deployer, null, BigInteger.valueOf(2_000_000_000L),
                                BigInteger.valueOf(200_000), contract, BigInteger.ZERO,
                                FunctionEncoder.encode(function))).send();
        if (sent.hasError() || !waitForReceipt(client, sent.getTransactionHash()).isStatusOK()) {
            throw new IllegalStateException("local role grant failed");
        }
    }

    private static TransactionReceipt waitForReceipt(Web3j client, String hash)
            throws Exception {
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
                        .resolve("../contracts/evm").normalize().toFile())
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        if (!process.waitFor(30, TimeUnit.SECONDS)
                || process.exitValue() != 0 || !output.matches("0x[0-9a-fA-F]+")) {
            process.destroyForcibly();
            throw new IllegalStateException("Foundry did not produce reference bytecode");
        }
        return output;
    }

    private static Fixture fixture(String name) {
        try {
            ECKeyPair pair = Keys.createEcKeyPair();
            return new Fixture(
                    name,
                    Numeric.toHexStringNoPrefixZeroPadded(pair.getPrivateKey(), 64),
                    "0x" + Keys.getAddress(pair.getPublicKey()));
        } catch (Exception failure) {
            throw new IllegalStateException("in-memory fixture key generation failed", failure);
        }
    }

    private static Fixture wallet(String name) {
        return WALLETS.stream().filter(candidate -> candidate.name().equals(name))
                .findFirst().orElseThrow();
    }

    private record Fixture(String name, String privateKey, String address) {
    }

    private static final class LocalAnvil implements AutoCloseable {
        private final Process process;
        private final String endpoint;

        private LocalAnvil(Process process, String endpoint) {
            this.process = process;
            this.endpoint = endpoint;
        }

        static LocalAnvil start() throws Exception {
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
            LocalAnvil node = new LocalAnvil(process, "http://127.0.0.1:" + port);
            for (int attempt = 0; attempt < 100; attempt++) {
                try (Web3j client = node.client()) {
                    if (client.ethChainId().send().getChainId()
                            .equals(BigInteger.valueOf(31_337))) {
                        return node;
                    }
                } catch (Exception ignored) {
                    // Bounded readiness polling; child output is intentionally discarded.
                }
                Thread.sleep(25);
            }
            node.close();
            throw new IllegalStateException("Anvil did not start on loopback");
        }

        Web3j client() {
            return Web3j.build(new HttpService(endpoint));
        }

        String endpoint() {
            return endpoint;
        }

        void setBalance(String address, BigInteger balance) throws IOException {
            HttpService service = new HttpService(endpoint);
            try {
                Request<Object, Response<String>> request = new Request<>(
                        "anvil_setBalance", List.of(address, Numeric.encodeQuantity(balance)),
                        service, castResponseType());
                Response<String> response = request.send();
                if (response.hasError()) {
                    throw new IllegalStateException("Anvil fixture funding failed");
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
                if (!process.waitFor(5, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            }
        }
    }
}
