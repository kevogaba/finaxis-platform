-- Issue #203: a dedicated branch.update permission for PATCH /api/v1/branches/{id}.
--
-- WHAT. Seeds one permission, branch.update, in the existing `branch` module, and grants it.
--
-- WHY. The #165 change that added PATCH /api/v1/branches/{id} (rename, re-parent, timezone,
-- address; DRAFT and ACTIVE branches only) authorised it with branch.create, on the argument that
-- editing a branch is something a branch's maker does (ADR 0029). That is too coarse in one place:
-- a custom maker-only role holding branch.create could edit a LIVE, already-approved branch with
-- no checker anywhere in the path. Editing a live branch is a higher-trust act than drafting one,
-- so it gets its own code, which an owner can withhold from makers and grant on purpose. From
-- this release the PATCH route and BranchProvisioningService.update require branch.update and no
-- longer accept branch.create; every other branch action keeps its existing permission.
--
-- THE GRANT-COPY RULE (the part to read before operating this). Nobody who can PATCH today may
-- silently lose the ability merely because the code changed under them, so this migration gives
-- branch.update to everyone who currently holds branch.create:
--
--   * PLATFORM_SUPER_ADMIN is granted it explicitly, as it holds branch.create.
--   * Every role_permission row for branch.create, in every organisation, is copied to a
--     role_permission row for branch.update on the same (organisation_id, role_id). role_permission
--     has no scope column - a role's TENANT or BRANCH scope lives on user_role_assignment, which is
--     untouched - so a copy keeps exactly the audience the original had.
--   * Every membership_permission row for branch.create is copied to branch.update with the SAME
--     effect, ALLOW or DENY. A direct ALLOW keeps its holder able to PATCH; a direct DENY that
--     used to withhold PATCH keeps withholding it, instead of being undone by a role grant.
--   * A role (or membership) WITHOUT branch.create receives nothing. In particular a checker-only
--     role holding branch.activate gains no new power.
--
-- The consequence, stated plainly: after this migration every existing branch.create holder
-- holds branch.update too, so behaviour is unchanged on the day it deploys. The separation takes
-- effect from then on. To take PATCH away from a role, revoke branch.update from it (the
-- role.remove_permission route); revoking branch.create no longer removes it. Roles created for
-- a new organisation afterwards get branch.update only where OrganisationBootstrapDefaults lists
-- it (TENANT_ADMIN and BRANCH_MANAGER, the two that already carry branch.create); an owner who
-- composes a new role chooses it independently of branch.create.
--
-- WHAT IT DOES NOT TOUCH. The bootstrap local-admin role does not hold branch.create (V3 never
-- granted it), so it receives nothing here; nor does PLATFORM_SUPPORT. The platform-operator
-- routes (/api/v1/platform/...) never offered PATCH and are unchanged.
--
-- IDENTIFIER. 40000000-0000-0000-0000-000000000064 is the next free id after the highest the V2
-- foundation block uses (...63). The gaps V2 left (...26-29, ...34-39) are deliberately not
-- filled, for the reason V5 gives: interleaving later codes into old numbering makes ORDER BY id
-- unreadable.
-- branch.update belongs to the branch module V2 already owns, so it continues that block rather
-- than opening a 4X000000 block of its own; a new domain still takes its own block.
--
-- IDEMPOTENT. The permission insert is ON CONFLICT (permission_code) DO NOTHING and every grant
-- is ON CONFLICT ON CONSTRAINT ... DO NOTHING, so re-running the file (as its migration
-- test does) adds nothing, and a deployment that already carries the code
-- from an out-of-band hotfix keeps its own row.
--
-- STATUS. branch.update is inserted with the status branch.create has at migration time, not a
-- literal ACTIVE. Runtime resolution only honours ACTIVE permissions, so if branch.create had been
-- DEPRECATED or DISABLED, an ACTIVE branch.update copied to its holders would re-open PATCH for
-- roles that cannot use branch.create today. Mirroring the status keeps the grant copy
-- access-neutral; an owner who wants it live changes that one row deliberately.
--
-- CACHE. This file writes the permission tables directly, so nothing evicts the Redis cache
-- `iam.effective-permissions`, which has no time-to-live and outlives a deployment: a set cached
-- before this migration would lack branch.update and 403 every holder on the new gate. The
-- application therefore (1) namespaces that cache's keys by the highest applied Flyway version
-- (`iam.effective-permissions:v<N>::...`, N read after migrations ran), so an instance started on
-- this schema never reads an entry written under an older one, including one a still-running
-- instance of the previous release resolves before this commits and writes after the new
-- instance started, and (2) clears the cache once at start, after Flyway and before traffic. The
-- new permission is effective on the first request of a new instance with no operator action.
-- Previous-release instances keep serving their own namespace until replaced. It does NOT cover a
-- grant changed by SQL outside Flyway while instances stay up: that needs the cache flushed
-- (restart, or delete the `iam.effective-permissions:*` keys in Redis).
--
-- Preconditions assert only what this file depends on; the post-conditions check only what it is
-- responsible for, so an out-of-band permission or grant elsewhere never fails the deploy.
--
-- DATA ONLY. Reference data and grants: no table, column, constraint or index changes. It
-- supersedes the #165 documentation that said PATCH "requires branch.create; there is no
-- branch.update code" and that a new code "would need a V18 grant migration"
-- (docs/security/authorization-model.md), and ADR 0029's remarks that a new code "would need a
-- forward-only V18+ migration" and that PATCH reuses branch.create. V1-V18 are frozen and
-- untouched.

-- Preconditions: the code being mirrored (whose status the new code copies) and the role being
-- granted must exist.
DO $$
DECLARE
    branch_create_codes INT;
    super_admin_roles   INT;
BEGIN
    SELECT COUNT(*) INTO branch_create_codes
    FROM permission
    WHERE permission_code = 'branch.create';
    IF branch_create_codes <> 1 THEN
        RAISE EXCEPTION
            'Expected the V2 branch.create permission to exist once but found %',
            branch_create_codes;
    END IF;

    -- Existence only: fk_role_permission_role is status-independent, and asserting more would
    -- fail the deploy over a fact this file does not depend on.
    SELECT COUNT(*) INTO super_admin_roles
    FROM role
    WHERE id = '50000000-0000-0000-0000-000000000001'
      AND organisation_id = '00000000-0000-0000-0000-000000000000';
    IF super_admin_roles <> 1 THEN
        RAISE EXCEPTION
            'Expected the V2 PLATFORM_SUPER_ADMIN role to exist but found %', super_admin_roles;
    END IF;
END $$;

-- The permission. HIGH, like branch.create: it changes a live branch's identity and hierarchy.
-- Its status is branch.create's (see STATUS above); the precondition guarantees that row exists.
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
)
SELECT
    '40000000-0000-0000-0000-000000000064', 'branch.update', 'Update branch', 'branch',
    'Amend the name, parent, timezone or address of a draft or active branch.', 'HIGH',
    creator.status, NOW(), NOW()
FROM permission creator
WHERE creator.permission_code = 'branch.create'
ON CONFLICT (permission_code) DO NOTHING;

-- PLATFORM_SUPER_ADMIN keeps the whole catalogue. V2's set-based grant ran once, at V2, so it
-- cannot pick this code up on an existing installation.
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
WHERE p.permission_code = 'branch.update'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- The grant-copy rule: every role that holds branch.create at migration time also holds
-- branch.update. granted_by and created_by stay NULL: this is the migration, not a person.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    rp.organisation_id,
    rp.role_id,
    updater.id,
    NOW(),
    NOW(),
    NOW()
FROM role_permission rp
JOIN permission creator ON creator.id = rp.permission_id
                       AND creator.permission_code = 'branch.create'
CROSS JOIN permission updater
WHERE updater.permission_code = 'branch.update'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- The same rule for direct membership overrides, carrying the effect across so a DENY on
-- branch.create keeps denying PATCH.
INSERT INTO membership_permission (
    organisation_id, membership_id, permission_id, effect, granted_at, created_at, updated_at
)
SELECT
    mp.organisation_id,
    mp.membership_id,
    updater.id,
    mp.effect,
    NOW(),
    NOW(),
    NOW()
FROM membership_permission mp
JOIN permission creator ON creator.id = mp.permission_id
                       AND creator.permission_code = 'branch.create'
CROSS JOIN permission updater
WHERE updater.permission_code = 'branch.update'
ON CONFLICT ON CONSTRAINT uq_membership_permission DO NOTHING;

-- Post-conditions: the migration enforces what the tests assert, so a mistake surfaces in every
-- environment on deployment rather than only in CI.
DO $$
DECLARE
    seeded        INT;
    super_admin   INT;
    uncopied_role INT;
    uncopied_over INT;
BEGIN
    SELECT COUNT(*) INTO seeded FROM permission WHERE permission_code = 'branch.update';
    IF seeded <> 1 THEN
        RAISE EXCEPTION 'Expected branch.update to exist once after seeding but found %', seeded;
    END IF;

    SELECT COUNT(*) INTO super_admin
    FROM role_permission rp
    JOIN permission p ON p.id = rp.permission_id
    WHERE rp.role_id = '50000000-0000-0000-0000-000000000001'
      AND p.permission_code = 'branch.update';
    IF super_admin <> 1 THEN
        RAISE EXCEPTION 'PLATFORM_SUPER_ADMIN does not hold branch.update after this migration';
    END IF;

    SELECT COUNT(*) INTO uncopied_role
    FROM role_permission rp
    JOIN permission creator ON creator.id = rp.permission_id
                           AND creator.permission_code = 'branch.create'
    WHERE NOT EXISTS (
        SELECT 1
        FROM role_permission copy
        JOIN permission updater ON updater.id = copy.permission_id
                               AND updater.permission_code = 'branch.update'
        WHERE copy.organisation_id = rp.organisation_id
          AND copy.role_id = rp.role_id
    );
    IF uncopied_role <> 0 THEN
        RAISE EXCEPTION
            '% role(s) hold branch.create but not branch.update after this migration',
            uncopied_role;
    END IF;

    SELECT COUNT(*) INTO uncopied_over
    FROM membership_permission mp
    JOIN permission creator ON creator.id = mp.permission_id
                           AND creator.permission_code = 'branch.create'
    WHERE NOT EXISTS (
        SELECT 1
        FROM membership_permission copy
        JOIN permission updater ON updater.id = copy.permission_id
                               AND updater.permission_code = 'branch.update'
        WHERE copy.organisation_id = mp.organisation_id
          AND copy.membership_id = mp.membership_id
    );
    IF uncopied_over <> 0 THEN
        RAISE EXCEPTION
            '% membership override(s) on branch.create were not copied to branch.update',
            uncopied_over;
    END IF;
END $$;
