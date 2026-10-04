-- ADR 0030 decisions 7 and 8: the permission catalogue learns what each code IS and which views a
-- mutation implies.
--
-- WHAT. One forward-only migration of catalogue metadata, with no new permission and no change to
-- any grant:
--
--   1. permission.kind - VIEW, MUTATION or CONTEXT;
--   2. permission.grant_scope - TENANT or PLATFORM;
--   3. permission_view_requirement - a join table pairing a MUTATION code with each VIEW code it
--      requires, so a role or a caller that holds the mutation must also hold those views.
--
-- Every existing code is classified and paired below, and both new columns are then made NOT NULL.
-- THAT IS THE POINT OF NOT NULL: a later migration that inserts a permission without a kind and a
-- grant_scope now fails to apply, so a new code cannot ship unclassified, and
-- PermissionCatalogueMetadataTests fails until a MUTATION code is paired with a view. FUTURE
-- PERMISSION MIGRATIONS MUST THEREFORE INSERT kind, grant_scope AND, FOR A MUTATION, ITS
-- permission_view_requirement ROWS in the same file as the permission row.
--
-- WHY. The owner's rule (ADR 0030) is that a role holding a mutation permission also holds the
-- matching view permission, so a caller can see what it wrote. The rule needs a map from every
-- mutation to its views; the scope of each code is also meant to feed a separate, later decision
-- on deriving the seeded admin roles ("an admin holds every permission of its scope"). The map
-- is policy that has to stay next to the codes it describes, readable by operators without the
-- application, and changed only together with the migration that adds a code, so it lives in the
-- catalogue, not in Kotlin (ADR 0030, decision 8). The runtime enforcement, the role-composition
-- check and the named 403 arrive in later changes that read these rows; this file only records
-- the facts and publishes them in the permission catalogue API.
--
-- KIND.
--
--   * VIEW (18): reads of a resource, which require nothing: tenant.view, branch.view, user.view,
--     membership.view, branch_assignment.view, role.view, role_assignment.view, permission.view,
--     settings.view, business_date.view, audit.view, iam.profile.read (a read by definition) and
--     the six accounting views (gl_account, fiscal_period, journal, posting_rule, reconciliation,
--     accounting_report).
--   * CONTEXT (3): auth.select_organisation, auth.select_branch and tenant_setting.manage_platform.
--     The first two gate the caller's own session context, being their own read and write. The
--     third gates both reading and writing a platform-only setting, is evaluated in the PLATFORM
--     organisation while settings.view lives in a tenant, so no role could hold both. They are
--     exempt: they require nothing, and they are not views.
--   * MUTATION (60): everything else. Each requires the view of its own resource family (the
--     resource it writes and returns), per the table below.
--
-- THE PAIRINGS that needed a decision (accepted by the owner, ADR 0030 point 7):
--
--   * user.invite requires membership.view AND user.view: it creates a membership and its Location
--     is the user. This is why the map is a join table and not a column.
--   * user.assign_branch and user.revoke_branch require branch_assignment.view, and no more.
--   * user.assign_role and user.revoke_role require role_assignment.view, and no more.
--   * role.assign_permission and role.remove_permission require role.view, not permission.view:
--     browsing the catalogue is not seeing one's own write.
--   * tenant.activate requires tenant.view and branch.approve requires branch.view.
--     tenant.activate is checked by no route (retiring it is a separate decision);
--     branch.approve has been THE branch-approval code since V21. Both are paired like their
--     siblings.
--   * branch.activate, DEPRECATED by V21, is classified like the rest and requires branch.view.
--     A deprecated code grants nothing at runtime, but it is still a mutation and its rows stay.
--   * journal.post_prior_period requires journal.view: it is a break-glass modifier, but a write to
--     the journal. journal.create_manual, posting_rule.create and posting_rule.update require only
--     their own resource's view; naming gl_account.view too would be a UI convenience, not "seeing
--     one's write", and the audit of the seeded bundles shows nothing is broken either way.
--   * tenant.*, user.activate, user.suspend, user.deactivate: the view is checked in the same
--     organisation as the mutation, the PLATFORM organisation, so a custom platform checker role
--     must hold tenant.view or user.view there.
--
-- GRANT_SCOPE. TENANT means the code is evaluated in a tenant organisation. It may ALSO be
-- evaluated in the PLATFORM organisation (at least tenant.view, branch.view, user.view,
-- membership.view, branch_assignment.view, role.view, role_assignment.view, permission.view,
-- audit.view, business_date.view, branch.create, branch.approve, branch.activate and
-- user.approve are), so TENANT is "may be granted in a tenant", not "only there". The two values
-- CANNOT say which TENANT codes are also evaluated in PLATFORM, so a future non-super platform
-- administrator cannot be derived as "the PLATFORM codes" alone: it would hold the tenant.* and
-- user.* mutations without tenant.view and user.view, which ADR 0030 forbids. Deriving it needs
-- its own decision and probably its own metadata.
-- PLATFORM means the code is ONLY ever evaluated in the PLATFORM
-- organisation (requirePlatformPermission, a /api/v1/platform controller together with
-- getPlatformCaller, TenantSettingsService's platform-only key): granting one to a tenant role
-- does nothing. There are 14 of them: the ten tenant.* lifecycle codes other than tenant.view
-- (create, update_draft, submit_for_approval, approve, reject, activate, suspend, reactivate,
-- deprovision, bootstrap_retry), user.activate, user.suspend, user.deactivate and
-- tenant_setting.manage_platform. The other 67 are TENANT. The platform administrator holds every
-- code today. Deriving the seeded tenant administrator from this column ("an admin holds every
-- code of its scope") is intended but is a SEPARATE decision and change; no role is changed by
-- this file.
--
-- NO DATA BACKFILL OF ROLES. No role_permission or membership_permission row is read, written or
-- deleted here. Whether an existing custom role holds a mutation without its views is reported by
-- the role APIs and the catalogue test of a later change, and repaired by an owner, row by row
-- (ADR 0030, decision 3). Nothing about who can do what changes on the day this deploys.
--
-- IDENTIFIERS. No permission row is inserted, so no catalogue identifier is allocated. The new
-- table follows the repository's conventions: id UUID PRIMARY KEY DEFAULT uuidv7() and a unique
-- guid UUID NOT NULL DEFAULT uuidv7(); both ends of a pairing reference permission (id). It adds
-- no trigger (ADR 0024): that a pairing joins a MUTATION to a VIEW cannot be a CHECK across two
-- tables, so this file asserts it when it runs and PermissionCatalogueMetadataTests asserts it on
-- every build.
--
-- COST ACCEPTED (ADR 0030, decision 8). The table is runtime-editable by SQL, so a hotfix could
-- silently weaken the rule. It is reference data, changed only by a forward migration, and the
-- catalogue test asserts the migrated database.
--
-- STATUS. kind and grant_scope are written to every row whatever its status. The catalogue test
-- requires an ACTIVE mutation to require only ACTIVE views, because runtime honours only ACTIVE
-- codes and a deprecated view would make its mutations unusable; a DEPRECATED mutation such as
-- branch.activate may name an ACTIVE view.
--
-- IDEMPOTENT. Every step can be re-run, as the migration test does: the columns and the table are
-- created IF NOT EXISTS, a row is rewritten (and its row_version bumped) only when its
-- classification differs, a pairing is ON CONFLICT DO NOTHING, and SET NOT NULL is a no-op the
-- second time. A re-run restores a pairing removed out of band. It also raises, naming the codes,
-- when the catalogue holds a permission this file does not know (an out-of-band code): it cannot
-- classify it for the owner, and making the columns NOT NULL would fail anyway.
--
-- Preconditions assert only what this file depends on: every code it names exists. The
-- post-conditions check only what it is responsible for, so an out-of-band grant elsewhere never
-- fails the deploy. V1-V21 are frozen and untouched.

ALTER TABLE permission
    ADD COLUMN IF NOT EXISTS kind TEXT,
    ADD COLUMN IF NOT EXISTS grant_scope TEXT;

-- The CHECKs are separate, guarded statements, so adding the columns by hand beforehand cannot
-- make this file skip them.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'permission'::regclass AND conname = 'chk_permission_kind'
    ) THEN
        ALTER TABLE permission ADD CONSTRAINT chk_permission_kind
            CHECK (kind IN ('VIEW', 'MUTATION', 'CONTEXT'));
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'permission'::regclass AND conname = 'chk_permission_grant_scope'
    ) THEN
        ALTER TABLE permission ADD CONSTRAINT chk_permission_grant_scope
            CHECK (grant_scope IN ('TENANT', 'PLATFORM'));
    END IF;
END $$;

COMMENT ON COLUMN permission.kind IS
    'VIEW: reads a resource and requires nothing. MUTATION: changes state and requires the views '
    'listed in permission_view_requirement. CONTEXT: gates the caller''s own session or a '
    'platform-only setting, requires nothing and is not a view. NOT NULL, so a new permission '
    'migration cannot ship an unclassified code (ADR 0030).';
COMMENT ON COLUMN permission.grant_scope IS
    'TENANT: evaluated in a tenant organisation (it may also be evaluated in PLATFORM). PLATFORM: '
    'only ever evaluated in the PLATFORM organisation, so a tenant role holding it gains nothing. '
    'It cannot say which TENANT codes are also evaluated in PLATFORM. Deriving the seeded '
    'administrator bundles from it is a separate decision. NOT NULL, so a new permission '
    'migration must classify its codes.';

CREATE TABLE IF NOT EXISTS permission_view_requirement (
    id UUID PRIMARY KEY DEFAULT uuidv7(),
    guid UUID NOT NULL DEFAULT uuidv7(),
    permission_id UUID NOT NULL REFERENCES permission (id),
    required_view_permission_id UUID NOT NULL REFERENCES permission (id),
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_permission_view_requirement_guid UNIQUE (guid),
    CONSTRAINT uq_permission_view_requirement UNIQUE (permission_id, required_view_permission_id),
    CONSTRAINT chk_permission_view_requirement_distinct
        CHECK (permission_id <> required_view_permission_id),
    CONSTRAINT chk_permission_view_requirement_version CHECK (row_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_permission_view_requirement_view
    ON permission_view_requirement (required_view_permission_id);

COMMENT ON TABLE permission_view_requirement IS
    'The views a mutation permission implies (ADR 0030): a role or caller holding permission_id '
    'must also hold every required_view_permission_id. A join table, not a column, because '
    'user.invite needs two views. Reference data, changed only by a forward migration. That '
    'permission_id is a MUTATION and required_view_permission_id a VIEW cannot be a CHECK across '
    'tables and no trigger is allowed (ADR 0024): the migration and '
    'PermissionCatalogueMetadataTests assert it.';
COMMENT ON COLUMN permission_view_requirement.permission_id IS
    'The MUTATION permission that requires the view.';
COMMENT ON COLUMN permission_view_requirement.required_view_permission_id IS
    'The VIEW permission the mutation requires.';

-- Preconditions and the classification, in one block so both lists are read once. A code named in
-- either list must exist; a permission the lists do not name is refused before NOT NULL is set.
DO $$
DECLARE
    entry          RECORD;
    unclassified   TEXT;
BEGIN
    FOR entry IN
        SELECT * FROM (VALUES
            -- Tenant lifecycle: platform-operated (PLATFORM) except the view
            ('tenant.view', 'VIEW', 'TENANT'),
            ('tenant.create', 'MUTATION', 'PLATFORM'),
            ('tenant.update_draft', 'MUTATION', 'PLATFORM'),
            ('tenant.submit_for_approval', 'MUTATION', 'PLATFORM'),
            ('tenant.approve', 'MUTATION', 'PLATFORM'),
            ('tenant.reject', 'MUTATION', 'PLATFORM'),
            ('tenant.activate', 'MUTATION', 'PLATFORM'),
            ('tenant.suspend', 'MUTATION', 'PLATFORM'),
            ('tenant.reactivate', 'MUTATION', 'PLATFORM'),
            ('tenant.deprovision', 'MUTATION', 'PLATFORM'),
            ('tenant.bootstrap_retry', 'MUTATION', 'PLATFORM'),
            -- Branch
            ('branch.view', 'VIEW', 'TENANT'),
            ('branch.create', 'MUTATION', 'TENANT'),
            ('branch.update', 'MUTATION', 'TENANT'),
            ('branch.approve', 'MUTATION', 'TENANT'),
            ('branch.activate', 'MUTATION', 'TENANT'),
            ('branch.suspend', 'MUTATION', 'TENANT'),
            ('branch.reactivate', 'MUTATION', 'TENANT'),
            ('branch.close', 'MUTATION', 'TENANT'),
            -- Users, memberships and assignments
            ('user.view', 'VIEW', 'TENANT'),
            ('user.invite', 'MUTATION', 'TENANT'),
            ('user.approve', 'MUTATION', 'TENANT'),
            ('user.activate', 'MUTATION', 'PLATFORM'),
            ('user.suspend', 'MUTATION', 'PLATFORM'),
            ('user.deactivate', 'MUTATION', 'PLATFORM'),
            ('user.assign_branch', 'MUTATION', 'TENANT'),
            ('user.revoke_branch', 'MUTATION', 'TENANT'),
            ('user.assign_role', 'MUTATION', 'TENANT'),
            ('user.revoke_role', 'MUTATION', 'TENANT'),
            ('membership.view', 'VIEW', 'TENANT'),
            ('membership.suspend', 'MUTATION', 'TENANT'),
            ('membership.reactivate', 'MUTATION', 'TENANT'),
            ('membership.revoke', 'MUTATION', 'TENANT'),
            ('branch_assignment.view', 'VIEW', 'TENANT'),
            ('role_assignment.view', 'VIEW', 'TENANT'),
            -- Roles and the catalogue
            ('role.view', 'VIEW', 'TENANT'),
            ('role.create', 'MUTATION', 'TENANT'),
            ('role.update', 'MUTATION', 'TENANT'),
            ('role.activate', 'MUTATION', 'TENANT'),
            ('role.deactivate', 'MUTATION', 'TENANT'),
            ('role.assign_permission', 'MUTATION', 'TENANT'),
            ('role.remove_permission', 'MUTATION', 'TENANT'),
            ('permission.view', 'VIEW', 'TENANT'),
            -- Audit, settings, business date, session
            ('audit.view', 'VIEW', 'TENANT'),
            ('settings.view', 'VIEW', 'TENANT'),
            ('settings.update', 'MUTATION', 'TENANT'),
            ('tenant_setting.manage_platform', 'CONTEXT', 'PLATFORM'),
            ('business_date.view', 'VIEW', 'TENANT'),
            ('business_date.advance', 'MUTATION', 'TENANT'),
            ('business_date.reopen', 'MUTATION', 'TENANT'),
            ('cob.start', 'MUTATION', 'TENANT'),
            ('cob.complete', 'MUTATION', 'TENANT'),
            ('auth.select_organisation', 'CONTEXT', 'TENANT'),
            ('auth.select_branch', 'CONTEXT', 'TENANT'),
            ('iam.profile.read', 'VIEW', 'TENANT'),
            -- Accounting
            ('gl_account.view', 'VIEW', 'TENANT'),
            ('gl_account.create', 'MUTATION', 'TENANT'),
            ('gl_account.update', 'MUTATION', 'TENANT'),
            ('gl_account.submit', 'MUTATION', 'TENANT'),
            ('gl_account.approve', 'MUTATION', 'TENANT'),
            ('gl_account.deactivate', 'MUTATION', 'TENANT'),
            ('fiscal_period.view', 'VIEW', 'TENANT'),
            ('fiscal_period.open', 'MUTATION', 'TENANT'),
            ('fiscal_period.close', 'MUTATION', 'TENANT'),
            ('fiscal_period.reopen', 'MUTATION', 'TENANT'),
            ('journal.view', 'VIEW', 'TENANT'),
            ('journal.create_manual', 'MUTATION', 'TENANT'),
            ('journal.submit', 'MUTATION', 'TENANT'),
            ('journal.approve', 'MUTATION', 'TENANT'),
            ('journal.reverse', 'MUTATION', 'TENANT'),
            ('journal.post_prior_period', 'MUTATION', 'TENANT'),
            ('posting_rule.view', 'VIEW', 'TENANT'),
            ('posting_rule.create', 'MUTATION', 'TENANT'),
            ('posting_rule.update', 'MUTATION', 'TENANT'),
            ('posting_rule.submit', 'MUTATION', 'TENANT'),
            ('posting_rule.approve', 'MUTATION', 'TENANT'),
            ('reconciliation.view', 'VIEW', 'TENANT'),
            ('reconciliation.run', 'MUTATION', 'TENANT'),
            ('reconciliation.resolve', 'MUTATION', 'TENANT'),
            ('accounting_report.view', 'VIEW', 'TENANT'),
            ('accounting_report.export', 'MUTATION', 'TENANT')
        ) AS classification (permission_code, kind, grant_scope)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM permission WHERE permission_code = entry.permission_code
        ) THEN
            RAISE EXCEPTION
                'Expected the catalogue permission % to exist, but it does not',
                entry.permission_code;
        END IF;

        UPDATE permission
        SET kind = entry.kind,
            grant_scope = entry.grant_scope,
            updated_at = NOW(),
            row_version = row_version + 1
        WHERE permission_code = entry.permission_code
          AND (kind IS DISTINCT FROM entry.kind OR grant_scope IS DISTINCT FROM entry.grant_scope);
    END LOOP;

    SELECT string_agg(permission_code, ', ' ORDER BY permission_code) INTO unclassified
    FROM permission
    WHERE kind IS NULL OR grant_scope IS NULL;
    IF unclassified IS NOT NULL THEN
        RAISE EXCEPTION
            'V22 cannot classify the permission(s) %. Remove the stray row(s), or add the kind '
            'and grant_scope columns by hand, classify them (kind, grant_scope and, for a '
            'MUTATION, its permission_view_requirement rows) and redeploy',
            unclassified;
    END IF;
END $$;

ALTER TABLE permission
    ALTER COLUMN kind SET NOT NULL,
    ALTER COLUMN grant_scope SET NOT NULL;

-- The pairings: every MUTATION with the VIEW codes it requires (61 rows for 60 mutations, because
-- user.invite needs two). A pairing is written once and never rewritten.
DO $$
DECLARE
    entry RECORD;
BEGIN
    FOR entry IN
        SELECT * FROM (VALUES
            ('tenant.create', 'tenant.view'),
            ('tenant.update_draft', 'tenant.view'),
            ('tenant.submit_for_approval', 'tenant.view'),
            ('tenant.approve', 'tenant.view'),
            ('tenant.reject', 'tenant.view'),
            ('tenant.activate', 'tenant.view'),
            ('tenant.suspend', 'tenant.view'),
            ('tenant.reactivate', 'tenant.view'),
            ('tenant.deprovision', 'tenant.view'),
            ('tenant.bootstrap_retry', 'tenant.view'),
            ('branch.create', 'branch.view'),
            ('branch.update', 'branch.view'),
            ('branch.approve', 'branch.view'),
            ('branch.activate', 'branch.view'),
            ('branch.suspend', 'branch.view'),
            ('branch.reactivate', 'branch.view'),
            ('branch.close', 'branch.view'),
            ('user.invite', 'membership.view'),
            ('user.invite', 'user.view'),
            ('user.approve', 'membership.view'),
            ('user.activate', 'user.view'),
            ('user.suspend', 'user.view'),
            ('user.deactivate', 'user.view'),
            ('user.assign_branch', 'branch_assignment.view'),
            ('user.revoke_branch', 'branch_assignment.view'),
            ('user.assign_role', 'role_assignment.view'),
            ('user.revoke_role', 'role_assignment.view'),
            ('membership.suspend', 'membership.view'),
            ('membership.reactivate', 'membership.view'),
            ('membership.revoke', 'membership.view'),
            ('role.create', 'role.view'),
            ('role.update', 'role.view'),
            ('role.activate', 'role.view'),
            ('role.deactivate', 'role.view'),
            ('role.assign_permission', 'role.view'),
            ('role.remove_permission', 'role.view'),
            ('settings.update', 'settings.view'),
            ('business_date.advance', 'business_date.view'),
            ('business_date.reopen', 'business_date.view'),
            ('cob.start', 'business_date.view'),
            ('cob.complete', 'business_date.view'),
            ('gl_account.create', 'gl_account.view'),
            ('gl_account.update', 'gl_account.view'),
            ('gl_account.submit', 'gl_account.view'),
            ('gl_account.approve', 'gl_account.view'),
            ('gl_account.deactivate', 'gl_account.view'),
            ('fiscal_period.open', 'fiscal_period.view'),
            ('fiscal_period.close', 'fiscal_period.view'),
            ('fiscal_period.reopen', 'fiscal_period.view'),
            ('journal.create_manual', 'journal.view'),
            ('journal.submit', 'journal.view'),
            ('journal.approve', 'journal.view'),
            ('journal.reverse', 'journal.view'),
            ('journal.post_prior_period', 'journal.view'),
            ('posting_rule.create', 'posting_rule.view'),
            ('posting_rule.update', 'posting_rule.view'),
            ('posting_rule.submit', 'posting_rule.view'),
            ('posting_rule.approve', 'posting_rule.view'),
            ('reconciliation.run', 'reconciliation.view'),
            ('reconciliation.resolve', 'reconciliation.view'),
            ('accounting_report.export', 'accounting_report.view')
        ) AS requirement (permission_code, required_view_code)
    LOOP
        INSERT INTO permission_view_requirement (
            permission_id, required_view_permission_id, created_at, updated_at
        )
        SELECT mutation.id, required_view.id, NOW(), NOW()
        FROM permission mutation
        JOIN permission required_view ON required_view.permission_code = entry.required_view_code
        WHERE mutation.permission_code = entry.permission_code
        ON CONFLICT ON CONSTRAINT uq_permission_view_requirement DO NOTHING;

        IF NOT FOUND AND NOT EXISTS (
            SELECT 1
            FROM permission_view_requirement r
            JOIN permission m ON m.id = r.permission_id
            JOIN permission v ON v.id = r.required_view_permission_id
            WHERE m.permission_code = entry.permission_code
              AND v.permission_code = entry.required_view_code
        ) THEN
            RAISE EXCEPTION 'Could not pair % with the view %',
                entry.permission_code, entry.required_view_code;
        END IF;
    END LOOP;
END $$;

-- Post-conditions: what this file is responsible for. The kinds are enforced here because a
-- CHECK cannot reach across tables and no trigger is allowed (ADR 0024).
DO $$
DECLARE
    offenders TEXT;
BEGIN
    SELECT string_agg(DISTINCT m.permission_code, ', ' ORDER BY m.permission_code) INTO offenders
    FROM permission m
    WHERE m.kind = 'MUTATION'
      AND NOT EXISTS (
          SELECT 1 FROM permission_view_requirement r WHERE r.permission_id = m.id
      );
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'MUTATION permission(s) without a required view: %', offenders;
    END IF;

    SELECT string_agg(DISTINCT m.permission_code, ', ' ORDER BY m.permission_code) INTO offenders
    FROM permission m
    JOIN permission_view_requirement r ON r.permission_id = m.id
    WHERE m.kind <> 'MUTATION';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'VIEW or CONTEXT permission(s) that require something: %', offenders;
    END IF;

    SELECT string_agg(
               m.permission_code || ' -> ' || v.permission_code,
               ', ' ORDER BY m.permission_code, v.permission_code)
    INTO offenders
    FROM permission_view_requirement r
    JOIN permission m ON m.id = r.permission_id
    JOIN permission v ON v.id = r.required_view_permission_id
    WHERE v.kind <> 'VIEW';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION 'A required permission is not a VIEW: %', offenders;
    END IF;
END $$;
