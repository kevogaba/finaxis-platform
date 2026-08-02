-- First-deployment bootstrap tenant. Keycloak credentials live in Keycloak and never in this
-- database; operators must rotate the local.admin identity before exposing the deployment.
INSERT INTO user_account (
    id, username, email, display_name, status, created_at, updated_at
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    'local.admin',
    'admin@finaxis.local',
    'Local Admin',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO keycloak_identity_link (
    id, user_id, provider, subject, realm_name, linked_at, created_at, updated_at
) VALUES (
    '11111111-1111-1111-1111-111111111112',
    '11111111-1111-1111-1111-111111111111',
    'KEYCLOAK',
    '11111111-1111-1111-1111-111111111111',
    'finaxis',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO organisation (
    id, tenant_code, display_name, legal_name, country_code, base_currency_code, timezone,
    status, activated_at, created_at, updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222222',
    'FINAXIS-LOCAL',
    'Finaxis Local Organisation',
    'Finaxis Local Organisation',
    'KE',
    'KES',
    'Africa/Nairobi',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO branch (
    id, organisation_id, branch_code, branch_name, branch_type, status, timezone,
    opened_on, created_at, updated_at
) VALUES
(
    '33333333-3333-3333-3333-333333333333',
    '22222222-2222-2222-2222-222222222222',
    'HQ',
    'Head Office',
    'HEAD_OFFICE',
    'ACTIVE',
    'Africa/Nairobi',
    '2026-07-04',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
),
(
    '44444444-4444-4444-4444-444444444444',
    '22222222-2222-2222-2222-222222222222',
    'OPS',
    'Operations Branch',
    'OPERATIONS',
    'ACTIVE',
    'Africa/Nairobi',
    '2026-07-04',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO user_organisation_membership (
    id, organisation_id, user_id, membership_status, membership_type, primary_branch_id,
    joined_at, created_at, updated_at
) VALUES (
    '55555555-5555-5555-5555-555555555555',
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    'ACTIVE',
    'ADMIN',
    '33333333-3333-3333-3333-333333333333',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO user_branch_assignment (
    id, organisation_id, user_id, branch_id, assignment_type, status, assigned_at, created_at,
    updated_at
) VALUES
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    '33333333-3333-3333-3333-333333333333',
    'HOME',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
),
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2',
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    '44444444-4444-4444-4444-444444444444',
    'OPERATE',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO role (
    id, organisation_id, role_code, role_name, description, system_role, status, created_at,
    updated_at
) VALUES (
    '77777777-7777-7777-7777-777777777777',
    '22222222-2222-2222-2222-222222222222',
    'local-admin',
    'Local Administrator',
    'Default local development administrator role.',
    TRUE,
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
) VALUES
(
    '88888888-8888-8888-8888-888888888801',
    '22222222-2222-2222-2222-222222222222',
    '77777777-7777-7777-7777-777777777777',
    '66666666-6666-6666-6666-666666666601',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
),
(
    '88888888-8888-8888-8888-888888888803',
    '22222222-2222-2222-2222-222222222222',
    '77777777-7777-7777-7777-777777777777',
    '40000000-0000-0000-0000-000000000040',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO user_role_assignment (
    id, organisation_id, user_id, role_id, scope_type, status, assigned_at, created_at, updated_at
) VALUES (
    '99999999-9999-9999-9999-999999999999',
    '22222222-2222-2222-2222-222222222222',
    '11111111-1111-1111-1111-111111111111',
    '77777777-7777-7777-7777-777777777777',
    'TENANT',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO organisation_setting (
    id, organisation_id, setting_key, setting_value, value_type, effective_from, created_at,
    updated_at
) VALUES (
    'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb',
    '22222222-2222-2222-2222-222222222222',
    'business-date.timezone',
    '"Africa/Nairobi"'::JSONB,
    'STRING',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
);

INSERT INTO business_date (
    id, organisation_id, current_business_date, status
) VALUES (
    'cccccccc-cccc-cccc-cccc-cccccccccccc',
    '22222222-2222-2222-2222-222222222222',
    '2026-07-04',
    'OPEN'
);

INSERT INTO role_permission (
    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    '22222222-2222-2222-2222-222222222222',
    '77777777-7777-7777-7777-777777777777',
    p.id,
    NOW(),
    NOW(),
    NOW()
FROM permission p
WHERE p.permission_code IN (
    'auth.select_branch',
    'branch.reactivate',
    'branch.view',
    'branch_assignment.view',
    'membership.reactivate',
    'membership.revoke',
    'membership.suspend',
    'membership.view',
    'permission.view',
    'role.activate',
    'role.deactivate',
    'role.remove_permission',
    'role.view',
    'role_assignment.view',
    'settings.view',
    'user.revoke_branch',
    'user.revoke_role',
    'user.view'
);
