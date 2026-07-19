-- Seed new foundation API permissions
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000040', 'auth.select_organisation', 'Select organisation', 'iam',
        'Select organisation context.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000041', 'auth.select_branch', 'Select branch', 'iam',
        'Select branch context.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000042', 'tenant.view', 'View organisation', 'tenant',
        'View organisation details.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000043', 'tenant.update_draft', 'Update organisation draft', 'tenant',
        'Update organisation draft details.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000044', 'tenant.reject', 'Reject organisation draft', 'tenant',
        'Reject organisation draft.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000045', 'tenant.reactivate', 'Reactivate organisation', 'tenant',
        'Reactivate a suspended organisation.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000046', 'tenant.bootstrap_retry', 'Retry administrator bootstrap', 'tenant',
        'Retry initial tenant administrator bootstrap.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000047', 'branch.view', 'View branch', 'branch',
        'View branch details.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000048', 'branch.reactivate', 'Reactivate branch', 'branch',
        'Reactivate a suspended branch.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000049', 'user.view', 'View users', 'iam',
        'View organisation users.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000050', 'membership.view', 'View memberships', 'iam',
        'View organisation memberships.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000051', 'membership.suspend', 'Suspend membership', 'iam',
        'Suspend an active membership.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000052', 'membership.reactivate', 'Reactivate membership', 'iam',
        'Reactivate a suspended membership.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000053', 'membership.revoke', 'Revoke membership', 'iam',
        'Revoke an active membership.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000054', 'branch_assignment.view', 'View branch assignments', 'iam',
        'View user branch assignments.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000055', 'user.revoke_branch', 'Revoke branch assignment', 'iam',
        'Revoke branch assignment.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000056', 'role.view', 'View roles', 'iam',
        'View organisation roles.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000057', 'role.activate', 'Activate role', 'iam',
        'Activate an inactive role.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000058', 'role.deactivate', 'Deactivate role', 'iam',
        'Deactivate an active role.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000059', 'role.remove_permission', 'Remove role permission', 'iam',
        'Remove a permission from a role.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000060', 'role_assignment.view', 'View role assignments', 'iam',
        'View user role assignments.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000061', 'user.revoke_role', 'Revoke role assignment', 'iam',
        'Revoke role assignment.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000062', 'permission.view', 'View permissions', 'iam',
        'View permission catalog.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000063', 'settings.view', 'View settings', 'settings',
        'View organisation settings.', 'LOW', 'ACTIVE', NOW(), NOW())
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    risk_level = EXCLUDED.risk_level,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

-- Remove user.suspend, user.activate, and user.deactivate from tenant bootstrap roles
DELETE FROM role_permission
WHERE permission_id IN (
    SELECT id FROM permission WHERE permission_code IN ('user.suspend', 'user.activate', 'user.deactivate')
)
AND role_id IN (
    SELECT id FROM role WHERE role_code IN ('TENANT_ADMIN', 'IAM_ADMIN')
);

-- Grant all new permissions to the platform super-admin role seeded in V5
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    '00000000-0000-0000-0000-000000000000',
    '50000000-0000-0000-0000-000000000001',
    p.id,
    NOW(),
    NOW(),
    NOW()
FROM permission p
WHERE p.permission_code IN (
    'auth.select_organisation', 'auth.select_branch', 'tenant.view', 'tenant.update_draft',
    'tenant.reject', 'tenant.reactivate', 'tenant.bootstrap_retry', 'branch.view',
    'branch.reactivate', 'user.view', 'membership.view', 'membership.suspend',
    'membership.reactivate', 'membership.revoke', 'branch_assignment.view',
    'user.revoke_branch', 'role.view', 'role.activate', 'role.deactivate',
    'role.remove_permission', 'role_assignment.view', 'user.revoke_role',
    'permission.view', 'settings.view'
)
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;

-- Grant select permissions to active operational tenant roles across all existing organisations
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    r.organisation_id,
    r.id,
    p.id,
    NOW(),
    NOW(),
    NOW()
FROM role r
JOIN permission p ON p.permission_code IN ('auth.select_organisation', 'auth.select_branch')
WHERE r.role_code IN ('TENANT_ADMIN', 'TENANT_AUDITOR', 'IAM_ADMIN', 'BRANCH_MANAGER', 'BRANCH_OPERATOR')
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;

-- Grant tenant-level permissions to the local-admin role seeded in V2
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    gen_random_uuid(),
    '22222222-2222-2222-2222-222222222222',
    '77777777-7777-7777-7777-777777777777',
    p.id,
    NOW(),
    NOW(),
    NOW()
FROM permission p
WHERE p.permission_code IN (
    'auth.select_organisation', 'auth.select_branch', 'branch.view',
    'branch.reactivate', 'user.view', 'membership.view', 'membership.suspend',
    'membership.reactivate', 'membership.revoke', 'branch_assignment.view',
    'user.revoke_branch', 'role.view', 'role.activate', 'role.deactivate',
    'role.remove_permission', 'role_assignment.view', 'user.revoke_role',
    'permission.view', 'settings.view'
)
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;
