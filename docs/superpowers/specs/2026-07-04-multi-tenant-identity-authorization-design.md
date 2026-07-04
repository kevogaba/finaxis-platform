# Multi-Tenant Identity And Authorization Design

## Context

Finaxis Platform is a Kotlin Spring Boot 4.1 Web MVC application on Java 25. The project already includes OAuth2 Resource Server, Spring Security, Spring Data JDBC, Flyway, Redis, jOOQ, and Spring Boot AOT dependencies, but has no existing domain model or security configuration.

Keycloak remains the authentication system for credentials, MFA, sessions, and token issuance. The application is the source of truth for users, organisations, memberships, roles, permissions, scopes, and authorization decisions.

## Architecture

The implementation uses Kotlin only. The project is already Kotlin-first, and mixing Java would add cognitive overhead without improving runtime behavior or framework compatibility.

IAM code lives under `com.finaxis.platform.iam` using hexagonal package boundaries: framework-independent domain model, application services and outbound ports, inbound web/security adapters, and outbound persistence adapters. Database schema is managed by Flyway. Persistence uses Spring Data JDBC repositories because that is already present in the project and keeps the first implementation simpler than generated jOOQ code.

Runtime authorization is permission-based. Roles are stored only as permission bundles and admin convenience. Controllers use `@PreAuthorize("hasAuthority('permission.code')")`; services use `AuthorizationService` for resource-specific checks. Business authorization never depends on role names.

## Active Organisation

The active organisation is request-context based, not stored on `app_user`. `POST /auth/select-organisation` verifies that the authenticated Keycloak subject maps to an app user with an ACTIVE membership in the requested organisation. It stores browser context in Redis-backed Spring Session and returns a signed app-managed context token for headless clients. The context carries `userId`, `organisationId`, `membershipId`, and optional `branchId`.

If exactly one branch is assigned to the active membership, it is auto-selected. If multiple branches are assigned, clients call `POST /auth/select-branch`; the application verifies the branch assignment and updates the same context.

Subsequent browser requests can rely on the session cookie. Headless clients pass the signed token in `X-Active-Organisation-Context`. Header context takes precedence over session context and invalid headers fail closed. The security adapter validates the Keycloak JWT, resolves the application context, resolves the active membership, and builds `AppPrincipal`.

## Permission Resolution

Effective permissions are resolved per membership:

1. If the membership is not ACTIVE, return an empty set.
2. Add active permissions from assigned active roles.
3. Add direct active `ALLOW` membership permissions.
4. Remove direct active `DENY` membership permissions.

DENY takes precedence over ALLOW. Effective permissions are cached by `membership_id` through Spring Cache. Services that mutate memberships, roles, role permissions, direct membership permissions, or permission status evict affected cache entries.

## OpenFGA Readiness

`AuthorizationService` exposes stable methods:

- `hasPermission(principal, permissionCode)`
- `requirePermission(principal, permissionCode)`
- `can(principal, permissionCode, resourceRef)`
- `require(principal, permissionCode, resourceRef)`

Controllers depend only on permission authorities and services depend on `AuthorizationService`. A later OpenFGA/ReBAC integration can be implemented behind `AuthorizationService` without changing controller annotations.

## Auditing

Audit references use nullable `created_by_user_id`, `created_by_membership_id`, `updated_by_user_id`, and `updated_by_membership_id` fields where appropriate. `AuditActorType` models USER, SYSTEM, SERVICE_ACCOUNT, INTEGRATION, MIGRATION, and SCHEDULER. A richer actor persistence model is intentionally deferred.

## Testing

Tests cover effective permission resolution, tenant-specific permissions for the same user, DENY precedence, suspended memberships, organisation selection failures, method security rejection, cross-organisation resource checks, and cache invalidation. Coverage is enforced for the IAM implementation with JaCoCo.
