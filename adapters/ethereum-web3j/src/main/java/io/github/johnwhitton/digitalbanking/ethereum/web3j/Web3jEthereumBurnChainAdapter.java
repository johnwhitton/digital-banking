package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationAttempt;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationKind;
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.web3j.crypto.Hash;
import org.web3j.protocol.Web3j;
import org.web3j.protocol.core.DefaultBlockParameter;
import org.web3j.protocol.core.DefaultBlockParameterName;
import org.web3j.protocol.core.methods.response.EthBlock;
import org.web3j.protocol.core.methods.response.EthSendTransaction;
import org.web3j.protocol.core.methods.response.Log;
import org.web3j.protocol.core.methods.response.Transaction;
import org.web3j.protocol.core.methods.response.TransactionReceipt;
import org.web3j.protocol.http.HttpService;
import org.web3j.utils.Numeric;

/** Anvil-only burn adapter; custody evidence is consumed before attempt preparation. */
public final class Web3jEthereumBurnChainAdapter implements ChainPort, AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();
    private static final String TRANSFER_TOPIC = Hash.sha3String(
            "Transfer(address,address,uint256)").toLowerCase(Locale.ROOT);
    private static final String ZERO_ADDRESS = "0x" + "0".repeat(40);

    private final EthereumBurnAttemptStore attempts;
    private final EthereumRedemptionBalanceStore balances;
    private final EthereumTokenStateReader tokenState;
    private final Web3j submissionClient;
    private final Web3j observationClient;
    private final SubmissionTransport submissionTransport;
    private final SubmissionReadiness submissionReadiness;
    private final EthereumTransactionCodec codec = new EthereumTransactionCodec();
    private final ConcurrentMap<AttemptKey, String> signedTransactions =
            new ConcurrentHashMap<>();
    private final Configuration configuration;
    private final Clock clock;

    public static Web3jEthereumBurnChainAdapter local(
            DataSource dataSource, String loopbackRpcUrl,
            Configuration configuration, Clock clock) {
        Web3j submission = Web3j.build(new HttpService(loopbackRpcUrl));
        Web3j observation = Web3j.build(new HttpService(loopbackRpcUrl));
        try {
            return new Web3jEthereumBurnChainAdapter(
                    dataSource, submission, observation, configuration, clock);
        } catch (RuntimeException failure) {
            submission.shutdown();
            observation.shutdown();
            throw failure;
        }
    }

    public Web3jEthereumBurnChainAdapter(
            DataSource dataSource, Web3j submissionClient, Web3j observationClient,
            Configuration configuration, Clock clock) {
        this(dataSource, submissionClient, observationClient,
                raw -> submissionClient.ethSendRawTransaction(raw).send(),
                () -> requireConfiguredChain(
                        submissionClient, configuration.chainId(), "submission"),
                configuration, clock);
    }

    public Web3jEthereumBurnChainAdapter(
            DataSource dataSource, Web3j submissionClient, Web3j observationClient,
            SubmissionTransport submissionTransport,
            Configuration configuration, Clock clock) {
        this(dataSource, submissionClient, observationClient, submissionTransport,
                () -> requireConfiguredChain(
                        submissionClient, configuration.chainId(), "submission"),
                configuration, clock);
    }

    Web3jEthereumBurnChainAdapter(
            DataSource dataSource, Web3j submissionClient, Web3j observationClient,
            SubmissionTransport submissionTransport,
            SubmissionReadiness submissionReadiness,
            Configuration configuration, Clock clock) {
        attempts = new EthereumBurnAttemptStore(dataSource);
        balances = new EthereumRedemptionBalanceStore(dataSource);
        this.submissionClient = Objects.requireNonNull(
                submissionClient, "submissionClient");
        this.observationClient = Objects.requireNonNull(
                observationClient, "observationClient");
        tokenState = new EthereumTokenStateReader(this.observationClient);
        this.submissionTransport = Objects.requireNonNull(
                submissionTransport, "submissionTransport");
        this.submissionReadiness = Objects.requireNonNull(
                submissionReadiness, "submissionReadiness");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        verifyChain(submissionClient, "submission");
        verifyChain(observationClient, "observation");
    }

    @Override
    public ChainCapabilities capabilities(String routeVersion) {
        if (!configuration.policyVersion().equals(routeVersion)) {
            throw new IllegalArgumentException("unsupported local Ethereum policy version");
        }
        return new ChainCapabilities(false, true, true);
    }

    @Override
    public PreparedAttempt prepare(
            UUID deliveryId, TokenOperation operation, OperationAttempt attempt) {
        validateBurn(operation, attempt);
        Optional<EthereumBurnAttemptStore.AttemptRow> existing = attempts.find(
                operation.operationId(), attempt.attemptId());
        if (existing.isPresent()) {
            EthereumBurnAttemptStore.AttemptRow retained = existing.orElseThrow();
            validateRetained(retained, operation);
            if (!retained.deliveryId().equals(deliveryId)) {
                throw new IllegalStateException(
                        "burn attempt is already bound to another delivery");
            }
            return prepared(retained);
        }
        try {
            BigInteger networkNonce = submissionClient.ethGetTransactionCount(
                            configuration.adminAddress(),
                            DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            EthereumBurnAttemptStore.AttemptRow retained = attempts.prepare(
                    deliveryId, operation, attempt.attemptId(), configuration.chainId(),
                    configuration.adminAddress(), networkNonce, now(),
                    preparation -> draft(operation, preparation.nonce()));
            validateRetained(retained, operation);
            return prepared(retained);
        } catch (IOException failure) {
            throw new IllegalStateException("local Ethereum burn nonce inquiry failed", failure);
        }
    }

    @Override
    public Optional<SignedAttempt> findSignedAttempt(AttemptIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return attempts.find(identity.operationId(), identity.attemptId())
                .filter(row -> row.transactionHash() != null)
                .filter(row -> row.status() != EthereumBurnAttemptStore.AttemptStatus.SIGNED
                        || signedTransactions.containsKey(key(row)))
                .map(this::signedAttempt);
    }

    @Override
    public SignedAttempt attachSignature(
            AttemptIdentity identity, AuthorizedSignature signature) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(signature, "signature");
        EthereumBurnAttemptStore.AttemptRow current = attempts.find(
                        identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "prepared burn attempt was not found"));
        String expected = normalizeAddress(signature.expectedSignerReference());
        if (!current.adminAddress().equals(expected)) {
            throw new IllegalArgumentException(
                    "authorized signer does not match durable ADMIN attempt");
        }
        EthereumTransactionCodec.SignedTransaction encoded = codec.encodeSigned(
                transaction(current), signature.bytes(), expected);
        String transactionHash = Numeric.toHexString(encoded.hash())
                .toLowerCase(Locale.ROOT);
        String rawTransaction = Numeric.toHexString(encoded.bytes())
                .toLowerCase(Locale.ROOT);
        EthereumBurnAttemptStore.AttemptRow retained = attempts.attachSignature(
                current, sha256(signature.bytes()), signature.encoding(),
                transactionHash, now());
        signedTransactions.put(key(retained), rawTransaction);
        return signedAttempt(retained);
    }

    @Override
    public SubmissionResult submitOnce(SignedAttempt signedAttempt) {
        Objects.requireNonNull(signedAttempt, "signedAttempt");
        EthereumBurnAttemptStore.AttemptRow current = attempts.find(
                        signedAttempt.operationId(), signedAttempt.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "signed burn attempt was not found"));
        if (current.status() != EthereumBurnAttemptStore.AttemptStatus.SIGNED) {
            return retainedSubmission(current);
        }
        String rawTransaction = signedTransactions.get(key(current));
        if (rawTransaction == null) {
            return new SubmissionResult(
                    SubmissionClassification.RETRYABLE_NO_EFFECT, null,
                    evidence(current, "signed-material-reauthorization-required"));
        }
        try {
            submissionReadiness.verify();
        } catch (IOException unavailable) {
            return new SubmissionResult(
                    SubmissionClassification.RETRYABLE_NO_EFFECT, null,
                    evidence(current, "submission-preflight-unavailable"));
        }
        EthereumBurnAttemptStore.SubmissionClaim claim =
                attempts.claimSubmission(current, now());
        if (!claim.claimed()) {
            return retainedSubmission(claim.attempt());
        }
        try {
            EthSendTransaction response = submissionTransport.send(rawTransaction);
            if (response.hasError()) {
                EthereumBurnAttemptStore.AttemptStatus status = ambiguousRpcError(
                        response.getError().getMessage())
                        ? EthereumBurnAttemptStore.AttemptStatus.AMBIGUOUS
                        : EthereumBurnAttemptStore.AttemptStatus.REJECTED;
                return retainedSubmission(attempts.recordSubmission(
                        claim.attempt(), status,
                        status == EthereumBurnAttemptStore.AttemptStatus.AMBIGUOUS
                                ? "rpc-outcome-ambiguous" : "rpc-rejected", now()));
            }
            String responseHash = normalizeHash(response.getTransactionHash());
            EthereumBurnAttemptStore.AttemptStatus status = responseHash.equals(
                    claim.attempt().transactionHash())
                    ? EthereumBurnAttemptStore.AttemptStatus.ACCEPTED
                    : EthereumBurnAttemptStore.AttemptStatus.AMBIGUOUS;
            return retainedSubmission(attempts.recordSubmission(
                    claim.attempt(), status,
                    status == EthereumBurnAttemptStore.AttemptStatus.ACCEPTED
                            ? "rpc-accepted" : "rpc-hash-mismatch", now()));
        } catch (IOException failure) {
            return retainedSubmission(attempts.recordSubmission(
                    claim.attempt(), EthereumBurnAttemptStore.AttemptStatus.AMBIGUOUS,
                    "rpc-response-unavailable", now()));
        } finally {
            signedTransactions.remove(key(claim.attempt()), rawTransaction);
        }
    }

    @Override
    public InquiryResult inquire(AttemptIdentity identity) {
        EthereumBurnAttemptStore.AttemptRow attempt = attempts.find(
                        identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "burn attempt was not found"));
        NativeIdentity nativeIdentity = new NativeIdentity(attempt.transactionHash());
        try {
            Optional<Transaction> transaction = observationClient
                    .ethGetTransactionByHash(attempt.transactionHash())
                    .send().getTransaction();
            return new InquiryResult(
                    attempt.operationId(), attempt.attemptId(), Optional.of(nativeIdentity),
                    RetrySafety.REQUIRES_OBSERVATION,
                    evidence(attempt, transaction.isPresent()
                            ? "inquiry-found" : "inquiry-pending"));
        } catch (IOException failure) {
            return new InquiryResult(
                    attempt.operationId(), attempt.attemptId(), Optional.of(nativeIdentity),
                    RetrySafety.REQUIRES_OBSERVATION,
                    evidence(attempt, "inquiry-unavailable"));
        }
    }

    @Override
    public Observation observe(ObservationRequest request) {
        EthereumBurnAttemptStore.AttemptRow attempt = attempts.find(
                        request.operationId(), request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "burn attempt was not found"));
        if (!request.nativeIdentity().value().equals(attempt.transactionHash())
                || !request.policyVersion().equals(
                        attempt.observationPolicyVersion())) {
            throw new IllegalArgumentException(
                    "burn observation identity or policy does not match attempt");
        }
        ObservationEvidence observed;
        try {
            observed = observe(attempt);
            if (observed.classification() == ObservationClassification.CONFIRMED) {
                EthereumRedemptionBalanceStore.Context context = balances
                        .findByBurn(attempt.operationId()).orElseThrow(() ->
                                new IllegalStateException(
                                        "redemption balance context is unavailable"));
                balances.record(EthereumRedemptionBalanceStore.Stage.AFTER_BURN,
                        context, tokenState.at(context,
                                observed.blockNumber().orElseThrow(), now()));
                if (!balances.burnDeltaMatches(context.correlationId())) {
                    observed = observed.reclassified(
                            ObservationClassification.MISMATCHED,
                            "redemption-burn-balance-mismatch");
                }
            }
        } catch (IOException failure) {
            observed = pending("observer-unavailable", Optional.empty());
        }
        Instant now = now();
        EvidenceRef evidence = evidence(attempt, observed.evidenceKind());
        attempts.recordObservation(attempt, observed.toDraft(), evidence.value(), now);
        return new Observation(
                attempt.operationId(), attempt.attemptId(),
                new NativeIdentity(attempt.transactionHash()), observed.classification(),
                attempt.observationPolicyVersion(), now, List.of(evidence));
    }

    private ObservationEvidence observe(EthereumBurnAttemptStore.AttemptRow attempt)
            throws IOException {
        Optional<Transaction> transaction = observationClient
                .ethGetTransactionByHash(attempt.transactionHash()).send().getTransaction();
        if (transaction.isEmpty()) {
            return pending("transaction-absent", Optional.empty());
        }
        Transaction tx = transaction.orElseThrow();
        Optional<TransactionEvidence> transactionEvidence = transactionEvidence(tx);
        Optional<TransactionReceipt> receipt = observationClient
                .ethGetTransactionReceipt(attempt.transactionHash()).send()
                .getTransactionReceipt();
        if (receipt.isEmpty()) {
            return pending("receipt-pending", transactionEvidence);
        }
        TransactionReceipt mined = receipt.orElseThrow();
        Optional<BigInteger> blockNumber = Optional.ofNullable(mined.getBlockNumber());
        Optional<String> blockHash = normalizedHash(mined.getBlockHash());
        Optional<String> receiptDigest = receiptDigest(mined);
        Optional<MatchedBurnLog> matchedLog = matchingBurnLog(attempt, mined);
        if (!mined.isStatusOK()) {
            return observed(ObservationClassification.REVERTED, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "receipt-reverted");
        }
        if (!matchesTransaction(attempt, tx, mined) || matchedLog.isEmpty()) {
            return observed(ObservationClassification.MISMATCHED, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "receipt-intent-mismatch");
        }
        if (blockNumber.isEmpty() || blockHash.isEmpty()) {
            return observed(ObservationClassification.ABSENT_OR_PENDING,
                    blockNumber, blockHash, Optional.empty(), mined, receiptDigest,
                    transactionEvidence, matchedLog, "receipt-block-pending");
        }
        EthBlock.Block canonical = observationClient.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber.orElseThrow()), false)
                .send().getBlock();
        if (canonical == null || !normalizeHash(canonical.getHash())
                .equals(blockHash.orElseThrow())) {
            return observed(ObservationClassification.ORPHANED, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "block-not-canonical");
        }
        BigInteger latest = observationClient.ethBlockNumber().send().getBlockNumber();
        BigInteger confirmations = latest.subtract(blockNumber.orElseThrow())
                .add(BigInteger.ONE);
        if (confirmations.compareTo(
                BigInteger.valueOf(attempt.requiredConfirmations())) < 0) {
            return observed(ObservationClassification.ABSENT_OR_PENDING,
                    blockNumber, blockHash, Optional.of(confirmations), mined,
                    receiptDigest, transactionEvidence, matchedLog,
                    "confirmations-pending");
        }
        EthBlock.Block rechecked = observationClient.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber.orElseThrow()), false)
                .send().getBlock();
        if (rechecked == null || !normalizeHash(rechecked.getHash())
                .equals(blockHash.orElseThrow())) {
            return observed(ObservationClassification.ORPHANED, blockNumber, blockHash,
                    Optional.of(confirmations), mined, receiptDigest,
                    transactionEvidence, matchedLog, "block-recheck-orphaned");
        }
        return observed(ObservationClassification.CONFIRMED, blockNumber, blockHash,
                Optional.of(confirmations), mined, receiptDigest, transactionEvidence,
                matchedLog, "burn-confirmed");
    }

    private static boolean matchesTransaction(
            EthereumBurnAttemptStore.AttemptRow attempt,
            Transaction transaction, TransactionReceipt receipt) {
        try {
            Long observedChainId = transaction.getChainId();
            return normalizeHash(transaction.getHash()).equals(attempt.transactionHash())
                    && observedChainId != null
                    && observedChainId == attempt.chainId().longValueExact()
                    && equalsAddress(transaction.getFrom(), attempt.adminAddress())
                    && equalsAddress(transaction.getTo(), attempt.contractAddress())
                    && attempt.nonce().equals(transaction.getNonce())
                    && BigInteger.ZERO.equals(transaction.getValue())
                    && attempt.calldata().equalsIgnoreCase(transaction.getInput())
                    && equalsAddress(receipt.getFrom(), attempt.adminAddress())
                    && equalsAddress(receipt.getTo(), attempt.contractAddress())
                    && normalizeHash(receipt.getTransactionHash())
                            .equals(attempt.transactionHash());
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static Optional<MatchedBurnLog> matchingBurnLog(
            EthereumBurnAttemptStore.AttemptRow attempt, TransactionReceipt receipt) {
        try {
            BigInteger amount = decodeBurnAmount(attempt.calldata());
            if (!hasExactBurnEvent(receipt, attempt.contractAddress(),
                    attempt.adminAddress(), amount)) {
                return Optional.empty();
            }
            Log log = receipt.getLogs().stream()
                    .filter(value -> exactBurnLog(
                            value, attempt.contractAddress(),
                            attempt.adminAddress(), amount))
                    .findFirst().orElseThrow();
            return Optional.of(new MatchedBurnLog(
                    attempt.adminAddress(), ZERO_ADDRESS, amount, 1,
                    logDigest(log)));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    static boolean hasExactBurnEvent(
            TransactionReceipt receipt, String contract,
            String admin, BigInteger amount) {
        try {
            if (receipt.getLogs() == null) {
                return false;
            }
            List<Log> transferLogs = receipt.getLogs().stream()
                    .filter(log -> equalsAddress(log.getAddress(), contract))
                    .filter(log -> log.getTopics() != null
                            && !log.getTopics().isEmpty())
                    .filter(log -> log.getTopics().getFirst()
                            .equalsIgnoreCase(TRANSFER_TOPIC))
                    .toList();
            return transferLogs.size() == 1 && exactBurnLog(
                    transferLogs.getFirst(), contract, admin, amount);
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static boolean exactBurnLog(
            Log log, String contract, String admin, BigInteger amount) {
        List<String> topics = log.getTopics();
        return !log.isRemoved() && equalsAddress(log.getAddress(), contract)
                && topics != null && topics.size() == 3
                && topics.getFirst().equalsIgnoreCase(TRANSFER_TOPIC)
                && topics.get(1).equalsIgnoreCase(addressTopic(admin))
                && topics.get(2).equalsIgnoreCase("0x" + "0".repeat(64))
                && log.getData().equalsIgnoreCase("0x"
                        + Numeric.toHexStringNoPrefixZeroPadded(amount, 64));
    }

    private EthereumBurnAttemptStore.AttemptDraft draft(
            TokenOperation operation, BigInteger nonce) {
        String calldata = codec.burnCalldata(operation.quantity().atomicUnits())
                .toLowerCase(Locale.ROOT);
        EthereumTransactionCodec.Transaction transaction =
                new EthereumTransactionCodec.Transaction(
                        configuration.chainId(), nonce,
                        configuration.maxPriorityFeePerGas(),
                        configuration.maxFeePerGas(), configuration.gasLimit(),
                        configuration.contractAddress(), BigInteger.ZERO, calldata);
        byte[] unsigned = codec.signingPayload(transaction);
        byte[] digest = codec.signingDigest(transaction);
        return new EthereumBurnAttemptStore.AttemptDraft(
                configuration.contractAddress(), configuration.adminKeyAlias(),
                configuration.adminRegistryVersion(), configuration.adminKeyVersion(),
                configuration.policyVersion(), configuration.confirmations(),
                configuration.maxPriorityFeePerGas(), configuration.maxFeePerGas(),
                configuration.gasLimit(), calldata,
                sha256(Numeric.hexStringToByteArray(calldata)),
                Numeric.toHexString(unsigned).toLowerCase(Locale.ROOT),
                HEX.formatHex(digest));
    }

    private PreparedAttempt prepared(EthereumBurnAttemptStore.AttemptRow attempt) {
        String lifetime = sha256(("chain=" + attempt.chainId() + ";type=2;nonce="
                + attempt.nonce()).getBytes(StandardCharsets.UTF_8));
        String constraints = sha256(Numeric.hexStringToByteArray(
                attempt.unsignedTransaction()));
        return new PreparedAttempt(
                HEX.parseHex(attempt.signingDigest()),
                "ethereum-keccak256:" + attempt.signingDigest(),
                attempt.adminAddress(), attempt.contractAddress(),
                "ethereum-burn:" + attempt.attemptId(), lifetime,
                "max-fee-per-gas=" + attempt.maxFeePerGas()
                        + ";gas-limit=" + attempt.gasLimit(), constraints,
                attempt.observationPolicyVersion(), evidence(attempt, "prepared"));
    }

    private static EthereumTransactionCodec.Transaction transaction(
            EthereumBurnAttemptStore.AttemptRow attempt) {
        return new EthereumTransactionCodec.Transaction(
                attempt.chainId().longValueExact(), attempt.nonce(),
                attempt.maxPriorityFeePerGas(), attempt.maxFeePerGas(),
                attempt.gasLimit(), attempt.contractAddress(), BigInteger.ZERO,
                attempt.calldata());
    }

    private SignedAttempt signedAttempt(EthereumBurnAttemptStore.AttemptRow attempt) {
        return new SignedAttempt(
                attempt.operationId(), attempt.attemptId(),
                new NativeIdentity(attempt.transactionHash()),
                evidence(attempt, "signed"));
    }

    private static AttemptKey key(EthereumBurnAttemptStore.AttemptRow attempt) {
        return new AttemptKey(attempt.operationId(), attempt.attemptId());
    }

    private SubmissionResult retainedSubmission(
            EthereumBurnAttemptStore.AttemptRow attempt) {
        NativeIdentity identity = new NativeIdentity(attempt.transactionHash());
        return switch (attempt.status()) {
            case ACCEPTED, CONFIRMED -> new SubmissionResult(
                    SubmissionClassification.ACCEPTED, identity,
                    evidence(attempt, "submission-accepted"));
            case REJECTED, REVERTED, MISMATCHED, ORPHANED -> new SubmissionResult(
                    SubmissionClassification.DEFINITIVELY_REJECTED, null,
                    evidence(attempt, "submission-rejected"));
            case AMBIGUOUS, SUBMISSION_STARTED -> new SubmissionResult(
                    SubmissionClassification.AMBIGUOUS, identity,
                    evidence(attempt, "submission-ambiguous"));
            case PREPARED, SIGNED -> throw new IllegalStateException(
                    "burn attempt did not reach a submission outcome");
        };
    }

    private record AttemptKey(OperationId operationId, AttemptId attemptId) { }

    private void validateBurn(TokenOperation operation, OperationAttempt attempt) {
        if (operation.kind() != OperationKind.BURN
                || !operation.attemptIds().contains(attempt.attemptId())) {
            throw new IllegalArgumentException(
                    "only an authorized retained burn attempt is supported");
        }
        var unit = operation.quantity().unit();
        if (!configuration.assetId().equals(unit.assetId())
                || !configuration.unitId().equals(unit.unitId())
                || configuration.unitVersion() != unit.version()
                || configuration.decimals() != unit.scale()) {
            throw new IllegalArgumentException(
                    "burn asset/unit does not match local token policy");
        }
    }

    private void validateRetained(
            EthereumBurnAttemptStore.AttemptRow retained,
            TokenOperation operation) {
        String expectedCalldata = codec.burnCalldata(
                operation.quantity().atomicUnits());
        var unit = operation.quantity().unit();
        if (!retained.chainId().equals(BigInteger.valueOf(configuration.chainId()))
                || !retained.contractAddress().equals(configuration.contractAddress())
                || !retained.adminAddress().equals(configuration.adminAddress())
                || !retained.adminKeyAlias().equals(configuration.adminKeyAlias())
                || !retained.adminRegistryVersion().equals(
                        configuration.adminRegistryVersion())
                || !retained.adminKeyVersion().equals(
                        configuration.adminKeyVersion())
                || !retained.assetId().equals(unit.assetId())
                || !retained.unitId().equals(unit.unitId())
                || retained.unitVersion() != unit.version()
                || retained.unitScale() != unit.scale()
                || !retained.quantityAtomic().equals(
                        operation.quantity().atomicUnits())
                || !retained.observationPolicyVersion().equals(
                        configuration.policyVersion())
                || retained.requiredConfirmations() != configuration.confirmations()
                || !retained.calldata().equalsIgnoreCase(expectedCalldata)) {
            throw new IllegalStateException(
                    "retained burn attempt does not match current accepted context");
        }
    }

    private void verifyChain(Web3j client, String role) {
        try {
            requireConfiguredChain(client, configuration.chainId(), role);
        } catch (IOException failure) {
            throw new IllegalStateException(role + " RPC chain ID check failed", failure);
        }
    }

    private static void requireConfiguredChain(Web3j client, long chainId, String role)
            throws IOException {
        BigInteger observed = client.ethChainId().send().getChainId();
        if (!observed.equals(BigInteger.valueOf(chainId))) {
            throw new IllegalStateException(
                    role + " RPC chain ID is not the configured Anvil chain");
        }
    }

    private static ObservationEvidence observed(
            ObservationClassification classification,
            Optional<BigInteger> blockNumber, Optional<String> blockHash,
            Optional<BigInteger> confirmations, TransactionReceipt receipt,
            Optional<String> receiptDigest,
            Optional<TransactionEvidence> transaction,
            Optional<MatchedBurnLog> burnLog, String evidenceKind) {
        return new ObservationEvidence(
                classification, blockNumber, blockHash, confirmations,
                Optional.of(receipt.isStatusOK()), receiptDigest,
                transaction, burnLog, evidenceKind);
    }

    private static ObservationEvidence pending(
            String evidenceKind, Optional<TransactionEvidence> transaction) {
        return new ObservationEvidence(
                ObservationClassification.ABSENT_OR_PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), transaction,
                Optional.empty(), evidenceKind);
    }

    private static Optional<TransactionEvidence> transactionEvidence(Transaction value) {
        try {
            return Optional.of(new TransactionEvidence(
                    normalizeAddress(value.getFrom()), normalizeAddress(value.getTo()),
                    value.getNonce(),
                    sha256(Numeric.hexStringToByteArray(value.getInput()))));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<String> receiptDigest(TransactionReceipt receipt) {
        try {
            String canonical = normalizeHash(receipt.getTransactionHash()) + "|"
                    + Objects.toString(receipt.getStatus(), "") + "|"
                    + normalizeAddress(receipt.getFrom()) + "|"
                    + normalizeAddress(receipt.getTo()) + "|"
                    + Objects.toString(receipt.getBlockNumber(), "") + "|"
                    + normalizedHash(receipt.getBlockHash()).orElse("");
            return Optional.of(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static String logDigest(Log log) {
        String canonical = normalizeAddress(log.getAddress()) + "|"
                + String.join(",", log.getTopics()).toLowerCase(Locale.ROOT) + "|"
                + log.getData().toLowerCase(Locale.ROOT) + "|"
                + normalizeHash(log.getTransactionHash());
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static BigInteger decodeBurnAmount(String calldata) {
        String clean = Numeric.cleanHexPrefix(calldata);
        if (clean.length() != 8 + 64 || !clean.startsWith("42966c68")) {
            throw new IllegalArgumentException("durable burn calldata is invalid");
        }
        return new BigInteger(clean.substring(8), 16);
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(
                normalizeAddress(address));
    }

    private static boolean ambiguousRpcError(String message) {
        String safe = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return safe.contains("already known") || safe.contains("nonce too low")
                || safe.contains("replacement transaction");
    }

    private static boolean equalsAddress(String left, String right) {
        return left != null && right != null && left.equalsIgnoreCase(right);
    }

    private static String normalizeAddress(String value) {
        String clean = Numeric.cleanHexPrefix(Objects.requireNonNull(value, "address"));
        if (clean.length() != 40 || !clean.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException(
                    "address must contain exactly 20 hexadecimal bytes");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHash(String value) {
        String clean = Numeric.cleanHexPrefix(Objects.requireNonNull(value, "hash"));
        if (clean.length() != 64 || !clean.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException(
                    "hash must contain exactly 32 hexadecimal bytes");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    private static Optional<String> normalizedHash(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(normalizeHash(value));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public void close() {
        submissionClient.shutdown();
        if (observationClient != submissionClient) {
            observationClient.shutdown();
        }
    }

    private static EvidenceRef evidence(
            EthereumBurnAttemptStore.AttemptRow attempt, String kind) {
        return new EvidenceRef(
                "internal:ethereum-burn:" + kind + ":" + attempt.attemptId());
    }

    @FunctionalInterface
    public interface SubmissionTransport {
        EthSendTransaction send(String rawTransaction) throws IOException;
    }

    @FunctionalInterface
    interface SubmissionReadiness {
        void verify() throws IOException;
    }

    public record Configuration(
            long chainId, String contractAddress, String adminAddress,
            String adminKeyAlias, String adminRegistryVersion,
            String adminKeyVersion, BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas, BigInteger gasLimit, int confirmations,
            String assetId, String unitId, int unitVersion, int decimals,
            String policyVersion) {

        public Configuration {
            if (chainId != 31_337L) {
                throw new IllegalArgumentException(
                        "local Ethereum chain ID must be 31337");
            }
            contractAddress = normalizeAddress(contractAddress);
            adminAddress = normalizeAddress(adminAddress);
            requireText(adminKeyAlias, "adminKeyAlias", 128);
            requireText(adminRegistryVersion, "adminRegistryVersion", 128);
            requireText(adminKeyVersion, "adminKeyVersion", 128);
            requireNonNegative(maxPriorityFeePerGas, "maxPriorityFeePerGas");
            requireNonNegative(maxFeePerGas, "maxFeePerGas");
            if (maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0) {
                throw new IllegalArgumentException(
                        "maxFeePerGas must cover the priority fee");
            }
            requirePositive(gasLimit, "gasLimit");
            if (confirmations < 1 || confirmations > 100) {
                throw new IllegalArgumentException(
                        "confirmations must be between 1 and 100");
            }
            requireText(assetId, "assetId", 64);
            requireText(unitId, "unitId", 64);
            if (unitVersion < 1 || decimals != 2) {
                throw new IllegalArgumentException(
                        "local reference token requires a positive unit version and 2 decimals");
            }
            requireText(policyVersion, "policyVersion", 128);
        }

        private static void requireText(String value, String field, int maximum) {
            if (value == null || value.isBlank() || value.length() > maximum) {
                throw new IllegalArgumentException(
                        field + " must be non-blank and bounded");
            }
        }

        private static void requireNonNegative(BigInteger value, String field) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
        }

        private static void requirePositive(BigInteger value, String field) {
            if (value == null || value.signum() <= 0) {
                throw new IllegalArgumentException(field + " must be positive");
            }
        }
    }

    private record ObservationEvidence(
            ObservationClassification classification,
            Optional<BigInteger> blockNumber, Optional<String> blockHash,
            Optional<BigInteger> observedConfirmations,
            Optional<Boolean> receiptSuccess,
            Optional<String> receiptEvidenceSha256,
            Optional<TransactionEvidence> transaction,
            Optional<MatchedBurnLog> burnLog, String evidenceKind) {

        ObservationEvidence reclassified(
                ObservationClassification changed, String changedEvidenceKind) {
            return new ObservationEvidence(
                    changed, blockNumber, blockHash, observedConfirmations,
                    receiptSuccess, receiptEvidenceSha256, transaction,
                    burnLog, changedEvidenceKind);
        }

        EthereumBurnAttemptStore.ObservationDraft toDraft() {
            return new EthereumBurnAttemptStore.ObservationDraft(
                    classification, blockNumber, blockHash, observedConfirmations,
                    receiptSuccess, receiptEvidenceSha256,
                    transaction.map(TransactionEvidence::adminAddress),
                    transaction.map(TransactionEvidence::contractAddress),
                    transaction.map(TransactionEvidence::nonce),
                    transaction.map(TransactionEvidence::calldataSha256),
                    burnLog.map(MatchedBurnLog::sourceAddress),
                    burnLog.map(MatchedBurnLog::destinationAddress),
                    burnLog.map(MatchedBurnLog::amount),
                    burnLog.map(MatchedBurnLog::count),
                    burnLog.map(MatchedBurnLog::evidenceSha256));
        }
    }

    private record TransactionEvidence(
            String adminAddress, String contractAddress,
            BigInteger nonce, String calldataSha256) { }

    private record MatchedBurnLog(
            String sourceAddress, String destinationAddress,
            BigInteger amount, int count, String evidenceSha256) { }
}
