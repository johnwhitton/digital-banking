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

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
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

/** Anvil-only mint adapter. Ethereum SDK types stop at this package. */
public final class Web3jEthereumMintChainAdapter implements ChainPort, AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();
    private static final String TRANSFER_TOPIC = Hash.sha3String(
            "Transfer(address,address,uint256)").toLowerCase(Locale.ROOT);

    private final EthereumMintAttemptStore attempts;
    private final Web3j submissionClient;
    private final Web3j observationClient;
    private final SubmissionTransport submissionTransport;
    private final SubmissionReadiness submissionReadiness;
    private final EthereumTransactionCodec codec;
    private final Configuration configuration;
    private final Clock clock;

    public static Web3jEthereumMintChainAdapter local(
            DataSource dataSource,
            String loopbackRpcUrl,
            Configuration configuration,
            Clock clock) {
        Web3j submission = Web3j.build(new HttpService(loopbackRpcUrl));
        Web3j observation = Web3j.build(new HttpService(loopbackRpcUrl));
        try {
            return new Web3jEthereumMintChainAdapter(
                    dataSource, submission, observation, configuration, clock);
        } catch (RuntimeException failure) {
            submission.shutdown();
            observation.shutdown();
            throw failure;
        }
    }

    public Web3jEthereumMintChainAdapter(
            DataSource dataSource,
            Web3j submissionClient,
            Web3j observationClient,
            Configuration configuration,
            Clock clock) {
        this(dataSource, submissionClient, observationClient,
                rawTransaction -> submissionClient.ethSendRawTransaction(rawTransaction).send(),
                () -> requireConfiguredChain(
                        submissionClient, configuration.chainId(), "submission"),
                configuration, clock);
    }

    public Web3jEthereumMintChainAdapter(
            DataSource dataSource,
            Web3j submissionClient,
            Web3j observationClient,
            SubmissionTransport submissionTransport,
            Configuration configuration,
            Clock clock) {
        this(dataSource, submissionClient, observationClient, submissionTransport,
                () -> requireConfiguredChain(
                        submissionClient, configuration.chainId(), "submission"),
                configuration, clock);
    }

    Web3jEthereumMintChainAdapter(
            DataSource dataSource,
            Web3j submissionClient,
            Web3j observationClient,
            SubmissionTransport submissionTransport,
            SubmissionReadiness submissionReadiness,
            Configuration configuration,
            Clock clock) {
        this.attempts = new EthereumMintAttemptStore(dataSource);
        this.submissionClient = Objects.requireNonNull(submissionClient, "submissionClient");
        this.observationClient = Objects.requireNonNull(observationClient, "observationClient");
        this.submissionTransport = Objects.requireNonNull(
                submissionTransport, "submissionTransport");
        this.submissionReadiness = Objects.requireNonNull(
                submissionReadiness, "submissionReadiness");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.codec = new EthereumTransactionCodec();
        verifyChain(submissionClient, "submission");
        verifyChain(observationClient, "observation");
    }

    @Override
    public ChainCapabilities capabilities(String routeVersion) {
        if (!configuration.policyVersion().equals(routeVersion)) {
            throw new IllegalArgumentException("unsupported local Ethereum policy version");
        }
        return new ChainCapabilities(true, false, true);
    }

    @Override
    public PreparedAttempt prepare(
            UUID deliveryId,
            TokenOperation operation,
            OperationAttempt attempt) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(attempt, "attempt");
        validateMint(operation, attempt);
        Optional<EthereumMintAttemptStore.AttemptRow> existing = attempts.find(
                operation.operationId(), attempt.attemptId());
        if (existing.isPresent()) {
            EthereumMintAttemptStore.AttemptRow retained = existing.orElseThrow();
            if (!retained.deliveryId().equals(deliveryId)) {
                throw new IllegalStateException(
                        "operation attempt is already bound to another delivery");
            }
            return prepared(retained);
        }
        try {
            BigInteger networkNonce = submissionClient.ethGetTransactionCount(
                            configuration.signingAddress(), DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            Instant now = now();
            EthereumMintAttemptStore.AttemptRow retained = attempts.prepare(
                    deliveryId, operation.operationId(), attempt.attemptId(),
                    configuration.chainId(), configuration.signingAddress(),
                    networkNonce, now, nonce -> draft(operation, nonce));
            return prepared(retained);
        } catch (IOException failure) {
            throw new IllegalStateException("local Ethereum nonce inquiry failed", failure);
        }
    }

    @Override
    public Optional<SignedAttempt> findSignedAttempt(AttemptIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return attempts.find(identity.operationId(), identity.attemptId())
                .filter(row -> row.transactionHash() != null)
                .map(this::signedAttempt);
    }

    @Override
    public SignedAttempt attachSignature(
            AttemptIdentity identity, AuthorizedSignature signature) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(signature, "signature");
        EthereumMintAttemptStore.AttemptRow current = attempts.find(
                        identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("prepared attempt was not found"));
        String expected = normalizeAddress(signature.expectedSignerReference());
        if (!current.signingAddress().equals(expected)) {
            throw new IllegalArgumentException("authorized signer does not match durable attempt");
        }
        EthereumTransactionCodec.SignedTransaction encoded = codec.encodeSigned(
                transaction(current), signature.bytes(), expected);
        String transactionHash = Numeric.toHexString(encoded.hash()).toLowerCase(Locale.ROOT);
        EthereumMintAttemptStore.AttemptRow retained = attempts.attachSignature(
                current, sha256(signature.bytes()), signature.encoding(),
                Numeric.toHexString(encoded.bytes()).toLowerCase(Locale.ROOT),
                transactionHash, now());
        return signedAttempt(retained);
    }

    @Override
    public SubmissionResult submitOnce(SignedAttempt signedAttempt) {
        Objects.requireNonNull(signedAttempt, "signedAttempt");
        EthereumMintAttemptStore.AttemptRow current = attempts.find(
                        signedAttempt.operationId(), signedAttempt.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("signed attempt was not found"));
        if (current.status() != EthereumMintAttemptStore.AttemptStatus.SIGNED) {
            return retainedSubmission(current);
        }
        try {
            submissionReadiness.verify();
        } catch (IOException unavailable) {
            return new SubmissionResult(
                    SubmissionClassification.RETRYABLE_NO_EFFECT, null,
                    evidence(current, "submission-preflight-unavailable"));
        }
        EthereumMintAttemptStore.SubmissionClaim claim = attempts.claimSubmission(current, now());
        if (!claim.claimed()) {
            return retainedSubmission(claim.attempt());
        }
        try {
            EthSendTransaction response = submissionTransport.send(
                    claim.attempt().signedTransaction());
            if (response.hasError()) {
                String message = response.getError().getMessage();
                EthereumMintAttemptStore.AttemptStatus status = ambiguousRpcError(message)
                        ? EthereumMintAttemptStore.AttemptStatus.AMBIGUOUS
                        : EthereumMintAttemptStore.AttemptStatus.REJECTED;
                EthereumMintAttemptStore.AttemptRow recorded = attempts.recordSubmission(
                        claim.attempt(), status,
                        status == EthereumMintAttemptStore.AttemptStatus.AMBIGUOUS
                                ? "rpc-outcome-ambiguous" : "rpc-rejected",
                        now());
                return retainedSubmission(recorded);
            }
            String responseHash = normalizeHash(response.getTransactionHash());
            EthereumMintAttemptStore.AttemptStatus status =
                    responseHash.equals(claim.attempt().transactionHash())
                            ? EthereumMintAttemptStore.AttemptStatus.ACCEPTED
                            : EthereumMintAttemptStore.AttemptStatus.AMBIGUOUS;
            EthereumMintAttemptStore.AttemptRow recorded = attempts.recordSubmission(
                    claim.attempt(), status,
                    status == EthereumMintAttemptStore.AttemptStatus.ACCEPTED
                            ? "rpc-accepted" : "rpc-hash-mismatch",
                    now());
            return retainedSubmission(recorded);
        } catch (IOException failure) {
            EthereumMintAttemptStore.AttemptRow recorded = attempts.recordSubmission(
                    claim.attempt(), EthereumMintAttemptStore.AttemptStatus.AMBIGUOUS,
                    "rpc-response-unavailable", now());
            return retainedSubmission(recorded);
        }
    }

    @Override
    public InquiryResult inquire(AttemptIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        EthereumMintAttemptStore.AttemptRow attempt = attempts.find(
                        identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("attempt was not found"));
        NativeIdentity nativeIdentity = new NativeIdentity(attempt.transactionHash());
        try {
            Optional<Transaction> transaction = observationClient
                    .ethGetTransactionByHash(attempt.transactionHash()).send().getTransaction();
            String kind = transaction.isPresent() ? "inquiry-found" : "inquiry-pending";
            return new InquiryResult(
                    attempt.operationId(), attempt.attemptId(), Optional.of(nativeIdentity),
                    RetrySafety.REQUIRES_OBSERVATION, evidence(attempt, kind));
        } catch (IOException failure) {
            return new InquiryResult(
                    attempt.operationId(), attempt.attemptId(), Optional.of(nativeIdentity),
                    RetrySafety.REQUIRES_OBSERVATION,
                    evidence(attempt, "inquiry-unavailable"));
        }
    }

    @Override
    public Observation observe(ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        EthereumMintAttemptStore.AttemptRow attempt = attempts.find(
                        request.operationId(), request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("attempt was not found"));
        if (!request.nativeIdentity().value().equals(attempt.transactionHash())) {
            throw new IllegalArgumentException("observation identity does not match attempt");
        }
        ObservationEvidence observed;
        try {
            observed = observe(attempt);
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

    private ObservationEvidence observe(EthereumMintAttemptStore.AttemptRow attempt)
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
        Optional<MatchedMintLog> matchedLog = matchingMintLog(attempt, mined);
        if (!mined.isStatusOK()) {
            return observed(
                    ObservationClassification.REVERTED, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "receipt-reverted");
        }
        if (!matchesTransaction(attempt, tx, mined) || matchedLog.isEmpty()) {
            return observed(
                    ObservationClassification.MISMATCHED, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "receipt-intent-mismatch");
        }
        if (blockNumber.isEmpty() || blockHash.isEmpty()) {
            return observed(
                    ObservationClassification.ABSENT_OR_PENDING, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "receipt-block-pending");
        }
        EthBlock.Block canonical = observationClient.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber.orElseThrow()), false)
                .send().getBlock();
        if (canonical == null
                || !normalizeHash(canonical.getHash()).equals(blockHash.orElseThrow())) {
            return observed(
                    ObservationClassification.ORPHANED, blockNumber, blockHash,
                    Optional.empty(), mined, receiptDigest, transactionEvidence,
                    matchedLog, "block-not-canonical");
        }
        BigInteger latest = observationClient.ethBlockNumber().send().getBlockNumber();
        BigInteger confirmations = latest.subtract(blockNumber.orElseThrow()).add(BigInteger.ONE);
        if (confirmations.compareTo(BigInteger.valueOf(attempt.requiredConfirmations())) < 0) {
            return observed(
                    ObservationClassification.ABSENT_OR_PENDING, blockNumber, blockHash,
                    Optional.of(confirmations), mined, receiptDigest, transactionEvidence,
                    matchedLog, "confirmations-pending");
        }
        EthBlock.Block rechecked = observationClient.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber.orElseThrow()), false)
                .send().getBlock();
        if (rechecked == null
                || !normalizeHash(rechecked.getHash()).equals(blockHash.orElseThrow())) {
            return observed(
                    ObservationClassification.ORPHANED, blockNumber, blockHash,
                    Optional.of(confirmations), mined, receiptDigest, transactionEvidence,
                    matchedLog, "block-recheck-orphaned");
        }
        return observed(
                ObservationClassification.CONFIRMED, blockNumber, blockHash,
                Optional.of(confirmations), mined, receiptDigest, transactionEvidence,
                matchedLog, "mint-confirmed");
    }

    private boolean matchesTransaction(
            EthereumMintAttemptStore.AttemptRow attempt,
            Transaction transaction,
            TransactionReceipt receipt) {
        try {
            Long observedChainId = transaction.getChainId();
            return normalizeHash(transaction.getHash()).equals(attempt.transactionHash())
                    && observedChainId != null
                    && observedChainId == attempt.chainId().longValueExact()
                    && equalsAddress(transaction.getFrom(), attempt.signingAddress())
                    && equalsAddress(transaction.getTo(), attempt.contractAddress())
                    && attempt.nonce().equals(transaction.getNonce())
                    && BigInteger.ZERO.equals(transaction.getValue())
                    && attempt.calldata().equalsIgnoreCase(transaction.getInput())
                    && equalsAddress(receipt.getFrom(), attempt.signingAddress())
                    && equalsAddress(receipt.getTo(), attempt.contractAddress())
                    && normalizeHash(receipt.getTransactionHash())
                            .equals(attempt.transactionHash());
        } catch (RuntimeException malformedEvidence) {
            return false;
        }
    }

    private static Optional<MatchedMintLog> matchingMintLog(
            EthereumMintAttemptStore.AttemptRow attempt, TransactionReceipt receipt) {
        try {
            String zeroTopic = "0x" + "0".repeat(64);
            String recipientTopic = "0x" + "0".repeat(24)
                    + Numeric.cleanHexPrefix(attempt.recipientAddress());
            BigInteger amount = decodeMintAmount(attempt.calldata());
            String amountData = "0x" + Numeric.toHexStringNoPrefixZeroPadded(amount, 64);
            if (receipt.getLogs() == null) {
                return Optional.empty();
            }
            return receipt.getLogs().stream()
                    .filter(log -> matchesLog(
                            log, attempt, zeroTopic, recipientTopic, amountData))
                    .findFirst()
                    .map(log -> new MatchedMintLog(
                            attempt.recipientAddress(), amount, logDigest(log)));
        } catch (RuntimeException malformedEvidence) {
            return Optional.empty();
        }
    }

    private static boolean matchesLog(
            Log log,
            EthereumMintAttemptStore.AttemptRow attempt,
            String zeroTopic,
            String recipientTopic,
            String amountData) {
        List<String> topics = log.getTopics();
        return !log.isRemoved()
                && equalsAddress(log.getAddress(), attempt.contractAddress())
                && topics.size() == 3
                && topics.get(0).equalsIgnoreCase(TRANSFER_TOPIC)
                && topics.get(1).equalsIgnoreCase(zeroTopic)
                && topics.get(2).equalsIgnoreCase(recipientTopic)
                && log.getData().equalsIgnoreCase(amountData)
                && normalizeHash(log.getTransactionHash()).equals(attempt.transactionHash());
    }

    private static String logDigest(Log log) {
        String canonical = normalizeAddress(log.getAddress()) + "|"
                + String.join(",", log.getTopics()).toLowerCase(Locale.ROOT) + "|"
                + log.getData().toLowerCase(Locale.ROOT) + "|"
                + normalizeHash(log.getTransactionHash());
        return sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static Optional<TransactionEvidence> transactionEvidence(Transaction transaction) {
        try {
            return Optional.of(new TransactionEvidence(
                    normalizeAddress(transaction.getFrom()),
                    normalizeAddress(transaction.getTo()), transaction.getNonce(),
                    sha256(Numeric.hexStringToByteArray(transaction.getInput()))));
        } catch (RuntimeException malformedEvidence) {
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
        } catch (RuntimeException malformedEvidence) {
            return Optional.empty();
        }
    }

    private static Optional<String> normalizedHash(String value) {
        try {
            return value == null ? Optional.empty() : Optional.of(normalizeHash(value));
        } catch (RuntimeException malformedEvidence) {
            return Optional.empty();
        }
    }

    private static ObservationEvidence observed(
            ObservationClassification classification,
            Optional<BigInteger> blockNumber,
            Optional<String> blockHash,
            Optional<BigInteger> confirmations,
            TransactionReceipt receipt,
            Optional<String> receiptDigest,
            Optional<TransactionEvidence> transaction,
            Optional<MatchedMintLog> mintLog,
            String evidenceKind) {
        return new ObservationEvidence(
                classification, blockNumber, blockHash, confirmations,
                Optional.of(receipt.isStatusOK()), receiptDigest,
                transaction, mintLog, evidenceKind);
    }

    private static BigInteger decodeMintAmount(String calldata) {
        String clean = Numeric.cleanHexPrefix(calldata);
        if (clean.length() != 8 + 64 + 64 || !clean.startsWith("40c10f19")) {
            throw new IllegalArgumentException("durable mint calldata is invalid");
        }
        return new BigInteger(clean.substring(8 + 64), 16);
    }

    private EthereumMintAttemptStore.AttemptDraft draft(
            TokenOperation operation, BigInteger nonce) {
        String calldata = codec.mintCalldata(
                configuration.recipientAddress(), operation.quantity().atomicUnits())
                .toLowerCase(Locale.ROOT);
        EthereumTransactionCodec.Transaction transaction = new EthereumTransactionCodec.Transaction(
                configuration.chainId(), nonce, configuration.maxPriorityFeePerGas(),
                configuration.maxFeePerGas(), configuration.gasLimit(),
                configuration.contractAddress(), BigInteger.ZERO, calldata);
        byte[] unsigned = codec.signingPayload(transaction);
        byte[] digest = codec.signingDigest(transaction);
        return new EthereumMintAttemptStore.AttemptDraft(
                configuration.contractAddress(), configuration.recipientAddress(),
                configuration.signingKeyAlias(), configuration.signingKeyMetadataVersion(),
                configuration.policyVersion(), configuration.confirmations(),
                configuration.maxPriorityFeePerGas(), configuration.maxFeePerGas(),
                configuration.gasLimit(), calldata,
                sha256(Numeric.hexStringToByteArray(calldata)),
                Numeric.toHexString(unsigned).toLowerCase(Locale.ROOT), HEX.formatHex(digest));
    }

    private PreparedAttempt prepared(EthereumMintAttemptStore.AttemptRow attempt) {
        byte[] digest = HEX.parseHex(attempt.signingDigest());
        String lifetime = sha256(("chain=" + attempt.chainId() + ";type=2;nonce="
                + attempt.nonce()).getBytes(StandardCharsets.UTF_8));
        String constraints = sha256(Numeric.hexStringToByteArray(
                attempt.unsignedTransaction()));
        return new PreparedAttempt(
                digest, "ethereum-keccak256:" + attempt.signingDigest(),
                attempt.signingAddress(), attempt.recipientAddress(),
                "ethereum-mint:" + attempt.attemptId(), lifetime,
                "max-fee-per-gas=" + attempt.maxFeePerGas()
                        + ";gas-limit=" + attempt.gasLimit(),
                constraints, attempt.observationPolicyVersion(),
                evidence(attempt, "prepared"));
    }

    private EthereumTransactionCodec.Transaction transaction(
            EthereumMintAttemptStore.AttemptRow attempt) {
        return new EthereumTransactionCodec.Transaction(
                attempt.chainId().longValueExact(), attempt.nonce(),
                attempt.maxPriorityFeePerGas(), attempt.maxFeePerGas(),
                attempt.gasLimit(), attempt.contractAddress(), BigInteger.ZERO,
                attempt.calldata());
    }

    private SignedAttempt signedAttempt(EthereumMintAttemptStore.AttemptRow attempt) {
        return new SignedAttempt(
                attempt.operationId(), attempt.attemptId(),
                new NativeIdentity(attempt.transactionHash()), evidence(attempt, "signed"));
    }

    private SubmissionResult retainedSubmission(EthereumMintAttemptStore.AttemptRow attempt) {
        NativeIdentity nativeIdentity = new NativeIdentity(attempt.transactionHash());
        return switch (attempt.status()) {
            case ACCEPTED, CONFIRMED -> new SubmissionResult(
                    SubmissionClassification.ACCEPTED, nativeIdentity,
                    evidence(attempt, "submission-accepted"));
            case REJECTED, REVERTED, MISMATCHED, ORPHANED -> new SubmissionResult(
                    SubmissionClassification.DEFINITIVELY_REJECTED, null,
                    evidence(attempt, "submission-rejected"));
            case AMBIGUOUS, SUBMISSION_STARTED -> new SubmissionResult(
                    SubmissionClassification.AMBIGUOUS, nativeIdentity,
                    evidence(attempt, "submission-ambiguous"));
            case PREPARED, SIGNED -> throw new IllegalStateException(
                    "attempt did not reach a submission outcome");
        };
    }

    private void validateMint(TokenOperation operation, OperationAttempt attempt) {
        if (operation.kind() != OperationKind.MINT
                || !operation.attemptIds().contains(attempt.attemptId())) {
            throw new IllegalArgumentException("only an authorized retained mint attempt is supported");
        }
        var unit = operation.quantity().unit();
        if (!configuration.assetId().equals(unit.assetId())
                || !configuration.unitId().equals(unit.unitId())
                || configuration.unitVersion() != unit.version()
                || configuration.decimals() != unit.scale()) {
            throw new IllegalArgumentException("mint asset/unit does not match local token policy");
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
            throw new IllegalArgumentException("address must contain exactly 20 hexadecimal bytes");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHash(String value) {
        String clean = Numeric.cleanHexPrefix(Objects.requireNonNull(value, "hash"));
        if (clean.length() != 64 || !clean.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("hash must contain exactly 32 hexadecimal bytes");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
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
            EthereumMintAttemptStore.AttemptRow attempt, String kind) {
        return new EvidenceRef(
                "internal:ethereum:" + kind + ":" + attempt.attemptId());
    }

    private static ObservationEvidence pending(
            String evidenceKind, Optional<TransactionEvidence> transaction) {
        return new ObservationEvidence(
                ObservationClassification.ABSENT_OR_PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), transaction,
                Optional.empty(), evidenceKind);
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
            long chainId,
            String contractAddress,
            String recipientAddress,
            String signingAddress,
            String signingKeyAlias,
            String signingKeyMetadataVersion,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            int confirmations,
            String assetId,
            String unitId,
            int unitVersion,
            int decimals,
            String policyVersion) {

        public Configuration {
            if (chainId != 31_337L) {
                throw new IllegalArgumentException("local Ethereum chain ID must be 31337");
            }
            contractAddress = normalizeAddress(contractAddress);
            recipientAddress = normalizeAddress(recipientAddress);
            signingAddress = normalizeAddress(signingAddress);
            requireText(signingKeyAlias, "signingKeyAlias", 128);
            requireText(signingKeyMetadataVersion, "signingKeyMetadataVersion", 256);
            requireNonNegative(maxPriorityFeePerGas, "maxPriorityFeePerGas");
            requireNonNegative(maxFeePerGas, "maxFeePerGas");
            if (maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0) {
                throw new IllegalArgumentException("maxFeePerGas must cover the priority fee");
            }
            requirePositive(gasLimit, "gasLimit");
            if (confirmations < 1 || confirmations > 100) {
                throw new IllegalArgumentException("confirmations must be between 1 and 100");
            }
            requireText(assetId, "assetId", 64);
            requireText(unitId, "unitId", 64);
            if (unitVersion < 1 || decimals != 2) {
                throw new IllegalArgumentException(
                        "local reference token requires a positive unit version and 2 decimals");
            }
            requireText(policyVersion, "policyVersion", 256);
        }

        private static void requireText(String value, String field, int max) {
            if (value == null || value.isBlank() || value.length() > max) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
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
            Optional<BigInteger> blockNumber,
            Optional<String> blockHash,
            Optional<BigInteger> observedConfirmations,
            Optional<Boolean> receiptSuccess,
            Optional<String> receiptEvidenceSha256,
            Optional<TransactionEvidence> transaction,
            Optional<MatchedMintLog> mintLog,
            String evidenceKind) {

        EthereumMintAttemptStore.ObservationDraft toDraft() {
            return new EthereumMintAttemptStore.ObservationDraft(
                    classification, blockNumber, blockHash, observedConfirmations,
                    receiptSuccess, receiptEvidenceSha256,
                    transaction.map(TransactionEvidence::senderAddress),
                    transaction.map(TransactionEvidence::contractAddress),
                    transaction.map(TransactionEvidence::nonce),
                    transaction.map(TransactionEvidence::calldataSha256),
                    mintLog.map(MatchedMintLog::recipientAddress),
                    mintLog.map(MatchedMintLog::amount),
                    mintLog.map(MatchedMintLog::evidenceSha256));
        }
    }

    private record TransactionEvidence(
            String senderAddress,
            String contractAddress,
            BigInteger nonce,
            String calldataSha256) {
    }

    private record MatchedMintLog(
            String recipientAddress,
            BigInteger amount,
            String evidenceSha256) {
    }
}
