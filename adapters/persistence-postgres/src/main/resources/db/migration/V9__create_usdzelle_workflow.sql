CREATE TABLE usdzelle_workflow (
    workflow_id UUID PRIMARY KEY,
    workflow_kind VARCHAR(16) NOT NULL,
    tenant_id VARCHAR(128) NOT NULL,
    participant_id VARCHAR(128) NOT NULL,
    workflow_policy_version VARCHAR(128) NOT NULL,
    amount_cents NUMERIC(18, 0) NOT NULL,
    asset_id VARCHAR(64) NOT NULL,
    unit_id VARCHAR(64) NOT NULL,
    unit_version INTEGER NOT NULL,
    unit_scale INTEGER NOT NULL,
    unit_max_atomic NUMERIC(512, 0) NOT NULL,
    quantity_atomic NUMERIC(512, 0) NOT NULL,
    bank_id VARCHAR(64) NOT NULL,
    bank_account_id VARCHAR(64) NOT NULL,
    user_wallet_reference VARCHAR(128) NOT NULL,
    user_wallet_metadata_version VARCHAR(128) NOT NULL,
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
    workflow_status VARCHAR(48) NOT NULL,
    aggregate_version BIGINT NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_usdzelle_workflow_kind
        CHECK (workflow_kind IN ('ACQUISITION', 'REDEMPTION')),
    CONSTRAINT ck_usdzelle_workflow_owner CHECK (
        tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_usdzelle_workflow_amount CHECK (
        amount_cents BETWEEN 1 AND 999999999999999999
        AND quantity_atomic = amount_cents
        AND quantity_atomic <= unit_max_atomic),
    CONSTRAINT ck_usdzelle_workflow_unit CHECK (
        unit_version > 0 AND unit_scale BETWEEN 0 AND 255
        AND unit_max_atomic > 0),
    CONSTRAINT ck_usdzelle_workflow_network
        CHECK (settlement_network = 'ETHEREUM'),
    CONSTRAINT ck_usdzelle_workflow_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_usdzelle_workflow_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_usdzelle_workflow_time CHECK (updated_at >= accepted_at)
);

CREATE INDEX idx_usdzelle_workflow_participant
    ON usdzelle_workflow (tenant_id, participant_id, workflow_kind, workflow_id);

CREATE TABLE usdzelle_workflow_idempotency (
    tenant_id VARCHAR(128) NOT NULL,
    participant_id VARCHAR(128) NOT NULL,
    workflow_kind VARCHAR(16) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    workflow_id UUID NOT NULL UNIQUE REFERENCES usdzelle_workflow (workflow_id)
        DEFERRABLE INITIALLY DEFERRED,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        tenant_id, participant_id, workflow_kind, idempotency_key_digest),
    CONSTRAINT ck_usdzelle_idempotency_kind
        CHECK (workflow_kind IN ('ACQUISITION', 'REDEMPTION')),
    CONSTRAINT ck_usdzelle_idempotency_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND request_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE usdzelle_workflow_step (
    workflow_id UUID NOT NULL REFERENCES usdzelle_workflow (workflow_id),
    step_sequence INTEGER NOT NULL,
    step_id UUID NOT NULL UNIQUE,
    step_kind VARCHAR(48) NOT NULL,
    step_status VARCHAR(16) NOT NULL,
    child_reference VARCHAR(128),
    evidence_reference VARCHAR(256),
    PRIMARY KEY (workflow_id, step_sequence),
    CONSTRAINT ck_usdzelle_step_sequence CHECK (step_sequence > 0),
    CONSTRAINT ck_usdzelle_step_kind CHECK (step_kind IN (
        'WITHDRAWAL', 'RESERVE_FUNDING_POST', 'MINT',
        'MINT_ACCOUNTING_POST', 'CUSTODY_TRANSFER',
        'CUSTODY_ACCOUNTING_POST', 'PAYOUT',
        'PAYOUT_ACCOUNTING_POST', 'BURN', 'RECONCILIATION')),
    CONSTRAINT ck_usdzelle_step_status CHECK (step_status IN (
        'PENDING', 'ELIGIBLE', 'ACTIVE', 'UNKNOWN', 'COMPLETED',
        'FAILED_NO_EFFECT', 'MANUAL_REVIEW')),
    CONSTRAINT ck_usdzelle_step_reference CHECK (
        child_reference IS NULL
        OR child_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_usdzelle_step_evidence CHECK (
        evidence_reference IS NULL
        OR char_length(evidence_reference) BETWEEN 1 AND 256)
);

CREATE TABLE usdzelle_workflow_transition (
    workflow_id UUID NOT NULL REFERENCES usdzelle_workflow (workflow_id),
    aggregate_version BIGINT NOT NULL,
    transition_id UUID NOT NULL UNIQUE,
    from_status VARCHAR(48) NOT NULL,
    to_status VARCHAR(48) NOT NULL,
    step_id UUID,
    evidence_reference VARCHAR(256) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workflow_id, aggregate_version),
    CONSTRAINT fk_usdzelle_transition_step FOREIGN KEY (step_id)
        REFERENCES usdzelle_workflow_step (step_id),
    CONSTRAINT ck_usdzelle_transition_version CHECK (aggregate_version >= 0),
    CONSTRAINT ck_usdzelle_transition_evidence
        CHECK (char_length(evidence_reference) BETWEEN 1 AND 256)
);

CREATE TABLE usdzelle_workflow_child (
    workflow_id UUID NOT NULL,
    step_id UUID NOT NULL,
    child_reference VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workflow_id, step_id),
    CONSTRAINT fk_usdzelle_child_step FOREIGN KEY (step_id)
        REFERENCES usdzelle_workflow_step (step_id),
    CONSTRAINT fk_usdzelle_child_workflow FOREIGN KEY (workflow_id)
        REFERENCES usdzelle_workflow (workflow_id),
    CONSTRAINT ck_usdzelle_child_reference
        CHECK (child_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE TABLE usdzelle_workflow_evidence (
    workflow_id UUID NOT NULL,
    aggregate_version BIGINT NOT NULL,
    evidence_reference VARCHAR(256) NOT NULL,
    PRIMARY KEY (workflow_id, aggregate_version, evidence_reference),
    CONSTRAINT fk_usdzelle_evidence_transition
        FOREIGN KEY (workflow_id, aggregate_version)
        REFERENCES usdzelle_workflow_transition (workflow_id, aggregate_version),
    CONSTRAINT ck_usdzelle_evidence_reference
        CHECK (char_length(evidence_reference) BETWEEN 1 AND 256)
);

CREATE TABLE usdzelle_workflow_conclusion (
    workflow_id UUID PRIMARY KEY REFERENCES usdzelle_workflow (workflow_id),
    reconciliation_status VARCHAR(48) NOT NULL,
    completed BOOLEAN NOT NULL,
    evidence_reference VARCHAR(256) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_usdzelle_conclusion_status CHECK (reconciliation_status IN (
        'RECONCILED', 'RESERVE_LEDGER_MISMATCH', 'CHAIN_SUPPLY_MISMATCH',
        'EVIDENCE_INCOMPLETE', 'UNSUPPORTED_OR_STALE_OBSERVATION')),
    CONSTRAINT ck_usdzelle_conclusion_result CHECK (
        completed = (reconciliation_status = 'RECONCILED')),
    CONSTRAINT ck_usdzelle_conclusion_evidence
        CHECK (char_length(evidence_reference) BETWEEN 1 AND 256)
);

CREATE TABLE usdzelle_chain_state_observation (
    workflow_id UUID NOT NULL REFERENCES usdzelle_workflow (workflow_id),
    step_id UUID NOT NULL REFERENCES usdzelle_workflow_step (step_id),
    effect_type VARCHAR(32) NOT NULL,
    child_operation_id UUID NOT NULL,
    evidence_reference VARCHAR(256) NOT NULL,
    block_number NUMERIC(78, 0) NOT NULL,
    block_hash CHAR(66) NOT NULL,
    user_balance_atomic NUMERIC(512, 0) NOT NULL,
    admin_balance_atomic NUMERIC(512, 0) NOT NULL,
    total_supply_atomic NUMERIC(512, 0) NOT NULL,
    observed_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (workflow_id, step_id),
    UNIQUE (effect_type, child_operation_id),
    CONSTRAINT ck_usdzelle_chain_effect CHECK (effect_type IN (
        'MINT', 'REDEMPTION_CUSTODY', 'BURN')),
    CONSTRAINT ck_usdzelle_chain_block CHECK (
        block_number >= 0 AND block_hash ~ '^0x[0-9a-f]{64}$'),
    CONSTRAINT ck_usdzelle_chain_balances CHECK (
        user_balance_atomic >= 0 AND admin_balance_atomic >= 0
        AND total_supply_atomic >= 0),
    CONSTRAINT ck_usdzelle_chain_evidence
        CHECK (char_length(evidence_reference) BETWEEN 1 AND 256)
);

ALTER TABLE operation_outbox
    ADD COLUMN workflow_id UUID REFERENCES usdzelle_workflow (workflow_id);

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
                    AND event_type = 'TransferAccepted')
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
                    AND event_type = 'TransferAccepted')
                OR (operation_id IS NULL AND transfer_id IS NULL
                    AND workflow_id IS NOT NULL
                    AND event_type = 'UsdzelleWorkflowAccepted'));
    END IF;
END $$;

CREATE UNIQUE INDEX uq_operation_outbox_usdzelle_workflow
    ON operation_outbox (workflow_id, event_type, event_version)
    WHERE workflow_id IS NOT NULL;
