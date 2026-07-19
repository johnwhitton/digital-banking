ALTER TABLE wallet_transfer_operation
    ADD COLUMN IF NOT EXISTS transfer_purpose VARCHAR(32) NOT NULL
        DEFAULT 'USER_TRANSFER';

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'ck_wallet_transfer_purpose'
          AND conrelid = 'wallet_transfer_operation'::regclass) THEN
        ALTER TABLE wallet_transfer_operation
            ADD CONSTRAINT ck_wallet_transfer_purpose CHECK (
                transfer_purpose IN ('USER_TRANSFER', 'REDEMPTION_CUSTODY'));
    END IF;
END $$;

ALTER TABLE wallet_transfer_operation
    ALTER COLUMN transfer_purpose DROP DEFAULT;

ALTER TABLE wallet_transfer_operation
    DROP CONSTRAINT ck_wallet_transfer_wallets,
    DROP CONSTRAINT ck_wallet_transfer_route,
    ALTER COLUMN source_address TYPE VARCHAR(44),
    ALTER COLUMN destination_address TYPE VARCHAR(44),
    ALTER COLUMN contract_address TYPE VARCHAR(44),
    ADD CONSTRAINT ck_wallet_transfer_wallets CHECK (
        source_wallet_ref ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}$'
        AND destination_wallet_ref ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,109}$'
        AND source_wallet_ref <> destination_wallet_ref
        AND source_address <> destination_address
        AND ((network = 'ETHEREUM'
                AND source_address ~ '^0x[0-9a-f]{40}$'
                AND destination_address ~ '^0x[0-9a-f]{40}$')
            OR (network = 'SOLANA'
                AND source_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
                AND destination_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'))),
    ADD CONSTRAINT ck_wallet_transfer_route CHECK (
        ((network = 'ETHEREUM' AND contract_address ~ '^0x[0-9a-f]{40}$')
            OR (network = 'SOLANA'
                AND contract_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'))
        AND char_length(contract_version) BETWEEN 1 AND 128
        AND char_length(finality_policy_version) BETWEEN 1 AND 128);

ALTER TABLE signing_request
    DROP CONSTRAINT ck_signing_action_role,
    ADD CONSTRAINT ck_signing_action_role CHECK (
        (action = 'MINT' AND (
            key_role = 'MINT_AUTHORITY'
            OR (key_role = 'FEE_PAYER' AND settlement_network = 'SOLANA')))
        OR (action = 'TRANSFER' AND (
            key_role = 'TRANSFER_AUTHORITY'
            OR (key_role = 'FEE_PAYER' AND settlement_network = 'SOLANA')))
        OR (action = 'BURN' AND key_role = 'BURN_AUTHORITY'));

ALTER TABLE solana_mint_attempt
    DROP CONSTRAINT solana_mint_attempt_operation_id_fkey,
    DROP CONSTRAINT fk_solana_mint_common_attempt,
    DROP CONSTRAINT ck_solana_mint_addresses,
    DROP CONSTRAINT ck_solana_mint_quantity,
    ADD COLUMN effect_kind VARCHAR(16) NOT NULL DEFAULT 'MINT',
    ADD COLUMN token_operation_id UUID,
    ADD COLUMN wallet_transfer_operation_id UUID,
    ADD COLUMN source_owner VARCHAR(44),
    ADD COLUMN source_ata VARCHAR(44),
    ADD COLUMN pre_source_balance NUMERIC(20, 0);

UPDATE solana_mint_attempt
SET token_operation_id = operation_id;

ALTER TABLE solana_mint_attempt
    ALTER COLUMN effect_kind DROP DEFAULT,
    ADD CONSTRAINT fk_solana_native_token_operation
        FOREIGN KEY (token_operation_id) REFERENCES token_operation (operation_id),
    ADD CONSTRAINT fk_solana_native_wallet_transfer
        FOREIGN KEY (wallet_transfer_operation_id)
        REFERENCES wallet_transfer_operation (operation_id),
    ADD CONSTRAINT ck_solana_native_effect CHECK (
        (effect_kind = 'MINT'
            AND token_operation_id = operation_id
            AND wallet_transfer_operation_id IS NULL
            AND source_owner IS NULL AND source_ata IS NULL
            AND pre_source_balance IS NULL)
        OR (effect_kind = 'TRANSFER'
            AND token_operation_id IS NULL
            AND wallet_transfer_operation_id = operation_id
            AND source_owner IS NOT NULL AND source_ata IS NOT NULL
            AND pre_source_balance IS NOT NULL)),
    ADD CONSTRAINT ck_solana_mint_addresses CHECK (
        cluster_identity ~ '^[1-9A-HJ-NP-Za-km-z]{32,88}$'
        AND token_program_id ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND ata_program_id ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND mint_address ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND destination_owner ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND destination_ata ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND fee_payer_public_key ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND mint_authority_public_key ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND recent_blockhash ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$'
        AND (source_owner IS NULL
            OR source_owner ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')
        AND (source_ata IS NULL
            OR source_ata ~ '^[1-9A-HJ-NP-Za-km-z]{32,44}$')),
    ADD CONSTRAINT ck_solana_mint_quantity CHECK (
        decimals = 2
        AND amount_atomic BETWEEN 1 AND 18446744073709551615
        AND maximum_fee_lamports BETWEEN 1 AND 18446744073709551615
        AND pre_mint_supply BETWEEN 0 AND 18446744073709551615
        AND pre_destination_balance BETWEEN 0 AND 18446744073709551615
        AND (pre_source_balance IS NULL
            OR pre_source_balance BETWEEN 0 AND 18446744073709551615)),
    ADD CONSTRAINT uq_solana_native_effect
        UNIQUE (operation_id, operation_attempt_id, effect_kind);

ALTER TABLE solana_mint_signature
    DROP CONSTRAINT fk_solana_mint_signature_attempt,
    DROP CONSTRAINT ck_solana_mint_signature_role,
    ADD COLUMN effect_kind VARCHAR(16) NOT NULL DEFAULT 'MINT';

ALTER TABLE solana_mint_signature
    ALTER COLUMN effect_kind DROP DEFAULT,
    ADD CONSTRAINT fk_solana_mint_signature_attempt
        FOREIGN KEY (operation_id, operation_attempt_id, effect_kind)
        REFERENCES solana_mint_attempt (
            operation_id, operation_attempt_id, effect_kind),
    ADD CONSTRAINT ck_solana_mint_signature_role CHECK (
        (signer_order = 0 AND key_role = 'FEE_PAYER')
        OR (signer_order = 1 AND effect_kind = 'MINT'
            AND key_role = 'MINT_AUTHORITY')
        OR (signer_order = 1 AND effect_kind = 'TRANSFER'
            AND key_role = 'TRANSFER_AUTHORITY'));

ALTER TABLE solana_mint_observation
    DROP CONSTRAINT fk_solana_mint_observation_attempt,
    DROP CONSTRAINT ck_solana_mint_observation_numbers,
    ADD COLUMN effect_kind VARCHAR(16) NOT NULL DEFAULT 'MINT',
    ADD COLUMN observed_source_balance NUMERIC(20, 0),
    ADD COLUMN source_delta NUMERIC(20, 0),
    ADD COLUMN transaction_pre_source_balance NUMERIC(20, 0),
    ADD COLUMN transaction_post_source_balance NUMERIC(20, 0),
    ADD COLUMN transaction_pre_destination_balance NUMERIC(20, 0),
    ADD COLUMN transaction_post_destination_balance NUMERIC(20, 0);

ALTER TABLE solana_mint_observation
    ALTER COLUMN effect_kind DROP DEFAULT,
    ADD CONSTRAINT fk_solana_mint_observation_attempt
        FOREIGN KEY (operation_id, operation_attempt_id, effect_kind)
        REFERENCES solana_mint_attempt (
            operation_id, operation_attempt_id, effect_kind),
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
                    AND transaction_post_destination_balance IS NOT NULL)))),
    ADD CONSTRAINT ck_solana_mint_observation_numbers CHECK (
        observation_sequence > 0
        AND (slot IS NULL OR slot >= 0)
        AND (observed_mint_supply IS NULL
            OR observed_mint_supply BETWEEN 0 AND 18446744073709551615)
        AND (observed_destination_balance IS NULL
            OR observed_destination_balance BETWEEN 0 AND 18446744073709551615)
        AND (mint_delta IS NULL OR mint_delta BETWEEN 0 AND 18446744073709551615)
        AND (destination_delta IS NULL
            OR destination_delta BETWEEN 0 AND 18446744073709551615)
        AND (observed_source_balance IS NULL
            OR observed_source_balance BETWEEN 0 AND 18446744073709551615)
        AND (source_delta IS NULL
            OR source_delta BETWEEN 0 AND 18446744073709551615)
        AND (transaction_pre_source_balance IS NULL
            OR transaction_pre_source_balance BETWEEN 0 AND 18446744073709551615)
        AND (transaction_post_source_balance IS NULL
            OR transaction_post_source_balance BETWEEN 0 AND 18446744073709551615)
        AND (transaction_pre_destination_balance IS NULL
            OR transaction_pre_destination_balance BETWEEN 0 AND 18446744073709551615)
        AND (transaction_post_destination_balance IS NULL
            OR transaction_post_destination_balance BETWEEN 0 AND 18446744073709551615));
