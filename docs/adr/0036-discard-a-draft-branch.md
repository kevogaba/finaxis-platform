# ADR 0036: Discard A Draft Branch With A Transition Of Its Own

## Status

Accepted, **implemented** in the release containing this change (issue #222).

Date: 2026-10-25

Builds on [ADR 0029](0029-approval-model-per-resource-extensions.md) (the branch return and
withdraw edge that leaves a `DRAFT`), [ADR 0013](0013-foundation-lifecycle-state-machines.md)
(one name per source state), [ADR 0030](0030-mutation-permission-implies-view-permission.md) (the
permission and its view) and [ADR 0031](0031-audit-one-row-per-transition-and-explicit-branch.md)
(the FSM row is the audit record). It supersedes nothing.

## Context

A branch draft has exactly two ways out. `SUBMIT` sends it to approval, and `SUSPEND_DRAFT`, an
internal edge reachable only through the tenant deprovisioning cascade
(`OrganisationProvisioningService`), parks it in `SUSPENDED`. `BranchProvisioningService.suspend`
always uses `SUSPEND`, which needs `ACTIVE`, and `close` accepts only `ACTIVE` or `SUSPENDED` and
answers `409` otherwise. A draft that was created by mistake, or that its maker no longer wants, so
stays in every list for good: ADR 0029 recorded the same dead end for a withdrawn branch and fixed
it with the amend route, which helps a draft that is wanted but not one that is not.

Two facts shape the answer:

- **A draft can already become `CLOSED`, but only by way of the tenant.** The tenant
  deprovisioning cascade (`OrganisationProvisioningService.branchSuspensionTransition`, called
  only from `deprovisionBranches`; the organisation `suspend` does not touch branches) parks a
  draft in `SUSPENDED` with `SUSPEND_DRAFT`, and `close` accepts a `SUSPENDED` branch
  (`CLOSE_SUSPENDED`). `JooqFoundationLifecyclePersistence.openedOn` already documents "suspended
  from a draft, then closed": a branch that never opened is a legal `CLOSED` row, with
  `opened_on` null. What is missing is that end state for one branch of a tenant that stays
  active, without a deprovisioning and the suspension it brings.
- **Every unique code outlives its row's status.** `uq_branch_organisation_code` is
  `UNIQUE (organisation_id, branch_code)` and `branchCodeExists` ignores status, so a closed branch
  keeps its code. The owner's rule for every terminal state (ADR 0035 for roles, ADR 0029 for the
  `REJECTED` tenant) is that the code stays taken, with no partial unique index.

## Decision

1. **A new edge, `DISCARD`: `DRAFT` to `CLOSED`.** It is not `CLOSE` with a second source, because
   ADR 0013 keeps `(state, transition)` a function. A name of its own also makes the transition log
   (`branch_transition_log.transition_name`) and the audit action (`branch.discard`) say that the
   branch never opened, where a `CLOSE` or `CLOSE_SUSPENDED` row says it was closed. The edge
   carries the same `branchClosureGuard` as the other two closes, so a draft with an `ACTIVE`
   child (or, in principle, an active assignment) is refused with a `409` whose message says
   "discarding" rather than "closing".
2. **No new state.** `CLOSED` already exists, is already terminal but for `ARCHIVE`, and is already
   what a never-opened branch can become once a deprovision has suspended it. ADR 0029 turned
   down a terminal `REJECTED` branch state on cost, since a state widens `chk_branch_status` and
   every status `when`; the same cost argument rules out a `DISCARDED` state, and it would buy
   nothing a transition name does not.
3. **No hard delete.** The branch row is referenced by `branch_transition_log`, `audit_event`
   (`branch_id`), and potentially by assignments, roles and journals; the audit trail must outlive
   it, as ADR 0005 (organisations) and ADR 0035 (roles) already hold for their own aggregates.
4. **The code stays taken.** A discarded branch keeps its `branch_code`, so creating a branch with
   it is the ordinary `409 conflict`, and that response is still true ("conflicts with the current
   resource state"), so no message changes. The way to reuse a name is a different code, as for a
   closed branch.
5. **No externalized event.** `CLOSE` and `CLOSE_SUSPENDED` publish
   `finaxis.lifecycle.branch.closed` because a downstream consumer holds an open branch. A branch
   that never opened was never announced as active, so there is nothing to retract: `DISCARD`
   declares `internalEventFactories()` only, like `SUSPEND_DRAFT`, `SUSPEND_PENDING_APPROVAL` and
   `RETURN_FOR_CHANGES`. The branch's earlier `approval-requested` event, if it was ever submitted
   and returned, is unaffected and, as for a return, has no exit event.
6. **The route is `POST /api/v1/branches/{branch_id}/discard`.** The body is `{ "reason": string }`,
   required, 3 to 500 characters, the same as `return` and `close`, persisted as `status_reason`,
   in the transition log and on the audit row. It is idempotent like its siblings.
7. **Permission `branch.create`, no new code and no migration.** Discarding is the undoing of
   creating, the same maker-side act as `submit` and withdraw, and the catalogue already holds it
   (with `branch.view` as its view, checked centrally by ADR 0030). `chk_branch_status` already
   allows `CLOSED`, so no schema changes. It is checked against the target branch, before any
   existence signal; since a branch-scope grant counts only while its branch is `ACTIVE` (#242), a
   draft is discarded by a tenant-wide `branch.create` holder.
8. **Refusals, in order.** Permission (`403`); branch not in the path organisation (`404`); the
   organisation row is locked, then its state must be `ACTIVE` or `PROVISIONING` (`409`, `404` for
   no such organisation), the rule for creating a branch; the **head office** is refused
   (`409 lifecycle.head_office_branch_protected`); then the FSM: any source state but `DRAFT` is a
   `409`, and the closure guard refuses a draft with an active child. The head office is excluded
   because the bootstrap seeds it as a draft and tenant approval submits and activates it
   (`activateHeadOffice`), so discarding it would leave a tenant that cannot be approved; it is
   found by its reserved code through the new `BranchLifecycleStore.isHeadOffice`.
9. **One audit row.** The FSM writes `branch.discard` (ADR 0031) with the reason and the actor; no
   service row repeats it, because there is no fact it could add (a withdrawal needs one for the
   intent, a discard has a single intent).

## Relation to `SUSPEND_DRAFT` and `CLOSE_SUSPENDED`

They are untouched. The deprovisioning cascade still parks a draft in `SUSPENDED`, and a branch
that was a draft can still be closed from there by `CLOSE_SUSPENDED`, which **does** announce
`finaxis.lifecycle.branch.closed` for it (existing behaviour, not changed here, and the one
inconsistency `DISCARD` does not copy). `DISCARD` is the direct, caller-driven route to the same
state, from `DRAFT` and without the suspension. A discarded branch is `CLOSED` with `closed_on`
the tenant's business date, stamped by the same `saveBranch` as any close, and `opened_on` null.

## Consequences

- A tenant can put away a draft it does not want, with a record of who and why. The row, its
  transition log and its audit rows stay; the code stays reserved.
- `CLOSED` now has three ways in: `CLOSE`, `CLOSE_SUSPENDED` and `DISCARD`. A report that counts
  closed branches should read `opened_on` to tell the never-opened ones; `transition_name`
  distinguishes them exactly.
- A discarded branch can be archived (`CLOSED` to `ARCHIVED`) like any closed one.
- **Known limitation, shared with close.** The guard counts only `ACTIVE` children. A `DRAFT`
  or `PENDING_APPROVAL` child under a discarded parent is left under a `CLOSED` parent, and
  activating it later is not refused; the same gap the closure guard has for `close`
  (see `lifecycle-fsm.md`, "Known limitation"). Closing it is out of scope here.
- `BranchLifecycleStore` gains `isHeadOffice`; nothing else in the ports changes.

## Not covered

- Discarding a draft from the platform routes (`/platform/tenants/{tenant_id}/branches/...`). The
  platform checker window exists to unblock a first approval, which discard does not need; a
  platform operator acts through the tenant when needed. Add a route if a case appears.
- Undoing a discard. `CLOSED` is terminal but for `ARCHIVE`; a tenant creates a new draft under a
  new code.
- Freeing the code. That needs a partial unique index, which the owner has ruled out for terminal
  states.

**Update (#176 documentation pass):** this ADR first described `SUSPEND_DRAFT` as reachable
through "the tenant-suspend and deprovision cascade". Only deprovisioning cascades to branches
(`deprovisionBranches`, then `branchSuspensionTransition`); the organisation `suspend` moves no
branch, and `branchesForDeprovisioning` skips `CLOSED` and `ARCHIVED` rows. The sentences above are
corrected; the decision is unchanged.
