ALTER TABLE usdzelle_workflow
    DROP CONSTRAINT ck_usdzelle_workflow_network,
    ADD CONSTRAINT ck_usdzelle_workflow_network
        CHECK (settlement_network IN ('ETHEREUM', 'SOLANA'));

ALTER TABLE settlement_instruction
    DROP CONSTRAINT ck_settlement_instruction_network,
    ADD CONSTRAINT ck_settlement_instruction_network
        CHECK (settlement_network IN ('ETHEREUM', 'SOLANA'));

ALTER TABLE settlement_transfer
    DROP CONSTRAINT ck_settlement_transfer_network,
    ADD CONSTRAINT ck_settlement_transfer_network
        CHECK (settlement_network IN ('ETHEREUM', 'SOLANA'));

INSERT INTO settlement_instruction (
    instruction_id, instruction_version, tenant_id, participant_id,
    bank_id, bank_account_id, bank_account_reference, wallet_reference,
    instruction_mode, currency, settlement_network, enabled,
    effective_at, expires_at)
VALUES
    ('local-user-1-acquisition', 'phase-7e-solana-v1', 'local-demo', 'USER_1',
     'BANK_1', 'USER_1_BANK_ACCOUNT',
     'synthetic-bank:USER_1_BANK_ACCOUNT', 'synthetic-wallet:USER_WALLET_1',
     'ACQUISITION', 'USD', 'SOLANA', TRUE,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL),
    ('local-user-2-auto-redeem', 'phase-7e-solana-v1', 'local-demo', 'USER_2',
     'BANK_2', 'USER_2_BANK_ACCOUNT',
     'synthetic-bank:USER_2_BANK_ACCOUNT', 'synthetic-wallet:USER_WALLET_2',
     'AUTO_REDEEM', 'USD', 'SOLANA', TRUE,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL);

ALTER TABLE accounting_confirmed_evidence
    ADD COLUMN settlement_network VARCHAR(16) NOT NULL DEFAULT 'ETHEREUM',
    ADD CONSTRAINT ck_accounting_confirmed_evidence_network
        CHECK (settlement_network IN ('ETHEREUM', 'SOLANA'));

ALTER TABLE accounting_confirmed_evidence
    ALTER COLUMN settlement_network DROP DEFAULT;

ALTER TABLE usdzelle_chain_state_observation
    ADD COLUMN settlement_network VARCHAR(16) NOT NULL DEFAULT 'ETHEREUM',
    ALTER COLUMN block_hash TYPE VARCHAR(88) USING btrim(block_hash),
    DROP CONSTRAINT ck_usdzelle_chain_block,
    ADD CONSTRAINT ck_usdzelle_chain_network
        CHECK (settlement_network IN ('ETHEREUM', 'SOLANA')),
    ADD CONSTRAINT ck_usdzelle_chain_block CHECK (
        block_number >= 0 AND (
            (settlement_network = 'ETHEREUM'
                AND block_hash ~ '^0x[0-9a-f]{64}$')
            OR (settlement_network = 'SOLANA'
                AND block_hash ~ '^[1-9A-HJ-NP-Za-km-z]{32,88}$')));

ALTER TABLE usdzelle_chain_state_observation
    ALTER COLUMN settlement_network DROP DEFAULT;
