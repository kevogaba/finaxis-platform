-- Issue #342: the permission that lets the seeded tenant administrator assign a role or a branch
-- to themselves (ADR 0044).
--
-- WHAT. One permission code, reference data only, no schema:
--   user.assign_self   MUTATION, TENANT scope, HIGH. POST /api/v1/tenant/role-assignments and
--                      POST /api/v1/tenant/branch-assignments require it, tenant-wide, when the
--                      user being assigned is the caller, and POST
--                      /api/v1/tenant/roles/{role_id}/permissions and .../activate require it
--                      when the caller holds the role. Requires user.view. It changes nothing on
--                      its own: it lifts those refusals for the one role the owner ruled may add
--                      to its own access, the tenant's own super user.
--
-- IDENTIFIER. 40000000-...-000000000069 is the next free id after V34's ...68 in the foundation
-- block.
--
-- GRANTS. PLATFORM_SUPER_ADMIN holds every ACTIVE code, so it gets it. user.assign_self is a
-- TENANT-scope code, so every seeded tenant administrator (TENANT_ADMIN of every tenant, and the
-- bootstrap local-admin) gets it, as V23 requires of any later permission migration. Nobody else
-- is granted it: IAM_ADMIN and BRANCH_MANAGER keep user.assign_role and user.assign_branch for
-- other members only. No other seeded bundle, no PLATFORM_SUPPORT and no direct membership
-- override gets it, roles a tenant composed itself (system_role = FALSE) are never touched, and the
-- API refuses to grant it to any role (RoleManagementService.assignPermissionToRole, 422).
--
-- CATALOGUE METADATA. Both columns of V22 are NOT NULL, so kind and grant_scope are written with
-- the row, and the view requirement with them (ADR 0030).
--
-- Idempotent (ON CONFLICT DO NOTHING), with its preconditions and post-conditions asserted below.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM permission
        WHERE permission_code = 'user.view' AND status = 'ACTIVE' AND kind = 'VIEW'
    ) THEN
        RAISE EXCEPTION 'Expected the ACTIVE VIEW permission user.view to exist';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM role
        WHERE id = '50000000-0000-0000-0000-000000000001'
          AND organisation_id = '00000000-0000-0000-0000-000000000000' AND system_role
    ) THEN
        RAISE EXCEPTION 'Expected the V2 PLATFORM_SUPER_ADMIN system role to exist';
    END IF;
END $$;

INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status, kind,
    grant_scope, created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000069', 'user.assign_self', 'Assign access to oneself',
     'iam',
     'Be the subject of one''s own role or branch assignment: held only by the seeded '
     || 'administrator roles, never grantable through the API.',
     'HIGH', 'ACTIVE', 'MUTATION', 'TENANT', NOW(), NOW())
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO permission_view_requirement (
    permission_id, required_view_permission_id, created_at, updated_at
)
SELECT mutation.id, required_view.id, NOW(), NOW()
FROM permission mutation
JOIN permission required_view ON required_view.permission_code = 'user.view'
WHERE mutation.permission_code = 'user.assign_self'
ON CONFLICT ON CONSTRAINT uq_permission_view_requirement DO NOTHING;

-- PLATFORM_SUPER_ADMIN keeps the whole catalogue.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT '00000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000001', p.id,
       NOW(), NOW(), NOW()
FROM permission p
WHERE p.permission_code = 'user.assign_self'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- Every seeded tenant administrator holds every ACTIVE tenant-scope code (V23).
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
  AND p.permission_code = 'user.assign_self'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

DO $$
DECLARE
    seeded  INT;
    paired  INT;
    missing BIGINT;
BEGIN
    SELECT COUNT(*) INTO seeded
    FROM permission
    WHERE permission_code = 'user.assign_self'
      AND id = '40000000-0000-0000-0000-000000000069'
      AND kind = 'MUTATION' AND status = 'ACTIVE' AND grant_scope = 'TENANT'
      AND risk_level = 'HIGH';
    IF seeded <> 1 THEN
        RAISE EXCEPTION 'Expected user.assign_self seeded once but found %', seeded;
    END IF;

    SELECT COUNT(*) INTO paired
    FROM permission_view_requirement r
    JOIN permission m ON m.id = r.permission_id
    JOIN permission v ON v.id = r.required_view_permission_id
    WHERE m.permission_code = 'user.assign_self' AND v.permission_code = 'user.view';
    IF paired <> 1 THEN
        RAISE EXCEPTION 'Expected user.assign_self to require user.view but found % pairing(s)',
            paired;
    END IF;

    SELECT COUNT(*) INTO missing
    FROM permission p
    WHERE p.permission_code = 'user.assign_self'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp
          WHERE rp.role_id = '50000000-0000-0000-0000-000000000001' AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION 'PLATFORM_SUPER_ADMIN lacks user.assign_self';
    END IF;

    SELECT COUNT(*) INTO missing
    FROM role r
    CROSS JOIN permission p
    WHERE r.system_role
      AND r.organisation_id <> '00000000-0000-0000-0000-000000000000'
      AND (
          r.role_code = 'TENANT_ADMIN'
          OR (r.role_code = 'local-admin'
              AND r.organisation_id = '22222222-2222-2222-2222-222222222222')
      )
      AND p.permission_code = 'user.assign_self'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION '% seeded tenant administrator(s) lack user.assign_self', missing;
    END IF;
END $$;
