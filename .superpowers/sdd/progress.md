# Foundation REST API SDD Progress

Branch start: `61d8f0a`
Plan: `docs/superpowers/plans/2026-07-18-foundation-rest-api.md`
Baseline: `./gradlew test` passed before implementation.

Task 1: complete (commits 61d8f0a..ba71b26, review clean, full test green)
Task 2: complete (commits ba71b26..7461017, review clean, qualityGate green)
Task 3: complete (commits 7461017..0d07040, review clean, qualityGate green)
Task 4: complete (commits up to `036f296`, review clean, qualityGate green)
Task 5: complete (commits up to `33402b1`, review clean, qualityGate green)
Task 6: complete (commit `88f6c0b`, detekt + qualityGate green)
Task 7: complete (commits 9b0d0ec, 42838f4 finalize bootstrap persistence + coverage, all tests green)
Task 8: complete (commit cb3e6d5, `InitialAdministratorBootstrap*` + `KeycloakUserProvisioningHandlerTests` green;
  re-verified 2026-07-23)
Task 9: complete (commit `0d59617`, amended twice: once to fold in production/test files the
  original commit omitted, once to fix a 500-instead-of-403 branch maker!=checker guard). Both
  follow-ups from the initial task-9-report closed in `6ca5c22` and `f948eef`:
  - `6ca5c22`: mapped all ~40 remaining `require()`/`IllegalArgumentException`/`IllegalStateException`
    sites across `BranchProvisioningService` and `OrganisationProvisioningService` to the correct
    safe `ApplicationException` subtype (including the tenant-approval maker!=checker guard, which
    had the same 500-instead-of-403 bug as the branch one). Added a controller-level test proving
    a tenant maker cannot approve their own draft while a distinct checker can.
  - `f948eef`: `./gradlew qualityGate` was hitting `OutOfMemoryError` on a full run; root-caused to
    Gradle's forked-worker default heap (512m, confirmed via `-XX:+PrintFlagsFinal`) being far too
    small for the Spring Boot + Testcontainers suite — NOT total system memory, which was already
    confirmed adequate. Raised test JVM heap to 2g in `build.gradle.kts`. Also fixed one genuine
    regression the full suite caught that targeted runs missed: `JooqFoundationLifecyclePersistenceTests`
    (a pre-existing test not in the original fix's scope list) asserted the old `IllegalArgumentException`
    for a cross-organisation branch assignment; updated to expect `ConflictException`.
  - `./gradlew qualityGate` now genuinely green: 607 tests, 0 failures, 0 errors, in ~5 minutes
    (down from a 1h40m OOM crash before the heap fix).
  See `.superpowers/sdd/task-9-brief.md` and `task-9-report.md` for full detail.
  Re-verified 2026-07-25 after a machine crash/reboot interrupted work partway into Task 10 (see
  below): confirmed no Task 10 commit touches any Task 9 file, and the full suite (653 tests) is
  green on the current tree, so Task 9 remains genuinely complete.
Task 10: complete. Three commits landed before a machine crash (`1deae51` Phase A application/query
  prerequisites, `5070b9b` Phase B1 membership/branch-assignment controllers, `f67d6d7` Phase B2
  role/permission/tenant-user/platform-user controllers, itself amended 3 times), but `f67d6d7` was
  never actually compiled or run through the quality gate before the crash. Resuming found the tree
  in a broken state, not merely "unknown". Fixed in `9df79f6`:
  - `FoundationIamControllerTests.kt` did not compile at all (Kotlin MockMvc DSL mixed with Java
    `ResultActions.perform(...)`; `apiPageOf(...)` called with missing required args).
  - `PlatformUserLifecycleController` had no class-level `@RequestMapping`, failing
    `ApiVersioningArchitectureTest`.
  - `roleMutationPayload(method)` sent the wrong DTO body for the permission-assign and
    role-assignment routes regardless of which endpoint was under test, producing spurious 400s
    where 403/409 was expected (2 test failures).
  - `gradle/libs.versions.toml` had bundled an unrelated `kotlinVersion` bump (2.4.0 -> 2.4.10)
    into the feature commit; detekt has no release compatible with 2.4.10 (2.0.0-alpha.5 is
    latest), so `detekt` failed outright. Reverted the version pin only; left the other dependency
    bumps in that commit alone.
  - One genuine detekt `UnusedParameter` finding (`detailRequests(tenantId, ...)`) fixed.
  - `spotlessApply` run project-wide then mechanically rewrapped several never-linted Task 10 files
    to the 100-char limit and reordered imports; no logic changed (verified via `git diff -w` plus
    full green test runs before and after).
  After `9df79f6`, the full suite and every static-analysis task were green, but
  `jacocoTestCoverageVerification` (iam module, 95% line minimum) still failed at 92%. Closed via
  Codex-delegated test-writing (all independently re-verified, not trusted from pasted summaries):
  - New `IamLifecycleReadAdapterTests.kt`: the Phase B1 dependency-inversion adapter had zero
    coverage (5%, 56/59 lines uncovered) — the largest single contributor to the shortfall.
  - `PlatformUserControllerTests.kt`/`TenantUserControllerTests.kt`: added the missing 401/403
    coverage the brief required (previously zero unauthenticated/forbidden assertions in either
    file).
  - Investigating the "revoked membership blocks further action" test the brief also required
    surfaced a genuine production bug: `suspendMembership`/`reactivateMembership`/membership
    `activate` on a membership in a state that disallows the transition leaked a raw
    `TransitionNotAllowedException` (an unmapped `RuntimeException`) to `ApiExceptionHandler`'s
    generic 500 fallback instead of a safe 409 — the same class of defect Task 9 fixed for
    provisioning services, but in the shared `FoundationLifecycleService.executeTransition`
    chokepoint used by every domain's FSM transitions. Fixed by converting
    `TransitionNotAllowedException` to `ConflictException` at that one chokepoint (scoped narrowly;
    `InvalidTransitionException`/`TransitionGuardException` left untouched as a documented,
    out-of-scope follow-up), with the one pre-existing unit test that asserted the old raw-exception
    behavior (`FoundationLifecycleServiceTests.kt`) updated to match. Added the membership-conflict
    and branch-assignment wrong-branch-context (safe 404) tests once the underlying behavior was
    confirmed safe.
  - New `FoundationIamWebIntegrationTests.kt`: a real `@SpringBootTest`/Testcontainers integration
    test (mirroring `FoundationLifecycleWebIntegrationTests`'s role for Task 9) chaining a full
    tenant-IAM flow — create/list a role, grant a catalogue permission, invite a user with a role
    assignment, assign a role via its dedicated route, a distinct checker approving the membership,
    and a platform-nested read — all through real DB-backed `AuthorizationService` resolution, not
    mocked permission guards. No further bug found; confirms the DB-seeded `TENANT_ADMIN`/
    `PLATFORM_SUPER_ADMIN` roles already carry every new Task 10 permission code.
  Final state: `./gradlew qualityGate` genuinely green — 664 tests, 0 failures, 0 errors; iam-module
  line coverage ~96.5% (was 92%); all static analysis green. See `.superpowers/sdd/task-10-brief.md`
  and `task-10-report.md` for full detail. Two of the three documented follow-ups (`RoleController`
  coverage, `TransitionGuardException` status mapping) closed in commits `5b72f65`/`ad3d3e7` before
  starting Task 11; the third (full OpenAPI/RFC 9457 operation-by-operation audit) is explicitly
  Task 12's job, not deferred maintenance.
Task 11: complete (commits `b70a591` exception-mapping groundwork, `ea6c6fa` controllers). The
  entire application layer (`TenantSettingsService`, `BusinessDateService`, `AuditQueryService`)
  already existed, unlike Tasks 9/10 — this was purely a web-adapter task, except for a real gap
  found during reconnaissance: those services used bare `require()`/`check()` for business guards,
  including a genuine optimistic-concurrency conflict in business-date advancement
  ("business date was concurrently changed; retry with the latest version") that would have leaked
  through `ApiExceptionHandler`'s generic 500 fallback exactly like the gaps already fixed in Tasks
  9/10. Mapped per a frozen table (see `task-11-brief.md`) before writing any controller, so the new
  controllers could trust safe exception types from day one. `TenantSettingsController` deliberately
  carries no per-route `@PreAuthorize` (the required permission is data-dependent on the setting
  key; the service's own conditional `authorize()` is the sole enforcement point) — a documented,
  deliberate exception to this codebase's usual coarse-gate pattern.
  A genuine Spring Modulith cycle was caught before commit (not after): the plan's specified
  location for `AuditEventController` (`common/audit/adapter/inbound/web`) required importing
  `lifecycle`'s `CallerContextResolver`, creating a `common -> lifecycle` dependency where
  `lifecycle -> common` already legitimately exists. Fixed by moving the web adapter (not the
  application service) into `lifecycle/adapter/inbound/web`, mirroring how
  `MembershipController`/`BranchAssignmentController` already live there despite depending on `iam`
  via dependency inversion. `./gradlew qualityGate` green — 703 tests, 0 failures, 0 errors. See
  `.superpowers/sdd/task-11-brief.md` and `task-11-report.md` for full detail.
Task 12: complete (commits `c55b759` architecture-test rewrites, `b35aa2d` OpenAPI configuration and
  contract verification, `6526d3e` manual-verification bug fixes). Phase A closed the real enforcement
  gap: `PaginationArchitectureTest`
  hardcoded exactly 2 controller names, leaving 15 of 17 real controllers with zero pagination
  enforcement — rewritten to classpath-scan like `ApiVersioningArchitectureTest` already did (shared
  helper extracted to `RestControllerScan.kt`), plus a positive check that `ApiPage`-returning GETs
  declare `page`/`size` params, and dropped an incorrect `Optional`-as-collection check.
  `HexagonalArchitectureTest` gained an outbox/RabbitMQ/JobRunr/Keycloak exclusion rule and a
  positive allow-list rule for web adapters. `iam/package-info.java` gained an explicit
  `allowedDependencies` list (previously declared with none, so Modulith's per-dependency
  allow-listing — confirmed via Context7 to be opt-in per module — simply wasn't active for `iam`).
  Building that list from `iam`'s imports surfaced a real methodology gap, not a production bug:
  `TenantUserController` reads `.name` off `UserLifecycleState`/`MembershipLifecycleState` values
  returned by an already-allowed `lifecycle.application` result type, with no source-level import of
  either enum ever appearing (Kotlin doesn't require one for property-type-inferred access) — a
  grep-based dependency list cannot see this; only Modulith's own bytecode-level verification
  (confirmed independently via `javap -c -p`) reveals it. Added `lifecycle::domain` (the exact,
  intentional relationship that named interface's own package-info documents) rather than treating
  it as a violation to fix in production code.
  Phase B relocated the OpenAPI bean into a new `FoundationOpenApiConfiguration.kt` with named,
  reusable `ApiProblem`/`ApiViolation`/`ApiPage`/`ApiPageMetadata` schemas and shared header
  parameters, added `FoundationOpenApiContractTests.kt` (a real test hitting the actual generated
  `/v3/api-docs` document, cross-referenced against Spring's real handler-mapping table so every
  route is proven present, not just that whatever's there looks right), and extended
  `PlatformApplicationTests.kt` with explicit bean-wiring assertions across controllers, permission
  guards, idempotency components, query adapters, `@RabbitListener`s, and JobRunr handlers. Writing
  the contract test surfaced a real, broader-than-planned documentation defect: 15 of 17
  controllers declared `@SecurityRequirement(name = "bearerAuth")`, a name never actually registered
  as a security scheme (the real one has always been `"bearer-key"`) — never a functional
  authorization gap (Spring Security doesn't read this annotation), but the generated docs showed no
  effective bearer-auth requirement on nearly every endpoint. Fixed across all 15, verified against
  the real generated document.
  Manual verification (starting the app for real against Postgres/Redis/RabbitMQ/Keycloak, per
  explicit request — the automated contract test alone only exercises `/v3/api-docs`, never the
  Scalar UI route) found two more real bugs the contract test structurally could not catch: the
  rate-limit exclusion list and the Spring Security `permitAll` allowlist both listed `/docs/**` as
  the public docs-UI path, but that path was never real — the Scalar starter's actual default UI
  path is `/scalar` (confirmed via bytecode inspection of the `scalar-webmvc` dependency:
  `@ConfigurationProperties(prefix = "scalar")`, no path override configured). `/docs/**` protected
  nothing at either layer. Fixing one layer at a time revealed the other: `GET /scalar` first
  returned `429` (no rate-limit rule or exclusion matched the real path), then after that fix
  returned `401` (the security allowlist had the identical stale entry), then finally `200` with
  real Scalar UI HTML once both were corrected to `/scalar/**`. Also fixed a matching stale claim in
  `README.md`. Regression tests added at both layers.
  `qualityGate` genuinely green — 712 tests, 0 failures. See `.superpowers/sdd/task-12-brief.md` and
  `task-12-report.md` for full detail.
Task 13: pending

Minor review findings to revisit in final review: no `review-*.diff` artifact and no task brief/report
  exist for tasks 5-8 (process stopped generating them after `task-5-brief.md`), unlike tasks 1-4 which
  each have one. "review clean" for tasks 5-7 is asserted in this file, not evidenced by an artifact.
  Re-run a proper review pass over tasks 5-8 during final review even though tests are green.
