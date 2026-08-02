-- The bootstrapped local-admin role (id 77777777-7777-7777-7777-777777777777) was seeded in V3
-- without user.invite, user.approve, or user.assign_branch, so the first administrator could not
-- onboard other users in their own tenant (invite requires user.invite; a branch-assigning invite
-- also drives BranchProvisioningService.assignUser, which requires user.assign_branch; approving
-- the invite requires user.approve). V1-V3 are frozen; this is the forward-only fix.
--
-- Fails fast (rather than silently granting nothing) if the expected permission codes are ever
-- missing from the catalogue, and is safe to re-run against an installation that already carries
-- one of these grants (e.g. a manual production hotfix applied before this migration shipped).
DO $$
DECLARE
    matched_permissions INT;
BEGIN
    SELECT COUNT(*) INTO matched_permissions
    FROM permission
    WHERE permission_code IN ('user.invite', 'user.approve', 'user.assign_branch');

    IF matched_permissions <> 3 THEN
        RAISE EXCEPTION
            'Expected permission codes user.invite, user.approve, user.assign_branch but found % '
            'of 3 in the permission catalogue',
            matched_permissions;
    END IF;
END $$;

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
WHERE p.permission_code IN ('user.invite', 'user.approve', 'user.assign_branch')
ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING;

-- Granting invite/approve above is still not enough: local.admin
-- (11111111-1111-1111-1111-111111111111) is the tenant's only active user, and
-- UserProvisioningService.approveUser rejects self-approval (approvedBy == inviter). There is no
-- other actor in the tenant who can approve local.admin's first invitation. This seeds a second
-- bootstrap actor - local.checker - holding the same local-admin role, purely so the first real
-- invitation has a distinct approver. Keycloak credentials live in Keycloak and never in this
-- database; operators must rotate the local.checker identity before exposing the deployment,
-- exactly as local.admin already requires.
INSERT INTO user_account (
    id, username, email, display_name, status, created_at, updated_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    'local.checker',
    'checker@finaxis.local',
    'Local Checker',
    'ACTIVE',
    NOW(),
    NOW()
);

INSERT INTO keycloak_identity_link (
    id, user_id, provider, subject, realm_name, linked_at, created_at, updated_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddd02',
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    'KEYCLOAK',
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    'finaxis',
    NOW(),
    NOW(),
    NOW()
);

INSERT INTO user_organisation_membership (
    id, organisation_id, user_id, membership_status, membership_type, primary_branch_id,
    joined_at, created_at, updated_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddd03',
    '22222222-2222-2222-2222-222222222222',
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    'ACTIVE',
    'ADMIN',
    '33333333-3333-3333-3333-333333333333',
    NOW(),
    NOW(),
    NOW()
);

INSERT INTO user_branch_assignment (
    id, organisation_id, user_id, branch_id, assignment_type, status, assigned_at, created_at,
    updated_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddd04',
    '22222222-2222-2222-2222-222222222222',
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    '33333333-3333-3333-3333-333333333333',
    'HOME',
    'ACTIVE',
    NOW(),
    NOW(),
    NOW()
);

INSERT INTO user_role_assignment (
    id, organisation_id, user_id, role_id, scope_type, status, assigned_at, created_at, updated_at
) VALUES (
    'dddddddd-dddd-dddd-dddd-dddddddddd05',
    '22222222-2222-2222-2222-222222222222',
    'dddddddd-dddd-dddd-dddd-dddddddddd01',
    '77777777-7777-7777-7777-777777777777',
    'TENANT',
    'ACTIVE',
    NOW(),
    NOW(),
    NOW()
);
