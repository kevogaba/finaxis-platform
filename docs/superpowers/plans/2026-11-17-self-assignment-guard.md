# Self-Assignment Guard (#342) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Refuse a role or branch self-assignment unless the caller holds `user.assign_self` tenant-wide, a permission only the seeded administrator roles hold.

**Architecture:** One `V39` permission seed (V34's shape), one extra `PermissionGuard.requireTenantPermission` call in each assignment service when the subject is the caller, and a composition refusal so the API can never grant the code.

**Tech Stack:** Kotlin, Spring Boot, jOOQ, Flyway, JUnit 5, Testcontainers PostgreSQL.

**Spec:** `docs/adr/0044-self-assignment-guard.md`

## Global Constraints

- Branch `ccr-df4cfd28-t6zhqt-self-assign`, stacked on `ccr-df4cfd28-t6zhqt-self-guard` (#344); one conventional commit over that base; `./gradlew qualityGate` before the push.
- Permission row, verbatim: `40000000-0000-0000-0000-000000000069`, `user.assign_self`, name `Assign access to oneself`, module `iam`, `HIGH`, `ACTIVE`, `MUTATION`, `TENANT`; requires `user.view`.
- Composition refusal: 422 `invalid_operation`, detail `user.assign_self is held only by the seeded administrator roles and cannot be granted.`
- Catalogue becomes 86 codes, 85 `ACTIVE`.
- Paths relative to `src/main/kotlin/com/finaxis/platform/` (tests `src/test/kotlin/com/finaxis/platform/`).

## Review Focus

1. A `TENANT_ADMIN` held only at BRANCH scope does not bypass — pinned in the integration test.
2. A caller who lacks `user.assign_role` entirely still gets the `user.assign_role` refusal first (the existing check runs first) — pinned in the service test.
3. Assigning someone else needs no `user.assign_self` — pinned in both service tests.
4. Re-running `V39` changes nothing — pinned in the migration test.
5. A repeat of an existing self-assignment by a non-admin is still refused (no idempotent bypass) — the check precedes the existing-assignment short-circuit; pinned in the service test.

---

### Task 1: Permission, guard and composition refusal

**Files:**
- Create: `src/main/resources/db/migration/V39__user_assign_self_permission.sql`
- Modify: `iam/application/role/RoleManagementService.kt` (`assignRoleToUser`, `assignPermissionToRole`)
- Modify: `lifecycle/application/BranchProvisioningService.kt` (`assignUser`)
- Modify: `iam/adapter/inbound/web/RoleAssignmentController.kt`, `lifecycle/adapter/inbound/web/BranchAssignmentController.kt` (403 OpenAPI text)
- Modify: `CLAUDE.md`, `docs/security/authorization-model.md`, `docs/api/foundation-api.md`
- Test: `iam/application/role/RoleManagementServiceTests.kt`, `lifecycle/application/OrganisationBranchProvisioningServiceTests.kt`, `foundation/FoundationSeedDataTests.kt`, `iam/PermissionCatalogueMetadataTests.kt`, `iam/SeededRolesDriftTests.kt`, `common/audit/HighRiskOperationAuditCoverageTests.kt`
- Create test: `foundation/SelfAssignPermissionMigrationTests.kt` (mirror `AuditExportPermissionMigrationTests`), `iam/adapter/inbound/web/SelfAssignmentIntegrationTests.kt`

**Interfaces:**
- Produces: in each service a private `const val SELF_ASSIGN_PERMISSION = "user.assign_self"`; when `command.userId == command.actorId` (`assignedBy` for branches), `permissionGuard.requireTenantPermission(actor, organisationId, SELF_ASSIGN_PERMISSION)` right after the route's existing permission check.

- [ ] **Step 1: Failing tests**
  - `RoleManagementServiceTests`: `assigning a role to yourself needs user.assign_self` (userId == actorId; `doThrow(MissingPermissionException("user.assign_self"))` on `requireTenantPermission(actor, org, "user.assign_self")` → refused, nothing assigned, also when the assignment already exists); `an administrator may assign a role to themselves` (no throw → assignment persisted); `assigning someone else never asks for user.assign_self` (`verify(..., never())`); `user.assign_self cannot be granted to a role` (422, nothing granted).
  - `OrganisationBranchProvisioningServiceTests`: `assigning yourself to a branch needs user.assign_self` (refused, no assignment) and `assigning someone else to a branch never asks for user.assign_self`.
  - Catalogue tests: size 86; `user.assign_self` in the seed list; metadata map `"user.assign_self" to setOf("user.view")`; drift: held by `TENANT_ADMIN`, `PLATFORM_SUPER_ADMIN`, `local-admin`, by no other seeded role, by no non-admin bundle; coverage map `"user.assign_self" to "user.assign_role"`.
  - `SelfAssignPermissionMigrationTests`: the row and its view; the three admin grants; `IAM_ADMIN`, `BRANCH_MANAGER` and a custom role stay without it; re-running changes nothing.
  - `SelfAssignmentIntegrationTests` (seeded tenant via `TenantAdminOrganisationFixture`): a member holding the seeded `IAM_ADMIN` tenant-wide self-assigns a role → 403, detail names `user.assign_self`, no assignment row; self-assigns a branch → 403; the tenant's `TENANT_ADMIN` self-assigns a role and a branch → 201; `IAM_ADMIN` assigns another member → 201; a member holding `TENANT_ADMIN` only at BRANCH scope self-assigns a role → 403.
- [ ] **Step 2: Run them, expect failures** — `./gradlew test --tests '*RoleManagementServiceTests' --tests '*OrganisationBranchProvisioningServiceTests' --tests '*SelfAssign*' --tests '*FoundationSeedDataTests' --tests '*PermissionCatalogueMetadataTests' --tests '*SeededRolesDriftTests' --tests '*HighRiskOperationAuditCoverageTests'` → FAIL.
- [ ] **Step 3: Implement** V39 (V34's shape, asserts in-file, idempotent), the two service checks, the composition refusal, the controller 403 texts.
- [ ] **Step 4: Run them, expect PASS.**
- [ ] **Step 5: Document** CLAUDE.md (V39 entry, summary sentence, "catalogue is now 86 codes, 85 ACTIVE"), authorization-model.md ("Self-assignment (#342)"), foundation-api.md (role-assignment and branch-assignment rows).
- [ ] **Step 6: `./gradlew qualityGate`** → BUILD SUCCESSFUL.
- [ ] **Step 7: Commit, push, draft PR** with base `ccr-df4cfd28-t6zhqt-self-guard`, closing #342.

## Amendment after review

- **Composing a held role** (owner ruling, ADR 0044 decision 4): `assignPermissionToRole` also requires `user.assign_self` tenant-wide when the caller's membership is among the role's holders (`membershipIdsWithRole`, read once under the role lock and reused for the cache eviction, so no new port method). Removing a permission is unchanged. Pinned by `RoleManagementServiceTests` and `SelfAssignmentIntegrationTests`.
- **Re-enabling a held role** (owner ruling after the second review): the shared role transition also requires `user.assign_self` on the DISABLED → ACTIVE edge when the caller holds the role; deactivating is unchanged. Same helper (`requireMayWidenHeldRole`), same tests.
- **Module code** is `iam`, as every other `user.*` code, not `user`.
- **Branch-route order:** the route check (`requireBranchPermission`) already answers 404 for an unknown branch, so the self check is "before any other lookup" there.
