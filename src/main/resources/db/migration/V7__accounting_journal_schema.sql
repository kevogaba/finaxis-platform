-- Issue #40: the immutable double-entry kernel - posting_request, journal_entry, journal_line.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. Correct the migration to match it (forward-only, in a later
-- version) rather than quietly diverging.
--
-- Three tables and one backfill. No trigger: the balance invariant across a journal's lines is a
-- cross-row property that a CHECK cannot see, and it is enforced by the posting engine's
-- verification read inside the posting transaction (issue #41), detected afterwards by the
-- header-versus-lines proof query, and bounded here by the header's own CHECK constraints.
--
-- journal_entry and journal_line carry created_at and created_by only - no updated_at, no
-- updated_by, no row_version. The absence is the point: there is no column for an update to
-- maintain, so the schema itself states the row is append-only. The physical REVOKE UPDATE, DELETE
-- on a least-privilege application role is issue #54's operational prerequisite.

-- ---------------------------------------------------------------------------------------------
-- posting_request: the durable identity of one business transaction's accounting effect
-- ---------------------------------------------------------------------------------------------

CREATE TABLE posting_request (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    branch_id UUID,
    source_module TEXT NOT NULL,
    source_entity_type TEXT NOT NULL,
    source_entity_id UUID NOT NULL,
    source_reference TEXT NOT NULL,
    event_code TEXT NOT NULL,
    request_fingerprint TEXT NOT NULL,
    -- The exact posting-rule version that resolved the legs. The column exists from the first
    -- journal so lineage is never backfilled; its foreign key to posting_rule_version is added by
    -- issue #44's migration, which creates that table.
    posting_rule_version_id UUID,
    corrects_posting_request_id UUID,
    business_date DATE NOT NULL,
    transaction_date DATE NOT NULL,
    value_date DATE NOT NULL,
    posting_date DATE NOT NULL,
    currency_code CHAR(3) NOT NULL,
    narrative TEXT,
    status TEXT NOT NULL,
    posted_at TIMESTAMPTZ,
    correlation_id TEXT,
    request_id TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_posting_request_guid UNIQUE (guid),
    CONSTRAINT uq_posting_request_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_posting_request_source
        UNIQUE (organisation_id, source_module, source_reference),
    CONSTRAINT fk_posting_request_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT fk_posting_request_corrects
        FOREIGN KEY (organisation_id, corrects_posting_request_id)
        REFERENCES posting_request (organisation_id, id),
    CONSTRAINT chk_posting_request_not_self_correction
        CHECK (corrects_posting_request_id IS NULL OR corrects_posting_request_id <> id),
    CONSTRAINT chk_posting_request_status CHECK (status IN ('PENDING', 'POSTED')),
    CONSTRAINT chk_posting_request_posted_at
        CHECK ((status = 'POSTED') = (posted_at IS NOT NULL)),
    CONSTRAINT chk_posting_request_dates
        CHECK (posting_date <= business_date AND transaction_date <= business_date),
    CONSTRAINT chk_posting_request_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_posting_request_source_module
        CHECK (source_module ~ '^[a-z][a-z0-9_]{0,63}$'),
    -- Bounded like source_module: it is a key column of idx_posting_request_source_entity, and an
    -- unbounded value can push the B-tree tuple past its size limit and fail the posting.
    CONSTRAINT chk_posting_request_source_entity_type
        CHECK (source_entity_type ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    -- Genuinely non-blank, not merely non-empty: '   ' passes a length check, and a module that
    -- fell back to it would make every later operation a retry of the first.
    CONSTRAINT chk_posting_request_source_reference
        CHECK (char_length(source_reference) BETWEEN 1 AND 200 AND btrim(source_reference) <> ''),
    CONSTRAINT chk_posting_request_event_code CHECK (event_code ~ '^[A-Z][A-Z0-9_]{0,63}$'),
    CONSTRAINT chk_posting_request_fingerprint CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_posting_request_narrative
        CHECK (narrative IS NULL OR char_length(narrative) <= 500),
    CONSTRAINT chk_posting_request_version CHECK (row_version >= 0)
);

-- Ends in id DESC so it serves the Q5 drill-down *and* the keyset order that pages it. Without
-- the trailing key PostgreSQL must sort every matching row before applying the limit, so a later
-- page of a busy entity costs more than an earlier one.
CREATE INDEX idx_posting_request_source_entity
    ON posting_request (
        organisation_id, source_module, source_entity_type, source_entity_id, id DESC
    );
-- Unique, not merely indexed: a request is replaced at most once. Two corrections with different
-- source references would otherwise each produce a journal and duplicate the replacement effect.
CREATE UNIQUE INDEX uq_posting_request_corrects
    ON posting_request (organisation_id, corrects_posting_request_id)
    WHERE corrects_posting_request_id IS NOT NULL;

COMMENT ON TABLE posting_request IS
    'The durable identity of one business transaction''s accounting effect: what asked for the '
    'posting, which rule version answered, and the idempotency key that makes a retry a no-op. '
    'One row per source reference, ever. Mutable only inside the posting transaction that claims '
    'it; a rejected posting rolls back with its row, so no committed row is ever PENDING.';
COMMENT ON COLUMN posting_request.source_entity_id IS
    'Identity of the originating business entity, for drill-down. Descriptive: never a foreign '
    'key, because the module that owns it may not exist yet (INV-16).';
COMMENT ON COLUMN posting_request.source_reference IS
    'The durable idempotency identity (INV-7). uq_posting_request_source is the one domain-level '
    'idempotency mechanism; api_idempotency_record handles the HTTP Idempotency-Key at the web '
    'boundary and the two are never conflated.';
COMMENT ON COLUMN posting_request.request_fingerprint IS
    'SHA-256, lower-case hex, of a canonical rendering of the request - source triple, event, '
    'dates, currency and legs - so a retry can be told from a conflicting reuse of the same key '
    'without storing the request itself.';
COMMENT ON COLUMN posting_request.posting_rule_version_id IS
    'The exact rule version that resolved the legs; null for a reversal or a manual journal. '
    'Its foreign key is added by the migration that creates posting_rule_version (issue #44).';
COMMENT ON COLUMN posting_request.corrects_posting_request_id IS
    'Correction lineage: the request this replacement posting corrects, after its journal was '
    'reversed. Kept on the mutable request so the immutable journal stays minimal.';
COMMENT ON COLUMN posting_request.status IS
    'PENDING between the idempotency claim and the journal write, which is visible only inside '
    'the posting transaction; POSTED once committed. There is no REJECTED: a rejected posting '
    'rolls back with the transaction that attempted it.';
COMMENT ON CONSTRAINT chk_posting_request_dates ON posting_request IS
    'Neither the posting date nor the transaction date may follow the tenant business date, '
    'mirroring PostingDatePolicy so a row written outside the application obeys the same rule.';

-- ---------------------------------------------------------------------------------------------
-- journal_entry: the balanced, immutable header
-- ---------------------------------------------------------------------------------------------

CREATE TABLE journal_entry (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    branch_id UUID,
    posting_request_id UUID NOT NULL,
    fiscal_period_id UUID NOT NULL,
    entry_number BIGINT NOT NULL,
    entry_type TEXT NOT NULL,
    reverses_journal_entry_id UUID,
    business_date DATE NOT NULL,
    transaction_date DATE NOT NULL,
    value_date DATE NOT NULL,
    posting_date DATE NOT NULL,
    currency_code CHAR(3) NOT NULL,
    functional_currency_code CHAR(3) NOT NULL,
    total_debit_functional NUMERIC(23, 6) NOT NULL,
    total_credit_functional NUMERIC(23, 6) NOT NULL,
    line_count INTEGER NOT NULL,
    narrative TEXT,
    posted_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    CONSTRAINT uq_journal_entry_guid UNIQUE (guid),
    CONSTRAINT uq_journal_entry_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_journal_entry_number UNIQUE (organisation_id, entry_number),
    CONSTRAINT uq_journal_entry_posting_request UNIQUE (organisation_id, posting_request_id),
    CONSTRAINT fk_journal_entry_posting_request
        FOREIGN KEY (organisation_id, posting_request_id)
        REFERENCES posting_request (organisation_id, id),
    CONSTRAINT fk_journal_entry_fiscal_period
        FOREIGN KEY (organisation_id, fiscal_period_id)
        REFERENCES accounting_fiscal_period (organisation_id, id),
    CONSTRAINT fk_journal_entry_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT fk_journal_entry_reverses
        FOREIGN KEY (organisation_id, reverses_journal_entry_id)
        REFERENCES journal_entry (organisation_id, id),
    CONSTRAINT chk_journal_entry_number CHECK (entry_number > 0),
    CONSTRAINT chk_journal_entry_type CHECK (entry_type IN ('STANDARD', 'MANUAL', 'REVERSAL')),
    CONSTRAINT chk_journal_entry_reversal_link
        CHECK ((entry_type = 'REVERSAL') = (reverses_journal_entry_id IS NOT NULL)),
    CONSTRAINT chk_journal_entry_not_self_reversal
        CHECK (reverses_journal_entry_id IS NULL OR reverses_journal_entry_id <> id),
    CONSTRAINT chk_journal_entry_balanced
        CHECK (total_debit_functional = total_credit_functional),
    CONSTRAINT chk_journal_entry_total_positive CHECK (total_debit_functional > 0),
    CONSTRAINT chk_journal_entry_line_count CHECK (line_count >= 2),
    CONSTRAINT chk_journal_entry_dates
        CHECK (posting_date <= business_date AND transaction_date <= business_date),
    CONSTRAINT chk_journal_entry_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_journal_entry_functional_currency
        CHECK (functional_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_journal_entry_narrative
        CHECK (narrative IS NULL OR char_length(narrative) <= 500)
);

CREATE UNIQUE INDEX uq_journal_entry_reversal_once
    ON journal_entry (organisation_id, reverses_journal_entry_id)
    WHERE reverses_journal_entry_id IS NOT NULL;
CREATE INDEX idx_journal_entry_posting_date ON journal_entry (organisation_id, posting_date, id);

COMMENT ON TABLE journal_entry IS
    'The balanced header of one journal: totals, gapless entry number, reversal link, branch and '
    'fiscal-period binding. Immutable once committed - no updated_at, updated_by or row_version to '
    'maintain, and REVOKE UPDATE, DELETE under issue #54. Correction is a new REVERSAL entry, '
    'never a change to this row (ADR 0020).';
COMMENT ON COLUMN journal_entry.entry_number IS
    'Gapless per tenant, allocated from reference_sequence code JOURNAL under a row lock rather '
    'than a PostgreSQL sequence, because a sequence loses gaplessness on every rollback.';
COMMENT ON COLUMN journal_entry.entry_type IS
    'STANDARD from a product module''s posting intent through a posting rule; MANUAL from an '
    'approved manual journal; REVERSAL for the equal-and-opposite entry that corrects another. '
    'There is deliberately no CORRECTION: a correction is a reversal followed by a fresh posting.';
COMMENT ON COLUMN journal_entry.reverses_journal_entry_id IS
    'The journal this one reverses. Set exactly when entry_type is REVERSAL '
    '(chk_journal_entry_reversal_link); at most one reversal per journal '
    '(uq_journal_entry_reversal_once). "Has this been reversed" is derived from it, never stored '
    'on the original row.';
COMMENT ON COLUMN journal_entry.posting_date IS
    'The only column that selects a fiscal period (INV-9). Not the business date: a backdated '
    'correction has posting_date < business_date.';
COMMENT ON CONSTRAINT chk_journal_entry_balanced ON journal_entry IS
    'The header half of INV-4. A row-level CHECK cannot sum the lines, so the posting engine '
    're-reads them inside the posting transaction and the header-versus-lines proof query detects '
    'any drift after the fact.';

-- ---------------------------------------------------------------------------------------------
-- journal_line: one debit or credit against one GL account
-- ---------------------------------------------------------------------------------------------

CREATE TABLE journal_line (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    journal_entry_id UUID NOT NULL,
    line_number INTEGER NOT NULL,
    gl_account_id UUID NOT NULL,
    branch_id UUID,
    fiscal_period_id UUID NOT NULL,
    posting_date DATE NOT NULL,
    direction TEXT NOT NULL,
    currency_code CHAR(3) NOT NULL,
    amount NUMERIC(23, 6) NOT NULL,
    functional_currency_code CHAR(3) NOT NULL,
    functional_amount NUMERIC(23, 6) NOT NULL,
    exchange_rate NUMERIC(20, 10) NOT NULL,
    signed_functional_amount NUMERIC(23, 6) NOT NULL GENERATED ALWAYS AS (
        CASE WHEN direction = 'DEBIT' THEN functional_amount ELSE -functional_amount END
    ) STORED,
    source_module TEXT NOT NULL,
    subledger_reference TEXT,
    narrative TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    CONSTRAINT uq_journal_line_guid UNIQUE (guid),
    CONSTRAINT uq_journal_line_entry_number UNIQUE (organisation_id, journal_entry_id, line_number),
    CONSTRAINT fk_journal_line_entry
        FOREIGN KEY (organisation_id, journal_entry_id)
        REFERENCES journal_entry (organisation_id, id),
    CONSTRAINT fk_journal_line_account
        FOREIGN KEY (organisation_id, gl_account_id) REFERENCES gl_account (organisation_id, id),
    CONSTRAINT fk_journal_line_branch
        FOREIGN KEY (organisation_id, branch_id) REFERENCES branch (organisation_id, id),
    CONSTRAINT fk_journal_line_fiscal_period
        FOREIGN KEY (organisation_id, fiscal_period_id)
        REFERENCES accounting_fiscal_period (organisation_id, id),
    CONSTRAINT chk_journal_line_number CHECK (line_number > 0),
    CONSTRAINT chk_journal_line_direction CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT chk_journal_line_amount CHECK (amount > 0),
    CONSTRAINT chk_journal_line_functional_amount CHECK (functional_amount > 0),
    CONSTRAINT chk_journal_line_exchange_rate CHECK (exchange_rate > 0),
    CONSTRAINT chk_journal_line_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_journal_line_functional_currency
        CHECK (functional_currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT chk_journal_line_source_module CHECK (source_module ~ '^[a-z][a-z0-9_]{0,63}$'),
    CONSTRAINT chk_journal_line_subledger_reference
        CHECK (subledger_reference IS NULL OR char_length(subledger_reference) BETWEEN 1 AND 200),
    CONSTRAINT chk_journal_line_narrative
        CHECK (narrative IS NULL OR char_length(narrative) <= 500)
);

CREATE INDEX idx_journal_line_account_date
    ON journal_line (organisation_id, gl_account_id, posting_date, id)
    INCLUDE (direction, functional_amount, branch_id, journal_entry_id);

COMMENT ON TABLE journal_line IS
    'One debit or credit against one GL account. Append-only under a hard no-update rule, which '
    'is the only reason it may safely denormalise branch_id, fiscal_period_id, posting_date and '
    'the currency codes from its header: the copies cannot drift.';
COMMENT ON COLUMN journal_line.direction IS
    'Carries the sign (INV-3). Amounts are always positive; a signed amount would make "debit or '
    'credit" depend on the account''s normal balance and would make a zero line representable.';
COMMENT ON COLUMN journal_line.functional_amount IS
    'The column the double-entry balance invariant is enforced over (INV-4). Transaction-currency '
    'amounts never have to balance across currencies.';
COMMENT ON COLUMN journal_line.signed_functional_amount IS
    'Generated, never written: functional_amount for a debit, its negation for a credit. For '
    'ad-hoc and reconciliation SQL, so an analyst never hand-writes the CASE and gets it inverted.';
COMMENT ON COLUMN journal_line.gl_account_id IS
    'ACTIVE and POSTABLE at posting time, enforced by the posting engine; the schema enforces '
    'tenant scope only, because an account legitimately becomes INACTIVE after it has lines.';
COMMENT ON COLUMN journal_line.subledger_reference IS
    'The product-owned position this line moved, for drill-down and control-account '
    'reconciliation only (Q5, Q6). Descriptive, no foreign key (INV-16). Never a statement source: '
    'a member statement is a subsidiary-ledger question (Q2).';
COMMENT ON INDEX idx_journal_line_account_date IS
    'The one index that carries the ledger''s read load: Q1 account ledger, Q3 movements, Q7 '
    'keyset pages and the header-versus-lines proof. Tenant first, posting_date in the key, id '
    'last for a total sort order, payload columns in INCLUDE for index-only aggregates.';

-- ---------------------------------------------------------------------------------------------
-- reference_sequence backfill: the gapless JOURNAL counter every existing organisation lacks
-- ---------------------------------------------------------------------------------------------
--
-- OrganisationProvisioningService seeds MEMBER, TRANSACTION and JOURNAL when it approves an
-- organisation, but V2 and V3 created the PLATFORM and bootstrap tenants in SQL with no
-- reference_sequence rows at all, so the first journal for either would have no counter to lock.
-- Inserting the three codes for every organisation that lacks them, with ON CONFLICT DO NOTHING
-- on uq_reference_sequence_organisation_code, leaves application-provisioned tenants untouched.
-- created_by/updated_by is the platform system actor, the same sentinel the provisioning store
-- writes.

INSERT INTO reference_sequence (
    organisation_id, sequence_code, next_value, created_at, created_by, updated_at, updated_by
)
SELECT o.id, codes.sequence_code, 1, NOW(),
       '00000000-0000-0000-0000-000000000001', NOW(), '00000000-0000-0000-0000-000000000001'
FROM organisation o
CROSS JOIN (VALUES ('MEMBER'), ('TRANSACTION'), ('JOURNAL')) AS codes (sequence_code)
ON CONFLICT ON CONSTRAINT uq_reference_sequence_organisation_code DO NOTHING;
