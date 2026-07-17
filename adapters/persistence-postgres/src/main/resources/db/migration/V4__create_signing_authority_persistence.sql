CREATE TABLE signing_request (
    request_id UUID PRIMARY KEY,
    intent_canonicalization_version INTEGER NOT NULL,
    intent_digest CHAR(64) NOT NULL,
    request_canonicalization_version INTEGER NOT NULL,
    request_digest CHAR(64) NOT NULL,
    operation_id UUID NOT NULL,
    operation_attempt_id UUID NOT NULL,
    transfer_id UUID,
    effect_id UUID,
    predecessor_signing_request_id UUID,
    predecessor_signing_attempt_id UUID,
    lineage_evidence_ref VARCHAR(256),
    action VARCHAR(16) NOT NULL,
    settlement_network VARCHAR(16) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    unit_max_atomic NUMERIC(512, 0) NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    source_reference VARCHAR(256) NOT NULL,
    destination_reference VARCHAR(256) NOT NULL,
    native_action_identity VARCHAR(256) NOT NULL,
    lifetime_context_digest CHAR(64) NOT NULL,
    fee_limit VARCHAR(256) NOT NULL,
    native_constraint_digest CHAR(64) NOT NULL,
    payload_mode VARCHAR(32) NOT NULL,
    algorithm VARCHAR(16) NOT NULL,
    payload_sha256 CHAR(64) NOT NULL,
    payload_length INTEGER NOT NULL,
    payload_encoding VARCHAR(48) NOT NULL,
    key_alias VARCHAR(128) NOT NULL,
    registry_version VARCHAR(256) NOT NULL,
    key_version VARCHAR(256),
    key_role VARCHAR(32) NOT NULL,
    key_status VARCHAR(16) NOT NULL,
    allowed_roles VARCHAR(256) NOT NULL,
    allowed_algorithms VARCHAR(256) NOT NULL,
    allowed_networks VARCHAR(256) NOT NULL,
    key_valid_from TIMESTAMPTZ NOT NULL,
    key_expires_at TIMESTAMPTZ,
    policy_version VARCHAR(256) NOT NULL,
    issued_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    signing_status VARCHAR(32) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_signing_digests CHECK (
        intent_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$'
        AND payload_sha256 ~ '^[0-9a-f]{64}$'
        AND lifetime_context_digest ~ '^[0-9a-f]{64}$'
        AND native_constraint_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_signing_versions CHECK (
        intent_canonicalization_version > 0
        AND request_canonicalization_version > 0
        AND aggregate_version >= 0
        AND unit_version > 0
        AND unit_scale BETWEEN 0 AND 255),
    CONSTRAINT ck_signing_transfer_correlation CHECK (
        (transfer_id IS NULL AND effect_id IS NULL)
        OR (transfer_id IS NOT NULL AND effect_id IS NOT NULL)),
    CONSTRAINT ck_signing_lineage CHECK (
        (predecessor_signing_request_id IS NULL
            AND predecessor_signing_attempt_id IS NULL
            AND lineage_evidence_ref IS NULL)
        OR (predecessor_signing_request_id IS NOT NULL
            AND predecessor_signing_attempt_id IS NOT NULL
            AND lineage_evidence_ref IS NOT NULL)),
    CONSTRAINT ck_signing_action_role CHECK (
        (action = 'MINT' AND key_role = 'MINT_AUTHORITY')
        OR (action = 'TRANSFER' AND key_role = 'TRANSFER_AUTHORITY')
        OR (action = 'BURN' AND key_role = 'BURN_AUTHORITY')),
    CONSTRAINT ck_signing_network CHECK (
        settlement_network IN ('ETHEREUM', 'SOLANA')),
    CONSTRAINT ck_signing_payload CHECK (
        (payload_mode = 'EVM_DIGEST'
            AND algorithm = 'SECP256K1'
            AND payload_length = 32
            AND payload_encoding = 'RAW_32_BYTE_DIGEST')
        OR (payload_mode = 'SOLANA_MESSAGE'
            AND algorithm = 'ED25519'
            AND payload_length BETWEEN 1 AND 65536
            AND payload_encoding = 'SOLANA_SERIALIZED_MESSAGE')),
    CONSTRAINT ck_signing_key_status CHECK (
        (key_status = 'NOT_FOUND'
            AND key_version IS NULL
            AND allowed_roles = ''
            AND allowed_algorithms = ''
            AND allowed_networks = '')
        OR (key_status IN ('ACTIVE', 'DISABLED', 'REVOKED')
            AND key_version IS NOT NULL)),
    CONSTRAINT ck_signing_quantity CHECK (
        unit_max_atomic > 0
        AND quantity_atomic > 0
        AND quantity_atomic <= unit_max_atomic),
    CONSTRAINT ck_signing_status CHECK (signing_status IN (
        'REQUESTED', 'AWAITING_AUTHORIZATION', 'AUTHORIZED',
        'PROVIDER_REQUEST_PERSISTED', 'SIGNED', 'DENIED',
        'RETRYABLE_NO_SIGNATURE', 'AMBIGUOUS', 'EXPIRED',
        'REVOKED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_signing_times CHECK (
        expires_at > issued_at
        AND updated_at >= created_at
        AND (key_expires_at IS NULL OR key_expires_at > key_valid_from)),
    CONSTRAINT ck_signing_text CHECK (
        char_length(source_reference) BETWEEN 1 AND 256
        AND char_length(destination_reference) BETWEEN 1 AND 256
        AND char_length(native_action_identity) BETWEEN 1 AND 256
        AND char_length(fee_limit) BETWEEN 1 AND 256
        AND char_length(policy_version) BETWEEN 1 AND 256
        AND char_length(registry_version) BETWEEN 1 AND 256
        AND char_length(key_alias) BETWEEN 1 AND 128)
);

CREATE TABLE signing_request_approval_evidence (
    request_id UUID NOT NULL REFERENCES signing_request (request_id),
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (request_id, evidence_order),
    CONSTRAINT ck_signing_approval_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_signing_approval_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE signing_attempt (
    request_id UUID NOT NULL REFERENCES signing_request (request_id),
    attempt_order INTEGER NOT NULL,
    attempt_id UUID NOT NULL,
    predecessor_attempt_id UUID,
    provider_request_id VARCHAR(256) NOT NULL,
    attempt_status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    signature_sha256 CHAR(64),
    signature_length INTEGER,
    signature_encoding VARCHAR(256),
    evidence_origin VARCHAR(32),
    safe_failure_code VARCHAR(128),
    PRIMARY KEY (request_id, attempt_order),
    CONSTRAINT uq_signing_attempt_identity UNIQUE (request_id, attempt_id),
    CONSTRAINT uq_signing_provider_request UNIQUE (provider_request_id),
    CONSTRAINT fk_signing_attempt_predecessor
        FOREIGN KEY (request_id, predecessor_attempt_id)
        REFERENCES signing_attempt (request_id, attempt_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_signing_attempt_order CHECK (
        (attempt_order = 0 AND predecessor_attempt_id IS NULL)
        OR (attempt_order > 0 AND predecessor_attempt_id IS NOT NULL)),
    CONSTRAINT ck_signing_attempt_status CHECK (attempt_status IN (
        'PROVIDER_REQUEST_PERSISTED', 'SIGNED', 'DENIED',
        'RETRYABLE_NO_SIGNATURE', 'AMBIGUOUS', 'MANUAL_REVIEW')),
    CONSTRAINT ck_signing_attempt_signature CHECK (
        (attempt_status = 'SIGNED'
            AND signature_sha256 ~ '^[0-9a-f]{64}$'
            AND signature_length BETWEEN 1 AND 65536
            AND signature_encoding IS NOT NULL
            AND evidence_origin IN ('PROVIDER', 'SYNTHETIC_TEST'))
        OR (attempt_status <> 'SIGNED'
            AND signature_sha256 IS NULL
            AND signature_length IS NULL
            AND signature_encoding IS NULL
            AND evidence_origin IS NULL)),
    CONSTRAINT ck_signing_attempt_failure CHECK (
        (attempt_status IN ('DENIED', 'RETRYABLE_NO_SIGNATURE', 'MANUAL_REVIEW')
            AND safe_failure_code ~ '^[a-z0-9][a-z0-9.-]{0,127}$')
        OR (attempt_status NOT IN ('DENIED', 'RETRYABLE_NO_SIGNATURE', 'MANUAL_REVIEW')
            AND safe_failure_code IS NULL)),
    CONSTRAINT ck_signing_attempt_times CHECK (updated_at >= created_at)
);

CREATE TABLE signing_attempt_evidence (
    request_id UUID NOT NULL,
    attempt_order INTEGER NOT NULL,
    evidence_order INTEGER NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (request_id, attempt_order, evidence_order),
    CONSTRAINT fk_signing_attempt_evidence
        FOREIGN KEY (request_id, attempt_order)
        REFERENCES signing_attempt (request_id, attempt_order),
    CONSTRAINT ck_signing_attempt_evidence_order CHECK (evidence_order >= 0),
    CONSTRAINT ck_signing_attempt_evidence_ref
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE signing_transition (
    request_id UUID NOT NULL REFERENCES signing_request (request_id),
    aggregate_version BIGINT NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    reason VARCHAR(256) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (request_id, aggregate_version),
    CONSTRAINT ck_signing_transition_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_signing_transition_initial CHECK (
        (aggregate_version = 0 AND from_status IS NULL AND to_status = 'REQUESTED')
        OR (aggregate_version > 0 AND from_status IS NOT NULL)),
    CONSTRAINT ck_signing_transition_from CHECK (
        from_status IS NULL OR from_status IN (
            'REQUESTED', 'AWAITING_AUTHORIZATION', 'AUTHORIZED',
            'PROVIDER_REQUEST_PERSISTED', 'SIGNED', 'DENIED',
            'RETRYABLE_NO_SIGNATURE', 'AMBIGUOUS', 'EXPIRED',
            'REVOKED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_signing_transition_to CHECK (to_status IN (
        'REQUESTED', 'AWAITING_AUTHORIZATION', 'AUTHORIZED',
        'PROVIDER_REQUEST_PERSISTED', 'SIGNED', 'DENIED',
        'RETRYABLE_NO_SIGNATURE', 'AMBIGUOUS', 'EXPIRED',
        'REVOKED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_signing_transition_text CHECK (
        char_length(reason) BETWEEN 1 AND 256
        AND char_length(evidence_ref) BETWEEN 1 AND 256)
);

ALTER TABLE signing_request
    ADD CONSTRAINT fk_signing_request_lineage
    FOREIGN KEY (predecessor_signing_request_id, predecessor_signing_attempt_id)
    REFERENCES signing_attempt (request_id, attempt_id)
    DEFERRABLE INITIALLY DEFERRED;
