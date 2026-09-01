-- Issue #36: the first accounting-owned schema - fiscal calendar and chart of accounts.
--
-- Transcribed from docs/database/accounting-erd.md, which is the design authority. Every column,
-- constraint, index and comment below appears there first; if this file and that document
-- disagree, this file is wrong. The document is the authority, so correct the migration to
-- match it (forward-only, in a later version) rather than quietly diverging.
--
-- No journal table is created here. `posting_date` selects a fiscal period (INV-9) and the
-- journal tables that carry it belong to issue #40.

-- ---------------------------------------------------------------------------------------------
-- btree_gist, installed outside `public` on purpose.
--
-- A tenant-scoped exclusion constraint needs GiST to index `organisation_id WITH =` alongside the
-- date range, and PostgreSQL 18 ships no built-in GiST operator class for `uuid` - only btree,
-- hash and BRIN. btree_gist supplies `gist_uuid_ops`.
--
-- It is installed into a dedicated schema because jOOQ code generation reads `inputSchema =
-- "public"` with no excludes and `compileKotlin` depends on `jooqCodegen`. Installed into
-- `public`, the extension's ~160 functions and 5 composite types would be generated into
-- `com.finaxis.platform.jooq` on every build. Installed into `extensions`, codegen never sees it,
-- and the opclass is named schema-qualified below so the DDL does not depend on `search_path`.
-- The constraint stores the opclass by OID, so nothing needs `extensions` on its path at runtime.
--
-- This is the repository's first extension. A deploy role therefore needs CREATE on the database.
-- btree_gist has been a trusted extension since PostgreSQL 13, so a database owner can install it
-- without superuser.
--
-- The install must RELOCATE an existing installation, not assume there is none. `CREATE EXTENSION
-- IF NOT EXISTS btree_gist WITH SCHEMA extensions` is not sufficient: when the extension already
-- exists anywhere, PostgreSQL emits `NOTICE: extension "btree_gist" already exists, skipping` and
-- ignores WITH SCHEMA entirely. On any database where an operator or a managed-PostgreSQL image
-- pre-installed it into `public` - which is common - it would stay in `public` and the first
-- EXCLUDE constraint below would fail with `operator class "extensions.gist_uuid_ops" does not
-- exist for access method "gist"`, failing the migration and blocking startup. Verified against
-- postgres:18.4 in all three states: pre-installed in public, absent, and already correct.
CREATE SCHEMA IF NOT EXISTS extensions;

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'btree_gist') THEN
        CREATE EXTENSION btree_gist WITH SCHEMA extensions;
    ELSIF (
        SELECT n.nspname
        FROM pg_extension e
        JOIN pg_namespace n ON n.oid = e.extnamespace
        WHERE e.extname = 'btree_gist'
    ) <> 'extensions' THEN
        ALTER EXTENSION btree_gist SET SCHEMA extensions;
    END IF;
END
$$;

COMMENT ON SCHEMA extensions IS
    'Holds PostgreSQL extensions so jOOQ code generation, which reads only public, never sees '
    'them.';

-- ---------------------------------------------------------------------------------------------
-- Fiscal calendar
-- ---------------------------------------------------------------------------------------------

CREATE TABLE accounting_fiscal_year (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    year_code TEXT NOT NULL,
    year_name TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_accounting_fiscal_year_guid UNIQUE (guid),
    CONSTRAINT uq_accounting_fiscal_year_organisation_code UNIQUE (organisation_id, year_code),
    CONSTRAINT uq_accounting_fiscal_year_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT chk_accounting_fiscal_year_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_accounting_fiscal_year_version CHECK (row_version >= 0),
    CONSTRAINT ex_accounting_fiscal_year_no_overlap EXCLUDE USING gist (
        organisation_id extensions.gist_uuid_ops WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    )
);

COMMENT ON TABLE accounting_fiscal_year IS
    'Tenant-scoped fiscal calendar container. Deliberately carries no status: a year is closed '
    'exactly when all of its periods are, which is derivable and therefore cannot drift.';
COMMENT ON CONSTRAINT ex_accounting_fiscal_year_no_overlap ON accounting_fiscal_year IS
    'Two fiscal years in one tenant may not cover the same date. Bounds are inclusive on both '
    'ends, so adjacent years must be a day apart.';

CREATE TABLE accounting_fiscal_period (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    fiscal_year_id UUID NOT NULL,
    period_number INTEGER NOT NULL,
    period_name TEXT NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    status TEXT NOT NULL,
    status_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_accounting_fiscal_period_guid UNIQUE (guid),
    CONSTRAINT uq_accounting_fiscal_period_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_accounting_fiscal_period_year_number
        UNIQUE (organisation_id, fiscal_year_id, period_number),
    CONSTRAINT fk_accounting_fiscal_period_year
        FOREIGN KEY (organisation_id, fiscal_year_id)
        REFERENCES accounting_fiscal_year (organisation_id, id),
    CONSTRAINT chk_accounting_fiscal_period_number CHECK (period_number > 0),
    CONSTRAINT chk_accounting_fiscal_period_dates CHECK (end_date >= start_date),
    CONSTRAINT chk_accounting_fiscal_period_status
        CHECK (status IN ('FUTURE', 'OPEN', 'CLOSED', 'LOCKED')),
    CONSTRAINT chk_accounting_fiscal_period_version CHECK (row_version >= 0),
    CONSTRAINT ex_accounting_fiscal_period_no_overlap EXCLUDE USING gist (
        organisation_id extensions.gist_uuid_ops WITH =,
        daterange(start_date, end_date, '[]') WITH &&
    )
);

CREATE INDEX idx_accounting_fiscal_period_year
    ON accounting_fiscal_period (organisation_id, fiscal_year_id);

COMMENT ON TABLE accounting_fiscal_period IS
    'The unit a posting binds to. posting_date alone selects it (INV-9), and a journal may only '
    'be created while it is OPEN.';
COMMENT ON COLUMN accounting_fiscal_period.status IS
    'FUTURE provisioned but not yet postable; OPEN postable; CLOSED reopenable by an audited '
    'privileged operation; LOCKED permanently final and never reopenable.';
COMMENT ON CONSTRAINT ex_accounting_fiscal_period_no_overlap ON accounting_fiscal_period IS
    'Scoped to the tenant rather than the fiscal year: a posting date must resolve to exactly one '
    'period across the whole chart, so periods in different years may not overlap either.';

-- ---------------------------------------------------------------------------------------------
-- Chart of accounts
-- ---------------------------------------------------------------------------------------------

CREATE TABLE gl_account (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    account_code TEXT NOT NULL,
    account_name TEXT NOT NULL,
    description TEXT,
    account_class TEXT NOT NULL,
    account_usage TEXT NOT NULL,
    -- Derived, never supplied. The side is a total function of the class and the contra flag:
    -- an ordinary ASSET/EXPENSE is a debit, its contra is a credit, and the reverse for the
    -- other three classes. Storing it as an independent column would create a second source
    -- of truth that a CHECK could only police, and the caller-supplied form is exactly what
    -- admitted a contra ASSET with a DEBIT balance. If an API ever accepts an explicit
    -- normal_balance it validates against this value at the boundary and never writes it.
    normal_balance TEXT NOT NULL GENERATED ALWAYS AS (
        CASE
            WHEN (account_class IN ('ASSET', 'EXPENSE')) <> is_contra_account THEN 'DEBIT'
            ELSE 'CREDIT'
        END
    ) STORED,
    is_contra_account BOOLEAN NOT NULL DEFAULT FALSE,
    manual_posting_allowed BOOLEAN NOT NULL DEFAULT FALSE,
    parent_account_id UUID,
    parent_account_usage TEXT GENERATED ALWAYS AS (
        CASE WHEN parent_account_id IS NULL THEN NULL ELSE 'HEADER' END
    ) STORED,
    status TEXT NOT NULL,
    status_reason TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_gl_account_guid UNIQUE (guid),
    CONSTRAINT uq_gl_account_organisation_code UNIQUE (organisation_id, account_code),
    CONSTRAINT uq_gl_account_organisation_id UNIQUE (organisation_id, id),
    CONSTRAINT uq_gl_account_parent_target UNIQUE (organisation_id, id, account_usage),
    CONSTRAINT fk_gl_account_parent_same_organisation
        FOREIGN KEY (organisation_id, parent_account_id, parent_account_usage)
        REFERENCES gl_account (organisation_id, id, account_usage),
    CONSTRAINT chk_gl_account_not_own_parent
        CHECK (parent_account_id IS NULL OR parent_account_id <> id),
    CONSTRAINT chk_gl_account_class
        CHECK (account_class IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE')),
    CONSTRAINT chk_gl_account_usage CHECK (account_usage IN ('HEADER', 'POSTABLE')),
    CONSTRAINT chk_gl_account_manual_posting
        CHECK (account_usage = 'POSTABLE' OR NOT manual_posting_allowed),
    CONSTRAINT chk_gl_account_status
        CHECK (status IN ('DRAFT', 'PENDING_APPROVAL', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT chk_gl_account_code CHECK (account_code ~ '^[A-Za-z0-9._-]{1,32}$'),
    CONSTRAINT chk_gl_account_version CHECK (row_version >= 0)
);

CREATE INDEX idx_gl_account_organisation_status ON gl_account (organisation_id, status);
CREATE INDEX idx_gl_account_parent ON gl_account (organisation_id, parent_account_id);

COMMENT ON TABLE gl_account IS
    'Tenant-scoped chart of accounts. Never branch-scoped: branch is a dimension on the posting, '
    'carried on journal_entry and journal_line.';
COMMENT ON COLUMN gl_account.account_usage IS
    'HEADER groups other accounts and can never receive a journal line; POSTABLE can. Only a '
    'HEADER account may be a parent, which fk_gl_account_parent_same_organisation enforces.';
COMMENT ON COLUMN gl_account.normal_balance IS
    'The side that increases the account, generated from account_class and is_contra_account and '
    'never supplied by a caller. An ordinary ASSET or EXPENSE is DEBIT and its contra is CREDIT; '
    'the other three classes are the reverse.';
COMMENT ON COLUMN gl_account.is_contra_account IS
    'Marks a deliberate inversion of the class-implied normal balance - an allowance for loan '
    'impairment, or accumulated depreciation. Presented as a deduction from its class, never as a '
    'balance of the opposite class.';
COMMENT ON COLUMN gl_account.manual_posting_allowed IS
    'Whether a manual journal may target this account. Defaults to false: an account fed by a '
    'posting rule or a subsidiary ledger should refuse hand-written entries unless it opts in.';
COMMENT ON COLUMN gl_account.parent_account_usage IS
    'Generated, never written by the application. Its only purpose is to carry the literal HEADER '
    'into fk_gl_account_parent_same_organisation, which is what makes "a parent must be a header '
    'account" a database guarantee rather than an application convention.';
COMMENT ON COLUMN gl_account.status IS
    'DRAFT, PENDING_APPROVAL, ACTIVE, INACTIVE. There is deliberately no REJECTED: rejection '
    'returns the account to DRAFT, so a rejected change cannot strand its account_code forever '
    'under uq_gl_account_organisation_code.';
COMMENT ON CONSTRAINT chk_gl_account_code ON gl_account IS
    'The same bound the AccountCode value object enforces. Without it a row written outside the '
    'application - a data migration, a bulk import, an operator fix - could hold a code the value '
    'object refuses, and every later read of that tenant chart would throw while constructing it.';
COMMENT ON CONSTRAINT chk_gl_account_not_own_parent ON gl_account IS
    'Row-local half of cycle prevention. Longer cycles are not expressible in a CHECK and are '
    'rejected by ChartHierarchyPolicy (issue #37).';

-- ---------------------------------------------------------------------------------------------
-- Transition logs
-- ---------------------------------------------------------------------------------------------

CREATE TABLE gl_account_transition_log (
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
    CONSTRAINT uq_gl_account_transition_log_guid UNIQUE (guid),
    CONSTRAINT fk_gl_account_transition_log_account
        FOREIGN KEY (organisation_id, entity_id) REFERENCES gl_account (organisation_id, id),
    CONSTRAINT chk_gl_account_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_gl_account_transition_log_organisation_entity
    ON gl_account_transition_log (organisation_id, entity_id, created_at DESC);

COMMENT ON TABLE gl_account_transition_log IS
    'Append-only history of chart-of-accounts lifecycle transitions, written by the common '
    'transition executor. Issue #38 declares the transitions it records.';

CREATE TABLE fiscal_period_transition_log (
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
    CONSTRAINT uq_fiscal_period_transition_log_guid UNIQUE (guid),
    CONSTRAINT fk_fiscal_period_transition_log_period
        FOREIGN KEY (organisation_id, entity_id)
        REFERENCES accounting_fiscal_period (organisation_id, id),
    CONSTRAINT chk_fiscal_period_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX idx_fiscal_period_transition_log_organisation_entity
    ON fiscal_period_transition_log (organisation_id, entity_id, created_at DESC);

COMMENT ON TABLE fiscal_period_transition_log IS
    'Append-only history of fiscal-period open, close, reopen and lock. A period status change is '
    'a transition with a log row, never a bare column update. Issue #39 declares the transitions.';
