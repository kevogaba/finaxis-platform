-- Permission view-gap report, MEMBERSHIPS section (ADR 0030, decision 3).
--
-- One row for every ACTIVE mutation permission a membership EFFECTIVELY holds whose required view
-- permission it does not effectively hold. A membership's effective set is what the runtime
-- resolver computes (EffectivePermissionResolver): the grants of its ACTIVE roles through ACTIVE
-- assignments, UNION its direct ALLOW overrides, MINUS its direct DENY overrides, counting only
-- ACTIVE permissions. A role-clean membership can still violate the rule through an override: a
-- direct ALLOW of a mutation with no view, or a direct DENY of a view a held mutation needs.
--
-- Read-only. See docs/operations/permission-view-gap-report.md.
--
-- CONTEXTS. A BRANCH-scope role applies only while that branch is selected, so the effective set
-- is computed per context: the tenant-wide one (branch_id IS NULL: TENANT-scope roles and the
-- overrides) and, for every branch the membership holds an ACTIVE BRANCH-scope role at, that
-- branch's (the tenant-wide set plus that branch's roles). A tenant-wide gap is repeated for a
-- branch context that does not close it.
--
-- STATUS. Every membership that is not REVOKED is reported, with its status: the resolver gives a
-- PENDING_APPROVAL or SUSPENDED membership nothing until it becomes ACTIVE, but the gap is the
-- one it would meet on activation. Filter on membership_status = 'ACTIVE' for the principals that
-- would be refused today.
--
-- Columns: organisation_code, membership_id, user_id, membership_status, branch_id (NULL for the
-- tenant-wide context), mutation_code and missing_view_code. No names or email addresses are
-- selected; join user_account on user_id when an operator needs them.

WITH member AS (
    SELECT id AS membership_id, organisation_id, user_id, membership_status
    FROM user_organisation_membership
    WHERE membership_status <> 'REVOKED'
),
context AS (
    SELECT membership_id, organisation_id, user_id, membership_status, NULL::uuid AS branch_id
    FROM member
    UNION
    SELECT member.membership_id, member.organisation_id, member.user_id,
           member.membership_status, assignment.branch_id
    FROM member
    JOIN user_role_assignment assignment
        ON assignment.organisation_id = member.organisation_id
       AND assignment.user_id = member.user_id
       AND assignment.status = 'ACTIVE'
       AND assignment.scope_type = 'BRANCH'
),
role_grant AS (
    SELECT context.membership_id, context.branch_id, granted.permission_id
    FROM context
    JOIN user_role_assignment assignment
        ON assignment.organisation_id = context.organisation_id
       AND assignment.user_id = context.user_id
       AND assignment.status = 'ACTIVE'
       AND (assignment.scope_type = 'TENANT'
            OR (assignment.scope_type = 'BRANCH' AND assignment.branch_id = context.branch_id))
    JOIN role
        ON role.organisation_id = assignment.organisation_id
       AND role.id = assignment.role_id
       AND role.status = 'ACTIVE'
    JOIN role_permission granted
        ON granted.organisation_id = role.organisation_id
       AND granted.role_id = role.id
),
direct_allow AS (
    SELECT context.membership_id, context.branch_id, override.permission_id
    FROM context
    JOIN membership_permission override
        ON override.organisation_id = context.organisation_id
       AND override.membership_id = context.membership_id
       AND override.effect = 'ALLOW'
),
effective AS (
    SELECT candidate.membership_id, candidate.branch_id, candidate.permission_id
    FROM (
        SELECT membership_id, branch_id, permission_id FROM role_grant
        UNION
        SELECT membership_id, branch_id, permission_id FROM direct_allow
    ) candidate
    JOIN permission
        ON permission.id = candidate.permission_id
       AND permission.status = 'ACTIVE'
    WHERE NOT EXISTS (
        SELECT 1
        FROM membership_permission denial
        WHERE denial.membership_id = candidate.membership_id
          AND denial.permission_id = candidate.permission_id
          AND denial.effect = 'DENY'
    )
)
SELECT o.tenant_code AS organisation_code,
       context.membership_id,
       context.user_id,
       context.membership_status,
       context.branch_id,
       mutation.permission_code AS mutation_code,
       needed_view.permission_code AS missing_view_code
FROM effective
JOIN context
    ON context.membership_id = effective.membership_id
   AND context.branch_id IS NOT DISTINCT FROM effective.branch_id
JOIN organisation o ON o.id = context.organisation_id
JOIN permission mutation ON mutation.id = effective.permission_id
JOIN permission_view_requirement requirement ON requirement.permission_id = mutation.id
JOIN permission needed_view ON needed_view.id = requirement.required_view_permission_id
WHERE NOT EXISTS (
    SELECT 1
    FROM effective held
    WHERE held.membership_id = effective.membership_id
      AND held.branch_id IS NOT DISTINCT FROM effective.branch_id
      AND held.permission_id = requirement.required_view_permission_id
)
ORDER BY o.tenant_code,
         context.membership_id,
         context.branch_id NULLS FIRST,
         mutation.permission_code,
         needed_view.permission_code;
