package io.github.johnwhitton.digitalbanking.ethereum.web3j;

import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit JDBC persistence for local Ethereum nonce and attempt evidence. */
final class EthereumMintAttemptStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    EthereumMintAttemptStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    AttemptRow prepare(
            UUID deliveryId,
            OperationId operationId,
            AttemptId attemptId,
            long chainId,
            String signingAddress,
            BigInteger networkNonce,
            Instant now,
            Function<BigInteger, AttemptDraft> draftFactory) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        return Objects.requireNonNull(transaction.execute(status -> {
            Optional<AttemptRow> existing = find(operationId, attemptId);
            if (existing.isPresent()) {
                AttemptRow retained = existing.orElseThrow();
                if (!retained.deliveryId().equals(deliveryId)) {
                    throw new IllegalStateException(
                            "operation attempt is already bound to another delivery");
                }
                return retained;
            }

            jdbc.sql("""
                    INSERT INTO ethereum_nonce_cursor (
                        chain_id, signing_address, next_nonce, updated_at)
                    VALUES (:chainId, :signingAddress, :networkNonce, :now)
                    ON CONFLICT (chain_id, signing_address) DO NOTHING
                    """)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("signingAddress", signingAddress)
                    .param("networkNonce", networkNonce)
                    .param("now", utc(now))
                    .update();
            BigInteger nonce = jdbc.sql("""
                    SELECT next_nonce FROM ethereum_nonce_cursor
                    WHERE chain_id = :chainId AND signing_address = :signingAddress
                    FOR UPDATE
                    """)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("signingAddress", signingAddress)
                    .query(BigInteger.class)
                    .single();
            jdbc.sql("""
                    UPDATE ethereum_nonce_cursor
                    SET next_nonce = :nextNonce, updated_at = :now
                    WHERE chain_id = :chainId AND signing_address = :signingAddress
                    """)
                    .param("nextNonce", nonce.add(BigInteger.ONE))
                    .param("now", utc(now))
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("signingAddress", signingAddress)
                    .update();

            AttemptDraft draft = Objects.requireNonNull(
                    draftFactory.apply(nonce), "draftFactory result");
            jdbc.sql("""
                    INSERT INTO ethereum_mint_attempt (
                        operation_id, operation_attempt_id, delivery_id, network,
                        chain_id, contract_address, recipient_address, signing_address,
                        signing_key_alias, signing_key_metadata_version,
                        observation_policy_version, required_confirmations, nonce,
                        transaction_type, max_priority_fee_per_gas, max_fee_per_gas,
                        gas_limit, transaction_value, calldata, calldata_sha256,
                        unsigned_transaction, signing_digest, attempt_status,
                        created_at, updated_at)
                    VALUES (
                        :operationId, :attemptId, :deliveryId, 'LOCAL_ANVIL',
                        :chainId, :contractAddress, :recipientAddress, :signingAddress,
                        :keyAlias, :keyMetadataVersion, :policyVersion,
                        :requiredConfirmations, :nonce,
                        2, :maxPriorityFee, :maxFee, :gasLimit, 0,
                        :calldata, :calldataSha256, :unsignedTransaction,
                        :signingDigest, 'PREPARED', :now, :now)
                    """)
                    .param("operationId", operationId.value())
                    .param("attemptId", attemptId.value())
                    .param("deliveryId", deliveryId)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("contractAddress", draft.contractAddress())
                    .param("recipientAddress", draft.recipientAddress())
                    .param("signingAddress", signingAddress)
                    .param("keyAlias", draft.signingKeyAlias())
                    .param("keyMetadataVersion", draft.signingKeyMetadataVersion())
                    .param("policyVersion", draft.observationPolicyVersion())
                    .param("requiredConfirmations", draft.requiredConfirmations())
                    .param("nonce", nonce)
                    .param("maxPriorityFee", draft.maxPriorityFeePerGas())
                    .param("maxFee", draft.maxFeePerGas())
                    .param("gasLimit", draft.gasLimit())
                    .param("calldata", draft.calldata())
                    .param("calldataSha256", draft.calldataSha256())
                    .param("unsignedTransaction", draft.unsignedTransaction())
                    .param("signingDigest", draft.signingDigest())
                    .param("now", utc(now))
                    .update();
            return find(operationId, attemptId).orElseThrow();
        }));
    }

    Optional<AttemptRow> find(OperationId operationId, AttemptId attemptId) {
        return jdbc.sql("""
                SELECT operation_id, operation_attempt_id, delivery_id, chain_id,
                       contract_address, recipient_address, signing_address,
                       signing_key_alias, signing_key_metadata_version,
                       observation_policy_version, required_confirmations, nonce,
                       max_priority_fee_per_gas, max_fee_per_gas, gas_limit,
                       calldata, calldata_sha256, unsigned_transaction, signing_digest,
                       signature_sha256, signature_encoding, signed_transaction,
                       transaction_hash, attempt_status, block_number, block_hash
                FROM ethereum_mint_attempt
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                """)
                .param("operationId", operationId.value())
                .param("attemptId", attemptId.value())
                .query(this::map)
                .optional();
    }

    AttemptRow attachSignature(
            AttemptRow current,
            String signatureSha256,
            String signatureEncoding,
            String signedTransaction,
            String transactionHash,
            Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            AttemptRow retained = find(current.operationId(), current.attemptId()).orElseThrow();
            if (retained.status() != AttemptStatus.PREPARED) {
                if (!Objects.equals(retained.signatureSha256(), signatureSha256)
                        || !Objects.equals(retained.transactionHash(), transactionHash)) {
                    throw new IllegalStateException(
                            "attempt already retains different signed transaction evidence");
                }
                return retained;
            }
            int changed = jdbc.sql("""
                    UPDATE ethereum_mint_attempt
                    SET signature_sha256 = :signatureSha256,
                        signature_encoding = :signatureEncoding,
                        signed_transaction = :signedTransaction,
                        transaction_hash = :transactionHash,
                        attempt_status = 'SIGNED', updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                      AND attempt_status = 'PREPARED'
                    """)
                    .param("signatureSha256", signatureSha256)
                    .param("signatureEncoding", signatureEncoding)
                    .param("signedTransaction", signedTransaction)
                    .param("transactionHash", transactionHash)
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .update();
            if (changed != 1) {
                throw new IllegalStateException("signed attempt update was fenced");
            }
            return find(current.operationId(), current.attemptId()).orElseThrow();
        }));
    }

    SubmissionClaim claimSubmission(AttemptRow current, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            AttemptRow retained = find(current.operationId(), current.attemptId()).orElseThrow();
            if (retained.status() != AttemptStatus.SIGNED) {
                return new SubmissionClaim(retained, false);
            }
            int changed = jdbc.sql("""
                    UPDATE ethereum_mint_attempt
                    SET attempt_status = 'SUBMISSION_STARTED',
                        submission_started_at = :now, updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                      AND attempt_status = 'SIGNED'
                    """)
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .update();
            return new SubmissionClaim(
                    find(current.operationId(), current.attemptId()).orElseThrow(),
                    changed == 1);
        }));
    }

    AttemptRow recordSubmission(
            AttemptRow current, AttemptStatus status, String safeCode, Instant now) {
        if (!status.submissionOutcome()) {
            throw new IllegalArgumentException("invalid submission outcome");
        }
        int changed = jdbc.sql("""
                UPDATE ethereum_mint_attempt
                SET attempt_status = :status, submission_recorded_at = :now,
                    submission_code = :safeCode, updated_at = :now
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                  AND attempt_status = 'SUBMISSION_STARTED'
                """)
                .param("status", status.name())
                .param("now", utc(now))
                .param("safeCode", safeCode)
                .param("operationId", current.operationId().value())
                .param("attemptId", current.attemptId().value())
                .update();
        if (changed != 1) {
            throw new IllegalStateException("submission outcome transition was not applied");
        }
        return find(current.operationId(), current.attemptId()).orElseThrow();
    }

    AttemptRow recordObservation(
            AttemptRow current,
            ObservationDraft observation,
            String evidenceRef,
            Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.sql("""
                    SELECT operation_attempt_id
                    FROM ethereum_mint_attempt
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                    FOR UPDATE
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(UUID.class)
                    .single();
            AttemptRow retained = find(current.operationId(), current.attemptId())
                    .orElseThrow();
            int sequence = jdbc.sql("""
                    SELECT COALESCE(MAX(observation_sequence), 0) + 1
                    FROM ethereum_mint_observation
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(Integer.class)
                    .single();
            jdbc.sql("""
                    INSERT INTO ethereum_mint_observation (
                        operation_id, operation_attempt_id, observation_sequence,
                        observation_status, transaction_hash, block_number,
                        block_hash, observation_source, finality_policy_version,
                        required_confirmations, observed_confirmations,
                        receipt_success, receipt_evidence_sha256,
                        observed_sender_address, observed_contract_address,
                        observed_nonce, observed_calldata_sha256,
                        mint_recipient_address, mint_atomic_amount,
                        mint_log_evidence_sha256, observed_at, evidence_ref)
                    VALUES (
                        :operationId, :attemptId, :sequence, :status,
                        :transactionHash, :blockNumber, :blockHash,
                        'LOCAL_ANVIL_RPC', :policyVersion, :requiredConfirmations,
                        :observedConfirmations, :receiptSuccess, :receiptEvidenceSha256,
                        :observedSenderAddress, :observedContractAddress,
                        :observedNonce, :observedCalldataSha256,
                        :mintRecipientAddress, :mintAtomicAmount,
                        :mintLogEvidenceSha256, :now, :evidenceRef)
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .param("sequence", sequence)
                    .param("status", observation.classification().name())
                    .param("transactionHash", retained.transactionHash())
                    .param("blockNumber", observation.blockNumber().orElse(null))
                    .param("blockHash", observation.blockHash().orElse(null))
                    .param("policyVersion", retained.observationPolicyVersion())
                    .param("requiredConfirmations", retained.requiredConfirmations())
                    .param("observedConfirmations",
                            observation.observedConfirmations().orElse(null))
                    .param("receiptSuccess", observation.receiptSuccess().orElse(null))
                    .param("receiptEvidenceSha256",
                            observation.receiptEvidenceSha256().orElse(null))
                    .param("observedSenderAddress",
                            observation.observedSenderAddress().orElse(null))
                    .param("observedContractAddress",
                            observation.observedContractAddress().orElse(null))
                    .param("observedNonce", observation.observedNonce().orElse(null))
                    .param("observedCalldataSha256",
                            observation.observedCalldataSha256().orElse(null))
                    .param("mintRecipientAddress",
                            observation.mintRecipientAddress().orElse(null))
                    .param("mintAtomicAmount", observation.mintAtomicAmount().orElse(null))
                    .param("mintLogEvidenceSha256",
                            observation.mintLogEvidenceSha256().orElse(null))
                    .param("now", utc(now))
                    .param("evidenceRef", evidenceRef)
                    .update();
            AttemptStatus statusValue = observation.classification()
                            == ChainPort.ObservationClassification.ABSENT_OR_PENDING
                    ? retained.status() : AttemptStatus.valueOf(observation.classification().name());
            jdbc.sql("""
                    UPDATE ethereum_mint_attempt
                    SET attempt_status = :status, block_number = :blockNumber,
                        block_hash = :blockHash,
                        reconciliation_disposition = :disposition,
                        updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                    """)
                    .param("status", statusValue.name())
                    .param("blockNumber", observation.blockNumber().orElse(null))
                    .param("blockHash", observation.blockHash().orElse(null))
                    .param("disposition", observation.classification().name())
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .update();
            return find(current.operationId(), current.attemptId()).orElseThrow();
        }));
    }

    private AttemptRow map(ResultSet row, int rowNumber) throws SQLException {
        return new AttemptRow(
                new OperationId(row.getObject("operation_id", UUID.class)),
                new AttemptId(row.getObject("operation_attempt_id", UUID.class)),
                row.getObject("delivery_id", UUID.class),
                row.getBigDecimal("chain_id").toBigIntegerExact(),
                row.getString("contract_address"), row.getString("recipient_address"),
                row.getString("signing_address"), row.getString("signing_key_alias"),
                row.getString("signing_key_metadata_version"),
                row.getString("observation_policy_version"),
                row.getInt("required_confirmations"),
                row.getBigDecimal("nonce").toBigIntegerExact(),
                row.getBigDecimal("max_priority_fee_per_gas").toBigIntegerExact(),
                row.getBigDecimal("max_fee_per_gas").toBigIntegerExact(),
                row.getBigDecimal("gas_limit").toBigIntegerExact(),
                row.getString("calldata"), row.getString("calldata_sha256"),
                row.getString("unsigned_transaction"), row.getString("signing_digest"),
                row.getString("signature_sha256"), row.getString("signature_encoding"),
                row.getString("signed_transaction"), row.getString("transaction_hash"),
                AttemptStatus.valueOf(row.getString("attempt_status")),
                optionalInteger(row, "block_number"),
                Optional.ofNullable(row.getString("block_hash")));
    }

    private static Optional<BigInteger> optionalInteger(ResultSet row, String column)
            throws SQLException {
        java.math.BigDecimal value = row.getBigDecimal(column);
        return value == null ? Optional.empty() : Optional.of(value.toBigIntegerExact());
    }

    private static OffsetDateTime utc(Instant instant) {
        return Objects.requireNonNull(instant, "instant").atOffset(ZoneOffset.UTC);
    }

    record AttemptDraft(
            String contractAddress,
            String recipientAddress,
            String signingKeyAlias,
            String signingKeyMetadataVersion,
            String observationPolicyVersion,
            int requiredConfirmations,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            String calldata,
            String calldataSha256,
            String unsignedTransaction,
            String signingDigest) {
    }

    record AttemptRow(
            OperationId operationId,
            AttemptId attemptId,
            UUID deliveryId,
            BigInteger chainId,
            String contractAddress,
            String recipientAddress,
            String signingAddress,
            String signingKeyAlias,
            String signingKeyMetadataVersion,
            String observationPolicyVersion,
            int requiredConfirmations,
            BigInteger nonce,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            String calldata,
            String calldataSha256,
            String unsignedTransaction,
            String signingDigest,
            String signatureSha256,
            String signatureEncoding,
            String signedTransaction,
            String transactionHash,
            AttemptStatus status,
            Optional<BigInteger> blockNumber,
            Optional<String> blockHash) {
    }

    record ObservationDraft(
            ChainPort.ObservationClassification classification,
            Optional<BigInteger> blockNumber,
            Optional<String> blockHash,
            Optional<BigInteger> observedConfirmations,
            Optional<Boolean> receiptSuccess,
            Optional<String> receiptEvidenceSha256,
            Optional<String> observedSenderAddress,
            Optional<String> observedContractAddress,
            Optional<BigInteger> observedNonce,
            Optional<String> observedCalldataSha256,
            Optional<String> mintRecipientAddress,
            Optional<BigInteger> mintAtomicAmount,
            Optional<String> mintLogEvidenceSha256) {
    }

    record SubmissionClaim(AttemptRow attempt, boolean claimed) {
    }

    enum AttemptStatus {
        PREPARED,
        SIGNED,
        SUBMISSION_STARTED,
        ACCEPTED,
        AMBIGUOUS,
        REJECTED,
        CONFIRMED,
        REVERTED,
        MISMATCHED,
        ORPHANED;

        boolean submissionOutcome() {
            return this == ACCEPTED || this == AMBIGUOUS || this == REJECTED;
        }
    }
}
