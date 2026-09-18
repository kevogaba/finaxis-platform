-- Issue #47: gl_account_daily_balance, the one derived balance projection the accounting
-- foundation approves, and the index on journal_entry its incremental build reads.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging. The decisions behind the shape are recorded in
-- docs/adr/0027-derived-balance-projection-and-its-build-trigger.md.
--
-- This is a PROJECTION, not a ledger (INV-13). The journal stays the sole system of record; every
-- row here is rebuildable from journal_line by the documented query, and when the two disagree the
-- journal wins and this table is rebuilt. It exists for one reason, and the reason is arithmetic:
-- a monthly trial balance scans ~16 million journal lines and survives, while an as-of balance
-- summing an account's lines from inception scans a table heading for 1.4 billion rows and does
-- not. Nothing else in the schema is allowed to be derived this way without its own such argument.

-- ---------------------------------------------------------------------------------------------
-- gl_account_daily_balance
-- ---------------------------------------------------------------------------------------------

CREATE TABLE gl_account_daily_balance (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    gl_account_id UUID NOT NULL,
    branch_id UUID,
    currency_code CHAR(3) NOT NULL,
    posting_date DATE NOT NULL,
    opening_signed_functional NUMERIC(23, 6) NOT NULL,
    debit_functional NUMERIC(23, 6) NOT NULL,
    credit_functional NUMERIC(23, 6) NOT NULL,
    movement_signed_functional NUMERIC(23, 6) NOT NULL GENERATED ALWAYS AS (
        debit_functional - credit_functional
    ) STORED,
    closing_signed_functional NUMERIC(23, 6) NOT NULL GENERATED ALWAYS AS (
        opening_signed_functional + debit_functional - credit_functional
    ) STORED,
    line_count INTEGER NOT NULL,
    built_at TIMESTAMPTZ NOT NULL,
    built_for_business_date DATE NOT NULL,
    CONSTRAINT uq_gl_account_daily_balance_guid UNIQUE (guid),
    -- The grain, and the as-of read path in one index. NULLS NOT DISTINCT because branch_id is
    -- nullable and two head-office rows for one account on one day must collide rather than
    -- coexist: under the default NULLS DISTINCT the projection could hold two contradictory
    -- balances for the same key and neither would violate the constraint.
    CONSTRAINT uq_gl_account_daily_balance_key UNIQUE NULLS NOT DISTINCT
        (organisation_id, gl_account_id, branch_id, currency_code, posting_date),
    CONSTRAINT fk_gl_account_daily_balance_account
        FOREIGN KEY (organisation_id, gl_account_id) REFERENCES gl_account (organisation_id, id),
    CONSTRAINT fk_gl_account_daily_balance_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    -- Direction carries the sign (INV-3), so a side total is an unsigned magnitude.
    CONSTRAINT chk_gl_account_daily_balance_debit CHECK (debit_functional >= 0),
    CONSTRAINT chk_gl_account_daily_balance_credit CHECK (credit_functional >= 0),
    -- Sparseness, enforced from both sides. A zero-movement row is a row the build should not have
    -- written, and it would break the as-of read's "latest row wins" reasoning by answering for a
    -- day the account did not move.
    CONSTRAINT chk_gl_account_daily_balance_movement
        CHECK (debit_functional > 0 OR credit_functional > 0),
    CONSTRAINT chk_gl_account_daily_balance_line_count CHECK (line_count > 0),
    CONSTRAINT chk_gl_account_daily_balance_currency CHECK (currency_code ~ '^[A-Z]{3}$')
);

-- One index beyond the unique key, and only one. The key's column order is exactly the as-of
-- predicate's, so that read is one backward range scan stopping at the first row; a tenant-wide
-- sweep is #49's trial balance, which reads this table through a LATERAL per account rather than
-- scanning it. Every index here is paid on every rebuild of every key, so V1's rule holds: no
-- speculative indexes, and the one below is not speculative - the as-of read cannot be exact
-- without it.
CREATE INDEX idx_gl_account_daily_balance_watermark
    ON gl_account_daily_balance (organisation_id, built_for_business_date DESC);

COMMENT ON INDEX idx_gl_account_daily_balance_watermark IS
    'The projection watermark: the latest business date this tenant''s build has settled. One '
    'backward index scan stopping at the first row. Read on every as-of balance, because the set '
    'of journal lines NOT yet projected is exactly those recorded after it - which is what makes a '
    'checkpoint plus a bounded delta exact rather than merely fresh.';

COMMENT ON TABLE gl_account_daily_balance IS
    'Derived daily balance projection over journal_line: one row per account, branch, functional '
    'currency and posting date that had movement. Never a statutory source of truth and never the '
    'only place a number exists (INV-13) - rebuildable by the documented query in '
    'docs/database/accounting-erd.md, and rebuilt whenever it disagrees with the journal.';
COMMENT ON COLUMN gl_account_daily_balance.posting_date IS
    'The day whose books these amounts belong to. The only column that selects a fiscal period '
    '(INV-9), and never the business date: a projection keyed on when a posting happened to be '
    'recorded could not answer an as-of question.';
COMMENT ON COLUMN gl_account_daily_balance.currency_code IS
    'The functional currency the amounts are expressed in, taken from the tenant''s frozen '
    'functional currency. Never the transaction currency: summing across transaction currencies '
    'would add incompatible units.';
COMMENT ON COLUMN gl_account_daily_balance.opening_signed_functional IS
    'This key''s closing balance on its previous row, carried forward; zero when the key has no '
    'earlier row. What makes an as-of balance one row read rather than a sum over the key''s '
    'history - and what makes a line arriving on an already-built day invalidate this key''s '
    'whole tail, which the build repairs by recomputing the key from that date forward.';
COMMENT ON COLUMN gl_account_daily_balance.line_count IS
    'How many journal_line rows this row aggregates. Part of the projection-versus-journal proof, '
    'not decoration: a row whose sums match but whose line count does not has missed a line that '
    'nets to zero.';
COMMENT ON COLUMN gl_account_daily_balance.built_at IS
    'When the build last recomputed this row. There is deliberately no created_by or updated_by: '
    'a projection row is written by a rebuild rather than by an actor, and the actor of record is '
    'on the journal lines it aggregates.';
COMMENT ON COLUMN gl_account_daily_balance.built_for_business_date IS
    'The tenant business date whose build wrote this row, so a stale row is identifiable without '
    'a join.';

-- ---------------------------------------------------------------------------------------------
-- journal_entry: the index the incremental build reads
-- ---------------------------------------------------------------------------------------------

-- The build for business date B enumerates the journals RECORDED on B - whatever their posting
-- dates - because that is the only enumeration that sees a backdated correction. journal_entry
-- carries business_date; journal_line deliberately does not, since it denormalises only the
-- columns its read-path indexes are keyed on. V7's idx_journal_entry_posting_date leads with
-- posting_date and cannot serve this, so the build would otherwise scan the tenant's whole header
-- table once per business day.
--
-- posting_date rides in INCLUDE for the reader rather than the build. An as-of balance needs the
-- EARLIEST posting date any not-yet-projected journal touches, so that it can place its checkpoint
-- safely before it; without the payload column that one aggregate would fetch a heap tuple per
-- journal recorded since the last build.
CREATE INDEX idx_journal_entry_business_date
    ON journal_entry (organisation_id, business_date, id)
    INCLUDE (posting_date);

COMMENT ON INDEX idx_journal_entry_business_date IS
    'Serves the daily-balance build: every journal recorded on one tenant business date, '
    'including backdated ones whose posting_date is earlier. Also serves the as-of read, which '
    'takes MIN(posting_date) over the journals recorded after the projection watermark - '
    'index-only, because posting_date is in the INCLUDE list.';
