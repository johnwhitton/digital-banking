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

import io.github.johnwhitton.digitalbanking.application.WalletTransferOperation;
import io.github.johnwhitton.digitalbanking.application.port.ChainPort;
import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable source-scoped nonce, submit fence, and observation facts for wallet transfer. */
final class EthereumWalletTransferAttemptStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    EthereumWalletTransferAttemptStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    AttemptRow prepare(
            UUID deliveryId,
            WalletTransferOperation operation,
            long chainId,
            BigInteger networkNonce,
            Instant now,
            Function<BigInteger, AttemptDraft> draftFactory) {
        return Objects.requireNonNull(transaction.execute(status -> {
            Optional<AttemptRow> existing = find(
                    operation.operationId(), operation.attemptId());
            if (existing.isPresent()) {
                AttemptRow retained = existing.orElseThrow();
                if (!retained.deliveryId().equals(deliveryId)) {
                    throw new IllegalStateException(
                            "wallet transfer attempt is bound to another delivery");
                }
                return retained;
            }
            String source = operation.source().normalizedAddress();
            jdbc.sql("""
                    INSERT INTO ethereum_nonce_cursor (
                        chain_id, signing_address, next_nonce, updated_at)
                    VALUES (:chainId, :source, :networkNonce, :now)
                    ON CONFLICT (chain_id, signing_address) DO NOTHING
                    """)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("source", source)
                    .param("networkNonce", networkNonce)
                    .param("now", utc(now)).update();
            BigInteger nonce = jdbc.sql("""
                    SELECT next_nonce FROM ethereum_nonce_cursor
                    WHERE chain_id = :chainId AND signing_address = :source
                    FOR UPDATE
                    """)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("source", source).query(BigInteger.class).single();
            jdbc.sql("""
                    UPDATE ethereum_nonce_cursor
                    SET next_nonce = :nextNonce, updated_at = :now
                    WHERE chain_id = :chainId AND signing_address = :source
                    """)
                    .param("nextNonce", nonce.add(BigInteger.ONE))
                    .param("now", utc(now))
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("source", source).update();

            AttemptDraft draft = Objects.requireNonNull(
                    draftFactory.apply(nonce), "draftFactory result");
            var unit = operation.quantity().unit();
            jdbc.sql("""
                    INSERT INTO ethereum_wallet_transfer_attempt (
                        operation_id, effect_id, attempt_id, delivery_id, chain_id,
                        contract_address, source_address, destination_address,
                        source_key_alias, source_registry_version, source_key_version,
                        destination_registry_version, destination_key_version,
                        asset_id, unit_id, unit_version, unit_scale, quantity_atomic,
                        observation_policy_version, required_confirmations, nonce,
                        transaction_type, max_priority_fee_per_gas, max_fee_per_gas,
                        gas_limit, transaction_value, calldata, calldata_sha256,
                        unsigned_transaction, signing_digest, attempt_status,
                        created_at, updated_at)
                    VALUES (
                        :operationId, :effectId, :attemptId, :deliveryId, :chainId,
                        :contract, :source, :destination, :keyAlias,
                        :sourceRegistryVersion, :sourceKeyVersion,
                        :destinationRegistryVersion, :destinationKeyVersion,
                        :assetId, :unitId, :unitVersion, :unitScale, :quantity,
                        :policyVersion, :confirmations, :nonce, 2,
                        :priorityFee, :maxFee, :gasLimit, 0, :calldata,
                        :calldataDigest, :unsignedTransaction, :signingDigest,
                        'PREPARED', :now, :now)
                    """)
                    .param("operationId", operation.operationId().value())
                    .param("effectId", operation.effectId().value())
                    .param("attemptId", operation.attemptId().value())
                    .param("deliveryId", deliveryId)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("contract", operation.contractAddress())
                    .param("source", source)
                    .param("destination", operation.destination().normalizedAddress())
                    .param("keyAlias", operation.source().keyReference().value())
                    .param("sourceRegistryVersion", operation.source().registryVersion())
                    .param("sourceKeyVersion", operation.source().keyVersion())
                    .param("destinationRegistryVersion",
                            operation.destination().registryVersion())
                    .param("destinationKeyVersion", operation.destination().keyVersion())
                    .param("assetId", unit.assetId()).param("unitId", unit.unitId())
                    .param("unitVersion", unit.version()).param("unitScale", unit.scale())
                    .param("quantity", operation.quantity().atomicUnits())
                    .param("policyVersion", operation.finalityPolicyVersion())
                    .param("confirmations", draft.requiredConfirmations())
                    .param("nonce", nonce)
                    .param("priorityFee", draft.maxPriorityFeePerGas())
                    .param("maxFee", draft.maxFeePerGas())
                    .param("gasLimit", draft.gasLimit())
                    .param("calldata", draft.calldata())
                    .param("calldataDigest", draft.calldataSha256())
                    .param("unsignedTransaction", draft.unsignedTransaction())
                    .param("signingDigest", draft.signingDigest())
                    .param("now", utc(now)).update();
            return find(operation.operationId(), operation.attemptId()).orElseThrow();
        }));
    }

    Optional<AttemptRow> find(OperationId operationId, AttemptId attemptId) {
        return jdbc.sql("""
                SELECT operation_id, effect_id, attempt_id, delivery_id, chain_id,
                       contract_address, source_address, destination_address,
                       source_key_alias, source_registry_version, source_key_version,
                       destination_registry_version, destination_key_version,
                       asset_id, unit_id, unit_version, unit_scale, quantity_atomic,
                       observation_policy_version, required_confirmations, nonce,
                       max_priority_fee_per_gas, max_fee_per_gas, gas_limit,
                       calldata, calldata_sha256, unsigned_transaction, signing_digest,
                       signature_sha256, signature_encoding, signed_transaction,
                       transaction_hash, attempt_status, block_number, block_hash
                FROM ethereum_wallet_transfer_attempt
                WHERE operation_id = :operationId AND attempt_id = :attemptId
                """)
                .param("operationId", operationId.value())
                .param("attemptId", attemptId.value())
                .query(this::map).optional();
    }

    AttemptRow attachSignature(
            AttemptRow current, String signatureSha256, String signatureEncoding,
            String signedTransaction, String transactionHash, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            AttemptRow retained = find(current.operationId(), current.attemptId())
                    .orElseThrow();
            if (retained.status() != AttemptStatus.PREPARED) {
                if (!Objects.equals(retained.signatureSha256(), signatureSha256)
                        || !Objects.equals(retained.transactionHash(), transactionHash)) {
                    throw new IllegalStateException(
                            "attempt retains different signed transaction evidence");
                }
                return retained;
            }
            int changed = jdbc.sql("""
                    UPDATE ethereum_wallet_transfer_attempt
                    SET signature_sha256 = :signatureDigest,
                        signature_encoding = :signatureEncoding,
                        signed_transaction = :signedTransaction,
                        transaction_hash = :transactionHash,
                        attempt_status = 'SIGNED', updated_at = :now
                    WHERE operation_id = :operationId AND attempt_id = :attemptId
                      AND attempt_status = 'PREPARED'
                    """)
                    .param("signatureDigest", signatureSha256)
                    .param("signatureEncoding", signatureEncoding)
                    .param("signedTransaction", signedTransaction)
                    .param("transactionHash", transactionHash)
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value()).update();
            requireOne(changed, "signature attachment");
            return find(current.operationId(), current.attemptId()).orElseThrow();
        }));
    }

    SubmissionClaim claimSubmission(AttemptRow current, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            AttemptRow retained = find(current.operationId(), current.attemptId())
                    .orElseThrow();
            if (retained.status() != AttemptStatus.SIGNED) {
                return new SubmissionClaim(retained, false);
            }
            int changed = jdbc.sql("""
                    UPDATE ethereum_wallet_transfer_attempt
                    SET attempt_status = 'SUBMISSION_STARTED',
                        submission_started_at = :now, updated_at = :now
                    WHERE operation_id = :operationId AND attempt_id = :attemptId
                      AND attempt_status = 'SIGNED'
                    """)
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value()).update();
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
                UPDATE ethereum_wallet_transfer_attempt
                SET attempt_status = :status, submission_recorded_at = :now,
                    submission_code = :safeCode, updated_at = :now
                WHERE operation_id = :operationId AND attempt_id = :attemptId
                  AND attempt_status = 'SUBMISSION_STARTED'
                """)
                .param("status", status.name()).param("now", utc(now))
                .param("safeCode", safeCode)
                .param("operationId", current.operationId().value())
                .param("attemptId", current.attemptId().value()).update();
        requireOne(changed, "submission outcome");
        return find(current.operationId(), current.attemptId()).orElseThrow();
    }

    AttemptRow recordObservation(
            AttemptRow current, ObservationDraft observation,
            String evidenceRef, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.sql("""
                    SELECT attempt_id FROM ethereum_wallet_transfer_attempt
                    WHERE operation_id = :operationId AND attempt_id = :attemptId
                    FOR UPDATE
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(UUID.class).single();
            AttemptRow retained = find(current.operationId(), current.attemptId())
                    .orElseThrow();
            int sequence = jdbc.sql("""
                    SELECT COALESCE(MAX(observation_sequence), 0) + 1
                    FROM ethereum_wallet_transfer_observation
                    WHERE operation_id = :operationId AND attempt_id = :attemptId
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO ethereum_wallet_transfer_observation (
                        operation_id, attempt_id, observation_sequence,
                        observation_status, transaction_hash, block_number, block_hash,
                        observation_source, finality_policy_version,
                        required_confirmations, observed_confirmations,
                        receipt_success, receipt_evidence_sha256,
                        observed_source_address, observed_contract_address,
                        observed_nonce, observed_calldata_sha256,
                        event_source_address, event_destination_address,
                        event_atomic_amount, event_count, event_evidence_sha256,
                        observed_at, evidence_ref)
                    VALUES (
                        :operationId, :attemptId, :sequence, :status,
                        :transactionHash, :blockNumber, :blockHash,
                        'LOCAL_ANVIL_RPC', :policyVersion, :confirmations,
                        :observedConfirmations, :receiptSuccess, :receiptDigest,
                        :observedSource, :observedContract, :observedNonce,
                        :observedCalldataDigest, :eventSource, :eventDestination,
                        :eventAmount, :eventCount, :eventDigest, :now, :evidenceRef)
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .param("sequence", sequence)
                    .param("status", observation.classification().name())
                    .param("transactionHash", retained.transactionHash())
                    .param("blockNumber", observation.blockNumber().orElse(null))
                    .param("blockHash", observation.blockHash().orElse(null))
                    .param("policyVersion", retained.observationPolicyVersion())
                    .param("confirmations", retained.requiredConfirmations())
                    .param("observedConfirmations",
                            observation.observedConfirmations().orElse(null))
                    .param("receiptSuccess", observation.receiptSuccess().orElse(null))
                    .param("receiptDigest", observation.receiptEvidenceSha256().orElse(null))
                    .param("observedSource", observation.observedSourceAddress().orElse(null))
                    .param("observedContract",
                            observation.observedContractAddress().orElse(null))
                    .param("observedNonce", observation.observedNonce().orElse(null))
                    .param("observedCalldataDigest",
                            observation.observedCalldataSha256().orElse(null))
                    .param("eventSource", observation.eventSourceAddress().orElse(null))
                    .param("eventDestination",
                            observation.eventDestinationAddress().orElse(null))
                    .param("eventAmount", observation.eventAtomicAmount().orElse(null))
                    .param("eventCount", observation.eventCount().orElse(null))
                    .param("eventDigest", observation.eventEvidenceSha256().orElse(null))
                    .param("now", utc(now)).param("evidenceRef", evidenceRef).update();
            AttemptStatus next = observation.classification()
                            == ChainPort.ObservationClassification.ABSENT_OR_PENDING
                    ? retained.status()
                    : AttemptStatus.valueOf(observation.classification().name());
            jdbc.sql("""
                    UPDATE ethereum_wallet_transfer_attempt
                    SET attempt_status = :status, block_number = :blockNumber,
                        block_hash = :blockHash,
                        reconciliation_disposition = :disposition, updated_at = :now
                    WHERE operation_id = :operationId AND attempt_id = :attemptId
                    """)
                    .param("status", next.name())
                    .param("blockNumber", observation.blockNumber().orElse(null))
                    .param("blockHash", observation.blockHash().orElse(null))
                    .param("disposition", observation.classification().name())
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value()).update();
            return find(current.operationId(), current.attemptId()).orElseThrow();
        }));
    }

    private AttemptRow map(ResultSet row, int rowNumber) throws SQLException {
        return new AttemptRow(
                new OperationId(row.getObject("operation_id", UUID.class)),
                new TransferEffect.Id(row.getObject("effect_id", UUID.class)),
                new AttemptId(row.getObject("attempt_id", UUID.class)),
                row.getObject("delivery_id", UUID.class),
                row.getBigDecimal("chain_id").toBigIntegerExact(),
                row.getString("contract_address"), row.getString("source_address"),
                row.getString("destination_address"), row.getString("source_key_alias"),
                row.getString("source_registry_version"),
                row.getString("source_key_version"),
                row.getString("destination_registry_version"),
                row.getString("destination_key_version"), row.getString("asset_id"),
                row.getString("unit_id"), row.getInt("unit_version"),
                row.getInt("unit_scale"), integer(row, "quantity_atomic"),
                row.getString("observation_policy_version"),
                row.getInt("required_confirmations"), integer(row, "nonce"),
                integer(row, "max_priority_fee_per_gas"),
                integer(row, "max_fee_per_gas"), integer(row, "gas_limit"),
                row.getString("calldata"), row.getString("calldata_sha256"),
                row.getString("unsigned_transaction"), row.getString("signing_digest"),
                row.getString("signature_sha256"), row.getString("signature_encoding"),
                row.getString("signed_transaction"), row.getString("transaction_hash"),
                AttemptStatus.valueOf(row.getString("attempt_status")),
                optionalInteger(row, "block_number"),
                Optional.ofNullable(row.getString("block_hash")));
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        return row.getBigDecimal(column).toBigIntegerExact();
    }

    private static Optional<BigInteger> optionalInteger(ResultSet row, String column)
            throws SQLException {
        java.math.BigDecimal value = row.getBigDecimal(column);
        return value == null ? Optional.empty() : Optional.of(value.toBigIntegerExact());
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "instant").atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int count, String action) {
        if (count != 1) {
            throw new IllegalStateException(action + " did not affect exactly one row");
        }
    }

    record AttemptDraft(
            int requiredConfirmations,
            BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas,
            BigInteger gasLimit,
            String calldata,
            String calldataSha256,
            String unsignedTransaction,
            String signingDigest) { }

    record AttemptRow(
            OperationId operationId, TransferEffect.Id effectId,
            AttemptId attemptId, UUID deliveryId,
            BigInteger chainId, String contractAddress, String sourceAddress,
            String destinationAddress, String sourceKeyAlias,
            String sourceRegistryVersion, String sourceKeyVersion,
            String destinationRegistryVersion, String destinationKeyVersion,
            String assetId, String unitId, int unitVersion, int unitScale,
            BigInteger quantityAtomic, String observationPolicyVersion,
            int requiredConfirmations, BigInteger nonce,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
            BigInteger gasLimit, String calldata, String calldataSha256,
            String unsignedTransaction, String signingDigest,
            String signatureSha256, String signatureEncoding,
            String signedTransaction, String transactionHash,
            AttemptStatus status, Optional<BigInteger> blockNumber,
            Optional<String> blockHash) { }

    record ObservationDraft(
            ChainPort.ObservationClassification classification,
            Optional<BigInteger> blockNumber,
            Optional<String> blockHash,
            Optional<BigInteger> observedConfirmations,
            Optional<Boolean> receiptSuccess,
            Optional<String> receiptEvidenceSha256,
            Optional<String> observedSourceAddress,
            Optional<String> observedContractAddress,
            Optional<BigInteger> observedNonce,
            Optional<String> observedCalldataSha256,
            Optional<String> eventSourceAddress,
            Optional<String> eventDestinationAddress,
            Optional<BigInteger> eventAtomicAmount,
            Optional<Integer> eventCount,
            Optional<String> eventEvidenceSha256) { }

    record SubmissionClaim(AttemptRow attempt, boolean claimed) { }

    enum AttemptStatus {
        PREPARED, SIGNED, SUBMISSION_STARTED, ACCEPTED, AMBIGUOUS, REJECTED,
        CONFIRMED, REVERTED, MISMATCHED, ORPHANED;

        boolean submissionOutcome() {
            return this == ACCEPTED || this == AMBIGUOUS || this == REJECTED;
        }
    }
}
