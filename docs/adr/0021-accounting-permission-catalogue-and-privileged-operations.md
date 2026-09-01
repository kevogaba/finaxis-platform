# ADR 0021: Accounting Permission Catalogue And Privileged Operations

## Status

Accepted

Date: 2026-08-31

## Context

Phase B of the accounting work builds the chart of accounts (#38), the fiscal-period lifecycle
(#39), reversal semantics (#43), posting-rule versioning (#45), control-account reconciliation
(#46) and manual journals (#48). Every one of those needs permission codes, and the platform's
existing habit — add a code in the migration that introduces the feature — has already produced one
documented defect: `V8` in the pre-reset history granted three business-date permissions to every
role coded `TENANT_ADMIN`, a role no migration creates (application code creates it at
tenant-provisioning time), so the grant was a silent no-op on a fresh database — see ADR 0010.
Adding twenty-odd accounting codes across six feature migrations would reproduce exactly that
failure mode six more times, and would also mean nobody ever reviews the accounting authorization
vocabulary as a whole.

GitHub issue #34 therefore seeds the entire accounting permission catalogue up front, before the
accounting schema exists, so the vocabulary is designed once and reviewed once. That ordering
creates its own honesty problem, addressed in Consequences: codes exist before the behaviour that
enforces and audits them.

Two further questions had to be settled before writing the codes down. First, how coarse should the
vocabulary be — one code per operation produces a catalogue nobody can administer, one code per
aggregate produces one nobody can build a control with. Second, and more subtly, whether separation
of duties is a property of permissions or of actors. Getting that backwards would have produced a
permission split that looks like a control and enforces nothing.

## Decision

**Twenty-six codes under a new `accounting` module code.**
`V5__accounting_permission_catalogue.sql` is reference data only — no tables, no columns, no
constraints — and raises the platform catalogue from 54 codes to 80. The existing module codes
(`tenant`, `branch`, `iam`, `audit`, `settings`) name the owning capability area; issue #31 created
a real `com.finaxis.platform.accounting` Modulith module for `accounting` to name. The full table,
with names, risk levels and identifiers, is in
[accounting authorization](../security/accounting-authorization.md).

**Identifier blocks are per-domain, not shared.** `V2` froze `40000000-0000-0000-0000-0000000000NN`
as the foundation block and left gaps at `…26`–`…29` and `…34`–`…39`. Accounting takes
`41000000-…`, contiguous from `…01` to `…26`, and every future domain takes its own `4X000000-…`
block rather than filling V2's gaps. Interleaving domain codes into the foundation numbering makes
`ORDER BY id` unreadable and destroys the property that an identifier alone answers "whose code is
this?".

**The vocabulary is deliberately folded where a second code would gate nothing.** `activate` folds
into `approve` for both the chart of accounts and posting rules, because the FSM has a single
`PENDING_APPROVAL → ACTIVE` transition and a separate code would create a state where the holder of
`approve` cannot finish the act they were authorized to perform. Fiscal year and period share four
codes, because a year is a container for its periods and no realistic role closes one but not the
other. Trial balance, GL ledger, financial statements and drill-down share
`accounting_report.view`, being the same posted data at different aggregations.

**`post` folds into `journal.approve`** — the only code the issue's own list asks for that was
deliberately removed. Approving a manual journal posts it, in the same transaction. An
approved-but-unposted journal is a promise the ledger cannot audit: the trial balance disagrees
with the journal register for an unbounded window and nothing records when, or whether, the promise
is kept. If deferred posting is ever genuinely needed, the correct model is a scheduled
`posting_request` with its own state, lifecycle and permission code — not an unposted journal
parked in an approved state. This follows from the immutable-ledger position in ADR 0020.

**Four splits are kept on purpose.** `create` stays separate from `update`, matching
`tenant.create`/`tenant.update_draft` and `role.create`/`role.update`. `submit` stays separate from
`create` everywhere, because that is what supports a three-eyes control — clerk prepares,
supervisor submits, approver approves — and removing `submit` collapses it to two eyes with no way
to express the third. `journal.post_prior_period` stays separate from `journal.approve` so
break-glass is independently grantable and revocable rather than inherited by every approver.
`reconciliation.run` (MEDIUM) stays separate from `reconciliation.resolve` (CRITICAL), because
running a proof is safe and accepting a break is the control failure itself.

**Runtime authorization stays permission-code based, and the two seeded accounting roles are
composable bundles.** `ACCOUNTING_OPERATOR` (maker: prepares and submits) and `ACCOUNTING_APPROVER`
(checker: approves and posts) are created in Kotlin at organisation-approval time by
`JooqOrganisationBranchProvisioningStore.createDefaultRoles`, from
`OrganisationBootstrapDefaults.ROLE_PERMISSIONS`. They appear in no migration and nothing anywhere
evaluates a role name.

**`PLATFORM_SUPPORT` receives nothing.** Platform staff must not read tenant financial data by
default; a support-escalation path into a tenant ledger is a separate, audited decision.
`PLATFORM_SUPER_ADMIN` still holds the whole catalogue, granted by a set-based
`INSERT … SELECT … FROM permission` repeated in `V5` because V2's equivalent ran once and cannot
pick up later codes.

**The bootstrap `local-admin` role receives exactly ten configuration codes** —
`gl_account.view`/`.create`/`.update`/`.submit`/`.approve`/`.deactivate`, `fiscal_period.view`,
`fiscal_period.open`, `posting_rule.view`, `accounting_report.view` — so a fresh deployment can
build a chart of accounts and open a period without anyone holding operational or break-glass
rights. `gl_account.approve` is included because `V4` already seeded a second bootstrap actor,
`local.checker`, holding the same role; with approval granted, the maker-checker chart-of-accounts
flow is exercisable out of the box with two distinct actors, which is precisely the gap `V4`
existed to close.

**Break-glass appears in no default bundle.** `fiscal_period.reopen` and
`journal.post_prior_period` are named by `AccountingPermissions.BREAK_GLASS` and are granted
deliberately, per tenant, never inherited from a bundle.

**Separation of duties is enforced by actor identity at the transition, not by splitting
permissions across roles.** The approver must differ from the submitter, as
`UserProvisioningService.approveUser` and `OrganisationProvisioningService.approveProvisioning`
already enforce and as the accounting FSM guards will. A role holding both `submit` and `approve`
is therefore legitimate: it means the holder may act on *different* records, not both sides of the
same one. `TENANT_ADMIN` deliberately holds both sides of the chart-of-accounts and posting-rule
approvals, and the bootstrap `local-admin` both sides of the chart-of-accounts approval, so a
two-actor flow works without an administrator first designing a role split. What the default
bundles guarantee is narrower and still worth guaranteeing: the two dedicated roles genuinely model
the split, and no bundle carries break-glass. Fiscal-period open and close are deliberately *not* a
maker-checker pair — they are two operations in a lifecycle, so one role legitimately holds both.

**Existing tenants are not backfilled, and the mechanism is named rather than improvised.**
`grantPermissions` runs only inside `createDefaultRoles`, called only from `approveProvisioning`, so
no already-`ACTIVE` organisation picks up the new codes. Today the only `ACTIVE` organisations are
`PLATFORM` (`V2`) and `FINAXIS-LOCAL` (`V3`), both handled explicitly by `V5`. The general
mechanism for a multi-tenant future is an idempotent, platform-permission-gated
`reseedOrganisationDefaults(organisationId)` command re-invoking the already-idempotent
`createDefaultRoles`; it is a follow-up, not part of #34. A SQL backfill was rejected because it
would have to create the organisation-local roles in SQL, duplicating
`OrganisationBootstrapDefaults` and creating a second source of truth — the exact defect ADR 0010
records. A Kotlin startup backfill was rejected because mutating authorization data at boot is
invisible (no request, no actor, no audit context), untestable in the form operators would actually
run, and impossible to gate behind a permission because there is no principal.

## Consequences

The accounting authorization vocabulary is now settled and reviewable as a whole, and Phase B adds
behaviour rather than codes. `FoundationSeedDataTests` pins the catalogue at exactly 80 codes, so
any later addition is a deliberate, reviewed change.

The honest cost: the 13 HIGH and CRITICAL accounting codes are seeded before the behaviour that
audits them exists. `HighRiskOperationAuditCoverageTests` previously guaranteed that every
HIGH/CRITICAL permission maps to an audit action *with a real production call site*; a constants
file naming an action now satisfies its text match without anything emitting it. Rather than let
that weakening pass silently, the test carries a `pendingEnforcement` ratchet naming each unwired
action against the issue that will wire it — `gl_account.approve` and `gl_account.deactivate`
(#38); `fiscal_period.open`, `fiscal_period.close` and `fiscal_period.reopen` (#39);
`journal.create_manual` and `journal.approve` (#48); `journal.reverse` (#43);
`journal.post_prior_period` (#35); `posting_rule.create`, `posting_rule.create_version` and
`posting_rule.approve` (#45); `reconciliation.resolve` (#46). The set can only shrink: the moment
an action gains a real call site the test fails until its entry is deleted, and it must reach empty
before the accounting readiness gate (#54) passes. This is a deliberate, temporary, self-closing
weakening of an existing guard. It is stated plainly here rather than presented as no weakening at
all.

A second gap is recorded rather than fixed. `AuthorizationService.can(principal, code, resourceRef)`
checks only that the principal's organisation equals the resource's organisation;
`ResourceRef.branchId`, `ResourceRef.warehouseId` and `ResourceRef.ownerId` are carried and never
read. Accounting will need genuine branch-scoped resource checks — a branch trial balance, a
branch-scoped manual journal — so that a principal scoped to one branch cannot act on another
branch's ledger merely by holding the code at tenant scope. That is a follow-up tracked as issue
#52, deliberately not changed in #34, which alters no enforcement code at all.

Because the bundles are conveniences rather than a security boundary, a tenant that wants a
stricter posture than `TENANT_ADMIN`'s must build it: `TENANT_ADMIN` holds `role.assign_permission`
and can grant itself anything in the catalogue. The default bundles are safe-by-default posture,
not containment.

Every accounting deployment now fails fast on a broken seed rather than degrading quietly. `V5`
asserts its own pre-conditions (both platform roles `ACTIVE`, the `V3` bootstrap role `ACTIVE`,
`PLATFORM_SUPER_ADMIN` not already missing a permission, no pre-existing accounting row) and
post-conditions (26 codes seeded, nothing ungranted on `PLATFORM_SUPER_ADMIN`, exactly ten grants
on `local-admin`), so a mistake surfaces in every environment on deployment, not only in CI.
