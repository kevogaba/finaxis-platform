# P2 Issue Stack Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the open P2 issues (#167, #168, #169, #172, #175, #176, #222, #224, #297) as a
stack of small, linear pull requests.

**Architecture:** Kotlin-first hexagonal Spring Boot service. Lifecycle changes are new
`TransitionDefinition` edges (audit row = the FSM row, ADR 0031). Reads are `@GatedRead` query
methods. Permission changes are forward-only migrations that also write catalogue metadata.

**Tech Stack:** Kotlin, Spring Boot Web MVC, jOOQ (generated from migrations), Flyway,
PostgreSQL, Testcontainers, JobRunr, Spring Modulith, ArchUnit.

**Spec:** `docs/superpowers/specs/2026-10-24-p2-issue-stack-design.md` (approved). Read it and
`CLAUDE.md` before any task.

## Global Constraints

- One conventional commit per PR; stacked PRs rebase onto their base; push with
  `--force-with-lease`, never `--force`.
- `./gradlew qualityGate` must pass on each branch on its own base before it is pushed. It
  needs the Docker daemon (`docker ps` must answer; start `dockerd` if not). If Gradle cannot
  resolve plugins, stop and report; never push as green without the gate.
- Never edit `V1`-`V32`. This stack uses `V33` (task 2) and `V34` (task 6d), permission id
  `…067` for `role.archive`; the next free id after that is `…068`. A migration that adds a
  permission also inserts `kind`, `grant_scope` and, for a `MUTATION`, its
  `permission_view_requirement` rows, and grants it to `PLATFORM_SUPER_ADMIN`, every tenant's
  `TENANT_ADMIN` and `local-admin` (copy `V23` steps 1a and 2).
- IDs: `uuidV7()` or the DB default, never `UUID.randomUUID()`.
- Public JSON `snake_case`; business dates `dd-MM-yyyy`; every listing paginated; every
  mutation takes an `Idempotency-Key`; every `@RequestBody` DTO is `@Valid`.
- New reads: `@GatedRead`, authorise before the first store call, no `@PreAuthorize` view gate
  on branch-aware routes. New mutations name only the mutation code in the service guard.
- Max line length 100. KDoc on public production classes and functions (Detekt).
- Do not add a trigger (ADR 0024). Do not log secrets or personal data.
- Each PR updates `docs/api/foundation-api.md`, OpenAPI annotations, the smoke script and the
  affected architecture or security docs for its own change.

## Review Focus

- A role archived while an invite or an assignment is in flight must not leave an `ACTIVE`
  assignment to an `ARCHIVED` role (both paths take the role lock).
- A first-life Keycloak or invite job retried after a member is re-invited must not activate
  the member or skip the new approval.
- `permission_scopes` for a role granted only on a suspended branch, and with a direct `DENY`
  override, must not report a grant the caller does not hold.
- COB readiness for a tenant with no accounting data returns `ready` with passing checks, and a
  caller without `cob.start` gets the named 403.
- Pending counts for a caller holding only a branch-scope `branch.approve` show no branch
  count, and never count an item the caller made.

## Branch protocol

The designated branch `ccr-ccd5aef9-9zr5r4` carries the design PR (#300, base `main`). Each
later PR needs its own branch off the previous one. Use
`ccr-ccd5aef9-9zr5r4-pr<N>-<slug>`, base = the previous PR's branch. **Pushing these branches
needs the owner's explicit permission** (session rule: never push a branch other than the
designated one); get it before task 1's push.

---

### Task 1: PR 1 — #175 `/auth/me` permission scopes

**Files:**
- Modify: `iam/application/profile/UserProfileService.kt`, `UserProfile` (same package),
  `iam/adapter/inbound/web/UserProfileController.kt` (DTO and mapper),
  `PermissionResolutionQueries` and its jOOQ adapter, `ReadGateRules.kt:51-56` comment
- Modify docs: `docs/api/foundation-api.md`, `docs/security/authorization-model.md`,
  `docs/security/active-organisation-context.md`
- Test: `AuthFlowIntegrationTests`, `BranchPinningIntegrationTests`, a new
  `ProfilePermissionScopesIntegrationTests`

**Interfaces:**
- Produces: `data class PermissionScope(val code: String, val scope: Scope,
  val branchIds: List<UUID>?)`; `PermissionResolutionQueries.grantsByScope(membershipId: UUID):
  List<PermissionScope>`; JSON `permission_scopes: [{code, scope: "TENANT"|"BRANCH",
  branch_ids?}]` beside the unchanged `permissions`.

- [ ] **Step 1:** Write failing tests: tenant grant reports `scope=TENANT`; branch-only grant on
  an `ACTIVE` branch reports `BRANCH` with that id; a suspended branch's grant is absent; a
  direct `DENY` removes the code; a direct `ALLOW` reports `TENANT`; `permissions` is unchanged.
- [ ] **Step 2:** Run them; expect FAIL (no field).
- [ ] **Step 3:** Implement `grantsByScope` as a variant of `branchIdsGranting` over every code,
  read fresh (not under the pinned-branch resolver key), tenant-filtered and bounded.
- [ ] **Step 4:** Map it in `UserProfileService` and the controller DTO; document the field.
- [ ] **Step 5:** Run the new tests and the two existing suites; PASS. Run `qualityGate`.
- [ ] **Step 6:** Commit `feat(iam): report permission scope on /auth/me (#175)`.

### Task 2: PR 2 — #297 role archive

**Files:**
- Create: `src/main/resources/db/migration/V33__role_archive.sql`,
  `docs/adr/0035-role-archive-and-assignment-lock.md`
- Modify: `RoleManagementService.kt` (new `archiveRole`, lock in `assignRoleToUser`),
  `RoleController.kt` (`POST /api/v1/roles/{role_id}/archive`),
  `IamAdministrationPersistence` and `JooqIamAdministrationPersistence.kt`
  (`countActiveAssignmentsForRole`, `insertRoleTransitionLog`), role definitions in
  `iam/domain`, `UserProvisioningService.kt:139-147` and `JooqUserProvisioningStore.kt:302-312`
  (take `lockRole` before the role-status check), `OrganisationBootstrapDefaults`
- Modify tests that hard-code the catalogue size: `PermissionCatalogueMetadataTests:279`,
  `FoundationSeedDataTests:272`, `JooqIamPermissionQueriesTests:54`; update the 83 in CLAUDE.md
- Test: new `RoleArchiveIntegrationTests`, `RoleArchiveRaceIntegrationTests`, unit tests for
  the role graph

**Interfaces:**
- Produces: transitions `ACTIVATE`, `DEACTIVATE`, `ARCHIVE` (audit actions stay `role.*`);
  `RoleManagementService.archiveRole(caller: Caller, roleId: UUID, reason: String?): RoleView`;
  permission `role.archive` (`MUTATION`, `TENANT`, requires `role.view`).

- [ ] **Step 1:** Write failing tests: archive from `ACTIVE` and `DISABLED`; 409 naming the
  count with an `ACTIVE` assignment; 409 for a system role; no edge out of `ARCHIVED`; 422 when
  assigning an archived role; archived roles hidden by default and shown by `status=ARCHIVED`;
  permission edits on an archived role refused; two-thread archive-versus-assign and
  archive-versus-invite races leave no `ACTIVE` assignment to an archived role; log row and
  status commit or roll back together.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Write `V33` (log table, permission, view requirement, grants, in-file pre- and
  post-condition asserts, idempotent). Run the migration tests.
- [ ] **Step 4:** Convert role status to `TransitionDefinition`s, add `archiveRole`, the route
  (idempotent, OpenAPI) and the `lockRole` calls on both assignment paths.
- [ ] **Step 5:** Write ADR 0035 superseding ADR 0034 decisions 1 and 2; update
  `foundation-api.md`, `authorization-model.md`, `lifecycle-fsm.md`, the smoke script.
- [ ] **Step 6:** Run the new suites, `PermissionCatalogueMetadataTests`, `SeededRolesDriftTests`,
  Modulith and ArchUnit; PASS. Run `qualityGate`.
- [ ] **Step 7:** Commit `feat(iam): archive a role as a terminal state (#297)`.

### Task 3: PR 3 — #222 discard a draft branch

**Files:**
- Create: `docs/adr/0036-discard-a-draft-branch.md`
- Modify: `FoundationLifecycleDefinitions.kt` (`DISCARD`, `DRAFT`→`CLOSED`, internal event only),
  `BranchProvisioningService.kt`, `BranchController.kt`
  (`POST /api/v1/branches/{branch_id}/discard`, body `{reason}` 3-500 chars),
  `docs/architecture/lifecycle-fsm.md`, `docs/api/foundation-api.md`,
  `docs/security/authorization-model.md`
- Test: `FoundationLifecycleTransitionTests`, a `BranchDiscardServiceTests`, a
  `BranchDiscardIntegrationTests`, `HighRiskOperationAuditCoverageTests`

**Interfaces:**
- Produces: `BranchProvisioningService.discard(caller: Caller, branchId: UUID, reason:
  String): BranchView`; permission `branch.create`; no externalized event; code stays taken.

- [ ] **Step 1:** Write failing tests: draft→`CLOSED` with `closed_on` set and `opened_on`
  null; 409 from any other state; refuses the head-office draft; refuses a draft with children;
  organisation must be `ACTIVE` or `PROVISIONING`; missing permission is a named 403 before any
  lookup; the code is still taken afterwards; one FSM audit row; no
  `finaxis.lifecycle.branch.closed` outbox row.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Add the edge, service method and route (idempotent, OpenAPI); reuse
  `branchClosureGuard`.
- [ ] **Step 4:** ADR 0036, docs, smoke step.
- [ ] **Step 5:** Run new suites plus lifecycle suites, Modulith, ArchUnit; PASS. `qualityGate`.
- [ ] **Step 6:** Commit `feat(lifecycle): discard a draft branch (#222)`.

### Task 4: PR 4 — #172 member re-invite, tenant documented

**Files:**
- Create: `docs/adr/0037-member-reinvite-and-dispatch-generation.md`
- Modify: `FoundationLifecycleDefinitions.kt` (membership `REINVITE`, `REVOKED`→
  `PENDING_APPROVAL`), `InviteeResolution.kt`, `UserProvisioningService.kt` (dispatch keys at
  `:513,541,645-648`), `JooqUserProvisioningStore.kt` (`existingMembershipId`, dispatch insert
  at `:492`), `ApplicationInviteJobRequestHandler.kt:34`,
  `KeycloakUserProvisioningJobRequestHandler.kt:159-214`, `JobIds.kt`
- Modify docs: `lifecycle-fsm.md`, `foundation-api.md` (REJECTED terminal, code reserved),
  ADR 0029 "out of scope" text
- Test: `UserProvisioningServiceTests`, new `MemberReinviteIntegrationTests`,
  `StaleDispatchJobIntegrationTests`

**Interfaces:**
- Produces: membership generation in the dispatch key and derived job id (if a column is
  needed, stop and raise it before writing a migration); handlers refuse a job whose generation
  is not the row's current one.

- [ ] **Step 1:** Write failing tests: a revoked member invited again returns to
  `PENDING_APPROVAL` with type, primary branch, `CREATED_BY`, preferences, `joined_at`,
  `revoked_at`, `status_reason` reset and direct overrides cleared; any non-revoked existing
  membership is still the 409; approval by the maker is refused; approval sends a fresh invite
  (new generation, new job id); a retried first-life Keycloak job after re-invite does not
  activate the row; an in-flight job for a live membership is undisturbed.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Add the edge and the reset; add the generation to dispatch key and job id
  and the handler guards.
- [ ] **Step 4:** ADR 0037, docs (tenant rejection documented), smoke step.
- [ ] **Step 5:** Run new suites, `InviteApproveClientErrorIntegrationTests`, Modulith,
  ArchUnit; PASS. `qualityGate`.
- [ ] **Step 6:** Commit `feat(lifecycle): re-invite a revoked member (#172)`.

### Task 5: PR 5 — #168 COB readiness and #169 pending counts

**Files:**
- Create: `accounting/application/AccountingCloseReadiness.kt` (port) with a jOOQ read
  adapter under `accounting/adapter/outbound`; `docs/adr/0038-cob-readiness-and-approval-counts.md`
- Modify: `BusinessDateController.kt`, `BusinessDateService.kt`, `ApiPaths.kt`, an approvals
  query service and controller in `lifecycle`, `PlatformTenantController.kt` or a sibling,
  `ReadGateRules.kt` only if an allowlist entry is genuinely needed (with its reason), docs,
  smoke script
- Test: `CobReadinessIntegrationTests`, `ApprovalCountsIntegrationTests`, an accounting
  adapter test

**Interfaces:**
- Produces: `GET /api/v1/tenant/business-date/cob/readiness` →
  `{business_date, status, ready, checks:[{code, severity, status, detail, count?}]}`;
  `AccountingCloseReadiness.snapshot(organisationId: UUID): CloseReadinessSnapshot`
  (`pendingApprovalManualJournals`, `draftManualJournals`, `latestBreakRuns`);
  `GET /api/v1/tenant/approvals/pending-count` and
  `GET /api/v1/platform/approvals/pending-count`.

- [ ] **Step 1:** Write failing tests: readiness `BLOCKING` fail for a `PENDING_APPROVAL`
  manual journal, `ADVISORY` warn for a draft and for a latest-run `BREAK`; a tenant with no
  accounting data is `ready`; business date not `OPEN` blocks; caller without `cob.start` gets
  the named 403; counts use `user.approve`, `branch.approve` (tenant-wide only) and, on the
  platform route, `tenant.approve`; a caller lacking a code sees no count for it; items the
  caller made are excluded; accounting approvals absent.
- [ ] **Step 2:** Run; expect FAIL.
- [ ] **Step 3:** Implement the port and adapter (no lifecycle read of accounting tables), the
  `@GatedRead` service methods and routes.
- [ ] **Step 4:** ADR 0038, docs, smoke steps; note the ADR 0029 pointer.
- [ ] **Step 5:** Run new suites, `PermissionFreeReadRuleTests`, Modulith, ArchUnit; PASS.
  `qualityGate`.
- [ ] **Step 6:** Commit `feat(lifecycle): COB readiness and pending approval counts (#168, #169)`.

### Task 6a: PR 6 — #224 platform audit read coverage

**Files:** Modify `PlatformAuditEventIntegrationTests.kt`, `scripts/local-smoke.sh:246-258`;
use `grantPlatformSupport` from `TenantAdminOrganisationFixture`.

- [ ] **Step 1:** Add tests: a real platform action (user suspend or tenant approve) read back
  as `PLATFORM_SUPPORT` with withheld fields null; a refused platform-audit route writes an
  `api.access_denied` `DENIED` row; `outcome=DENIED` returns it.
- [ ] **Step 2:** Add an `?outcome=DENIED` smoke step.
- [ ] **Step 3:** Run the suites; PASS. `qualityGate`.
- [ ] **Step 4:** Commit `test(audit): cover platform audit reads and the denied outcome (#224)`.

### Task 6b: PR 7 — #167 ADR for search and export

**Files:** Create `docs/adr/0039-global-search-and-audit-export.md`.

- [ ] **Step 1:** Write the ADR with the decisions in the spec (PR 6b): scope, bounded page per
  type of about 10, `ILIKE`, `audit.export`, CSV only, row cap (state the number), synchronous
  streaming, audit row per export, rate-limit policy, ledger export deferred to #52.
- [ ] **Step 2:** Commit `docs(adr): global search and audit export (#167)`.

### Task 6c: PR 8 — #167 tenant global search

**Files:** `iam` query service and controller for `GET /api/v1/tenant/search?q=`; depends on
`lifecycle::query` for branches; tests `GlobalSearchIntegrationTests`.

**Interfaces:** Produces JSON `{users:[…], branches:[…], roles:[…], memberships:[…]}`, each a
bounded page; a type the caller cannot view is omitted.

- [ ] **Step 1:** Failing tests: each type returns matches; a caller without a type's view code
  gets no entry for it; branch results respect branch visibility; the cap applies; empty `q`
  is a 400.
- [ ] **Step 2:** Implement with `@GatedRead` delegating to each type's gated query.
- [ ] **Step 3:** Docs, smoke, run suites plus `PermissionFreeReadRuleTests`, Modulith,
  ArchUnit; `qualityGate`.
- [ ] **Step 4:** Commit `feat(iam): tenant global search (#167)`.

### Task 6d: PR 9 — #167 audit CSV export and `audit.export`

**Files:** Create `V34__audit_export_permission.sql`; modify `AuditEventController.kt`,
`AuditQueryService`, `JooqAuditEventQueries`, rate-limit config, docs; update the three
catalogue-size tests again and CLAUDE.md; tests `AuditExportIntegrationTests`.

**Interfaces:** Produces `GET /api/v1/audit-events/export` (streamed CSV, `occurred_from`
required, row cap), permission `audit.export` (`MUTATION`, `TENANT`, requires `audit.view`).

- [ ] **Step 1:** Failing tests: CSV streams with the same filters as search; missing
  `occurred_from` is 400; over the cap is the documented refusal; a caller with `audit.view`
  but not `audit.export` gets the named 403; one audit row records filters and never contents;
  the rate limit applies.
- [ ] **Step 2:** Write `V34` and implement; update drift and catalogue tests.
- [ ] **Step 3:** Docs, smoke; run suites; `qualityGate`.
- [ ] **Step 4:** Commit `feat(audit): export audit events as CSV (#167)`.

### Task 7: PR 10 — #176 documentation drift

**Files:** `docs/api/foundation-api.md` (profile example, `module_code`, "local Scalar UI"),
`docs/security/active-organisation-context.md` (snake_case names at `:30,40,42,47,161`),
`docs/architecture/rate-limiting.md:14-26`, `docs/architecture/lifecycle-fsm.md` (missing and
misnamed edges incl. those added by tasks 2-4), `README.md:10,122-123`.

- [ ] **Step 1:** Re-verify each edit against the code on the stack's head; fix only drift that
  still exists.
- [ ] **Step 2:** Commit `docs: fix drift against the implemented API (#176)`.
