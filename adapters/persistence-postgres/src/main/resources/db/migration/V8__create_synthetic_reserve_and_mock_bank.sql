CREATE TABLE synthetic_bank (
    bank_id VARCHAR(64) PRIMARY KEY,
    enabled BOOLEAN NOT NULL,
    fixture_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_synthetic_bank_id CHECK (bank_id ~ '^[A-Z0-9][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_synthetic_bank_fixture
        CHECK (fixture_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE TABLE synthetic_bank_account (
    bank_id VARCHAR(64) NOT NULL REFERENCES synthetic_bank (bank_id),
    account_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    currency VARCHAR(3) NOT NULL,
    balance_cents NUMERIC(18, 0) NOT NULL,
    initial_balance_cents NUMERIC(18, 0) NOT NULL,
    enabled BOOLEAN NOT NULL,
    account_version BIGINT NOT NULL,
    fixture_version VARCHAR(128) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (bank_id, account_id),
    CONSTRAINT ck_synthetic_account_id
        CHECK (account_id ~ '^[A-Z0-9][A-Z0-9_]{0,63}$'),
    CONSTRAINT ck_synthetic_account_owner CHECK (
        tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        AND participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_synthetic_account_currency CHECK (currency = 'USD'),
    CONSTRAINT ck_synthetic_account_balances CHECK (
        balance_cents BETWEEN 0 AND 999999999999999999
        AND initial_balance_cents BETWEEN 0 AND 999999999999999999),
    CONSTRAINT ck_synthetic_account_version CHECK (account_version >= 0),
    CONSTRAINT ck_synthetic_account_fixture
        CHECK (fixture_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_synthetic_account_times CHECK (updated_at >= created_at)
);

CREATE INDEX idx_synthetic_account_owner
    ON synthetic_bank_account (tenant_id, participant_id, bank_id, account_id);

CREATE TABLE synthetic_bank_operation (
    operation_id UUID PRIMARY KEY,
    evidence_id UUID NOT NULL UNIQUE,
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    bank_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    operation_status VARCHAR(16) NOT NULL,
    amount_cents NUMERIC(18, 0) NOT NULL,
    balance_after_cents NUMERIC(18, 0),
    idempotency_key_digest CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    correlation_id VARCHAR(128) NOT NULL,
    policy_version VARCHAR(128) NOT NULL,
    safe_failure_code VARCHAR(128),
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_synthetic_bank_operation_account
        FOREIGN KEY (bank_id, account_id)
        REFERENCES synthetic_bank_account (bank_id, account_id),
    CONSTRAINT ck_synthetic_bank_operation_owner CHECK (
        tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        AND participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_synthetic_bank_operation_kind
        CHECK (operation_kind IN ('WITHDRAWAL', 'DEPOSIT')),
    CONSTRAINT ck_synthetic_bank_operation_status
        CHECK (operation_status IN ('SUCCEEDED', 'REJECTED')),
    CONSTRAINT ck_synthetic_bank_operation_amount
        CHECK (amount_cents BETWEEN 1 AND 999999999999999999),
    CONSTRAINT ck_synthetic_bank_operation_balance CHECK (
        balance_after_cents IS NULL
        OR balance_after_cents BETWEEN 0 AND 999999999999999999),
    CONSTRAINT ck_synthetic_bank_operation_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_synthetic_bank_operation_context CHECK (
        correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'),
    CONSTRAINT ck_synthetic_bank_operation_shape CHECK (
        (operation_status = 'SUCCEEDED'
            AND balance_after_cents IS NOT NULL AND safe_failure_code IS NULL)
        OR (operation_status = 'REJECTED'
            AND balance_after_cents IS NULL
            AND safe_failure_code ~ '^[a-z0-9][a-z0-9.-]{0,127}$'))
);

CREATE INDEX idx_synthetic_bank_operation_owner
    ON synthetic_bank_operation (tenant_id, participant_id, operation_id);

CREATE TABLE synthetic_bank_idempotency (
    tenant_id VARCHAR(64) NOT NULL,
    participant_id VARCHAR(64) NOT NULL,
    bank_id VARCHAR(64) NOT NULL,
    account_id VARCHAR(64) NOT NULL,
    operation_kind VARCHAR(16) NOT NULL,
    idempotency_key_digest CHAR(64) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    operation_id UUID NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (
        tenant_id, participant_id, bank_id, account_id,
        operation_kind, idempotency_key_digest),
    CONSTRAINT fk_synthetic_bank_idempotency_account
        FOREIGN KEY (bank_id, account_id)
        REFERENCES synthetic_bank_account (bank_id, account_id),
    CONSTRAINT fk_synthetic_bank_idempotency_operation
        FOREIGN KEY (operation_id) REFERENCES synthetic_bank_operation (operation_id)
        DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_synthetic_bank_idempotency_owner CHECK (
        tenant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'
        AND participant_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,63}$'),
    CONSTRAINT ck_synthetic_bank_idempotency_kind
        CHECK (operation_kind IN ('WITHDRAWAL', 'DEPOSIT')),
    CONSTRAINT ck_synthetic_bank_idempotency_digests CHECK (
        idempotency_key_digest ~ '^[0-9a-f]{64}$'
        AND command_digest ~ '^[0-9a-f]{64}$')
);

CREATE TABLE synthetic_bank_balance_entry (
    operation_id UUID PRIMARY KEY REFERENCES synthetic_bank_operation (operation_id),
    evidence_id UUID NOT NULL UNIQUE REFERENCES synthetic_bank_operation (evidence_id),
    direction VARCHAR(8) NOT NULL,
    amount_cents NUMERIC(18, 0) NOT NULL,
    balance_before_cents NUMERIC(18, 0) NOT NULL,
    balance_after_cents NUMERIC(18, 0) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_synthetic_bank_balance_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_synthetic_bank_balance_amount
        CHECK (amount_cents BETWEEN 1 AND 999999999999999999),
    CONSTRAINT ck_synthetic_bank_balance_values CHECK (
        balance_before_cents BETWEEN 0 AND 999999999999999999
        AND balance_after_cents BETWEEN 0 AND 999999999999999999),
    CONSTRAINT ck_synthetic_bank_balance_math CHECK (
        (direction = 'DEBIT'
            AND balance_before_cents - amount_cents = balance_after_cents)
        OR (direction = 'CREDIT'
            AND balance_before_cents + amount_cents = balance_after_cents))
);

CREATE TABLE accounting_ledger_account (
    account_type VARCHAR(64) PRIMARY KEY,
    normal_balance VARCHAR(8) NOT NULL,
    balance_cents NUMERIC(18, 0) NOT NULL,
    account_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounting_ledger_type CHECK (account_type IN (
        'RESERVE_CASH_ASSET',
        'FIAT_RECEIVED_PENDING_MINT_LIABILITY',
        'USDZELLE_CIRCULATING_LIABILITY',
        'REDEMPTION_PAYABLE_LIABILITY')),
    CONSTRAINT ck_accounting_ledger_normal CHECK (
        (account_type = 'RESERVE_CASH_ASSET' AND normal_balance = 'DEBIT')
        OR (account_type <> 'RESERVE_CASH_ASSET' AND normal_balance = 'CREDIT')),
    CONSTRAINT ck_accounting_ledger_balance CHECK (
        balance_cents BETWEEN 0 AND 999999999999999999
        AND account_version >= 0)
);

INSERT INTO accounting_ledger_account (
    account_type, normal_balance, balance_cents, account_version, updated_at)
VALUES
    ('RESERVE_CASH_ASSET', 'DEBIT', 0, 0, TIMESTAMPTZ '1970-01-01 00:00:00+00'),
    ('FIAT_RECEIVED_PENDING_MINT_LIABILITY', 'CREDIT', 0, 0,
        TIMESTAMPTZ '1970-01-01 00:00:00+00'),
    ('USDZELLE_CIRCULATING_LIABILITY', 'CREDIT', 0, 0,
        TIMESTAMPTZ '1970-01-01 00:00:00+00'),
    ('REDEMPTION_PAYABLE_LIABILITY', 'CREDIT', 0, 0,
        TIMESTAMPTZ '1970-01-01 00:00:00+00');

CREATE TABLE accounting_confirmed_evidence (
    evidence_id VARCHAR(256) PRIMARY KEY,
    evidence_type VARCHAR(32) NOT NULL,
    operation_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    observation_sequence INTEGER NOT NULL,
    observed_supply_cents NUMERIC(18, 0) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounting_evidence_id
        CHECK (evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'),
    CONSTRAINT ck_accounting_evidence_type CHECK (evidence_type IN (
        'MINT', 'REDEMPTION_CUSTODY', 'BURN')),
    CONSTRAINT ck_accounting_evidence_source CHECK (
        observation_sequence > 0
        AND observed_supply_cents BETWEEN 0 AND 999999999999999999)
);

CREATE TABLE accounting_journal_entry (
    journal_entry_id UUID PRIMARY KEY,
    posting_type VARCHAR(48) NOT NULL,
    accounting_policy_version VARCHAR(128) NOT NULL,
    effective_at TIMESTAMPTZ NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    tenant_id VARCHAR(64),
    participant_id VARCHAR(64),
    correlation_id VARCHAR(128) NOT NULL,
    evidence_id VARCHAR(256) NOT NULL,
    reverses_entry_id UUID REFERENCES accounting_journal_entry (journal_entry_id),
    amount_cents NUMERIC(18, 0) NOT NULL,
    entry_status VARCHAR(16) NOT NULL,
    UNIQUE (journal_entry_id, amount_cents),
    CONSTRAINT ck_accounting_journal_posting CHECK (posting_type IN (
        'RESERVE_FUNDING', 'MINT_CONFIRMED',
        'REDEMPTION_CUSTODY_CONFIRMED', 'BANK_PAYOUT_CONFIRMED', 'REVERSAL')),
    CONSTRAINT ck_accounting_journal_context CHECK (
        accounting_policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND correlation_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$'
        AND evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'),
    CONSTRAINT ck_accounting_journal_amount
        CHECK (amount_cents BETWEEN 1 AND 999999999999999999),
    CONSTRAINT ck_accounting_journal_status CHECK (entry_status = 'POSTED'),
    CONSTRAINT ck_accounting_journal_reversal CHECK (
        (posting_type = 'REVERSAL' AND reverses_entry_id IS NOT NULL)
        OR (posting_type <> 'REVERSAL' AND reverses_entry_id IS NULL)),
    CONSTRAINT ck_accounting_journal_time CHECK (recorded_at >= effective_at)
);

CREATE TABLE accounting_journal_line (
    journal_line_id UUID PRIMARY KEY,
    journal_entry_id UUID NOT NULL,
    account_type VARCHAR(64) NOT NULL REFERENCES accounting_ledger_account (account_type),
    direction VARCHAR(8) NOT NULL,
    amount_cents NUMERIC(18, 0) NOT NULL,
    UNIQUE (journal_entry_id, direction),
    CONSTRAINT fk_accounting_journal_line_amount
        FOREIGN KEY (journal_entry_id, amount_cents)
        REFERENCES accounting_journal_entry (journal_entry_id, amount_cents),
    CONSTRAINT ck_accounting_journal_line_direction
        CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_accounting_journal_line_amount
        CHECK (amount_cents BETWEEN 1 AND 999999999999999999)
);

CREATE TABLE accounting_reversal_binding (
    correction_evidence_id VARCHAR(256) PRIMARY KEY,
    original_journal_id UUID NOT NULL UNIQUE
        REFERENCES accounting_journal_entry (journal_entry_id),
    reversal_journal_id UUID NOT NULL UNIQUE
        REFERENCES accounting_journal_entry (journal_entry_id)
        DEFERRABLE INITIALLY DEFERRED,
    command_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounting_reversal_evidence CHECK (
        correction_evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'),
    CONSTRAINT ck_accounting_reversal_digest CHECK (
        command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_accounting_reversal_distinct CHECK (
        original_journal_id <> reversal_journal_id)
);

CREATE TABLE accounting_evidence_consumption (
    evidence_id VARCHAR(256) PRIMARY KEY,
    evidence_type VARCHAR(32) NOT NULL,
    posting_type VARCHAR(48) NOT NULL,
    command_digest CHAR(64) NOT NULL,
    journal_entry_id UUID REFERENCES accounting_journal_entry (journal_entry_id)
        DEFERRABLE INITIALLY DEFERRED,
    consumed_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounting_consumption_evidence
        CHECK (evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$'),
    CONSTRAINT ck_accounting_consumption_digest
        CHECK (command_digest ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_accounting_consumption_type CHECK (evidence_type IN (
        'BANK_WITHDRAWAL', 'BANK_DEPOSIT', 'MINT',
        'REDEMPTION_CUSTODY', 'BURN')),
    CONSTRAINT ck_accounting_consumption_posting CHECK (posting_type IN (
        'RESERVE_FUNDING', 'MINT_CONFIRMED',
        'REDEMPTION_CUSTODY_CONFIRMED', 'BANK_PAYOUT_CONFIRMED',
        'BURN_CONFIRMED')),
    CONSTRAINT ck_accounting_consumption_journal CHECK (
        (posting_type = 'BURN_CONFIRMED' AND journal_entry_id IS NULL)
        OR (posting_type <> 'BURN_CONFIRMED' AND journal_entry_id IS NOT NULL))
);

CREATE TABLE accounting_operational_position (
    position_type VARCHAR(64) PRIMARY KEY,
    quantity_cents NUMERIC(18, 0) NOT NULL,
    position_version BIGINT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounting_position_type CHECK (position_type IN (
        'ADMIN_REDEMPTION_CUSTODY_PENDING_BURN',
        'CONFIRMED_CHAIN_TOTAL_SUPPLY',
        'CONTROLLED_INVENTORY')),
    CONSTRAINT ck_accounting_position_value CHECK (
        quantity_cents BETWEEN 0 AND 999999999999999999
        AND position_version >= 0)
);

INSERT INTO accounting_operational_position (
    position_type, quantity_cents, position_version, updated_at)
VALUES
    ('ADMIN_REDEMPTION_CUSTODY_PENDING_BURN', 0, 0,
        TIMESTAMPTZ '1970-01-01 00:00:00+00'),
    ('CONFIRMED_CHAIN_TOTAL_SUPPLY', 0, 0,
        TIMESTAMPTZ '1970-01-01 00:00:00+00'),
    ('CONTROLLED_INVENTORY', 0, 0,
        TIMESTAMPTZ '1970-01-01 00:00:00+00');

CREATE TABLE accounting_reconciliation_run (
    reconciliation_run_id UUID PRIMARY KEY,
    reconciliation_result_id UUID NOT NULL UNIQUE,
    reconciliation_status VARCHAR(48) NOT NULL,
    reserve_cash_cents NUMERIC(18, 0) NOT NULL,
    pending_mint_cents NUMERIC(18, 0) NOT NULL,
    circulating_cents NUMERIC(18, 0) NOT NULL,
    redemption_payable_cents NUMERIC(18, 0) NOT NULL,
    custody_pending_burn_cents NUMERIC(18, 0) NOT NULL,
    confirmed_supply_cents NUMERIC(18, 0) NOT NULL,
    controlled_inventory_cents NUMERIC(18, 0) NOT NULL,
    evidence_complete BOOLEAN NOT NULL,
    observation_supported_fresh BOOLEAN NOT NULL,
    accounting_policy_version VARCHAR(128) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_accounting_reconciliation_status CHECK (reconciliation_status IN (
        'RECONCILED', 'RESERVE_LEDGER_MISMATCH', 'CHAIN_SUPPLY_MISMATCH',
        'EVIDENCE_INCOMPLETE', 'UNSUPPORTED_OR_STALE_OBSERVATION')),
    CONSTRAINT ck_accounting_reconciliation_values CHECK (
        reserve_cash_cents BETWEEN 0 AND 999999999999999999
        AND pending_mint_cents BETWEEN 0 AND 999999999999999999
        AND circulating_cents BETWEEN 0 AND 999999999999999999
        AND redemption_payable_cents BETWEEN 0 AND 999999999999999999
        AND custody_pending_burn_cents BETWEEN 0 AND 999999999999999999
        AND confirmed_supply_cents BETWEEN 0 AND 999999999999999999
        AND controlled_inventory_cents BETWEEN 0 AND 999999999999999999),
    CONSTRAINT ck_accounting_reconciliation_policy
        CHECK (accounting_policy_version ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$')
);

CREATE TABLE accounting_reconciliation_evidence (
    reconciliation_run_id UUID NOT NULL
        REFERENCES accounting_reconciliation_run (reconciliation_run_id),
    evidence_order INTEGER NOT NULL,
    evidence_id VARCHAR(256) NOT NULL,
    PRIMARY KEY (reconciliation_run_id, evidence_order),
    CONSTRAINT ck_accounting_reconciliation_evidence_order
        CHECK (evidence_order >= 0),
    CONSTRAINT ck_accounting_reconciliation_evidence_id
        CHECK (evidence_id ~ '^[A-Za-z0-9][A-Za-z0-9._:-]{0,255}$')
);

CREATE OR REPLACE FUNCTION verify_balanced_accounting_journal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    line_count INTEGER;
    debit_total NUMERIC(18, 0);
    credit_total NUMERIC(18, 0);
    expected_total NUMERIC(18, 0);
BEGIN
    SELECT count(*),
           coalesce(sum(amount_cents) FILTER (WHERE direction = 'DEBIT'), 0),
           coalesce(sum(amount_cents) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO line_count, debit_total, credit_total
      FROM accounting_journal_line
     WHERE journal_entry_id = NEW.journal_entry_id;
    SELECT amount_cents INTO expected_total
      FROM accounting_journal_entry
     WHERE journal_entry_id = NEW.journal_entry_id;
    IF line_count <> 2 OR debit_total <> credit_total
            OR debit_total <> expected_total THEN
        RAISE EXCEPTION 'accounting journal must contain one balanced debit and credit';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER accounting_journal_balanced
    AFTER INSERT ON accounting_journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION verify_balanced_accounting_journal();

CREATE OR REPLACE FUNCTION reject_append_only_financial_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
    RAISE EXCEPTION 'append-only financial history cannot be changed';
END;
$$;

CREATE TRIGGER synthetic_bank_idempotency_append_only
    BEFORE UPDATE OR DELETE ON synthetic_bank_idempotency
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER synthetic_bank_operation_append_only
    BEFORE UPDATE OR DELETE ON synthetic_bank_operation
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER synthetic_bank_balance_entry_append_only
    BEFORE UPDATE OR DELETE ON synthetic_bank_balance_entry
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_confirmed_evidence_append_only
    BEFORE UPDATE OR DELETE ON accounting_confirmed_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_journal_entry_append_only
    BEFORE UPDATE OR DELETE ON accounting_journal_entry
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_journal_line_append_only
    BEFORE UPDATE OR DELETE ON accounting_journal_line
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_reversal_binding_append_only
    BEFORE UPDATE OR DELETE ON accounting_reversal_binding
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_evidence_consumption_append_only
    BEFORE UPDATE OR DELETE ON accounting_evidence_consumption
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_reconciliation_run_append_only
    BEFORE UPDATE OR DELETE ON accounting_reconciliation_run
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
CREATE TRIGGER accounting_reconciliation_evidence_append_only
    BEFORE UPDATE OR DELETE ON accounting_reconciliation_evidence
    FOR EACH ROW EXECUTE FUNCTION reject_append_only_financial_mutation();
