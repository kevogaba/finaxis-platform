-- Seeded administrator roles hold EVERY permission of their scope, and the other seeded roles are
-- repaired against their purpose.
--
-- WHAT. One forward-only DATA migration: no table, column, constraint or index changes and no new
-- permission row, so no identifier is allocated. Four steps, each idempotent:
--
--   1. TENANT ADMINISTRATORS. Every existing tenant's seeded TENANT_ADMIN role, and the bootstrap
--      tenant's local-admin role (V3; the role local.admin and local.checker log in with), is
--      granted every ACTIVE permission whose grant_scope is TENANT and loses every permission whose
--      grant_scope is PLATFORM.
--   2. PLATFORM ADMINISTRATOR. PLATFORM_SUPER_ADMIN is granted every ACTIVE permission it lacks.
--   3. PLATFORM_SUPPORT is granted what it needs to work at all and the platform reads it was
--      meant for (below).
--   4. THE OTHER SEEDED TENANT ROLES get the additions below.
--
-- WHY. The owner's rule: an administrator role has access to EVERYTHING in its scope. Before this
-- file TENANT_ADMIN held 70 codes and lacked seven tenant-scope accounting ones (the manual
-- journal maker and checker codes, journal.reverse, reconciliation.resolve and the two break-glass
-- codes fiscal_period.reopen and journal.post_prior_period) while holding ten tenant.* lifecycle
-- codes that are only ever evaluated in the PLATFORM organisation; local-admin held 33 of the 67
-- tenant-scope codes and was missing even audit.view, so GET /api/v1/audit-events refused the
-- bootstrap administrator; and PLATFORM_SUPPORT held two reads it could never exercise, because it
-- could not select the PLATFORM organisation. From this release the application derives the
-- TENANT_ADMIN bundle of every newly approved tenant from permission.grant_scope instead of a list
-- (OrganisationBootstrapDefaults), and this file brings the rows already written to the same
-- state. THE TWO MUST AGREE: tenant reactivation requires the readiness check
-- (JooqOrganisationAccessStore.hasDefaultRolesAndPermissions) to find every code of the current
-- bundle on the tenant's TENANT_ADMIN role, so a tenant that has not been backfilled could not be
-- reactivated after a suspension (409). This file is that backfill.
--
-- WHICH ROLES ARE TOUCHED (and which are not). A seeded role is a role with system_role = TRUE and
-- one of the seeded role codes in an organisation other than PLATFORM, or one of the two platform
-- roles by id. The application writes system_role = TRUE only when it seeds a default role
-- (JooqOrganisationBranchProvisioningStore.createDefaultRoles), and a role created through the API
-- is always system_role = FALSE (JooqIamAdministrationPersistence), so a tenant cannot create a
-- role this file mistakes for a seeded one, and (uq_role_organisation_code) cannot create a second
-- role with a seeded code beside the seeded one. Seeded roles are immutable through the API:
-- RoleManagementService.requireMutable refuses update, activate, deactivate, assign-permission and
-- remove-permission on a system role, so there is no tenant edit of a seeded role to preserve. A
-- tenant-CUSTOMISED role is therefore never touched: a custom role (system_role = FALSE, whatever
-- its code or contents) is left exactly as it is, so a tenant that composed its own administrator
-- role keeps it, and an administrator who needed less than everything keeps using that role.
-- Tenants narrow access with direct membership_permission DENY overrides or by revoking an
-- assignment; both survive a widening grant and are not read or written here.
--
--   * The bootstrap role is pinned by organisation, code and flag (FINAXIS-LOCAL, local-admin,
--     system_role), not by tenant-wide code, so no other tenant's role can match it.
--   * Grants are made to a seeded role whatever its status: a DISABLED seeded role grants nothing
--     at runtime, and an owner who re-enables it should find it complete.
--
-- STEP 1 AND 2 IN DETAIL. "Every ACTIVE permission" means permission.status = 'ACTIVE': runtime
-- resolution honours only ACTIVE codes, so a DEPRECATED (branch.activate, since V21) or DISABLED
-- code is not granted. A DEPRECATED row a role already holds (V21 leaves them) is neither removed
-- nor needed. grant_scope TENANT means "may be granted in a tenant" (V22), so the tenant
-- administrator holds the 66 ACTIVE TENANT codes, break-glass codes included. The ten inert
-- platform-only tenant.* codes are DELETED from TENANT_ADMIN (and from local-admin, which never
-- held them): they are evaluated only in the PLATFORM organisation, so removing them is
-- access-neutral and makes "an administrator holds exactly its scope" true of real data. This is
-- the only deletion in the file. PLATFORM_SUPER_ADMIN keeps holding every row and so every
-- PLATFORM code.
--
-- STEP 3. PLATFORM_SUPPORT gains auth.select_organisation and iam.profile.read (without which it
-- can never enter the PLATFORM organisation) and the platform reads tenant.view, branch.view,
-- user.view, membership.view, branch_assignment.view, role.view, role_assignment.view and
-- permission.view. It still holds NO accounting code.
--
-- STEP 4. In every tenant: IAM_ADMIN gains branch.view (it assigns users to branches and could not
-- list them); BRANCH_MANAGER gains user.revoke_branch and user.view (it could assign a user to a
-- branch and neither undo it nor list users); BRANCH_OPERATOR gains branch.view. TENANT_AUDITOR,
-- ACCOUNTING_OPERATOR and ACCOUNTING_APPROVER are unchanged: the auditor deliberately still lacks
-- accounting_report.export, which no route checks yet. Every addition keeps the ADR 0030 rule that
-- a role holding a mutation holds its views.
--
-- INTENDED AUTHORITY INCREASE. Every existing tenant administrator gains the manual-journal maker
-- and checker codes, journal.reverse, reconciliation.resolve, fiscal_period.reopen and
-- journal.post_prior_period. The owner decided this. It rewrites the documented invariant "no
-- default bundle holds a break-glass code" to "no NON-ADMIN default bundle holds one" (ADR 0021,
-- docs/security/accounting-authorization.md). Use of the break-glass codes is still audited and
-- lock-checked where it is enforced, and every per-resource actor guard (no self-approval of a
-- journal, a GL account, a posting rule, a user or a branch) still applies to an administrator.
-- THE BOOTSTRAP TENANT becomes a full administrator twice over: local.admin and local.checker both
-- hold local-admin and gain every tenant-scope code, including CRITICAL ones such as
-- role.assign_permission and business_date.reopen. V3's header already says to rotate these demo
-- identities before any real exposure; this widening raises the cost of not doing so, so a
-- production deployment must rotate or deactivate them (docs/security/production-hardening.md).
--
-- CACHE. Effective permissions are cached (iam.effective-permissions) and this file writes the
-- tables directly, bypassing the invalidation the application performs on role changes. The
-- application clears that cache on EVERY start, after Flyway has run and before the web server
-- accepts traffic (EffectivePermissionCacheStartupClearer, V21), so the new grants take effect on
-- the first request of a new instance. During a rolling deploy an old instance may serve a cached
-- pre-deploy set (narrower, so it fails safe) until it is replaced.
--
-- GOING FORWARD. A migration that adds an ACTIVE permission must still grant it to the
-- administrators it belongs to, because the application seeds only NEW tenants. The re-runnable
-- statements in steps 1 and 2 are written without any code list for that reason, and
-- SeededRolesDriftTests fails the build when a catalogue code is missing from an administrator
-- role (it checks the migrated database, where local-admin is the V3-era role).
--
-- IDEMPOTENT. Every insert is ON CONFLICT DO NOTHING on uq_role_permission and the one delete
-- matches nothing the second time, so a re-run writes no row, as the migration test proves.
-- granted_by and created_by stay NULL: this is a migration, not a person (as V19 and V21).
--
-- Preconditions assert only what this file depends on. The post-conditions check only what it is
-- responsible for, so an out-of-band grant elsewhere never fails the deploy. V1-V22 are frozen.

-- Preconditions: V22's classification and every code and role this file names.
DO $$
DECLARE
    entry RECORD;
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM information_schema.columns
        WHERE table_schema = 'public' AND table_name = 'permission'
          AND column_name = 'grant_scope' AND is_nullable = 'NO'
    ) THEN
        RAISE EXCEPTION 'V23 needs the NOT NULL permission.grant_scope column that V22 adds';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM role
        WHERE id = '50000000-0000-0000-0000-000000000001'
          AND organisation_id = '00000000-0000-0000-0000-000000000000' AND system_role
    ) THEN
        RAISE EXCEPTION 'Expected the V2 PLATFORM_SUPER_ADMIN system role to exist';
    END IF;

    IF NOT EXISTS (
        SELECT 1 FROM role
        WHERE id = '50000000-0000-0000-0000-000000000002'
          AND organisation_id = '00000000-0000-0000-0000-000000000000' AND system_role
    ) THEN
        RAISE EXCEPTION 'Expected the V2 PLATFORM_SUPPORT system role to exist';
    END IF;

    FOR entry IN
        SELECT * FROM (VALUES
            ('auth.select_organisation'), ('iam.profile.read'), ('tenant.view'), ('branch.view'),
            ('user.view'), ('membership.view'), ('branch_assignment.view'), ('role.view'),
            ('role_assignment.view'), ('permission.view'), ('user.revoke_branch')
        ) AS named (permission_code)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM permission
            WHERE permission_code = entry.permission_code AND status = 'ACTIVE'
        ) THEN
            RAISE EXCEPTION 'Expected the catalogue permission % to exist and be ACTIVE',
                entry.permission_code;
        END IF;
    END LOOP;
END $$;

-- Step 1a: every seeded tenant administrator holds every ACTIVE tenant-scope permission. The
-- selector is the one in the header: system_role plus the seeded code, outside PLATFORM, with the
-- bootstrap role pinned to its own organisation.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT r.organisation_id, r.id, p.id, NOW(), NOW(), NOW()
FROM role r
CROSS JOIN permission p
WHERE r.system_role
  AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
  AND (
      r.role_code = 'TENANT_ADMIN'
      OR (r.role_code = 'local-admin'
          AND r.organisation_id = '22222222-2222-2222-2222-222222222222')
  )
  AND p.status = 'ACTIVE'
  AND p.grant_scope = 'TENANT'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- Step 1b: and none of the platform-only codes, which a tenant evaluates nowhere.
DELETE FROM role_permission rp
USING role r, permission p
WHERE r.id = rp.role_id
  AND p.id = rp.permission_id
  AND r.system_role
  AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
  AND (
      r.role_code = 'TENANT_ADMIN'
      OR (r.role_code = 'local-admin'
          AND r.organisation_id = '22222222-2222-2222-2222-222222222222')
  )
  AND p.grant_scope = 'PLATFORM';

-- Step 2: the platform administrator holds every ACTIVE permission.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT '00000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000001', p.id,
       NOW(), NOW(), NOW()
FROM permission p
WHERE p.status = 'ACTIVE'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- Steps 3 and 4: the hand lists of the non-administrator roles. The loop asserts each addition as
-- it makes it, so the list is read once and the post-condition cannot drift from it.
DO $$
DECLARE
    entry   RECORD;
    missing BIGINT;
BEGIN
    -- Step 4: seeded tenant roles, in every tenant.
    FOR entry IN
        SELECT * FROM (VALUES
            ('IAM_ADMIN', 'branch.view'),
            ('BRANCH_MANAGER', 'user.revoke_branch'),
            ('BRANCH_MANAGER', 'user.view'),
            ('BRANCH_OPERATOR', 'branch.view')
        ) AS addition (role_code, permission_code)
    LOOP
        INSERT INTO role_permission (
            organisation_id, role_id, permission_id, granted_at, created_at, updated_at
        )
        SELECT r.organisation_id, r.id, p.id, NOW(), NOW(), NOW()
        FROM role r
        JOIN permission p ON p.permission_code = entry.permission_code AND p.status = 'ACTIVE'
        WHERE r.system_role
          AND r.role_code = entry.role_code
          AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
        ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

        SELECT COUNT(*) INTO missing
        FROM role r
        WHERE r.system_role
          AND r.role_code = entry.role_code
          AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
          AND NOT EXISTS (
              SELECT 1 FROM role_permission rp
              JOIN permission p ON p.id = rp.permission_id
              WHERE rp.role_id = r.id AND p.permission_code = entry.permission_code
          );
        IF missing > 0 THEN
            RAISE EXCEPTION '% seeded % role(s) still lack % after this migration',
                missing, entry.role_code, entry.permission_code;
        END IF;
    END LOOP;

    -- Step 3: PLATFORM_SUPPORT.
    FOR entry IN
        SELECT * FROM (VALUES
            ('auth.select_organisation'), ('iam.profile.read'), ('tenant.view'), ('branch.view'),
            ('user.view'), ('membership.view'), ('branch_assignment.view'), ('role.view'),
            ('role_assignment.view'), ('permission.view')
        ) AS addition (permission_code)
    LOOP
        INSERT INTO role_permission (
            organisation_id, role_id, permission_id, granted_at, created_at, updated_at
        )
        SELECT '00000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000002',
               p.id, NOW(), NOW(), NOW()
        FROM permission p
        WHERE p.permission_code = entry.permission_code AND p.status = 'ACTIVE'
        ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

        IF NOT EXISTS (
            SELECT 1 FROM role_permission rp
            JOIN permission p ON p.id = rp.permission_id
            WHERE rp.role_id = '50000000-0000-0000-0000-000000000002'
              AND p.permission_code = entry.permission_code
        ) THEN
            RAISE EXCEPTION 'PLATFORM_SUPPORT still lacks % after this migration',
                entry.permission_code;
        END IF;
    END LOOP;
END $$;

-- Post-conditions: what this file is responsible for. Each names what it found, so a failing
-- deploy says which role and which codes.
DO $$
DECLARE
    offenders TEXT;
BEGIN
    SELECT string_agg(DISTINCT r.role_code || ' of ' || r.organisation_id || ' lacks ' ||
                      p.permission_code, '; ') INTO offenders
    FROM role r
    CROSS JOIN permission p
    WHERE r.system_role
      AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
      AND (
          r.role_code = 'TENANT_ADMIN'
          OR (r.role_code = 'local-admin'
              AND r.organisation_id = '22222222-2222-2222-2222-222222222222')
      )
      AND p.status = 'ACTIVE'
      AND p.grant_scope = 'TENANT'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp
          WHERE rp.role_id = r.id AND rp.permission_id = p.id
      );
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'a seeded tenant administrator does not hold every ACTIVE tenant-scope permission: %',
            offenders;
    END IF;

    SELECT string_agg(DISTINCT r.role_code || ' of ' || r.organisation_id || ' holds ' ||
                      p.permission_code, '; ') INTO offenders
    FROM role_permission rp
    JOIN role r ON r.id = rp.role_id
    JOIN permission p ON p.id = rp.permission_id
    WHERE r.system_role
      AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
      AND (
          r.role_code = 'TENANT_ADMIN'
          OR (r.role_code = 'local-admin'
              AND r.organisation_id = '22222222-2222-2222-2222-222222222222')
      )
      AND p.grant_scope = 'PLATFORM';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'a seeded tenant administrator still holds an inert platform-only permission: %',
            offenders;
    END IF;

    SELECT string_agg(p.permission_code, ', ' ORDER BY p.permission_code) INTO offenders
    FROM permission p
    WHERE p.status = 'ACTIVE'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp
          WHERE rp.permission_id = p.id
            AND rp.role_id = '50000000-0000-0000-0000-000000000001'
      );
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'PLATFORM_SUPER_ADMIN does not hold every ACTIVE permission: %', offenders;
    END IF;
END $$;
