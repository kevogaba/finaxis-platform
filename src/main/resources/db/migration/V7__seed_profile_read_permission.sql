-- iam.profile.read backs /api/v1/auth/me but was only present in the dev-only smoke-data seed
-- (V2), never in the global permission catalogue. Add it here so provisioned tenants can grant it.
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000026', 'iam.profile.read', 'View own profile', 'iam',
        'View the authenticated user''s own profile and active-organisation context.', 'LOW',
        'ACTIVE', NOW(), NOW())
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    risk_level = EXCLUDED.risk_level,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;
