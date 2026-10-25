# ADR 0038: Close-Of-Business Readiness And Pending-Approval Counts

## Status

Accepted, **implemented** in the release containing this change (issues #168 and #169).

Date: 2026-10-25

Builds on [ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) (who may approve what,
and never the maker), [ADR 0029](0029-approval-model-per-resource-extensions.md) (per-resource
approval, no inbox), [ADR 0030](0030-mutation-permission-implies-view-permission.md) (a mutation
implies its view; reads are gated) and the accounting module boundary
(`docs/architecture/accounting-module-boundary.md`). It supersedes nothing and adds no migration
and no permission code.

## Context

Two questions from the frontend have no answer today, and both are questions about work that is
already stored, not about new state:

- **#168.** An operator about to start close-of-business has no way to ask whether anything is
  still open. `startCob` and `completeCob` check one thing, the row status (`OPEN` and `CLOSING`),
  so a tenant with manual journals waiting for approval starts its close with nothing said.
- **#169.** A checker cannot tell, without opening every list, whether anything waits for them.
  Each resource lists its own `PENDING_APPROVAL` rows, but a badge needs a number.

## Decision

### Close-of-business readiness

1. **`GET /api/v1/tenant/business-date/cob/readiness`** returns one object, so it is not paginated:
   `{business_date, status, ready, checks: [{code, severity, status, detail, count?}]}`, with the
   business date as `dd-MM-yyyy`. `severity` is `BLOCKING` or `ADVISORY`; `status` is `PASS`, `FAIL`
   or `WARN`. A `BLOCKING` check can `FAIL`, an `ADVISORY` one can only `WARN`, and `ready` is true
   when no `BLOCKING` check failed.
2. **Five checks, in this order.**

   | Code | Severity | Fails or warns when |
   | --- | --- | --- |
   | `business_date_open` | `BLOCKING` | the business date row is not `OPEN` |
   | `organisation_active` | `BLOCKING` | the organisation is not `ACTIVE` |
   | `manual_journals_pending_approval` | `BLOCKING` | any manual journal is `PENDING_APPROVAL` (`count`) |
   | `manual_journals_draft` | `ADVISORY` | any manual journal is `DRAFT` (`count`) |
   | `reconciliation_breaks` | `ADVISORY` | a control account's **latest** run is a `BREAK` (`count`) |

   The pending journals block because they are the one open item that changes the ledger once
   decided: closing around them leaves a posting to land in a closed day or to be backdated. A draft
   changes nothing until it is submitted, so it only warns. A break warns because it is a finding
   about the books to resolve, not a condition on closing them.
3. **The reconciliation count is latest-run-per-control-account, not a count of `BREAK` rows.** A
   break is superseded by a later matching run, and a resolved break is no longer a `BREAK` row at
   all, so counting rows would keep reporting evidence that the tenant has already answered. The
   latest run is the one with the greatest `as_of_date`, then `id` (a time-ordered UUIDv7), the
   order `idx_control_account_reconciliation_run_account_date` already has. One control account per
   sub-ledger class is allowed (`uq_gl_account_control_kind`), which bounds the number of probes.
   Runs for different branch scopes of one account supersede each other here; the check is advice,
   and a finer key would need a decision about which scope is the account's verdict.
4. **Accounting facts come through a new accounting-owned port.**
   `AccountingCloseReadiness.snapshot(organisationId)` returns
   `CloseReadinessSnapshot(pendingApprovalManualJournals, draftManualJournals, latestBreakRuns)`.
   It sits in the root package of `accounting` beside
   `AccountingDayRollover` and `AccountingLedgerActivity`, which is the package lifecycle's
   `@ApplicationModule` already allows, and a jOOQ adapter in `accounting/adapter/outbound/
   persistence` implements it with two tenant-filtered, non-locking statements. Lifecycle reads no
   accounting table, and accounting gains no dependency on lifecycle.
5. **Permission `cob.start`, which implies `business_date.view`.** Whoever may start the close may
   ask whether to. The catalogue already pairs the two (`permission_view_requirement`), so the
   adapter checks both centrally and a refusal is the named 403. It is tenant-wide, a `@GatedRead`
   method (`BusinessDateService.cobReadiness`) that asks the guard before its first store call, as
   `get` and `listHistory` do.

#### Why readiness is advisory only

`cob/start` and `cob/complete` do **not** consult it: no new `409`, no enforcement. Readiness is a
point-in-time snapshot read without a lock. A posting, a submission or an approval can land between
the read and the `startCob` that follows, so a gate built on it would give a false assurance in the
very window it exists to cover. The real guarantee already exists and is untouched: posting while
the date is `CLOSING` is refused (`accounting.business_date_not_open`), and `startCob` takes the
`business_date` row lock that in-flight postings hold (ADR 0026 and issue #125).

#### Later: enforcement

If the owner wants `startCob` to refuse a not-ready tenant, it needs its own decision, because it
turns advice into a lock-sensitive rule: the pending-journal count would have to be read under the
same transaction and lock as the status change, a backdated posting would have to be considered, a
documented override (with a reason, audited, behind a permission) would be needed for the tenant
that must close anyway, and `completeCob` would need the same treatment or an explicit statement
that it does not. The check list and the `BLOCKING` / `ADVISORY` split are written so that this is
a change to who reads them, not to what they say.

#### Checks that are not there, and why

- **No check for pending posting requests.** No committed `posting_request` is ever `PENDING`: the
  engine inserts and decides it in one transaction (`V7`, `PostingEngine`), so the check could only
  ever pass.
- **No check for background jobs or the audit stream.** No signal exists for either. A readiness
  answer that invented one would be a claim the platform cannot back.

### Pending-approval counts

6. **`GET /api/v1/tenant/approvals/pending-count`** returns `{memberships, branches}` and
   **`GET /api/v1/platform/approvals/pending-count`** returns `{tenants}`. Each count is one bounded
   `COUNT(*)`, tenant-filtered from the active context except the tenant count, which counts the
   `organisation` rows themselves (one row per tenant, so it is bounded by the number of tenants and
   by nothing a tenant writes). Accounting approvals are not included.
7. **Existing codes only, and a key is omitted when the caller lacks the authority.**

   | Key | Counted for | Implies | Scope |
   | --- | --- | --- | --- |
   | `memberships` | `user.approve` | `membership.view` | tenant |
   | `branches` | `branch.approve` | `branch.view` | tenant-wide only |
   | `tenants` (platform route) | `tenant.approve` | `tenant.view` | platform organisation |

   An omitted key is not a zero: a caller learns nothing about a resource it cannot act on. A tenant
   caller who may approve neither is refused with the named 403 (the first resource's code), as
   every other gated read is. A tenant administrator calling the platform route is refused: the
   route resolves the platform context and the check runs in the platform organisation.
8. **Branches count for tenant-wide holders only.** A branch-scope grant counts only while its
   branch is `ACTIVE` (#242), and a `PENDING_APPROVAL` branch has no `ACTIVE` grant, so only a
   tenant-wide `branch.approve` holder can approve a pending branch. The count asks
   `PermissionGuard.mutationBranchVisibility` for `branch.approve` and counts only on
   `AllBranches`, the same answer the branch routes' own combined lookups use.
9. **The caller's own items are excluded, and each count is an upper bound.** Memberships exclude
   those the caller invited (`created_by`), branches those the caller created, tenants those the
   caller requested or last submitted (`organisation_initial_administrator_bootstrap`). A tenant
   that has switched its maker-checker control off (ADR 0032) does not refuse the caller's own item,
   so for that tenant the count includes it. It does **not** replay every refusal: a branch the
   caller amended (`lifecycle.approver_is_branch_modifier`), a tenant the caller amended
   (`lifecycle.approver_is_tenant_modifier`), the platform checker's bootstrap-only window
   (ADR 0028) and an organisation that is not `ACTIVE` can still refuse an approval the count
   included. Replaying them would make a badge read as expensive as the approval, and an
   over-count is the safe direction: the caller opens the list, and the approval itself decides.
10. **`@GatedRead`, and no new surface in `ReadGateRules`.** `ApprovalCountQueryService` lives in
    `lifecycle.application.query`, takes the caller and asks the guard before its first store call.
    No allowlist entry was added.

#### Why there is still no inbox

ADR 0029's point stands: nothing needs one list of "things waiting for me" across resources. Each
resource already lists its own `PENDING_APPROVAL` rows, and a list with `size=1` returns
`total_items`, so the number for one resource was already one call away. What these routes add is
one call for the whole badge, the exclusion of items the caller could not approve anyway (a
resource list counts the caller's own submissions), and the platform tenant count. They are two
small reads over existing columns, not a new table or a new aggregate, and they add no notion of an
approval item that something else would then have to keep in step with each resource's status.

## Consequences

- An operator can see what is open before closing, and the answer is honest about being a snapshot.
  The readiness route is read-only and adds no lock, no write and no audit row.
- A tenant with no accounting data is `ready` with every check `PASS`.
- `AccountingCloseReadiness` is a second accounting-owned port lifecycle reads, with the same
  restraint as `AccountingLedgerActivity`: counts, never rows.
- The badge counts can over-count (point 9) but never reveal a resource the caller cannot approve.
- `docs/api/foundation-api.md`, `docs/operations/business-date.md`,
  `docs/security/authorization-model.md` and `scripts/local-smoke.sh` describe and exercise the
  three routes.

## Not covered

- Enforcing readiness in `cob/start` or `cob/complete`; see "Later: enforcement".
- A cross-resource inbox, or counts for accounting approvals (manual journals, posting rules, fiscal
  period reopen): readiness already reports pending manual journals for the one flow that cares.
- Counts for resources that need no approval, such as assignments (ADR 0029, point 2).
