-- Issue #44: versioned, effective-dated posting rules - posting_rule, posting_rule_version,
-- posting_rule_leg, their transition log, and the foreign key posting_request has waited for.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- One resolution path (ADR 0020): posting_rule -> the version effective on the posting date ->
-- its legs -> a general-ledger account. Fineract's three overlapping mechanisms are rejected, and
-- there is no account_mapping table. Versions are effective-dated so a prior-period correction
-- re-posts under the rule that was in force on that date, not today's.

-- ---------------------------------------------------------------------------------------------
-- posting_rule: the tenant's rule for one financial event, selected by event and dimensions
-- ---------------------------------------------------------------------------------------------

CREATE TABLE posting_rule (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    rule_code TEXT NOT NULL,
    rule_name TEXT NOT NULL,
    description TEXT,
    event_code TEXT NOT NULL,
    product_class TEXT,
    currency_code CHAR(3),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_posting_rule_guid UNIQUE (guid),
    CONSTRAINT uq_posting_rule_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_posting_rule_organisation_code UNIQUE (organisation_id, rule_code),
    CONSTRAINT uq_posting_rule_selector
        UNIQUE NULLS NOT DISTINCT (organisation_id, event_code, product_class, currency_code),
    CONSTRAINT chk_posting_rule_code CHECK (rule_code ~ '^[A-Za-z0-9._-]{1,64}$'),
    CONSTRAINT chk_posting_rule_event_code CHECK (event_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT chk_posting_rule_product_class
        CHECK (product_class IS NULL OR product_class ~ '^[A-Z][A-Z0-9_:.-]{0,63}$'),
    CONSTRAINT chk_posting_rule_currency
        CHECK (currency_code IS NULL OR currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_posting_rule_version CHECK (row_version >= 0)
);

CREATE INDEX idx_posting_rule_event ON posting_rule (organisation_id, event_code);

COMMENT ON TABLE posting_rule IS
    'One tenant rule for one financial event, selected by event_code and the optional '
    'product_class and currency_code dimensions. Carries no status: a rule is as alive as its '
    'versions, which is derivable and therefore cannot drift. Identity and selectors are frozen '
    'once a version has been activated (application-enforced).';
COMMENT ON COLUMN posting_rule.product_class IS
    'Optional selector dimension, e.g. SAVINGS:REGULAR. NULL means the rule applies to every '
    'product class of the event. The resolver prefers the most specific matching rule and fails '
    'fast when two rules match at the same specificity.';
COMMENT ON COLUMN posting_rule.currency_code IS
    'Optional selector dimension. NULL means the rule applies to every currency of the event.';
COMMENT ON CONSTRAINT uq_posting_rule_selector ON posting_rule IS
    'At most one rule per selector combination, with NULL treated as a value: two rules for the '
    'same event with no product class and no currency are the same selector, not two.';

-- ---------------------------------------------------------------------------------------------
-- posting_rule_version: immutable once approved; effective-dated; non-overlapping per rule
-- ---------------------------------------------------------------------------------------------

CREATE TABLE posting_rule_version (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    posting_rule_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    status TEXT NOT NULL,
    status_reason TEXT,
    effective_from DATE NOT NULL,
    effective_to DATE,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_posting_rule_version_guid UNIQUE (guid),
    CONSTRAINT uq_posting_rule_version_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_posting_rule_version_number
        UNIQUE (organisation_id, posting_rule_id, version_number),
    CONSTRAINT fk_posting_rule_version_rule
        FOREIGN KEY (organisation_id, posting_rule_id)
        REFERENCES posting_rule (organisation_id, id),
    CONSTRAINT chk_posting_rule_version_number CHECK (version_number > 0),
    CONSTRAINT chk_posting_rule_version_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'SUPERSEDED', 'RETIRED')),
    CONSTRAINT chk_posting_rule_version_effective
        CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CONSTRAINT chk_posting_rule_version_closed_when_ended
        CHECK (status NOT IN ('SUPERSEDED', 'RETIRED') OR effective_to IS NOT NULL),
    CONSTRAINT chk_posting_rule_version_version CHECK (row_version >= 0),
    CONSTRAINT ex_posting_rule_version_no_overlap EXCLUDE USING gist (
        organisation_id extensions.gist_uuid_ops WITH =,
        posting_rule_id extensions.gist_uuid_ops WITH =,
        daterange(effective_from, effective_to, '[]') WITH &&
    ) WHERE (status IN ('ACTIVE', 'SUPERSEDED', 'RETIRED'))
);

CREATE INDEX idx_posting_rule_version_rule_status
    ON posting_rule_version (organisation_id, posting_rule_id, status);

COMMENT ON TABLE posting_rule_version IS
    'One approved-or-proposed set of legs for a rule, effective from a date. Once ACTIVE its legs '
    'and effective_from never change (application-enforced); supersession or retirement only '
    'closes effective_to and moves the status. Historical postings keep the exact version they '
    'used through posting_request.posting_rule_version_id.';
COMMENT ON COLUMN posting_rule_version.status IS
    'DRAFT editable; PENDING_APPROVAL awaiting a checker who is not the maker; ACTIVE approved '
    'and the rule''s current head; SUPERSEDED closed by a successor version; RETIRED closed '
    'deliberately with no successor. Rejection returns a version to DRAFT with a reason. All '
    'three approved statuses resolve postings whose posting date falls in the effective range.';
COMMENT ON COLUMN posting_rule_version.effective_to IS
    'Inclusive last posting date the version governs; NULL while open-ended. Written when a '
    'successor activates or the version is retired, never edited by hand.';
COMMENT ON CONSTRAINT ex_posting_rule_version_no_overlap ON posting_rule_version IS
    'Approved versions of one rule never govern the same posting date, so the resolver finds at '
    'most one. Drafts and proposals are outside the constraint until they are approved.';

-- ---------------------------------------------------------------------------------------------
-- posting_rule_leg: one ordered debit or credit, its account and where its amount comes from
-- ---------------------------------------------------------------------------------------------

CREATE TABLE posting_rule_leg (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    posting_rule_version_id UUID NOT NULL,
    leg_number INTEGER NOT NULL,
    direction TEXT NOT NULL,
    account_resolution TEXT NOT NULL,
    gl_account_id UUID NOT NULL,
    amount_source TEXT NOT NULL,
    amount_percentage NUMERIC(9, 6) NOT NULL DEFAULT 100,
    is_residual BOOLEAN NOT NULL DEFAULT FALSE,
    narrative TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_posting_rule_leg_guid UNIQUE (guid),
    CONSTRAINT uq_posting_rule_leg_number
        UNIQUE (organisation_id, posting_rule_version_id, leg_number),
    CONSTRAINT fk_posting_rule_leg_version
        FOREIGN KEY (organisation_id, posting_rule_version_id)
        REFERENCES posting_rule_version (organisation_id, id),
    CONSTRAINT fk_posting_rule_leg_account
        FOREIGN KEY (organisation_id, gl_account_id) REFERENCES gl_account (organisation_id, id),
    CONSTRAINT chk_posting_rule_leg_number CHECK (leg_number > 0),
    CONSTRAINT chk_posting_rule_leg_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_posting_rule_leg_resolution CHECK (account_resolution IN ('FIXED_ACCOUNT')),
    CONSTRAINT chk_posting_rule_leg_amount_source
        CHECK (amount_source ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT chk_posting_rule_leg_percentage
        CHECK (amount_percentage > 0 AND amount_percentage <= 100),
    CONSTRAINT chk_posting_rule_leg_narrative
        CHECK (narrative IS NULL OR char_length(narrative) <= 200),
    CONSTRAINT chk_posting_rule_leg_version CHECK (row_version >= 0)
);

-- Per fact *per side*: a fact split across two debits and independently across two credits needs
-- a residual on each side, because each side's rounding remainder is its own. Omitting direction
-- would make such a rule unrepresentable even though it is balanced and ordinary.
CREATE UNIQUE INDEX uq_posting_rule_leg_residual
    ON posting_rule_leg (organisation_id, posting_rule_version_id, amount_source, direction)
    WHERE is_residual;
CREATE INDEX idx_posting_rule_leg_account ON posting_rule_leg (organisation_id, gl_account_id);

COMMENT ON TABLE posting_rule_leg IS
    'One ordered debit or credit of a rule version: the account it lands in and the financial '
    'fact - by amount_source - it takes its amount from, optionally as a percentage of that fact. '
    'Editable only while its version is DRAFT (application-enforced).';
COMMENT ON COLUMN posting_rule_leg.account_resolution IS
    'How the account is found. FIXED_ACCOUNT names gl_account_id directly and is the only '
    'strategy this version of the schema admits; a product-parameter strategy is a later, '
    'forward-only widening of this CHECK once a product module exists to bind it.';
COMMENT ON COLUMN posting_rule_leg.amount_source IS
    'The FinancialFact code whose amount this leg carries, e.g. PRINCIPAL or FEE. A fact the '
    'intent does not supply is a resolution failure, never a zero line.';
COMMENT ON COLUMN posting_rule_leg.amount_percentage IS
    'The share of the fact this leg takes, rounded HALF_EVEN at the currency minor unit. The legs '
    'of one fact need not sum to 100: what they do not take is not posted.';
COMMENT ON COLUMN posting_rule_leg.is_residual IS
    'Exactly this leg receives the fact total minus the sum of the fact''s other legs, so a split '
    'reproduces the total exactly rather than approximately (INV-2). At most one per fact per '
    'version; when a fact is split across legs, one of them must be the residual.';

-- ---------------------------------------------------------------------------------------------
-- Transition log
-- ---------------------------------------------------------------------------------------------

CREATE TABLE posting_rule_version_transition_log (
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
    CONSTRAINT uq_posting_rule_version_transition_log_guid UNIQUE (guid),
    CONSTRAINT fk_posting_rule_version_transition_log_version
        FOREIGN KEY (organisation_id, entity_id)
        REFERENCES posting_rule_version (organisation_id, id),
    CONSTRAINT chk_posting_rule_version_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_posting_rule_version_transition_log_organisation_entity
    ON posting_rule_version_transition_log (organisation_id, entity_id, created_at DESC);

COMMENT ON TABLE posting_rule_version_transition_log IS
    'Append-only history of posting-rule-version submit, approve, reject, supersede and retire, '
    'written by the common transition executor. Issue #45 declares the transitions it records; '
    'created_by is what makes the approver-is-not-the-submitter control answerable from data.';

-- ---------------------------------------------------------------------------------------------
-- The foreign key posting_request has carried a column for since V7
-- ---------------------------------------------------------------------------------------------

ALTER TABLE posting_request
    ADD CONSTRAINT fk_posting_request_rule_version
        FOREIGN KEY (organisation_id, posting_rule_version_id)
        REFERENCES posting_rule_version (organisation_id, id);

CREATE INDEX idx_posting_request_rule_version
    ON posting_request (organisation_id, posting_rule_version_id)
    WHERE posting_rule_version_id IS NOT NULL;

COMMENT ON INDEX idx_posting_request_rule_version IS
    'The rule-version foreign key, and "which postings used this version" - the question a '
    'reviewer of a superseded version asks. Partial, because reversals and manual journals carry '
    'no version.';
