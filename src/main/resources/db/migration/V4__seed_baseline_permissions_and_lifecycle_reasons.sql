-- The platform permission catalogue is global and immutable by code. Organisation provisioning
-- maps these stable permissions to organisation-local system roles.
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000001', 'tenant.create', 'Create organisation', 'tenant',
        'Create an organisation draft.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000002', 'tenant.submit_for_approval',
        'Submit organisation for approval', 'tenant', 'Submit an organisation draft.', 'HIGH',
        'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000003', 'tenant.approve', 'Approve organisation', 'tenant',
        'Approve organisation provisioning.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000004', 'tenant.activate', 'Activate organisation', 'tenant',
        'Activate an organisation after setup.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000005', 'tenant.suspend', 'Suspend organisation', 'tenant',
        'Suspend organisation operations.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000006', 'tenant.deprovision', 'Deprovision organisation',
        'tenant', 'Start controlled organisation deprovisioning.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000007', 'branch.create', 'Create branch', 'branch',
        'Create a branch draft.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000008', 'branch.approve', 'Approve branch', 'branch',
        'Approve a branch.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000009', 'branch.activate', 'Activate branch', 'branch',
        'Activate an approved branch.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000010', 'branch.suspend', 'Suspend branch', 'branch',
        'Suspend branch operations.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000011', 'branch.close', 'Close branch', 'branch',
        'Close a branch without deleting it.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000012', 'user.invite', 'Invite user', 'iam',
        'Invite a user to an organisation.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000013', 'user.approve', 'Approve user', 'iam',
        'Approve a user membership.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000014', 'user.activate', 'Activate user', 'iam',
        'Activate an invited user.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000015', 'user.suspend', 'Suspend user', 'iam',
        'Suspend user access.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000016', 'user.deactivate', 'Deactivate user', 'iam',
        'Deactivate a user.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000017', 'user.assign_branch', 'Assign user branch', 'iam',
        'Assign a user to an active branch.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000018', 'user.assign_role', 'Assign user role', 'iam',
        'Assign a role to a user.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000019', 'role.create', 'Create role', 'iam',
        'Create an organisation role.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000020', 'role.update', 'Update role', 'iam',
        'Update an organisation role.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000021', 'role.assign_permission',
        'Assign permission to role', 'iam', 'Change role permissions.', 'CRITICAL', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000022', 'audit.view', 'View audit log', 'audit',
        'View organisation audit events.', 'MEDIUM', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000023', 'settings.update', 'Update settings', 'settings',
        'Update organisation settings.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000024', 'business_date.view', 'View business date',
        'settings', 'View the organisation business date.', 'LOW', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000025', 'business_date.advance', 'Advance business date',
        'settings', 'Advance the organisation business date.', 'CRITICAL', 'ACTIVE', NOW(), NOW())
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    risk_level = EXCLUDED.risk_level,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

ALTER TABLE organisation_transition_log ADD COLUMN reason TEXT;
ALTER TABLE branch_transition_log ADD COLUMN reason TEXT;
ALTER TABLE user_account_transition_log ADD COLUMN reason TEXT;
ALTER TABLE user_organisation_membership_transition_log ADD COLUMN reason TEXT;
ALTER TABLE audit_event ADD COLUMN reason TEXT;
