CREATE TABLE branch (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation(id) ON DELETE CASCADE,
    code VARCHAR(100) NOT NULL,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_branch_organisation_code UNIQUE (organisation_id, code)
);

ALTER TABLE membership_branch_scope
    ADD CONSTRAINT fk_membership_branch_scope_branch
    FOREIGN KEY (branch_id) REFERENCES branch(id);

INSERT INTO app_user (
    id,
    keycloak_subject,
    email,
    full_name,
    status,
    created_at,
    updated_at
) VALUES (
    '11111111-1111-1111-1111-111111111111',
    '11111111-1111-1111-1111-111111111111',
    'admin@finaxis.local',
    'Local Admin',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO organisation (
    id,
    name,
    code,
    status,
    created_at,
    updated_at
) VALUES (
    '22222222-2222-2222-2222-222222222222',
    'Finaxis Local Organisation',
    'FINAXIS-LOCAL',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO branch (
    id,
    organisation_id,
    code,
    name,
    status,
    created_at,
    updated_at
) VALUES
(
    '33333333-3333-3333-3333-333333333333',
    '22222222-2222-2222-2222-222222222222',
    'HQ',
    'Head Office',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
),
(
    '44444444-4444-4444-4444-444444444444',
    '22222222-2222-2222-2222-222222222222',
    'OPS',
    'Operations Branch',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO organisation_membership (
    id,
    user_id,
    organisation_id,
    status,
    joined_at,
    updated_at
) VALUES (
    '55555555-5555-5555-5555-555555555555',
    '11111111-1111-1111-1111-111111111111',
    '22222222-2222-2222-2222-222222222222',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO permission (
    id,
    module,
    resource,
    action,
    code,
    description,
    risk_level,
    status,
    created_at
) VALUES
(
    '66666666-6666-6666-6666-666666666601',
    'iam',
    'profile',
    'read',
    'iam.profile.read',
    'Read the authenticated user profile and active tenant context.',
    NULL,
    'ACTIVE',
    '2026-07-04T00:00:00Z'
),
(
    '66666666-6666-6666-6666-666666666602',
    'iam',
    'user',
    'invite',
    'iam.user.invite',
    'Invite users into the active organisation.',
    'MEDIUM',
    'ACTIVE',
    '2026-07-04T00:00:00Z'
),
(
    '66666666-6666-6666-6666-666666666603',
    'logistics',
    'shipment',
    'approve',
    'logistics.shipment.approve',
    'Approve shipments in the active organisation.',
    'HIGH',
    'ACTIVE',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO role (
    id,
    organisation_id,
    name,
    code,
    description,
    status,
    created_at,
    updated_at
) VALUES (
    '77777777-7777-7777-7777-777777777777',
    '22222222-2222-2222-2222-222222222222',
    'Local Administrator',
    'local-admin',
    'Default local development administrator role.',
    'ACTIVE',
    '2026-07-04T00:00:00Z',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO role_permission (id, role_id, permission_id) VALUES
(
    '88888888-8888-8888-8888-888888888801',
    '77777777-7777-7777-7777-777777777777',
    '66666666-6666-6666-6666-666666666601'
),
(
    '88888888-8888-8888-8888-888888888802',
    '77777777-7777-7777-7777-777777777777',
    '66666666-6666-6666-6666-666666666602'
),
(
    '88888888-8888-8888-8888-888888888803',
    '77777777-7777-7777-7777-777777777777',
    '66666666-6666-6666-6666-666666666603'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO membership_role (id, membership_id, role_id, granted_by, granted_at) VALUES (
    '99999999-9999-9999-9999-999999999999',
    '55555555-5555-5555-5555-555555555555',
    '77777777-7777-7777-7777-777777777777',
    '11111111-1111-1111-1111-111111111111',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;

INSERT INTO membership_branch_scope (id, membership_id, branch_id, granted_by, granted_at) VALUES
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1',
    '55555555-5555-5555-5555-555555555555',
    '33333333-3333-3333-3333-333333333333',
    '11111111-1111-1111-1111-111111111111',
    '2026-07-04T00:00:00Z'
),
(
    'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2',
    '55555555-5555-5555-5555-555555555555',
    '44444444-4444-4444-4444-444444444444',
    '11111111-1111-1111-1111-111111111111',
    '2026-07-04T00:00:00Z'
) ON CONFLICT (id) DO NOTHING;
