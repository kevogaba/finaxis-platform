-- Grants the existing local-dev "local.admin" identity an additional membership in the
-- reserved PLATFORM organisation (seeded by V5) so scripts/local-smoke.sh can exercise
-- platform-scoped routes (tenant onboarding maker-checker, bootstrap retry) end to end
-- without provisioning a second Keycloak identity. Local development only.

INSERT INTO user_organisation_membership (
    id, organisation_id, user_id, membership_status, membership_type, primary_branch_id,
    joined_at, created_at, updated_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddddd',
    '00000000-0000-0000-0000-000000000000',
    '11111111-1111-1111-1111-111111111111',
    'ACTIVE',
    'ADMIN',
    NULL,
    NOW(),
    NOW(),
    NOW()
)
ON CONFLICT (organisation_id, user_id) DO NOTHING;

INSERT INTO user_role_assignment (
    id, organisation_id, user_id, role_id, scope_type, status, assigned_at, created_at, updated_at
) VALUES (
    'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee',
    '00000000-0000-0000-0000-000000000000',
    '11111111-1111-1111-1111-111111111111',
    '50000000-0000-0000-0000-000000000001',
    'TENANT',
    'ACTIVE',
    NOW(),
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;
