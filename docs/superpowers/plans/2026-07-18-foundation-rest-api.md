# Foundation REST API Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose the existing foundation use cases through secure, tenant-safe, versioned REST APIs and complete tenant provisioning with a maker-checker-approved asynchronous initial-administrator bootstrap.

**Architecture:** Keep controllers as inbound adapters in the owning Spring Modulith module. Controllers map validated transport DTOs to application commands and queries; application services enforce permission, tenant, branch, lifecycle, and maker-checker rules; jOOQ adapters provide bounded reads and durable idempotency/bootstrap state. Reuse the existing FSM, audit, Namastack outbox, RabbitMQ, JobRunr, and Keycloak provisioning paths.

**Tech Stack:** Kotlin, Java 25, Spring Boot 4.1 Web MVC, Spring Security, Jakarta Bean Validation, springdoc OpenAPI, Spring Modulith, jOOQ/JDBC, PostgreSQL/Flyway, Bucket4j/Redis, Namastack Outbox, RabbitMQ, JobRunr, Keycloak, JUnit 5, MockMvc, Testcontainers, ArchUnit.

## Global Constraints

- Preserve the approved design in `docs/superpowers/specs/2026-07-18-foundation-rest-api-design.md` and the repository rules in `CLAUDE.md`.
- Preserve maker-checker: draft creator/requester cannot approve the same tenant or branch request; submission freezes the approved provisioning payload.
- Require mandatory initial-administrator details in every tenant draft request.
- Require an authenticated reserved-platform context for `/api/v1/platform/**`; the target tenant in the path never replaces the active platform context.
- Require active tenant context for every tenant endpoint and validate matching tenant plus branch context for branch-scoped operations.
- Check an explicit permission code inside the application layer for every endpoint. Controller annotations are an additional coarse guard, not the resource authorization boundary.
- Return tenant-mismatched resources as safe `404` responses and never disclose whether another tenant owns them.
- Return RFC 9457 `application/problem+json` errors with stable public codes, request correlation, and safe field violations. Never return SQL, class names, stack traces, Keycloak responses, internal job details, or raw exception messages.
- Serialize JSON keys as `snake_case`. Serialize dates as `dd-MM-yyyy`, times as `HH:mm:ss`, and datetimes as ISO 8601 with an explicit offset. Store dates and timestamps in their native database types.
- Every collection endpoint returns `items` plus `page`; no collection, including settings and permissions, may return an unpaginated array. Page numbers are zero-based, default size is 25, and maximum size is 100.
- Every `POST`, `PUT`, `PATCH`, and `DELETE` endpoint uses UUID `Idempotency-Key`. Accept a valid client key or generate one, return it on success and error, and replay a completed equivalent mutation. Never persist secrets or signed context tokens.
- Every request DTO uses Jakarta Bean Validation. Do not rely on `require`, database constraints, or Keycloak errors as the public validation layer.
- Every REST operation has OpenAPI summary, description, security, parameters, request schema, response schema, idempotency header for mutations, pagination metadata for lists, and RFC 9457 error responses.
- Do not add password handling. Keycloak remains authentication/external identity; application permission codes remain the runtime authorization primitive.
- Use `./gradlew qualityGate` as the final local authority. Run focused tests after each red/green step and commit each completed task independently.

The implementation must preserve these shared type contracts (package declarations and imports are
omitted here but are explicit in the task file lists):

```kotlin
data class ApiPage<T>(
    val items: List<T>,
    val page: ApiPageMetadata,
)

data class ApiPageMetadata(
    val number: Int,
    val size: Int,
    val totalItems: Long,
    val totalPages: Int,
    val hasNext: Boolean,
    val hasPrevious: Boolean,
)

sealed interface FoundationCaller {
    val actorId: UUID
}

data class TenantCaller(
    override val actorId: UUID,
    val activeOrganisationId: UUID,
    val activeBranchId: UUID?,
) : FoundationCaller

data class PlatformCaller(
    override val actorId: UUID,
    val platformOrganisationId: UUID,
) : FoundationCaller
```

```kotlin
interface PermissionGuard {
    fun requireTenantPermission(actorId: UUID, organisationId: UUID, permissionCode: String)
    fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    )
    fun requirePlatformPermission(actorId: UUID, permissionCode: String)
}

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.RUNTIME)
annotation class IdempotentMutation(
    val replayMode: ReplayMode = ReplayMode.STORED_RESPONSE,
)

enum class ReplayMode {
    STORED_RESPONSE,
    REISSUE_CONTEXT_TOKEN,
}
```

Application errors remain transport-neutral. The application layer throws safe coded errors from
`common.application`; only `common.web.api.ApiExceptionHandler` converts them to HTTP problems.

---

## Task 1: Establish the shared JSON, time, page, and RFC 9457 contract

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/ApiDateFormats.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/ApiPage.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/ApiProblem.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/ApiProblemFactory.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/ApiExceptionHandler.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/application/ApplicationErrors.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/WebJsonConfiguration.kt`
- Create: `src/main/java/com/finaxis/platform/common/web/api/package-info.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/ApiExceptionHandler.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/AuthController.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/api/WebJsonContractTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/api/ApiExceptionHandlerTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/WebAdapterUnitTests.kt`

- [ ] Write `WebJsonContractTests` with a real Spring MVC `ObjectMapper` asserting `tenant_code`, `18-07-2026`, `23:59:58`, and an offset datetime such as `2026-07-18T23:59:58+03:00`; assert camel-case input and ISO date input are rejected where the contract requires snake case and `dd-MM-yyyy`.
- [ ] Run `./gradlew test --tests '*WebJsonContractTests'` and confirm it fails because the shared mapper customizer does not exist.
- [ ] Add `spring.jackson.property-naming-strategy: SNAKE_CASE` and implement a Jackson 3 `JsonMapperBuilderCustomizer` in `WebJsonConfiguration` that registers strict serializers/deserializers using `tools.jackson.databind.module.SimpleModule`. Keep the existing Jackson 2 mapper used by JDBC/Namastack persistence unchanged.
- [ ] Define `ApiPage<T>(items: List<T>, page: ApiPageMetadata)` and `ApiPageMetadata(number, size, totalItems, totalPages, hasNext, hasPrevious)`. Add one conversion function that rejects page `< 0` or size outside `1..100`; every later list adapter must use it.
- [ ] Write `ApiExceptionHandlerTests` covering malformed JSON, validation errors with multiple field violations, invalid query parameter, missing tenant context, access denied, not found, conflict, unsupported media type, and unexpected exception. Assert `application/problem+json`, safe `type`, `title`, `status`, `detail`, `instance`, `code`, `request_id`, and optional `violations`; assert internal exception text is absent.
- [ ] Run `./gradlew test --tests '*ApiExceptionHandlerTests'` and confirm the current IAM-local response shape fails.
- [ ] Introduce transport-neutral `ResourceNotFoundException`, `ConflictException`, `ForbiddenOperationException`, and `InvalidOperationException` in `common.application.ApplicationErrors`, each carrying only a stable safe public code and safe detail. Map framework and application errors centrally in `ApiExceptionHandler` through `ApiProblemFactory`.
- [ ] Delete the IAM-local `ApiErrorResponse` and handler after migrating `AuthController` OpenAPI schemas to the shared `ApiProblem`; keep selection-denied wording safe and generic.
- [ ] Run `./gradlew test --tests '*WebJsonContractTests' --tests '*ApiExceptionHandlerTests' --tests '*WebAdapterUnitTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/common/application/ApplicationErrors.kt src/main/kotlin/com/finaxis/platform/common/web/api src/main/java/com/finaxis/platform/common/web/api src/main/resources/application.yaml src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web src/test/kotlin/com/finaxis/platform/common/web/api src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/WebAdapterUnitTests.kt && git commit -m "feat(api): establish shared REST contract"`.

## Task 2: Make security and rate-limit failures use the shared safe contract

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/ApiProblemWriter.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityConfiguration.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/web/ratelimit/RateLimitFilter.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/web/ratelimit/RateLimitProperties.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/web/ratelimit/RateLimitPathProperties.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/web/ratelimit/Bucket4jRateLimiterService.kt`
- Modify: `src/main/resources/application.yaml`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/api/ApiProblemWriterTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/common/web/ratelimit/RateLimitFilterTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/common/web/ratelimit/RateLimitPropertiesTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityAdapterTests.kt`

- [ ] Add failing tests asserting authentication entry-point, access-denied handler, missing/invalid active-context filter, throttled request, and unavailable rate limiter all return the same RFC 9457 shape and request ID as controller errors.
- [ ] Run `./gradlew test --tests '*ApiProblemWriterTests' --tests '*RateLimitFilterTests' --tests '*SecurityAdapterTests'` and confirm failures from `sendError` and handwritten JSON.
- [ ] Implement `ApiProblemWriter` with the application MVC mapper and inject it into security handlers and `RateLimitFilter`; remove all `sendError` and manually assembled problem JSON from these paths.
- [ ] Replace one-size rate limiting with named policies `auth-selection`, `platform-read`, `platform-command`, `tenant-read`, and `tenant-command`. Resolve policies from allowlisted path/method rules, include the policy ID in the Redis/Bucket4j key, and keep authenticated identity plus tenant scope in that key.
- [ ] Configure conservative defaults in `application.yaml`; preserve `RateLimit-*` and `Retry-After` headers; make all values overrideable without a redeploy.
- [ ] Run `./gradlew test --tests '*ApiProblemWriterTests' --tests '*RateLimitFilterTests' --tests '*RateLimitPropertiesTests' --tests '*SecurityAdapterTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/common/web/api/ApiProblemWriter.kt src/main/kotlin/com/finaxis/platform/common/web/ratelimit src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityConfiguration.kt src/main/resources/application.yaml src/test/kotlin/com/finaxis/platform/common/web/api/ApiProblemWriterTests.kt src/test/kotlin/com/finaxis/platform/common/web/ratelimit src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/security/SecurityAdapterTests.kt && git commit -m "feat(api): unify security and rate limit errors"`.

## Task 3: Add durable mutation idempotency

**Files:**

- Create: `src/main/resources/db/migration/V9__foundation_api_idempotency.sql`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyModels.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyStore.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/JooqIdempotencyStore.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyExecutor.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyProperties.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyCleanupJob.kt`
- Create: `src/main/java/com/finaxis/platform/common/web/idempotency/package-info.java`
- Modify: `src/main/resources/application.yaml`
- Modify: `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/idempotency/JooqIdempotencyStoreTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyExecutorTests.kt`

- [ ] Extend the migration test first to require `api_idempotency_record` with UUID `scope_organisation_id`, UUID `idempotency_key`, actor fingerprint, method, normalized path, request hash, status (`IN_PROGRESS`, `COMPLETED`), safe response status/headers/body, created/expiry timestamps, and unique `(scope_organisation_id, idempotency_key)`.
- [ ] Run `./gradlew test --tests '*FoundationSchemaMigrationTests'` and confirm V9 is absent.
- [ ] Add V9 and regenerate jOOQ with `./gradlew jooqCodegen`; do not store Authorization, Cookie, session identifiers, signed context tokens, raw exception text, or arbitrary response headers.
- [ ] Write `JooqIdempotencyStoreTests` for insert, atomic winner selection, concurrent same-key acquisition, completed replay, request-hash mismatch, actor mismatch, rollback, and expiry. Use a PostgreSQL transaction-scoped advisory lock derived from organisation plus UUID before reading/inserting.
- [ ] Write `IdempotencyExecutorTests` asserting same request executes once and replays the exact safe 2xx status/body/allowlisted headers; same key with different actor/method/path/body yields `409 IDEMPOTENCY_KEY_REUSED`; failed business transaction leaves no completed record; stale in-progress work can be reacquired after configured timeout.
- [ ] Implement `IdempotencyExecutor.execute(scope, key, fingerprint, operation)` so business mutation and completion record commit in one transaction. Allow only `Location`, `ETag`, and explicit non-secret domain headers in stored responses.
- [ ] Add a JobRunr cleanup job deleting expired completed records in bounded batches; expose retention, in-progress timeout, cleanup schedule, and batch size under `finaxis.api.idempotency`.
- [ ] Run `./gradlew test --tests '*FoundationSchemaMigrationTests' --tests '*JooqIdempotencyStoreTests' --tests '*IdempotencyExecutorTests'` and confirm all pass.
- [ ] Commit with `git add src/main/resources/db/migration/V9__foundation_api_idempotency.sql src/main/kotlin/com/finaxis/platform/common/web/idempotency src/main/java/com/finaxis/platform/common/web/idempotency src/main/resources/application.yaml src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt src/test/kotlin/com/finaxis/platform/common/web/idempotency && git commit -m "feat(api): add durable mutation idempotency"`.

## Task 4: Enforce idempotency on every mutation, including context selection

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotentMutation.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyKeyFilter.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyMutationAspect.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/MutationScopeResolver.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/CanonicalRequestHasher.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyReplayResponse.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/application/selection/AuthSelectionService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/AuthController.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/idempotency/IdempotencyWebIntegrationTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/AuthFlowIntegrationTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/common/web/versioning/ApiVersioningArchitectureTest.kt`

- [ ] Add failing integration tests for a client UUID key, generated key, malformed key (`400 INVALID_IDEMPOTENCY_KEY`), successful replay, changed payload conflict, concurrent duplicate, and a validation failure that still returns the generated key but creates no completed record.
- [ ] Add failing architecture assertions that every `@PostMapping`, `@PutMapping`, `@PatchMapping`, and `@DeleteMapping` in a REST controller carries `@IdempotentMutation`.
- [ ] Run `./gradlew test --tests '*IdempotencyWebIntegrationTests' --tests '*ApiVersioningArchitectureTest'` and confirm failures.
- [ ] Implement a highest-precedence `IdempotencyKeyFilter` that parses/generates a UUID, stores it as request metadata, and always returns `Idempotency-Key`. Implement canonical hashing over actor identity, method, normalized path, sorted query parameters, and canonical JSON body.
- [ ] Implement `MutationScopeResolver`: tenant mutations use the active tenant ID; every platform mutation uses the reserved active platform organisation ID while method/path/body keep target tenants distinct in the fingerprint; organisation selection uses the requested organisation ID because it establishes context; branch selection uses the active organisation ID.
- [ ] Implement the aspect and `IdempotencyReplayResponse` so normal APIs may persist safe response DTOs. Mark both existing selection methods with `@IdempotentMutation(replayMode = REISSUE_CONTEXT_TOKEN)`.
- [ ] Refactor `AuthSelectionService` so the durable replay value contains only `ActiveOrganisationContext`, branch-selection flags, and assigned branch IDs. On original and replayed calls, store that context in the current session and call `ActiveOrganisationContextService.issue(context)` to produce a fresh response token. Never pass `contextToken` to `IdempotencyExecutor`.
- [ ] Add tests proving replay updates the current session, returns a newly issued valid token, and the idempotency table body does not contain the token or secret material.
- [ ] Run `./gradlew test --tests '*IdempotencyWebIntegrationTests' --tests '*AuthFlowIntegrationTests' --tests '*ApiVersioningArchitectureTest'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/common/web/idempotency src/main/kotlin/com/finaxis/platform/iam/application/selection/AuthSelectionService.kt src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/AuthController.kt src/test/kotlin/com/finaxis/platform/common/web/idempotency src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/AuthFlowIntegrationTests.kt src/test/kotlin/com/finaxis/platform/common/web/versioning/ApiVersioningArchitectureTest.kt && git commit -m "feat(api): enforce idempotency for mutations"`.

## Task 5: Add explicit API permissions and caller/context authorization

**Files:**

- Create: `src/main/resources/db/migration/V10__foundation_api_permissions.sql`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/PermissionGuard.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/FoundationCaller.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapter.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/iam/application/authorization/AuthorizationService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationBranchProvisioningStore.kt`
- Create: `src/main/java/com/finaxis/platform/iam/package-info.java`
- Modify: `src/main/java/com/finaxis/platform/lifecycle/package-info.java`
- Modify: `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapterTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/application/authorization/AuthorizationServiceTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/architecture/ModulithArchitectureTest.kt`

- [ ] Add migration assertions for these exact permission codes: `auth.select_organisation`, `auth.select_branch`, `tenant.view`, `tenant.update_draft`, `tenant.reject`, `tenant.reactivate`, `tenant.bootstrap_retry`, `branch.view`, `branch.reactivate`, `user.view`, `membership.view`, `membership.suspend`, `membership.reactivate`, `membership.revoke`, `branch_assignment.view`, `user.revoke_branch`, `role.view`, `role.activate`, `role.deactivate`, `role.remove_permission`, `role_assignment.view`, `user.revoke_role`, `permission.view`, and `settings.view`.
- [ ] Create V10 with deterministic UUID rows and role grants. Grant both `auth.select_*` codes to active operational tenant roles. Remove `user.suspend`, `user.activate`, and `user.deactivate` from tenant bootstrap roles; grant global user lifecycle permissions only to the reserved platform super-administrator role. Keep existing command permission codes where already defined.
- [ ] Introduce `FoundationCaller` as `TenantCaller(actorId, activeOrganisationId, activeBranchId)` and `PlatformCaller(actorId, platformOrganisationId)`. Do not accept a target organisation inside caller context.
- [ ] Extend `PermissionGuard` with `requireTenantPermission(actorId, organisationId, permissionCode)`, `requireBranchPermission(actorId, organisationId, branchId, permissionCode)`, and `requirePlatformPermission(actorId, permissionCode)`. The platform implementation must verify active context is `PlatformOrganisation.ID`; the branch implementation must verify organisation ownership, active assignment where required, matching active branch, and effective permission.
- [ ] Add tests for valid tenant/platform/branch calls, missing tenant context, wrong active tenant, wrong branch, inactive membership, absent permission, and cross-tenant target. Assert target mismatches become safe not-found at the application boundary. Update `AuthSelectionService` to require `auth.select_organisation` against the target active membership and `auth.select_branch` against the selected assigned branch; keep `profile.read` on `/auth/me`.
- [ ] Add explicit IAM module metadata and export only the named application/authorization interfaces needed by lifecycle. Update lifecycle allowed dependencies to include `common::web-api` and `common::web-idempotency`, never IAM persistence or Keycloak adapters.
- [ ] Run `./gradlew test --tests '*FoundationSchemaMigrationTests' --tests '*LifecyclePermissionGuardAdapterTests' --tests '*AuthorizationServiceTests' --tests '*ModulithArchitectureTest'` and confirm all pass.
- [ ] Commit with `git add src/main/resources/db/migration/V10__foundation_api_permissions.sql src/main/kotlin/com/finaxis/platform/lifecycle/PermissionGuard.kt src/main/kotlin/com/finaxis/platform/lifecycle/application/FoundationCaller.kt src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationBranchProvisioningStore.kt src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapter.kt src/main/kotlin/com/finaxis/platform/iam/application/authorization/AuthorizationService.kt src/main/kotlin/com/finaxis/platform/iam/application/selection/AuthSelectionService.kt src/main/java/com/finaxis/platform/iam/package-info.java src/main/java/com/finaxis/platform/lifecycle/package-info.java src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt src/test/kotlin/com/finaxis/platform/iam src/test/kotlin/com/finaxis/platform/architecture/ModulithArchitectureTest.kt && git commit -m "feat(security): authorize foundation API use cases"`.

## Task 6: Add bounded lifecycle, IAM, settings, and audit read models

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/query/FoundationReadModels.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/query/FoundationQueryPorts.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/query/FoundationQueryService.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqFoundationQueryStore.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/query/IamReadModels.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/query/IamAdministrationQueries.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/application/query/IamQueryService.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/persistence/JooqIamAdministrationQueries.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/audit/AuditQueryService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/audit/AuditEventQuery.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/common/audit/adapter/outbound/persistence/JooqAuditEventQueries.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/application/query/FoundationQueryServiceTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqFoundationQueryStoreTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/application/query/IamQueryServiceTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/adapter/outbound/persistence/JooqIamAdministrationQueriesTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsServiceTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/common/audit/AuditQueryServiceTests.kt`

- [ ] Define explicit detail and summary projections for tenant, branch, user-in-tenant, membership, branch assignment, role, role permission, role assignment, permission, setting, business-date history, and audit detail. Detail projections contain bounded scalar/nested summaries only; related collections get separate page queries.
- [ ] Define allowlisted filters and sorts: tenant (`q`, status, country, created range), branch (`q`, status, type), user (`q`, user status, membership status), membership (status/type), assignments (branch/type/status or role/scope/status), roles (`q`, status, system role), permissions (`q`, risk level), settings (`q`, category, active), and audit (actor, entity type/id, action, occurred range).
- [ ] Write service tests first for permission selection, tenant predicates, branch-context validation, page bounds, sort allowlists, and safe not-found behavior.
- [ ] Write jOOQ integration tests proving SQL predicates include organisation ID, counts match filters, ordering is deterministic with an ID tie-breaker, search is case-insensitive, and a cross-tenant ID returns no record.
- [ ] Implement `FoundationQueryService` and `IamQueryService` with `FoundationCaller`/authorization input and shared page validation. Never return jOOQ records or persistence objects.
- [ ] Change `TenantSettingsService.list` from `List<TenantSettingView>` to a bounded `TenantSettingPage`; use `settings.view` for reads and retain `settings.update`/`tenant_setting.manage_platform` for mutations.
- [ ] Add tenant-scoped `AuditQueryService.get(eventId, organisationId, actorId)` and permission checks using `audit.view`; make every audit list use the shared maximum and stable ordering.
- [ ] Run `./gradlew test --tests '*FoundationQueryServiceTests' --tests '*JooqFoundationQueryStoreTests' --tests '*IamQueryServiceTests' --tests '*JooqIamAdministrationQueriesTests' --tests '*TenantSettingsServiceTests' --tests '*AuditQueryServiceTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/lifecycle/application/query src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqFoundationQueryStore.kt src/main/kotlin/com/finaxis/platform/iam/application/query src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/persistence/JooqIamAdministrationQueries.kt src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsService.kt src/main/kotlin/com/finaxis/platform/common/audit src/test/kotlin/com/finaxis/platform/lifecycle src/test/kotlin/com/finaxis/platform/iam src/test/kotlin/com/finaxis/platform/common/audit/AuditQueryServiceTests.kt && git commit -m "feat(foundation): add bounded administration queries"`.

## Task 7: Persist mandatory initial-administrator draft data and maker-checker state

**Files:**

- Create: `src/main/resources/db/migration/V11__initial_administrator_bootstrap.sql`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningCommands.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapModels.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapPorts.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationProvisioningService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningPorts.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationBranchProvisioningStore.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqInitialAdministratorBootstrapStore.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningServiceTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqInitialAdministratorBootstrapStoreTests.kt`

- [ ] Extend draft-command tests first to require `InitialAdministratorDraft(email, username, displayName, phoneE164, sendApplicationInvite)` and `requestedBy`; reject blank/invalid email, username, display name, malformed E.164 phone, and missing maker identity before persistence. Add an explicit `AmendOrganisationDraftCommand` carrying the same validated mutable tenant/admin fields, actor ID, and request ID.
- [ ] Extend submit/approve commands with `actorId` and `requestId`; add a failing test proving the requester cannot approve their own tenant and a second actor can.
- [ ] Add V11 `organisation_initial_administrator_bootstrap` with one row per organisation, frozen admin fields, requester/submitted/approved actor IDs, status, attempts, user/membership/head-office/role references, last safe failure code, timestamps, and row version. Use statuses `DRAFT`, `PENDING_ACTIVATION`, `QUEUED`, `PROVISIONING_IDENTITY`, `COMPLETED`, and `FAILED`.
- [ ] Implement draft create/amend while organisation is `DRAFT`; submission atomically validates required tenant/admin fields and freezes them; approval checks maker != checker and records approver before activation. Rejection returns the payload to an amendable draft state without deleting history.
- [ ] Keep existing local setup inside `approveProvisioning`: settings, business date, active head office, reference sequences, and default roles must commit before organisation activation publishes its existing event. Do not synchronously call Keycloak.
- [ ] Add query projection fields for public bootstrap status, attempt count, user/membership references when present, and safe failure code; never return internal exception or Keycloak payload.
- [ ] Run `./gradlew test --tests '*FoundationSchemaMigrationTests' --tests '*OrganisationBranchProvisioningServiceTests' --tests '*JooqInitialAdministratorBootstrapStoreTests'` and confirm all pass.
- [ ] Commit with `git add src/main/resources/db/migration/V11__initial_administrator_bootstrap.sql src/main/kotlin/com/finaxis/platform/lifecycle/application src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt src/test/kotlin/com/finaxis/platform/lifecycle && git commit -m "feat(tenant): persist initial administrator bootstrap"`.

## Task 8: Complete initial-administrator bootstrap through outbox, RabbitMQ, JobRunr, and Keycloak

**Files:**

- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/messaging/OrganisationActivatedAmqpConfiguration.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/messaging/InitialAdministratorBootstrapListener.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapJobRequest.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapJobRequestHandler.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/UserProvisioningCommands.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/UserProvisioningService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/KeycloakUserProvisioningJobRequest.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/KeycloakUserProvisioningJobRequestHandler.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/messaging/IdentityProvisioningListener.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapServiceTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/messaging/InitialAdministratorBootstrapListenerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/application/InitialAdministratorBootstrapJobRequestHandlerTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/application/KeycloakUserProvisioningHandlerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/InitialAdministratorBootstrapIntegrationTests.kt`

- [ ] Add failing listener tests proving only `finaxis.lifecycle.organisation.activated` schedules a deterministic bootstrap job on a dedicated queue and duplicate deliveries schedule the same logical work.
- [ ] Add failing service tests proving it resolves the active head office and seeded `TENANT_ADMIN`, creates or resumes one local user/membership, assigns the branch and role once, forces `sendKeycloakInvite = true`, preserves the optional application-invite preference, and calls existing `inviteUser` then `approveUser` rather than duplicating provisioning logic. Pass the frozen tenant requester as `invitedBy` and the checker as `approvedBy`, so the automated job preserves maker-checker actors instead of using one system actor for both steps.
- [ ] Implement event metadata correlation by adding optional `bootstrapRequestId` and `bootstrapAttempt` to invite/approve commands, provisioning events, `IdentityProvisioningListener`, and `KeycloakUserProvisioningJobRequest`.
- [ ] Implement status progression `QUEUED -> PROVISIONING_IDENTITY -> COMPLETED` and safe `FAILED` transitions. After Keycloak create/find, local link, required-actions email, user invite transition, and membership activation succeed, mark the correlated bootstrap complete.
- [ ] In `KeycloakUserProvisioningJobRequestHandler`, if the dispatch log already says `SUCCEEDED`, still call the idempotent bootstrap-completion operation before returning. This closes the crash window between dispatch success and bootstrap completion.
- [ ] Implement retry as an application command guarded by `tenant.bootstrap_retry`. Before identity dispatch, resume from persisted references; after dispatch, republish/schedule the existing provisioning target. Never create a second membership, branch assignment, role assignment, or Keycloak user, and never schedule JobRunr directly from a controller.
- [ ] Write integration tests for activation event -> outbox routing -> bootstrap job scheduling -> local seed references -> Keycloak job correlation, plus duplicate activation, listener redelivery, crash-after-dispatch, failed retry, successful retry, and no activation event before checker approval.
- [ ] Run `./gradlew test --tests '*InitialAdministratorBootstrap*' --tests '*KeycloakUserProvisioningHandlerTests' --tests '*OrganisationActivationOutboxIntegrationTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/lifecycle src/test/kotlin/com/finaxis/platform/lifecycle && git commit -m "feat(tenant): bootstrap initial administrator asynchronously"`.

## Task 9: Expose platform tenant and tenant/branch lifecycle adapters

**Files:**

- Modify: `src/main/kotlin/com/finaxis/platform/common/web/versioning/ApiPaths.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/dto/TenantApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/dto/BranchApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/PlatformTenantController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/PlatformTenantBranchController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/TenantController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/BranchController.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/PlatformTenantControllerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/BranchControllerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/FoundationLifecycleWebIntegrationTests.kt`

- [ ] Define path constants for `/api/v1/platform`, `/api/v1/platform/tenants`, `/api/v1/tenant`, and `/api/v1/branches`; do not concatenate controller-local version strings.
- [ ] Write MockMvc tests first for every approved tenant route: create/list/detail/amend/submit/approve/reject/suspend/reactivate/deprovision/bootstrap retry, current tenant detail, and platform nested branch list/detail/create. Cover happy path, missing/invalid idempotency key behavior, validation, unauthenticated, forbidden, maker-checker rejection, tenant mismatch, and pagination.
- [ ] Write tenant branch tests for list/create/detail/submit/activate/suspend/reactivate/close. Require matching branch context for detail and lifecycle operations; list/create remain tenant-wide and still require `branch.view`/`branch.create`. Extend branch submit/activate commands with actor and request IDs, persist the maker identity, and prove the branch maker cannot activate the branch while a distinct checker can.
- [ ] Create dedicated request DTOs with explicit bounds and patterns: tenant code, ISO country/currency, IANA timezone, admin email/username/display name/E.164 phone, required reasons, branch code/name/type/address, date filters in `dd-MM-yyyy`, and page/filter/sort parameters.
- [ ] Map DTOs to commands and query facades only. Return `201 + Location` for draft creation, `202` for approved bootstrap queued, current resource state for lifecycle commands, `ApiPage` for lists, and no embedded unbounded relations.
- [ ] Add `@PreAuthorize` plus application-layer permission checks for every route using the exact codes from Task 5. Annotate every mutation with `@IdempotentMutation`.
- [ ] Add complete springdoc annotations including RFC 9457 schemas and `Idempotency-Key`; ensure DTO properties render snake case and dates show `dd-MM-yyyy` examples.
- [ ] Run `./gradlew test --tests '*PlatformTenantControllerTests' --tests '*BranchControllerTests' --tests '*FoundationLifecycleWebIntegrationTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/common/web/versioning/ApiPaths.kt src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web && git commit -m "feat(api): expose tenant and branch lifecycle"`.

## Task 10: Expose user, membership, branch-assignment, role, and permission adapters

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/dto/MembershipApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/dto/BranchAssignmentApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/MembershipController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/BranchAssignmentController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/dto/UserApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/dto/RoleApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/PlatformUserController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/TenantUserController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/RoleController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/RoleAssignmentController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/inbound/web/PermissionController.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/UserProvisioningService.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/FoundationIamControllerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/MembershipControllerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/FoundationIamWebIntegrationTests.kt`

- [ ] Add missing membership `suspendMembership` and `reactivateMembership` application commands using `FoundationLifecycleService`, audit, actor/request IDs, lifecycle guards, and permission checks. Persist/read the invitation maker and reject `approveUser` when `approvedBy == invitedBy`; initial bootstrap supplies the frozen tenant requester and checker from Task 8. Keep global user suspend/reactivate/deactivate platform-only.
- [ ] Write controller tests first for tenant users list/invite/detail, memberships list/detail/activate/suspend/reactivate/revoke, branch assignments list/assign/delete, role assignments list/assign/delete, roles list/create/detail/update/activate/deactivate, role permissions list/grant/delete, immutable permissions list/detail, platform nested tenant users list/detail, and platform global user lifecycle.
- [ ] Cover valid calls, Bean Validation failures, all list page bounds, `401`, permission `403`, cross-tenant safe `404`, wrong branch context, revoked membership, immutable system-role mutation, and idempotent replay for every mutation method.
- [ ] Use explicit request DTO validation for email, username, E.164 phone, membership type, branch/role assignments, scope type, permission code, role code/name, status/reason, and UUID path parameters. Never accept password fields.
- [ ] Return `202` from membership activation when Keycloak provisioning is queued and `200` when local activation is complete. `GET /api/v1/auth/me` remains the canonical current-user profile; update only its shared schema/error/idempotency documentation as applicable.
- [ ] Resolve delete paths by assignment ID through tenant-scoped query data, then map to the existing revocation commands. A caller cannot supply a role/branch tuple that bypasses assignment ownership.
- [ ] Apply the permission matrix: reads use `user.view`, `membership.view`, `branch_assignment.view`, `role.view`, `role_assignment.view`, or `permission.view`; mutations use the existing command code or new exact revoke/lifecycle code from Task 5.
- [ ] Run `./gradlew test --tests '*FoundationIamControllerTests' --tests '*MembershipControllerTests' --tests '*FoundationIamWebIntegrationTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/lifecycle src/main/kotlin/com/finaxis/platform/iam src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web && git commit -m "feat(api): expose foundation identity administration"`.

## Task 11: Expose settings, business date, and audit adapters

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/dto/TenantSettingApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/dto/BusinessDateApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/TenantSettingsController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/BusinessDateController.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/audit/adapter/inbound/web/AuditEventApiDtos.kt`
- Create: `src/main/kotlin/com/finaxis/platform/common/audit/adapter/inbound/web/AuditEventController.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/TenantSettingsControllerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web/BusinessDateControllerTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/audit/adapter/inbound/web/AuditEventControllerTests.kt`

- [ ] Write tests for paginated settings list/detail, create-or-update/deactivate, current business date, paginated history, advance, COB start/complete, reopen, paginated audit search, and audit detail.
- [ ] Assert settings and audit never return an unpaginated array; secret settings stay redacted; platform-only definitions require `tenant_setting.manage_platform`; all tenant reads use active tenant predicates.
- [ ] Validate setting key/value/effective dates, business date `dd-MM-yyyy`, required reasons, optimistic version where exposed, audit date-time ranges as ISO 8601 offsets, action/entity filters, sort allowlists, and page bounds.
- [ ] Apply `settings.view`, `settings.update`, `tenant_setting.manage_platform`, `business_date.view`, `business_date.advance`, `cob.start`, `cob.complete`, `business_date.reopen`, and `audit.view` inside application services. Annotate all mutation handlers for idempotency.
- [ ] Document and return business dates as `dd-MM-yyyy`; audit and effective timestamps remain offset ISO 8601. Return safe conflict problems for stale business-date/settings versions.
- [ ] Run `./gradlew test --tests '*TenantSettingsControllerTests' --tests '*BusinessDateControllerTests' --tests '*AuditEventControllerTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web src/main/kotlin/com/finaxis/platform/common/audit/adapter/inbound/web src/test/kotlin/com/finaxis/platform/lifecycle/adapter/inbound/web src/test/kotlin/com/finaxis/platform/common/audit/adapter/inbound/web && git commit -m "feat(api): expose settings business date and audit"`.

## Task 12: Enforce API/OpenAPI/architecture rules without hardcoded controller lists

**Files:**

- Create: `src/main/kotlin/com/finaxis/platform/common/web/api/FoundationOpenApiConfiguration.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/common/web/versioning/ApiVersioningArchitectureTest.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/common/web/pagination/PaginationArchitectureTest.kt`
- Create: `src/test/kotlin/com/finaxis/platform/common/web/api/FoundationOpenApiContractTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/architecture/HexagonalArchitectureTest.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/architecture/ModulithArchitectureTest.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/PlatformApplicationTests.kt`

- [ ] Replace hardcoded controller sets in versioning/pagination tests with classpath discovery of every `@RestController` under `com.finaxis.platform`. Fail for a route outside `/api/v1`, any collection returning `List`/array/iterable directly, or a list handler without page/size parameters and an `ApiPage` response.
- [ ] Add architecture rules that controllers depend only on application/common web types, contain no jOOQ/JDBC/repository/outbox/Rabbit/JobRunr/Keycloak dependency, and expose no persistence records. Verify lifecycle and IAM Spring Modulith boundaries.
- [ ] Add OpenAPI contract tests that fetch `/v3/api-docs`, enumerate every handler mapping, and assert operation ID, tag, summary, bearer security, success response, shared RFC 9457 responses, request/response schemas, query parameters, pagination schema for lists, and `Idempotency-Key` for all mutations.
- [ ] Configure shared OpenAPI components for bearer auth, `ApiProblem`, validation violations, page metadata, request ID, active-organisation context, idempotency key, rate-limit headers, and date/time examples. Do not publish internal admin/job/persistence models.
- [ ] Add application-context verification that all controllers, permission guard implementations, idempotency components, query adapters, event listeners, and JobRunr handlers wire successfully.
- [ ] Run `./gradlew test --tests '*ApiVersioningArchitectureTest' --tests '*PaginationArchitectureTest' --tests '*FoundationOpenApiContractTests' --tests '*HexagonalArchitectureTest' --tests '*ModulithArchitectureTest' --tests '*PlatformApplicationTests'` and confirm all pass.
- [ ] Commit with `git add src/main/kotlin/com/finaxis/platform/common/web/api/FoundationOpenApiConfiguration.kt src/test/kotlin/com/finaxis/platform/common/web src/test/kotlin/com/finaxis/platform/architecture src/test/kotlin/com/finaxis/platform/PlatformApplicationTests.kt && git commit -m "test(api): enforce foundation REST architecture"`.

## Task 13: Publish the API guide and perform final verification

**Files:**

- Create: `docs/api/foundation-api.md`
- Modify: `docs/development/common-project-context.md`
- Modify: `CLAUDE.md`
- Modify: `README.md`
- Modify: `scripts/local-smoke.sh`

- [ ] Write `docs/api/foundation-api.md` with: base/version paths; platform versus tenant context; active branch rules; complete endpoint tables; exact permission mapping; request/response examples; `snake_case`; `dd-MM-yyyy`; `HH:mm:ss`; ISO 8601 offset datetime; page/filter/search/sort rules; RFC 9457 examples; idempotency generation/replay/conflict semantics; rate-limit headers; maker-checker flow; async bootstrap states/retry; Keycloak invitation behavior; and safe error disclosure rules.
- [ ] Add an explicit architecture-rule summary to `docs/development/common-project-context.md` and `CLAUDE.md`: module-owned thin adapters, bounded queries, every endpoint permission checked, every collection paginated, every mutation idempotent, tenant/branch context enforcement, JSON/date/time conventions, and OpenAPI coverage.
- [ ] Update `README.md` with foundation API documentation and OpenAPI/Scalar links. Extend `scripts/local-smoke.sh` to authenticate, select platform context, send/generated UUID idempotency keys, create a tenant draft with mandatory initial administrator, verify maker cannot approve, approve as checker, poll bootstrap status, and call representative paginated tenant endpoints. Do not put credentials in source.
- [ ] Run formatting: `./gradlew spotlessApply`.
- [ ] Run the complete test suite and static/architecture gates: `./gradlew qualityGate`.
- [ ] If local Keycloak/RabbitMQ/Redis/PostgreSQL services are available, run `./scripts/local-smoke.sh`; record separately whether this live smoke ran or was blocked. Do not substitute an earlier or pre-format run for final-tree evidence.
- [ ] Scan the final diff for unfinished markers and accidental sensitive output: `rg -n "TODO|FIXME|NotImplementedError|UnsupportedOperationException|printStackTrace|password" src/main src/test docs/api/foundation-api.md` and classify any legitimate existing matches.
- [ ] Inspect final scope and history: `git status --short && git diff --check && git log --oneline --decorate -15`.
- [ ] Commit documentation and smoke coverage with `git add docs/api/foundation-api.md docs/development/common-project-context.md CLAUDE.md README.md scripts/local-smoke.sh && git commit -m "docs(api): publish foundation REST contract"`.
- [ ] Re-run `./gradlew qualityGate` on the committed final tree. Report exact passing commands, any live-smoke boundary, migrations added, and the final commit range; do not claim external Keycloak/email delivery unless the live smoke proves it.

---

## Final acceptance checklist

- [ ] Tenant draft rejects missing initial-administrator details and maker cannot approve their own request.
- [ ] Approved tenant activation produces durable asynchronous initial-user, Keycloak invite, head-office assignment, and tenant-admin role setup through the existing event pipeline.
- [ ] Platform routes require reserved-platform context; tenant routes require tenant context; branch routes validate branch context.
- [ ] Every REST operation has an explicit application-layer permission check and forbidden-path test; selection specifically requires `auth.select_organisation` or `auth.select_branch`.
- [ ] Every collection is paginated and every query is bounded and tenant-filtered in SQL.
- [ ] Every mutation method has optional/generated UUID idempotency and safe replay; no token or secret is stored.
- [ ] Validation uses Jakarta Bean Validation and has failing-input tests.
- [ ] Errors are safe RFC 9457 responses across MVC, security, context, and rate-limit paths.
- [ ] JSON is snake case; date/time/datetime formats match the approved contract.
- [ ] OpenAPI documents every endpoint, parameter, mutation header, success response, and error response.
- [ ] Controllers remain thin, Spring Modulith/hexagonal tests pass, and no adapter bypasses application services.
- [ ] `docs/api/foundation-api.md` matches the implemented routes and permission matrix.
- [ ] `./gradlew qualityGate` passes on the final committed tree.
