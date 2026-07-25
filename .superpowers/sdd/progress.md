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
  and `task-10-report.md` for full detail. Known, deliberately out-of-scope follow-ups: RoleController
  at 91% line coverage (below the module average but not gating), `InvalidTransitionException`/
  `TransitionGuardException` status-code mapping for other FSM transitions, and a full
  operation-by-operation OpenAPI/RFC 9457 completeness pass (spot-checked present, not exhaustively
  verified).
Task 11: pending
Task 12: pending
Task 13: pending

Minor review findings to revisit in final review: no `review-*.diff` artifact and no task brief/report
  exist for tasks 5-8 (process stopped generating them after `task-5-brief.md`), unlike tasks 1-4 which
  each have one. "review clean" for tasks 5-7 is asserted in this file, not evidenced by an artifact.
  Re-run a proper review pass over tasks 5-8 during final review even though tests are green.
