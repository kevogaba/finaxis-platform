-- New setup-level permissions for tenant settings and business-date / COB foundation.
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000030', 'tenant_setting.manage_platform',
        'Manage platform-only settings', 'settings',
        'Create or update platform-admin-only organisation settings.', 'HIGH', 'ACTIVE',
        NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000031', 'business_date.reopen', 'Reopen business date',
        'settings', 'Reopen a closed organisation business date.', 'CRITICAL', 'ACTIVE',
        NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000032', 'cob.start', 'Start close of business', 'settings',
        'Start the close-of-business status transition.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000033', 'cob.complete', 'Complete close of business',
        'settings', 'Complete the close-of-business status transition.', 'HIGH', 'ACTIVE',
        NOW(), NOW())
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    risk_level = EXCLUDED.risk_level,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

-- Grant every new permission to the platform super-admin role seeded in V5.
WITH new_super_admin_permissions (permission_number, permission_code) AS (
    VALUES
        (30, 'tenant_setting.manage_platform'),
        (31, 'business_date.reopen'),
        (32, 'cob.start'),
        (33, 'cob.complete')
)
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    ('51000000-0000-0000-0000-' || LPAD(permission_number::TEXT, 12, '0'))::UUID,
    '00000000-0000-0000-0000-000000000000',
    '50000000-0000-0000-0000-000000000001',
    permission.id,
    NOW(),
    NOW(),
    NOW()
FROM new_super_admin_permissions
JOIN permission ON permission.permission_code = new_super_admin_permissions.permission_code
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;

-- Existing tenant administrators need the new business-date / COB actions too. Platform-only
-- setting management is deliberately excluded; it remains reserved to platform super-admins.
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    tenant_admin.organisation_id,
    tenant_admin.id,
    permission.id,
    NOW(),
    NOW(),
    NOW()
FROM role AS tenant_admin
JOIN permission ON permission.permission_code IN (
    'business_date.reopen', 'cob.start', 'cob.complete'
)
WHERE tenant_admin.role_code = 'TENANT_ADMIN'
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;

-- Append-only history of business-date / COB status changes; the singleton business_date row
-- keeps only current state, so history lives here (mirrors the append-only audit posture).
CREATE TABLE business_date_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    event_type TEXT NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    from_business_date DATE,
    to_business_date DATE NOT NULL,
    actor_id UUID,
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    CONSTRAINT chk_business_date_history_event CHECK (
        event_type IN ('INITIALIZED', 'ADVANCED', 'COB_STARTED', 'COB_COMPLETED', 'REOPENED')
    )
);

CREATE INDEX idx_business_date_history_org_time
    ON business_date_history (organisation_id, occurred_at DESC);

COMMENT ON TABLE business_date_history IS
    'Append-only log of business-date and COB status changes per organisation.';
