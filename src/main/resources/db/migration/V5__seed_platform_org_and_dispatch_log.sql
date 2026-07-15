INSERT INTO organisation (
    id, tenant_code, display_name, country_code, base_currency_code, timezone, status,
    activated_at, created_at, updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    'PLATFORM',
    'Platform',
    'ZZ',
    'XXX',
    'UTC',
    'ACTIVE',
    NOW(),
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

INSERT INTO role (
    id, organisation_id, role_code, role_name, description, system_role, status, created_at,
    updated_at
) VALUES
(
    '50000000-0000-0000-0000-000000000001',
    '00000000-0000-0000-0000-000000000000',
    'PLATFORM_SUPER_ADMIN',
    'Platform Super Admin',
    'Global platform administrator with every baseline permission.',
    TRUE,
    'ACTIVE',
    NOW(),
    NOW()
),
(
    '50000000-0000-0000-0000-000000000002',
    '00000000-0000-0000-0000-000000000000',
    'PLATFORM_SUPPORT',
    'Platform Support',
    'Global platform support role with read-only operational support permissions.',
    TRUE,
    'ACTIVE',
    NOW(),
    NOW()
)
ON CONFLICT (organisation_id, role_code) DO NOTHING;

WITH super_admin_permissions (permission_number, permission_code) AS (
    VALUES
        (1, 'tenant.create'),
        (2, 'tenant.submit_for_approval'),
        (3, 'tenant.approve'),
        (4, 'tenant.activate'),
        (5, 'tenant.suspend'),
        (6, 'tenant.deprovision'),
        (7, 'branch.create'),
        (8, 'branch.approve'),
        (9, 'branch.activate'),
        (10, 'branch.suspend'),
        (11, 'branch.close'),
        (12, 'user.invite'),
        (13, 'user.approve'),
        (14, 'user.activate'),
        (15, 'user.suspend'),
        (16, 'user.deactivate'),
        (17, 'user.assign_branch'),
        (18, 'user.assign_role'),
        (19, 'role.create'),
        (20, 'role.update'),
        (21, 'role.assign_permission'),
        (22, 'audit.view'),
        (23, 'settings.update'),
        (24, 'business_date.view'),
        (25, 'business_date.advance')
),
support_permissions (permission_number, permission_code) AS (
    VALUES
        (1, 'audit.view'),
        (2, 'business_date.view')
),
role_permission_seed AS (
    SELECT
        ('51000000-0000-0000-0000-' || LPAD(permission_number::TEXT, 12, '0'))::UUID AS id,
        '50000000-0000-0000-0000-000000000001'::UUID AS role_id,
        permission_code
    FROM super_admin_permissions
    UNION ALL
    SELECT
        ('52000000-0000-0000-0000-' || LPAD(permission_number::TEXT, 12, '0'))::UUID AS id,
        '50000000-0000-0000-0000-000000000002'::UUID AS role_id,
        permission_code
    FROM support_permissions
)
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    role_permission_seed.id,
    '00000000-0000-0000-0000-000000000000',
    role_permission_seed.role_id,
    permission.id,
    NOW(),
    NOW(),
    NOW()
FROM role_permission_seed
JOIN permission ON permission.permission_code = role_permission_seed.permission_code
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;

CREATE TABLE IF NOT EXISTS identity_dispatch_log (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    user_id UUID NOT NULL REFERENCES user_account (id),
    dispatch_type TEXT NOT NULL,
    dispatch_key TEXT NOT NULL,
    status TEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    last_error TEXT,
    external_ref TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    updated_at TIMESTAMPTZ NOT NULL,
    updated_by UUID,
    row_version BIGINT NOT NULL DEFAULT 0,
    CONSTRAINT uq_identity_dispatch_key UNIQUE (dispatch_key),
    CONSTRAINT chk_identity_dispatch_type CHECK (
        dispatch_type IN ('KEYCLOAK_PROVISIONING', 'APPLICATION_INVITE')
    ),
    CONSTRAINT chk_identity_dispatch_status CHECK (status IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT chk_identity_dispatch_version CHECK (row_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_identity_dispatch_organisation_user
    ON identity_dispatch_log (organisation_id, user_id);
CREATE INDEX IF NOT EXISTS idx_identity_dispatch_status ON identity_dispatch_log (status);
