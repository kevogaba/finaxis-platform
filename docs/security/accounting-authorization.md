# Accounting authorization

## Overview

Accounting uses the platform's existing authorization machinery unchanged: Keycloak authenticates,
Finaxis authorizes, and every runtime check evaluates a permission **code**, never a role name.
This document records the accounting permission catalogue seeded by
`V5__accounting_permission_catalogue.sql`, argues the grouping decisions behind it, and states
precisely what the default role bundles do — and do not — guarantee.

`V5` is reference data only: no tables, no columns, no constraints. It seeds 26 codes under a new
`accounting` module code, raising the platform catalogue from 54 codes to 80. The vocabulary is
deliberately seeded *ahead* of the accounting schema (GitHub issue #34) so that the
chart-of-accounts, fiscal-calendar, journal and posting-rule work in Phase B inherits a settled
authorization vocabulary instead of adding codes piecemeal, one migration per feature.

Two Kotlin constants objects name what the migration seeds:

- `com.finaxis.platform.accounting.domain.AccountingPermissions` — the 26 permission codes, plus
  the grouped subsets (`CHART_OF_ACCOUNTS`, `FISCAL_PERIOD`, `JOURNAL`, `POSTING_RULE`,
  `RECONCILIATION`, `REPORTING`, `BREAK_GLASS`, `ALL`). This is the repository's first central
  permission-code constants object; foundation code still uses inline string literals.
- `com.finaxis.platform.accounting.domain.AccountingAuditActions` — the audit action name each
  privileged accounting operation will emit.

Nothing enforces these codes yet, because the behaviour they gate does not exist yet. What ships
with issue #34 is the vocabulary, the default bundles, and the tests that keep them honest.

## Permission Catalogue

| Code | Name | Module | Risk | Identifier |
| --- | --- | --- | --- | --- |
| `gl_account.view` | View chart of accounts | `accounting` | LOW | `41000000-0000-0000-0000-000000000001` |
| `gl_account.create` | Create GL account | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000002` |
| `gl_account.update` | Update GL account | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000003` |
| `gl_account.submit` | Submit GL account for approval | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000004` |
| `gl_account.approve` | Approve GL account | `accounting` | HIGH | `41000000-0000-0000-0000-000000000005` |
| `gl_account.deactivate` | Deactivate GL account | `accounting` | HIGH | `41000000-0000-0000-0000-000000000006` |
| `fiscal_period.view` | View fiscal periods | `accounting` | LOW | `41000000-0000-0000-0000-000000000007` |
| `fiscal_period.open` | Open fiscal period | `accounting` | HIGH | `41000000-0000-0000-0000-000000000008` |
| `fiscal_period.close` | Close fiscal period | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000009` |
| `fiscal_period.reopen` | Reopen fiscal period | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000010` |
| `journal.view` | View journals | `accounting` | LOW | `41000000-0000-0000-0000-000000000011` |
| `journal.create_manual` | Create manual journal | `accounting` | HIGH | `41000000-0000-0000-0000-000000000012` |
| `journal.submit` | Submit journal for approval | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000013` |
| `journal.approve` | Approve and post journal | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000014` |
| `journal.reverse` | Reverse journal | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000015` |
| `journal.post_prior_period` | Post into a prior period | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000016` |
| `posting_rule.view` | View posting rules | `accounting` | LOW | `41000000-0000-0000-0000-000000000017` |
| `posting_rule.create` | Create posting rule | `accounting` | HIGH | `41000000-0000-0000-0000-000000000018` |
| `posting_rule.update` | Create posting-rule version | `accounting` | HIGH | `41000000-0000-0000-0000-000000000019` |
| `posting_rule.submit` | Submit posting rule for approval | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000020` |
| `posting_rule.approve` | Approve and activate posting rule | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000021` |
| `reconciliation.view` | View reconciliations | `accounting` | LOW | `41000000-0000-0000-0000-000000000022` |
| `reconciliation.run` | Run reconciliation | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000023` |
| `reconciliation.resolve` | Resolve reconciliation | `accounting` | CRITICAL | `41000000-0000-0000-0000-000000000024` |
| `accounting_report.view` | View accounting reports | `accounting` | LOW | `41000000-0000-0000-0000-000000000025` |
| `accounting_report.export` | Export accounting reports | `accounting` | MEDIUM | `41000000-0000-0000-0000-000000000026` |

### The Identifier Block Rule

`V2__platform_reference_data.sql` froze `40000000-0000-0000-0000-0000000000NN` as the **foundation**
permission block. It runs from `…01` to `…63` for 53 codes, leaving gaps at `…26`–`…29` and
`…34`–`…39`. The 54th foundation code, `iam.profile.read`, sits outside that block at
`66666666-6666-6666-6666-666666666601` — which is why 53 ids and a 54-code catalogue are both
correct.

Accounting does **not** fill those gaps. It takes its own block, `41000000-…`, contiguous from
`…01` through `…26`, and every future domain takes the next `4X000000-…` block in turn. Two reasons:

- Interleaving domain codes into the foundation numbering makes `ORDER BY id` unreadable — the
  catalogue stops grouping by owning module the moment a second domain borrows a foundation gap.
- A block boundary makes the "is this code mine?" question answerable from the identifier alone, in
  a migration, without joining to `module_code`.

`AccountingPermissionCatalogueTests` asserts the block is exactly `…01`–`…26` with no gaps, so a
code added out of sequence fails the build rather than quietly fragmenting the block.

## Grouping Decisions

The catalogue is deliberately smaller than a naive one-code-per-operation expansion. Every fold is
argued below; so is every code that survived a proposal to fold it.

### Folded In

- **`activate` folds into `approve`**, for both the chart of accounts and posting rules. The
  lifecycle FSM has one `PENDING_APPROVAL → ACTIVE` transition, so a separate
  `gl_account.activate` / `posting_rule.activate` code would gate nothing an approver does not
  already do. Worse, it would create a reachable state in which the holder of `approve` cannot
  complete the act they were authorized to perform.
- **`post` folds into `journal.approve`.** This is the only code the issue's own list asks for that
  was deliberately removed. Approving a manual journal *is* posting it, in the same transaction. An
  approved-but-unposted journal is a promise the ledger cannot audit: the trial balance disagrees
  with the journal register for an unbounded window, and nothing in the schema records when — or
  whether — the promise is kept. If deferred posting is ever genuinely needed, the right model is a
  scheduled `posting_request` with its own state, its own lifecycle and its own permission code,
  not an unposted journal parked in an approved state. This follows directly from the
  immutable-ledger position in
  [ADR 0020](../adr/0020-immutable-ledger-and-reversal-only-correction.md).
- **Fiscal year and period share four codes** (`fiscal_period.view`, `.open`, `.close`, `.reopen`).
  A fiscal year is a container for its periods, and no realistic role closes periods but not years.
  Separate `fiscal_year.*` codes would double that part of the vocabulary and then be granted
  together every single time.
- **Trial balance, GL ledger, financial statements and drill-down share
  `accounting_report.view`.** They are the same posted data at different aggregations: anyone who
  may read the trial balance can already derive the ledger behind it, so splitting the code would
  advertise a boundary the data does not actually have.

### Kept Separate

- **`create` vs `update`.** The right to bring a new account into existence is not the right to
  amend an existing one. This also matches the foundation precedent —
  `tenant.create`/`tenant.update_draft` and `role.create`/`role.update` are already split the same
  way.
- **`submit` vs `create`, everywhere.** This is what supports a three-eyes control: a clerk
  prepares, a supervisor submits, an approver approves. Removing `submit` collapses that to two
  eyes, and an institution that wants three-eyes on manual journals then has nowhere to express it.
- **`journal.post_prior_period` apart from `journal.approve`.** Break-glass must be independently
  grantable and independently revocable. Folded into `journal.approve`, prior-period posting would
  be inherited by every approver, permanently, and revoking it would mean revoking the ability to
  approve anything at all.
- **`reconciliation.run` (MEDIUM) apart from `reconciliation.resolve` (CRITICAL).** Running a proof
  is safe: it reads, computes and reports, and the worst outcome is a report nobody wanted.
  Accepting or overriding a break is the control failure itself — it is the moment a known
  discrepancy is declared acceptable — and it belongs to a different person at a different risk
  level.

## Risk Classification

Risk levels follow the existing `LOW`/`MEDIUM`/`HIGH`/`CRITICAL` scale in `permission.risk_level`.

| Risk | Count | Codes |
| --- | --- | --- |
| LOW | 6 | `gl_account.view`, `fiscal_period.view`, `journal.view`, `posting_rule.view`, `reconciliation.view`, `accounting_report.view` |
| MEDIUM | 7 | `gl_account.create`, `gl_account.update`, `gl_account.submit`, `journal.submit`, `posting_rule.submit`, `reconciliation.run`, `accounting_report.export` |
| HIGH | 6 | `gl_account.approve`, `gl_account.deactivate`, `fiscal_period.open`, `journal.create_manual`, `posting_rule.create`, `posting_rule.update` |
| CRITICAL | 7 | `fiscal_period.close`, `fiscal_period.reopen`, `journal.approve`, `journal.reverse`, `journal.post_prior_period`, `posting_rule.approve`, `reconciliation.resolve` |

Two classifications are worth explaining:

- **`journal.create_manual` is HIGH while `journal.submit` is MEDIUM.** Preparing a manual journal
  is the entry point for arbitrary hand-written ledger movement — the content of the entry is
  decided there, and everything downstream only says yes or no to it. Submitting merely moves a
  document that already exists into a queue.
- **`accounting_report.export` is MEDIUM while `accounting_report.view` is LOW.** Viewing keeps
  financial data inside the platform's access controls and audit trail; exporting takes it out of
  them.

The 13 HIGH and CRITICAL codes each map to an `AccountingAuditActions` entry, and
`HighRiskOperationAuditCoverageTests` fails if a HIGH/CRITICAL permission is ever added without
deciding how it is audited. Note that `posting_rule.update` maps to the action
`posting_rule.create_version`, because amending a posting rule creates a new version rather than
mutating the active one.

Those 13 codes are seeded before the behaviour that emits their audit actions exists. That gap is
tracked explicitly by the `pendingEnforcement` ratchet in `HighRiskOperationAuditCoverageTests`,
which names each unwired action against the issue that will wire it (#38, #39, #43, #45, #46, #48,
#35). The set can only shrink — the moment an action gains a real call site, the test fails until
its entry is deleted — and it must reach empty before the accounting readiness gate (#54) passes.

## Privileged Operations And Maker-Checker

This is the part that is easiest to get backwards, so it is worth being exact.

**Separation of duties is enforced by actor identity at the transition — the approver must differ
from the submitter — not by splitting permissions across roles.** The foundation already works this
way: `UserProvisioningService.approveUser` rejects an approval whose actor is the inviter, and
`OrganisationProvisioningService.approveProvisioning` rejects an actor who requested or submitted
the organisation. The accounting FSM guards will follow the same pattern.

It follows that **a role holding both `submit` and `approve` is legitimate**. It means the holder
may perform either act on *different* records — not both acts on the same one, which the transition
guard refuses regardless of what the role grants. `TENANT_ADMIN` deliberately holds both sides of
the chart-of-accounts and posting-rule approvals, and the bootstrap `local-admin` role holds both
sides of the chart-of-accounts approval, so a two-actor maker-checker flow works out of the box
without an administrator first having to design a role split.

What the default bundles still guarantee is narrower, and still worth guaranteeing:

1. The two *dedicated* roles, `ACCOUNTING_OPERATOR` and `ACCOUNTING_APPROVER`, genuinely model the
   split — neither holds any code from the other side of an approval.
2. No default bundle carries a break-glass code.

Because runtime authorization never evaluates a role name, these bundles are conveniences rather
than a security boundary. The boundary is the permission check plus the actor-identity guard.

The approval pairs `AccountingSeparationOfDutiesPolicyTests` enforces across the two dedicated
roles are:

| Submit code | Approve code |
| --- | --- |
| `gl_account.submit` | `gl_account.approve` |
| `journal.submit` | `journal.approve` |
| `posting_rule.submit` | `posting_rule.approve` |

**Fiscal-period open and close are deliberately not a maker-checker pair.** They are two operations
in a lifecycle, not two halves of an approval of the same act, so one role legitimately holds both
— `ACCOUNTING_APPROVER` does. Treating them as a pair would force a second actor to open the period
that the first actor just closed, which is bookkeeping, not control.

## Default Role Bundles

Organisation approval creates the organisation-local system roles in **Kotlin**, not in any
migration: `JooqOrganisationBranchProvisioningStore.createDefaultRoles` iterates
`OrganisationBootstrapDefaults.ROLE_PERMISSIONS` and grants each bundle's codes. The role codes
below therefore appear in no migration, and — as everywhere else in the platform — runtime
authorization never evaluates one of them.

The accounting codes each default role receives:

- **`TENANT_ADMIN`** — all baseline foundation permissions plus 19 accounting configuration and
  oversight codes: `gl_account.view`, `gl_account.create`, `gl_account.update`,
  `gl_account.submit`, `gl_account.approve`, `gl_account.deactivate`, `fiscal_period.view`,
  `fiscal_period.open`, `fiscal_period.close`, `journal.view`, `posting_rule.view`,
  `posting_rule.create`, `posting_rule.update`, `posting_rule.submit`, `posting_rule.approve`,
  `reconciliation.view`, `reconciliation.run`, `accounting_report.view`,
  `accounting_report.export`. It deliberately excludes the operational and break-glass codes —
  manual journal preparation, journal submission, journal approval, reversal, prior-period posting,
  reconciliation resolution and period reopening — so the default administrator is not also the
  default poster. `TENANT_ADMIN` holds `role.assign_permission` and can grant itself more, so this
  is safe-by-default posture rather than a security boundary.
- **`TENANT_AUDITOR`** — read-only across the platform, including the six accounting reads:
  `gl_account.view`, `fiscal_period.view`, `journal.view`, `posting_rule.view`,
  `reconciliation.view`, `accounting_report.view`. It does **not** receive
  `accounting_report.export`: an auditor may read financial data inside the platform's audit trail
  without being able to take it out.
- **`BRANCH_MANAGER`** — one accounting code, `accounting_report.view`.
- **`BRANCH_OPERATOR`** — no accounting codes at all.
- **`ACCOUNTING_OPERATOR`** — the maker. Prepares and submits, never approves: `gl_account.view`,
  `gl_account.create`, `gl_account.update`, `gl_account.submit`, `fiscal_period.view`,
  `journal.view`, `journal.create_manual`, `journal.submit`, `posting_rule.view`,
  `posting_rule.create`, `posting_rule.update`, `posting_rule.submit`, `reconciliation.view`,
  `reconciliation.run`, `accounting_report.view`, `accounting_report.export` — plus the session
  codes `auth.select_organisation`, `auth.select_branch` and `iam.profile.read`.
- **`ACCOUNTING_APPROVER`** — the checker. Approves and posts, never prepares: `gl_account.view`,
  `gl_account.approve`, `gl_account.deactivate`, `fiscal_period.view`, `fiscal_period.open`,
  `fiscal_period.close`, `journal.view`, `journal.approve`, `journal.reverse`, `posting_rule.view`,
  `posting_rule.approve`, `reconciliation.view`, `reconciliation.resolve`,
  `accounting_report.view` — plus the same three session codes. It does not receive
  `accounting_report.export`, which stays with the maker and the administrator.

`IAM_ADMIN` receives no accounting codes: identity administration and financial operations are
separate concerns, and an IAM administrator who needs ledger access should be granted an accounting
role rather than have one silently folded into theirs.

## Break-Glass Permissions

Break-glass codes are enforced through a separate port method,
`AccountingPermissionGuard.requireBreakGlassPermission`, which does **not** honour the platform's
system-actor short-circuit. Their use is audited at the point of enforcement, and a build rule
fails if enforcement and audit are ever separated. See
`docs/architecture/accounting-dates-and-periods.md` for the reasoning.

Two codes are classified break-glass and appear in **no default tenant role bundle** (`PLATFORM_SUPER_ADMIN` holds the whole catalogue, as described below):

- `fiscal_period.reopen` — reopening a closed accounting period.
- `journal.post_prior_period` — posting into a period earlier than the business date.

They are named by `AccountingPermissions.BREAK_GLASS`, and
`AccountingSeparationOfDutiesPolicyTests` asserts that no default bundle intersects that set. They
are granted deliberately, per tenant, by an administrator making an explicit and audited decision —
never inherited by anyone who happens to hold an accounting role. Both are also CRITICAL and both
have an `AccountingAuditActions` entry, so every use will land in the audit trail once the
behaviour exists.

## Bootstrap And Existing-Tenant Posture

**`PLATFORM_SUPER_ADMIN`** keeps the entire catalogue. V2's superset grant was a set-based
`INSERT … SELECT … FROM permission` that ran once, at V2, so it cannot pick up codes added later;
`V5` repeats the same set-based grant for its own 26 codes, and asserts in a post-condition block
that no catalogue row is left ungranted.

**`PLATFORM_SUPPORT` deliberately receives nothing.** Platform staff must not be able to read
tenant financial data by default. A support-escalation path into a tenant's ledger is a separate,
audited decision, not a side effect of holding the support role.

**The bootstrap `local-admin` role receives exactly ten tenant-configuration codes:**
`gl_account.view`, `gl_account.create`, `gl_account.update`, `gl_account.submit`,
`gl_account.approve`, `gl_account.deactivate`, `fiscal_period.view`, `fiscal_period.open`,
`posting_rule.view`, `accounting_report.view`. A fresh deployment can therefore set up a chart of
accounts and open a fiscal period without any user holding operational or break-glass accounting
rights.

`gl_account.approve` is included on purpose.
`V4__grant_local_admin_invite_approve_and_seed_checker.sql` already seeded a second bootstrap
actor, `local.checker`, holding the same `local-admin` role, precisely because `local.admin` cannot
approve its own work. Granting approval here makes the maker-checker chart-of-accounts flow
exercisable out of the box with two distinct actors — which is exactly the gap `V4` existed to
close. Withholding it would have reintroduced that gap for accounting.

### Existing Tenants

Being honest about the limit: `grantPermissions` runs only inside `createDefaultRoles`, which is
called only from `OrganisationProvisioningService.approveProvisioning`. **No already-`ACTIVE`
organisation receives the new accounting codes.** Newly approved organisations get them; existing
ones do not.

Today that is not a live problem. The only `ACTIVE` organisations in any deployment are `PLATFORM`
(seeded by `V2`) and `FINAXIS-LOCAL` (seeded by `V3`), and `V5` handles both explicitly — the
platform superset grant for the first, the ten configuration codes on `local-admin` for the second.

The general mechanism for a genuinely multi-tenant future is an idempotent,
platform-permission-gated `reseedOrganisationDefaults(organisationId)` command that re-invokes the
already-idempotent `createDefaultRoles`. That command is a follow-up, not part of issue #34, and it
is the right shape because `createDefaultRoles` already upserts the role and already grants
`ON CONFLICT DO NOTHING`, so re-running it is safe by construction.

Two alternatives were rejected:

- **A SQL backfill in `V5`.** It would have to create the organisation-local roles in SQL for every
  existing tenant, duplicating `OrganisationBootstrapDefaults` in a second place. That is the exact
  defect [ADR 0010](../adr/0010-greenfield-migration-reset-and-schema-rewrite.md) records: a
  migration granting to a role no migration creates, or naming a permission that was never seeded,
  silently grants nothing and nobody notices until an authorization check fails in production.
- **A Kotlin startup backfill.** Mutating authorization data at boot is invisible (it happens with
  no request, no actor and no audit context), effectively untestable in the form operators would
  actually run, and impossible to gate behind a permission because there is no principal.

## Runtime Enforcement

Nothing changes about how enforcement works; accounting simply extends the vocabulary.

- Controllers use permission authorities for coarse gates
  (`@PreAuthorize("hasAuthority('gl_account.view')")`, `@authz.hasPermission(...)`).
- Application services enforce resource-specific authorization through `AuthorizationService`, and
  every endpoint must carry an explicit application-layer permission check.
- Effective permissions resolve per membership and selected branch exactly as documented in
  [authorization model](authorization-model.md); branch-scoped role grants apply only when that
  branch is selected.

**Known gap, not fixed by issue #34.** `AuthorizationService.can(principal, code, resourceRef)`
checks only that `principal.organisationId == resourceRef.organisationId`;
`ResourceRef.branchId`, `ResourceRef.warehouseId` and `ResourceRef.ownerId` are carried but never
read. Accounting will need real branch-scoped resource checks — a branch trial balance, a
branch-scoped manual journal — so a principal scoped to one branch cannot act on another branch's
ledger merely by holding the code at tenant scope. That work is tracked as issue #52 and was
deliberately left out of #34, which changes no enforcement code at all.

## Tests

| Test | What it guards |
| --- | --- |
| `AccountingPermissionCatalogueTests` | The 26 codes exist, are `ACTIVE`, agree with `AccountingPermissions` in both directions, occupy a contiguous `41000000-…01`–`…26` block, collide with no foundation code, are fully held by `PLATFORM_SUPER_ADMIN`, are entirely absent from `PLATFORM_SUPPORT`, and appear on `local-admin` as exactly the ten configuration codes |
| `AccountingSeparationOfDutiesPolicyTests` | The dedicated maker and checker roles hold opposite sides of every approval pair, no default bundle carries a break-glass code, both accounting roles are provisioned at organisation approval, and every code named by a default bundle exists and is `ACTIVE` |
| `HighRiskOperationAuditCoverageTests` | Every HIGH/CRITICAL catalogue code maps to an audited action, every mapped action is reachable from production, and the `pendingEnforcement` ratchet still names only actions with no real call site |
| `FoundationSeedDataTests` | The exact 80-code catalogue, no duplicates, and the exact permission set on the bootstrap `local-admin` role |

The migration also enforces its own invariants in `DO $$` blocks — pre-conditions (the two platform
roles are `ACTIVE`, the `V3` bootstrap role is `ACTIVE`, `PLATFORM_SUPER_ADMIN` is not already
missing a permission, no accounting row pre-exists) and post-conditions (26 seeded, nothing
ungranted on `PLATFORM_SUPER_ADMIN`, exactly ten grants on `local-admin`). A mistake therefore
surfaces on deployment in every environment, not only in CI.

## Related Documents

- [Authorization model](authorization-model.md) — the platform-wide permission and role model.
- [ADR 0021](../adr/0021-accounting-permission-catalogue-and-privileged-operations.md) — the
  decision record behind this catalogue.
- [ADR 0020](../adr/0020-immutable-ledger-and-reversal-only-correction.md) — why an
  approved-but-unposted journal has no place in the model.
- [ADR 0010](../adr/0010-greenfield-migration-reset-and-schema-rewrite.md) — the seed-data defect
  this posture is designed to avoid repeating.
- [Accounting module boundary](../architecture/accounting-module-boundary.md) and
  [accounting foundation](../architecture/accounting-foundation.md) — where the accounting module
  starts and stops.
- [Accounting schema](../database/accounting-erd.md) — the designed, not-yet-created tables these
  codes will gate.
- [Audit logging](audit-logging.md) — how the audit actions in `AccountingAuditActions` will be
  recorded.
