CREATE TABLE ethereum_nonce_cursor (
    chain_id NUMERIC(78, 0) NOT NULL,
    signing_address CHAR(42) NOT NULL,
    next_nonce NUMERIC(78, 0) NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (chain_id, signing_address),
    CONSTRAINT ck_ethereum_nonce_cursor_address
        CHECK (signing_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT ck_ethereum_nonce_cursor_values
        CHECK (chain_id > 0 AND next_nonce >= 0)
);

CREATE TABLE ethereum_mint_attempt (
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    operation_attempt_id UUID NOT NULL,
    delivery_id UUID NOT NULL UNIQUE REFERENCES operation_outbox (event_id),
    network VARCHAR(32) NOT NULL,
    chain_id NUMERIC(78, 0) NOT NULL,
    contract_address CHAR(42) NOT NULL,
    recipient_address CHAR(42) NOT NULL,
    signing_address CHAR(42) NOT NULL,
    signing_key_alias VARCHAR(128) NOT NULL,
    signing_key_metadata_version VARCHAR(256) NOT NULL,
    observation_policy_version VARCHAR(256) NOT NULL,
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
    PRIMARY KEY (operation_id, operation_attempt_id),
    CONSTRAINT uq_ethereum_mint_nonce
        UNIQUE (chain_id, signing_address, nonce),
    CONSTRAINT uq_ethereum_mint_transaction_hash UNIQUE (transaction_hash),
    CONSTRAINT ck_ethereum_mint_network CHECK (network = 'LOCAL_ANVIL'),
    CONSTRAINT ck_ethereum_mint_addresses CHECK (
        contract_address ~ '^0x[0-9a-f]{40}$'
        AND recipient_address ~ '^0x[0-9a-f]{40}$'
        AND signing_address ~ '^0x[0-9a-f]{40}$'),
    CONSTRAINT ck_ethereum_mint_numeric CHECK (
        chain_id > 0 AND nonce >= 0 AND transaction_type = 2
        AND max_priority_fee_per_gas >= 0
        AND max_fee_per_gas >= max_priority_fee_per_gas
        AND gas_limit > 0 AND transaction_value = 0
        AND required_confirmations BETWEEN 1 AND 100),
    CONSTRAINT ck_ethereum_mint_policy CHECK (
        char_length(observation_policy_version) BETWEEN 1 AND 256),
    CONSTRAINT ck_ethereum_mint_payload CHECK (
        calldata ~ '^0x[0-9a-f]+$'
        AND unsigned_transaction ~ '^0x[0-9a-f]+$'
        AND calldata_sha256 ~ '^[0-9a-f]{64}$'
        AND signing_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ethereum_mint_status CHECK (attempt_status IN (
        'PREPARED', 'SIGNED', 'SUBMISSION_STARTED', 'ACCEPTED',
        'AMBIGUOUS', 'REJECTED', 'CONFIRMED', 'REVERTED',
        'MISMATCHED', 'ORPHANED')),
    CONSTRAINT ck_ethereum_mint_signed_shape CHECK (
        (attempt_status = 'PREPARED'
            AND signature_sha256 IS NULL AND signature_encoding IS NULL
            AND signed_transaction IS NULL AND transaction_hash IS NULL)
        OR (attempt_status <> 'PREPARED'
            AND signature_sha256 ~ '^[0-9a-f]{64}$'
            AND signature_encoding IS NOT NULL
            AND signed_transaction ~ '^0x[0-9a-f]+$'
            AND transaction_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_mint_submission_shape CHECK (
        (attempt_status IN ('PREPARED', 'SIGNED')
            AND submission_started_at IS NULL)
        OR (attempt_status NOT IN ('PREPARED', 'SIGNED')
            AND submission_started_at IS NOT NULL)),
    CONSTRAINT ck_ethereum_mint_block_shape CHECK (
        (block_number IS NULL AND block_hash IS NULL)
        OR (block_number >= 0 AND block_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_mint_times CHECK (updated_at >= created_at)
);

CREATE TABLE ethereum_mint_observation (
    operation_id UUID NOT NULL,
    operation_attempt_id UUID NOT NULL,
    observation_sequence INTEGER NOT NULL,
    observation_status VARCHAR(32) NOT NULL,
    transaction_hash CHAR(66) NOT NULL,
    block_number NUMERIC(78, 0),
    block_hash CHAR(66),
    observation_source VARCHAR(64) NOT NULL,
    finality_policy_version VARCHAR(256) NOT NULL,
    required_confirmations INTEGER NOT NULL,
    observed_confirmations NUMERIC(78, 0),
    receipt_success BOOLEAN,
    receipt_evidence_sha256 CHAR(64),
    observed_sender_address CHAR(42),
    observed_contract_address CHAR(42),
    observed_nonce NUMERIC(78, 0),
    observed_calldata_sha256 CHAR(64),
    mint_recipient_address CHAR(42),
    mint_atomic_amount NUMERIC(78, 0),
    mint_log_evidence_sha256 CHAR(64),
    observed_at TIMESTAMPTZ NOT NULL,
    evidence_ref VARCHAR(256) NOT NULL,
    PRIMARY KEY (operation_id, operation_attempt_id, observation_sequence),
    CONSTRAINT fk_ethereum_mint_observation_attempt
        FOREIGN KEY (operation_id, operation_attempt_id)
        REFERENCES ethereum_mint_attempt (operation_id, operation_attempt_id),
    CONSTRAINT ck_ethereum_mint_observation_status CHECK (
        observation_status IN (
            'ABSENT_OR_PENDING', 'CONFIRMED', 'REVERTED',
            'MISMATCHED', 'ORPHANED')),
    CONSTRAINT ck_ethereum_mint_observation_hash
        CHECK (transaction_hash ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT ck_ethereum_mint_observation_block CHECK (
        (block_number IS NULL AND block_hash IS NULL)
        OR (block_number >= 0 AND block_hash ~ '^0x[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_mint_observation_sequence
        CHECK (observation_sequence > 0),
    CONSTRAINT ck_ethereum_mint_observation_policy CHECK (
        observation_source = 'LOCAL_ANVIL_RPC'
        AND char_length(finality_policy_version) BETWEEN 1 AND 256
        AND required_confirmations BETWEEN 1 AND 100
        AND (observed_confirmations IS NULL OR observed_confirmations >= 0)),
    CONSTRAINT ck_ethereum_mint_observation_receipt CHECK (
        receipt_evidence_sha256 IS NULL
        OR receipt_evidence_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_ethereum_mint_observation_transaction CHECK (
        (observed_sender_address IS NULL
            AND observed_contract_address IS NULL
            AND observed_nonce IS NULL
            AND observed_calldata_sha256 IS NULL)
        OR (observed_sender_address ~ '^0x[0-9a-f]{40}$'
            AND observed_contract_address ~ '^0x[0-9a-f]{40}$'
            AND observed_nonce >= 0
            AND observed_calldata_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_mint_observation_log CHECK (
        (mint_recipient_address IS NULL
            AND mint_atomic_amount IS NULL
            AND mint_log_evidence_sha256 IS NULL)
        OR (mint_recipient_address ~ '^0x[0-9a-f]{40}$'
            AND mint_atomic_amount >= 0
            AND mint_log_evidence_sha256 ~ '^[0-9a-f]{64}$')),
    CONSTRAINT ck_ethereum_mint_observation_evidence
        CHECK (char_length(evidence_ref) BETWEEN 1 AND 256)
);
