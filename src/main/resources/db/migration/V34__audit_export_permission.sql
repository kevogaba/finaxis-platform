-- Issue #167: the permission that gates the audit CSV export (ADR 0039).
--
-- WHAT. One permission code, reference data only, no schema:
--
--   audit.export   MUTATION, TENANT scope, HIGH. Streams the tenant audit log as CSV
--                  (GET /api/v1/tenant/audit-events/export). Requires audit.view. It is a
--                  mutation by kind, not because it changes the log: it moves data out of the
--                  system's access controls, and ADR 0030 decision 4 (a mutation implies its
--                  view) is the rule that makes the named 403 say which code is missing.
--
-- IDENTIFIER. 40000000-...-000000000068 is the next free id after V33's ...67 in the foundation
-- block.
--
-- GRANTS. PLATFORM_SUPER_ADMIN holds every ACTIVE code, so it gets it. audit.export is a
-- TENANT-scope code, so every seeded tenant administrator (TENANT_ADMIN of every tenant, and the
-- bootstrap local-admin) gets it, as V23 requires of any later permission migration. Nobody else
-- is granted it: TENANT_AUDITOR holds audit.view and stays without audit.export until a tenant
-- administrator grants it, since reusing the view for the export would let every read-only
-- viewer bulk-extract the log. No other seeded bundle, no PLATFORM_SUPPORT and no direct
-- membership override gets it, and roles a tenant composed itself (system_role = FALSE) are never
-- touched.
--
-- CATALOGUE METADATA. Both columns of V22 are NOT NULL, so kind and grant_scope are written with
-- the row, and the view requirement with them (ADR 0030).
--
-- Idempotent (ON CONFLICT DO NOTHING), with its preconditions and post-conditions asserted below.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM permission
        WHERE permission_code = 'audit.view' AND status = 'ACTIVE' AND kind = 'VIEW'
    ) THEN
        RAISE EXCEPTION 'Expected the ACTIVE VIEW permission audit.view to exist';
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
    ('40000000-0000-0000-0000-000000000068', 'audit.export', 'Export audit events', 'audit',
     'Export the organisation audit log as CSV: a bulk extraction, bounded and audited.',
     'HIGH', 'ACTIVE', 'MUTATION', 'TENANT', NOW(), NOW())
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO permission_view_requirement (
    permission_id, required_view_permission_id, created_at, updated_at
)
SELECT mutation.id, required_view.id, NOW(), NOW()
FROM permission mutation
JOIN permission required_view ON required_view.permission_code = 'audit.view'
WHERE mutation.permission_code = 'audit.export'
ON CONFLICT ON CONSTRAINT uq_permission_view_requirement DO NOTHING;

-- PLATFORM_SUPER_ADMIN keeps the whole catalogue.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT '00000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000001', p.id,
       NOW(), NOW(), NOW()
FROM permission p
WHERE p.permission_code = 'audit.export'
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
  AND p.permission_code = 'audit.export'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

DO $$
DECLARE
    seeded  INT;
    paired  INT;
    missing BIGINT;
BEGIN
    SELECT COUNT(*) INTO seeded
    FROM permission
    WHERE permission_code = 'audit.export'
      AND id = '40000000-0000-0000-0000-000000000068'
      AND kind = 'MUTATION' AND status = 'ACTIVE' AND grant_scope = 'TENANT'
      AND risk_level = 'HIGH';
    IF seeded <> 1 THEN
        RAISE EXCEPTION 'Expected audit.export seeded once but found %', seeded;
    END IF;

    SELECT COUNT(*) INTO paired
    FROM permission_view_requirement r
    JOIN permission m ON m.id = r.permission_id
    JOIN permission v ON v.id = r.required_view_permission_id
    WHERE m.permission_code = 'audit.export' AND v.permission_code = 'audit.view';
    IF paired <> 1 THEN
        RAISE EXCEPTION 'Expected audit.export to require audit.view but found % pairing(s)',
            paired;
    END IF;

    SELECT COUNT(*) INTO missing
    FROM permission p
    WHERE p.permission_code = 'audit.export'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp
          WHERE rp.role_id = '50000000-0000-0000-0000-000000000001' AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION 'PLATFORM_SUPER_ADMIN lacks audit.export';
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
      AND p.permission_code = 'audit.export'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION '% seeded tenant administrator(s) lack audit.export', missing;
    END IF;
END $$;
