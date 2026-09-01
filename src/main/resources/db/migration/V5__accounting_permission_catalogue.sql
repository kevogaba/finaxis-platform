-- Seeds the accounting permission catalogue (GitHub issue #34) ahead of the accounting schema, so
-- the chart-of-accounts, fiscal-calendar, journal and posting-rule work in Phase B has its
-- authorization vocabulary already in place and does not have to add codes piecemeal.
--
-- Reference-data only: no tables, no columns, no constraints.
--
-- Identifier block allocation. V2 froze 40000000-0000-0000-0000-0000000000NN as the foundation
-- permission block. Accounting takes 41000000-…, contiguous from 01. Every future domain takes its
-- own 4X000000-… block rather than filling the gaps V2 left (…26-29, …34-39), because interleaving
-- domain codes into the foundation numbering makes ORDER BY id unreadable.
--
-- Runtime authorization stays permission-code based. The two accounting roles created at
-- organisation-approval time (ACCOUNTING_OPERATOR, ACCOUNTING_APPROVER, in
-- OrganisationBootstrapDefaults) are composable bundles; nothing anywhere evaluates a role name.

-- Preconditions. Each asserts a fact this migration actually depends on, rather than a general
-- health check: an exact catalogue-size assertion would break any deployment legitimately carrying
-- an out-of-band permission and would assert something V5 does not rely on.
DO $$
DECLARE
    active_platform_roles INT;
    bootstrap_admin_roles INT;
    existing_accounting   INT;
BEGIN
    -- Only PLATFORM_SUPER_ADMIN, and only its existence. This migration grants to that role, so
    -- the row must exist for the foreign key; it never touches PLATFORM_SUPPORT, and role status
    -- does not affect a role_permission insert. Asserting either would fail the deploy over a
    -- fact V5 does not depend on and block every later migration behind it.
    SELECT COUNT(*) INTO active_platform_roles
    FROM role
    WHERE id = '50000000-0000-0000-0000-000000000001'
      AND organisation_id = '00000000-0000-0000-0000-000000000000';
    IF active_platform_roles <> 1 THEN
        RAISE EXCEPTION
            'Expected the V2 PLATFORM_SUPER_ADMIN role to exist but found %', active_platform_roles;
    END IF;

    -- Existence only, for the same reason: fk_role_permission_role is keyed on
    -- (organisation_id, role_id) and is status-independent. Deactivating the shipped demo role is
    -- a legitimate hardening step and must not fail this migration.
    SELECT COUNT(*) INTO bootstrap_admin_roles
    FROM role
    WHERE id = '77777777-7777-7777-7777-777777777777'
      AND organisation_id = '22222222-2222-2222-2222-222222222222';
    IF bootstrap_admin_roles <> 1 THEN
        RAISE EXCEPTION
            'Expected the V3 bootstrap local-admin role to exist but found %',
            bootstrap_admin_roles;
    END IF;

    -- Deliberately NOT asserted here: that PLATFORM_SUPER_ADMIN already holds every pre-existing
    -- permission. That is a property of earlier migrations, not something V5 depends on, and
    -- asserting it would break exactly the deployment this file's header promises not to break -
    -- one legitimately carrying an out-of-band permission that no role happens to grant. V5 seeds
    -- its own codes and grants them; a pre-existing inconsistency is left exactly as found. The
    -- post-condition below checks what V5 is actually responsible for.

    SELECT COUNT(*) INTO existing_accounting
    FROM permission
    WHERE module_code = 'accounting' OR id::text LIKE '41000000-%';
    IF existing_accounting <> 0 THEN
        RAISE EXCEPTION
            'Found % pre-existing accounting permission row(s); refusing to seed',
            existing_accounting;
    END IF;
END $$;

-- The catalogue. 26 codes under a new `accounting` module code: the existing module codes name the
-- owning capability area, and #31 created a real com.finaxis.platform.accounting module for these
-- to name.
--
-- Grouping decisions, argued in docs/security/accounting-authorization.md: `activate` folds into
-- `approve` for both the chart of accounts and posting rules, because the FSM has one
-- PENDING_APPROVAL -> ACTIVE transition and a separate code would gate nothing an approver does not
-- already do. `post` folds into `journal.approve`, because an approved-but-unposted journal is a
-- promise the ledger cannot audit. Fiscal year and period share four codes, because the year is a
-- container and no realistic role closes periods but not years. Trial balance, GL ledger,
-- statements and drill-down share accounting_report.view, being the same data at different
-- aggregations.
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
) VALUES
    ('41000000-0000-0000-0000-000000000001', 'gl_account.view', 'View chart of accounts',
        'accounting', 'View the organisation chart of accounts.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000002', 'gl_account.create', 'Create GL account',
        'accounting', 'Create a general-ledger account draft.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000003', 'gl_account.update', 'Update GL account',
        'accounting', 'Amend a general-ledger account.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000004', 'gl_account.submit',
        'Submit GL account for approval', 'accounting',
        'Submit a general-ledger account for approval.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000005', 'gl_account.approve', 'Approve GL account',
        'accounting', 'Approve and activate a general-ledger account.', 'HIGH', 'ACTIVE',
        NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000006', 'gl_account.deactivate', 'Deactivate GL account',
        'accounting', 'Stop posting to a general-ledger account.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000007', 'fiscal_period.view', 'View fiscal periods',
        'accounting', 'View fiscal years and periods.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000008', 'fiscal_period.open', 'Open fiscal period',
        'accounting', 'Open a fiscal year or period for posting.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000009', 'fiscal_period.close', 'Close fiscal period',
        'accounting', 'Close a fiscal year or period.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000010', 'fiscal_period.reopen', 'Reopen fiscal period',
        'accounting', 'Reopen a closed fiscal period.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000011', 'journal.view', 'View journals', 'accounting',
        'View journal entries and lines.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000012', 'journal.create_manual', 'Create manual journal',
        'accounting', 'Prepare a manual journal.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000013', 'journal.submit', 'Submit journal for approval',
        'accounting', 'Submit a manual journal for approval.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000014', 'journal.approve', 'Approve and post journal',
        'accounting', 'Approve a manual journal, which posts it.', 'CRITICAL', 'ACTIVE',
        NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000015', 'journal.reverse', 'Reverse journal', 'accounting',
        'Reverse a posted journal with a contra entry.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000016', 'journal.post_prior_period',
        'Post into a prior period', 'accounting',
        'Post into a period earlier than the business date.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000017', 'posting_rule.view', 'View posting rules',
        'accounting', 'View posting rules and their versions.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000018', 'posting_rule.create', 'Create posting rule',
        'accounting', 'Create a posting rule.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000019', 'posting_rule.update', 'Create posting-rule version',
        'accounting', 'Create a new version of a posting rule.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000020', 'posting_rule.submit',
        'Submit posting rule for approval', 'accounting',
        'Submit a posting-rule version for approval.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000021', 'posting_rule.approve',
        'Approve and activate posting rule', 'accounting',
        'Approve a posting-rule version, which activates it.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000022', 'reconciliation.view', 'View reconciliations',
        'accounting', 'View control-account reconciliation results.', 'LOW', 'ACTIVE',
        NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000023', 'reconciliation.run', 'Run reconciliation',
        'accounting', 'Run a control-account reconciliation.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000024', 'reconciliation.resolve', 'Resolve reconciliation',
        'accounting', 'Resolve or override a reconciliation break.', 'CRITICAL', 'ACTIVE',
        NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000025', 'accounting_report.view',
        'View accounting reports', 'accounting',
        'View trial balance, GL ledger and financial statements.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('41000000-0000-0000-0000-000000000026', 'accounting_report.export',
        'Export accounting reports', 'accounting',
        'Export accounting reports out of the platform.', 'MEDIUM', 'ACTIVE', NOW(), NOW());

-- PLATFORM_SUPER_ADMIN keeps the whole catalogue. V2's set-based grant ran once, at V2, so it
-- cannot pick these up on an existing installation.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    '00000000-0000-0000-0000-000000000000',
    '50000000-0000-0000-0000-000000000001',
    p.id,
    NOW(),
    NOW(),
    NOW()
FROM permission p
WHERE p.module_code = 'accounting'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- PLATFORM_SUPPORT deliberately receives nothing. Platform staff must not read tenant financial
-- data by default; a support-escalation role is a separate, audited decision.

-- The bootstrap local-admin role receives exactly the tenant-CONFIGURATION subset, so a fresh
-- deployment can set up a chart of accounts and a fiscal calendar without any user holding
-- operational or break-glass accounting rights. Approval is included because V4 already seeded
-- local.checker holding the same role, so the maker-checker chart-of-accounts flow is exercisable
-- out of the box with two distinct actors - which is exactly the gap V4 existed to close.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    '22222222-2222-2222-2222-222222222222',
    '77777777-7777-7777-7777-777777777777',
    p.id,
    NOW(),
    NOW(),
    NOW()
FROM permission p
WHERE p.permission_code IN (
        'gl_account.view',
        'gl_account.create',
        'gl_account.update',
        'gl_account.submit',
        'gl_account.approve',
        'gl_account.deactivate',
        'fiscal_period.view',
        'fiscal_period.open',
        'posting_rule.view',
        'accounting_report.view'
    )
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- Post-conditions. The migration enforces what the tests assert, so a mistake surfaces in every
-- environment on deployment rather than only in CI.
DO $$
DECLARE
    seeded      INT;
    ungranted   INT;
    local_admin INT;
BEGIN
    SELECT COUNT(*) INTO seeded FROM permission WHERE module_code = 'accounting';
    IF seeded <> 26 THEN
        RAISE EXCEPTION 'Expected 26 accounting permissions after seeding but found %', seeded;
    END IF;

    -- Scoped to accounting: V5 is responsible for granting the codes it seeds, not for the state
    -- of grants it never touched. Counting the whole catalogue here would fail a deployment that
    -- legitimately carries an out-of-band permission, which is the case this file must not break.
    SELECT COUNT(*) INTO ungranted
    FROM permission p
    WHERE p.module_code = 'accounting'
      AND NOT EXISTS (
        SELECT 1
        FROM role_permission rp
        WHERE rp.permission_id = p.id
          AND rp.role_id = '50000000-0000-0000-0000-000000000001'
    );
    IF ungranted <> 0 THEN
        RAISE EXCEPTION
            'PLATFORM_SUPER_ADMIN is missing % accounting permission(s) after this migration',
            ungranted;
    END IF;

    SELECT COUNT(*) INTO local_admin
    FROM role_permission rp
    JOIN permission p ON p.id = rp.permission_id
    WHERE rp.role_id = '77777777-7777-7777-7777-777777777777'
      AND p.module_code = 'accounting';
    IF local_admin <> 10 THEN
        RAISE EXCEPTION
            'Expected 10 accounting grants on the bootstrap local-admin role but found %',
            local_admin;
    END IF;
END $$;
