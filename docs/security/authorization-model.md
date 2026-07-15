# Authorization model

Keycloak authenticates users. Finaxis authorizes them. Runtime checks evaluate permission
**codes**, never role names. Roles are administration bundles that grant permission codes to
memberships in an organisation and, optionally, a selected branch.

Read this with [active organisation context](active-organisation-context.md),
[login prechecks](login-prechecks.md), and
[ADR 0005](../adr/0005-organisation-lifecycle-no-hard-delete.md).

## Permission catalogue and roles

Flyway seeds a global permission catalogue in `permission`. Each permission has a stable
`permission_code`, `module_code`, `risk_level`, `system_permission`, and status. Current module
codes include `tenant`, `branch`, `iam`, `audit`, and `settings`; risk levels are `LOW`,
`MEDIUM`, `HIGH`, and `CRITICAL`.

Organisation approval creates organisation-local system roles from that catalogue:

- `TENANT_ADMIN`: all baseline permissions;
- `TENANT_AUDITOR`: `audit.view` and `business_date.view`;
- `IAM_ADMIN`: user, role, and audit administration permissions;
- `BRANCH_MANAGER`: branch lifecycle, branch assignment, and business-date view permissions;
- `BRANCH_OPERATOR`: `business_date.view`.

Flyway also creates the reserved platform organisation:

- id `00000000-0000-0000-0000-000000000000`;
- `tenant_code=PLATFORM`;
- system roles `PLATFORM_SUPER_ADMIN` and `PLATFORM_SUPPORT`.

`PLATFORM_SUPER_ADMIN` receives every baseline permission. `PLATFORM_SUPPORT` receives
`audit.view` and `business_date.view`. These are still modelled as roles in the reserved
organisation, not as special runtime role-name checks.

## Role administration

`RoleManagementService` exposes application commands to:

- create, update, activate, and deactivate tenant roles;
- assign and remove catalogue permissions on roles;
- assign and revoke roles for users at `TENANT` or `BRANCH` scope.

Implemented guards include:

- role code must be unique per organisation;
- system roles cannot be modified or deactivated;
- role lookup is always by selected organisation and role id;
- permission assignment requires a known catalogue permission code;
- role assignment requires an active organisation and a non-revoked membership;
- branch-scoped role assignment requires an active branch assignment for that branch;
- tenant-scoped role assignment must not carry a branch id.

Role and role-assignment mutations write audit events. Role permission changes and user-role
assignment changes evict affected effective-permission cache entries and publish administration
events for assignment/revocation.

## Effective permissions

Effective permissions are resolved for one active membership and optional selected branch:

1. If the membership is not `ACTIVE`, the result is empty.
2. Active tenant-scoped role grants apply for the membership.
3. Active branch-scoped role grants apply only when that exact branch is selected.
4. Direct membership permission effects are applied.
5. Direct `DENY` effects remove codes from the final allowed set.

The schema supports direct membership overrides in `membership_permission` with `ALLOW` and
`DENY`. The resolver returns permission codes only.

```mermaid
flowchart LR
    U[User account] --> M[Organisation membership]
    M --> B[Branch assignment]
    M --> RA[User role assignment]
    B --> RA
    RA --> R[Role]
    R --> RP[Role permission]
    RP --> P[Permission code]
    M --> MP[Direct allow or deny]
    MP --> P
```

## Caching and invalidation

`RequestPermissionCache` is request-scoped. It memoizes permission resolution only for the current
request and selected `(membershipId, branchId)`.

`EffectivePermissionResolver` also uses the application cache named `iam.effective-permissions`.
The cache key includes membership id and selected branch id, so branch switches do not reuse
another branch's permissions.

`PermissionCacheInvalidator` evicts cached selections when role grants or role assignments change.
It can also clear the entire effective-permission cache.

## Spring Security integration

After JWT authentication and active-organisation resolution, `AppPrincipalAuthenticationToken`
wraps the application principal. Its authorities are `SimpleGrantedAuthority` values built from
the principal's permission codes.

Controllers may use Spring Security authority checks such as:

```kotlin
@PreAuthorize("hasAuthority('iam.profile.read')")
```

Method security that needs organisation or branch matching uses the named authorizer:

```kotlin
@PreAuthorize("@authz.hasPermission(#organisationId, 'branch.create')")
@PreAuthorize("@authz.hasPermission(#organisationId, #branchId, 'branch.create')")
```

Application services can use `AuthorizationService` for explicit permission and resource checks.
Those checks still compare permission codes and reject cross-organisation access.
