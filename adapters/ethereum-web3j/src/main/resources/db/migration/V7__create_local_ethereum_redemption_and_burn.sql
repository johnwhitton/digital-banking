ALTER TABLE wallet_transfer_operation
    ADD COLUMN transfer_purpose VARCHAR(32) NOT NULL DEFAULT 'USER_TRANSFER',
    ADD CONSTRAINT ck_wallet_transfer_purpose CHECK (
        transfer_purpose IN ('USER_TRANSFER', 'REDEMPTION_CUSTODY'));

ALTER TABLE wallet_transfer_operation
    ALTER COLUMN transfer_purpose DROP DEFAULT;

CREATE TABLE ethereum_redemption_correlation (
    correlation_id UUID PRIMARY KEY,
    burn_operation_id UUID NOT NULL UNIQUE
        REFERENCES token_operation (operation_id),
    custody_operation_id UUID NOT NULL UNIQUE
        REFERENCES wallet_transfer_operation (operation_id),
    custody_effect_id UUID NOT NULL UNIQUE,
    custody_attempt_id UUID NOT NULL UNIQUE,
    correlation_status VARCHAR(32) NOT NULL,
    custody_evidence_ref VARCHAR(256),
    consumed_by_burn_attempt_id UUID UNIQUE,
    consumed_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_ethereum_redemption_status CHECK (correlation_status IN (
        'AWAITING_CUSTODY', 'CUSTODY_CONFIRMED', 'CONSUMED', 'MANUAL_REVIEW')),
    CONSTRAINT ck_ethereum_redemption_consumption CHECK (
        (correlation_status = 'AWAITING_CUSTODY'
            AND custody_evidence_ref IS NULL
            AND consumed_by_burn_attempt_id IS NULL AND consumed_at IS NULL)
        OR (correlation_status = 'CUSTODY_CONFIRMED'
            AND char_length(custody_evidence_ref) BETWEEN 1 AND 256
            AND consumed_by_burn_attempt_id IS NULL AND consumed_at IS NULL)
        OR (correlation_status = 'CONSUMED'
            AND char_length(custody_evidence_ref) BETWEEN 1 AND 256
            AND consumed_by_burn_attempt_id IS NOT NULL AND consumed_at IS NOT NULL)
        OR correlation_status = 'MANUAL_REVIEW'),
    CONSTRAINT ck_ethereum_redemption_times CHECK (updated_at >= created_at)
);

CREATE TABLE ethereum_burn_attempt (
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    operation_attempt_id UUID NOT NULL,
    correlation_id UUID NOT NULL UNIQUE
        REFERENCES ethereum_redemption_correlation (correlation_id),
    delivery_id UUID NOT NULL UNIQUE REFERENCES operation_outbox (event_id),
    chain_id NUMERIC(78, 0) NOT NULL,
    contract_address CHAR(42) NOT NULL,
    admin_address CHAR(42) NOT NULL,
    admin_key_alias VARCHAR(128) NOT NULL,
    admin_registry_version VARCHAR(128) NOT NULL,
    admin_key_version VARCHAR(128) NOT NULL,
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
    PRIMARY KEY (operation_id, operation_attempt_id),
    CONSTRAINT uq_ethereum_burn_nonce UNIQUE (chain_id, admin_address, nonce),
    CONSTRAINT uq_ethereum_burn_hash UNIQUE (transaction_hash),
    CONSTRAINT ck_ethereum_burn_addresses CHECK (
        contract_address ~ '^0x[0-9a-f]{40}$'
        AND admin_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT ck_ethereum_burn_numeric CHECK (
        chain_id = 31337 AND nonce >= 0 AND transaction_type = 2
        AND max_priority_fee_per_gas >= 0
        AND max_fee_per_gas >= max_priority_fee_per_gas
        AND gas_limit > 0 AND transaction_value = 0
        AND quantity_atomic > 0 AND unit_version > 0
        AND unit_scale = 2 AND required_confirmations BETWEEN 1 AND 100),
    CONSTRAINT ck_ethereum_burn_payload CHECK (
        calldata ~ '^0x42966c68[0-9a-f]{64}$'
        AND calldata_sha256 ~ '^[0-9a-f]{64}$'
        AND unsigned_transaction ~ '^0x[0-9a-f]+$'
        AND signing_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ethereum_burn_status CHECK (attempt_status IN (
        'PREPARED', 'SIGNED', 'SUBMISSION_STARTED', 'ACCEPTED', 'AMBIGUOUS',
        'REJECTED', 'CONFIRMED', 'REVERTED', 'MISMATCHED', 'ORPHANED')),
    CONSTRAINT ck_ethereum_burn_signed_shape CHECK (
        (attempt_status = 'PREPARED'
            AND signature_sha256 IS NULL AND signature_encoding IS NULL
            AND transaction_hash IS NULL)
        OR (attempt_status <> 'PREPARED'
            AND signature_sha256 ~ '^[0-9a-f]{64}$'
            AND signature_encoding IS NOT NULL
            AND transaction_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_burn_submission_shape CHECK (
        (attempt_status IN ('PREPARED', 'SIGNED') AND submission_started_at IS NULL)
        OR (attempt_status NOT IN ('PREPARED', 'SIGNED')
            AND submission_started_at IS NOT NULL)),
    CONSTRAINT ck_ethereum_burn_block_shape CHECK (
        (block_number IS NULL AND block_hash IS NULL)
        OR (block_number >= 0 AND block_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_burn_times CHECK (updated_at >= created_at)
);

CREATE TABLE ethereum_burn_observation (
    operation_id UUID NOT NULL,
    operation_attempt_id UUID NOT NULL,
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
    observed_admin_address CHAR(42),
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
    PRIMARY KEY (operation_id, operation_attempt_id, observation_sequence),
    CONSTRAINT fk_ethereum_burn_observation_attempt
        FOREIGN KEY (operation_id, operation_attempt_id)
        REFERENCES ethereum_burn_attempt (operation_id, operation_attempt_id),
    CONSTRAINT ck_ethereum_burn_observation_status CHECK (
        observation_status IN (
            'ABSENT_OR_PENDING', 'CONFIRMED', 'REVERTED', 'MISMATCHED', 'ORPHANED')),
    CONSTRAINT ck_ethereum_burn_observation_identity CHECK (
        observation_sequence > 0
        AND transaction_hash ~ '^0x[0-9a-f]{64}$'
        AND observation_source = 'LOCAL_ANVIL_RPC'
        AND required_confirmations BETWEEN 1 AND 100),
    CONSTRAINT ck_ethereum_burn_observation_event CHECK (
        (event_source_address IS NULL AND event_destination_address IS NULL
            AND event_atomic_amount IS NULL AND event_count IS NULL
            AND event_evidence_sha256 IS NULL)
        OR (event_source_address ~ '^0x[0-9a-f]{40}$'
            AND event_destination_address = '0x0000000000000000000000000000000000000000'
            AND event_atomic_amount > 0 AND event_count > 0
            AND event_evidence_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_burn_observation_transaction CHECK (
        (observed_admin_address IS NULL AND observed_contract_address IS NULL
            AND observed_nonce IS NULL AND observed_calldata_sha256 IS NULL)
        OR (observed_admin_address ~ '^0x[0-9a-f]{40}$'
            AND observed_contract_address ~ '^0x[0-9a-f]{40}$'
            AND observed_nonce >= 0
            AND observed_calldata_sha256 ~ '^[0-9a-f]{64}$'))
);

CREATE TABLE ethereum_redemption_balance_observation (
    correlation_id UUID NOT NULL
        REFERENCES ethereum_redemption_correlation (correlation_id),
    observation_stage VARCHAR(32) NOT NULL,
    block_number NUMERIC(78, 0) NOT NULL,
    block_hash CHAR(66) NOT NULL,
    source_address CHAR(42) NOT NULL,
    admin_address CHAR(42) NOT NULL,
    contract_address CHAR(42) NOT NULL,
    source_balance NUMERIC(512, 0) NOT NULL,
    admin_balance NUMERIC(512, 0) NOT NULL,
    total_supply NUMERIC(512, 0) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (correlation_id, observation_stage),
    CONSTRAINT ck_ethereum_redemption_balance_stage CHECK (
        observation_stage IN ('BEFORE_CUSTODY', 'AFTER_CUSTODY', 'AFTER_BURN')),
    CONSTRAINT ck_ethereum_redemption_balance_block CHECK (
        block_number >= 0 AND block_hash ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT ck_ethereum_redemption_balance_addresses CHECK (
        source_address ~ '^0x[0-9a-f]{40}$'
        AND admin_address ~ '^0x[0-9a-f]{40}$'
        AND source_address <> admin_address
        AND contract_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT ck_ethereum_redemption_balance_values CHECK (
        source_balance >= 0 AND admin_balance >= 0 AND total_supply >= 0),
    CONSTRAINT ck_ethereum_redemption_balance_evidence CHECK (
        char_length(evidence_ref) BETWEEN 1 AND 256)
);
