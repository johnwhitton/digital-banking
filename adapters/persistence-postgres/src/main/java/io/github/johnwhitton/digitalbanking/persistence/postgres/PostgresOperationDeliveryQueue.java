package io.github.johnwhitton.digitalbanking.persistence.postgres;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import javax.sql.DataSource;

import io.github.johnwhitton.digitalbanking.application.delivery.DeliveryOutcome;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDelivery;
import io.github.johnwhitton.digitalbanking.application.delivery.OperationDeliveryQueue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Explicit PostgreSQL claim, lease, acknowledgement, retry, and recovery adapter. */
public final class PostgresOperationDeliveryQueue implements OperationDeliveryQueue {

    private static final Duration MAX_LEASE = Duration.ofDays(1);
    private static final Duration MIN_LEASE = Duration.ofNanos(1_000);

    private final JdbcClient jdbc;
    private final TransactionTemplate transaction;
    private final Filter filter;

    public PostgresOperationDeliveryQueue(DataSource dataSource) {
        this(dataSource, Filter.ALL);
    }

    private PostgresOperationDeliveryQueue(DataSource dataSource, Filter filter) {
        Objects.requireNonNull(dataSource, "dataSource");
        this.jdbc = JdbcClient.create(dataSource);
        this.filter = Objects.requireNonNull(filter, "filter");
        this.transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.transaction.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Queue view used by the bounded Phase 5A worker; unsupported outbox rows remain untouched. */
    public static PostgresOperationDeliveryQueue mintOnly(DataSource dataSource) {
        return new PostgresOperationDeliveryQueue(dataSource, Filter.MINT);
    }

    /** Queue view used by Phase 5C; all other accepted work remains untouched. */
    public static PostgresOperationDeliveryQueue walletTransfersOnly(DataSource dataSource) {
        return new PostgresOperationDeliveryQueue(dataSource, Filter.WALLET_TRANSFER);
    }

    @Override
    public ClaimBatch claim(
            String workerId, Instant now, Duration leaseDuration, int limit) {
        validateWorker(workerId);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.compareTo(MIN_LEASE) < 0
                || leaseDuration.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException(
                    "leaseDuration must be at least one microsecond and at most one day");
        }
        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("limit must be between 1 and 100");
        }
        Instant claimedAt = canonical(now);
        Instant leaseExpiresAt = canonical(claimedAt.plus(leaseDuration));
        return Objects.requireNonNull(transaction.execute(status ->
                claimInTransaction(workerId, claimedAt, leaseExpiresAt, limit)));
    }

    private ClaimBatch claimInTransaction(
            String workerId, Instant claimedAt, Instant leaseExpiresAt, int limit) {
        List<Candidate> candidates = jdbc.sql("""
                SELECT candidate.event_id,
                       COALESCE(candidate.operation_id, candidate.transfer_id,
                                (to_jsonb(candidate)->>'wallet_transfer_id')::uuid)
                           AS aggregate_id,
                       candidate.event_type, candidate.event_version,
                       candidate.payload_schema_version,
                       candidate.delivery_attempt_count, candidate.lease_id
                FROM operation_outbox candidate
                WHERE ((candidate.status = 'PENDING'
                            AND candidate.available_at <= :now)
                        OR (candidate.status = 'IN_PROGRESS'
                            AND candidate.lease_expires_at <= :now))
                  AND (:filter = 'ALL'
                      OR (:filter = 'MINT'
                          AND candidate.event_type = 'TokenOperationAccepted'
                          AND EXISTS (
                              SELECT 1 FROM token_operation supported_operation
                              WHERE supported_operation.operation_id = candidate.operation_id
                                AND supported_operation.operation_kind = 'MINT'))
                      OR (:filter = 'WALLET_TRANSFER'
                          AND candidate.event_type = 'WalletTransferAccepted'))
                  AND NOT EXISTS (
                      SELECT 1
                      FROM operation_outbox prior
                      WHERE ((prior.operation_id = candidate.operation_id
                                AND candidate.operation_id IS NOT NULL)
                            OR (prior.transfer_id = candidate.transfer_id
                                AND candidate.transfer_id IS NOT NULL)
                            OR ((to_jsonb(prior)->>'wallet_transfer_id')
                                    = (to_jsonb(candidate)->>'wallet_transfer_id')
                                AND (to_jsonb(candidate)->>'wallet_transfer_id') IS NOT NULL))
                        AND (prior.created_at, prior.event_id)
                            < (candidate.created_at, candidate.event_id)
                        AND prior.status IN ('PENDING', 'IN_PROGRESS'))
                ORDER BY CASE
                            WHEN candidate.status = 'PENDING' THEN candidate.available_at
                            ELSE candidate.lease_expires_at
                         END,
                         candidate.created_at,
                         candidate.event_id
                FOR UPDATE OF candidate SKIP LOCKED
                LIMIT :limit
                """)
                .param("now", utc(claimedAt))
                .param("limit", limit)
                .param("filter", filter.name())
                .query((row, rowNumber) -> new Candidate(
                        row.getObject("event_id", UUID.class),
                        row.getObject("aggregate_id", UUID.class),
                        row.getString("event_type"),
                        row.getInt("event_version"),
                        row.getInt("payload_schema_version"),
                        row.getInt("delivery_attempt_count"),
                        row.getObject("lease_id", UUID.class)))
                .list();

        int recovered = 0;
        List<OperationDelivery> deliveries = new ArrayList<>(candidates.size());
        for (Candidate candidate : candidates) {
            if (candidate.currentLeaseId() != null) {
                int expired = jdbc.sql("""
                        UPDATE operation_delivery_attempt
                        SET outcome = 'LEASE_EXPIRED', completed_at = :completedAt
                        WHERE event_id = :eventId
                          AND lease_id = :leaseId
                          AND outcome IS NULL
                        """)
                        .param("completedAt", utc(claimedAt))
                        .param("eventId", candidate.eventId())
                        .param("leaseId", candidate.currentLeaseId())
                        .update();
                requireOne(expired, "expired lease history");
                recovered++;
            }

            UUID leaseId = UUID.randomUUID();
            int attemptNumber = candidate.attemptCount() + 1;
            int claimed = jdbc.sql("""
                    UPDATE operation_outbox
                    SET status = 'IN_PROGRESS',
                        delivery_attempt_count = :attemptNumber,
                        lease_id = :leaseId,
                        worker_id = :workerId,
                        lease_expires_at = :leaseExpiresAt,
                        last_outcome = NULL,
                        manual_review_reason = NULL,
                        last_failure_code = NULL,
                        updated_at = :claimedAt
                    WHERE event_id = :eventId
                    """)
                    .param("attemptNumber", attemptNumber)
                    .param("leaseId", leaseId)
                    .param("workerId", workerId)
                    .param("leaseExpiresAt", utc(leaseExpiresAt))
                    .param("claimedAt", utc(claimedAt))
                    .param("eventId", candidate.eventId())
                    .update();
            requireOne(claimed, "outbox claim");
            jdbc.sql("""
                    INSERT INTO operation_delivery_attempt (
                        event_id, attempt_number, lease_id, worker_id,
                        claimed_at, lease_expires_at)
                    VALUES (
                        :eventId, :attemptNumber, :leaseId, :workerId,
                        :claimedAt, :leaseExpiresAt)
                    """)
                    .param("eventId", candidate.eventId())
                    .param("attemptNumber", attemptNumber)
                    .param("leaseId", leaseId)
                    .param("workerId", workerId)
                    .param("claimedAt", utc(claimedAt))
                    .param("leaseExpiresAt", utc(leaseExpiresAt))
                    .update();
            deliveries.add(new OperationDelivery(
                    candidate.eventId(), candidate.aggregateId(), candidate.eventType(),
                    candidate.eventVersion(), candidate.payloadSchemaVersion(),
                    leaseId, workerId, attemptNumber));
        }
        return new ClaimBatch(deliveries, recovered);
    }

    @Override
    public LeaseUpdateResult acknowledge(
            OperationDelivery delivery, DeliveryOutcome outcome, Instant completedAt) {
        Objects.requireNonNull(delivery, "delivery");
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.classification() != DeliveryOutcome.Classification.DELIVERED
                && outcome.classification() != DeliveryOutcome.Classification.DUPLICATE) {
            throw new IllegalArgumentException("acknowledgement requires a delivered outcome");
        }
        Instant recordedAt = canonical(completedAt);
        return updateLease(
                delivery,
                """
                UPDATE operation_outbox
                SET status = 'DELIVERED', lease_id = NULL, worker_id = NULL,
                    lease_expires_at = NULL, last_outcome = :outcome,
                    manual_review_reason = NULL,
                    last_failure_code = NULL, delivered_at = :recordedAt,
                    updated_at = :recordedAt
                WHERE event_id = :eventId AND status = 'IN_PROGRESS'
                  AND lease_id = :leaseId AND worker_id = :workerId
                  AND lease_expires_at > :recordedAt
                """,
                outcome.classification().name(), null, null, recordedAt, null);
    }

    @Override
    public LeaseUpdateResult reschedule(
            OperationDelivery delivery,
            DeliveryOutcome outcome,
            Instant nextAttemptAt,
            Instant recordedAt) {
        Objects.requireNonNull(outcome, "outcome");
        if (outcome.classification() != DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT
                && outcome.classification()
                    != DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT) {
            throw new IllegalArgumentException("reschedule requires a retryable outcome");
        }
        Instant completedAt = canonical(recordedAt);
        Instant availableAt = canonical(nextAttemptAt);
        if (availableAt.isBefore(completedAt)) {
            throw new IllegalArgumentException("nextAttemptAt must not precede recordedAt");
        }
        return updateLease(
                delivery,
                """
                UPDATE operation_outbox
                SET status = 'PENDING', available_at = :nextAvailableAt,
                    lease_id = NULL, worker_id = NULL, lease_expires_at = NULL,
                    last_outcome = :outcome, manual_review_reason = NULL,
                    last_failure_code = :failureCode,
                    updated_at = :recordedAt
                WHERE event_id = :eventId AND status = 'IN_PROGRESS'
                  AND lease_id = :leaseId AND worker_id = :workerId
                  AND lease_expires_at > :recordedAt
                """,
                outcome.classification().name(), failureCode(outcome),
                null, completedAt, availableAt);
    }

    @Override
    public LeaseUpdateResult moveToManualReview(
            OperationDelivery delivery,
            ManualReviewReason reason,
            DeliveryOutcome outcome,
            Instant recordedAt) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(outcome, "outcome");
        if (reason == ManualReviewReason.TERMINAL_NO_EFFECT
                && outcome.classification() != DeliveryOutcome.Classification.TERMINAL_NO_EFFECT) {
            throw new IllegalArgumentException("terminal review requires terminal no-effect");
        }
        if (reason == ManualReviewReason.ATTEMPTS_EXHAUSTED
                && outcome.classification() != DeliveryOutcome.Classification.RETRYABLE_NO_EFFECT
                && outcome.classification()
                    != DeliveryOutcome.Classification.AMBIGUOUS_ACKNOWLEDGEMENT) {
            throw new IllegalArgumentException("exhaustion requires a retryable outcome");
        }
        return updateLease(
                delivery,
                """
                UPDATE operation_outbox
                SET status = 'MANUAL_REVIEW', lease_id = NULL, worker_id = NULL,
                    lease_expires_at = NULL, last_outcome = :outcome,
                    manual_review_reason = :reviewReason,
                    last_failure_code = :failureCode, updated_at = :recordedAt
                WHERE event_id = :eventId AND status = 'IN_PROGRESS'
                  AND lease_id = :leaseId AND worker_id = :workerId
                  AND lease_expires_at > :recordedAt
                """,
                outcome.classification().name(), failureCode(outcome), reason,
                canonical(recordedAt), null);
    }

    private LeaseUpdateResult updateLease(
            OperationDelivery delivery,
            String outboxSql,
            String persistedOutcome,
            String failureCode,
            ManualReviewReason reviewReason,
            Instant recordedAt,
            Instant nextAvailableAt) {
        Objects.requireNonNull(delivery, "delivery");
        return Objects.requireNonNull(transaction.execute(status -> {
            var statement = jdbc.sql(outboxSql)
                    .param("eventId", delivery.deliveryId())
                    .param("leaseId", delivery.leaseId())
                    .param("workerId", delivery.workerId())
                    .param("outcome", persistedOutcome)
                    .param("recordedAt", utc(recordedAt));
            if (outboxSql.contains(":failureCode")) {
                statement = statement.param("failureCode", failureCode);
            }
            if (outboxSql.contains(":reviewReason")) {
                statement = statement.param("reviewReason", reviewReason.name());
            }
            if (outboxSql.contains(":nextAvailableAt")) {
                statement = statement.param("nextAvailableAt", utc(nextAvailableAt));
            }
            if (statement.update() == 0) {
                return LeaseUpdateResult.STALE_LEASE;
            }
            var history = jdbc.sql("""
                    UPDATE operation_delivery_attempt
                    SET outcome = :outcome,
                        completed_at = :recordedAt,
                        next_available_at = :nextAvailableAt,
                        manual_review_reason = :reviewReason,
                        failure_code = :failureCode
                    WHERE event_id = :eventId AND attempt_number = :attemptNumber
                      AND lease_id = :leaseId AND outcome IS NULL
                    """)
                    .param("outcome", persistedOutcome)
                    .param("recordedAt", utc(recordedAt))
                    .param("nextAvailableAt", utcOrNull(nextAvailableAt))
                    .param("reviewReason", reviewReason == null ? null : reviewReason.name())
                    .param("failureCode", failureCode)
                    .param("eventId", delivery.deliveryId())
                    .param("attemptNumber", delivery.attemptNumber())
                    .param("leaseId", delivery.leaseId());
            requireOne(history.update(), "delivery attempt outcome");
            return LeaseUpdateResult.UPDATED;
        }));
    }

    @Override
    public QueueMeasurements measurements(Instant now) {
        Instant measuredAt = canonical(now);
        return Objects.requireNonNull(transaction.execute(status -> {
            MetricsRow eligible = jdbc.sql("""
                    WITH eligible AS (
                        SELECT CASE
                                 WHEN candidate.status = 'PENDING'
                                   THEN candidate.available_at
                                 ELSE candidate.lease_expires_at
                               END AS eligible_at
                        FROM operation_outbox candidate
                        WHERE ((candidate.status = 'PENDING'
                                    AND candidate.available_at <= :now)
                                OR (candidate.status = 'IN_PROGRESS'
                                    AND candidate.lease_expires_at <= :now))
                          AND (:filter = 'ALL'
                              OR (:filter = 'MINT'
                                  AND candidate.event_type = 'TokenOperationAccepted'
                                  AND EXISTS (
                                      SELECT 1 FROM token_operation supported_operation
                                      WHERE supported_operation.operation_id = candidate.operation_id
                                        AND supported_operation.operation_kind = 'MINT'))
                              OR (:filter = 'WALLET_TRANSFER'
                                  AND candidate.event_type = 'WalletTransferAccepted'))
                          AND NOT EXISTS (
                              SELECT 1 FROM operation_outbox prior
                              WHERE ((prior.operation_id = candidate.operation_id
                                        AND candidate.operation_id IS NOT NULL)
                                    OR (prior.transfer_id = candidate.transfer_id
                                        AND candidate.transfer_id IS NOT NULL)
                                    OR ((to_jsonb(prior)->>'wallet_transfer_id')
                                            = (to_jsonb(candidate)->>'wallet_transfer_id')
                                        AND (to_jsonb(candidate)->>'wallet_transfer_id')
                                            IS NOT NULL))
                                AND (prior.created_at, prior.event_id)
                                    < (candidate.created_at, candidate.event_id)
                                AND prior.status IN ('PENDING', 'IN_PROGRESS')))
                    SELECT count(*) AS eligible_count, min(eligible_at) AS oldest_at
                    FROM eligible
                    """)
                    .param("now", utc(measuredAt))
                    .param("filter", filter.name())
                    .query((row, rowNumber) -> new MetricsRow(
                            row.getLong("eligible_count"),
                            instantOrNull(row.getObject("oldest_at", OffsetDateTime.class))))
                    .single();
            long active = jdbc.sql("""
                    SELECT count(*)
                    FROM operation_outbox candidate
                    WHERE status = 'IN_PROGRESS' AND lease_expires_at > :now
                      AND (:filter = 'ALL'
                          OR (:filter = 'MINT'
                              AND candidate.event_type = 'TokenOperationAccepted'
                              AND EXISTS (
                                  SELECT 1 FROM token_operation supported_operation
                                  WHERE supported_operation.operation_id = candidate.operation_id
                                    AND supported_operation.operation_kind = 'MINT'))
                          OR (:filter = 'WALLET_TRANSFER'
                              AND candidate.event_type = 'WalletTransferAccepted'))
                    """)
                    .param("now", utc(measuredAt))
                    .param("filter", filter.name())
                    .query(Long.class).single();
            Duration oldestAge = eligible.oldestAt() == null
                    ? Duration.ZERO : Duration.between(eligible.oldestAt(), measuredAt);
            return new QueueMeasurements(eligible.eligibleCount(), active, oldestAge);
        }));
    }

    private static String failureCode(DeliveryOutcome outcome) {
        return outcome.safeFailureCode().orElseThrow();
    }

    private static Instant canonical(Instant value) {
        return Objects.requireNonNull(value, "instant").truncatedTo(ChronoUnit.MICROS);
    }

    private static OffsetDateTime utc(Instant value) {
        return Objects.requireNonNull(value, "instant").atOffset(ZoneOffset.UTC);
    }

    private static OffsetDateTime utcOrNull(Instant value) {
        return value == null ? null : utc(value);
    }

    private static Instant instantOrNull(OffsetDateTime value) {
        return value == null ? null : value.toInstant();
    }

    private static void validateWorker(String workerId) {
        if (workerId == null || workerId.isEmpty() || workerId.length() > 128
                || workerId.chars().anyMatch(character -> character < 0x21 || character > 0x7e)) {
            throw new IllegalArgumentException(
                    "workerId must contain 1-128 visible US-ASCII characters");
        }
    }

    private static void requireOne(int updated, String action) {
        if (updated != 1) {
            throw new IllegalStateException(action + " did not affect exactly one row");
        }
    }

    private record Candidate(
            UUID eventId,
            UUID aggregateId,
            String eventType,
            int eventVersion,
            int payloadSchemaVersion,
            int attemptCount,
            UUID currentLeaseId) { }

    private record MetricsRow(long eligibleCount, Instant oldestAt) { }

    private enum Filter {
        ALL,
        MINT,
        WALLET_TRANSFER
    }
}
