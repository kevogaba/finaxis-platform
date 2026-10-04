-- Permission view-gap report, ROLES section (ADR 0030, decision 3).
--
-- One row for every ACTIVE mutation permission a role holds whose required view permission the
-- role does not hold as an ACTIVE grant: the roles that would meet a named 403 once the mutation
-- check ships, and that the role APIs report as `missing_view_permissions`. It reads the
-- catalogue's own `permission_view_requirement` rows, so it needs no copy of the mapping.
--
-- It covers EVERY role of EVERY organisation, the PLATFORM organisation's roles included, which
-- no API lists. Read-only. See docs/operations/permission-view-gap-report.md.
--
-- Columns: organisation_code, role_id, role_code, role_status, system_role, mutation_code and
-- missing_view_code. A DISABLED role is listed too (so it is repaired before it is re-enabled),
-- and a grant of a DEPRECATED or DISABLED mutation is not (it grants nothing at runtime). A view
-- that is itself not ACTIVE grants nothing at runtime, so a role holding only that counts as
-- missing it.

SELECT o.tenant_code AS organisation_code,
       r.id AS role_id,
       r.role_code,
       r.status AS role_status,
       r.system_role,
       mutation.permission_code AS mutation_code,
       needed_view.permission_code AS missing_view_code
FROM role r
JOIN organisation o ON o.id = r.organisation_id
JOIN role_permission granted
    ON granted.organisation_id = r.organisation_id
   AND granted.role_id = r.id
JOIN permission mutation
    ON mutation.id = granted.permission_id
   AND mutation.status = 'ACTIVE'
JOIN permission_view_requirement requirement
    ON requirement.permission_id = mutation.id
JOIN permission needed_view
    ON needed_view.id = requirement.required_view_permission_id
WHERE NOT EXISTS (
    SELECT 1
    FROM role_permission held
    JOIN permission held_permission
        ON held_permission.id = held.permission_id
       AND held_permission.status = 'ACTIVE'
    WHERE held.organisation_id = r.organisation_id
      AND held.role_id = r.id
      AND held.permission_id = needed_view.id
)
ORDER BY o.tenant_code,
         r.role_code,
         mutation.permission_code,
         needed_view.permission_code;
