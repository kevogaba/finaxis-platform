-- Issue #161: the permissions that gate editing a user's profile and a membership.
--
-- WHAT. Two permission codes, reference data only, no schema:
--
--   user.update        MUTATION, PLATFORM scope, HIGH. Edits a global user account's display name
--                      and phone (PATCH /api/v1/platform/users/{user_id}). Evaluated only in the
--                      PLATFORM organisation, like user.suspend: a user account is global, so no
--                      single tenant owns it. Requires user.view.
--   membership.update  MUTATION, TENANT scope, HIGH. Edits a membership's type and primary branch
--                      (PATCH /api/v1/memberships/{membership_id}). Requires membership.view.
--
-- IDENTIFIERS. 40000000-...-000000000065 and ...66 are the next free ids after V19's ...64 in the
-- foundation block.
--
-- GRANTS. PLATFORM_SUPER_ADMIN holds every ACTIVE code, so it gets both. membership.update is a
-- TENANT-scope code, so every seeded tenant administrator (TENANT_ADMIN of every tenant, and the
-- bootstrap local-admin) gets it, as V23 requires of any later permission migration. user.update
-- is PLATFORM scope and goes to no tenant role. Nobody else is granted either: editing a profile
-- is a higher-trust act than viewing it, and no existing role could do it before.
--
-- CATALOGUE METADATA. Both columns of V22 are NOT NULL, so kind and grant_scope are written with
-- the row, and the view requirements with them (ADR 0030).
--
-- Idempotent (ON CONFLICT DO NOTHING), with its preconditions and post-conditions asserted below.

DO $$
DECLARE
    prerequisite RECORD;
BEGIN
    FOR prerequisite IN
        SELECT * FROM (VALUES ('user.view'), ('membership.view')) AS needed (permission_code)
    LOOP
        IF NOT EXISTS (
            SELECT 1 FROM permission
            WHERE permission_code = prerequisite.permission_code
              AND status = 'ACTIVE' AND kind = 'VIEW'
        ) THEN
            RAISE EXCEPTION 'Expected the ACTIVE VIEW permission % to exist',
                prerequisite.permission_code;
        END IF;
    END LOOP;
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
    ('40000000-0000-0000-0000-000000000065', 'user.update', 'Update user profile', 'iam',
     'Edit the display name and phone of a user account, across organisations.', 'HIGH',
     'ACTIVE', 'MUTATION', 'PLATFORM', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000066', 'membership.update', 'Update membership', 'iam',
     'Edit the type and primary branch of a membership.', 'HIGH',
     'ACTIVE', 'MUTATION', 'TENANT', NOW(), NOW())
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO permission_view_requirement (
    permission_id, required_view_permission_id, created_at, updated_at
)
SELECT mutation.id, required_view.id, NOW(), NOW()
FROM (VALUES ('user.update', 'user.view'), ('membership.update', 'membership.view'))
         AS requirement (permission_code, required_view_code)
JOIN permission mutation ON mutation.permission_code = requirement.permission_code
JOIN permission required_view ON required_view.permission_code = requirement.required_view_code
ON CONFLICT ON CONSTRAINT uq_permission_view_requirement DO NOTHING;

-- PLATFORM_SUPER_ADMIN keeps the whole catalogue.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT '00000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000001', p.id,
       NOW(), NOW(), NOW()
FROM permission p
WHERE p.permission_code IN ('user.update', 'membership.update')
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
  AND p.permission_code = 'membership.update'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

DO $$
DECLARE
    seeded  INT;
    paired  INT;
    missing BIGINT;
BEGIN
    SELECT COUNT(*) INTO seeded
    FROM permission
    WHERE permission_code IN ('user.update', 'membership.update')
      AND kind = 'MUTATION' AND status = 'ACTIVE'
      AND grant_scope = CASE permission_code WHEN 'user.update' THEN 'PLATFORM' ELSE 'TENANT' END;
    IF seeded <> 2 THEN
        RAISE EXCEPTION 'Expected user.update and membership.update seeded once each but found %',
            seeded;
    END IF;

    SELECT COUNT(*) INTO paired
    FROM permission_view_requirement r
    JOIN permission m ON m.id = r.permission_id
    WHERE m.permission_code IN ('user.update', 'membership.update');
    IF paired <> 2 THEN
        RAISE EXCEPTION 'Expected one view requirement per new mutation but found %', paired;
    END IF;

    SELECT COUNT(*) INTO missing
    FROM permission p
    WHERE p.permission_code IN ('user.update', 'membership.update')
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp
          WHERE rp.role_id = '50000000-0000-0000-0000-000000000001' AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION 'PLATFORM_SUPER_ADMIN lacks % of the new permissions', missing;
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
      AND p.permission_code = 'membership.update'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION '% seeded tenant administrator(s) lack membership.update', missing;
    END IF;
END $$;
