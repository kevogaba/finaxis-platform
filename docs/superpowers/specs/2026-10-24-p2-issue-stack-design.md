# P2 issue stack: design

Status: **draft for approval**. Nothing in this document is built yet.

Scope: the open P2 issues other than the tenant-settings ones (#276, #277, #278, handled
separately): #167, #168, #169, #172, #175, #176, #222, #224, #297. Each was verified against
`main` at `9ec5cc5` before being scoped here, and the design was then checked against the code by
an independent reviewer (corrections folded in below).

## Intent

Close the remaining P2 gaps as a stack of small, linear pull requests. Each PR is one
conventional commit, rebased onto the previous one, and passes `./gradlew qualityGate` on its own
base (CLAUDE.md, "Pull requests and commits").

## Goals, non-goals, anti-goals

- Goals: fix what is still true on `main`; record every design call in an ADR or the changed
  docs; keep the existing security boundaries (permission codes, maker-checker, `@GatedRead`).
- Non-goals: anything about tenant settings; ledger export (waits for the accounting REST work,
  #52); a full notifications inbox; XLSX export; a doc-drift guard test (split into its own
  issue).
- Anti-goals: no edit to `V1`-`V32`; no new trigger (ADR 0024); no new permission code unless
  listed below; no widening of any role's access.

## Decisions taken (owner)

1. A terminal state keeps its unique code taken (#222, #297, and #172's rejected tenant). No
   partial unique indexes. Error messages and docs say the code is reserved.
2. #172: a rejected tenant stays terminal and is documented as the "start over" outcome, since
   return-for-changes is the recovery path. A revoked member can be re-invited.
3. #167: an ADR, then tenant global search, then audit CSV export.
4. #169: a pending-approval counts endpoint, no new tables.

## Decisions proposed by the assistant (awaiting confirmation)

- #224: tests and smoke coverage only. The no-GET-audit policy stays.
- #168: advisory readiness only, no enforcement at `cob/start`.
- #175: additive `permission_scopes` field; the flat `permissions` list is unchanged.
- #297: archive only (terminal), no hard delete, no cascade revoke.
- #222: a `DISCARD` edge from `DRAFT` to `CLOSED`, no new state, no externalized event.
- #176: the six doc edits; guard test split off.
- #172 re-invite: add a generation to the invite dispatch key (see PR 4).
- #169: counts exclude items the caller made themselves; accounting approvals are left out.
- #167: a new `audit.export` permission, following the `accounting_report.export` precedent.

## Approaches considered (summary)

| Issue | Chosen | Rejected, and why |
|---|---|---|
| #222 | `DISCARD` edge to `CLOSED` | `DISCARDED` state (migration, cost per ADR 0029); hard delete |
| #297 | Archive only | Cascade revoke (strips users); hard delete (ignores assigned roles) |
| #172 member | `REINVITE` edge | Clearer 409 only (leaves a returning person unrecoverable) |
| #168 | Advisory endpoint | Enforcement (readiness is a point-in-time snapshot, so it is racy) |
| #169 | Counts endpoint | Full inbox (reverses ADR 0029, needs a table and events) |
| #175 | Additive field | Replacing `permissions` with objects (breaking) |
| #167 | Slices behind an ADR | One large PR |

## Migration numbers

PR 2 takes `V33` and permission id `…067`. A parallel tenant-settings stack may also claim `V33`
or `V34`, so the numbers are reserved up front: this stack uses `V33` (PR 2) and, if approved,
`V34` (PR 6d). If the settings work lands first, renumber before pushing, never after.

## The stack (bottom to top)

### PR 1: #175, `/auth/me` permission scopes (iam)

- Add `permission_scopes: [{code, scope: TENANT|BRANCH, branch_ids?}]` next to `permissions`.
  Branch grants count only for `ACTIVE` branches, the same rule as #242
  (`branchIdsGranting`). Direct `DENY` overrides are subtracted, and direct `ALLOW` overrides are
  tenant-wide, as `EffectivePermissionResolver` already treats them.
- New `PermissionResolutionQueries` method for every code's branch grants; read fresh, not under
  the pinned-branch `EffectivePermissionResolver` key.
- Touches `UserProfileService`, `UserProfile`, the web DTO and mapper, OpenAPI, and
  `foundation-api.md`, `authorization-model.md`, `active-organisation-context.md`. Update the
  `ReadGateRules` comment that says `/auth/me` is built from the principal alone.
- No migration, no permission code, no new route.
- Tests: `AuthFlowIntegrationTests`, `BranchPinningIntegrationTests`, a branch-scope-only role,
  a `DENY` override.
- Closes #175.

### PR 2: #297, role archive (iam)

- ADR 0035 supersedes ADR 0034 decisions 1 and 2 (the status guard and the unlocked assignment),
  not only its "not covered" text.
- `V33__role_archive.sql`: `role_transition_log`; the `role.archive` permission (`MUTATION`,
  `TENANT`, requires `role.view`, id `…067`) with its `permission_view_requirement` row; grants
  to `PLATFORM_SUPER_ADMIN`, every tenant's `TENANT_ADMIN` and `local-admin` (V23 steps 1a and
  2). Pre- and post-condition asserts in-file, idempotent.
- Role status becomes an FSM with transitions named `ACTIVATE`, `DEACTIVATE` and `ARCHIVE`, so
  audit action names stay `role.*`. `ARCHIVED` is reachable from `ACTIVE` or `DISABLED`, only
  with zero `ACTIVE` assignments (409 naming the count), has no outgoing edge, and system roles
  stay 409. Permission edits on an archived role are refused.
- `POST /api/v1/roles/{role_id}/archive` (idempotent, OpenAPI). Archived roles are hidden from
  the default list; `status=ARCHIVED` shows them. The code stays reserved.
- Both paths that assign a role take `lockRole` so an archive serialises with them:
  `assignRoleToUser` and the invite path (`UserProvisioningService` role check and
  `JooqUserProvisioningStore`).
- The FSM row is the audit record (ADR 0031); no extra service row.
- Tests: edges, precondition, 409 and 422 paths, two-thread archive-versus-assign and
  archive-versus-invite races on Testcontainers, log-row-plus-status atomicity,
  `PermissionCatalogueMetadataTests`, the V23 drift test, and the three tests that hard-code the
  catalogue size 83 (`PermissionCatalogueMetadataTests`, `FoundationSeedDataTests`,
  `JooqIamPermissionQueriesTests`). Update the catalogue count in CLAUDE.md.
- Closes #297.

### PR 3: #222, discard a draft branch (lifecycle)

- `BranchLifecycleTransition.DISCARD` (`DRAFT` to `CLOSED`, own transition name) and
  `POST /api/v1/branches/{branch_id}/discard` with a required reason (3-500 characters).
  Permission `branch.create`, checked before any lookup. The head-office draft is refused. The
  `branchClosureGuard` children check applies. The organisation must be `ACTIVE` or
  `PROVISIONING`. The code stays taken.
- A draft can already reach `CLOSED` through `SUSPEND_DRAFT` then `CLOSE_SUSPENDED` during an
  organisation deprovision. `DISCARD` is the per-branch route to the same state and emits no
  externalized `finaxis.lifecycle.branch.closed` event, because a branch that never opened has
  nothing to announce. Say so in the ADR.
- ADR 0036. Update `lifecycle-fsm.md` for the new edge.
- No migration (no CHECK pins the branch status set beyond `chk_branch_status`, which already
  allows `CLOSED`).
- Tests: `FoundationLifecycleTransitionTests`, service tests in the `BranchReturnServiceTests`
  style, `HighRiskOperationAuditCoverageTests`, a controller and integration test.
- Closes #222.

### PR 4: #172, member re-invite and tenant documentation (lifecycle)

- Tenant: no code. Document `REJECTED` as terminal and the code as reserved in
  `lifecycle-fsm.md`, `foundation-api.md` and ADR 0029's "out of scope" text.
- Member: `MembershipLifecycleTransition.REINVITE` (`REVOKED` to `PENDING_APPROVAL`), through the
  existing invite route and the `user.invite` permission. `InviteeResolution` stops refusing
  `REVOKED`; any other existing membership stays a 409.
- Reset on the existing row: type, primary branch, `CREATED_BY`, invitation preferences,
  `joined_at`, `revoked_at`, `status_reason`, and the user's `PROVISIONING_IDP` "resumed" case.
  Direct `membership_permission` overrides are cleared. Revoke already ended the branch and role
  assignments. A checker must approve again (the checker is never the maker).
- **Dispatch safety (found in review).** Invite dispatch keys are fixed per organisation and
  user with no per-membership part, the dispatch insert is a no-op when the key exists, the
  handler returns early once a dispatch has `SUCCEEDED`, and the JobRunr job id derives from the
  key. Without a change a re-approved member silently gets no invite. Dispatch also happens at
  approval, not at re-invite. Further, a Keycloak job from the first life that is retried only
  cancels while the membership is `REVOKED`; once the row is `PENDING_APPROVAL` it would
  activate the member and bypass the new checker. Proposed: add a membership generation to the
  dispatch key (and the derived job id), and make the handlers refuse a job whose generation is
  not the row's current one. The alternative, deleting the old dispatch rows, loses the delivery
  record and does not stop the stale job. **Open: confirm.**
- ADR 0037 for the edge, the generation rule and the code-retention rule.
- No migration unless the generation needs a column; if it does, it takes the next free number
  and is raised before coding.
- Tests: FSM graph, service, controller; a stale-job test (retry of a first-life Keycloak job
  after re-invite must not activate); a re-approval-sends-invite test; atomicity.
- Closes #172 (the tenant half is resolved as documented behaviour).

### PR 5: #168 and #169 (lifecycle tenant and platform reads)

- #168: `GET /api/v1/tenant/business-date/cob/readiness`. Returns `{business_date, status, ready,
  checks:[{code, severity BLOCKING|ADVISORY, status PASS|FAIL|WARN, detail, count?}]}`.
  Lifecycle checks: business date `OPEN`, organisation `ACTIVE`. Accounting checks come from a
  new accounting-owned port `AccountingCloseReadiness` (in the style of `AccountingDayRollover`):
  `PENDING_APPROVAL` manual journals are `BLOCKING`; `DRAFT` manual journals and reconciliation
  `BREAK` runs (the latest run per account only) are `ADVISORY`. No `PENDING` posting-request
  check, because no committed posting request is ever `PENDING`. Jobs and audit-stream checks are
  omitted (no signal exists). Tenant-wide, `@GatedRead`, reuses `cob.start` (already paired with
  `business_date.view`). Advisory only; the ADR records the later move to enforcement.
- #169: `GET /api/v1/tenant/approvals/pending-count` returns one count per resource the caller
  may approve, using existing codes only: memberships by `user.approve` (implies
  `membership.view`), branches by `branch.approve` (implies `branch.view`; only a tenant-wide
  grant can approve a pending branch, so no branch targeting is needed). The platform
  equivalent is `GET /api/v1/platform/approvals/pending-count` for pending tenants by
  `tenant.approve` (implies `tenant.view`, platform scope). Counts exclude items the caller
  made. Accounting approvals are not included. `@GatedRead`. No new permission code.
- ADR 0038 covers the COB check list; an ADR 0029 note records that the counts route answers
  the badge need without an inbox.
- Tests: controller and security integration, named 403, a service test per check, Modulith and
  ArchUnit, smoke steps.
- Closes #168 and #169.

### PR 6 (four small PRs on one branch each): #224 and #167

- 6a, #224: integration tests for a real platform action read back as `PLATFORM_SUPPORT`
  (withheld fields null), a refused platform-audit route writing an `api.access_denied`
  `DENIED` row, and an `outcome=DENIED` smoke step. No policy change. Refs #224 and recommends
  retitling it.
- 6b, #167 ADR 0039: search and export design (scope, one bounded page per type of about 10
  hits, `ILIKE`, export permission, CSV only, row cap, synchronous streaming, audit row per
  export). Docs only.
- 6c, #167 search: `GET /api/v1/tenant/search?q=` lives in `iam`, which may depend on
  `lifecycle::query` (the reverse is not allowed). It fans out to users, branches, roles and
  memberships. Each fan-out method is `@GatedRead` and takes the caller, each type is capped, and
  a type the caller cannot view is omitted. No migration.
- 6d, #167 export: audit CSV export, streamed, with a row cap and `occurred_from` required, an
  audit row per export (filters recorded, never contents), and its own rate-limit policy. It
  needs a gating permission; the proposal is a new `audit.export` (`MUTATION`, `TENANT`,
  requires `audit.view`, V23 grants) in `V34`, because reusing `audit.view` would let any viewer
  bulk-extract personal data. The catalogue-size tests are updated again. **Open: confirm.**
- Refs #167 (ledger export stays open until #52).

### PR 7: #176, docs drift (docs only)

Six edits: the profile example in `foundation-api.md` (after PR 1); `module_code` example;
snake_case names in `active-organisation-context.md`; the `rate-limiting.md` config shape; the
`lifecycle-fsm.md` graphs (the missing and misnamed edges, after PRs 2-4); the README
production-docs claim and module table, plus the `foundation-api.md` "local Scalar UI" wording.
Closes #176.

## Constraints

- Hexagonal layers, Modulith verification and ArchUnit rules (`ReadGateRules`) stay green.
- Public JSON is `snake_case`; business dates `dd-MM-yyyy`; every listing paginated; every
  mutation idempotent.
- Max line length 100.
- `./gradlew qualityGate` needs the Docker daemon (now running in this sandbox) and Gradle
  plugin resolution, which is currently intermittent here; the gate is run on every branch of
  the stack, and a PR is not pushed as green without it.

## Left to the builder

File-level layout, test names, exact problem codes (following existing `lifecycle.*` naming),
and OpenAPI wording, within the constraints above.

## Risks

- The stack is long; a defect found late in a lower PR forces a rebase of everything above it.
- Reordering the stack renumbers migrations, so the order is fixed once PR 2 is pushed.
- The re-invite generation change touches job ids that JobRunr already stores; it must not
  disturb jobs already in flight for live memberships.
- Gradle availability in the sandbox decides whether the gate can run at all.
