CREATE TABLE banking_transfer (
    transfer_id UUID PRIMARY KEY,
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    request_canonicalization_version INTEGER NOT NULL,
    request_digest CHAR(64) NOT NULL,
    resolved_canonicalization_version INTEGER NOT NULL,
    resolved_digest CHAR(64) NOT NULL,
    currency VARCHAR(12) NOT NULL,
    source_bank_account_ref VARCHAR(128) NOT NULL,
    destination_bank_account_ref VARCHAR(128) NOT NULL,
    sender_wallet_ref VARCHAR(128) NOT NULL,
    recipient_wallet_ref VARCHAR(128) NOT NULL,
    settlement_network VARCHAR(16) NOT NULL,
    route_version VARCHAR(128) NOT NULL,
    wallet_policy_version VARCHAR(128) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    unit_max_atomic NUMERIC(512, 0) NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    transfer_status VARCHAR(32) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    acceptance_evidence_ref VARCHAR(256) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_transfer_tenant
        CHECK (tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_transfer_participant
        CHECK (participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_transfer_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND resolved_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transfer_canonicalization_versions CHECK (
        request_canonicalization_version > 0
        AND resolved_canonicalization_version > 0),
    CONSTRAINT ck_transfer_currency CHECK (currency ~ '^[A-Z][A-Z0-9]{2,11}$'),
    CONSTRAINT ck_transfer_bank_refs CHECK (
        source_bank_account_ref ~ '^synthetic-bank:[A-Za-z0-9][A-Za-z0-9._:-]{0,111}$'
        AND destination_bank_account_ref ~ '^synthetic-bank:[A-Za-z0-9][A-Za-z0-9._:-]{0,111}$'
        AND source_bank_account_ref <> destination_bank_account_ref),
    CONSTRAINT ck_transfer_wallet_refs CHECK (
        sender_wallet_ref ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}$'
        AND recipient_wallet_ref ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}$'),
    CONSTRAINT ck_transfer_network CHECK (settlement_network IN ('ETHEREUM', 'SOLANA')),
    CONSTRAINT ck_transfer_versions CHECK (
        char_length(route_version) BETWEEN 1 AND 128
        AND char_length(wallet_policy_version) BETWEEN 1 AND 128
        AND unit_version > 0 AND unit_scale BETWEEN 0 AND 255
        AND aggregate_version >= 0),
    CONSTRAINT ck_transfer_asset CHECK (
        asset_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        AND unit_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_transfer_quantity CHECK (
        unit_max_atomic > 0 AND quantity_atomic > 0
        AND quantity_atomic <= unit_max_atomic),
    CONSTRAINT ck_transfer_status CHECK (transfer_status IN (
        'ACCEPTED', 'IN_PROGRESS', 'MANUAL_REVIEW',
        'COMPENSATION_REQUIRED', 'EFFECTS_APPLIED')),
    CONSTRAINT ck_transfer_evidence
        CHECK (char_length(acceptance_evidence_ref) BETWEEN 1 AND 256),
    CONSTRAINT ck_transfer_times CHECK (updated_at >= created_at)
);

CREATE INDEX idx_banking_transfer_participant
    ON banking_transfer (tenant_id, participant_id, transfer_id);

CREATE TABLE transfer_idempotency (
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    idempotency_resource VARCHAR(16) NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    request_canonicalization_version INTEGER NOT NULL,
    request_digest CHAR(64) NOT NULL,
    transfer_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        tenant_id, participant_id, idempotency_resource,
        operation_kind, idempotency_key_digest),
    CONSTRAINT uq_transfer_idempotency_transfer UNIQUE (transfer_id),
    CONSTRAINT fk_transfer_idempotency_transfer
        FOREIGN KEY (transfer_id) REFERENCES banking_transfer (transfer_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_transfer_idempotency_scope CHECK (
        idempotency_resource = 'TRANSFER' AND operation_kind = 'TRANSFER'),
    CONSTRAINT ck_transfer_idempotency_key
        CHECK (idempotency_key_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_transfer_idempotency_command CHECK (
        request_canonicalization_version > 0
        AND request_digest ~ '^[0-9a-f]{64}$')
);

CREATE INDEX idx_transfer_idempotency_lookup
    ON transfer_idempotency (transfer_id);

CREATE TABLE transfer_effect (
    transfer_id UUID NOT NULL REFERENCES banking_transfer (transfer_id),
    effect_sequence INTEGER NOT NULL,
    effect_id UUID NOT NULL,
    effect_kind VARCHAR(32) NOT NULL,
    predecessor_effect_id UUID,
    effect_status VARCHAR(32) NOT NULL,
    current_attempt_id UUID,
    PRIMARY KEY (transfer_id, effect_sequence),
    CONSTRAINT uq_transfer_effect_id UNIQUE (effect_id),
    CONSTRAINT uq_transfer_effect_identity UNIQUE (transfer_id, effect_id),
    CONSTRAINT fk_transfer_effect_predecessor
        FOREIGN KEY (transfer_id, predecessor_effect_id)
        REFERENCES transfer_effect (transfer_id, effect_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_transfer_effect_sequence CHECK (effect_sequence BETWEEN 1 AND 5),
    CONSTRAINT ck_transfer_effect_kind CHECK (
        (effect_sequence = 1 AND effect_kind = 'BANK_WITHDRAWAL')
        OR (effect_sequence = 2 AND effect_kind = 'TOKEN_MINT')
        OR (effect_sequence = 3 AND effect_kind = 'TOKEN_TRANSFER')
        OR (effect_sequence = 4 AND effect_kind = 'TOKEN_BURN')
        OR (effect_sequence = 5 AND effect_kind = 'BANK_DEPOSIT')),
    CONSTRAINT ck_transfer_effect_predecessor CHECK (
        (effect_sequence = 1 AND predecessor_effect_id IS NULL)
        OR (effect_sequence > 1 AND predecessor_effect_id IS NOT NULL)),
    CONSTRAINT ck_transfer_effect_status CHECK (effect_status IN (
        'PLANNED', 'PREPARED', 'ATTEMPT_PENDING', 'APPLIED', 'AMBIGUOUS',
        'RETRYABLE_NO_EFFECT', 'TERMINAL_NO_EFFECT', 'MANUAL_REVIEW',
        'COMPENSATION_REQUIRED')),
    CONSTRAINT ck_transfer_effect_attempt CHECK (
        (effect_status IN ('PLANNED', 'PREPARED') AND current_attempt_id IS NULL)
        OR (effect_status NOT IN ('PLANNED', 'PREPARED') AND current_attempt_id IS NOT NULL))
);

CREATE TABLE transfer_effect_evidence (
    transfer_id UUID NOT NULL,
    effect_sequence INTEGER NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (transfer_id, effect_sequence, evidence_order),
    CONSTRAINT fk_transfer_effect_evidence
        FOREIGN KEY (transfer_id, effect_sequence)
        REFERENCES transfer_effect (transfer_id, effect_sequence),
    CONSTRAINT ck_transfer_effect_evidence_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_transfer_effect_evidence_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE transfer_transition (
    transfer_id UUID NOT NULL REFERENCES banking_transfer (transfer_id),
    transition_sequence BIGINT NOT NULL,
    transition_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    effect_id UUID,
    action VARCHAR(64) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (transfer_id, transition_sequence),
    CONSTRAINT uq_transfer_transition_id UNIQUE (transition_id),
    CONSTRAINT uq_transfer_transition_version UNIQUE (transfer_id, aggregate_version),
    CONSTRAINT fk_transfer_transition_effect
        FOREIGN KEY (transfer_id, effect_id)
        REFERENCES transfer_effect (transfer_id, effect_id),
    CONSTRAINT ck_transfer_transition_sequence CHECK (
        transition_sequence >= 0 AND aggregate_version >= 0),
    CONSTRAINT ck_transfer_transition_initial CHECK (
        (transition_sequence = 0 AND aggregate_version = 0
            AND from_status IS NULL AND to_status = 'ACCEPTED' AND effect_id IS NULL)
        OR (transition_sequence > 0 AND aggregate_version > 0
            AND from_status IS NOT NULL AND effect_id IS NOT NULL)),
    CONSTRAINT ck_transfer_transition_from CHECK (from_status IS NULL OR from_status IN (
        'ACCEPTED', 'IN_PROGRESS', 'MANUAL_REVIEW',
        'COMPENSATION_REQUIRED', 'EFFECTS_APPLIED')),
    CONSTRAINT ck_transfer_transition_to CHECK (to_status IN (
        'ACCEPTED', 'IN_PROGRESS', 'MANUAL_REVIEW',
        'COMPENSATION_REQUIRED', 'EFFECTS_APPLIED')),
    CONSTRAINT ck_transfer_transition_action
        CHECK (char_length(action) BETWEEN 1 AND 64)
);

CREATE TABLE transfer_transition_evidence (
    transfer_id UUID NOT NULL,
    transition_sequence BIGINT NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (transfer_id, transition_sequence, evidence_order),
    CONSTRAINT fk_transfer_transition_evidence
        FOREIGN KEY (transfer_id, transition_sequence)
        REFERENCES transfer_transition (transfer_id, transition_sequence),
    CONSTRAINT ck_transfer_transition_evidence_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_transfer_transition_evidence_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE transfer_finality (
    transfer_id UUID NOT NULL REFERENCES banking_transfer (transfer_id),
    finality_type VARCHAR(32) NOT NULL,
    history_order INTEGER NOT NULL,
    finality_status VARCHAR(16) NOT NULL,
    authority VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (transfer_id, finality_type, history_order),
    CONSTRAINT ck_transfer_finality_type CHECK (finality_type IN (
        'BLOCKCHAIN', 'LEGAL', 'CUSTOMER_VISIBLE', 'ACCOUNTING')),
    CONSTRAINT ck_transfer_finality_status CHECK (finality_status IN (
        'NOT_ASSESSED', 'PENDING', 'REACHED', 'REJECTED')),
    CONSTRAINT ck_transfer_finality_order CHECK (history_order >= 0),
    CONSTRAINT ck_transfer_finality_initial CHECK (
        (history_order = 0 AND finality_status = 'NOT_ASSESSED')
        OR (history_order > 0 AND finality_status <> 'NOT_ASSESSED')),
    CONSTRAINT ck_transfer_finality_authority
        CHECK (char_length(authority) BETWEEN 1 AND 128),
    CONSTRAINT ck_transfer_finality_policy
        CHECK (char_length(policy_version) BETWEEN 1 AND 128)
);

CREATE TABLE transfer_finality_evidence (
    transfer_id UUID NOT NULL,
    finality_type VARCHAR(32) NOT NULL,
    history_order INTEGER NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (transfer_id, finality_type, history_order, evidence_order),
    CONSTRAINT fk_transfer_finality_evidence
        FOREIGN KEY (transfer_id, finality_type, history_order)
        REFERENCES transfer_finality (transfer_id, finality_type, history_order),
    CONSTRAINT ck_transfer_finality_evidence_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_transfer_finality_evidence_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE transfer_handler_inbox (
    delivery_id UUID PRIMARY KEY REFERENCES operation_outbox (event_id),
    transfer_id UUID NOT NULL REFERENCES banking_transfer (transfer_id),
    result VARCHAR(32) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_transfer_handler_result CHECK (result = 'WITHDRAWAL_PREPARED')
);

ALTER TABLE operation_outbox
    DROP CONSTRAINT uq_operation_outbox_acceptance,
    DROP CONSTRAINT ck_operation_outbox_type,
    ALTER COLUMN operation_id DROP NOT NULL,
    ADD COLUMN transfer_id UUID REFERENCES banking_transfer (transfer_id),
    ADD CONSTRAINT ck_operation_outbox_aggregate CHECK (
        (operation_id IS NOT NULL AND transfer_id IS NULL
            AND event_type = 'TokenOperationAccepted')
        OR (operation_id IS NULL AND transfer_id IS NOT NULL
            AND event_type = 'TransferAccepted'));

CREATE UNIQUE INDEX uq_operation_outbox_token
    ON operation_outbox (operation_id, event_type, event_version)
    WHERE operation_id IS NOT NULL;

CREATE UNIQUE INDEX uq_operation_outbox_transfer
    ON operation_outbox (transfer_id, event_type, event_version)
    WHERE transfer_id IS NOT NULL;

DROP INDEX idx_operation_outbox_eligible;

CREATE INDEX idx_operation_outbox_eligible
    ON operation_outbox (
        status, available_at, lease_expires_at,
        operation_id, transfer_id, created_at, event_id);
