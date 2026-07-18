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

import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.application.port.WalletTransferChainPort;
import io.github.johnwhitton.digitalbanking.domain.operation.EvidenceRef;
import io.github.johnwhitton.digitalbanking.domain.transfer.SettlementNetwork;
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

/** Anvil-only direct ERC-20 wallet-transfer adapter. */
public final class Web3jEthereumWalletTransferChainAdapter
        implements WalletTransferChainPort, AutoCloseable {

    private static final HexFormat HEX = HexFormat.of();
    private static final String TRANSFER_TOPIC = Hash.sha3String(
            "Transfer(address,address,uint256)").toLowerCase(Locale.ROOT);

    private final EthereumWalletTransferAttemptStore attempts;
    private final EthereumRedemptionBalanceStore balances;
    private final EthereumTokenStateReader tokenState;
    private final Web3j submissionClient;
    private final Web3j observationClient;
    private final SubmissionTransport submissionTransport;
    private final SubmissionReadiness submissionReadiness;
    private final EthereumTransactionCodec codec = new EthereumTransactionCodec();
    private final Configuration configuration;
    private final Clock clock;

    public static Web3jEthereumWalletTransferChainAdapter local(
            DataSource dataSource, String rpcUrl,
            Configuration configuration, Clock clock) {
        Web3j submission = Web3j.build(new HttpService(rpcUrl));
        Web3j observation = Web3j.build(new HttpService(rpcUrl));
        try {
            return new Web3jEthereumWalletTransferChainAdapter(
                    dataSource, submission, observation, configuration, clock);
        } catch (RuntimeException failure) {
            submission.shutdown();
            observation.shutdown();
            throw failure;
        }
    }

    public Web3jEthereumWalletTransferChainAdapter(
            DataSource dataSource, Web3j submissionClient, Web3j observationClient,
            Configuration configuration, Clock clock) {
        this(dataSource, submissionClient, observationClient,
                raw -> submissionClient.ethSendRawTransaction(raw).send(),
                () -> requireConfiguredChain(
                        submissionClient, configuration.chainId(), "submission"),
                configuration, clock);
    }

    Web3jEthereumWalletTransferChainAdapter(
            DataSource dataSource, Web3j submissionClient, Web3j observationClient,
            SubmissionTransport submissionTransport,
            SubmissionReadiness submissionReadiness,
            Configuration configuration, Clock clock) {
        attempts = new EthereumWalletTransferAttemptStore(dataSource);
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
    public ChainPort.PreparedAttempt prepare(
            UUID deliveryId, WalletTransferOperation operation) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        validate(operation);
        Optional<EthereumWalletTransferAttemptStore.AttemptRow> existing = attempts.find(
                operation.operationId(), operation.attemptId());
        if (existing.isPresent()) {
            EthereumWalletTransferAttemptStore.AttemptRow retained = existing.orElseThrow();
            if (!retained.deliveryId().equals(deliveryId)) {
                throw new IllegalStateException(
                        "wallet transfer attempt is bound to another delivery");
            }
            validateRetained(operation, retained);
            return prepared(retained);
        }
        try {
            Optional<EthereumRedemptionBalanceStore.Context> redemption =
                    balances.findByCustody(operation.operationId());
            if (redemption.isPresent()) {
                balances.record(EthereumRedemptionBalanceStore.Stage.BEFORE_CUSTODY,
                        redemption.orElseThrow(),
                        tokenState.latest(redemption.orElseThrow(), now()));
            }
            BigInteger networkNonce = submissionClient.ethGetTransactionCount(
                            operation.source().normalizedAddress(),
                            DefaultBlockParameterName.PENDING)
                    .send().getTransactionCount();
            EthereumWalletTransferAttemptStore.AttemptRow retained = attempts.prepare(
                    deliveryId, operation, configuration.chainId(), networkNonce,
                    now(), nonce -> draft(operation, nonce));
            validateRetained(operation, retained);
            return prepared(retained);
        } catch (IOException failure) {
            throw new IllegalStateException("local Ethereum nonce inquiry failed", failure);
        }
    }

    @Override
    public Optional<ChainPort.SignedAttempt> findSignedAttempt(
            ChainPort.AttemptIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        return attempts.find(identity.operationId(), identity.attemptId())
                .filter(row -> row.transactionHash() != null)
                .map(this::signed);
    }

    @Override
    public ChainPort.SignedAttempt attachSignature(
            ChainPort.AttemptIdentity identity,
            ChainPort.AuthorizedSignature signature) {
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(signature, "signature");
        var current = attempts.find(identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "prepared wallet transfer attempt was not found"));
        String expected = normalizeAddress(signature.expectedSignerReference());
        if (!current.sourceAddress().equals(expected)) {
            throw new IllegalArgumentException(
                    "authorized signer does not match durable source wallet");
        }
        EthereumTransactionCodec.SignedTransaction encoded = codec.encodeSigned(
                transaction(current), signature.bytes(), expected);
        var retained = attempts.attachSignature(
                current, sha256(signature.bytes()), signature.encoding(),
                Numeric.toHexString(encoded.bytes()).toLowerCase(Locale.ROOT),
                Numeric.toHexString(encoded.hash()).toLowerCase(Locale.ROOT), now());
        return signed(retained);
    }

    @Override
    public ChainPort.SubmissionResult submitOnce(ChainPort.SignedAttempt signedAttempt) {
        Objects.requireNonNull(signedAttempt, "signedAttempt");
        var current = attempts.find(
                        signedAttempt.operationId(), signedAttempt.attemptId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "signed wallet transfer attempt was not found"));
        if (current.status() != EthereumWalletTransferAttemptStore.AttemptStatus.SIGNED) {
            return retainedSubmission(current);
        }
        try {
            submissionReadiness.verify();
        } catch (IOException unavailable) {
            return new ChainPort.SubmissionResult(
                    ChainPort.SubmissionClassification.RETRYABLE_NO_EFFECT, null,
                    evidence(current, "submission-preflight-unavailable"));
        }
        var claim = attempts.claimSubmission(current, now());
        if (!claim.claimed()) {
            return retainedSubmission(claim.attempt());
        }
        try {
            EthSendTransaction response = submissionTransport.send(
                    claim.attempt().signedTransaction());
            EthereumWalletTransferAttemptStore.AttemptStatus status;
            String code;
            if (response.hasError()) {
                status = ambiguousRpcError(response.getError().getMessage())
                        ? EthereumWalletTransferAttemptStore.AttemptStatus.AMBIGUOUS
                        : EthereumWalletTransferAttemptStore.AttemptStatus.REJECTED;
                code = status == EthereumWalletTransferAttemptStore.AttemptStatus.AMBIGUOUS
                        ? "rpc-outcome-ambiguous" : "rpc-rejected";
            } else {
                String responseHash = normalizeHash(response.getTransactionHash());
                status = responseHash.equals(claim.attempt().transactionHash())
                        ? EthereumWalletTransferAttemptStore.AttemptStatus.ACCEPTED
                        : EthereumWalletTransferAttemptStore.AttemptStatus.AMBIGUOUS;
                code = status == EthereumWalletTransferAttemptStore.AttemptStatus.ACCEPTED
                        ? "rpc-accepted" : "rpc-hash-mismatch";
            }
            return retainedSubmission(attempts.recordSubmission(
                    claim.attempt(), status, code, now()));
        } catch (IOException failure) {
            return retainedSubmission(attempts.recordSubmission(
                    claim.attempt(),
                    EthereumWalletTransferAttemptStore.AttemptStatus.AMBIGUOUS,
                    "rpc-response-unavailable", now()));
        }
    }

    @Override
    public ChainPort.InquiryResult inquire(ChainPort.AttemptIdentity identity) {
        Objects.requireNonNull(identity, "identity");
        var attempt = attempts.find(identity.operationId(), identity.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("attempt was not found"));
        ChainPort.NativeIdentity nativeIdentity =
                new ChainPort.NativeIdentity(attempt.transactionHash());
        try {
            Optional<Transaction> found = observationClient
                    .ethGetTransactionByHash(attempt.transactionHash()).send()
                    .getTransaction();
            return new ChainPort.InquiryResult(
                    attempt.operationId(), attempt.attemptId(),
                    Optional.of(nativeIdentity), ChainPort.RetrySafety.REQUIRES_OBSERVATION,
                    evidence(attempt, found.isPresent()
                            ? "inquiry-found" : "inquiry-pending"));
        } catch (IOException failure) {
            return new ChainPort.InquiryResult(
                    attempt.operationId(), attempt.attemptId(),
                    Optional.of(nativeIdentity), ChainPort.RetrySafety.REQUIRES_OBSERVATION,
                    evidence(attempt, "inquiry-unavailable"));
        }
    }

    @Override
    public ChainPort.Observation observe(ChainPort.ObservationRequest request) {
        Objects.requireNonNull(request, "request");
        var attempt = attempts.find(request.operationId(), request.attemptId())
                .orElseThrow(() -> new IllegalArgumentException("attempt was not found"));
        if (!request.nativeIdentity().value().equals(attempt.transactionHash())
                || !request.policyVersion().equals(attempt.observationPolicyVersion())) {
            throw new IllegalArgumentException(
                    "observation identity or policy does not match durable attempt");
        }
        ObservationEvidence observed;
        try {
            observed = observe(attempt);
            if (observed.classification()
                    == ChainPort.ObservationClassification.CONFIRMED) {
                Optional<EthereumRedemptionBalanceStore.Context> redemption =
                        balances.findByCustody(attempt.operationId());
                if (redemption.isPresent()) {
                    EthereumRedemptionBalanceStore.Context context =
                            redemption.orElseThrow();
                    balances.record(EthereumRedemptionBalanceStore.Stage.AFTER_CUSTODY,
                            context, tokenState.at(context,
                                    observed.blockNumber().orElseThrow(), now()));
                    if (!balances.custodyDeltaMatches(context.correlationId())) {
                        observed = observed.reclassified(
                                ChainPort.ObservationClassification.MISMATCHED,
                                "redemption-custody-balance-mismatch");
                    }
                }
            }
        } catch (IOException failure) {
            observed = pending("observer-unavailable", Optional.empty());
        }
        Instant observedAt = now();
        EvidenceRef evidence = evidence(attempt, observed.evidenceKind());
        attempts.recordObservation(attempt, observed.toDraft(), evidence.value(), observedAt);
        return new ChainPort.Observation(
                attempt.operationId(), attempt.attemptId(),
                new ChainPort.NativeIdentity(attempt.transactionHash()),
                observed.classification(), attempt.observationPolicyVersion(),
                observedAt, List.of(evidence));
    }

    private ObservationEvidence observe(
            EthereumWalletTransferAttemptStore.AttemptRow attempt) throws IOException {
        Optional<Transaction> transaction = observationClient
                .ethGetTransactionByHash(attempt.transactionHash()).send().getTransaction();
        if (transaction.isEmpty()) {
            return pending("transaction-absent", Optional.empty());
        }
        Transaction tx = transaction.orElseThrow();
        Optional<TransactionEvidence> txEvidence = transactionEvidence(tx);
        Optional<TransactionReceipt> receipt = observationClient
                .ethGetTransactionReceipt(attempt.transactionHash()).send()
                .getTransactionReceipt();
        if (receipt.isEmpty()) {
            return pending("receipt-pending", txEvidence);
        }
        TransactionReceipt mined = receipt.orElseThrow();
        Optional<BigInteger> blockNumber = Optional.ofNullable(mined.getBlockNumber());
        Optional<String> blockHash = normalizedHash(mined.getBlockHash());
        Optional<String> receiptDigest = receiptDigest(mined);
        Optional<MatchedTransferLog> matched = matchingTransferLog(attempt, mined);
        if (!mined.isStatusOK()) {
            return observed(ChainPort.ObservationClassification.REVERTED,
                    blockNumber, blockHash, Optional.empty(), mined, receiptDigest,
                    txEvidence, matched, "receipt-reverted");
        }
        if (!matchesTransaction(attempt, tx, mined) || matched.isEmpty()) {
            return observed(ChainPort.ObservationClassification.MISMATCHED,
                    blockNumber, blockHash, Optional.empty(), mined, receiptDigest,
                    txEvidence, matched, "receipt-intent-mismatch");
        }
        if (blockNumber.isEmpty() || blockHash.isEmpty()) {
            return observed(ChainPort.ObservationClassification.ABSENT_OR_PENDING,
                    blockNumber, blockHash, Optional.empty(), mined, receiptDigest,
                    txEvidence, matched, "receipt-block-pending");
        }
        EthBlock.Block canonical = observationClient.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber.orElseThrow()), false)
                .send().getBlock();
        if (canonical == null || !normalizeHash(canonical.getHash())
                .equals(blockHash.orElseThrow())) {
            return observed(ChainPort.ObservationClassification.ORPHANED,
                    blockNumber, blockHash, Optional.empty(), mined, receiptDigest,
                    txEvidence, matched, "block-not-canonical");
        }
        BigInteger latest = observationClient.ethBlockNumber().send().getBlockNumber();
        BigInteger confirmations = latest.subtract(blockNumber.orElseThrow())
                .add(BigInteger.ONE);
        if (confirmations.compareTo(
                BigInteger.valueOf(attempt.requiredConfirmations())) < 0) {
            return observed(ChainPort.ObservationClassification.ABSENT_OR_PENDING,
                    blockNumber, blockHash, Optional.of(confirmations), mined,
                    receiptDigest, txEvidence, matched, "confirmations-pending");
        }
        EthBlock.Block rechecked = observationClient.ethGetBlockByNumber(
                        DefaultBlockParameter.valueOf(blockNumber.orElseThrow()), false)
                .send().getBlock();
        if (rechecked == null || !normalizeHash(rechecked.getHash())
                .equals(blockHash.orElseThrow())) {
            return observed(ChainPort.ObservationClassification.ORPHANED,
                    blockNumber, blockHash, Optional.of(confirmations), mined,
                    receiptDigest, txEvidence, matched, "block-recheck-orphaned");
        }
        return observed(ChainPort.ObservationClassification.CONFIRMED,
                blockNumber, blockHash, Optional.of(confirmations), mined,
                receiptDigest, txEvidence, matched, "wallet-transfer-confirmed");
    }

    private static boolean matchesTransaction(
            EthereumWalletTransferAttemptStore.AttemptRow attempt,
            Transaction transaction, TransactionReceipt receipt) {
        try {
            Long observedChainId = transaction.getChainId();
            return normalizeHash(transaction.getHash()).equals(attempt.transactionHash())
                    && observedChainId != null
                    && observedChainId == attempt.chainId().longValueExact()
                    && equalsAddress(transaction.getFrom(), attempt.sourceAddress())
                    && equalsAddress(transaction.getTo(), attempt.contractAddress())
                    && attempt.nonce().equals(transaction.getNonce())
                    && BigInteger.ZERO.equals(transaction.getValue())
                    && attempt.calldata().equalsIgnoreCase(transaction.getInput())
                    && equalsAddress(receipt.getFrom(), attempt.sourceAddress())
                    && equalsAddress(receipt.getTo(), attempt.contractAddress())
                    && normalizeHash(receipt.getTransactionHash())
                            .equals(attempt.transactionHash());
        } catch (RuntimeException malformedEvidence) {
            return false;
        }
    }

    private static Optional<MatchedTransferLog> matchingTransferLog(
            EthereumWalletTransferAttemptStore.AttemptRow attempt,
            TransactionReceipt receipt) {
        try {
            if (!hasExactTransferEvent(
                    receipt, attempt.contractAddress(), attempt.sourceAddress(),
                    attempt.destinationAddress(), attempt.quantityAtomic())) {
                return Optional.empty();
            }
            Log log = receipt.getLogs().stream()
                    .filter(value -> exactTransferLog(
                            value, attempt.contractAddress(), attempt.sourceAddress(),
                            attempt.destinationAddress(), attempt.quantityAtomic()))
                    .findFirst().orElseThrow();
            return Optional.of(new MatchedTransferLog(
                    attempt.sourceAddress(), attempt.destinationAddress(),
                    attempt.quantityAtomic(), 1, logDigest(log)));
        } catch (RuntimeException malformedEvidence) {
            return Optional.empty();
        }
    }

    static boolean hasExactTransferEvent(
            TransactionReceipt receipt, String contract, String source,
            String destination, BigInteger amount) {
        try {
            if (receipt.getLogs() == null) {
                return false;
            }
            List<Log> transferLogs = receipt.getLogs().stream()
                    .filter(log -> equalsAddress(log.getAddress(), contract))
                    .filter(log -> log.getTopics() != null && !log.getTopics().isEmpty())
                    .filter(log -> log.getTopics().getFirst()
                            .equalsIgnoreCase(TRANSFER_TOPIC))
                    .toList();
            if (transferLogs.size() != 1) {
                return false;
            }
            return exactTransferLog(
                    transferLogs.getFirst(), contract, source, destination, amount);
        } catch (RuntimeException malformedEvidence) {
            return false;
        }
    }

    private static boolean exactTransferLog(
            Log log, String contract, String source,
            String destination, BigInteger amount) {
        return !log.isRemoved()
                && equalsAddress(log.getAddress(), contract)
                && log.getTopics() != null && log.getTopics().size() == 3
                && log.getTopics().getFirst().equalsIgnoreCase(TRANSFER_TOPIC)
                && log.getTopics().get(1).equalsIgnoreCase(addressTopic(source))
                && log.getTopics().get(2).equalsIgnoreCase(addressTopic(destination))
                && log.getData().equalsIgnoreCase("0x"
                        + Numeric.toHexStringNoPrefixZeroPadded(amount, 64));
    }

    private EthereumWalletTransferAttemptStore.AttemptDraft draft(
            WalletTransferOperation operation, BigInteger nonce) {
        String calldata = codec.transferCalldata(
                operation.destination().normalizedAddress(),
                operation.quantity().atomicUnits()).toLowerCase(Locale.ROOT);
        EthereumTransactionCodec.Transaction transaction = new EthereumTransactionCodec.Transaction(
                configuration.chainId(), nonce,
                configuration.maxPriorityFeePerGas(), configuration.maxFeePerGas(),
                configuration.gasLimit(), operation.contractAddress(),
                BigInteger.ZERO, calldata);
        return new EthereumWalletTransferAttemptStore.AttemptDraft(
                configuration.requiredConfirmations(),
                configuration.maxPriorityFeePerGas(), configuration.maxFeePerGas(),
                configuration.gasLimit(), calldata,
                sha256(Numeric.hexStringToByteArray(calldata)),
                Numeric.toHexString(codec.signingPayload(transaction))
                        .toLowerCase(Locale.ROOT),
                HEX.formatHex(codec.signingDigest(transaction)));
    }

    private void validateRetained(
            WalletTransferOperation operation,
            EthereumWalletTransferAttemptStore.AttemptRow row) {
        var unit = operation.quantity().unit();
        EthereumWalletTransferAttemptStore.AttemptDraft expected =
                draft(operation, row.nonce());
        EthereumWalletTransferAttemptStore.AttemptDraft retained =
                new EthereumWalletTransferAttemptStore.AttemptDraft(
                        row.requiredConfirmations(), row.maxPriorityFeePerGas(),
                        row.maxFeePerGas(), row.gasLimit(), row.calldata(),
                        row.calldataSha256(), row.unsignedTransaction(),
                        row.signingDigest());
        if (!row.operationId().equals(operation.operationId())
                || !row.effectId().equals(operation.effectId())
                || !row.attemptId().equals(operation.attemptId())
                || !row.chainId().equals(BigInteger.valueOf(configuration.chainId()))
                || !row.contractAddress().equals(operation.contractAddress())
                || !row.sourceAddress().equals(operation.source().normalizedAddress())
                || !row.destinationAddress().equals(
                        operation.destination().normalizedAddress())
                || !row.sourceKeyAlias().equals(
                        operation.source().keyReference().value())
                || !row.sourceRegistryVersion().equals(
                        operation.source().registryVersion())
                || !row.sourceKeyVersion().equals(operation.source().keyVersion())
                || !row.destinationRegistryVersion().equals(
                        operation.destination().registryVersion())
                || !row.destinationKeyVersion().equals(
                        operation.destination().keyVersion())
                || !row.assetId().equals(unit.assetId())
                || !row.unitId().equals(unit.unitId())
                || row.unitVersion() != unit.version()
                || row.unitScale() != unit.scale()
                || !row.quantityAtomic().equals(operation.quantity().atomicUnits())
                || !row.observationPolicyVersion().equals(
                        operation.finalityPolicyVersion())
                || !retained.equals(expected)) {
            throw new IllegalStateException(
                    "retained wallet transfer attempt does not match accepted context");
        }
    }

    private ChainPort.PreparedAttempt prepared(
            EthereumWalletTransferAttemptStore.AttemptRow row) {
        return new ChainPort.PreparedAttempt(
                HEX.parseHex(row.signingDigest()), row.signingDigest(),
                row.sourceAddress(), row.destinationAddress(),
                "erc20-transfer:" + row.contractAddress(),
                sha256(canonical(row.chainId(), row.nonce(), row.sourceAddress(),
                        row.sourceRegistryVersion(), row.sourceKeyVersion())),
                row.maxFeePerGas().multiply(row.gasLimit()).toString(),
                sha256(canonical(row.contractAddress(), row.calldataSha256(),
                        row.destinationAddress(), row.quantityAtomic(),
                        row.sourceKeyAlias(),
                        row.destinationRegistryVersion(), row.destinationKeyVersion())),
                row.observationPolicyVersion(), evidence(row, "attempt-prepared"));
    }

    private static EthereumTransactionCodec.Transaction transaction(
            EthereumWalletTransferAttemptStore.AttemptRow row) {
        return new EthereumTransactionCodec.Transaction(
                row.chainId().longValueExact(), row.nonce(),
                row.maxPriorityFeePerGas(), row.maxFeePerGas(), row.gasLimit(),
                row.contractAddress(), BigInteger.ZERO, row.calldata());
    }

    private ChainPort.SignedAttempt signed(
            EthereumWalletTransferAttemptStore.AttemptRow row) {
        return new ChainPort.SignedAttempt(
                row.operationId(), row.attemptId(),
                new ChainPort.NativeIdentity(row.transactionHash()),
                evidence(row, "signed-transaction-retained"));
    }

    private ChainPort.SubmissionResult retainedSubmission(
            EthereumWalletTransferAttemptStore.AttemptRow row) {
        ChainPort.SubmissionClassification classification = switch (row.status()) {
            case ACCEPTED, CONFIRMED -> ChainPort.SubmissionClassification.ACCEPTED;
            case AMBIGUOUS, SUBMISSION_STARTED, MISMATCHED, ORPHANED ->
                    ChainPort.SubmissionClassification.AMBIGUOUS;
            case REJECTED, REVERTED -> ChainPort.SubmissionClassification.DEFINITIVELY_REJECTED;
            case PREPARED, SIGNED -> ChainPort.SubmissionClassification.RETRYABLE_NO_EFFECT;
        };
        return new ChainPort.SubmissionResult(
                classification,
                classification == ChainPort.SubmissionClassification.ACCEPTED
                        || classification == ChainPort.SubmissionClassification.AMBIGUOUS
                        ? new ChainPort.NativeIdentity(row.transactionHash()) : null,
                evidence(row, "submission-" + row.status().name().toLowerCase(Locale.ROOT)));
    }

    private void validate(WalletTransferOperation operation) {
        Objects.requireNonNull(operation, "operation");
        var unit = operation.quantity().unit();
        if (operation.network() != SettlementNetwork.ETHEREUM
                || !operation.contractAddress().equals(configuration.contractAddress())
                || !operation.contractVersion().equals(configuration.contractVersion())
                || !operation.finalityPolicyVersion().equals(configuration.policyVersion())
                || !unit.assetId().equals(configuration.assetId())
                || !unit.unitId().equals(configuration.unitId())
                || unit.version() != configuration.unitVersion()
                || unit.scale() != configuration.unitScale()) {
            throw new IllegalArgumentException(
                    "wallet transfer does not match the configured local route");
        }
    }

    private void verifyChain(Web3j client, String role) {
        try {
            requireConfiguredChain(client, configuration.chainId(), role);
        } catch (IOException failure) {
            throw new IllegalStateException(
                    "local Ethereum " + role + " chain inquiry failed", failure);
        }
    }

    private static void requireConfiguredChain(
            Web3j client, long chainId, String role) throws IOException {
        BigInteger observed = client.ethChainId().send().getChainId();
        if (!BigInteger.valueOf(chainId).equals(observed)) {
            throw new IllegalStateException(
                    "local Ethereum " + role + " chain ID mismatch");
        }
    }

    private static boolean ambiguousRpcError(String message) {
        String normalized = Objects.requireNonNullElse(message, "")
                .toLowerCase(Locale.ROOT);
        return normalized.contains("already known")
                || normalized.contains("known transaction")
                || normalized.contains("nonce too low");
    }

    private static Optional<TransactionEvidence> transactionEvidence(Transaction tx) {
        try {
            return Optional.of(new TransactionEvidence(
                    normalizeAddress(tx.getFrom()), normalizeAddress(tx.getTo()),
                    tx.getNonce(), sha256(Numeric.hexStringToByteArray(tx.getInput()))));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static Optional<String> receiptDigest(TransactionReceipt receipt) {
        try {
            return Optional.of(sha256(canonical(
                    normalizeHash(receipt.getTransactionHash()), receipt.getStatus(),
                    receipt.getBlockNumber(), normalizeHash(receipt.getBlockHash()),
                    normalizeAddress(receipt.getFrom()), normalizeAddress(receipt.getTo()))));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static String logDigest(Log log) {
        return sha256(canonical(normalizeAddress(log.getAddress()),
                String.join(",", log.getTopics()), log.getData(), log.isRemoved()));
    }

    private static ObservationEvidence pending(
            String kind, Optional<TransactionEvidence> transaction) {
        return new ObservationEvidence(
                ChainPort.ObservationClassification.ABSENT_OR_PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), transaction,
                Optional.empty(), kind);
    }

    private static ObservationEvidence observed(
            ChainPort.ObservationClassification classification,
            Optional<BigInteger> blockNumber, Optional<String> blockHash,
            Optional<BigInteger> confirmations, TransactionReceipt receipt,
            Optional<String> receiptDigest,
            Optional<TransactionEvidence> transaction,
            Optional<MatchedTransferLog> log, String kind) {
        return new ObservationEvidence(
                classification, blockNumber, blockHash, confirmations,
                Optional.of(receipt.isStatusOK()), receiptDigest,
                transaction, log, kind);
    }

    private static String addressTopic(String address) {
        return "0x" + "0".repeat(24) + Numeric.cleanHexPrefix(normalizeAddress(address));
    }

    private static boolean equalsAddress(String left, String right) {
        try {
            return normalizeAddress(left).equals(normalizeAddress(right));
        } catch (RuntimeException malformed) {
            return false;
        }
    }

    private static String normalizeAddress(String value) {
        String clean = Numeric.cleanHexPrefix(Objects.requireNonNull(value, "address"));
        if (clean.length() != 40 || !clean.matches("[0-9a-fA-F]{40}")) {
            throw new IllegalArgumentException("Ethereum address is malformed");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    private static String normalizeHash(String value) {
        String clean = Numeric.cleanHexPrefix(Objects.requireNonNull(value, "hash"));
        if (clean.length() != 64 || !clean.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("Ethereum hash is malformed");
        }
        return "0x" + clean.toLowerCase(Locale.ROOT);
    }

    private static Optional<String> normalizedHash(String value) {
        try {
            return Optional.of(normalizeHash(value));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    private static String canonical(Object... values) {
        return java.util.Arrays.stream(values).map(String::valueOf)
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private static String sha256(String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String sha256(byte[] value) {
        try {
            return HEX.formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException("SHA-256 is unavailable", failure);
        }
    }

    private static EvidenceRef evidence(
            EthereumWalletTransferAttemptStore.AttemptRow row, String kind) {
        return new EvidenceRef("internal:local-ethereum-wallet-transfer:"
                + kind + ":" + row.operationId());
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MICROS);
    }

    @Override
    public void close() {
        submissionClient.shutdown();
        observationClient.shutdown();
    }

    @FunctionalInterface
    interface SubmissionTransport {
        EthSendTransaction send(String rawTransaction) throws IOException;
    }

    @FunctionalInterface
    interface SubmissionReadiness {
        void verify() throws IOException;
    }

    public record Configuration(
            long chainId, String contractAddress, String contractVersion,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
            BigInteger gasLimit, int requiredConfirmations,
            String assetId, String unitId, int unitVersion, int unitScale,
            String policyVersion) {

        public Configuration {
            if (chainId != 31_337L) {
                throw new IllegalArgumentException("local chain ID must be 31337");
            }
            contractAddress = normalizeAddress(contractAddress);
            requireText(contractVersion, "contractVersion");
            requireNonNegative(maxPriorityFeePerGas, "maxPriorityFeePerGas");
            requireNonNegative(maxFeePerGas, "maxFeePerGas");
            if (maxFeePerGas.compareTo(maxPriorityFeePerGas) < 0
                    || gasLimit == null || gasLimit.signum() <= 0) {
                throw new IllegalArgumentException("local fee policy is invalid");
            }
            if (requiredConfirmations < 1 || requiredConfirmations > 100
                    || unitVersion < 1 || unitScale < 0 || unitScale > 255) {
                throw new IllegalArgumentException("local transfer policy is invalid");
            }
            requireText(assetId, "assetId");
            requireText(unitId, "unitId");
            requireText(policyVersion, "policyVersion");
        }

        private static void requireText(String value, String field) {
            if (value == null || value.isBlank() || value.length() > 128) {
                throw new IllegalArgumentException(field + " must be non-blank and bounded");
            }
        }

        private static void requireNonNegative(BigInteger value, String field) {
            if (value == null || value.signum() < 0) {
                throw new IllegalArgumentException(field + " must not be negative");
            }
        }
    }

    private record TransactionEvidence(
            String sourceAddress, String contractAddress,
            BigInteger nonce, String calldataSha256) { }

    private record MatchedTransferLog(
            String sourceAddress, String destinationAddress,
            BigInteger amount, int count, String evidenceSha256) { }

    private record ObservationEvidence(
            ChainPort.ObservationClassification classification,
            Optional<BigInteger> blockNumber,
            Optional<String> blockHash,
            Optional<BigInteger> confirmations,
            Optional<Boolean> receiptSuccess,
            Optional<String> receiptDigest,
            Optional<TransactionEvidence> transaction,
            Optional<MatchedTransferLog> transferLog,
            String evidenceKind) {

        ObservationEvidence reclassified(
                ChainPort.ObservationClassification changed,
                String changedEvidenceKind) {
            return new ObservationEvidence(
                    changed, blockNumber, blockHash, confirmations,
                    receiptSuccess, receiptDigest, transaction,
                    transferLog, changedEvidenceKind);
        }

        EthereumWalletTransferAttemptStore.ObservationDraft toDraft() {
            return new EthereumWalletTransferAttemptStore.ObservationDraft(
                    classification, blockNumber, blockHash, confirmations,
                    receiptSuccess, receiptDigest,
                    transaction.map(TransactionEvidence::sourceAddress),
                    transaction.map(TransactionEvidence::contractAddress),
                    transaction.map(TransactionEvidence::nonce),
                    transaction.map(TransactionEvidence::calldataSha256),
                    transferLog.map(MatchedTransferLog::sourceAddress),
                    transferLog.map(MatchedTransferLog::destinationAddress),
                    transferLog.map(MatchedTransferLog::amount),
                    transferLog.map(MatchedTransferLog::count),
                    transferLog.map(MatchedTransferLog::evidenceSha256));
        }
    }
}
