CREATE TABLE settlement_instruction (
    instruction_id VARCHAR(128) NOT NULL,
    instruction_version VARCHAR(128) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    participant_id VARCHAR(128) NOT NULL,
    bank_id VARCHAR(64) NOT NULL,
    bank_account_id VARCHAR(64) NOT NULL,
    bank_account_reference VARCHAR(128) NOT NULL,
    wallet_reference VARCHAR(128) NOT NULL,
    instruction_mode VARCHAR(16) NOT NULL,
    currency VARCHAR(12) NOT NULL,
    settlement_network VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    expires_at TIMESTAMPTZ,
    PRIMARY KEY (instruction_id, instruction_version),
    UNIQUE (bank_account_reference, instruction_mode, instruction_version),
    CONSTRAINT ck_settlement_instruction_identity CHECK (
        instruction_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND instruction_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_settlement_instruction_route CHECK (
        bank_id ~ '^[A-Z0-9][A-Z0-9_]{0,63}$'
        AND bank_account_id ~ '^[A-Z0-9][A-Z0-9_]{0,63}$'
        AND bank_account_reference
            ~ '^synthetic-bank:[A-Za-z0-9][A-Za-z0-9._:-]{0,111}$'
        AND wallet_reference
            ~ '^synthetic-wallet:[A-Za-z0-9][A-Za-z0-9._:-]{0,110}$'),
    CONSTRAINT ck_settlement_instruction_mode
        CHECK (instruction_mode IN ('ACQUISITION', 'AUTO_REDEEM')),
    CONSTRAINT ck_settlement_instruction_currency
        CHECK (currency ~ '^[A-Z][A-Z0-9]{2,11}$'),
    CONSTRAINT ck_settlement_instruction_network
        CHECK (settlement_network = 'ETHEREUM'),
    CONSTRAINT ck_settlement_instruction_time CHECK (
        expires_at IS NULL OR expires_at > effective_at)
);

CREATE INDEX idx_settlement_instruction_sender
    ON settlement_instruction (
        tenant_id, participant_id, bank_account_reference,
        currency, settlement_network, enabled);

CREATE INDEX idx_settlement_instruction_recipient
    ON settlement_instruction (
        bank_account_reference, instruction_mode,
        currency, settlement_network, enabled);

CREATE OR REPLACE FUNCTION protect_settlement_instruction_version()
RETURNS TRIGGER AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'settlement instruction versions are append-only';
    END IF;
    IF ROW(
            NEW.instruction_id, NEW.instruction_version,
            NEW.tenant_id, NEW.participant_id,
            NEW.bank_id, NEW.bank_account_id,
            NEW.bank_account_reference, NEW.wallet_reference,
            NEW.instruction_mode, NEW.currency,
            NEW.settlement_network, NEW.effective_at)
        IS DISTINCT FROM ROW(
            OLD.instruction_id, OLD.instruction_version,
            OLD.tenant_id, OLD.participant_id,
            OLD.bank_id, OLD.bank_account_id,
            OLD.bank_account_reference, OLD.wallet_reference,
            OLD.instruction_mode, OLD.currency,
            OLD.settlement_network, OLD.effective_at) THEN
        RAISE EXCEPTION 'settlement instruction identity and routing are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER settlement_instruction_version_guard
    BEFORE UPDATE OR DELETE ON settlement_instruction
    FOR EACH ROW EXECUTE FUNCTION protect_settlement_instruction_version();

INSERT INTO settlement_instruction (
    instruction_id, instruction_version, tenant_id, participant_id,
    bank_id, bank_account_id, bank_account_reference, wallet_reference,
    instruction_mode, currency, settlement_network, enabled,
    effective_at, expires_at)
VALUES
    ('local-user-1-acquisition', 'phase-6c-v1', 'local-demo', 'USER_1',
     'BANK_1', 'USER_1_BANK_ACCOUNT',
     'synthetic-bank:USER_1_BANK_ACCOUNT', 'synthetic-wallet:USER_WALLET_1',
     'ACQUISITION', 'USD', 'ETHEREUM', TRUE,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL),
    ('local-user-2-auto-redeem', 'phase-6c-v1', 'local-demo', 'USER_2',
     'BANK_2', 'USER_2_BANK_ACCOUNT',
     'synthetic-bank:USER_2_BANK_ACCOUNT', 'synthetic-wallet:USER_WALLET_2',
     'AUTO_REDEEM', 'USD', 'ETHEREUM', TRUE,
     TIMESTAMPTZ '2026-01-01 00:00:00+00', NULL);

CREATE TABLE settlement_transfer (
    transfer_id UUID PRIMARY KEY REFERENCES banking_transfer (transfer_id),
    workflow_version VARCHAR(128) NOT NULL,
    amount_cents NUMERIC(18, 0) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    unit_max_atomic NUMERIC(512, 0) NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    sender_instruction_id VARCHAR(128) NOT NULL,
    sender_instruction_version VARCHAR(128) NOT NULL,
    sender_tenant_id VARCHAR(128) NOT NULL,
    sender_participant_id VARCHAR(128) NOT NULL,
    sender_bank_id VARCHAR(64) NOT NULL,
    sender_bank_account_id VARCHAR(64) NOT NULL,
    sender_bank_account_reference VARCHAR(128) NOT NULL,
    sender_wallet_reference VARCHAR(128) NOT NULL,
    sender_wallet_metadata_version VARCHAR(128) NOT NULL,
    recipient_instruction_id VARCHAR(128) NOT NULL,
    recipient_instruction_version VARCHAR(128) NOT NULL,
    recipient_tenant_id VARCHAR(128) NOT NULL,
    recipient_participant_id VARCHAR(128) NOT NULL,
    recipient_bank_id VARCHAR(64) NOT NULL,
    recipient_bank_account_id VARCHAR(64) NOT NULL,
    recipient_bank_account_reference VARCHAR(128) NOT NULL,
    recipient_wallet_reference VARCHAR(128) NOT NULL,
    recipient_wallet_metadata_version VARCHAR(128) NOT NULL,
    admin_wallet_reference VARCHAR(128) NOT NULL,
    admin_wallet_metadata_version VARCHAR(128) NOT NULL,
    settlement_network VARCHAR(32) NOT NULL,
    contract_reference VARCHAR(128) NOT NULL,
    payout_policy_version VARCHAR(128) NOT NULL,
    conversion_policy_version VARCHAR(128) NOT NULL,
    accounting_policy_version VARCHAR(128) NOT NULL,
    fee_policy_version VARCHAR(128) NOT NULL,
    finality_policy_version VARCHAR(128) NOT NULL,
    reconciliation_policy_version VARCHAR(128) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    parent_status VARCHAR(48) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_settlement_sender_instruction FOREIGN KEY (
        sender_instruction_id, sender_instruction_version)
        REFERENCES settlement_instruction (instruction_id, instruction_version),
    CONSTRAINT fk_settlement_recipient_instruction FOREIGN KEY (
        recipient_instruction_id, recipient_instruction_version)
        REFERENCES settlement_instruction (instruction_id, instruction_version),
    CONSTRAINT ck_settlement_transfer_amount CHECK (
        amount_cents BETWEEN 1 AND 999999999999999999
        AND quantity_atomic = amount_cents
        AND quantity_atomic <= unit_max_atomic),
    CONSTRAINT ck_settlement_transfer_unit CHECK (
        unit_version > 0 AND unit_scale BETWEEN 0 AND 255
        AND unit_max_atomic > 0),
    CONSTRAINT ck_settlement_transfer_route CHECK (
        sender_tenant_id <> recipient_tenant_id
        OR sender_participant_id <> recipient_participant_id),
    CONSTRAINT ck_settlement_transfer_network
        CHECK (settlement_network = 'ETHEREUM'),
    CONSTRAINT ck_settlement_transfer_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_settlement_transfer_status CHECK (parent_status IN (
        'ACCEPTED',
        'SENDER_ACQUISITION_PENDING', 'SENDER_ACQUISITION_UNKNOWN',
        'SENDER_ACQUISITION_COMPLETED',
        'USER_TRANSFER_PENDING', 'USER_TRANSFER_SUBMISSION_UNKNOWN',
        'USER_TRANSFER_COMPLETED',
        'RECIPIENT_REDEMPTION_PENDING', 'RECIPIENT_REDEMPTION_UNKNOWN',
        'RECIPIENT_REDEMPTION_COMPLETED',
        'FINAL_RECONCILIATION_PENDING',
        'COMPLETED', 'FAILED_NO_EFFECT', 'MANUAL_REVIEW')),
    CONSTRAINT ck_settlement_transfer_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_settlement_transfer_time CHECK (updated_at >= accepted_at)
);

CREATE INDEX idx_settlement_transfer_sender
    ON settlement_transfer (
        sender_tenant_id, sender_participant_id, transfer_id);

CREATE TABLE settlement_transfer_boundary (
    transfer_id UUID NOT NULL REFERENCES settlement_transfer (transfer_id),
    boundary_sequence INTEGER NOT NULL,
    boundary_id UUID NOT NULL UNIQUE,
    boundary_kind VARCHAR(32) NOT NULL,
    boundary_status VARCHAR(16) NOT NULL,
    child_reference VARCHAR(128),
    evidence_reference VARCHAR(256),
    PRIMARY KEY (transfer_id, boundary_sequence),
    UNIQUE (transfer_id, boundary_kind),
    CONSTRAINT ck_settlement_boundary_sequence
        CHECK (boundary_sequence BETWEEN 1 AND 4),
    CONSTRAINT ck_settlement_boundary_kind CHECK (boundary_kind IN (
        'SENDER_ACQUISITION', 'USER_TRANSFER',
        'RECIPIENT_REDEMPTION', 'FINAL_RECONCILIATION')),
    CONSTRAINT ck_settlement_boundary_status CHECK (boundary_status IN (
        'PENDING', 'ELIGIBLE', 'ACTIVE', 'UNKNOWN', 'COMPLETED',
        'FAILED_NO_EFFECT', 'MANUAL_REVIEW')),
    CONSTRAINT ck_settlement_boundary_child CHECK (
        child_reference IS NULL
        OR child_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_settlement_boundary_reconciliation CHECK (
        boundary_kind <> 'FINAL_RECONCILIATION' OR child_reference IS NULL)
);

CREATE UNIQUE INDEX uq_settlement_transfer_child
    ON settlement_transfer_boundary (child_reference)
    WHERE child_reference IS NOT NULL;

CREATE TABLE settlement_transfer_transition (
    transfer_id UUID NOT NULL REFERENCES settlement_transfer (transfer_id),
    aggregate_version BIGINT NOT NULL,
    transition_id UUID NOT NULL UNIQUE,
    from_status VARCHAR(48) NOT NULL,
    to_status VARCHAR(48) NOT NULL,
    boundary_id UUID,
    evidence_reference VARCHAR(256) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (transfer_id, aggregate_version),
    CONSTRAINT fk_settlement_transition_boundary FOREIGN KEY (boundary_id)
        REFERENCES settlement_transfer_boundary (boundary_id),
    CONSTRAINT ck_settlement_transition_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_settlement_transition_status CHECK (
        from_status IN (
            'ACCEPTED',
            'SENDER_ACQUISITION_PENDING', 'SENDER_ACQUISITION_UNKNOWN',
            'SENDER_ACQUISITION_COMPLETED',
            'USER_TRANSFER_PENDING', 'USER_TRANSFER_SUBMISSION_UNKNOWN',
            'USER_TRANSFER_COMPLETED',
            'RECIPIENT_REDEMPTION_PENDING', 'RECIPIENT_REDEMPTION_UNKNOWN',
            'RECIPIENT_REDEMPTION_COMPLETED',
            'FINAL_RECONCILIATION_PENDING',
            'COMPLETED', 'FAILED_NO_EFFECT', 'MANUAL_REVIEW')
        AND to_status IN (
            'ACCEPTED',
            'SENDER_ACQUISITION_PENDING', 'SENDER_ACQUISITION_UNKNOWN',
            'SENDER_ACQUISITION_COMPLETED',
            'USER_TRANSFER_PENDING', 'USER_TRANSFER_SUBMISSION_UNKNOWN',
            'USER_TRANSFER_COMPLETED',
            'RECIPIENT_REDEMPTION_PENDING', 'RECIPIENT_REDEMPTION_UNKNOWN',
            'RECIPIENT_REDEMPTION_COMPLETED',
            'FINAL_RECONCILIATION_PENDING',
            'COMPLETED', 'FAILED_NO_EFFECT', 'MANUAL_REVIEW')),
    CONSTRAINT ck_settlement_transition_evidence
        CHECK (char_length(evidence_reference) BETWEEN 1 AND 256)
);

CREATE TABLE settlement_transfer_conclusion (
    transfer_id UUID PRIMARY KEY REFERENCES settlement_transfer (transfer_id),
    reconciliation_status VARCHAR(48) NOT NULL,
    completed BOOLEAN NOT NULL,
    evidence_reference VARCHAR(256) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_settlement_conclusion_status CHECK (
        reconciliation_status IN (
            'RECONCILED', 'RESERVE_LEDGER_MISMATCH',
            'CHAIN_SUPPLY_MISMATCH', 'EVIDENCE_INCOMPLETE',
            'UNSUPPORTED_OR_STALE_OBSERVATION')),
    CONSTRAINT ck_settlement_conclusion_completed CHECK (
        completed = (reconciliation_status = 'RECONCILED'))
);

ALTER TABLE operation_outbox
    DROP CONSTRAINT ck_operation_outbox_aggregate;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = current_schema()
          AND table_name = 'operation_outbox'
          AND column_name = 'wallet_transfer_id') THEN
        EXECUTE $constraint$
            ALTER TABLE operation_outbox
            ADD CONSTRAINT ck_operation_outbox_aggregate CHECK (
                (operation_id IS NOT NULL AND transfer_id IS NULL
                    AND wallet_transfer_id IS NULL AND workflow_id IS NULL
                    AND event_type = 'TokenOperationAccepted')
                OR (operation_id IS NULL AND transfer_id IS NOT NULL
                    AND wallet_transfer_id IS NULL AND workflow_id IS NULL
                    AND event_type IN (
                        'TransferAccepted', 'SettlementTransferAccepted'))
                OR (operation_id IS NULL AND transfer_id IS NULL
                    AND wallet_transfer_id IS NOT NULL AND workflow_id IS NULL
                    AND event_type = 'WalletTransferAccepted')
                OR (operation_id IS NULL AND transfer_id IS NULL
                    AND wallet_transfer_id IS NULL AND workflow_id IS NOT NULL
                    AND event_type = 'UsdzelleWorkflowAccepted'))
        $constraint$;
    ELSE
        ALTER TABLE operation_outbox
            ADD CONSTRAINT ck_operation_outbox_aggregate CHECK (
                (operation_id IS NOT NULL AND transfer_id IS NULL
                    AND workflow_id IS NULL
                    AND event_type = 'TokenOperationAccepted')
                OR (operation_id IS NULL AND transfer_id IS NOT NULL
                    AND workflow_id IS NULL
                    AND event_type IN (
                        'TransferAccepted', 'SettlementTransferAccepted'))
                OR (operation_id IS NULL AND transfer_id IS NULL
                    AND workflow_id IS NOT NULL
                    AND event_type = 'UsdzelleWorkflowAccepted'));
    END IF;
END $$;

CREATE UNIQUE INDEX uq_operation_outbox_settlement_transfer
    ON operation_outbox (transfer_id, event_type, event_version)
    WHERE transfer_id IS NOT NULL
      AND event_type = 'SettlementTransferAccepted';
