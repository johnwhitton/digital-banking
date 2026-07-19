CREATE TABLE wallet_transfer_operation (
    operation_id UUID PRIMARY KEY,
    transfer_id UUID NOT NULL UNIQUE,
    effect_id UUID NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    command_version INTEGER NOT NULL,
    command_digest CHAR(64) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    unit_max_atomic NUMERIC(512, 0) NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    source_wallet_ref VARCHAR(128) NOT NULL,
    source_address CHAR(42) NOT NULL,
    source_key_alias VARCHAR(128) NOT NULL,
    source_registry_version VARCHAR(128) NOT NULL,
    source_key_version VARCHAR(128) NOT NULL,
    destination_wallet_ref VARCHAR(128) NOT NULL,
    destination_address CHAR(42) NOT NULL,
    destination_key_alias VARCHAR(128) NOT NULL,
    destination_registry_version VARCHAR(128) NOT NULL,
    destination_key_version VARCHAR(128) NOT NULL,
    network VARCHAR(16) NOT NULL,
    contract_address CHAR(42) NOT NULL,
    contract_version VARCHAR(128) NOT NULL,
    finality_policy_version VARCHAR(128) NOT NULL,
    attempt_id UUID NOT NULL UNIQUE,
    operation_status VARCHAR(32) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_wallet_transfer_idempotency UNIQUE (
        tenant_id, participant_id, idempotency_key_digest),
    CONSTRAINT ck_wallet_transfer_participant CHECK (
        tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        AND participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_wallet_transfer_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_wallet_transfer_quantity CHECK (
        command_version > 0 AND unit_version > 0
        AND unit_scale BETWEEN 0 AND 255
        AND unit_max_atomic > 0 AND quantity_atomic > 0
        AND quantity_atomic <= unit_max_atomic),
    CONSTRAINT ck_wallet_transfer_wallets CHECK (
        source_wallet_ref ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}$'
        AND destination_wallet_ref ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}$'
        AND source_wallet_ref <> destination_wallet_ref
        AND source_address ~ '^0x[0-9a-f]{40}$'
        AND destination_address ~ '^0x[0-9a-f]{40}$'
        AND source_address <> destination_address),
    CONSTRAINT ck_wallet_transfer_route CHECK (
        network = 'ETHEREUM'
        AND contract_address ~ '^0x[0-9a-f]{40}$'
        AND char_length(contract_version) BETWEEN 1 AND 128
        AND char_length(finality_policy_version) BETWEEN 1 AND 128),
    CONSTRAINT ck_wallet_transfer_versions CHECK (
        char_length(source_key_alias) BETWEEN 1 AND 128
        AND char_length(source_registry_version) BETWEEN 1 AND 128
        AND char_length(source_key_version) BETWEEN 1 AND 128
        AND char_length(destination_key_alias) BETWEEN 1 AND 128
        AND char_length(destination_registry_version) BETWEEN 1 AND 128
        AND char_length(destination_key_version) BETWEEN 1 AND 128),
    CONSTRAINT ck_wallet_transfer_status CHECK (operation_status IN (
        'ACCEPTED', 'SIGNING', 'SUBMISSION_PENDING', 'SUBMISSION_AMBIGUOUS',
        'OBSERVING', 'CHAIN_FINALITY_REACHED', 'COMPLETED',
        'MANUAL_REVIEW', 'FAILED_NO_EFFECT')),
    CONSTRAINT ck_wallet_transfer_time CHECK (
        aggregate_version >= 0 AND updated_at >= created_at)
);

CREATE TABLE wallet_transfer_transition (
    operation_id UUID NOT NULL REFERENCES wallet_transfer_operation (operation_id),
    aggregate_version BIGINT NOT NULL,
    from_status VARCHAR(32),
    to_status VARCHAR(32) NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (operation_id, aggregate_version),
    CONSTRAINT ck_wallet_transfer_transition_initial CHECK (
        (aggregate_version = 0 AND from_status IS NULL AND to_status = 'ACCEPTED')
        OR (aggregate_version > 0 AND from_status IS NOT NULL)),
    CONSTRAINT ck_wallet_transfer_transition_evidence CHECK (
        char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE TABLE wallet_transfer_finality (
    operation_id UUID NOT NULL REFERENCES wallet_transfer_operation (operation_id),
    finality_type VARCHAR(32) NOT NULL,
    history_order INTEGER NOT NULL,
    finality_status VARCHAR(16) NOT NULL,
    authority VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    evidence_ref VARCHAR(256),
    PRIMARY KEY (operation_id, finality_type, history_order),
    CONSTRAINT ck_wallet_transfer_finality_type CHECK (finality_type IN (
        'BLOCKCHAIN', 'LEGAL', 'CUSTOMER_VISIBLE', 'ACCOUNTING')),
    CONSTRAINT ck_wallet_transfer_finality_status CHECK (finality_status IN (
        'NOT_ASSESSED', 'PENDING', 'REACHED', 'REJECTED')),
    CONSTRAINT ck_wallet_transfer_finality_shape CHECK (
        (history_order = 0 AND finality_status = 'NOT_ASSESSED'
            AND evidence_ref IS NULL)
        OR (history_order > 0 AND finality_status <> 'NOT_ASSESSED'
            AND char_length(evidence_ref) BETWEEN 1 AND 256))
);

ALTER TABLE operation_outbox
    DROP CONSTRAINT ck_operation_outbox_aggregate,
    ADD COLUMN wallet_transfer_id UUID
        REFERENCES wallet_transfer_operation (operation_id),
    ADD CONSTRAINT ck_operation_outbox_aggregate CHECK (
        (operation_id IS NOT NULL AND transfer_id IS NULL
            AND wallet_transfer_id IS NULL
            AND event_type = 'TokenOperationAccepted')
        OR (operation_id IS NULL AND transfer_id IS NOT NULL
            AND wallet_transfer_id IS NULL
            AND event_type = 'TransferAccepted')
        OR (operation_id IS NULL AND transfer_id IS NULL
            AND wallet_transfer_id IS NOT NULL
            AND event_type = 'WalletTransferAccepted'));

CREATE UNIQUE INDEX uq_operation_outbox_wallet_transfer
    ON operation_outbox (wallet_transfer_id, event_type, event_version)
    WHERE wallet_transfer_id IS NOT NULL;

DROP INDEX idx_operation_outbox_eligible;

CREATE INDEX idx_operation_outbox_eligible
    ON operation_outbox (
        status, available_at, lease_expires_at,
        operation_id, transfer_id, wallet_transfer_id, created_at, event_id);

CREATE TABLE wallet_transfer_handler_inbox (
    delivery_id UUID PRIMARY KEY REFERENCES operation_outbox (event_id),
    operation_id UUID NOT NULL REFERENCES wallet_transfer_operation (operation_id),
    result VARCHAR(32) NOT NULL,
    applied_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_wallet_transfer_handler_result CHECK (result = 'ATTEMPT_STARTED')
);

CREATE TABLE ethereum_wallet_transfer_attempt (
    operation_id UUID NOT NULL REFERENCES wallet_transfer_operation (operation_id),
    effect_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    delivery_id UUID NOT NULL UNIQUE REFERENCES operation_outbox (event_id),
    chain_id NUMERIC(78, 0) NOT NULL,
    contract_address CHAR(42) NOT NULL,
    source_address CHAR(42) NOT NULL,
    destination_address CHAR(42) NOT NULL,
    source_key_alias VARCHAR(128) NOT NULL,
    source_registry_version VARCHAR(128) NOT NULL,
    source_key_version VARCHAR(128) NOT NULL,
    destination_registry_version VARCHAR(128) NOT NULL,
    destination_key_version VARCHAR(128) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    observation_policy_version VARCHAR(128) NOT NULL,
    required_confirmations INTEGER NOT NULL,
    nonce NUMERIC(78, 0) NOT NULL,
    transaction_type INTEGER NOT NULL,
    max_priority_fee_per_gas NUMERIC(78, 0) NOT NULL,
    max_fee_per_gas NUMERIC(78, 0) NOT NULL,
    gas_limit NUMERIC(78, 0) NOT NULL,
    transaction_value NUMERIC(78, 0) NOT NULL,
    calldata TEXT NOT NULL,
    calldata_sha256 CHAR(64) NOT NULL,
    unsigned_transaction TEXT NOT NULL,
    signing_digest CHAR(64) NOT NULL,
    signature_sha256 CHAR(64),
    signature_encoding VARCHAR(256),
    signed_transaction TEXT,
    transaction_hash CHAR(66),
    attempt_status VARCHAR(32) NOT NULL,
    submission_started_at TIMESTAMPTZ,
    submission_recorded_at TIMESTAMPTZ,
    submission_code VARCHAR(64),
    block_number NUMERIC(78, 0),
    block_hash CHAR(66),
    reconciliation_disposition VARCHAR(32),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, attempt_id),
    CONSTRAINT uq_ethereum_wallet_transfer_effect UNIQUE (operation_id, effect_id),
    CONSTRAINT uq_ethereum_wallet_transfer_nonce UNIQUE (chain_id, source_address, nonce),
    CONSTRAINT uq_ethereum_wallet_transfer_hash UNIQUE (transaction_hash),
    CONSTRAINT ck_ethereum_wallet_transfer_addresses CHECK (
        contract_address ~ '^0x[0-9a-f]{40}$'
        AND source_address ~ '^0x[0-9a-f]{40}$'
        AND destination_address ~ '^0x[0-9a-f]{40}$'
        AND source_address <> destination_address),
    CONSTRAINT ck_ethereum_wallet_transfer_numeric CHECK (
        chain_id = 31337 AND nonce >= 0 AND transaction_type = 2
        AND max_priority_fee_per_gas >= 0
        AND max_fee_per_gas >= max_priority_fee_per_gas
        AND gas_limit > 0 AND transaction_value = 0
        AND quantity_atomic > 0 AND unit_version > 0
        AND unit_scale = 2 AND required_confirmations BETWEEN 1 AND 100),
    CONSTRAINT ck_ethereum_wallet_transfer_payload CHECK (
        calldata ~ '^0xa9059cbb[0-9a-f]{128}$'
        AND calldata_sha256 ~ '^[0-9a-f]{64}$'
        AND unsigned_transaction ~ '^0x[0-9a-f]+$'
        AND signing_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ethereum_wallet_transfer_status CHECK (attempt_status IN (
        'PREPARED', 'SIGNED', 'SUBMISSION_STARTED', 'ACCEPTED', 'AMBIGUOUS',
        'REJECTED', 'CONFIRMED', 'REVERTED', 'MISMATCHED', 'ORPHANED')),
    CONSTRAINT ck_ethereum_wallet_transfer_signed_shape CHECK (
        (attempt_status = 'PREPARED'
            AND signature_sha256 IS NULL AND signature_encoding IS NULL
            AND signed_transaction IS NULL AND transaction_hash IS NULL)
        OR (attempt_status <> 'PREPARED'
            AND signature_sha256 ~ '^[0-9a-f]{64}$'
            AND signature_encoding IS NOT NULL
            AND signed_transaction ~ '^0x[0-9a-f]+$'
            AND transaction_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_wallet_transfer_submission_shape CHECK (
        (attempt_status IN ('PREPARED', 'SIGNED') AND submission_started_at IS NULL)
        OR (attempt_status NOT IN ('PREPARED', 'SIGNED')
            AND submission_started_at IS NOT NULL)),
    CONSTRAINT ck_ethereum_wallet_transfer_block_shape CHECK (
        (block_number IS NULL AND block_hash IS NULL)
        OR (block_number >= 0 AND block_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_wallet_transfer_times CHECK (updated_at >= created_at)
);

CREATE TABLE ethereum_wallet_transfer_observation (
    operation_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    observation_sequence INTEGER NOT NULL,
    observation_status VARCHAR(32) NOT NULL,
    transaction_hash CHAR(66) NOT NULL,
    block_number NUMERIC(78, 0),
    block_hash CHAR(66),
    observation_source VARCHAR(64) NOT NULL,
    finality_policy_version VARCHAR(128) NOT NULL,
    required_confirmations INTEGER NOT NULL,
    observed_confirmations NUMERIC(78, 0),
    receipt_success BOOLEAN,
    receipt_evidence_sha256 CHAR(64),
    observed_source_address CHAR(42),
    observed_contract_address CHAR(42),
    observed_nonce NUMERIC(78, 0),
    observed_calldata_sha256 CHAR(64),
    event_source_address CHAR(42),
    event_destination_address CHAR(42),
    event_atomic_amount NUMERIC(512, 0),
    event_count INTEGER,
    event_evidence_sha256 CHAR(64),
    observed_at TIMESTAMPTZ NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (operation_id, attempt_id, observation_sequence),
    CONSTRAINT fk_ethereum_wallet_transfer_observation_attempt
        FOREIGN KEY (operation_id, attempt_id)
        REFERENCES ethereum_wallet_transfer_attempt (operation_id, attempt_id),
    CONSTRAINT ck_ethereum_wallet_transfer_observation_status CHECK (
        observation_status IN (
            'ABSENT_OR_PENDING', 'CONFIRMED', 'REVERTED', 'MISMATCHED', 'ORPHANED')),
    CONSTRAINT ck_ethereum_wallet_transfer_observation_identity CHECK (
        observation_sequence > 0
        AND transaction_hash ~ '^0x[0-9a-f]{64}$'
        AND observation_source = 'LOCAL_ANVIL_RPC'
        AND required_confirmations BETWEEN 1 AND 100),
    CONSTRAINT ck_ethereum_wallet_transfer_observation_event CHECK (
        (event_source_address IS NULL AND event_destination_address IS NULL
            AND event_atomic_amount IS NULL AND event_count IS NULL
            AND event_evidence_sha256 IS NULL)
        OR (event_source_address ~ '^0x[0-9a-f]{40}$'
            AND event_destination_address ~ '^0x[0-9a-f]{40}$'
            AND event_atomic_amount > 0 AND event_count > 0
            AND event_evidence_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_wallet_transfer_observation_transaction CHECK (
        (observed_source_address IS NULL AND observed_contract_address IS NULL
            AND observed_nonce IS NULL AND observed_calldata_sha256 IS NULL)
        OR (observed_source_address ~ '^0x[0-9a-f]{40}$'
            AND observed_contract_address ~ '^0x[0-9a-f]{40}$'
            AND observed_nonce >= 0
            AND observed_calldata_sha256 ~ '^[0-9a-f]{64}$'))
);
