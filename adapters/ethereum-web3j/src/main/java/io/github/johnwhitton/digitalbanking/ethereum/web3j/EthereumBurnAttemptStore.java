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
import io.github.johnwhitton.digitalbanking.domain.operation.TokenOperation;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Durable ADMIN-scoped nonce, one-time custody consumption, and burn evidence. */
final class EthereumBurnAttemptStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    EthereumBurnAttemptStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    AttemptRow prepare(
            UUID deliveryId,
            TokenOperation operation,
            AttemptId attemptId,
            long chainId,
            String adminAddress,
            BigInteger networkNonce,
            Instant now,
            Function<Preparation, AttemptDraft> draftFactory) {
        Objects.requireNonNull(deliveryId, "deliveryId");
        return Objects.requireNonNull(transaction.execute(status -> {
            Optional<AttemptRow> existing = find(operation.operationId(), attemptId);
            if (existing.isPresent()) {
                AttemptRow retained = existing.orElseThrow();
                validateRetained(retained, operation, chainId, adminAddress);
                if (!retained.deliveryId().equals(deliveryId)) {
                    throw new IllegalStateException(
                            "burn attempt is already bound to another delivery");
                }
                return retained;
            }
            CustodyEvidence custody = lockConfirmedCustody(operation.operationId());
            if (!custody.matches(operation)) {
                throw new IllegalStateException(
                        "confirmed redemption custody does not match the accepted burn");
            }
            if (!custody.adminAddress().equals(adminAddress)) {
                throw new IllegalStateException(
                        "redemption custody ADMIN does not match burn authority");
            }

            jdbc.sql("""
                    INSERT INTO ethereum_nonce_cursor (
                        chain_id, signing_address, next_nonce, updated_at)
                    VALUES (:chainId, :adminAddress, :networkNonce, :now)
                    ON CONFLICT (chain_id, signing_address) DO NOTHING
                    """)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("adminAddress", adminAddress)
                    .param("networkNonce", networkNonce)
                    .param("now", utc(now)).update();
            BigInteger nonce = jdbc.sql("""
                    SELECT next_nonce FROM ethereum_nonce_cursor
                    WHERE chain_id = :chainId AND signing_address = :adminAddress
                    FOR UPDATE
                    """)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("adminAddress", adminAddress)
                    .query(BigInteger.class).single();
            jdbc.sql("""
                    UPDATE ethereum_nonce_cursor
                    SET next_nonce = :nextNonce, updated_at = :now
                    WHERE chain_id = :chainId AND signing_address = :adminAddress
                    """)
                    .param("nextNonce", nonce.add(BigInteger.ONE))
                    .param("now", utc(now))
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("adminAddress", adminAddress).update();

            AttemptDraft draft = Objects.requireNonNull(
                    draftFactory.apply(new Preparation(nonce, custody)),
                    "draftFactory result");
            if (!custody.contractAddress().equals(draft.contractAddress())
                    || !custody.adminKeyAlias().equals(draft.adminKeyAlias())
                    || !custody.adminRegistryVersion().equals(
                            draft.adminRegistryVersion())
                    || !custody.adminKeyVersion().equals(draft.adminKeyVersion())) {
                throw new IllegalStateException(
                        "burn authority does not match confirmed redemption custody");
            }
            var unit = operation.quantity().unit();
            jdbc.sql("""
                    INSERT INTO ethereum_burn_attempt (
                        operation_id, operation_attempt_id, correlation_id, delivery_id,
                        chain_id, contract_address, admin_address, admin_key_alias,
                        admin_registry_version, admin_key_version,
                        asset_id, unit_id, unit_version, unit_scale, quantity_atomic,
                        observation_policy_version, required_confirmations, nonce,
                        transaction_type, max_priority_fee_per_gas, max_fee_per_gas,
                        gas_limit, transaction_value, calldata, calldata_sha256,
                        unsigned_transaction, signing_digest, attempt_status,
                        created_at, updated_at)
                    VALUES (
                        :operationId, :attemptId, :correlationId, :deliveryId,
                        :chainId, :contract, :admin, :keyAlias, :registryVersion,
                        :keyVersion, :assetId, :unitId, :unitVersion, :unitScale,
                        :quantity, :policyVersion, :confirmations, :nonce, 2,
                        :priorityFee, :maxFee, :gasLimit, 0, :calldata,
                        :calldataDigest, :unsignedTransaction, :signingDigest,
                        'PREPARED', :now, :now)
                    """)
                    .param("operationId", operation.operationId().value())
                    .param("attemptId", attemptId.value())
                    .param("correlationId", custody.correlationId())
                    .param("deliveryId", deliveryId)
                    .param("chainId", BigInteger.valueOf(chainId))
                    .param("contract", draft.contractAddress())
                    .param("admin", adminAddress)
                    .param("keyAlias", draft.adminKeyAlias())
                    .param("registryVersion", draft.adminRegistryVersion())
                    .param("keyVersion", draft.adminKeyVersion())
                    .param("assetId", unit.assetId()).param("unitId", unit.unitId())
                    .param("unitVersion", unit.version()).param("unitScale", unit.scale())
                    .param("quantity", operation.quantity().atomicUnits())
                    .param("policyVersion", draft.observationPolicyVersion())
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
            int consumed = jdbc.sql("""
                    UPDATE ethereum_redemption_correlation
                    SET correlation_status = 'CONSUMED',
                        custody_evidence_ref = :evidence,
                        consumed_by_burn_attempt_id = :attemptId,
                        consumed_at = :now, updated_at = :now
                    WHERE correlation_id = :correlationId
                      AND correlation_status IN ('AWAITING_CUSTODY', 'CUSTODY_CONFIRMED')
                      AND consumed_by_burn_attempt_id IS NULL
                    """)
                    .param("evidence", custody.evidenceRef())
                    .param("attemptId", attemptId.value())
                    .param("now", utc(now))
                    .param("correlationId", custody.correlationId()).update();
            if (consumed != 1) {
                throw new IllegalStateException(
                        "redemption custody evidence was already consumed");
            }
            return find(operation.operationId(), attemptId).orElseThrow();
        }));
    }

    private static void validateRetained(
            AttemptRow retained, TokenOperation operation,
            long chainId, String adminAddress) {
        var unit = operation.quantity().unit();
        if (!retained.chainId().equals(BigInteger.valueOf(chainId))
                || !retained.adminAddress().equals(adminAddress)
                || !retained.assetId().equals(unit.assetId())
                || !retained.unitId().equals(unit.unitId())
                || retained.unitVersion() != unit.version()
                || retained.unitScale() != unit.scale()
                || !retained.quantityAtomic().equals(
                        operation.quantity().atomicUnits())) {
            throw new IllegalStateException(
                    "retained burn attempt does not match the accepted context");
        }
    }

    Optional<AttemptRow> find(OperationId operationId, AttemptId attemptId) {
        return jdbc.sql(selectAttempt())
                .param("operationId", operationId.value())
                .param("attemptId", attemptId.value())
                .query(this::map).optional();
    }

    AttemptRow attachSignature(
            AttemptRow current, String signatureSha256, String signatureEncoding,
            String transactionHash, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            AttemptRow retained = find(current.operationId(), current.attemptId())
                    .orElseThrow();
            if (retained.status() != AttemptStatus.PREPARED) {
                if (!Objects.equals(retained.signatureSha256(), signatureSha256)
                        || !Objects.equals(retained.transactionHash(), transactionHash)) {
                    throw new IllegalStateException(
                            "burn attempt retains different signed transaction evidence");
                }
                return retained;
            }
            int changed = jdbc.sql("""
                    UPDATE ethereum_burn_attempt
                    SET signature_sha256 = :signatureDigest,
                        signature_encoding = :signatureEncoding,
                        transaction_hash = :transactionHash,
                        attempt_status = 'SIGNED', updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                      AND attempt_status = 'PREPARED'
                    """)
                    .param("signatureDigest", signatureSha256)
                    .param("signatureEncoding", signatureEncoding)
                    .param("transactionHash", transactionHash)
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value()).update();
            if (changed != 1) {
                throw new IllegalStateException("burn signed-attempt update was fenced");
            }
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
                    UPDATE ethereum_burn_attempt
                    SET attempt_status = 'SUBMISSION_STARTED',
                        submission_started_at = :now, updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
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
            throw new IllegalArgumentException("invalid burn submission outcome");
        }
        int changed = jdbc.sql("""
                UPDATE ethereum_burn_attempt
                SET attempt_status = :status, submission_recorded_at = :now,
                    submission_code = :safeCode, updated_at = :now
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                  AND attempt_status = 'SUBMISSION_STARTED'
                """)
                .param("status", status.name()).param("now", utc(now))
                .param("safeCode", safeCode)
                .param("operationId", current.operationId().value())
                .param("attemptId", current.attemptId().value()).update();
        if (changed != 1) {
            throw new IllegalStateException("burn submission outcome was not applied");
        }
        return find(current.operationId(), current.attemptId()).orElseThrow();
    }

    AttemptRow recordObservation(
            AttemptRow current, ObservationDraft observation,
            String evidenceRef, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            jdbc.sql("""
                    SELECT operation_attempt_id FROM ethereum_burn_attempt
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                    FOR UPDATE
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(UUID.class).single();
            AttemptRow retained = find(current.operationId(), current.attemptId())
                    .orElseThrow();
            int sequence = jdbc.sql("""
                    SELECT COALESCE(MAX(observation_sequence), 0) + 1
                    FROM ethereum_burn_observation
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO ethereum_burn_observation (
                        operation_id, operation_attempt_id, observation_sequence,
                        observation_status, transaction_hash, block_number, block_hash,
                        observation_source, finality_policy_version,
                        required_confirmations, observed_confirmations, receipt_success,
                        receipt_evidence_sha256, observed_admin_address,
                        observed_contract_address, observed_nonce,
                        observed_calldata_sha256, event_source_address,
                        event_destination_address, event_atomic_amount, event_count,
                        event_evidence_sha256, observed_at, evidence_ref)
                    VALUES (
                        :operationId, :attemptId, :sequence, :status,
                        :transactionHash, :blockNumber, :blockHash,
                        'LOCAL_ANVIL_RPC', :policyVersion, :confirmations,
                        :observedConfirmations, :receiptSuccess, :receiptDigest,
                        :observedAdmin, :observedContract, :observedNonce,
                        :observedCalldataDigest, :eventSource, :eventDestination,
                        :eventAmount, :eventCount, :eventDigest, :now, :evidence)
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
                    .param("observedAdmin", observation.observedAdminAddress().orElse(null))
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
                    .param("now", utc(now)).param("evidence", evidenceRef).update();
            AttemptStatus next = observation.classification()
                            == ChainPort.ObservationClassification.ABSENT_OR_PENDING
                    ? retained.status()
                    : AttemptStatus.valueOf(observation.classification().name());
            jdbc.sql("""
                    UPDATE ethereum_burn_attempt
                    SET attempt_status = :status, block_number = :blockNumber,
                        block_hash = :blockHash,
                        reconciliation_disposition = :disposition,
                        updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
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

    private CustodyEvidence lockConfirmedCustody(OperationId burnOperationId) {
        return jdbc.sql("""
                SELECT correlation.correlation_id, transfer.tenant_id,
                       transfer.participant_id, transfer.asset_id, transfer.unit_id,
                       transfer.unit_version, transfer.unit_scale,
                       transfer.quantity_atomic, transfer.source_address,
                       transfer.destination_address AS admin_address,
                       transfer.destination_key_alias AS admin_key_alias,
                       transfer.destination_registry_version AS admin_registry_version,
                       transfer.destination_key_version AS admin_key_version,
                       transfer.contract_address, transfer.finality_policy_version,
                       observation.block_number, observation.block_hash,
                       observation.evidence_ref
                FROM ethereum_redemption_correlation correlation
                JOIN wallet_transfer_operation transfer
                  ON transfer.operation_id = correlation.custody_operation_id
                JOIN ethereum_wallet_transfer_attempt attempt
                  ON attempt.operation_id = transfer.operation_id
                 AND attempt.attempt_id = correlation.custody_attempt_id
                 AND attempt.effect_id = correlation.custody_effect_id
                JOIN LATERAL (
                    SELECT observed.block_number, observed.block_hash,
                           observed.evidence_ref, observed.observation_status,
                           observed.transaction_hash, observed.finality_policy_version,
                           observed.required_confirmations,
                           observed.observed_confirmations, observed.receipt_success,
                           observed.observed_source_address,
                           observed.observed_contract_address,
                           observed.observed_nonce,
                           observed.observed_calldata_sha256,
                           observed.event_source_address,
                           observed.event_destination_address,
                           observed.event_atomic_amount, observed.event_count
                    FROM ethereum_wallet_transfer_observation observed
                    WHERE observed.operation_id = transfer.operation_id
                      AND observed.attempt_id = attempt.attempt_id
                    ORDER BY observed.observation_sequence DESC
                    LIMIT 1) observation ON TRUE
                JOIN LATERAL (
                    SELECT assessed.finality_status, assessed.policy_version,
                           assessed.evidence_ref
                    FROM wallet_transfer_finality assessed
                    WHERE assessed.operation_id = transfer.operation_id
                      AND assessed.finality_type = 'BLOCKCHAIN'
                    ORDER BY assessed.history_order DESC
                    LIMIT 1) finality ON TRUE
                JOIN ethereum_redemption_balance_observation before_state
                  ON before_state.correlation_id = correlation.correlation_id
                 AND before_state.observation_stage = 'BEFORE_CUSTODY'
                JOIN ethereum_redemption_balance_observation after_state
                  ON after_state.correlation_id = correlation.correlation_id
                 AND after_state.observation_stage = 'AFTER_CUSTODY'
                WHERE correlation.burn_operation_id = :burnOperationId
                  AND correlation.correlation_status IN (
                      'AWAITING_CUSTODY', 'CUSTODY_CONFIRMED')
                  AND transfer.transfer_purpose = 'REDEMPTION_CUSTODY'
                  AND transfer.operation_status = 'COMPLETED'
                  AND attempt.attempt_status = 'CONFIRMED'
                  AND attempt.contract_address = transfer.contract_address
                  AND attempt.source_address = transfer.source_address
                  AND attempt.destination_address = transfer.destination_address
                  AND attempt.asset_id = transfer.asset_id
                  AND attempt.unit_id = transfer.unit_id
                  AND attempt.unit_version = transfer.unit_version
                  AND attempt.unit_scale = transfer.unit_scale
                  AND attempt.quantity_atomic = transfer.quantity_atomic
                  AND attempt.observation_policy_version = transfer.finality_policy_version
                  AND observation.observation_status = 'CONFIRMED'
                  AND observation.transaction_hash = attempt.transaction_hash
                  AND observation.finality_policy_version = transfer.finality_policy_version
                  AND observation.required_confirmations = attempt.required_confirmations
                  AND observation.observed_confirmations >=
                      observation.required_confirmations
                  AND observation.receipt_success IS TRUE
                  AND observation.observed_source_address = transfer.source_address
                  AND observation.observed_contract_address = transfer.contract_address
                  AND observation.observed_nonce = attempt.nonce
                  AND observation.observed_calldata_sha256 = attempt.calldata_sha256
                  AND observation.event_source_address = transfer.source_address
                  AND observation.event_destination_address = transfer.destination_address
                  AND observation.event_atomic_amount = transfer.quantity_atomic
                  AND observation.event_count = 1
                  AND observation.block_number IS NOT NULL
                  AND observation.block_hash IS NOT NULL
                  AND observation.block_number = attempt.block_number
                  AND observation.block_hash = attempt.block_hash
                  AND finality.finality_status = 'REACHED'
                  AND finality.policy_version = transfer.finality_policy_version
                  AND finality.evidence_ref = observation.evidence_ref
                  AND before_state.source_balance - transfer.quantity_atomic =
                      after_state.source_balance
                  AND before_state.admin_balance + transfer.quantity_atomic =
                      after_state.admin_balance
                  AND before_state.total_supply = after_state.total_supply
                FOR UPDATE OF correlation
                """)
                .param("burnOperationId", burnOperationId.value())
                .query((row, number) -> new CustodyEvidence(
                        row.getObject("correlation_id", UUID.class),
                        row.getString("tenant_id"), row.getString("participant_id"),
                        row.getString("asset_id"), row.getString("unit_id"),
                        row.getInt("unit_version"), row.getInt("unit_scale"),
                        integer(row, "quantity_atomic"), row.getString("source_address"),
                        row.getString("admin_address"),
                        row.getString("admin_key_alias"),
                        row.getString("admin_registry_version"),
                        row.getString("admin_key_version"),
                        row.getString("contract_address"),
                        row.getString("finality_policy_version"),
                        integer(row, "block_number"), row.getString("block_hash"),
                        row.getString("evidence_ref")))
                .optional().orElseThrow(() -> new IllegalStateException(
                        "confirmed redemption custody evidence is unavailable"));
    }

    private AttemptRow map(ResultSet row, int rowNumber) throws SQLException {
        return new AttemptRow(
                new OperationId(row.getObject("operation_id", UUID.class)),
                new AttemptId(row.getObject("operation_attempt_id", UUID.class)),
                row.getObject("correlation_id", UUID.class),
                row.getObject("delivery_id", UUID.class), integer(row, "chain_id"),
                row.getString("contract_address"), row.getString("admin_address"),
                row.getString("admin_key_alias"), row.getString("admin_registry_version"),
                row.getString("admin_key_version"), row.getString("asset_id"),
                row.getString("unit_id"), row.getInt("unit_version"),
                row.getInt("unit_scale"), integer(row, "quantity_atomic"),
                row.getString("observation_policy_version"),
                row.getInt("required_confirmations"), integer(row, "nonce"),
                integer(row, "max_priority_fee_per_gas"),
                integer(row, "max_fee_per_gas"), integer(row, "gas_limit"),
                row.getString("calldata"), row.getString("calldata_sha256"),
                row.getString("unsigned_transaction"), row.getString("signing_digest"),
                row.getString("signature_sha256"), row.getString("signature_encoding"),
                row.getString("transaction_hash"),
                AttemptStatus.valueOf(row.getString("attempt_status")),
                optionalInteger(row, "block_number"),
                Optional.ofNullable(row.getString("block_hash")));
    }

    private static String selectAttempt() {
        return """
                SELECT operation_id, operation_attempt_id, correlation_id, delivery_id,
                       chain_id, contract_address, admin_address, admin_key_alias,
                       admin_registry_version, admin_key_version,
                       asset_id, unit_id, unit_version, unit_scale, quantity_atomic,
                       observation_policy_version, required_confirmations, nonce,
                       max_priority_fee_per_gas, max_fee_per_gas, gas_limit,
                       calldata, calldata_sha256, unsigned_transaction, signing_digest,
                       signature_sha256, signature_encoding, transaction_hash,
                       attempt_status, block_number, block_hash
                FROM ethereum_burn_attempt
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                """;
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        return row.getBigDecimal(column).toBigIntegerExact();
    }

    private static Optional<BigInteger> optionalInteger(ResultSet row, String column)
            throws SQLException {
        var value = row.getBigDecimal(column);
        return value == null ? Optional.empty() : Optional.of(value.toBigIntegerExact());
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "instant").atOffset(ZoneOffset.UTC);
    }

    record Preparation(BigInteger nonce, CustodyEvidence custody) { }

    record AttemptDraft(
            String contractAddress, String adminKeyAlias,
            String adminRegistryVersion, String adminKeyVersion,
            String observationPolicyVersion, int requiredConfirmations,
            BigInteger maxPriorityFeePerGas, BigInteger maxFeePerGas,
            BigInteger gasLimit, String calldata, String calldataSha256,
            String unsignedTransaction, String signingDigest) { }

    record AttemptRow(
            OperationId operationId, AttemptId attemptId, UUID correlationId,
            UUID deliveryId, BigInteger chainId, String contractAddress,
            String adminAddress, String adminKeyAlias, String adminRegistryVersion,
            String adminKeyVersion, String assetId, String unitId, int unitVersion,
            int unitScale, BigInteger quantityAtomic,
            String observationPolicyVersion, int requiredConfirmations,
            BigInteger nonce, BigInteger maxPriorityFeePerGas,
            BigInteger maxFeePerGas, BigInteger gasLimit, String calldata,
            String calldataSha256, String unsignedTransaction, String signingDigest,
            String signatureSha256, String signatureEncoding,
            String transactionHash, AttemptStatus status,
            Optional<BigInteger> blockNumber, Optional<String> blockHash) { }

    record CustodyEvidence(
            UUID correlationId, String tenantId, String participantId,
            String assetId, String unitId, int unitVersion, int unitScale,
            BigInteger quantityAtomic, String sourceAddress, String adminAddress,
            String adminKeyAlias,
            String adminRegistryVersion, String adminKeyVersion,
            String contractAddress, String finalityPolicyVersion,
            BigInteger blockNumber, String blockHash, String evidenceRef) {

        boolean matches(TokenOperation operation) {
            var unit = operation.quantity().unit();
            return tenantId.equals(operation.acceptanceContext().tenantId())
                    && participantId.equals(operation.acceptanceContext().participantId())
                    && assetId.equals(unit.assetId()) && unitId.equals(unit.unitId())
                    && unitVersion == unit.version() && unitScale == unit.scale()
                    && quantityAtomic.equals(operation.quantity().atomicUnits());
        }
    }

    record ObservationDraft(
            ChainPort.ObservationClassification classification,
            Optional<BigInteger> blockNumber, Optional<String> blockHash,
            Optional<BigInteger> observedConfirmations,
            Optional<Boolean> receiptSuccess,
            Optional<String> receiptEvidenceSha256,
            Optional<String> observedAdminAddress,
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
