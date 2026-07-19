package io.github.johnwhitton.digitalbanking.solana.sava;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.domain.operation.AttemptId;
import io.github.johnwhitton.digitalbanking.domain.operation.OperationId;
import io.github.johnwhitton.digitalbanking.domain.signing.KeyAlias;
import io.github.johnwhitton.digitalbanking.domain.signing.SigningRequest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit JDBC persistence for one restart-safe local Solana mint attempt. */
final class SolanaMintAttemptStore {

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;

    SolanaMintAttemptStore(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    AttemptRow prepare(Draft draft, Instant now) {
        Objects.requireNonNull(draft, "draft");
        return Objects.requireNonNull(transaction.execute(status -> {
            Optional<AttemptRow> existing = find(draft.operationId(), draft.attemptId());
            if (existing.isPresent()) {
                AttemptRow retained = existing.orElseThrow();
                if (!retained.matches(draft)) {
                    throw new IllegalStateException(
                            "operation attempt is already bound to different Solana context");
                }
                return retained;
            }
            CustodyEvidence custody = draft.effectKind() == EffectKind.BURN
                    ? lockConfirmedCustody(draft) : null;
            jdbc.sql("""
                    INSERT INTO solana_mint_attempt (
                        operation_id, operation_attempt_id, delivery_id,
                        native_attempt_id, replacement_parent_id, replacement_sequence,
                        effect_kind, token_operation_id, wallet_transfer_operation_id,
                        redemption_correlation_id, redemption_registry_version,
                        network, cluster_identity, route_snapshot_ref,
                        token_program_id, ata_program_id, mint_address,
                        source_owner, source_ata, pre_source_balance,
                        destination_owner, destination_ata, ata_existed,
                        decimals, amount_atomic, pre_mint_supply,
                        pre_destination_balance, fee_payer_public_key,
                        fee_payer_key_alias, fee_payer_key_version,
                        mint_authority_public_key, mint_authority_key_alias,
                        mint_authority_key_version, policy_version,
                        maximum_fee_lamports, commitment, recent_blockhash,
                        last_valid_block_height, unsigned_transaction,
                        message_sha256, instruction_sha256, attempt_status,
                        submit_fence, aggregate_version, created_at, updated_at)
                    VALUES (
                        :operationId, :attemptId, :deliveryId,
                        :nativeAttemptId, :replacementParentId, :replacementSequence,
                        :effectKind, :tokenOperationId, :walletTransferOperationId,
                        :redemptionCorrelationId, :redemptionRegistryVersion,
                        'LOCAL_SOLANA', :clusterIdentity, :routeSnapshotRef,
                        :tokenProgramId, :ataProgramId, :mintAddress,
                        :sourceOwner, :sourceAta, :preSourceBalance,
                        :destinationOwner, :destinationAta, :ataExisted,
                        :decimals, :amount, :preSupply, :preBalance,
                        :feePayer, :feePayerAlias, :feePayerVersion,
                        :mintAuthority, :mintAuthorityAlias, :mintAuthorityVersion,
                        :policyVersion, :maximumFeeLamports,
                        'finalized', :blockhash, :lastValidHeight,
                        :unsignedTransaction, :messageSha256, :instructionSha256,
                        'PREPARED', 0, 0, :now, :now)
                    ON CONFLICT (operation_id, operation_attempt_id) DO NOTHING
                    """)
                    .param("operationId", draft.operationId().value())
                    .param("attemptId", draft.attemptId().value())
                    .param("deliveryId", draft.deliveryId())
                    .param("nativeAttemptId", draft.nativeAttemptId())
                    .param("replacementParentId", draft.replacementParentId().orElse(null))
                    .param("replacementSequence", draft.replacementSequence())
                    .param("effectKind", draft.effectKind().name())
                    .param("tokenOperationId", draft.effectKind() != EffectKind.TRANSFER
                            ? draft.operationId().value() : null)
                    .param("walletTransferOperationId",
                            draft.effectKind() == EffectKind.TRANSFER
                                    ? draft.operationId().value() : null)
                    .param("redemptionCorrelationId", custody == null
                            ? null : custody.correlationId())
                    .param("redemptionRegistryVersion",
                            draft.redemptionRegistryVersion().orElse(null))
                    .param("clusterIdentity", draft.clusterIdentity())
                    .param("routeSnapshotRef", draft.routeSnapshotRef())
                    .param("tokenProgramId", draft.tokenProgramId())
                    .param("ataProgramId", draft.ataProgramId())
                    .param("mintAddress", draft.mintAddress())
                    .param("sourceOwner", draft.sourceOwner().orElse(null))
                    .param("sourceAta", draft.sourceAta().orElse(null))
                    .param("preSourceBalance", draft.preSourceBalance().orElse(null))
                    .param("destinationOwner", draft.destinationOwner())
                    .param("destinationAta", draft.destinationAta())
                    .param("ataExisted", draft.ataExisted())
                    .param("decimals", draft.decimals())
                    .param("amount", draft.amount())
                    .param("preSupply", draft.preMintSupply())
                    .param("preBalance", draft.preDestinationBalance())
                    .param("feePayer", draft.feePayer().publicKey())
                    .param("feePayerAlias", draft.feePayer().keyAlias().value())
                    .param("feePayerVersion", draft.feePayer().keyVersion())
                    .param("mintAuthority", draft.mintAuthority().publicKey())
                    .param("mintAuthorityAlias", draft.mintAuthority().keyAlias().value())
                    .param("mintAuthorityVersion", draft.mintAuthority().keyVersion())
                    .param("policyVersion", draft.policyVersion())
                    .param("maximumFeeLamports", draft.maximumFeeLamports())
                    .param("blockhash", draft.recentBlockhash())
                    .param("lastValidHeight", draft.lastValidBlockHeight())
                    .param("unsignedTransaction", draft.unsignedTransaction())
                    .param("messageSha256", draft.messageSha256())
                    .param("instructionSha256", draft.instructionSha256())
                    .param("now", utc(now))
                    .update();
            AttemptRow retained = find(draft.operationId(), draft.attemptId()).orElseThrow();
            if (!retained.matches(draft)) {
                throw new IllegalStateException(
                        "operation attempt is already bound to different Solana context");
            }
            if (custody != null && custody.consume()) {
                int consumed = jdbc.sql("""
                        UPDATE ethereum_redemption_correlation
                        SET correlation_status = 'CONSUMED',
                            custody_evidence_ref = :evidence,
                            consumed_by_burn_attempt_id = :attemptId,
                            consumed_at = :now, updated_at = :now
                        WHERE correlation_id = :correlationId
                          AND correlation_status IN (
                              'AWAITING_CUSTODY', 'CUSTODY_CONFIRMED')
                          AND consumed_by_burn_attempt_id IS NULL
                        """)
                        .param("evidence", custody.evidenceRef())
                        .param("attemptId", draft.attemptId().value())
                        .param("now", utc(now))
                        .param("correlationId", custody.correlationId())
                        .update();
                if (consumed != 1) {
                    throw new IllegalStateException(
                            "redemption custody evidence was already consumed");
                }
            }
            return retained;
        }));
    }

    Optional<AttemptRow> find(OperationId operationId, AttemptId attemptId) {
        return jdbc.sql("""
                SELECT operation_id, operation_attempt_id, delivery_id,
                       native_attempt_id, replacement_parent_id, replacement_sequence,
                       effect_kind, redemption_correlation_id,
                       redemption_registry_version,
                       cluster_identity, route_snapshot_ref, token_program_id,
                       ata_program_id, mint_address, source_owner, source_ata,
                       pre_source_balance, destination_owner, destination_ata,
                       ata_existed, decimals, amount_atomic, pre_mint_supply,
                       pre_destination_balance, fee_payer_public_key,
                       fee_payer_key_alias, fee_payer_key_version,
                       mint_authority_public_key, mint_authority_key_alias,
                       mint_authority_key_version, policy_version,
                       maximum_fee_lamports, recent_blockhash,
                       last_valid_block_height, unsigned_transaction,
                       message_sha256, instruction_sha256, transaction_signature,
                       attempt_status, submit_fence, submission_code, aggregate_version
                FROM solana_mint_attempt
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                """)
                .param("operationId", operationId.value())
                .param("attemptId", attemptId.value())
                .query(this::mapAttempt)
                .optional();
    }

    List<SignatureRow> signatures(OperationId operationId, AttemptId attemptId) {
        return jdbc.sql("""
                SELECT signer_order, key_role, key_alias, key_version,
                       public_key, signature_bytes, signature_sha256,
                       signature_encoding
                FROM solana_mint_signature
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                ORDER BY signer_order
                """)
                .param("operationId", operationId.value())
                .param("attemptId", attemptId.value())
                .query(this::mapSignature)
                .list();
    }

    Set<Integer> signatureOrders(OperationId operationId, AttemptId attemptId) {
        return signatures(operationId, attemptId).stream()
                .map(SignatureRow::order).collect(Collectors.toUnmodifiableSet());
    }

    AttemptRow attachSignature(
            AttemptRow expected,
            SignatureDraft signature,
            String transactionSignature,
            Instant now) {
        Objects.requireNonNull(signature, "signature");
        return Objects.requireNonNull(transaction.execute(status -> {
            lock(expected.operationId(), expected.attemptId());
            AttemptRow current = find(expected.operationId(), expected.attemptId()).orElseThrow();
            Optional<SignatureRow> existing = signatures(
                    current.operationId(), current.attemptId()).stream()
                    .filter(value -> value.order() == signature.order()).findFirst();
            if (existing.isPresent()) {
                if (!existing.orElseThrow().matches(signature)) {
                    throw new IllegalStateException(
                            "signer order already retains different signature evidence");
                }
                return current;
            }
            int next = signatures(current.operationId(), current.attemptId()).size();
            if (signature.order() != next || next > 1
                    || (next == 0 && transactionSignature == null)
                    || (next == 1 && current.transactionSignature() == null)) {
                throw new IllegalStateException("Solana signatures must be retained in order");
            }
            jdbc.sql("""
                    INSERT INTO solana_mint_signature (
                        operation_id, operation_attempt_id, signer_order,
                        effect_kind,
                        key_role, key_alias, key_version, public_key,
                        signature_bytes, signature_sha256, signature_encoding,
                        retained_at)
                    VALUES (
                        :operationId, :attemptId, :signerOrder, :effectKind, :keyRole,
                        :keyAlias, :keyVersion, :publicKey, :signatureBytes,
                        :signatureSha256, :signatureEncoding, :now)
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .param("signerOrder", signature.order())
                    .param("effectKind", current.effectKind().name())
                    .param("keyRole", signature.keyRole().name())
                    .param("keyAlias", signature.keyAlias().value())
                    .param("keyVersion", signature.keyVersion())
                    .param("publicKey", signature.publicKey())
                    .param("signatureBytes", signature.bytes())
                    .param("signatureSha256", signature.sha256())
                    .param("signatureEncoding", signature.encoding())
                    .param("now", utc(now))
                    .update();
            AttemptStatus changedStatus = signature.order() == 0
                    ? AttemptStatus.PARTIALLY_SIGNED : AttemptStatus.SIGNED;
            int changed = jdbc.sql("""
                    UPDATE solana_mint_attempt
                    SET transaction_signature = COALESCE(
                            transaction_signature, :transactionSignature),
                        attempt_status = :attemptStatus,
                        aggregate_version = aggregate_version + 1,
                        updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                      AND aggregate_version = :version
                      AND attempt_status IN ('PREPARED', 'PARTIALLY_SIGNED')
                    """)
                    .param("transactionSignature", transactionSignature)
                    .param("attemptStatus", changedStatus.name())
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .param("version", current.version())
                    .update();
            if (changed != 1) {
                throw new IllegalStateException("signature persistence was fenced");
            }
            return find(current.operationId(), current.attemptId()).orElseThrow();
        }));
    }

    SubmissionClaim claimSubmission(AttemptRow expected, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            lock(expected.operationId(), expected.attemptId());
            AttemptRow current = find(expected.operationId(), expected.attemptId()).orElseThrow();
            if (current.status() != AttemptStatus.SIGNED) {
                return new SubmissionClaim(current, false);
            }
            int changed = jdbc.sql("""
                    UPDATE solana_mint_attempt
                    SET attempt_status = 'SUBMISSION_STARTED',
                        submit_fence = submit_fence + 1,
                        submission_started_at = :now,
                        aggregate_version = aggregate_version + 1,
                        updated_at = :now
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                      AND aggregate_version = :version
                      AND attempt_status = 'SIGNED'
                    """)
                    .param("now", utc(now))
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .param("version", current.version())
                    .update();
            return new SubmissionClaim(
                    find(current.operationId(), current.attemptId()).orElseThrow(),
                    changed == 1);
        }));
    }

    AttemptRow recordSubmission(
            AttemptRow expected, AttemptStatus outcome, String safeCode, Instant now) {
        if (outcome != AttemptStatus.ACCEPTED
                && outcome != AttemptStatus.AMBIGUOUS
                && outcome != AttemptStatus.REJECTED
                && outcome != AttemptStatus.EXPIRED) {
            throw new IllegalArgumentException("invalid Solana submission outcome");
        }
        int changed = jdbc.sql("""
                UPDATE solana_mint_attempt
                SET attempt_status = :outcome,
                    submission_recorded_at = :now,
                    submission_code = :safeCode,
                    aggregate_version = aggregate_version + 1,
                    updated_at = :now
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                  AND aggregate_version = :version
                  AND attempt_status = 'SUBMISSION_STARTED'
                """)
                .param("outcome", outcome.name())
                .param("now", utc(now))
                .param("safeCode", safeCode)
                .param("operationId", expected.operationId().value())
                .param("attemptId", expected.attemptId().value())
                .param("version", expected.version())
                .update();
        if (changed != 1) {
            throw new IllegalStateException("Solana submission outcome was fenced");
        }
        return find(expected.operationId(), expected.attemptId()).orElseThrow();
    }

    AttemptRow recordObservation(AttemptRow expected, ObservationDraft draft, Instant now) {
        return Objects.requireNonNull(transaction.execute(status -> {
            lock(expected.operationId(), expected.attemptId());
            AttemptRow current = find(expected.operationId(), expected.attemptId()).orElseThrow();
            int sequence = jdbc.sql("""
                    SELECT COALESCE(MAX(observation_sequence), 0) + 1
                    FROM solana_mint_observation
                    WHERE operation_id = :operationId
                      AND operation_attempt_id = :attemptId
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .query(Integer.class).single();
            jdbc.sql("""
                    INSERT INTO solana_mint_observation (
                        operation_id, operation_attempt_id, observation_sequence,
                        effect_kind,
                        observation_status, transaction_signature, commitment,
                        slot, block_time, transaction_error_code,
                        expected_instructions, observed_mint_supply,
                        observed_destination_balance, observed_source_balance,
                        transaction_pre_source_balance,
                        transaction_post_source_balance,
                        transaction_pre_destination_balance,
                        transaction_post_destination_balance,
                        mint_delta, destination_delta, source_delta,
                        evidence_ref, observed_at)
                    VALUES (
                        :operationId, :attemptId, :sequence, :effectKind, :status,
                        :signature, :commitment, :slot, :blockTime, :errorCode,
                        :expectedInstructions, :supply, :balance, :sourceBalance,
                        :transactionPreSource, :transactionPostSource,
                        :transactionPreDestination, :transactionPostDestination,
                        :mintDelta, :destinationDelta, :sourceDelta, :evidenceRef, :now)
                    """)
                    .param("operationId", current.operationId().value())
                    .param("attemptId", current.attemptId().value())
                    .param("sequence", sequence)
                    .param("effectKind", current.effectKind().name())
                    .param("status", draft.status().name())
                    .param("signature", current.transactionSignature())
                    .param("commitment", draft.commitment())
                    .param("slot", draft.slot().orElse(null))
                    .param("blockTime", draft.blockTime().orElse(null))
                    .param("errorCode", draft.errorCode().orElse(null))
                    .param("expectedInstructions", draft.expectedInstructions())
                    .param("supply", draft.observedSupply().orElse(null))
                    .param("balance", draft.observedBalance().orElse(null))
                    .param("sourceBalance", draft.observedSourceBalance().orElse(null))
                    .param("transactionPreSource",
                            draft.transactionPreSourceBalance().orElse(null))
                    .param("transactionPostSource",
                            draft.transactionPostSourceBalance().orElse(null))
                    .param("transactionPreDestination",
                            draft.transactionPreDestinationBalance().orElse(null))
                    .param("transactionPostDestination",
                            draft.transactionPostDestinationBalance().orElse(null))
                    .param("mintDelta", draft.mintDelta().orElse(null))
                    .param("destinationDelta", draft.destinationDelta().orElse(null))
                    .param("sourceDelta", draft.sourceDelta().orElse(null))
                    .param("evidenceRef", draft.evidenceRef())
                    .param("now", utc(now))
                    .update();
            if (draft.status() != ObservationStatus.ABSENT_OR_PENDING) {
                AttemptStatus statusValue = switch (draft.status()) {
                    case CONFIRMED -> AttemptStatus.CONFIRMED;
                    case REVERTED -> AttemptStatus.REVERTED;
                    case MISMATCHED -> AttemptStatus.MISMATCHED;
                    case EXPIRED -> AttemptStatus.EXPIRED;
                    case ABSENT_OR_PENDING -> throw new IllegalStateException();
                };
                jdbc.sql("""
                        UPDATE solana_mint_attempt
                        SET attempt_status = :status,
                            aggregate_version = aggregate_version + 1,
                            updated_at = :now
                        WHERE operation_id = :operationId
                          AND operation_attempt_id = :attemptId
                        """)
                        .param("status", statusValue.name())
                        .param("now", utc(now))
                        .param("operationId", current.operationId().value())
                        .param("attemptId", current.attemptId().value())
                        .update();
            }
            return find(current.operationId(), current.attemptId()).orElseThrow();
        }));
    }

    private void lock(OperationId operationId, AttemptId attemptId) {
        jdbc.sql("""
                SELECT operation_attempt_id
                FROM solana_mint_attempt
                WHERE operation_id = :operationId
                  AND operation_attempt_id = :attemptId
                FOR UPDATE
                """)
                .param("operationId", operationId.value())
                .param("attemptId", attemptId.value())
                .query(UUID.class).single();
    }

    private CustodyEvidence lockConfirmedCustody(Draft burn) {
        CustodyEvidence evidence = jdbc.sql("""
                SELECT correlation.correlation_id,
                       transfer.destination_address AS admin_owner,
                       transfer.destination_key_alias AS admin_key_alias,
                       transfer.destination_registry_version AS admin_registry_version,
                       transfer.destination_key_version AS admin_key_version,
                       transfer.contract_address AS mint_address,
                       transfer.quantity_atomic, transfer.finality_policy_version,
                       observed.transaction_signature, observed.slot,
                       observed.evidence_ref
                FROM ethereum_redemption_correlation correlation
                JOIN token_operation burn
                  ON burn.operation_id = correlation.burn_operation_id
                JOIN wallet_transfer_operation transfer
                  ON transfer.operation_id = correlation.custody_operation_id
                JOIN solana_mint_attempt attempt
                  ON attempt.operation_id = transfer.operation_id
                 AND attempt.operation_attempt_id = correlation.custody_attempt_id
                 AND attempt.effect_kind = 'TRANSFER'
                JOIN LATERAL (
                    SELECT observation_status, transaction_signature, commitment,
                           slot, expected_instructions, observed_mint_supply,
                           observed_destination_balance, observed_source_balance,
                           transaction_pre_source_balance,
                           transaction_post_source_balance,
                           transaction_pre_destination_balance,
                           transaction_post_destination_balance,
                           mint_delta, destination_delta, source_delta, evidence_ref
                    FROM solana_mint_observation observation
                    WHERE observation.operation_id = attempt.operation_id
                      AND observation.operation_attempt_id = attempt.operation_attempt_id
                      AND observation.effect_kind = 'TRANSFER'
                    ORDER BY observation.observation_sequence DESC
                    LIMIT 1) observed ON TRUE
                JOIN LATERAL (
                    SELECT finality_status, policy_version, evidence_ref
                    FROM wallet_transfer_finality finality
                    WHERE finality.operation_id = transfer.operation_id
                      AND finality.finality_type = 'BLOCKCHAIN'
                    ORDER BY finality.history_order DESC
                    LIMIT 1) finality ON TRUE
                WHERE correlation.burn_operation_id = :burnOperationId
                  AND ((CAST(:replacementParentId AS UUID) IS NULL
                        AND correlation.correlation_status IN (
                            'AWAITING_CUSTODY', 'CUSTODY_CONFIRMED')
                        AND correlation.consumed_by_burn_attempt_id IS NULL)
                    OR (CAST(:replacementParentId AS UUID) IS NOT NULL
                        AND correlation.correlation_status = 'CONSUMED'
                        AND correlation.consumed_by_burn_attempt_id IS NOT NULL
                        AND EXISTS (
                            SELECT 1 FROM solana_mint_attempt predecessor
                            WHERE predecessor.operation_id = burn.operation_id
                              AND predecessor.native_attempt_id = :replacementParentId
                              AND predecessor.effect_kind = 'BURN'
                              AND predecessor.redemption_correlation_id =
                                  correlation.correlation_id
                              AND predecessor.attempt_status = 'EXPIRED'
                              AND predecessor.replacement_sequence + 1 =
                                  :replacementSequence)))
                  AND burn.operation_kind = 'BURN'
                  AND burn.tenant_id = transfer.tenant_id
                  AND burn.participant_id = transfer.participant_id
                  AND burn.asset_id = transfer.asset_id
                  AND burn.unit_id = transfer.unit_id
                  AND burn.unit_version = transfer.unit_version
                  AND burn.unit_scale = transfer.unit_scale
                  AND burn.quantity_atomic = transfer.quantity_atomic
                  AND transfer.transfer_purpose = 'REDEMPTION_CUSTODY'
                  AND transfer.network = 'SOLANA'
                  AND transfer.operation_status = 'COMPLETED'
                  AND transfer.effect_id = correlation.custody_effect_id
                  AND transfer.attempt_id = correlation.custody_attempt_id
                  AND attempt.attempt_status = 'CONFIRMED'
                  AND attempt.source_owner = transfer.source_address
                  AND attempt.destination_owner = transfer.destination_address
                  AND attempt.mint_address = transfer.contract_address
                  AND attempt.amount_atomic = transfer.quantity_atomic
                  AND attempt.policy_version = transfer.finality_policy_version
                  AND attempt.cluster_identity = :clusterIdentity
                  AND attempt.token_program_id = :tokenProgramId
                  AND attempt.ata_program_id = :ataProgramId
                  AND attempt.decimals = :decimals
                  AND transfer.destination_registry_version = :walletRegistryVersion
                  AND observed.observation_status = 'CONFIRMED'
                  AND observed.transaction_signature = attempt.transaction_signature
                  AND observed.commitment = 'finalized'
                  AND observed.slot IS NOT NULL
                  AND observed.expected_instructions
                  AND observed.observed_mint_supply = attempt.pre_mint_supply
                  AND observed.observed_source_balance =
                      attempt.pre_source_balance - attempt.amount_atomic
                  AND observed.observed_destination_balance =
                      attempt.pre_destination_balance + attempt.amount_atomic
                  AND observed.transaction_pre_source_balance =
                      attempt.pre_source_balance
                  AND observed.transaction_post_source_balance =
                      attempt.pre_source_balance - attempt.amount_atomic
                  AND observed.transaction_pre_destination_balance =
                      attempt.pre_destination_balance
                  AND observed.transaction_post_destination_balance =
                      attempt.pre_destination_balance + attempt.amount_atomic
                  AND observed.mint_delta = 0
                  AND observed.source_delta = attempt.amount_atomic
                  AND observed.destination_delta = attempt.amount_atomic
                  AND finality.finality_status = 'REACHED'
                  AND finality.policy_version = transfer.finality_policy_version
                  AND finality.evidence_ref = observed.evidence_ref
                FOR UPDATE OF correlation
                """)
                .param("burnOperationId", burn.operationId().value())
                .param("replacementParentId", burn.replacementParentId().orElse(null))
                .param("replacementSequence", burn.replacementSequence())
                .param("clusterIdentity", burn.clusterIdentity())
                .param("tokenProgramId", burn.tokenProgramId())
                .param("ataProgramId", burn.ataProgramId())
                .param("decimals", burn.decimals())
                .param("walletRegistryVersion",
                        burn.redemptionRegistryVersion().orElseThrow())
                .query((row, number) -> new CustodyEvidence(
                        row.getObject("correlation_id", UUID.class),
                        row.getString("admin_owner"),
                        row.getString("admin_key_alias"),
                        row.getString("admin_registry_version"),
                        row.getString("admin_key_version"),
                        row.getString("mint_address"),
                        integer(row, "quantity_atomic"),
                        row.getString("finality_policy_version"),
                        row.getString("transaction_signature"),
                        row.getLong("slot"), row.getString("evidence_ref"),
                        burn.replacementParentId().isEmpty()))
                .optional().orElseThrow(() -> new IllegalStateException(
                        "confirmed Solana redemption custody evidence is unavailable"));
        if (!evidence.matches(burn)) {
            throw new IllegalStateException(
                    "confirmed Solana redemption custody differs from burn context");
        }
        return evidence;
    }

    private AttemptRow mapAttempt(ResultSet row, int rowNumber) throws SQLException {
        EffectKind effectKind = EffectKind.valueOf(row.getString("effect_kind"));
        return new AttemptRow(
                new OperationId(row.getObject("operation_id", UUID.class)),
                new AttemptId(row.getObject("operation_attempt_id", UUID.class)),
                row.getObject("delivery_id", UUID.class),
                row.getObject("native_attempt_id", UUID.class),
                Optional.ofNullable(row.getObject("replacement_parent_id", UUID.class)),
                row.getInt("replacement_sequence"),
                effectKind,
                Optional.ofNullable(row.getObject(
                        "redemption_correlation_id", UUID.class)),
                Optional.ofNullable(row.getString("redemption_registry_version")),
                row.getString("cluster_identity"),
                row.getString("route_snapshot_ref"), row.getString("token_program_id"),
                row.getString("ata_program_id"), row.getString("mint_address"),
                Optional.ofNullable(row.getString("source_owner")),
                Optional.ofNullable(row.getString("source_ata")),
                integerOptional(row, "pre_source_balance"),
                row.getString("destination_owner"), row.getString("destination_ata"),
                row.getBoolean("ata_existed"), row.getInt("decimals"),
                integer(row, "amount_atomic"), integer(row, "pre_mint_supply"),
                integer(row, "pre_destination_balance"),
                new SignerContext(
                        new KeyAlias(row.getString("fee_payer_key_alias")),
                        SigningRequest.KeyRole.FEE_PAYER,
                        row.getString("fee_payer_key_version"),
                        row.getString("fee_payer_public_key")),
                new SignerContext(
                        new KeyAlias(row.getString("mint_authority_key_alias")),
                        switch (effectKind) {
                            case MINT -> SigningRequest.KeyRole.MINT_AUTHORITY;
                            case TRANSFER -> SigningRequest.KeyRole.TRANSFER_AUTHORITY;
                            case BURN -> SigningRequest.KeyRole.BURN_AUTHORITY;
                        },
                        row.getString("mint_authority_key_version"),
                        row.getString("mint_authority_public_key")),
                row.getString("policy_version"), integer(row, "maximum_fee_lamports"),
                row.getString("recent_blockhash"), row.getLong("last_valid_block_height"),
                row.getBytes("unsigned_transaction"), row.getString("message_sha256"),
                row.getString("instruction_sha256"),
                row.getString("transaction_signature"),
                AttemptStatus.valueOf(row.getString("attempt_status")),
                row.getLong("submit_fence"), row.getString("submission_code"),
                row.getLong("aggregate_version"));
    }

    private SignatureRow mapSignature(ResultSet row, int rowNumber) throws SQLException {
        return new SignatureRow(
                row.getInt("signer_order"),
                SigningRequest.KeyRole.valueOf(row.getString("key_role")),
                new KeyAlias(row.getString("key_alias")), row.getString("key_version"),
                row.getString("public_key"), row.getBytes("signature_bytes"),
                row.getString("signature_sha256"), row.getString("signature_encoding"));
    }

    private static BigInteger integer(ResultSet row, String column) throws SQLException {
        BigDecimal value = row.getBigDecimal(column);
        return value.toBigIntegerExact();
    }

    private static Optional<BigInteger> integerOptional(
            ResultSet row, String column) throws SQLException {
        BigDecimal value = row.getBigDecimal(column);
        return value == null ? Optional.empty() : Optional.of(value.toBigIntegerExact());
    }

    private static OffsetDateTime utc(Instant instant) {
        return Objects.requireNonNull(instant, "instant").atOffset(ZoneOffset.UTC);
    }

    record Draft(
            OperationId operationId,
            AttemptId attemptId,
            UUID deliveryId,
            UUID nativeAttemptId,
            Optional<UUID> replacementParentId,
            int replacementSequence,
            EffectKind effectKind,
            Optional<String> redemptionRegistryVersion,
            String clusterIdentity,
            String routeSnapshotRef,
            String tokenProgramId,
            String ataProgramId,
            String mintAddress,
            Optional<String> sourceOwner,
            Optional<String> sourceAta,
            Optional<BigInteger> preSourceBalance,
            String destinationOwner,
            String destinationAta,
            boolean ataExisted,
            int decimals,
            BigInteger amount,
            BigInteger preMintSupply,
            BigInteger preDestinationBalance,
            SignerContext feePayer,
            SignerContext mintAuthority,
            String policyVersion,
            BigInteger maximumFeeLamports,
            String recentBlockhash,
            long lastValidBlockHeight,
            byte[] unsignedTransaction,
            String messageSha256,
            String instructionSha256) {

        Draft(
                OperationId operationId, AttemptId attemptId, UUID deliveryId,
                UUID nativeAttemptId, Optional<UUID> replacementParentId,
                int replacementSequence, String clusterIdentity,
                String routeSnapshotRef, String tokenProgramId,
                String ataProgramId, String mintAddress,
                String destinationOwner, String destinationAta, boolean ataExisted,
                int decimals, BigInteger amount, BigInteger preMintSupply,
                BigInteger preDestinationBalance, SignerContext feePayer,
                SignerContext mintAuthority, String policyVersion,
                BigInteger maximumFeeLamports, String recentBlockhash,
                long lastValidBlockHeight, byte[] unsignedTransaction,
                String messageSha256, String instructionSha256) {
            this(operationId, attemptId, deliveryId, nativeAttemptId,
                    replacementParentId, replacementSequence, EffectKind.MINT,
                    Optional.empty(),
                    clusterIdentity, routeSnapshotRef, tokenProgramId, ataProgramId,
                    mintAddress, Optional.empty(), Optional.empty(), Optional.empty(),
                    destinationOwner, destinationAta, ataExisted, decimals, amount,
                    preMintSupply, preDestinationBalance, feePayer, mintAuthority,
                    policyVersion, maximumFeeLamports, recentBlockhash,
                    lastValidBlockHeight, unsignedTransaction, messageSha256,
                    instructionSha256);
        }

        Draft {
            replacementParentId = Objects.requireNonNull(
                    replacementParentId, "replacementParentId");
            Objects.requireNonNull(effectKind, "effectKind");
            redemptionRegistryVersion = Objects.requireNonNull(
                    redemptionRegistryVersion, "redemptionRegistryVersion");
            sourceOwner = Objects.requireNonNull(sourceOwner, "sourceOwner");
            sourceAta = Objects.requireNonNull(sourceAta, "sourceAta");
            preSourceBalance = Objects.requireNonNull(
                    preSourceBalance, "preSourceBalance");
            unsignedTransaction = Objects.requireNonNull(
                    unsignedTransaction, "unsignedTransaction").clone();
        }

        @Override public byte[] unsignedTransaction() { return unsignedTransaction.clone(); }
    }

    record AttemptRow(
            OperationId operationId,
            AttemptId attemptId,
            UUID deliveryId,
            UUID nativeAttemptId,
            Optional<UUID> replacementParentId,
            int replacementSequence,
            EffectKind effectKind,
            Optional<UUID> redemptionCorrelationId,
            Optional<String> redemptionRegistryVersion,
            String clusterIdentity,
            String routeSnapshotRef,
            String tokenProgramId,
            String ataProgramId,
            String mintAddress,
            Optional<String> sourceOwner,
            Optional<String> sourceAta,
            Optional<BigInteger> preSourceBalance,
            String destinationOwner,
            String destinationAta,
            boolean ataExisted,
            int decimals,
            BigInteger amount,
            BigInteger preMintSupply,
            BigInteger preDestinationBalance,
            SignerContext feePayer,
            SignerContext mintAuthority,
            String policyVersion,
            BigInteger maximumFeeLamports,
            String recentBlockhash,
            long lastValidBlockHeight,
            byte[] unsignedTransaction,
            String messageSha256,
            String instructionSha256,
            String transactionSignature,
            AttemptStatus status,
            long submitFence,
            String submissionCode,
            long version) {

        AttemptRow {
            redemptionCorrelationId = Objects.requireNonNull(
                    redemptionCorrelationId, "redemptionCorrelationId");
            redemptionRegistryVersion = Objects.requireNonNull(
                    redemptionRegistryVersion, "redemptionRegistryVersion");
            unsignedTransaction = unsignedTransaction.clone();
        }

        @Override public byte[] unsignedTransaction() { return unsignedTransaction.clone(); }

        boolean matches(Draft draft) {
            return deliveryId.equals(draft.deliveryId())
                    && nativeAttemptId.equals(draft.nativeAttemptId())
                    && replacementParentId.equals(draft.replacementParentId())
                    && replacementSequence == draft.replacementSequence()
                    && effectKind == draft.effectKind()
                    && (effectKind == EffectKind.BURN
                        ? redemptionCorrelationId.isPresent()
                            && redemptionRegistryVersion.equals(
                                    draft.redemptionRegistryVersion())
                        : redemptionCorrelationId.isEmpty()
                            && redemptionRegistryVersion.isEmpty())
                    && clusterIdentity.equals(draft.clusterIdentity())
                    && routeSnapshotRef.equals(draft.routeSnapshotRef())
                    && tokenProgramId.equals(draft.tokenProgramId())
                    && ataProgramId.equals(draft.ataProgramId())
                    && mintAddress.equals(draft.mintAddress())
                    && sourceOwner.equals(draft.sourceOwner())
                    && sourceAta.equals(draft.sourceAta())
                    && preSourceBalance.equals(draft.preSourceBalance())
                    && destinationOwner.equals(draft.destinationOwner())
                    && destinationAta.equals(draft.destinationAta())
                    && ataExisted == draft.ataExisted()
                    && decimals == draft.decimals()
                    && amount.equals(draft.amount())
                    && preMintSupply.equals(draft.preMintSupply())
                    && preDestinationBalance.equals(draft.preDestinationBalance())
                    && feePayer.equals(draft.feePayer())
                    && mintAuthority.equals(draft.mintAuthority())
                    && policyVersion.equals(draft.policyVersion())
                    && maximumFeeLamports.equals(draft.maximumFeeLamports())
                    && recentBlockhash.equals(draft.recentBlockhash())
                    && lastValidBlockHeight == draft.lastValidBlockHeight()
                    && java.util.Arrays.equals(unsignedTransaction, draft.unsignedTransaction())
                    && messageSha256.equals(draft.messageSha256())
                    && instructionSha256.equals(draft.instructionSha256());
        }
    }

    record SignerContext(
            KeyAlias keyAlias,
            SigningRequest.KeyRole keyRole,
            String keyVersion,
            String publicKey) {
    }

    record SignatureDraft(
            int order,
            SigningRequest.KeyRole keyRole,
            KeyAlias keyAlias,
            String keyVersion,
            String publicKey,
            byte[] bytes,
            String sha256,
            String encoding) {

        SignatureDraft {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }
    }

    record SignatureRow(
            int order,
            SigningRequest.KeyRole keyRole,
            KeyAlias keyAlias,
            String keyVersion,
            String publicKey,
            byte[] bytes,
            String sha256,
            String encoding) {

        SignatureRow {
            bytes = bytes.clone();
        }

        @Override public byte[] bytes() { return bytes.clone(); }

        boolean matches(SignatureDraft draft) {
            return order == draft.order() && keyRole == draft.keyRole()
                    && keyAlias.equals(draft.keyAlias())
                    && keyVersion.equals(draft.keyVersion())
                    && publicKey.equals(draft.publicKey())
                    && java.util.Arrays.equals(bytes, draft.bytes())
                    && sha256.equals(draft.sha256())
                    && encoding.equals(draft.encoding());
        }
    }

    record SubmissionClaim(AttemptRow attempt, boolean claimed) {
    }

    record ObservationDraft(
            ObservationStatus status,
            String commitment,
            Optional<Long> slot,
            Optional<Long> blockTime,
            Optional<String> errorCode,
            boolean expectedInstructions,
            Optional<BigInteger> observedSupply,
            Optional<BigInteger> observedBalance,
            Optional<BigInteger> observedSourceBalance,
            Optional<BigInteger> transactionPreSourceBalance,
            Optional<BigInteger> transactionPostSourceBalance,
            Optional<BigInteger> transactionPreDestinationBalance,
            Optional<BigInteger> transactionPostDestinationBalance,
            Optional<BigInteger> mintDelta,
            Optional<BigInteger> destinationDelta,
            Optional<BigInteger> sourceDelta,
            String evidenceRef) {

        ObservationDraft(
                ObservationStatus status, String commitment, Optional<Long> slot,
                Optional<Long> blockTime, Optional<String> errorCode,
                boolean expectedInstructions, Optional<BigInteger> observedSupply,
                Optional<BigInteger> observedBalance, Optional<BigInteger> mintDelta,
                Optional<BigInteger> destinationDelta, String evidenceRef) {
            this(status, commitment, slot, blockTime, errorCode,
                    expectedInstructions, observedSupply, observedBalance,
                    Optional.empty(), Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), mintDelta, destinationDelta,
                    Optional.empty(),
                    evidenceRef);
        }

        ObservationDraft(
                ObservationStatus status, String commitment, Optional<Long> slot,
                Optional<Long> blockTime, Optional<String> errorCode,
                boolean expectedInstructions, Optional<BigInteger> observedSupply,
                Optional<BigInteger> observedBalance,
                Optional<BigInteger> observedSourceBalance,
                Optional<BigInteger> mintDelta,
                Optional<BigInteger> destinationDelta,
                Optional<BigInteger> sourceDelta,
                String evidenceRef) {
            this(status, commitment, slot, blockTime, errorCode,
                    expectedInstructions, observedSupply, observedBalance,
                    observedSourceBalance, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), mintDelta, destinationDelta,
                    sourceDelta, evidenceRef);
        }
    }

    record CustodyEvidence(
            UUID correlationId,
            String adminOwner,
            String adminKeyAlias,
            String adminRegistryVersion,
            String adminKeyVersion,
            String mintAddress,
            BigInteger quantity,
            String policyVersion,
            String transactionSignature,
            long finalizedSlot,
            String evidenceRef,
            boolean consume) {

        boolean matches(Draft burn) {
            return burn.effectKind() == EffectKind.BURN
                    && adminOwner.equals(burn.sourceOwner().orElseThrow())
                    && adminOwner.equals(burn.destinationOwner())
                    && adminKeyAlias.equals(burn.mintAuthority().keyAlias().value())
                    && adminKeyVersion.equals(burn.mintAuthority().keyVersion())
                    && burn.redemptionRegistryVersion().equals(
                            Optional.of(adminRegistryVersion))
                    && mintAddress.equals(burn.mintAddress())
                    && quantity.equals(burn.amount())
                    && policyVersion.equals(burn.policyVersion())
                    && burn.sourceAta().equals(Optional.of(burn.destinationAta()))
                    && burn.preSourceBalance().equals(
                            Optional.of(burn.preDestinationBalance()));
        }
    }

    enum EffectKind {
        MINT,
        TRANSFER,
        BURN
    }

    enum AttemptStatus {
        PREPARED,
        PARTIALLY_SIGNED,
        SIGNED,
        SUBMISSION_STARTED,
        ACCEPTED,
        AMBIGUOUS,
        REJECTED,
        EXPIRED,
        CONFIRMED,
        REVERTED,
        MISMATCHED
    }

    enum ObservationStatus {
        ABSENT_OR_PENDING,
        CONFIRMED,
        REVERTED,
        MISMATCHED,
        EXPIRED
    }
}
