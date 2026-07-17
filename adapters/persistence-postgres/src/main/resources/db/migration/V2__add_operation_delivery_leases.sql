ALTER TABLE operation_outbox
    DROP CONSTRAINT ck_operation_outbox_status;

ALTER TABLE operation_outbox
    ADD COLUMN delivery_attempt_count INTEGER NOT NULL DEFAULT 0,
    ADD COLUMN lease_id UUID,
    ADD COLUMN worker_id VARCHAR(128),
    ADD COLUMN lease_expires_at TIMESTAMPTZ,
    ADD COLUMN last_outcome VARCHAR(32),
    ADD COLUMN manual_review_reason VARCHAR(32),
    ADD COLUMN last_failure_code VARCHAR(128),
    ADD COLUMN delivered_at TIMESTAMPTZ,
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE operation_outbox
SET updated_at = created_at;

ALTER TABLE operation_outbox
    ALTER COLUMN updated_at SET NOT NULL,
    ADD CONSTRAINT ck_operation_outbox_status
        CHECK (status IN ('PENDING', 'IN_PROGRESS', 'DELIVERED', 'MANUAL_REVIEW')),
    ADD CONSTRAINT ck_operation_outbox_delivery_attempt_count
        CHECK (delivery_attempt_count >= 0),
    ADD CONSTRAINT ck_operation_outbox_worker_id
        CHECK (worker_id IS NULL OR worker_id ~ '^[!-~]{1,128}$'),
    ADD CONSTRAINT ck_operation_outbox_outcome
        CHECK (last_outcome IS NULL OR last_outcome IN (
            'DELIVERED', 'DUPLICATE', 'RETRYABLE_NO_EFFECT',
            'TERMINAL_NO_EFFECT', 'AMBIGUOUS_ACKNOWLEDGEMENT')),
    ADD CONSTRAINT ck_operation_outbox_failure_code
        CHECK (last_failure_code IS NULL
            OR last_failure_code ~ '^[a-z0-9][a-z0-9.-]{0,127}$'),
    ADD CONSTRAINT ck_operation_outbox_manual_review
        CHECK ((manual_review_reason IS NULL AND status <> 'MANUAL_REVIEW')
            OR (status = 'MANUAL_REVIEW'
                AND ((manual_review_reason = 'TERMINAL_NO_EFFECT'
                        AND last_outcome = 'TERMINAL_NO_EFFECT')
                    OR (manual_review_reason = 'ATTEMPTS_EXHAUSTED'
                        AND last_outcome IN (
                            'RETRYABLE_NO_EFFECT',
                            'AMBIGUOUS_ACKNOWLEDGEMENT'))))),
    ADD CONSTRAINT ck_operation_outbox_lease_shape
        CHECK ((status = 'IN_PROGRESS'
                AND lease_id IS NOT NULL
                AND worker_id IS NOT NULL
                AND lease_expires_at IS NOT NULL)
            OR (status <> 'IN_PROGRESS'
                AND lease_id IS NULL
                AND worker_id IS NULL
                AND lease_expires_at IS NULL)),
    ADD CONSTRAINT ck_operation_outbox_delivery_shape
        CHECK ((status = 'DELIVERED' AND delivered_at IS NOT NULL)
            OR (status <> 'DELIVERED' AND delivered_at IS NULL)),
    ADD CONSTRAINT ck_operation_outbox_updated_time
        CHECK (updated_at >= created_at);

DROP INDEX idx_operation_outbox_pending;

CREATE INDEX idx_operation_outbox_eligible
    ON operation_outbox (
        status, available_at, lease_expires_at, operation_id, created_at, event_id);

CREATE TABLE operation_delivery_attempt (
    event_id UUID NOT NULL REFERENCES operation_outbox (event_id),
    attempt_number INTEGER NOT NULL,
    lease_id UUID NOT NULL UNIQUE,
    worker_id VARCHAR(128) NOT NULL,
    claimed_at TIMESTAMPTZ NOT NULL,
    lease_expires_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(32),
    manual_review_reason VARCHAR(32),
    completed_at TIMESTAMPTZ,
    next_available_at TIMESTAMPTZ,
    failure_code VARCHAR(128),
    PRIMARY KEY (event_id, attempt_number),
    CONSTRAINT ck_operation_delivery_attempt_number
        CHECK (attempt_number > 0),
    CONSTRAINT ck_operation_delivery_worker_id
        CHECK (worker_id ~ '^[!-~]{1,128}$'),
    CONSTRAINT ck_operation_delivery_lease_time
        CHECK (lease_expires_at > claimed_at),
    CONSTRAINT ck_operation_delivery_outcome
        CHECK (outcome IS NULL OR outcome IN (
            'DELIVERED', 'DUPLICATE', 'RETRYABLE_NO_EFFECT',
            'TERMINAL_NO_EFFECT', 'AMBIGUOUS_ACKNOWLEDGEMENT',
            'LEASE_EXPIRED')),
    CONSTRAINT ck_operation_delivery_manual_review
        CHECK (manual_review_reason IS NULL
            OR (manual_review_reason = 'TERMINAL_NO_EFFECT'
                AND outcome = 'TERMINAL_NO_EFFECT')
            OR (manual_review_reason = 'ATTEMPTS_EXHAUSTED'
                AND outcome IN (
                    'RETRYABLE_NO_EFFECT',
                    'AMBIGUOUS_ACKNOWLEDGEMENT'))),
    CONSTRAINT ck_operation_delivery_completion
        CHECK ((outcome IS NULL AND completed_at IS NULL
                AND next_available_at IS NULL AND failure_code IS NULL)
            OR (outcome IS NOT NULL AND completed_at IS NOT NULL)),
    CONSTRAINT ck_operation_delivery_next_available
        CHECK (next_available_at IS NULL OR next_available_at >= completed_at),
    CONSTRAINT ck_operation_delivery_failure_code
        CHECK (failure_code IS NULL
            OR failure_code ~ '^[a-z0-9][a-z0-9.-]{0,127}$')
);

CREATE INDEX idx_operation_delivery_attempt_event
    ON operation_delivery_attempt (event_id, attempt_number DESC);
