-- Issue #297: archive a role (ADR 0035).
--
-- WHAT. One table, one permission code, no change to any existing row's meaning:
--
--   role_transition_log  The role's transition log. Role status is now a finite state machine
--                        (transitions ACTIVATE, DEACTIVATE, ARCHIVE and ARCHIVE_DISABLED over the
--                        three statuses chk_role_status has always allowed), so every status
--                        change writes one row here, in the same transaction as the status
--                        itself, exactly as branch, membership and the accounting aggregates do.
--                        ARCHIVED is terminal: nothing leaves it.
--   role.archive         MUTATION, TENANT scope, HIGH. Archives a role
--                        (POST /api/v1/tenant/roles/{role_id}/archive). Requires
--                        role.view.
--
-- THE TABLE. Transcribed from the shape of V1's membership log: id and guid default to uuidv7(),
-- the composite foreign key (organisation_id, entity_id) -> role (organisation_id, id) means a
-- row can name only a role of its own organisation, and the index serves "this role's history,
-- newest first". There is no foreign key from a log row's actor, as for every other log. No
-- trigger (ADR 0024 does not apply): the application writes the row through TransitionExecutor.
--
-- IDENTIFIER. 40000000-...-000000000067 is the next free id after V32's ...66 in the foundation
-- block.
--
-- GRANTS. PLATFORM_SUPER_ADMIN holds every ACTIVE code, so it gets it. role.archive is a
-- TENANT-scope code, so every seeded tenant administrator (TENANT_ADMIN of every tenant, and the
-- bootstrap local-admin) gets it, as V23 requires of any later permission migration. Nobody else
-- is granted it: archiving is a terminal act and no existing role could do it before. Roles a
-- tenant composed itself (system_role = FALSE) are never touched.
--
-- CATALOGUE METADATA. Both columns of V22 are NOT NULL, so kind and grant_scope are written with
-- the row, and the view requirement with them (ADR 0030).
--
-- Idempotent (IF NOT EXISTS, ON CONFLICT DO NOTHING), with its preconditions and post-conditions
-- asserted below.

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM permission
        WHERE permission_code = 'role.view' AND status = 'ACTIVE' AND kind = 'VIEW'
    ) THEN
        RAISE EXCEPTION 'Expected the ACTIVE VIEW permission role.view to exist';
    END IF;
    IF to_regclass('role') IS NULL THEN
        RAISE EXCEPTION 'Expected the V1 role table to exist';
    END IF;
    IF NOT EXISTS (
        SELECT 1 FROM role
        WHERE id = '50000000-0000-0000-0000-000000000001'
          AND organisation_id = '00000000-0000-0000-0000-000000000000' AND system_role
    ) THEN
        RAISE EXCEPTION 'Expected the V2 PLATFORM_SUPER_ADMIN system role to exist';
    END IF;
END $$;

CREATE TABLE IF NOT EXISTS role_transition_log (
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
    CONSTRAINT uq_role_transition_log_guid UNIQUE (guid),
    CONSTRAINT fk_role_transition_log_role
        FOREIGN KEY (organisation_id, entity_id) REFERENCES role (organisation_id, id),
    CONSTRAINT chk_role_transition_log_version CHECK (row_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_role_transition_log_organisation_entity
    ON role_transition_log (organisation_id, entity_id, created_at DESC);

COMMENT ON TABLE role_transition_log IS
    'One row per role status change (ACTIVATE, DEACTIVATE, ARCHIVE, ARCHIVE_DISABLED), written '
    'with the status change. ARCHIVED is terminal; the role keeps its code, which stays reserved.';

INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status, kind,
    grant_scope, created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000067', 'role.archive', 'Archive role', 'iam',
     'Archive a role: a terminal state, refused while the role has an active assignment.',
     'HIGH', 'ACTIVE', 'MUTATION', 'TENANT', NOW(), NOW())
ON CONFLICT (permission_code) DO NOTHING;

INSERT INTO permission_view_requirement (
    permission_id, required_view_permission_id, created_at, updated_at
)
SELECT mutation.id, required_view.id, NOW(), NOW()
FROM permission mutation
JOIN permission required_view ON required_view.permission_code = 'role.view'
WHERE mutation.permission_code = 'role.archive'
ON CONFLICT ON CONSTRAINT uq_permission_view_requirement DO NOTHING;

-- PLATFORM_SUPER_ADMIN keeps the whole catalogue.
INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT '00000000-0000-0000-0000-000000000000', '50000000-0000-0000-0000-000000000001', p.id,
       NOW(), NOW(), NOW()
FROM permission p
WHERE p.permission_code = 'role.archive'
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
  AND p.permission_code = 'role.archive'
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

DO $$
DECLARE
    seeded  INT;
    paired  INT;
    missing BIGINT;
BEGIN
    IF to_regclass('role_transition_log') IS NULL THEN
        RAISE EXCEPTION 'Expected role_transition_log to exist';
    END IF;

    SELECT COUNT(*) INTO seeded
    FROM permission
    WHERE permission_code = 'role.archive'
      AND id = '40000000-0000-0000-0000-000000000067'
      AND kind = 'MUTATION' AND status = 'ACTIVE' AND grant_scope = 'TENANT'
      AND risk_level = 'HIGH';
    IF seeded <> 1 THEN
        RAISE EXCEPTION 'Expected role.archive seeded once but found %', seeded;
    END IF;

    SELECT COUNT(*) INTO paired
    FROM permission_view_requirement r
    JOIN permission m ON m.id = r.permission_id
    JOIN permission v ON v.id = r.required_view_permission_id
    WHERE m.permission_code = 'role.archive' AND v.permission_code = 'role.view';
    IF paired <> 1 THEN
        RAISE EXCEPTION 'Expected role.archive to require role.view but found % pairing(s)',
            paired;
    END IF;

    SELECT COUNT(*) INTO missing
    FROM permission p
    WHERE p.permission_code = 'role.archive'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp
          WHERE rp.role_id = '50000000-0000-0000-0000-000000000001' AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION 'PLATFORM_SUPER_ADMIN lacks role.archive';
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
      AND p.permission_code = 'role.archive'
      AND NOT EXISTS (
          SELECT 1 FROM role_permission rp WHERE rp.role_id = r.id AND rp.permission_id = p.id
      );
    IF missing > 0 THEN
        RAISE EXCEPTION '% seeded tenant administrator(s) lack role.archive', missing;
    END IF;
END $$;
