CREATE TABLE token_operation (
    operation_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    idempotency_resource VARCHAR(64) NOT NULL,
    operation_kind VARCHAR(8) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    request_contract_version INTEGER NOT NULL,
    canonicalization_version INTEGER NOT NULL,
    command_digest CHAR(64) NOT NULL,
    business_correlation VARCHAR(128) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    unit_max_atomic NUMERIC(512, 0) NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    lifecycle_state VARCHAR(32) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    acceptance_evidence_ref VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_token_operation_tenant
        CHECK (tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_token_operation_participant
        CHECK (participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_token_operation_resource
        CHECK (idempotency_resource = 'TOKEN_OPERATION'),
    CONSTRAINT ck_token_operation_kind
        CHECK (operation_kind IN ('MINT', 'BURN')),
    CONSTRAINT ck_token_operation_key_digest
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_token_operation_contract_version
        CHECK (request_contract_version > 0),
    CONSTRAINT ck_token_operation_canonicalization_version
        CHECK (canonicalization_version > 0),
    CONSTRAINT ck_token_operation_command_digest
        CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_token_operation_correlation
        CHECK (char_length(business_correlation) BETWEEN 1 AND 128),
    CONSTRAINT ck_token_operation_asset
        CHECK (asset_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_token_operation_unit
        CHECK (unit_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_token_operation_unit_version
        CHECK (unit_version > 0),
    CONSTRAINT ck_token_operation_unit_scale
        CHECK (unit_scale BETWEEN 0 AND 255),
    CONSTRAINT ck_token_operation_quantity
        CHECK (unit_max_atomic > 0 AND quantity_atomic > 0
            AND quantity_atomic <= unit_max_atomic),
    CONSTRAINT ck_token_operation_state
        CHECK (lifecycle_state IN (
            'REQUESTED', 'VALIDATED', 'POLICY_PENDING', 'APPROVAL_PENDING',
            'AUTHORIZED', 'SIGNING', 'SUBMISSION_PENDING',
            'SUBMISSION_AMBIGUOUS', 'OBSERVING', 'CHAIN_FINALITY_REACHED',
            'RECONCILING', 'MANUAL_REVIEW', 'REJECTED', 'FAILED_NO_EFFECT',
            'COMPLETED')),
    CONSTRAINT ck_token_operation_version
        CHECK (aggregate_version >= 0),
    CONSTRAINT ck_token_operation_acceptance_evidence
        CHECK (char_length(acceptance_evidence_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_token_operation_times
        CHECK (updated_at >= created_at)
);

CREATE INDEX idx_token_operation_participant
    ON token_operation (tenant_id, participant_id, operation_id);

CREATE TABLE operation_idempotency (
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    idempotency_resource VARCHAR(64) NOT NULL,
    operation_kind VARCHAR(8) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    canonicalization_version INTEGER NOT NULL,
    command_digest CHAR(64) NOT NULL,
    operation_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        tenant_id, participant_id, idempotency_resource,
        operation_kind, idempotency_key_digest),
    CONSTRAINT uq_operation_idempotency_operation UNIQUE (operation_id),
    CONSTRAINT fk_operation_idempotency_operation
        FOREIGN KEY (operation_id) REFERENCES token_operation (operation_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_operation_idempotency_resource
        CHECK (idempotency_resource = 'TOKEN_OPERATION'),
    CONSTRAINT ck_operation_idempotency_kind
        CHECK (operation_kind IN ('MINT', 'BURN')),
    CONSTRAINT ck_operation_idempotency_key_digest
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_operation_idempotency_canonicalization_version
        CHECK (canonicalization_version > 0),
    CONSTRAINT ck_operation_idempotency_command_digest
        CHECK (command_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_operation_idempotency_lookup
    ON operation_idempotency (operation_id);

CREATE TABLE operation_transition (
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    transition_sequence BIGINT NOT NULL,
    aggregate_version BIGINT NOT NULL,
    from_state VARCHAR(32),
    to_state VARCHAR(32) NOT NULL,
    actor VARCHAR(128) NOT NULL,
    reason VARCHAR(128) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, transition_sequence),
    CONSTRAINT uq_operation_transition_version
        UNIQUE (operation_id, aggregate_version),
    CONSTRAINT ck_operation_transition_sequence
        CHECK (transition_sequence >= 0),
    CONSTRAINT ck_operation_transition_version
        CHECK (aggregate_version >= 0),
    CONSTRAINT ck_operation_transition_states
        CHECK (
            (transition_sequence = 0 AND aggregate_version = 0
                AND from_state IS NULL AND to_state = 'REQUESTED')
            OR
            (transition_sequence > 0 AND aggregate_version > 0
                AND from_state IS NOT NULL)),
    CONSTRAINT ck_operation_transition_from_state
        CHECK (from_state IS NULL OR from_state IN (
            'REQUESTED', 'VALIDATED', 'POLICY_PENDING', 'APPROVAL_PENDING',
            'AUTHORIZED', 'SIGNING', 'SUBMISSION_PENDING',
            'SUBMISSION_AMBIGUOUS', 'OBSERVING', 'CHAIN_FINALITY_REACHED',
            'RECONCILING', 'MANUAL_REVIEW', 'REJECTED', 'FAILED_NO_EFFECT',
            'COMPLETED')),
    CONSTRAINT ck_operation_transition_to_state
        CHECK (to_state IN (
            'REQUESTED', 'VALIDATED', 'POLICY_PENDING', 'APPROVAL_PENDING',
            'AUTHORIZED', 'SIGNING', 'SUBMISSION_PENDING',
            'SUBMISSION_AMBIGUOUS', 'OBSERVING', 'CHAIN_FINALITY_REACHED',
            'RECONCILING', 'MANUAL_REVIEW', 'REJECTED', 'FAILED_NO_EFFECT',
            'COMPLETED')),
    CONSTRAINT ck_operation_transition_actor
        CHECK (char_length(actor) BETWEEN 1 AND 128),
    CONSTRAINT ck_operation_transition_reason
        CHECK (char_length(reason) BETWEEN 1 AND 128)
);

CREATE TABLE operation_transition_evidence (
    operation_id UUID NOT NULL,
    transition_sequence BIGINT NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (operation_id, transition_sequence, evidence_order),
    CONSTRAINT fk_transition_evidence_transition
        FOREIGN KEY (operation_id, transition_sequence)
        REFERENCES operation_transition (operation_id, transition_sequence),
    CONSTRAINT ck_transition_evidence_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_transition_evidence_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE operation_attempt (
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    attempt_order INTEGER NOT NULL,
    attempt_id UUID NOT NULL,
    predecessor_attempt_id UUID,
    retry_basis VARCHAR(64),
    retry_policy_version VARCHAR(128),
    authorization_evidence_ref VARCHAR(256) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, attempt_order),
    CONSTRAINT uq_operation_attempt_id UNIQUE (attempt_id),
    CONSTRAINT uq_operation_attempt_identity UNIQUE (operation_id, attempt_id),
    CONSTRAINT uq_operation_attempt_version UNIQUE (operation_id, aggregate_version),
    CONSTRAINT fk_operation_attempt_predecessor
        FOREIGN KEY (operation_id, predecessor_attempt_id)
        REFERENCES operation_attempt (operation_id, attempt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_operation_attempt_order CHECK (attempt_order >= 0),
    CONSTRAINT ck_operation_attempt_version CHECK (aggregate_version > 0),
    CONSTRAINT ck_operation_attempt_lineage CHECK (
        (attempt_order = 0 AND predecessor_attempt_id IS NULL
            AND retry_basis IS NULL AND retry_policy_version IS NULL)
        OR
        (attempt_order > 0 AND predecessor_attempt_id IS NOT NULL
            AND retry_basis = 'NATIVE_SAFE_REPLACEMENT'
            AND char_length(retry_policy_version) BETWEEN 1 AND 128)),
    CONSTRAINT ck_operation_attempt_evidence
        CHECK (char_length(authorization_evidence_ref) BETWEEN 1 AND 256)
);

CREATE INDEX idx_operation_attempt_lineage
    ON operation_attempt (operation_id, attempt_order, attempt_id);

CREATE TABLE operation_finality (
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    finality_type VARCHAR(32) NOT NULL,
    history_order INTEGER NOT NULL,
    aggregate_version BIGINT NOT NULL,
    finality_status VARCHAR(16) NOT NULL,
    authority VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, finality_type, history_order),
    CONSTRAINT ck_operation_finality_type CHECK (finality_type IN (
        'BLOCKCHAIN', 'LEGAL', 'CUSTOMER_VISIBLE', 'ACCOUNTING')),
    CONSTRAINT ck_operation_finality_status CHECK (finality_status IN (
        'NOT_ASSESSED', 'PENDING', 'REACHED', 'REJECTED')),
    CONSTRAINT ck_operation_finality_order CHECK (history_order >= 0),
    CONSTRAINT ck_operation_finality_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_operation_finality_initial CHECK (
        (history_order = 0 AND aggregate_version = 0
            AND finality_status = 'NOT_ASSESSED')
        OR
        (history_order > 0 AND aggregate_version > 0
            AND finality_status <> 'NOT_ASSESSED')),
    CONSTRAINT ck_operation_finality_authority
        CHECK (char_length(authority) BETWEEN 1 AND 128),
    CONSTRAINT ck_operation_finality_policy
        CHECK (char_length(policy_version) BETWEEN 1 AND 128)
);

CREATE UNIQUE INDEX uq_operation_finality_version
    ON operation_finality (operation_id, aggregate_version)
    WHERE aggregate_version > 0;

CREATE INDEX idx_operation_finality_history
    ON operation_finality (operation_id, finality_type, history_order);

CREATE TABLE operation_finality_evidence (
    operation_id UUID NOT NULL,
    finality_type VARCHAR(32) NOT NULL,
    history_order INTEGER NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (operation_id, finality_type, history_order, evidence_order),
    CONSTRAINT fk_finality_evidence_finality
        FOREIGN KEY (operation_id, finality_type, history_order)
        REFERENCES operation_finality (operation_id, finality_type, history_order),
    CONSTRAINT ck_finality_evidence_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_finality_evidence_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE operation_outbox (
    event_id UUID PRIMARY KEY,
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    event_type VARCHAR(64) NOT NULL,
    event_version INTEGER NOT NULL,
    payload_schema_version INTEGER NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    available_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_operation_outbox_acceptance
        UNIQUE (operation_id, event_type, event_version),
    CONSTRAINT ck_operation_outbox_type
        CHECK (event_type = 'TokenOperationAccepted'),
    CONSTRAINT ck_operation_outbox_versions
        CHECK (event_version > 0 AND payload_schema_version > 0),
    CONSTRAINT ck_operation_outbox_status
        CHECK (status = 'PENDING'),
    CONSTRAINT ck_operation_outbox_payload
        CHECK (jsonb_typeof(payload) = 'object'),
    CONSTRAINT ck_operation_outbox_time
        CHECK (available_at >= created_at)
);

CREATE INDEX idx_operation_outbox_pending
    ON operation_outbox (status, available_at, event_id);
