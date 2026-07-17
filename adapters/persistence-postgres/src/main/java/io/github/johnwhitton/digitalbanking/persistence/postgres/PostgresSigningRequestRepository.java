package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

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
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferEffect;
import io.github.johnwhitton.digitalbanking.domain.transfer.TransferId;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit PostgreSQL mapping for durable signing context and redacted evidence. */
public final class PostgresSigningRequestRepository implements SigningRequestRepository {

    private final JdbcClient jdbc;
    private final TransactionTemplate transactions;

    public PostgresSigningRequestRepository(DataSource dataSource) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    @Override
    public Acceptance accept(SigningRequest proposed) {
        Objects.requireNonNull(proposed, "proposed");
        Acceptance accepted = transactions.execute(status -> acceptInTransaction(proposed));
        return Objects.requireNonNull(accepted, "acceptance transaction result");
    }

    @Override
    public Optional<SigningRequest> findById(SigningRequestId requestId) {
        Objects.requireNonNull(requestId, "requestId");
        Optional<SigningRequest> found = transactions.execute(
                status -> findByIdInTransaction(requestId, false));
        return Objects.requireNonNull(found, "find transaction result");
    }

    private Optional<SigningRequest> findByIdInTransaction(
            SigningRequestId requestId, boolean exclusiveLock) {
        String lockClause = exclusiveLock ? " FOR UPDATE" : " FOR SHARE";
        return jdbc.sql("SELECT * FROM signing_request WHERE request_id = :requestId"
                        + lockClause)
                .param("requestId", requestId.value())
                .query(this::mapSeed).optional().map(this::hydrate);
    }

    @Override
    public void save(SigningRequest request, long expectedVersion) {
        Objects.requireNonNull(request, "request");
        transactions.executeWithoutResult(status -> saveInTransaction(request, expectedVersion));
    }

    private Acceptance acceptInTransaction(SigningRequest proposed) {
        if (proposed.status() != SigningRequest.Status.REQUESTED
                || proposed.version() != 0
                || !proposed.attempts().isEmpty()
                || proposed.transitions().size() != 1) {
            throw new IllegalArgumentException("only a new requested signing aggregate is accepted");
        }
        int claimed = insertRequest(proposed);
        if (claimed == 0) {
            SigningRequest winner = findByIdInTransaction(proposed.requestId(), false)
                    .orElseThrow(() ->
                    new IllegalStateException("signing request winner was not visible"));
            if (!sameReplayContext(winner, proposed)) {
                throw new SigningRequestConflictException(winner);
            }
            return new Acceptance(winner, true);
        }
        insertApprovalEvidence(proposed);
        insertTransition(proposed.requestId(), proposed.transitions().getFirst());
        return new Acceptance(proposed, false);
    }

    private void saveInTransaction(SigningRequest request, long expectedVersion) {
        SigningRequest current = findByIdInTransaction(request.requestId(), true)
                .orElseThrow(() ->
                        new IllegalStateException("signing request was not found"));
        if (current.version() != expectedVersion || request.version() != expectedVersion + 1
                || !sameImmutableContext(current, request)
                || request.transitions().size() != current.transitions().size() + 1
                || !request.transitions().subList(0, current.transitions().size())
                        .equals(current.transitions())) {
            throw new IllegalStateException("signing request optimistic history conflict");
        }
        AttemptChange attemptChange = validateAttemptChange(current, request);
        int updated = jdbc.sql("""
                        UPDATE signing_request
                        SET signing_status = :status,
                            aggregate_version = :nextVersion,
                            updated_at = :updatedAt
                        WHERE request_id = :requestId
                          AND aggregate_version = :expectedVersion
                        """)
                .param("status", request.status().name())
                .param("nextVersion", request.version())
                .param("updatedAt", utc(request.transitions().getLast().occurredAt()))
                .param("requestId", request.requestId().value())
                .param("expectedVersion", expectedVersion)
                .update();
        requireOne(updated, "signing request optimistic update");
        persistAttemptChange(request.requestId(), attemptChange);
        insertTransition(request.requestId(), request.transitions().getLast());
    }

    private int insertRequest(SigningRequest request) {
        SigningRequest.Correlation correlation = request.correlation();
        SigningRequest.PayloadIdentity payload = request.payloadIdentity();
        SigningRequest.KeyContext key = request.keyContext();
        SigningRequest.AuthorityContext authority = request.authorityContext();
        AssetUnit unit = authority.quantity().unit();
        SigningRequest.Lineage lineage = request.lineage().orElse(null);
        return jdbc.sql("""
                        INSERT INTO signing_request (
                            request_id, intent_canonicalization_version, intent_digest,
                            request_canonicalization_version, request_digest,
                            operation_id, operation_attempt_id, transfer_id, effect_id,
                            predecessor_signing_request_id, predecessor_signing_attempt_id,
                            lineage_evidence_ref, action, settlement_network,
                            asset_id, unit_id, unit_version, unit_scale, unit_max_atomic,
                            quantity_atomic, source_reference, destination_reference,
                            native_action_identity, lifetime_context_digest, fee_limit,
                            native_constraint_digest, payload_mode, algorithm, payload_sha256,
                            payload_length, payload_encoding, key_alias, registry_version,
                            key_version, key_role, key_status, allowed_roles,
                            allowed_algorithms, allowed_networks, key_valid_from,
                            key_expires_at, policy_version, issued_at, expires_at,
                            signing_status, aggregate_version, created_at, updated_at)
                        VALUES (
                            :requestId, :intentVersion, :intentDigest,
                            :requestVersion, :requestDigest,
                            :operationId, :operationAttemptId, :transferId, :effectId,
                            :predecessorRequestId, :predecessorAttemptId,
                            :lineageEvidence, :action, :network,
                            :assetId, :unitId, :unitVersion, :unitScale, :unitMaxAtomic,
                            :quantityAtomic, :sourceReference, :destinationReference,
                            :nativeActionIdentity, :lifetimeDigest, :feeLimit,
                            :constraintDigest, :payloadMode, :algorithm, :payloadDigest,
                            :payloadLength, :payloadEncoding, :keyAlias, :registryVersion,
                            :keyVersion, :keyRole, :keyStatus, :allowedRoles,
                            :allowedAlgorithms, :allowedNetworks, :keyValidFrom,
                            :keyExpiresAt, :policyVersion, :issuedAt, :expiresAt,
                            :status, :aggregateVersion, :createdAt, :createdAt)
                        ON CONFLICT (request_id) DO NOTHING
                        """)
                .param("requestId", request.requestId().value())
                .param("intentVersion", request.intentCanonicalizationVersion())
                .param("intentDigest", request.intentDigest())
                .param("requestVersion", request.requestCanonicalizationVersion())
                .param("requestDigest", request.requestDigest())
                .param("operationId", correlation.operationId().value())
                .param("operationAttemptId", correlation.operationAttemptId().value())
                .param("transferId", correlation.transferId().map(TransferId::value).orElse(null))
                .param("effectId", correlation.effectId().map(TransferEffect.Id::value).orElse(null))
                .param("predecessorRequestId", lineage == null ? null : lineage.requestId().value())
                .param("predecessorAttemptId", lineage == null ? null : lineage.attemptId().value())
                .param("lineageEvidence", lineage == null
                        ? null : lineage.authorizationEvidence().value())
                .param("action", authority.action().name())
                .param("network", authority.network().name())
                .param("assetId", unit.assetId())
                .param("unitId", unit.unitId())
                .param("unitVersion", unit.version())
                .param("unitScale", unit.scale())
                .param("unitMaxAtomic", unit.maxAtomicUnits())
                .param("quantityAtomic", authority.quantity().atomicUnits())
                .param("sourceReference", authority.sourceReference())
                .param("destinationReference", authority.destinationReference())
                .param("nativeActionIdentity", authority.nativeActionIdentity())
                .param("lifetimeDigest", authority.lifetimeContextDigest())
                .param("feeLimit", authority.feeLimit())
                .param("constraintDigest", authority.nativeConstraintDigest())
                .param("payloadMode", payload.mode().name())
                .param("algorithm", payload.algorithm().name())
                .param("payloadDigest", payload.sha256())
                .param("payloadLength", payload.length())
                .param("payloadEncoding", payload.encoding().name())
                .param("keyAlias", key.alias().value())
                .param("registryVersion", key.registryVersion())
                .param("keyVersion", key.keyVersion().orElse(null))
                .param("keyRole", key.role().name())
                .param("keyStatus", key.status().name())
                .param("allowedRoles", enumCsv(key.allowedRoles()))
                .param("allowedAlgorithms", enumCsv(key.allowedAlgorithms()))
                .param("allowedNetworks", enumCsv(key.allowedNetworks()))
                .param("keyValidFrom", utc(key.validFrom()))
                .param("keyExpiresAt", key.expiresAt().map(PostgresSigningRequestRepository::utc)
                        .orElse(null))
                .param("policyVersion", authority.policyVersion())
                .param("issuedAt", utc(authority.issuedAt()))
                .param("expiresAt", utc(authority.expiresAt()))
                .param("status", request.status().name())
                .param("aggregateVersion", request.version())
                .param("createdAt", utc(request.createdAt()))
                .update();
    }

    private void insertApprovalEvidence(SigningRequest request) {
        List<EvidenceRef> evidence = request.authorityContext().approvalEvidence();
        for (int index = 0; index < evidence.size(); index++) {
            jdbc.sql("""
                            INSERT INTO signing_request_approval_evidence (
                                request_id, evidence_order, evidence_ref)
                            VALUES (:requestId, :evidenceOrder, :evidenceRef)
                            """)
                    .param("requestId", request.requestId().value())
                    .param("evidenceOrder", index)
                    .param("evidenceRef", evidence.get(index).value())
                    .update();
        }
    }

    private AttemptChange validateAttemptChange(
            SigningRequest current, SigningRequest changed) {
        List<SigningRequest.Attempt> before = current.attempts();
        List<SigningRequest.Attempt> after = changed.attempts();
        if (after.size() == before.size()) {
            if (after.equals(before)) {
                return AttemptChange.none();
            }
            if (before.isEmpty()
                    || !after.subList(0, after.size() - 1)
                            .equals(before.subList(0, before.size() - 1))
                    || !after.getLast().attemptId().equals(before.getLast().attemptId())
                    || after.getLast().evidence().size() != before.getLast().evidence().size() + 1) {
                throw new IllegalStateException("signing attempt history was rewritten");
            }
            return AttemptChange.updated(before.getLast(), after.getLast());
        }
        if (after.size() == before.size() + 1
                && after.subList(0, before.size()).equals(before)) {
            return AttemptChange.appended(after.getLast());
        }
        throw new IllegalStateException("signing attempt history was rewritten");
    }

    private void persistAttemptChange(SigningRequestId requestId, AttemptChange change) {
        if (change.appended().isPresent()) {
            SigningRequest.Attempt attempt = change.appended().orElseThrow();
            int order = jdbc.sql("""
                            SELECT count(*) FROM signing_attempt WHERE request_id = :requestId
                            """).param("requestId", requestId.value()).query(Integer.class).single();
            jdbc.sql("""
                            INSERT INTO signing_attempt (
                                request_id, attempt_order, attempt_id, predecessor_attempt_id,
                                provider_request_id, attempt_status, created_at, updated_at,
                                signature_sha256, signature_length, signature_encoding,
                                evidence_origin, safe_failure_code)
                            VALUES (
                                :requestId, :attemptOrder, :attemptId, :predecessorAttemptId,
                                :providerRequestId, :status, :createdAt, :updatedAt,
                                :signatureDigest, :signatureLength, :signatureEncoding,
                                :evidenceOrigin, :safeFailureCode)
                            """)
                    .param("requestId", requestId.value())
                    .param("attemptOrder", order)
                    .param("attemptId", attempt.attemptId().value())
                    .param("predecessorAttemptId", attempt.predecessor()
                            .map(SigningAttemptId::value).orElse(null))
                    .param("providerRequestId", attempt.providerRequestId().value())
                    .param("status", attempt.status().name())
                    .param("createdAt", utc(attempt.createdAt()))
                    .param("updatedAt", utc(attempt.updatedAt()))
                    .param("signatureDigest", attempt.signatureEvidence()
                            .map(SigningRequest.SignatureEvidence::sha256).orElse(null))
                    .param("signatureLength", attempt.signatureEvidence()
                            .map(SigningRequest.SignatureEvidence::length).orElse(null))
                    .param("signatureEncoding", attempt.signatureEvidence()
                            .map(SigningRequest.SignatureEvidence::encoding).orElse(null))
                    .param("evidenceOrigin", attempt.signatureEvidence()
                            .map(value -> value.origin().name()).orElse(null))
                    .param("safeFailureCode", attempt.safeFailureCode().orElse(null))
                    .update();
            insertAttemptEvidence(requestId, order, attempt.evidence(), 0);
        }
        if (change.updated().isPresent()) {
            SigningRequest.Attempt before = change.updated().orElseThrow().before();
            SigningRequest.Attempt after = change.updated().orElseThrow().after();
            int order = findAttemptOrder(requestId, after.attemptId());
            int updated = jdbc.sql("""
                            UPDATE signing_attempt
                            SET attempt_status = :status,
                                updated_at = :updatedAt,
                                signature_sha256 = :signatureDigest,
                                signature_length = :signatureLength,
                                signature_encoding = :signatureEncoding,
                                evidence_origin = :evidenceOrigin,
                                safe_failure_code = :safeFailureCode
                            WHERE request_id = :requestId
                              AND attempt_order = :attemptOrder
                              AND attempt_status = :expectedStatus
                              AND updated_at = :expectedUpdatedAt
                            """)
                    .param("status", after.status().name())
                    .param("updatedAt", utc(after.updatedAt()))
                    .param("signatureDigest", after.signatureEvidence()
                            .map(SigningRequest.SignatureEvidence::sha256).orElse(null))
                    .param("signatureLength", after.signatureEvidence()
                            .map(SigningRequest.SignatureEvidence::length).orElse(null))
                    .param("signatureEncoding", after.signatureEvidence()
                            .map(SigningRequest.SignatureEvidence::encoding).orElse(null))
                    .param("evidenceOrigin", after.signatureEvidence()
                            .map(value -> value.origin().name()).orElse(null))
                    .param("safeFailureCode", after.safeFailureCode().orElse(null))
                    .param("requestId", requestId.value())
                    .param("attemptOrder", order)
                    .param("expectedStatus", before.status().name())
                    .param("expectedUpdatedAt", utc(before.updatedAt()))
                    .update();
            requireOne(updated, "signing attempt optimistic update");
            insertAttemptEvidence(
                    requestId, order, after.evidence(), before.evidence().size());
        }
    }

    private int findAttemptOrder(SigningRequestId requestId, SigningAttemptId attemptId) {
        return jdbc.sql("""
                        SELECT attempt_order FROM signing_attempt
                        WHERE request_id = :requestId AND attempt_id = :attemptId
                        """)
                .param("requestId", requestId.value())
                .param("attemptId", attemptId.value())
                .query(Integer.class).single();
    }

    private void insertAttemptEvidence(
            SigningRequestId requestId,
            int attemptOrder,
            List<EvidenceRef> evidence,
            int start) {
        for (int index = start; index < evidence.size(); index++) {
            jdbc.sql("""
                            INSERT INTO signing_attempt_evidence (
                                request_id, attempt_order, evidence_order, evidence_ref)
                            VALUES (:requestId, :attemptOrder, :evidenceOrder, :evidenceRef)
                            """)
                    .param("requestId", requestId.value())
                    .param("attemptOrder", attemptOrder)
                    .param("evidenceOrder", index)
                    .param("evidenceRef", evidence.get(index).value())
                    .update();
        }
    }

    private void insertTransition(
            SigningRequestId requestId, SigningRequest.Transition transition) {
        jdbc.sql("""
                        INSERT INTO signing_transition (
                            request_id, aggregate_version, from_status, to_status,
                            reason, occurred_at, evidence_ref)
                        VALUES (
                            :requestId, :aggregateVersion, :fromStatus, :toStatus,
                            :reason, :occurredAt, :evidenceRef)
                        """)
                .param("requestId", requestId.value())
                .param("aggregateVersion", transition.version())
                .param("fromStatus", transition.from().map(Enum::name).orElse(null))
                .param("toStatus", transition.to().name())
                .param("reason", transition.reason())
                .param("occurredAt", utc(transition.occurredAt()))
                .param("evidenceRef", transition.evidence().value())
                .update();
    }

    private Seed mapSeed(ResultSet row, int rowNumber) throws SQLException {
        UUID transferId = row.getObject("transfer_id", UUID.class);
        UUID effectId = row.getObject("effect_id", UUID.class);
        UUID predecessorRequestId = row.getObject(
                "predecessor_signing_request_id", UUID.class);
        UUID predecessorAttemptId = row.getObject(
                "predecessor_signing_attempt_id", UUID.class);
        AssetUnit unit = new AssetUnit(
                row.getString("asset_id"), row.getString("unit_id"),
                row.getInt("unit_version"), row.getInt("unit_scale"),
                exactInteger(row, "unit_max_atomic"));
        SigningRequest.Correlation correlation = new SigningRequest.Correlation(
                new OperationId(row.getObject("operation_id", UUID.class)),
                new AttemptId(row.getObject("operation_attempt_id", UUID.class)),
                Optional.ofNullable(transferId).map(TransferId::new),
                Optional.ofNullable(effectId).map(TransferEffect.Id::new));
        Optional<SigningRequest.Lineage> lineage = predecessorRequestId == null
                ? Optional.empty() : Optional.of(new SigningRequest.Lineage(
                        new SigningRequestId(predecessorRequestId),
                        new SigningAttemptId(predecessorAttemptId),
                        new EvidenceRef(row.getString("lineage_evidence_ref"))));
        SigningRequest.PayloadIdentity payload = new SigningRequest.PayloadIdentity(
                SigningRequest.Mode.valueOf(row.getString("payload_mode")),
                SigningRequest.Algorithm.valueOf(row.getString("algorithm")),
                row.getString("payload_sha256"), row.getInt("payload_length"),
                SigningRequest.PayloadEncoding.valueOf(row.getString("payload_encoding")));
        SigningRequest.KeyContext key = new SigningRequest.KeyContext(
                new KeyAlias(row.getString("key_alias")), row.getString("registry_version"),
                Optional.ofNullable(row.getString("key_version")),
                SigningRequest.KeyRole.valueOf(row.getString("key_role")),
                SigningRequest.Algorithm.valueOf(row.getString("algorithm")),
                SettlementNetwork.valueOf(row.getString("settlement_network")),
                SigningRequest.KeyStatus.valueOf(row.getString("key_status")),
                keyRoles(row.getString("allowed_roles")),
                algorithms(row.getString("allowed_algorithms")),
                networks(row.getString("allowed_networks")),
                instant(row, "key_valid_from"),
                optionalInstant(row, "key_expires_at"));
        return new Seed(
                new SigningRequestId(row.getObject("request_id", UUID.class)), correlation,
                lineage, payload, key,
                SigningRequest.Action.valueOf(row.getString("action")),
                TokenQuantity.ofAtomic(exactInteger(row, "quantity_atomic"), unit),
                row.getString("source_reference"), row.getString("destination_reference"),
                row.getString("native_action_identity"),
                row.getString("lifetime_context_digest"), row.getString("fee_limit"),
                row.getString("native_constraint_digest"), row.getString("policy_version"),
                instant(row, "issued_at"), instant(row, "expires_at"),
                row.getInt("intent_canonicalization_version"), row.getString("intent_digest"),
                row.getInt("request_canonicalization_version"), row.getString("request_digest"),
                SigningRequest.Status.valueOf(row.getString("signing_status")),
                row.getLong("aggregate_version"), instant(row, "created_at"));
    }

    private SigningRequest hydrate(Seed seed) {
        List<EvidenceRef> approvals = jdbc.sql("""
                        SELECT evidence_ref FROM signing_request_approval_evidence
                        WHERE request_id = :requestId ORDER BY evidence_order
                        """).param("requestId", seed.requestId().value())
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
        SigningRequest.AuthorityContext authority = new SigningRequest.AuthorityContext(
                seed.action(), seed.key().network(), seed.quantity(), seed.sourceReference(),
                seed.destinationReference(), seed.nativeActionIdentity(), seed.lifetimeDigest(),
                seed.feeLimit(), seed.constraintDigest(), seed.policyVersion(), approvals,
                seed.issuedAt(), seed.expiresAt());
        return SigningRequest.rehydrate(
                seed.requestId(), seed.correlation(), seed.lineage(), seed.payload(), seed.key(),
                authority, seed.intentVersion(), seed.intentDigest(), seed.requestVersion(),
                seed.requestDigest(), seed.status(), seed.aggregateVersion(), seed.createdAt(),
                attempts(seed.requestId()), transitions(seed.requestId()));
    }

    private List<SigningRequest.Attempt> attempts(SigningRequestId requestId) {
        return jdbc.sql("""
                        SELECT * FROM signing_attempt
                        WHERE request_id = :requestId ORDER BY attempt_order
                        """).param("requestId", requestId.value())
                .query((row, rowNumber) -> {
                    int order = row.getInt("attempt_order");
                    UUID predecessor = row.getObject("predecessor_attempt_id", UUID.class);
                    String signatureDigest = row.getString("signature_sha256");
                    Optional<SigningRequest.SignatureEvidence> signature =
                            signatureDigest == null ? Optional.empty() : Optional.of(
                                    new SigningRequest.SignatureEvidence(
                                            signatureDigest, row.getInt("signature_length"),
                                            row.getString("signature_encoding"),
                                            signatureEvidence(requestId, order),
                                            SigningRequest.EvidenceOrigin.valueOf(
                                                    row.getString("evidence_origin"))));
                    return new SigningRequest.Attempt(
                            new SigningAttemptId(row.getObject("attempt_id", UUID.class)),
                            Optional.ofNullable(predecessor).map(SigningAttemptId::new),
                            new ProviderRequestId(row.getString("provider_request_id")),
                            SigningRequest.Status.valueOf(row.getString("attempt_status")),
                            instant(row, "created_at"), instant(row, "updated_at"),
                            attemptEvidence(requestId, order), signature,
                            Optional.ofNullable(row.getString("safe_failure_code")));
                }).list();
    }

    private EvidenceRef signatureEvidence(SigningRequestId requestId, int order) {
        List<EvidenceRef> evidence = attemptEvidence(requestId, order);
        if (evidence.isEmpty()) {
            throw new IllegalStateException("signed attempt has no evidence");
        }
        return evidence.getLast();
    }

    private List<EvidenceRef> attemptEvidence(SigningRequestId requestId, int order) {
        return jdbc.sql("""
                        SELECT evidence_ref FROM signing_attempt_evidence
                        WHERE request_id = :requestId AND attempt_order = :attemptOrder
                        ORDER BY evidence_order
                        """).param("requestId", requestId.value())
                .param("attemptOrder", order)
                .query(String.class).list().stream().map(EvidenceRef::new).toList();
    }

    private List<SigningRequest.Transition> transitions(SigningRequestId requestId) {
        return jdbc.sql("""
                        SELECT aggregate_version, from_status, to_status, reason,
                               occurred_at, evidence_ref
                        FROM signing_transition
                        WHERE request_id = :requestId ORDER BY aggregate_version
                        """).param("requestId", requestId.value())
                .query((row, rowNumber) -> {
                    String from = row.getString("from_status");
                    return new SigningRequest.Transition(
                            row.getLong("aggregate_version"),
                            from == null ? Optional.empty() : Optional.of(
                                    SigningRequest.Status.valueOf(from)),
                            SigningRequest.Status.valueOf(row.getString("to_status")),
                            row.getString("reason"), instant(row, "occurred_at"),
                            new EvidenceRef(row.getString("evidence_ref")));
                }).list();
    }

    private static boolean sameCanonicalIdentity(
            SigningRequest first, SigningRequest second) {
        return first.intentCanonicalizationVersion() == second.intentCanonicalizationVersion()
                && first.intentDigest().equals(second.intentDigest())
                && first.requestCanonicalizationVersion()
                        == second.requestCanonicalizationVersion()
                && first.requestDigest().equals(second.requestDigest());
    }

    private static boolean sameImmutableContext(
            SigningRequest first, SigningRequest second) {
        return sameReplayContext(first, second)
                && first.createdAt().equals(second.createdAt());
    }

    private static boolean sameReplayContext(
            SigningRequest first, SigningRequest second) {
        return first.requestId().equals(second.requestId())
                && sameCanonicalIdentity(first, second)
                && first.correlation().equals(second.correlation())
                && first.lineage().equals(second.lineage())
                && first.payloadIdentity().equals(second.payloadIdentity())
                && first.keyContext().equals(second.keyContext())
                && first.authorityContext().equals(second.authorityContext());
    }

    private static String enumCsv(Set<? extends Enum<?>> values) {
        return values.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }

    private static Set<SigningRequest.KeyRole> keyRoles(String value) {
        return enumSet(value, SigningRequest.KeyRole::valueOf);
    }

    private static Set<SigningRequest.Algorithm> algorithms(String value) {
        return enumSet(value, SigningRequest.Algorithm::valueOf);
    }

    private static Set<SettlementNetwork> networks(String value) {
        return enumSet(value, SettlementNetwork::valueOf);
    }

    private static <T> Set<T> enumSet(
            String value, java.util.function.Function<String, T> parser) {
        if (value.isEmpty()) {
            return Set.of();
        }
        return Arrays.stream(value.split(",")).map(parser).collect(Collectors.toUnmodifiableSet());
    }

    private static java.math.BigInteger exactInteger(ResultSet row, String column)
            throws SQLException {
        return row.getObject(column, BigDecimal.class).toBigIntegerExact();
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        return row.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static Optional<Instant> optionalInstant(ResultSet row, String column)
            throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return Optional.ofNullable(value).map(OffsetDateTime::toInstant);
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "instant").atOffset(ZoneOffset.UTC);
    }

    private static void requireOne(int updated, String action) {
        if (updated != 1) {
            throw new IllegalStateException(action + " did not affect exactly one row");
        }
    }

    private record AttemptChange(
            Optional<SigningRequest.Attempt> appended,
            Optional<UpdatedAttempt> updated) {

        static AttemptChange none() {
            return new AttemptChange(Optional.empty(), Optional.empty());
        }

        static AttemptChange appended(SigningRequest.Attempt attempt) {
            return new AttemptChange(Optional.of(attempt), Optional.empty());
        }

        static AttemptChange updated(
                SigningRequest.Attempt before, SigningRequest.Attempt after) {
            return new AttemptChange(Optional.empty(), Optional.of(new UpdatedAttempt(before, after)));
        }
    }

    private record UpdatedAttempt(
            SigningRequest.Attempt before, SigningRequest.Attempt after) { }

    private record Seed(
            SigningRequestId requestId,
            SigningRequest.Correlation correlation,
            Optional<SigningRequest.Lineage> lineage,
            SigningRequest.PayloadIdentity payload,
            SigningRequest.KeyContext key,
            SigningRequest.Action action,
            TokenQuantity quantity,
            String sourceReference,
            String destinationReference,
            String nativeActionIdentity,
            String lifetimeDigest,
            String feeLimit,
            String constraintDigest,
            String policyVersion,
            Instant issuedAt,
            Instant expiresAt,
            int intentVersion,
            String intentDigest,
            int requestVersion,
            String requestDigest,
            SigningRequest.Status status,
            long aggregateVersion,
            Instant createdAt) { }
}
