# Self-Access Guard (#341) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** No API call lets a caller suspend or revoke their own membership, or suspend or deactivate their own account.

**Architecture:** One small `SelfAccessGuard` in `lifecycle.application` (the `MembershipCheckerRule` pattern): after the permission check (and, for a membership, the lookup), if the subject user is the actor it records a `DENIED`/`HIGH` audit row through `AuditService.recordIndependently` and throws a `ForbiddenOperationException` with a specific code. `DeniedAccessAuditor` skips these codes as it skips `lifecycle.approver_is_*`.

**Tech Stack:** Kotlin, Spring Boot Web MVC, jOOQ, JUnit 5 + MockMvc, Testcontainers PostgreSQL.

**Spec:** `docs/adr/0043-self-access-change-guard.md` (written in Task 1; the approved short design).

## Global Constraints

- Branch `ccr-df4cfd28-t6zhqt-self-guard`, created from `origin/main`; the PR carries exactly one conventional commit over `main`.
- `./gradlew qualityGate` passes locally (Docker daemon up) before the push.
- Codes and details, verbatim: `lifecycle.self_membership_change` / "A member cannot suspend or revoke their own membership."; `lifecycle.self_account_change` / "A user cannot suspend or deactivate their own account." Both 403.
- Audit actions are the FSM's own (ADR 0031): `membership.suspend`, `membership.revoke`, `user_account.suspend`, `user_account.start_deactivation` (the first of a deactivation's two transitions); outcome `DENIED`, severity `HIGH`, `reason` = the code, `subjectUserId` = the subject.
- Order per route: permission check → lookup (memberships only) → self check → write. An unknown membership stays 404; a caller without the permission stays 403 `forbidden`.
- Still allowed (documented, not refused): reactivate, membership `PATCH`, revoking one's own role or branch assignment, self-assignment (#342).
- KDoc on public production code; max line length 100. Paths relative to `src/main/kotlin/com/finaxis/platform/` (tests: `src/test/kotlin/com/finaxis/platform/`).

## Review Focus

1. A permitted caller naming an unknown membership id still gets 404, not the self code — pinned in Task 1's integration test.
2. A caller lacking `membership.suspend` who targets their own membership gets 403 `forbidden` (permission first), not the self code — pinned in Task 1's service test.
3. The refusal writes exactly one audit row, not an extra `api.access_denied` — pinned in Task 1's integration test and `DeniedAccessAuditorTests`.
4. Acting on another member or account is unchanged (200) — the control case in both integration tests.
5. A revoke of one's own membership cascades nothing (no branch or role assignment is revoked) — pinned in Task 1's service test.

---

### Task 1: The guard, its decision record, and the membership routes

**Files:**
- Create: `docs/adr/0043-self-access-change-guard.md`
- Create: `lifecycle/application/SelfAccessGuard.kt`
- Modify: `lifecycle/application/LifecycleErrorCodes.kt` (four constants)
- Modify: `lifecycle/application/UserProvisioningService.kt` (`suspendMembership` ~401, `revokeTenantMembership` ~349; construct the guard like `membershipCheckerRule` at ~50)
- Modify: `common/web/api/DeniedAccessAuditor.kt:32,66,148` (prefix set)
- Modify: `lifecycle/adapter/inbound/web/MembershipController.kt` (403 `@ApiResponse` text on suspend and revoke)
- Modify: `docs/api/foundation-api.md` (Memberships, line ~1589), `docs/security/authorization-model.md` (new "Self-access guard (#341)" section after "Maker-checker and the platform checker", line ~862)
- Test: `lifecycle/application/SelfAccessGuardTests.kt` (create), `lifecycle/application/UserProvisioningServiceTests.kt`, `common/web/api/DeniedAccessAuditorTests.kt`
- Create test: `lifecycle/adapter/inbound/web/SelfAccessGuardIntegrationTests.kt`

**Interfaces:**
- Produces: `LifecycleErrorCodes.SELF_MEMBERSHIP_CHANGE`, `SELF_MEMBERSHIP_CHANGE_DETAIL`, `SELF_ACCOUNT_CHANGE`, `SELF_ACCOUNT_CHANGE_DETAIL` (values in Global Constraints).
- Produces: `internal data class SelfAccessAttempt(actorId: UUID, organisationId: UUID, action: String, requestId: String?)` and `internal class SelfAccessGuard(private val auditService: AuditService)` with
  - `fun requireNotOwnMembership(attempt: SelfAccessAttempt, membershipId: UUID, memberUserId: UUID)` — resource `MEMBERSHIP`/`membershipId`, `tenantId = attempt.organisationId`;
  - `fun requireNotOwnAccount(attempt: SelfAccessAttempt, userId: UUID)` — resource `USER_ACCOUNT`/`userId`, `tenantId = attempt.organisationId` (the platform organisation the route runs in).
  Each returns normally when the actor is not the subject; otherwise records and throws. (Ruled during execution: Detekt's `LongParameterList` allows five function parameters, so the shared arguments travel as one value.)
- Produces: `DeniedAccessAuditor` `OWN_ROW_PREFIXES = listOf("lifecycle.approver_is_", "lifecycle.self_")`.

- [ ] **Step 1: Write the ADR** `0043-self-access-change-guard.md` in the format of ADR 0042 (Status: Accepted, implemented with #341; Date 2026-11-16; Context: #341 and the frontend's BG-35; Decision: the four refused actions, the two codes as 403, the ordering rule, the audit row and the dedupe; what stays allowed and why (assignment revokes are recoverable by another administrator and the UI warns; self-assignment is #342); Alternatives: 409 instead of 403, refusing every self change, a last-administrator guard (out of scope); Consequences).
- [ ] **Step 2: Write the failing unit tests**
  - `SelfAccessGuardTests` (mock `AuditService`): `acting on someone else records nothing`; `acting on your own membership records one denied high row and throws` (assert the thrown `ForbiddenOperationException.code == SELF_MEMBERSHIP_CHANGE`, the captured `AuditCommand` has `action = "membership.suspend"`, `outcome = DENIED`, `severity = HIGH`, `reason = SELF_MEMBERSHIP_CHANGE`, `resourceType = "MEMBERSHIP"`, `subjectUserId = actor`); same for `requireNotOwnAccount` with `USER_ACCOUNT` and `SELF_ACCOUNT_CHANGE`.
  - `UserProvisioningServiceTests`: `suspending your own membership is refused before any transition` (no `lifecycleService.transition`); `revoking your own membership is refused and cascades nothing` (no transition, no `revokeBranchAssignmentsForMembership`/`revokeRoleAssignmentsForMembership`); `a caller without membership.suspend gets the permission refusal, not the self refusal` (`MissingPermissionException`).
  - `DeniedAccessAuditorTests`: `a self-access refusal writes no second row` (code `lifecycle.self_membership_change` → no `api.access_denied` write).
- [ ] **Step 3: Write the failing integration test** `SelfAccessGuardIntegrationTests` (setup, `tenantPost`, `token`, `seedUser`, `membershipId` helpers as in `AuditSubjectUserSearchIntegrationTests`):
  - `an administrator cannot suspend or revoke their own membership`: the admin's own membership → suspend 403 `$.code == "lifecycle.self_membership_change"`; revoke 403 same; DB membership status still `ACTIVE`; `audit_event` rows with `outcome = 'DENIED'` and action in (`membership.suspend`, `membership.revoke`) for that membership = 2; `api.access_denied` rows by the admin = 0.
  - `another member can still be suspended`: a second member granted via `fixture.grantTenantAdmin` → suspend 200.
  - `an unknown membership is still not found`: random id → 404.
- [ ] **Step 4: Run them to verify they fail**

  Run: `./gradlew test --tests '*SelfAccessGuard*' --tests '*UserProvisioningServiceTests' --tests '*DeniedAccessAuditorTests'`
  Expected: compilation failure, then the new tests FAIL.
- [ ] **Step 5: Implement** the Interfaces block; in `suspendMembership` keep the `store.requireMembership` result and call `selfAccessGuard.requireNotOwnMembership(SelfAccessAttempt(command.actorId, command.organisationId, "membership.suspend", command.requestId), command.membershipId, snapshot.userId)` before the transition; in `revokeTenantMembership` the same with `"membership.revoke"` right after `requireMembership`; widen the auditor's prefix check and its KDoc; the 403 `@ApiResponse` descriptions name the new code.
- [ ] **Step 6: Run the tests to verify they pass** (same command). Expected: PASS.
- [ ] **Step 7: Document** the code on the membership suspend and revoke rows in `foundation-api.md`, and the "Self-access guard (#341)" section in `authorization-model.md` (what is refused, the codes, what stays allowed, link to ADR 0043 and #342).

### Task 2: The account routes, then ship

**Files:**
- Modify: `lifecycle/application/UserProvisioningService.kt` (`suspendUser` ~288, `deactivateUser` ~323)
- Modify: `iam/adapter/inbound/web/PlatformUserController.kt` (403 `@ApiResponse` text on suspend and deactivate)
- Modify: `docs/api/foundation-api.md` (Platform users table, line ~1405)
- Test: `lifecycle/application/UserProvisioningServiceTests.kt`, `lifecycle/adapter/inbound/web/SelfAccessGuardIntegrationTests.kt`

**Interfaces:**
- Consumes: `SelfAccessGuard.requireNotOwnAccount(...)` and `SELF_ACCOUNT_CHANGE` from Task 1.

- [ ] **Step 1: Write the failing tests**
  - `UserProvisioningServiceTests`: `suspending your own account is refused before any transition`; `deactivating your own account is refused and revokes nothing` (no transition, no `deactivationAssignmentRevoker.revoke`).
  - `SelfAccessGuardIntegrationTests`: `a platform administrator cannot suspend or deactivate their own account`: `/platform/users/{self}/suspend` → 403 `$.code == "lifecycle.self_account_change"`; `/deactivate` → 403 same; `user_account.status` still `ACTIVE`; 2 `DENIED` rows (`user_account.suspend`, `user_account.start_deactivation`) with `subject_user_id = self`; `another account can still be suspended` (link its Keycloak identity as `runLifecycle` does) → 200.
- [ ] **Step 2: Run them to verify they fail** (Task 1's command). Expected: the new tests FAIL.
- [ ] **Step 3: Implement**: in `suspendUser` and `deactivateUser`, right after `requirePlatformPermission`, `selfAccessGuard.requireNotOwnAccount(SelfAccessAttempt(command.actorId, command.organisationId, "user_account.suspend" | "user_account.start_deactivation", command.requestId), command.userId)`; the controller 403 texts; the Platform users rows in `foundation-api.md`.
- [ ] **Step 4: Run the tests to verify they pass.** Expected: PASS.
- [ ] **Step 5: Run the full gate**

  Run: `./gradlew qualityGate`
  Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 6: Commit, push and open the draft PR**

  ```bash
  git add -A docs src
  git commit -m "feat(lifecycle): refuse suspending, revoking or deactivating your own access (#341)"
  git push -u origin ccr-df4cfd28-t6zhqt-self-guard
  ```
  Draft PR against `main`, body closing #341 and naming #342; subscribe to its activity.
