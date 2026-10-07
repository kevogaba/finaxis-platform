# Permission view-gap report

[ADR 0030](../adr/0030-mutation-permission-implies-view-permission.md) requires that a role (and
so an effective permission set) holding a **mutation** permission also holds every **view**
permission the catalogue pairs with it, so the caller can see what they wrote. New role
compositions are checked by the API
([role composition](../security/authorization-model.md#role-composition)), and there is **no
backfill**: roles and overrides that predate the rule stay as they are. This
report finds them before the mutation-time check that refuses them ships, so no principal is first
discovered by a `403` in production.

Two read-only queries cover what the role APIs cannot:

| File | Answers |
| --- | --- |
| [`sql/permission-view-gap-roles.sql`](sql/permission-view-gap-roles.sql) | Every role of every organisation (the `PLATFORM` organisation's roles included, which no API lists) that holds an `ACTIVE` mutation without one of its views. |
| [`sql/permission-view-gap-memberships.sql`](sql/permission-view-gap-memberships.sql) | Every membership whose **effective** permission set holds a mutation without one of its views. |

Both read the catalogue's own `permission_view_requirement` rows, so they carry no copy of the
mapping and follow a permission migration automatically. `PermissionViewGapReportTests` runs the
SQL **text of these files** against crafted data (a custom role with a mutation and no view, a
membership with a direct `ALLOW` of a mutation without its view, a direct `DENY` of a needed view,
a branch-scope role, a platform role, a compliant seeded administrator), so what is documented is
what is tested.

## Running it

Run it as a read-only database role against the application database:

```shell
psql "$DATABASE_URL" --csv -f docs/operations/sql/permission-view-gap-roles.sql
psql "$DATABASE_URL" --csv -f docs/operations/sql/permission-view-gap-memberships.sql
```

An empty result is the goal: nothing to repair. Neither query writes anything, so they are safe on
a production replica or primary.

## Roles report

One row per `(role, mutation held, missing view)`:

| Column | Meaning |
| --- | --- |
| `organisation_code` | The organisation's `tenant_code`; `PLATFORM` for the platform roles. |
| `role_id`, `role_code`, `role_status`, `system_role` | The role. A `DISABLED` role is listed so it is repaired before it is re-enabled. |
| `mutation_code` | An `ACTIVE` mutation the role holds. A grant of a deprecated or disabled mutation is not listed: it grants nothing. |
| `missing_view_code` | A view that mutation requires and the role does not hold **as an `ACTIVE` grant**. A view that is itself not `ACTIVE` grants nothing at runtime, so it counts as missing. |

For tenant roles this is the same information as `missing_view_permissions` on the role APIs,
across every organisation at once. Repair a tenant role through the API
(`POST /api/v1/tenant/roles/{role_id}/permissions` with the view), one code at a time. A platform
role has no API: grant the view with a reviewed forward migration or an audited SQL change, and
flush the effective-permission cache (`iam.effective-permissions:*`, or restart) if it is not done
by a migration.

## Memberships report

A membership's **effective** permission set is what the runtime resolver computes: the grants of
its `ACTIVE` roles through `ACTIVE` assignments, **union** its direct `ALLOW` overrides,
**minus** its direct `DENY` overrides, counting only `ACTIVE` permissions. A membership whose every
role complies can still violate the rule through an override (a direct `ALLOW` of a mutation with
no view, or a direct `DENY` of a view a held mutation needs), which no role report can see. There
is deliberately **no API field** for this; this query is the report.

One row per `(membership, context, mutation held, missing view)`:

| Column | Meaning |
| --- | --- |
| `organisation_code` | The organisation's `tenant_code`. |
| `membership_id`, `user_id` | The membership and its user. No name or email is selected; join `user_account` on `user_id` when an operator needs them. |
| `membership_status` | Every membership that is not `REVOKED` is listed. The resolver gives a `PENDING_APPROVAL` or `SUSPENDED` membership nothing, but the gap is the one it meets on activation. Filter on `ACTIVE` for the principals refused today. |
| `branch_id` | The **context**. `NULL` is the tenant-wide set (`TENANT`-scope roles and the overrides). A branch id is that branch's set: the tenant-wide set plus the membership's `BRANCH`-scope roles at that branch, because those apply only while the branch is selected. A tenant-wide gap is repeated for a branch context that does not close it, and a gap a branch-scope role closes does not appear for that branch. |
| `mutation_code`, `missing_view_code` | As in the roles report. |

To repair a membership gap, add the missing view to one of its roles (or grant it on a new role),
or, for an override, remove the offending direct `ALLOW` or `DENY` row. Nothing writes
`membership_permission` today, so overrides come only from SQL; apply the composition rule when
writing one (ADR 0030, "Open items"). SQL run outside Flyway needs the effective-permission cache
flushed (restart, or delete `iam.effective-permissions:*`).

## Keeping it current

The query follows the catalogue, not this document. A new permission migration inserts its `kind`,
`grant_scope` and `permission_view_requirement` rows (ADR 0030, decision 8), and both queries
report against them from the first run. Change a query only together with the rule it states
(`EffectivePermissionResolver`, `RoleComposition`), and keep `PermissionViewGapReportTests` green.
A migration or hand SQL that makes a mutation `ACTIVE` (for example re-activating
`branch.activate`) can leave a role holding it without its views, because granting it while it was
not `ACTIVE` was never checked: run both reports after any such change.
