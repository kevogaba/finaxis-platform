# Role Update Validation (#340) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `PATCH /api/v1/tenant/roles/{role_id}` refuses a blank `role_name`, and clears `description` only on an explicit `null`.

**Architecture:** Validation at the web edge (DTO pattern for the name, controller check for the `Optional` description), a service backstop for the name, and a `changesDescription` flag carried from the command through the persistence port to jOOQ.

**Tech Stack:** Kotlin, Spring Boot Web MVC, Bean Validation, jOOQ, JUnit 5 + MockMvc, Testcontainers PostgreSQL.

**Spec:** `docs/superpowers/specs/2026-11-16-role-update-validation-design.md`

## Global Constraints

- Branch `ccr-df4cfd28-t6zhqt`; the PR carries exactly one conventional commit over `main`.
- `./gradlew qualityGate` passes locally (Docker daemon up) before the push.
- Public JSON is snake_case; error codes: blank value → 400 `validation_failed`; service backstop → 422 `invalid_operation`.
- KDoc on every public production class and function (Detekt); max line length 100.
- Paths below are relative to `src/main/kotlin/com/finaxis/platform/` (tests: `src/test/kotlin/com/finaxis/platform/`).

## Review Focus

1. `{"role_name": null}` keeps the name (it is not a 400) — pinned in Task 1's controller test.
2. A name of only non-breaking spaces (`" "`) passes the DTO regex but is refused by the service backstop with 422 — pinned in the service test.
3. `{}` (empty body) is a no-op update that still answers 200 with the role and writes one `role.update` row, as today — pinned in the integration test.
4. A blank description on a system role answers 400 (validation runs before the service's 409) — pinned in the controller test (service never called).
5. An explicit `null` description on a role that has none is a harmless 200 — covered by the integration test's clear-then-clear sequence.

---

### Task 1: Role update validation

**Files:**
- Create: `common/web/api/RequestPatterns.kt`
- Modify: `lifecycle/adapter/inbound/web/dto/BranchApiDtos.kt:54` (use the shared constant)
- Modify: `iam/adapter/inbound/web/dto/RoleApiDtos.kt:25-28` (`UpdateRoleRequest`)
- Modify: `iam/adapter/inbound/web/RoleController.kt:296-317` (`updateRole`)
- Modify: `iam/application/role/RoleCommands.kt:17-24` (`UpdateTenantRole`)
- Modify: `iam/application/role/RoleManagementService.kt:76-95` (`updateTenantRole`)
- Modify: `iam/application/port/outbound/IamAdministrationPersistence.kt:52-59`, `iam/adapter/outbound/persistence/JooqIamAdministrationPersistence.kt:89-111` (`updateRole`)
- Modify: `docs/api/foundation-api.md` (Roles section, line ~1860)
- Test: `iam/adapter/inbound/web/RoleControllerTests.kt`, `iam/application/role/RoleManagementServiceTests.kt` (and its fake persistence at ~1174/~1384)
- Create test: `iam/adapter/inbound/web/RoleUpdateIntegrationTests.kt`

**Interfaces:**
- Produces: `const val NOT_BLANK_PATTERN = "[\\s\\S]*\\S[\\s\\S]*"` (object or top-level in `common.web.api`, KDoc'd).
- Produces: `data class UpdateRoleRequest(@field:Pattern(regexp = NOT_BLANK_PATTERN, message = "must not be blank") val roleName: String? = null, val description: Optional<String>? = null)` with `@field:Schema` texts: name "Absent or null keeps the name; a value must not be blank."; description `type = "string", nullable = true`, "Absent leaves the description unchanged; an explicit null clears it; a non-blank string sets it."
- Produces: `data class UpdateTenantRole(organisationId: UUID, roleId: UUID, roleName: String?, changesDescription: Boolean, description: String?, actorId: UUID, requestId: String? = null)`.
- Produces: `fun updateRole(organisationId: UUID, roleId: UUID, roleName: String?, changesDescription: Boolean, description: String?, rowVersion: Long, actorId: UUID)` on the port and its jOOQ adapter: set `ROLE.DESCRIPTION` to `description` (possibly `null`) only when `changesDescription`.

- [ ] **Step 1: Write the failing controller tests** in `RoleControllerTests`:
  - `a blank role name is a validation failure that reaches no service`: PATCH `{"role_name":"   "}` → 400, `$.code == "validation_failed"`; `verify(roleManagementService, never()).updateTenantRole(any())`.
  - `a blank description is a validation failure that reaches no service`: PATCH `{"description":" "}` → 400 `validation_failed`; service never called.
  - `the description passes through as absent, null or a value`: three PATCHes (`{"role_name":"Ops"}`, `{"role_name":null,"description":null}`, `{"description":"Front office"}`) each 200; capture `UpdateTenantRole`: `(roleName="Ops", changesDescription=false, description=null)`, `(null, true, null)`, `(null, true, "Front office")`.
  - Change the existing `role create update activate and deactivate …` PATCH body to the raw JSON `{"role_name":"Ops"}` (an `UpdateRoleRequest` no longer serialises to that shape).
- [ ] **Step 2: Write the failing service tests** in `RoleManagementServiceTests`:
  - `update tenant role refuses a blank name before writing`: `roleName = " "` → `assertThrows<InvalidOperationException>`; the fake persistence recorded no `updateRole` call.
  - `update tenant role clears the description only when asked`: `changesDescription = true, description = null` → the fake records `(changesDescription=true, description=null)`; `changesDescription = false, description = null` → records `false`.
  - Update the existing `update tenant role changes mutable fields …` and `… rejects unknown role` constructions for the new parameter.
- [ ] **Step 3: Write the failing integration test** `RoleUpdateIntegrationTests` (copy the setup, `post`, `token`, `seedUser` and `audits` helpers of `RoleStatusIntegrationTests`; add a `patch` helper with `IdempotencyKeyFilter.IDEMPOTENCY_KEY_HEADER` and the `role.update` + `role.view` authorities):
  - `a blank name is refused and writes nothing`: create `{"role_code":"BLANK","role_name":"Blank","description":"Keep"}`; PATCH `{"role_name":"   "}` → 400 `validation_failed`; DB `role_name == "Blank"`; `audits("role.update", id) == 0`.
  - `an absent description keeps it and an explicit null clears it`: create with `"description":"First"`; PATCH `{"role_name":"Renamed"}` → 200, `$.description == "First"`; PATCH `{"description":null}` → 200, `$.description` is null; DB `description IS NULL`; PATCH `{"description":null}` again → 200; PATCH `{}` → 200; `audits("role.update", id) == 4`.
  - `a blank description is refused and the stored one stays`: create with `"description":"Stay"`; PATCH `{"description":""}` → 400; DB description `"Stay"`.
- [ ] **Step 4: Run them to verify they fail**

  Run: `./gradlew test --tests '*RoleControllerTests' --tests '*RoleManagementServiceTests' --tests '*RoleUpdateIntegrationTests'`
  Expected: compilation failure on `changesDescription`, then (after stubbing) the new tests FAIL.
- [ ] **Step 5: Implement** the Interfaces block: the shared constant (and `BranchApiDtos` switched to it); the DTO; in `RoleController.updateRole`, before the service call, throw `InvalidRequestException("validation_failed", "description must not be blank; send null to clear it.")` when `request.description?.orElse(null)?.isBlank() == true`, and pass `changesDescription = request.description != null, description = request.description?.orElse(null)`; in `updateTenantRole`, `invalidOperationUnless(command.roleName?.isNotBlank() ?: true)` before `requiredRole`, then pass the flag through; the port and jOOQ adapter as specified; KDoc on the new parameter.
- [ ] **Step 6: Run the tests to verify they pass** (same command as Step 4). Expected: PASS.
- [ ] **Step 7: Document** in `docs/api/foundation-api.md`'s Roles section a "Role update (#340)" paragraph: absent or `null` `role_name` keeps it, a blank one is 400 `validation_failed`; `description` absent keeps, explicit `null` clears, a string sets it, a blank string is 400.
- [ ] **Step 8: Run the full gate**

  Run: `./gradlew qualityGate`
  Expected: `BUILD SUCCESSFUL`.
- [ ] **Step 9: Commit, push and open the draft PR**

  ```bash
  git add -A docs src
  git commit -m "fix(iam): refuse a blank role name and clear a description only on null (#340)"
  git push -u origin ccr-df4cfd28-t6zhqt
  ```
  Draft PR against `main`, body closing #340; subscribe to its activity.
