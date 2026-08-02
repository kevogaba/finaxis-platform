# ADR 0009: Tenant Settings Scope and Authentication Boundary

## Status

Accepted

Date: 2026-07-17

## Context

The tenant-settings and business-date foundation adds application-service operations for
setup-level organisation settings, a controlled business date, COB status changes, history,
audit, and outbox events.

The tempting failure mode is to treat every tenant-facing operational choice as a free-form
setting. That would blur boundaries already established in this repository: Keycloak
authenticates users, the application authorizes permission codes, `Organisation` is the tenant
boundary, and branch assignment is part of the platform's operating structure.

There is also a module-boundary concern. `iam` already depends on `lifecycle` for activation and
provisioning contracts. If `lifecycle` application services depended directly on `iam` to check
permissions, Spring Modulith verification would see a reverse edge and report a module cycle.

## Decision

Authentication, login policy, invite transport, and external SSO are owned by Keycloak. This
application remains an OAuth2 resource server only. It does not model credentials, lockout
policy, invite-provider toggles, realm federation, or external IdP configuration as tenant
settings.

Branch-assignment enforcement and the one-active-branch minimum are structural platform
invariants. They are not configurable tenant settings.

Authorization remains permission-code based. A user without the required permission receives a
403 from the authorization layer; there is no separate role/login-enforcement setting.

Tenant settings are catalog-validated, effective-dated, permission-gated, and redaction-aware.
Normal tenant settings require `settings.update` scoped to the tenant organisation.
Platform-only settings require `tenant_setting.manage_platform` scoped to the reserved platform
organisation, not to the tenant organisation.

Permission enforcement inside `lifecycle` uses dependency inversion. `lifecycle` defines the
`PermissionGuard` port, and `iam` implements it with `LifecyclePermissionGuardAdapter`. This lets
`TenantSettingsService` and `BusinessDateService` enforce permissions without adding a direct
`lifecycle -> iam` dependency.

Business date and COB remain application-service operations over the existing singleton
`business_date` row and append-only `business_date_history` table. COB status is not promoted
into the reusable FSM transition engine in this foundation slice.

## Consequences

The application will not grow tenant settings such as `keycloak_invite_enabled`,
`max_failed_login_policy_reference`, `application_invite_enabled`,
`enforce_branch_assignment_for_login`, `minimum_active_branches_required`,
`enforce_role_assignment_for_login`, or `outbox_max_retries`.

The settings catalog is intentionally small and explicit. Adding a new key requires code review,
validation rules, permission scope, redaction classification, and operational documentation.

**Amended 2026-08-02:** when this ADR was written there was no REST controller for tenant
settings or business date, and integration tests called the permission-gated services
directly. `TenantSettingsController` and `BusinessDateController` now exist and are documented
in [foundation-api.md](../api/foundation-api.md). The permission model below is unchanged; the
controllers delegate to the same services.

Those direct service tests must bind a mock Spring request context when they use the real
permission-check chain, because `AuthorizationService` reaches a `@RequestScope` bean while
resolving effective permissions.

Module boundaries stay acyclic: `lifecycle` owns the use cases and the permission-check port,
while `iam` owns the implementation backed by runtime authorization.

## Alternatives Considered

Free-form key/value tenant settings with no catalog validation:

- Rejected. It would make operational behavior discoverable only from data, not code, and would
  make validation, permissions, redaction, audit meaning, and documentation inconsistent.

Store external IdP or SSO configuration inside this application:

- Rejected. Keycloak owns authentication and identity-provider configuration. Duplicating that
  state in this resource server would create conflicting sources of truth.

Use a single coarse permission for all tenant settings:

- Rejected. `audit_retention_days` is platform-only, while normal settings are tenant-scoped.
  A single tenant-scoped permission would let tenant administrators mutate platform policy, and a
  single platform-scoped permission would be too broad for ordinary tenant configuration.

Let `lifecycle` call `iam` directly for permission checks:

- Rejected. It would create a reverse module dependency because `iam` already depends on
  `lifecycle`. The `PermissionGuard` port keeps the dependency direction explicit.
