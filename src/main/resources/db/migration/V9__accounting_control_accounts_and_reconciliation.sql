-- Issue #46: control-account classification on gl_account, the reconciliation evidence table,
-- and the sub-ledger drill-down index on journal_line.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- A control account IS a general-ledger account (ADR 0020): no table of its own, a classification
-- on gl_account that carries one obligation - its balance must equal the aggregate of the
-- subsidiary ledger it controls, and the equality is proven by a reconciliation run (INV-14).
-- Reconciliation is the detective control; same-transaction posting is the preventive one.
-- A run never mutates a journal: a break is resolved by reversal or a fresh posting.

-- ---------------------------------------------------------------------------------------------
-- gl_account: the control-account classification
-- ---------------------------------------------------------------------------------------------

ALTER TABLE gl_account
    ADD COLUMN is_control_account BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN control_subledger_kind TEXT,
    ADD CONSTRAINT chk_gl_account_control_kind_present
        CHECK (is_control_account = (control_subledger_kind IS NOT NULL)),
    ADD CONSTRAINT chk_gl_account_control_kind CHECK (
        control_subledger_kind IS NULL OR control_subledger_kind IN (
            'SAVINGS_DEPOSITS', 'SHARE_CAPITAL', 'LOAN_PRINCIPAL',
            'ACCRUED_INTEREST_RECEIVABLE', 'ACCRUED_INTEREST_PAYABLE', 'TELLER_CASH', 'SUSPENSE'
        )
    ),
    ADD CONSTRAINT chk_gl_account_control_postable
        CHECK (NOT is_control_account OR account_usage = 'POSTABLE'),
    ADD CONSTRAINT chk_gl_account_control_no_manual_posting
        CHECK (NOT is_control_account OR NOT manual_posting_allowed);

CREATE INDEX idx_gl_account_control
    ON gl_account (organisation_id, control_subledger_kind)
    WHERE is_control_account;

COMMENT ON COLUMN gl_account.is_control_account IS
    'Whether this account represents the aggregate position of one subsidiary-ledger class. A '
    'control account is postable, never takes a manual entry, and must reconcile to its '
    'sub-ledger (INV-14).';
COMMENT ON COLUMN gl_account.control_subledger_kind IS
    'The subsidiary-ledger class this account controls; set exactly when is_control_account. The '
    'key a SubledgerProofProvider answers for.';
COMMENT ON CONSTRAINT chk_gl_account_control_no_manual_posting ON gl_account IS
    'A hand-written entry to a control account would break its reconciliation by definition, so '
    'the schema forbids the combination rather than leaving it to a policy check.';

-- ---------------------------------------------------------------------------------------------
-- control_account_reconciliation_run: evidence that GL and sub-ledger agreed, or did not
-- ---------------------------------------------------------------------------------------------

CREATE TABLE control_account_reconciliation_run (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    gl_account_id UUID NOT NULL,
    branch_id UUID,
    control_subledger_kind TEXT NOT NULL,
    as_of_date DATE NOT NULL,
    currency_code CHAR(3) NOT NULL,
    gl_balance NUMERIC(23, 6) NOT NULL,
    subledger_balance NUMERIC(23, 6) NOT NULL,
    difference NUMERIC(23, 6) NOT NULL GENERATED ALWAYS AS (gl_balance - subledger_balance) STORED,
    tolerance NUMERIC(23, 6) NOT NULL DEFAULT 0,
    status TEXT NOT NULL,
    provider TEXT NOT NULL,
    subledger_detail_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    resolution_reason TEXT,
    resolved_by UUID,
    resolved_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_control_account_reconciliation_run_guid UNIQUE (guid),
    CONSTRAINT fk_control_account_reconciliation_run_account
        FOREIGN KEY (organisation_id, gl_account_id) REFERENCES gl_account (organisation_id, id),
    CONSTRAINT fk_control_account_reconciliation_run_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT chk_control_account_reconciliation_run_status
        CHECK (status IN ('MATCHED', 'BREAK', 'RESOLVED')),
    -- The verdict and the numbers agree in both directions: MATCHED is within tolerance, and a
    -- BREAK - or the RESOLVED break it becomes - is outside it. Without the second half the table
    -- would accept evidence whose status contradicts the balances it stores.
    CONSTRAINT chk_control_account_reconciliation_run_matched
        CHECK (status <> 'MATCHED' OR abs(gl_balance - subledger_balance) <= tolerance),
    CONSTRAINT chk_control_account_reconciliation_run_break
        CHECK (status = 'MATCHED' OR abs(gl_balance - subledger_balance) > tolerance),
    CONSTRAINT chk_control_account_reconciliation_run_resolved CHECK (
        (status = 'RESOLVED') = (resolved_at IS NOT NULL)
        AND (status = 'RESOLVED') = (resolved_by IS NOT NULL)
        AND (status <> 'RESOLVED' OR resolution_reason IS NOT NULL)
    ),
    CONSTRAINT chk_control_account_reconciliation_run_tolerance CHECK (tolerance >= 0),
    CONSTRAINT chk_control_account_reconciliation_run_currency
        CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_control_account_reconciliation_run_provider
        CHECK (provider ~ '^[A-Za-z0-9._-]{1,128}$'),
    CONSTRAINT chk_control_account_reconciliation_run_version CHECK (row_version >= 0)
);

-- Two indexes because the table answers two different questions. The first serves the listing,
-- whose keyset is id alone: as_of_date between the equality prefix and id would force a sort of
-- every matching run before the limit applied. The second serves "the runs around this date".
CREATE INDEX idx_control_account_reconciliation_run_account
    ON control_account_reconciliation_run (organisation_id, gl_account_id, id DESC);
CREATE INDEX idx_control_account_reconciliation_run_account_date
    ON control_account_reconciliation_run (organisation_id, gl_account_id, as_of_date DESC, id DESC);

COMMENT ON TABLE control_account_reconciliation_run IS
    'One proof that a control account''s GL balance equalled - or did not equal - the aggregate '
    'the owning module reported for its subsidiary ledger, as of one business date, in one '
    'scope. Evidence rows: a run never changes a journal. A BREAK is resolved by a reversal or a '
    'fresh posting and then marked RESOLVED here by an actor other than the one who ran it.';
COMMENT ON COLUMN control_account_reconciliation_run.gl_balance IS
    'Signed functional balance from journal_line as of as_of_date: debits positive, credits '
    'negative, so a liability control account carries a negative balance here.';
COMMENT ON COLUMN control_account_reconciliation_run.subledger_balance IS
    'The aggregate the SubledgerProofProvider reported, in the same sign convention, so equality '
    'is a subtraction rather than a rule about account classes.';
COMMENT ON COLUMN control_account_reconciliation_run.tolerance IS
    'Exact equality by default. A non-zero tolerance is a specifically approved rounding or '
    'timing policy and is recorded on the run that used it.';
COMMENT ON COLUMN control_account_reconciliation_run.subledger_detail_jsonb IS
    'Drill-down references the provider supplied - position counts, the newest position moved, '
    'whatever lets an investigator start in the owning module. Descriptive only; never a foreign '
    'key (INV-16).';

-- ---------------------------------------------------------------------------------------------
-- journal_line: the sub-ledger drill-down index (Q6)
-- ---------------------------------------------------------------------------------------------

CREATE INDEX idx_journal_line_subledger
    ON journal_line (organisation_id, source_module, subledger_reference, posting_date, id)
    WHERE subledger_reference IS NOT NULL;

COMMENT ON INDEX idx_journal_line_subledger IS
    'Q6: the GL lines that moved one subsidiary position, for reconciliation drill-down. Partial, '
    'because lines with no position - manual journals, reversals of them - never need it.';
