# ADR 0028: A Platform Administrator As The Audited Checker Of A Tenant's Approvals

## Status

Accepted

Date: 2026-10-03

Amended by #208 (`V21__branch_approve_permission.sql`): the permission that approves a
branch is `branch.approve`; `branch.activate` is deprecated and no longer checked. This ADR's
branch rules (the window, the creator and submitter rules) are unchanged.

Amended by the branch amender rule: on both routes the approver of a branch is also never anyone
who amended it (has a successful `branch.update` on it), see the fourth bullet of decision 5. The
window and the creator and submitter rules are unchanged.

Amended by the tenant checker rule (#221): decision 5's creator, submitter and amender rules now
also hold for the tenant itself on its platform decision routes (`approve`, `reject`, `return`),
with tenant-named codes `lifecycle.approver_is_tenant_maker` and
`lifecycle.approver_is_tenant_modifier` (the amender is barred from approving and rejecting, not
from returning) and a `DENIED` audit row per refusal. A tenant is not a platform-checker item, so
decision 7's window does not apply to it; see ADR 0029's matching amendment.

Amended by #223: the window count of decision 7 now runs under the tenant's organisation row
lock, so two platform checkers can no longer both pass it in the same instant when the first
activates its item at once (decision 8, first bullet). An approval that waits for its identity
(the 202 path, decision 8, second bullet) still leaves the window open until the Keycloak job
activates it. The bound itself, and every other rule, is unchanged.

Resolves GitHub issue #153. Relaxes, in one named place, the maker-checker rule that
`UserProvisioningService.approveUser` and `BranchProvisioningService.activate` enforce. Does not
amend [ADR 0004](0004-membership-activation-notification-pipeline.md): the notification pipeline
is reused, not changed. Builds on
[ADR 0012](0012-keycloak-authentication-application-authorization.md) for the rule that the
application, never Keycloak, decides who may approve.

**Superseded in part by [ADR 0030](0030-mutation-permission-implies-view-permission.md).** The
sentence "Each route works with its one permission alone" and the permission-free read-back it
justifies (under "Routes and permissions") no longer hold: each route needs its permission **and
that permission's view** in the platform organisation, and reads its result back through the gated
query. The platform checker roles must hold the view codes too. Everything else in this ADR
stands. The change is in effect: the ADR 0030 rollout is implemented (steps 1 to 7), so the
platform checker routes check the permission and its view in the service and read back through the
gated query.

## Context

A tenant approved through the platform has exactly one user: the bootstrap `TENANT_ADMIN`, invited
by `SystemActor.ID`. Two tenant maker-checker rules then leave it with nothing it can finish:

- a membership cannot be approved by the actor that invited it (`approveUser`), so the
  administrator can invite a second person but never approve the invitation;
- a branch cannot be activated by the actor that created it (`BranchProvisioningService.activate`),
  so the administrator can draft and submit a first branch but never activate it.

A fresh tenant has no second actor, so both flows deadlock. Invitations need an `ACTIVE` branch
for every ordinary membership type, so the deadlock is not avoidable by ordering either.
`V4__grant_local_admin_invite_approve_and_seed_checker.sql` hides it locally by seeding a second
actor and says so in its own header.

Platform administrators could not step in. A platform-context caller is refused on every tenant
route (`CallerContextResolver.getTenantCaller`), and no platform route existed for membership
approval or for branch submission or activation. The one platform branch route that did exist,
`POST /platform/tenants/{id}/branches`, was no way out either: it checked `branch.create` in the
platform organisation **and** again inside the tenant, so it needed the platform administrator to
hold an `ACTIVE` membership in the tenant, an `ACTIVE` tenant, and a tenant role granting
`branch.create`. A platform administrator with no membership, or any administrator on a
`PROVISIONING` tenant, was refused although `ALLOWED_BRANCH_CREATION_STATES` includes
`PROVISIONING` (BG-18).

Three resolutions were on the table: a second approver created at bootstrap, an exemption for the
bootstrap administrator's first invitation, or a platform-context checker. Product chose the third.

## Decision

**A platform-context actor may approve a tenant's pending membership and submit and activate a
tenant's pending branch, as an audited checker, when it holds the matching permission in the
platform organisation, and only while the tenant has no self-sufficient approvers of that kind
(point 7 below).**
Everything else about the tenant's maker-checker rule is unchanged: tenant users still cannot
approve their own invitations or activate their own branches.

### When the platform checker is open

"First approvals" is shorthand for two separate bounds, one per kind of item, each checked in the
application services inside the approving transaction (point 7): a pending **membership** can be
checked by the platform only while the tenant has no `ACTIVE` member beyond its bootstrap
administrator, and a pending **branch** only while it has no `ACTIVE` branch beyond the bootstrap
head office. The kinds are independent: a tenant with its own active member but no active branch of
its own can still have a branch checked, and the other way round. The full rule, for each platform
checker step:

1. The caller is in the reserved platform organisation context. A tenant-context caller is refused
   whatever authorities it holds.
2. The caller holds the step's permission **in the platform organisation**, checked in the
   application services (`UserProvisioningService.approveUser` and `BranchProvisioningService`,
   not only the controller): `user.approve` for a membership, `branch.approve` to activate a
   branch, `branch.create` to create or submit one. Nothing is checked in the tenant, so no tenant
   membership is needed.
3. The permission check comes first. An id that does not belong to the **path tenant** reads as
   404 only after it, so a caller without the permission cannot probe for existence, and an id of
   tenant A under tenant B's path is never operated on.
4. The tenant is `ACTIVE` to approve a membership or activate a branch, and `ACTIVE` or
   `PROVISIONING` to create a branch (the existing `ALLOWED_BRANCH_CREATION_STATES`).
5. The item is the checker's to approve: the membership is `PENDING_APPROVAL` and the branch
   `PENDING_APPROVAL`, and **the checker is neither the maker nor the beneficiary**:
   - the platform actor is not the item's maker (`created_by` of the branch, the inviter of the
     membership), exactly as for a tenant user, so a platform administrator who drafted a branch
     cannot activate it and a second platform administrator must;
   - the platform actor is not the **beneficiary**: `approveUser` refuses, in every scope, an
     approver who is the invited user. A tenant maker can invite any existing account by email,
     including a platform administrator's, and without this rule that administrator could approve
     its own membership (in any role) through the platform route;
   - the platform actor did not **submit** the branch: a platform administrator may submit a draft
     the tenant never put up for approval, but cannot then also activate it, so no single platform
     actor requests and approves. This applies to platform activation only; the tenant route refuses
     the creator and anyone who amended the branch (next bullet), and a tenant submitter is not
     refused, which is the documented tenant behaviour;
   - the approver did not **amend** the branch, on the tenant and the platform route alike: a
     returned draft is amendable by anyone holding `branch.update`, so without this rule one
     person could amend, resubmit and approve someone else's draft. An amender is anyone with a
     successful `branch.update` audit event on the branch, not just the latest one: a "latest
     amender only" rule can be laundered by any later PATCH, even one that changes nothing. "Ever"
     is exactly "any amender of the version being approved", because a branch is amendable only in
     `DRAFT` or `ACTIVE` and `ACTIVATE` is reachable only from `PENDING_APPROVAL`, so every
     `branch.update` precedes the pending version. The row's `updated_by` is not used: every status
     transition stamps it too (a checker's return, a submit), so at approval time it is always the
     submitter. A checker who only returned the draft is not an amender and may approve a later
     resubmission. The refusal is `403` `lifecycle.approver_is_branch_modifier`, judged at the same
     point as the creator rule. The cost: an amender whose edit was later overwritten still cannot
     approve, so a tiny tenant may need a third person, or the platform checker inside its window;
   - the rule reads `branch.update` rows of the audit trail, so any future audit retention or purge
     job (the `audit_retention_days` setting) must exclude the `branch.update` events of branches
     that are not `ACTIVE` or terminal, or the rule fails open. A durable column on `branch` is the
     long-term alternative;
   - all comparisons are on user id, not on request context, so a person who is both a platform
     administrator and a tenant member cannot approve what they invited by switching context.
6. The path tenant is a real tenant: the platform organisation itself is never a valid
   `{tenant_id}` for these routes (404, after the permission check), and submitting a branch obeys
   the same tenant-state rule as creating one (`ACTIVE` or `PROVISIONING`, otherwise 409). The
   tenant's *own* lifecycle routes (`/platform/tenants/{tenant_id}/suspend`, `/deprovision`,
   `/approve` and the rest) answer the platform organisation with a **409**
   `lifecycle.platform_organisation_protected` instead, since it is the resource addressed and not
   a parent of one (#205; see the authorization model).
7. **The bound.** Each checker step is available only while the tenant has no self-sufficient
   approver of that kind, counted as follows, and answers **409
   `lifecycle.platform_checker_closed`** otherwise, changing nothing:
   - membership activate: the tenant has no `ACTIVE` membership whose creator is not the system
     actor. The bootstrap administrator is invited by `SystemActor.ID`, so it does not count; the
     first person the tenant brings in and has activated does;
   - branch submit and activate: the tenant has no `ACTIVE` branch whose creator is not the system
     actor. The bootstrap seeds the head office with `SystemActor.ID` as creator (and assigns the
     administrator to it), so it does not count; the first branch the tenant creates and has
     activated does.
   The creator is the marker on purpose. Branch type and code are free text a tenant can set
   (`HEAD_OFFICE` included), whereas no API can set `created_by` to the system actor, so a tenant
   cannot disguise its own member or branch as bootstrap data, or the reverse. A row with no
   creator counts as the tenant's own, which errs towards closing. Draft creation, including
   platform branch creation (BG-18), is not a checker step and is not bounded; neither is the
   tenant's own approver. Only `ACTIVE` rows count, so the bound is **not monotonic**: if the
   tenant suspends or revokes its own non-bootstrap members, or suspends or closes its own
   branches, the count falls and the platform checker **reopens** for it.
8. **Windows the bound does not close.** The count is one read inside the approving transaction,
   taken under the tenant's organisation row lock (`FOR NO KEY UPDATE`, #223), and a membership is
   not `ACTIVE` the moment it is approved:
   - two platform administrators acting in the same instant **no longer** both pass the count: each
     platform checker step (membership activate; branch submit, activate and checker return) locks
     the organisation row before it counts, so the second waits for the first to commit and then
     counts what it committed. That closes the window for the second only when the first's item
     became `ACTIVE` in that commit: a membership approved on the 202 path (next bullet) is still
     `PENDING_APPROVAL`, so the second checker passes too, as before. The lock is the one every
     tenant provisioning decision already takes first (ADR 0029, "Locking rule"), and a checker step
     locks none of the tenant's rows before it, so the order is the same everywhere. Holding it, a
     checker step also waits for, and is waited on by, every organisation provisioning decision
     (`lockOrganisation`: amend, submit, approve, reject, return), every other organisation
     transition (its `UPDATE` of the row takes the same lock), a tenant branch re-parenting
     (`lockBranchHierarchy`, the same row and mode) and an accounting
     posting's `FOR SHARE` read of the base currency (`lockBaseCurrencyCode`). None of them takes a
     lock a checker step then needs, so the waits cannot form a cycle, and inserts of rows that
     reference the organisation (`FOR KEY SHARE` through their foreign keys) are not blocked. Among
     approvals it serialises platform checkers only: the **tenant's own** approval does not take
     it, so a tenant member activating someone in the same instant as a platform checker can still
     leave both committed (each is individually maker-checked and audited, and the tenant-side
     approver is exactly what the bound is waiting for);
   - approving a membership for a user with no Keycloak identity (the normal new invitee) returns
     202 and leaves it `PENDING_APPROVAL` until the Keycloak job activates it, and the bound stays
     open meanwhile, so a tenant administrator can invite N such people and the platform can
     approve all N before the first of them turns `ACTIVE`;
   - if drafts A and B are both submitted and the platform activates A, the bound closes for
     branches and B stays pending until the tenant has a non-creator member of its own holding
     `branch.approve` (or the tenant suspends A and reopens the bound).
   These are accepted: each step is still individually authorised, maker-checked, beneficiary-
   checked and audited, and the bound limits standing exposure rather than being a rate limit.
9. **A person who is both a platform administrator and an `ACTIVE` member of the tenant** may submit
   a tenant's draft on the platform route and activate it on the tenant route (if it did not create
   it). That is consistent with the tenant rule, which refuses the creator and anyone who amended
   the branch but not the submitter, and is not a new path; the
   platform-route submitter rule (above) binds only platform activation.
10. **The initial administrator cannot approve the tenant that names them.** The bootstrap approves
    its administrator's membership in the name of the platform user who approved the tenant, and
    `inviteUser` reuses an existing account found by email, so a tenant draft naming the approver's
    own email as its initial administrator would make the beneficiary rule fail the bootstrap
    asynchronously and leave an `ACTIVE` tenant with no administrator. `approveProvisioning`
    therefore refuses it up front, before any state change: when the draft's administrator email
    already belongs to an account and that account is the approver, the approval answers 403
    `lifecycle.approver_is_initial_administrator`. If the account does not exist yet there is
    nothing to compare. The bootstrap is deliberately **not** exempted from the beneficiary rule.
    The refusal, like the requester and submitter rule, is judged after `approveProvisioning` has
    locked the organisation row, so it reads the submitter and the named administrator of the
    submission it then approves, not those of one a concurrent return, amend and resubmit has
    since replaced (ADR 0029, "Locking rule").

### Routes and permissions

| Route | Permission | Scope |
| --- | --- | --- |
| `POST /platform/tenants/{tenant_id}/memberships/{membership_id}/activate` | `user.approve` | PLATFORM |
| `POST /platform/tenants/{tenant_id}/branches/{branch_id}/activate` | `branch.approve` | PLATFORM |
| `POST /platform/tenants/{tenant_id}/branches/{branch_id}/submit` | `branch.create` | PLATFORM |
| `POST /platform/tenants/{tenant_id}/branches` (existing) | `branch.create` | PLATFORM |

> **Superseded in part by [ADR 0030](0030-mutation-permission-implies-view-permission.md):**
> each route also needs the view code of its permission, and reads back through the gated query.

Each route works with its one permission alone. The membership or branch it returns is read back
without a second permission gate (`membership.view`, `branch.view`) inside the same transaction,
because that gate would otherwise reject a role holding only the advertised permission after the
mutation and roll it back. The read stays bound to the path tenant, so an id of another tenant is
still 404, and the checker already holds authority over the resource it has just changed; the
response shape is unchanged. The optional bodies of branch submit and activate are validated like
the tenant routes' (`reason` at most 500 characters): both route families mark the body `@Valid`
(the tenant branch routes did not until #206, which also added a guard test that fails any
constrained `@RequestBody` that is not).

No new permission code is introduced, so there is no migration: `PLATFORM_SUPER_ADMIN` already
holds all three. `PLATFORM_SUPPORT` holds none and cannot use any of the routes.

The routes are thin. They call the existing `UserProvisioningService.approveUser` and
`BranchProvisioningService.createDraft`, `submitForApproval` and `activate` with an
`ActingScope.PLATFORM` flag. The flag changes only which permission is asked of which
organisation (and, for membership approval, adds the tenant-state check, the bound and the audit
marker). It does not touch the FSM, the guards, the transition log or the event factories, so a
membership approved by a platform checker raises the same
`finaxis.lifecycle.membership.activated` event as a tenant approval and reaches the same
notification pipeline.

### Audit

Every platform checker step is attributed to the **platform actor** and the **path tenant**, in
that tenant's own audit log, so it is visible to the tenant administrator and, through
`GET /platform/tenants/{tenant_id}/audit-events`, to platform auditors:

- the FSM writes its usual transition row (`membership.activate`, `branch.activate`), whose actor
  is the request's actor, now the platform administrator;
- membership approval writes its usual `user.approve` row with metadata
  `checkerScope = PLATFORM`;
- branch activation writes an additional `branch.activate_as_platform_checker` row with the same
  metadata, because branch activation has no explicit approval row to mark;
- branch submission through the platform route likewise writes an additional
  `branch.submit_as_platform_checker` row with the same metadata, so a platform submission is
  distinguishable from the tenant's own `branch.submit` transition row, which carries no marker.

A reviewer can therefore list every approval a tenant did not make for itself by filtering on
`checkerScope = PLATFORM` or on that action.

### Platform branch creation (BG-18)

`POST /platform/tenants/{tenant_id}/branches` now authorises with the platform permission alone,
through the same flag. It no longer needs a tenant membership or a tenant role, and it works on a
`PROVISIONING` tenant. A tenant that does not exist is 404 and a tenant that is neither `ACTIVE`
nor `PROVISIONING` is 409. The tenant-context `POST /branches` is unchanged.

## Alternatives rejected

- **Seed a second approver at bootstrap.** It would create a standing identity in every tenant
  that nobody asked for, with approval rights over that tenant, whose credentials, lifecycle and
  offboarding someone would then own. It also needs an email and a Keycloak identity the tenant
  has not chosen, and it fixes the deadlock only until that identity is revoked. `V4` already shows
  the cost: a fixture actor that exists to be the second approver.
- **Exempt the bootstrap administrator from the rule.** It turns segregation of duties off for the
  most privileged tenant user, for its first and most consequential invitation, with no second
  pair of eyes at all. The platform checker keeps two actors on every approval.
- **Time-box the exemption** (the issue's third variant). A clock is a worse bound than a second
  actor: it expires whether or not the tenant has onboarded anyone, and it leaves the tenant
  deadlocked again the first time its only approver is suspended after the window.
- **Close the platform route once the tenant has another *eligible* approver.** Whether another
  tenant user *can* approve depends on effective permissions: role grants at tenant and branch
  scope, which branch is selected, direct `ALLOW` and `DENY` overrides, and membership state, all
  cached. Reproducing that in a query is neither simple nor reliable, and a wrong "an approver
  exists" answer re-creates the deadlock. The adopted bound counts members and branches instead
  of reasoning about permissions: it is cruder (a second member who cannot approve anything still
  closes the route) and in exchange it is one read per step and testable exhaustively.
- **No bound at all** (the first version of this ADR). A standing platform approver over every
  tenant for its whole life is more than the deadlock needs, and the user chose to close it.
- **Platform-only invitation flow.** It moves the maker role to the platform as well, which makes
  the platform the sole actor in a tenant's access control. The tenant keeps the maker role.

## Consequences

- A platform administrator holding `user.approve` or `branch.approve` can check a tenant's
  pending item only while the tenant has no own `ACTIVE` member (memberships) or own `ACTIVE` branch
  (branches), and only if it did not make the item, was not its beneficiary and did not submit
  it. The checker neither chooses the
  invitee, the roles nor the branch (the tenant maker did) and cannot approve its own access. The
  residual risk is collusion between a tenant maker and a platform checker during onboarding,
  which is the risk of every two-person control and is what the audit trail is for.
- **Accepted consequence: the checker is closed while the tenant has its own approvers, and can
  reopen.** Once a tenant has its own `ACTIVE` member (or branch) the platform checker is closed to
  it for that kind of item. If that tenant's only approver later leaves or is suspended, the
  tenant's own `ACTIVE` count falls and the platform checker reopens for it, but a tenant whose
  non-bootstrap members stay `ACTIVE` while none of them can approve anything is not rescued by
  this change: that is a support and operations matter (for example reinstating or inviting an
  approver through a controlled procedure) and is deliberately not solved here. A monotonic "ever
  approved" bound was considered and not built: it would close the route for good even after the
  tenant lost every approver.
- The tenant's own maker-checker is untouched, and a platform actor is bound by it too. Platform
  roles that must not check tenant work simply do not hold the permissions.
- `PLATFORM_SUPER_ADMIN` is now a role that can finish a tenant's first onboarding by itself, given
  a second platform administrator. A tenant already past the bound when this ships is unaffected
  and gets no platform checker.
- Approving a membership for a user with no identity yet still returns 202 and completes
  asynchronously through the Keycloak provisioning pipeline, whose own activation is unchanged.
- `approveUser` now answers 404 for a membership that is not in the organisation and 409 for one
  that is not pending, where it used to raise an unmapped `IllegalArgumentException` (HTTP 500).
  This applies to the tenant route too and is a correction, not a policy change.
- The change adds no table, no column and no durable effect of its own, so it registers no
  `FinancialTransactionAtomicityFixture` probe: it is not a financial write path
  ([ADR 0018](0018-financial-transaction-atomicity-invariant.md)). Every effect it has already
  commits or rolls back with the existing service transaction.
