ALTER TABLE signing_request
    DROP CONSTRAINT ck_signing_action_role;

ALTER TABLE signing_request
    ADD CONSTRAINT ck_signing_action_role CHECK (
        (action = 'MINT' AND (
            key_role = 'MINT_AUTHORITY'
            OR (key_role = 'FEE_PAYER' AND settlement_network = 'SOLANA')))
        OR (action = 'TRANSFER' AND key_role = 'TRANSFER_AUTHORITY')
        OR (action = 'BURN' AND key_role = 'BURN_AUTHORITY'));

CREATE TABLE solana_mint_attempt (
    operation_id UUID NOT NULL REFERENCES token_operation (operation_id),
    operation_attempt_id UUID NOT NULL,
    delivery_id UUID NOT NULL REFERENCES operation_outbox (event_id),
    native_attempt_id UUID NOT NULL UNIQUE,
    replacement_parent_id UUID,
    replacement_sequence INTEGER NOT NULL,
    network VARCHAR(32) NOT NULL,
    cluster_identity VARCHAR(88) NOT NULL,
    route_snapshot_ref VARCHAR(256) NOT NULL,
    token_program_id VARCHAR(44) NOT NULL,
    ata_program_id VARCHAR(44) NOT NULL,
    mint_address VARCHAR(44) NOT NULL,
    destination_owner VARCHAR(44) NOT NULL,
    destination_ata VARCHAR(44) NOT NULL,
    ata_existed BOOLEAN NOT NULL,
    decimals INTEGER NOT NULL,
    amount_atomic NUMERIC(20, 0) NOT NULL,
    pre_mint_supply NUMERIC(20, 0) NOT NULL,
    pre_destination_balance NUMERIC(20, 0) NOT NULL,
    fee_payer_public_key VARCHAR(44) NOT NULL,
    fee_payer_key_alias VARCHAR(128) NOT NULL,
    fee_payer_key_version VARCHAR(256) NOT NULL,
    mint_authority_public_key VARCHAR(44) NOT NULL,
    mint_authority_key_alias VARCHAR(128) NOT NULL,
    mint_authority_key_version VARCHAR(256) NOT NULL,
    policy_version VARCHAR(256) NOT NULL,
    maximum_fee_lamports NUMERIC(20, 0) NOT NULL,
    commitment VARCHAR(16) NOT NULL,
    recent_blockhash VARCHAR(44) NOT NULL,
    last_valid_block_height BIGINT NOT NULL,
    unsigned_transaction BYTEA NOT NULL,
    message_sha256 CHAR(64) NOT NULL,
    instruction_sha256 CHAR(64) NOT NULL,
    transaction_signature VARCHAR(88),
    attempt_status VARCHAR(32) NOT NULL,
    submit_fence BIGINT NOT NULL,
    submission_started_at TIMESTAMPTZ,
    submission_recorded_at TIMESTAMPTZ,
    submission_code VARCHAR(64),
    aggregate_version BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, operation_attempt_id),
    CONSTRAINT fk_solana_mint_common_attempt
        FOREIGN KEY (operation_id, operation_attempt_id)
        REFERENCES operation_attempt (operation_id, attempt_id),
    CONSTRAINT fk_solana_mint_replacement_parent
        FOREIGN KEY (replacement_parent_id)
        REFERENCES solana_mint_attempt (native_attempt_id),
    CONSTRAINT ck_solana_mint_lineage CHECK (
        (replacement_sequence = 0 AND replacement_parent_id IS NULL)
        OR (replacement_sequence > 0 AND replacement_parent_id IS NOT NULL)),
    CONSTRAINT ck_solana_mint_network CHECK (network = 'LOCAL_SOLANA'),
    CONSTRAINT ck_solana_mint_addresses CHECK (
        cluster_identity ~ '^[1-9A-HJ-NP-Za-km-z]{32,88}$'
        AND token_program_id ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND ata_program_id ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND destination_owner ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND destination_ata ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND fee_payer_public_key ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND mint_authority_public_key ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND recent_blockhash ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'),
    CONSTRAINT ck_solana_mint_quantity CHECK (
        decimals = 2
        AND amount_atomic BETWEEN 1 AND 18446744073709551615
        AND maximum_fee_lamports BETWEEN 1 AND 18446744073709551615
        AND pre_mint_supply BETWEEN 0 AND 18446744073709551615
        AND pre_destination_balance BETWEEN 0 AND 18446744073709551615),
    CONSTRAINT ck_solana_mint_message CHECK (
        octet_length(unsigned_transaction) BETWEEN 1 AND 1232
        AND message_sha256 ~ '^[0-9a-f]{64}$'
        AND instruction_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_solana_mint_commitment CHECK (commitment = 'finalized'),
    CONSTRAINT ck_solana_mint_status CHECK (attempt_status IN (
        'PREPARED', 'PARTIALLY_SIGNED', 'SIGNED', 'SUBMISSION_STARTED',
        'ACCEPTED', 'AMBIGUOUS', 'REJECTED', 'EXPIRED', 'CONFIRMED',
        'REVERTED', 'MISMATCHED')),
    CONSTRAINT ck_solana_mint_submission CHECK (
        submit_fence >= 0 AND aggregate_version >= 0
        AND ((attempt_status IN ('PREPARED', 'PARTIALLY_SIGNED', 'SIGNED')
                AND submission_started_at IS NULL)
            OR (attempt_status NOT IN ('PREPARED', 'PARTIALLY_SIGNED', 'SIGNED')
                AND submission_started_at IS NOT NULL))),
    CONSTRAINT ck_solana_mint_signature CHECK (
        transaction_signature IS NULL
        OR transaction_signature ~ '^[1-9A-HJ-NP-Za-km-z]{1,88}$'),
    CONSTRAINT ck_solana_mint_text CHECK (
        char_length(route_snapshot_ref) BETWEEN 1 AND 256
        AND char_length(fee_payer_key_alias) BETWEEN 1 AND 128
        AND char_length(fee_payer_key_version) BETWEEN 1 AND 256
        AND char_length(mint_authority_key_alias) BETWEEN 1 AND 128
        AND char_length(mint_authority_key_version) BETWEEN 1 AND 256
        AND char_length(policy_version) BETWEEN 1 AND 256),
    CONSTRAINT ck_solana_mint_times CHECK (updated_at >= created_at)
);

CREATE TABLE solana_mint_signature (
    operation_id UUID NOT NULL,
    operation_attempt_id UUID NOT NULL,
    signer_order INTEGER NOT NULL,
    key_role VARCHAR(32) NOT NULL,
    key_alias VARCHAR(128) NOT NULL,
    key_version VARCHAR(256) NOT NULL,
    public_key VARCHAR(44) NOT NULL,
    signature_bytes BYTEA NOT NULL,
    signature_sha256 CHAR(64) NOT NULL,
    signature_encoding VARCHAR(128) NOT NULL,
    retained_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, operation_attempt_id, signer_order),
    CONSTRAINT fk_solana_mint_signature_attempt
        FOREIGN KEY (operation_id, operation_attempt_id)
        REFERENCES solana_mint_attempt (operation_id, operation_attempt_id),
    CONSTRAINT uq_solana_mint_signature_role
        UNIQUE (operation_id, operation_attempt_id, key_role),
    CONSTRAINT ck_solana_mint_signature_order CHECK (signer_order IN (0, 1)),
    CONSTRAINT ck_solana_mint_signature_role CHECK (
        (signer_order = 0 AND key_role = 'FEE_PAYER')
        OR (signer_order = 1 AND key_role = 'MINT_AUTHORITY')),
    CONSTRAINT ck_solana_mint_signature_shape CHECK (
        public_key ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND octet_length(signature_bytes) = 64
        AND signature_sha256 ~ '^[0-9a-f]{64}$'
        AND char_length(signature_encoding) BETWEEN 1 AND 128),
    CONSTRAINT ck_solana_mint_signature_text CHECK (
        char_length(key_alias) BETWEEN 1 AND 128
        AND char_length(key_version) BETWEEN 1 AND 256)
);

CREATE TABLE solana_mint_observation (
    operation_id UUID NOT NULL,
    operation_attempt_id UUID NOT NULL,
    observation_sequence INTEGER NOT NULL,
    observation_status VARCHAR(32) NOT NULL,
    transaction_signature VARCHAR(88) NOT NULL,
    commitment VARCHAR(16) NOT NULL,
    slot BIGINT,
    block_time BIGINT,
    transaction_error_code VARCHAR(64),
    expected_instructions BOOLEAN NOT NULL,
    observed_mint_supply NUMERIC(20, 0),
    observed_destination_balance NUMERIC(20, 0),
    mint_delta NUMERIC(20, 0),
    destination_delta NUMERIC(20, 0),
    evidence_ref VARCHAR(256) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (operation_id, operation_attempt_id, observation_sequence),
    CONSTRAINT fk_solana_mint_observation_attempt
        FOREIGN KEY (operation_id, operation_attempt_id)
        REFERENCES solana_mint_attempt (operation_id, operation_attempt_id),
    CONSTRAINT ck_solana_mint_observation_status CHECK (observation_status IN (
        'ABSENT_OR_PENDING', 'CONFIRMED', 'REVERTED', 'MISMATCHED', 'EXPIRED')),
    CONSTRAINT ck_solana_mint_observation_signature CHECK (
        transaction_signature ~ '^[1-9A-HJ-NP-Za-km-z]{1,88}$'),
    CONSTRAINT ck_solana_mint_observation_commitment CHECK (
        commitment IN ('processed', 'confirmed', 'finalized')),
    CONSTRAINT ck_solana_mint_observation_numbers CHECK (
        observation_sequence > 0
        AND (slot IS NULL OR slot >= 0)
        AND (observed_mint_supply IS NULL
            OR observed_mint_supply BETWEEN 0 AND 18446744073709551615)
        AND (observed_destination_balance IS NULL
            OR observed_destination_balance BETWEEN 0 AND 18446744073709551615)
        AND (mint_delta IS NULL OR mint_delta BETWEEN 0 AND 18446744073709551615)
        AND (destination_delta IS NULL
            OR destination_delta BETWEEN 0 AND 18446744073709551615)),
    CONSTRAINT ck_solana_mint_observation_text CHECK (
        (transaction_error_code IS NULL
            OR char_length(transaction_error_code) BETWEEN 1 AND 64)
        AND char_length(evidence_ref) BETWEEN 1 AND 256)
);

CREATE INDEX idx_solana_mint_status
    ON solana_mint_attempt (attempt_status, updated_at);

CREATE UNIQUE INDEX uq_solana_mint_one_active_local_route
    ON solana_mint_attempt (network)
    WHERE attempt_status IN (
        'PREPARED', 'PARTIALLY_SIGNED', 'SIGNED', 'SUBMISSION_STARTED',
        'ACCEPTED', 'AMBIGUOUS', 'MISMATCHED');
