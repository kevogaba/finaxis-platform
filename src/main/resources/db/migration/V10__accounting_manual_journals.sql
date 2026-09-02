-- Issue #48: controlled manual journals - the draft aggregate an accountant prepares, its lines,
-- and its transition log. Posting goes through the same PostingEngine as every other journal.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- A draft is not a posting_request: that row is created by the engine at posting time and its
-- mutability is bounded to the posting transaction, while a manual journal is edited over days,
-- submitted, rejected and resubmitted before anything reaches the ledger. Once approved it posts
-- as an ordinary immutable journal_entry with entry_type MANUAL, and the draft keeps the id.

-- ---------------------------------------------------------------------------------------------
-- manual_journal: the draft header
-- ---------------------------------------------------------------------------------------------

CREATE TABLE manual_journal (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    branch_id UUID,
    title TEXT NOT NULL,
    narrative TEXT NOT NULL,
    status TEXT NOT NULL,
    status_reason TEXT,
    transaction_date DATE,
    value_date DATE,
    posting_date DATE,
    journal_entry_id UUID,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_manual_journal_guid UNIQUE (guid),
    CONSTRAINT uq_manual_journal_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_manual_journal_entry UNIQUE (organisation_id, journal_entry_id),
    CONSTRAINT fk_manual_journal_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT fk_manual_journal_entry
        FOREIGN KEY (organisation_id, journal_entry_id)
        REFERENCES journal_entry (organisation_id, id),
    CONSTRAINT chk_manual_journal_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'POSTED', 'CANCELLED')),
    CONSTRAINT chk_manual_journal_posted_has_entry
        CHECK ((status = 'POSTED') = (journal_entry_id IS NOT NULL)),
    CONSTRAINT chk_manual_journal_title CHECK (char_length(title) BETWEEN 1 AND 200),
    CONSTRAINT chk_manual_journal_narrative CHECK (char_length(narrative) BETWEEN 1 AND 500),
    CONSTRAINT chk_manual_journal_version CHECK (row_version >= 0)
);

CREATE INDEX idx_manual_journal_organisation_status ON manual_journal (organisation_id, status);

COMMENT ON TABLE manual_journal IS
    'A manual accounting adjustment an accountant prepares: DRAFT, PENDING_APPROVAL, POSTED or '
    'CANCELLED. Approval posts it through the PostingEngine as an ordinary journal_entry of type '
    'MANUAL and records that entry here; the journal tables are never written by this path '
    'directly. Rejection returns it to DRAFT with a reason.';
COMMENT ON COLUMN manual_journal.narrative IS
    'The reason for the adjustment, mandatory, carried onto the posted journal.';
COMMENT ON COLUMN manual_journal.posting_date IS
    'The day the adjustment posts into; NULL means the tenant business date at approval time. A '
    'date before it is a backdated posting the approver needs journal.post_prior_period for.';
COMMENT ON COLUMN manual_journal.journal_entry_id IS
    'The immutable journal the approval produced; set exactly when POSTED, and unique, so one '
    'draft posts at most once.';

-- ---------------------------------------------------------------------------------------------
-- manual_journal_line: explicit debit and credit legs, editable while the header is DRAFT
-- ---------------------------------------------------------------------------------------------

CREATE TABLE manual_journal_line (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    manual_journal_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    gl_account_id UUID NOT NULL,
    direction TEXT NOT NULL,
    amount NUMERIC(23, 6) NOT NULL,
    currency_code CHAR(3) NOT NULL,
    narrative TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_manual_journal_line_guid UNIQUE (guid),
    CONSTRAINT uq_manual_journal_line_number
        UNIQUE (organisation_id, manual_journal_id, line_number),
    CONSTRAINT fk_manual_journal_line_journal
        FOREIGN KEY (organisation_id, manual_journal_id)
        REFERENCES manual_journal (organisation_id, id),
    CONSTRAINT fk_manual_journal_line_account
        FOREIGN KEY (organisation_id, gl_account_id) REFERENCES gl_account (organisation_id, id),
    CONSTRAINT chk_manual_journal_line_number CHECK (line_number > 0),
    CONSTRAINT chk_manual_journal_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_manual_journal_line_amount CHECK (amount > 0),
    CONSTRAINT chk_manual_journal_line_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_manual_journal_line_narrative
        CHECK (narrative IS NULL OR char_length(narrative) <= 500),
    CONSTRAINT chk_manual_journal_line_version CHECK (row_version >= 0)
);

CREATE INDEX idx_manual_journal_line_account ON manual_journal_line (organisation_id, gl_account_id);

COMMENT ON TABLE manual_journal_line IS
    'One explicit debit or credit of a manual journal, named by account. Replaced wholesale while '
    'the header is DRAFT (application-enforced); frozen from submission onwards. Never targets a '
    'control account, and only an account that opted into manual posting.';

-- ---------------------------------------------------------------------------------------------
-- Transition log
-- ---------------------------------------------------------------------------------------------

CREATE TABLE manual_journal_transition_log (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL,
    entity_id UUID NOT NULL,
    transition_name TEXT NOT NULL,
    status_from TEXT NOT NULL,
    status_to TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    metadata_jsonb JSONB NOT NULL DEFAULT '{}'::JSONB,
    reason TEXT,
    CONSTRAINT uq_manual_journal_transition_log_guid UNIQUE (guid),
    CONSTRAINT fk_manual_journal_transition_log_journal
        FOREIGN KEY (organisation_id, entity_id) REFERENCES manual_journal (organisation_id, id),
    CONSTRAINT chk_manual_journal_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_manual_journal_transition_log_organisation_entity
    ON manual_journal_transition_log (organisation_id, entity_id, created_at DESC);

COMMENT ON TABLE manual_journal_transition_log IS
    'Append-only history of manual-journal submit, approve, reject and cancel, written by the '
    'common transition executor. created_by is what makes "the approver is not the submitter" '
    'answerable from data.';
