# JDBC Auditing and Context

Spring Data JDBC auditing is enabled by `JdbcAuditingConfiguration`. Mutable persistence records
use explicit `created_at`, `created_by`, `updated_at`, `updated_by`, and `row_version` fields;
`@CreatedDate`, `@CreatedBy`, `@LastModifiedDate`, `@LastModifiedBy`, and `@Version` map them.

This PR does not yet implement organisation/branch/user creation flows, so nothing currently
inserts through `FoundationJdbcEntities`; `JdbcAuditingIntegrationTests` verifies the auditing
config directly against `JdbcAggregateTemplate` so the mechanism is proven ahead of that work. All
lifecycle **updates** in this PR go through jOOQ (`JooqFoundationLifecyclePersistence` and related
adapters), which populates `updated_at`/`updated_by` manually via `RequestContexts`/`SystemActor`
and increments `row_version` itself; it does not use the `@LastModifiedBy`-style annotations.
Future creation flows must either insert through a Spring Data JDBC repository over these entities
(so `@CreatedBy`/`@CreatedDate` apply automatically), or, if they insert through jOOQ instead,
populate `created_at`/`created_by` explicitly the same way the jOOQ adapters already populate
`updated_at`/`updated_by`.

`ContextAuditorAware` obtains the actor from `RequestContexts`. If no user exists, it uses the
stable `SystemActor.ID` (`00000000-0000-0000-0000-000000000001`). Background jobs and outbox
workers must install a system or service actor explicitly before persistence, preserving a clear
audit trail without pretending a human initiated the work.

`RequestContexts` carries tenant/organisation, branch, actor, and correlation data and mirrors it
into MDC keys: `organisationId`, `organisationCode`, `branchId`, `actorId`, `actorSubject`,
`requestId`, and `correlationId`. Servlet filters must install the snapshot after authenticated
principal resolution and clear it in `finally`.

Tenant-scoped repositories accept `organisationId` with every lookup. They must never expose an
`findById(id)` path for organisation-owned records; callers use `(organisationId, id)` and the
database enforces same-organisation relationships with composite foreign keys.

The resource-server adapter extracts the Keycloak subject and supported claims from the authenticated
JWT, then delegates to the local-principal lookup port. That port is intentionally separate from
authorization: a Keycloak subject alone is never sufficient to authorize a request. The resolved
local user, active organisation membership, and selected branch scope establish `ActorContext`,
`TenantContext`, and `BranchContext`; a protected organisation or branch route without its required
resolved context is rejected as unauthenticated. Suspended users and memberships are rejected even
when the upstream Keycloak token is valid.

jOOQ is the persistence-query adapter convention. Generated PostgreSQL metadata is available only
to outbound adapters; domain and application layers depend on tenant-safe ports and explicit
`organisationId` parameters, never jOOQ tables or unscoped SQL helpers.
