CREATE TABLE IF NOT EXISTS ethereum_redemption_correlation (
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

ALTER TABLE signing_request
    DROP CONSTRAINT ck_signing_action_role,
    ADD CONSTRAINT ck_signing_action_role CHECK (
        (action = 'MINT' AND (
            key_role = 'MINT_AUTHORITY'
            OR (key_role = 'FEE_PAYER' AND settlement_network = 'SOLANA')))
        OR (action = 'TRANSFER' AND (
            key_role = 'TRANSFER_AUTHORITY'
            OR (key_role = 'FEE_PAYER' AND settlement_network = 'SOLANA')))
        OR (action = 'BURN' AND (
            key_role = 'BURN_AUTHORITY'
            OR (key_role = 'FEE_PAYER' AND settlement_network = 'SOLANA'))));

ALTER TABLE solana_mint_attempt
    DROP CONSTRAINT ck_solana_native_effect,
    ADD COLUMN redemption_correlation_id UUID
        REFERENCES ethereum_redemption_correlation (correlation_id),
    ADD COLUMN redemption_registry_version VARCHAR(128),
    ADD CONSTRAINT ck_solana_native_effect CHECK (
        (effect_kind = 'MINT'
            AND token_operation_id = operation_id
            AND wallet_transfer_operation_id IS NULL
            AND redemption_correlation_id IS NULL
            AND redemption_registry_version IS NULL
            AND source_owner IS NULL AND source_ata IS NULL
            AND pre_source_balance IS NULL)
        OR (effect_kind = 'TRANSFER'
            AND token_operation_id IS NULL
            AND wallet_transfer_operation_id = operation_id
            AND redemption_correlation_id IS NULL
            AND redemption_registry_version IS NULL
            AND source_owner IS NOT NULL AND source_ata IS NOT NULL
            AND pre_source_balance IS NOT NULL)
        OR (effect_kind = 'BURN'
            AND token_operation_id = operation_id
            AND wallet_transfer_operation_id IS NULL
            AND redemption_correlation_id IS NOT NULL
            AND char_length(redemption_registry_version) BETWEEN 1 AND 128
            AND source_owner IS NOT NULL AND source_ata IS NOT NULL
            AND pre_source_balance IS NOT NULL
            AND destination_owner = source_owner
            AND destination_ata = source_ata
            AND ata_existed));

ALTER TABLE solana_mint_signature
    DROP CONSTRAINT ck_solana_mint_signature_role,
    ADD CONSTRAINT ck_solana_mint_signature_role CHECK (
        (signer_order = 0 AND key_role = 'FEE_PAYER')
        OR (signer_order = 1 AND effect_kind = 'MINT'
            AND key_role = 'MINT_AUTHORITY')
        OR (signer_order = 1 AND effect_kind = 'TRANSFER'
            AND key_role = 'TRANSFER_AUTHORITY')
        OR (signer_order = 1 AND effect_kind = 'BURN'
            AND key_role = 'BURN_AUTHORITY'));

ALTER TABLE solana_mint_observation
    DROP CONSTRAINT ck_solana_native_observation_effect,
    ADD CONSTRAINT ck_solana_native_observation_effect CHECK (
        (effect_kind = 'MINT'
            AND observed_source_balance IS NULL AND source_delta IS NULL
            AND transaction_pre_source_balance IS NULL
            AND transaction_post_source_balance IS NULL
            AND transaction_pre_destination_balance IS NULL
            AND transaction_post_destination_balance IS NULL)
        OR (effect_kind = 'TRANSFER'
            AND (observation_status <> 'CONFIRMED'
                OR (transaction_pre_source_balance IS NOT NULL
                    AND transaction_post_source_balance IS NOT NULL
                    AND transaction_pre_destination_balance IS NOT NULL
                    AND transaction_post_destination_balance IS NOT NULL)))
        OR (effect_kind = 'BURN'
            AND destination_delta IS NULL
            AND transaction_pre_destination_balance IS NULL
            AND transaction_post_destination_balance IS NULL
            AND (observation_status <> 'CONFIRMED'
                OR (observed_mint_supply IS NOT NULL
                    AND observed_source_balance IS NOT NULL
                    AND mint_delta IS NOT NULL
                    AND source_delta IS NOT NULL
                    AND transaction_pre_source_balance IS NOT NULL
                    AND transaction_post_source_balance IS NOT NULL))));
