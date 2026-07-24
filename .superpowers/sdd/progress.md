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
Task 10: pending
Task 11: pending
Task 12: pending
Task 13: pending

Minor review findings to revisit in final review: no `review-*.diff` artifact and no task brief/report
  exist for tasks 5-8 (process stopped generating them after `task-5-brief.md`), unlike tasks 1-4 which
  each have one. "review clean" for tasks 5-7 is asserted in this file, not evidenced by an artifact.
  Re-run a proper review pass over tasks 5-8 during final review even though tests are green.
