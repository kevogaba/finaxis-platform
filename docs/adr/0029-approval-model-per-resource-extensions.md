# ADR 0029: Approvals Stay Per-Resource, With Remarks And Return-For-Changes

## Status

Accepted

Date: 2026-10-03

Amended by #208 (`V21__branch_approve_permission.sql`): the permission that approves a
branch is `branch.approve`; `branch.activate` is deprecated and no longer checked. This ADR's
branch rules (the window, the creator and submitter rules) are unchanged.

Amended by the branch amender rule (ADR 0028, decision 5): the approver of a branch is also never
anyone who amended it (has a successful `branch.update` on it), on either route, because a
returned draft is amendable by anyone holding `branch.update`. Returning, amending and
resubmitting are otherwise as written below.

Amended by the tenant checker rule (#221), which mirrors that branch rule for tenants: on the
platform tenant routes (the only routes that decide a tenant) the requester and the submitter of
the current submission can neither approve, reject nor return a pending tenant (403
`lifecycle.approver_is_tenant_maker`, replacing the generic `forbidden`), and anyone with a
successful `organisation.amend_draft` audit event on it can neither approve nor reject it (403
`lifecycle.approver_is_tenant_modifier`); an amender may still return it, as a branch amender may.
Each refusal is audited `DENIED` in its own transaction. This supersedes 3c's "Amenders are not
makers" and the consequence "Rejecting is wider than returning"; see
`docs/security/authorization-model.md` ("Tenant checker rule").

Resolves GitHub issue #178, the design gate of the approval-model gap #155, and fixes the scope of
its sub-issues #179, #180 and #181. Closes #182 as not planned. Builds on
[ADR 0002](0002-fsm-transition-infrastructure.md) and
[ADR 0004](0004-membership-activation-notification-pipeline.md) (the transition and event
pattern it reuses) and on
[ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) (the maker-checker exception it
must not disturb). It records decisions and a plan: **nothing described under "Decision" as
planned is implemented by this ADR**, and the code is unchanged by it. The branch return/withdraw
design (3b) is accepted and is implemented **after #165** in the same effort (see
"Sequencing").

**Amended in part by [ADR 0030](0030-mutation-permission-implies-view-permission.md).** Wherever
this ADR names the permission a route needs (3b "Permission", "Order of checks" and "Errors", the
#203 amendment, 3c "Route and permission", and the assignment permissions in point 2), that
permission is required **plus its view code at the same scope** (`branch.view`, `tenant.view`,
`branch_assignment.view`, `role_assignment.view`), and a missing view is a `403` that names it.
No new permission code is introduced by this. Nothing else in this ADR changes.
The amendment is in effect: the ADR 0030 rollout is implemented (steps 1 to 7), so each route
named above checks its permission and the view in the service and reads back through the gated
query.

## Context

Maker-checker exists today as hard-coded rules around per-resource transitions, not as a model:

- a membership is approved with `POST /tenant/memberships/{id}/activate`, which takes no body, so
  an approver cannot record a remark (`UserProvisioningService.approveUser`); the inviter and the
  beneficiary may not approve;
- a branch goes `DRAFT -> PENDING_APPROVAL -> ACTIVE` (`SUBMIT`, `ACTIVATE` in
  `BranchDefinitions.approval`, `FoundationLifecycleDefinitions.kt`); the creator may not activate
  it, and nothing leaves `PENDING_APPROVAL` except `ACTIVATE` and the tenant-deprovisioning
  `SUSPEND_PENDING_APPROVAL`, so a checker cannot send back a branch with a typo and a maker cannot
  withdraw it;
- a tenant goes `DRAFT -> PENDING_APPROVAL -> PROVISIONING -> ACTIVE`, or `REJECT` to a terminal
  `REJECTED`; the approver may be neither the requester nor the submitter
  (`OrganisationProvisioningService.approveProvisioning`) and the approve route takes no body, so
  `command.reason` is always null;
- branch and role assignments made after onboarding are effective at once
  (`BranchProvisioningService.assignUser`, `RoleManagementService.assignRoleToUser`);
- the tenant settings `require_maker_checker_for_user_invites` and
  `require_maker_checker_for_branch_creation` exist only in `TenantSettingCatalog.kt` (default
  `"false"`) and are never read at runtime: maker-checker is unconditional.

The prototype the frontend was built against assumed a generic approval request with return for
changes, resubmission, decision remarks and pending assignments. #155 asked whether to build that
model (Option A) or to extend the existing flows minimally (Option B), whether assignments become
pending, what to do with the two settings, and how this fits #153 (resolved by ADR 0028) and
#156 (maker exposure).

## Decision

### 1. Option B: minimal per-resource extensions

Approvals stay what they are: a lifecycle FSM per resource, with the maker-checker rule enforced
by the application service around the approving transition. We extend those FSMs with two
transitions and one optional field (point 3). We do **not** build a generic approval-request
resource.

Reasons:

- **Size.** A generic resource needs new tables (subject, proposed change, decisions, history), a
  polymorphic "apply the proposed change on approval" mechanism for each resource kind, and a
  second source of truth beside each aggregate's own status. Option B is two small transitions
  and one optional field.
- **Reuse.** `TransitionGraph`, `TransitionExecutor`, the per-aggregate transition logs, the audit
  rows the FSM already writes and the ADR 0004 event pattern all apply unchanged. Option A would
  sit beside them and duplicate their guarantees.
- **No new tables.** Every change below fits existing columns (`status_reason`, the transition
  logs, `organisation_initial_administrator_bootstrap`). There is no migration, and this ADR claims
  no migration number.
- **No demand for a cross-resource inbox.** Nothing in the frontend gap needs one list of "things
  waiting for me" across resources; each resource already lists its own `PENDING_APPROVAL` rows.

Option A remains the path if assignments or settings later need approval (point 2): that is the
first requirement a per-resource FSM cannot express without a new state on a high-volume table.
It would need its own ADR.

### 2. Assignments stay immediate

Branch assignments (`BranchProvisioningService.assignUser`) and role assignments
(`RoleManagementService.assignRoleToUser`) made after onboarding stay effective immediately.
There is **no `PENDING` assignment state**, no change to effective-permission resolution, and no
approval step for them. Consequently sub-issue **#182 is closed as not planned**.

The authority to assign remains the permission checks the services already make
(`user.assign_branch`, `user.assign_role`), audited as `branch.assign_user` and
`user.assign_role`.

Consequences of the decision:

- This is an **accepted gap** against the prototype's "pending assignments": an assigner with the
  permission can grant access with no second person. The compensating control is the audit trail,
  not a gate.
- That trail must therefore identify the **target user**. Today `branch.assign_user` records the
  branch as its resource with no metadata, and `user.assign_role` records the assignment id with
  the organisation, role, scope and branch as metadata, so neither row names the user who received
  access. The target user id must be added to both rows. That is owned by #186 (assignment events
  carry the target user, part of the audit gap #158); this ADR depends on it but does not
  duplicate it.
- Invitations are unaffected: the initial branch and role assignments created with a membership
  are approved together with it by the existing membership approval.

### 3. Two transitions and one optional field

Each extension is a transition or a field on an existing FSM, built with `TransitionDefinition`
and `eventFactories`, executed through `FoundationLifecycleService`, audited through the common
audit service, and exposed by a thin controller route under `/api/v1` with
`@IdempotentMutation`. Public JSON stays `snake_case`. A transition added to the enum without its
`TransitionDefinition` fails as an unmapped `InvalidTransitionException` (HTTP 500), so each one
is added to its graph in the same change as its service method.

#### 3a. Decision remarks on membership activate and tenant approve (#179)

- `POST /tenant/memberships/{membership_id}/activate` and
  `POST /platform/tenants/{tenant_id}/approve` accept an **optional** body
  `{"reason": "..."}`, `reason` at most 500 characters, mirroring `ActivateBranchRequest`. An absent
  body or an absent field behaves exactly as today. The change is additive and backwards
  compatible. The platform-checker route
  `POST /platform/tenants/{tenant_id}/memberships/{membership_id}/activate` (ADR 0028) takes the
  same optional body, so the two ways to approve a membership do not differ.
- **Membership: two paths, described precisely.** `approveUser` does not always activate the
  membership itself.
  - *Synchronous path* (the invitee already has a Keycloak identity,
    `activateMembershipIfPossible`): the reason is passed to the membership `ACTIVATE` transition,
    so it is the membership's `status_reason`, is in the transition log, is on the FSM's own
    `membership.activate` audit row, **and** is on the explicit `user.approve` audit row.
  - *Asynchronous path* (no identity yet, `requestKeycloakProvisioningIfNeeded`): `approveUser`
    runs the user-account transitions (`SUBMIT` if the user is a draft, then
    `START_IDP_PROVISIONING`), returns 202, and leaves the membership `PENDING_APPROVAL`. The
    membership `ACTIVATE` runs later in `KeycloakUserProvisioningJobRequestHandler`, with no
    reason, under that job's actor. On this path **the remark lives only on the `user.approve`
    audit row**. #179 does **not** thread the reason through the Keycloak job request, and does
    not pass it to the user-account transitions, which describe the account and not the
    membership decision. The later job-driven `membership.activate` row carries no reason, and
    that is accepted: the approval's remark and its actor are on `user.approve`.
- **Tenant approve.** The reason is already passed by `approveProvisioning` to `START_PROVISIONING`,
  to the head office's `SUBMIT` and `ACTIVATE` (`activateHeadOffice`) and to the organisation
  `ACTIVATE`, so one remark becomes the organisation's `status_reason` and also the bootstrap head
  office's `status_reason`. That is accepted: it is the existing behaviour for the value, and is
  what the audit rows of those transitions record.
- It is returned **where a status reason is already exposed**. The branch detail exposes
  `status_reason` today; tenant detail and membership detail do not, and exposing them is not part
  of this item: the tenant's is added by #181 (shared with #166, whichever lands first) and
  nothing is added for memberships here.
- The `reason` is a remark, not an input to any rule. It never changes who may approve.

#### 3b. Return or withdraw a pending branch (#180)

**Sequencing: after #165.** The design below is accepted and planned for implementation in this
effort, but **only after #165** (branch address, update endpoint, `opened_on` and `closed_on`, P1).
Without branch update, a returned or withdrawn branch would be a dead-end `DRAFT`:

- it cannot be amended: there is no branch update, so a typo can never be fixed;
- it cannot be closed: `BranchProvisioningService.close` accepts only `ACTIVE` or `SUSPENDED` and
  answers 409 otherwise;
- it cannot be suspended: `suspend` uses `SUSPEND`, which needs `ACTIVE`; `SUSPEND_DRAFT` is
  reachable only through tenant deprovisioning (`OrganisationProvisioningService`);
- it cannot be recreated under the same code: `branchCodeExists` ignores status, so the code stays
  taken (`uq_branch_organisation_code`).

Return-for-changes is only useful if the maker can then amend, so **implementing #180 before #165
is explicitly rejected**: it would ship a transition whose only outcome is an unusable draft. The
order is the mitigation, so there is no accepted dead-end consequence.

#165 is a P1 ticket outside this ADR's decisions: its design is the ticket's own and is **not**
designed here. This ADR constrains it by one requirement only: **branch amend must be allowed in
`DRAFT`**, so that a returned or withdrawn draft is amendable by the maker.

**Amendment (#203): the update permission.** #165 authorised `PATCH /api/v1/branches/{id}` with
`branch.create`, on the reasoning that editing a branch's descriptive fields is the maker's act.
That holds for a `DRAFT` but not for an `ACTIVE` branch, which the same route also edits: a custom
maker-only role holding `branch.create` could change a live, already-approved branch with no
checker in the path. The owner therefore decided on a dedicated **`branch.update`** code, seeded
by `V19__branch_update_permission.sql`, which now gates the route and
`BranchProvisioningService.update` and which `branch.create` no longer implies. The migration
copies the grant to every role and direct membership override that held `branch.create`, so
existing callers keep access and an owner narrows it deliberately by revoking `branch.update`.
This supersedes the "reuses `branch.create`" wording of #165 and the "no new code" remark below as
far as `PATCH` is concerned; the withdraw and return codes (`branch.create`, and `branch.approve`
since #208) in 3b are unchanged.

- **Transition.** One new branch transition, `BranchLifecycleTransition.RETURN_FOR_CHANGES`,
  `PENDING_APPROVAL -> DRAFT`. Nothing else leaves `PENDING_APPROVAL` for `DRAFT`. The transition
  is a state change only: the branch keeps its code, its parent and its `created_by`.
- **Reason.** Required, 3 to 500 characters (as `RejectTenantRequest`), persisted as
  `status_reason`, in the transition log and in the audit row's `reason`. A branch's `status_reason`
  is overwritten by its next transition, which is acceptable: the log and the audit keep the
  history.
- **Routes.** `POST /api/v1/branches/{branch_id}/return` (tenant) and
  `POST /api/v1/platform/tenants/{tenant_id}/branches/{branch_id}/return` (platform), both calling
  one service method, `BranchProvisioningService.returnForChanges`, with the same `ActingScope`
  flag ADR 0028 introduced. The route name is `return`; the FSM transition and audit action use
  the full name. The tenant route authorises against the **target branch**, not the caller's
  selected branch, and does not re-introduce the branch pinning that #154 removed.
- **One transition, two roles.** The same transition serves two intents, told apart by **who the
  actor is relative to the pending branch**, not by a second transition:
  1. *Withdraw*: the actor is the branch's **creator or its current submitter** (the latest
     `SUBMIT` row, as `submittedBy` already resolves it). The maker takes their own request back.
  2. *Return for changes*: the actor is **neither**. The checker sends it back.
> **Amended by [ADR 0030](0030-mutation-permission-implies-view-permission.md):** each of the
> permissions below also needs `branch.view` at the same scope.

- **Permission: no new code.** The permission required depends on the same distinction, and both
  codes already exist:
  - the maker (withdraw) needs `branch.create`, the permission that submitted it, so anyone who
    could put the branch up can take it down;
  - anyone else (return) needs `branch.approve`, the permission that gates the decision they are
    pre-empting, so exactly the people who could approve it can also return it, and no one else.
    (Written as `branch.activate` until #208, which deprecated that code.)

  A creator who also holds `branch.approve` is still a maker for this branch, so is classified as
  withdrawing and needs `branch.create`. Permissions can be revoked after the fact, so the outcome
  is stated explicitly: a creator or submitter who has since **lost `branch.create` cannot
  withdraw**, but a non-maker checker holding `branch.approve` can still return the branch. A new
  code would need a forward-only migration, a grant to every role that submits or activates, and a
  `docs/security` update, for a distinction the two existing codes already draw. (The separate
  `PATCH` permission is a different case: see the amendment above.)
- **Order of checks.** The permission to demand depends on who the actor is, so classifying the
  actor comes first, but it reveals nothing: it only chooses which permission is asked. The order,
  consistent with `activate` (permission, then platform-organisation 404, then branch 404, then
  the window, then state) is:
  1. **Classify** the actor as maker or checker, reading `created_by` and the current submitter
     **scoped to the path organisation**. A branch that is absent from that organisation
     classifies as a *return*, so a missing branch and a branch of another tenant look the same.
  2. **Permission** for that class (`branch.create` or `branch.approve`) in the route's scope:
     tenant, against the target branch; platform, in the platform organisation. An unauthorised
     caller therefore gets **403 before any existence signal**.
  3. Platform only: the platform organisation is never a valid `{tenant_id}` (404, after the
     permission).
  4. **Branch 404** when it is not in the path organisation.
  5. Platform only, **as a checker only**: the checker window below (409
     `lifecycle.platform_checker_closed`).
  6. **Organisation state 409**: see the next point.
  7. **Branch state 409** from the FSM when the branch is not `PENDING_APPROVAL`.
- **Organisation state.** Both intents on both routes require the organisation to be `ACTIVE` or
  `PROVISIONING`, the rule `requireOrganisationAllowsBranch` already applies to creating and
  platform-submitting a branch (a missing organisation is 404, any other state 409). A
  `SUSPENDED`, `DEPROVISIONING` or other tenant is frozen: nothing about its pending branches
  moves, and deprovisioning already suspends them. The stricter `ACTIVE`-only rule of `activate`
  is not needed here, because a return neither activates nor needs the tenant's setup complete.
- **Maker-checker.** Returning is the one decision a maker may take on their own branch, because it
  hands the branch back to them rather than approving it; it can never activate anything. The
  creator rule (`creator != activator`) and the platform submitter rule (`submitter != activator`
  on the platform route) are unchanged and keep applying to every later `ACTIVATE`.
- **Resubmission.** A returned branch is `DRAFT`, and `SUBMIT` is unchanged, so the same route
  resubmits it. `submittedBy` resolves the latest `SUBMIT` row, so the submitter of the new
  request is whoever resubmits, and a branch the creator drafted still cannot be activated by the
  creator, however many times it loops. Anyone who amended the draft (has a successful
  `branch.update` on it, however many amends followed) is barred from approving it too, on both
  routes, with `403` `lifecycle.approver_is_branch_modifier`; the checker who returned it amended
  nothing and may approve the resubmission.
- **Platform checker (ADR 0028).** A platform actor reaches the transition through the platform
  route with the same `ActingScope.PLATFORM` flag as `activate`, and there is one rule for the
  window: **returning as a checker is a checker step and is bounded exactly like activating**.
  The caller needs `branch.approve` in the platform organisation, is not the creator or
  submitter, the path tenant is a real tenant, and the tenant has no `ACTIVE` branch beyond the
  bootstrap head office (otherwise 409 `lifecycle.platform_checker_closed`, changing nothing).
  **Withdrawing is not a checker step**: a platform actor who is the creator or submitter needs
  `branch.create` in the platform organisation, as for `submit`, but **unlike platform `submit`,
  which is window-bounded, a platform withdrawal is not window-bounded**, because taking back
  one's own request grants nothing. This keeps one flag, one window, one permission per intent,
  rather than a second platform rule that would have to be remembered beside ADR 0028's.
- **Audit.** The FSM writes the transition row `branch.return_for_changes` for both intents. A
  withdrawal additionally writes an explicit `branch.withdraw` row (resource type `BRANCH`, the
  same `reason`, the actor) with **no `checkerScope` marker, even on the platform route**: the
  marker means "an approval the tenant did not make for itself" in ADR 0028's review filter, and a
  platform `submit` writes none either, so a withdrawal must not pollute it. Only a platform
  **return as a checker** carries `checkerScope = PLATFORM`, on a
  `branch.return_for_changes_as_platform_checker` row, mirroring
  `branch.activate_as_platform_checker`. A reviewer tells the two intents apart by action.
- **Event.** None: the transition uses `internalEventFactories()` (see "Events" below).
- **Errors.** `403` when the class's permission is missing, `404` for a branch outside the path
  organisation or the platform organisation as `{tenant_id}`, `409` when the organisation state
  is not allowed, the platform window is closed, or the branch is not `PENDING_APPROVAL`. Request
  validation is `400`, as everywhere in the foundation API
  ([foundation-api.md](../api/foundation-api.md), "Errors"): a missing or unreadable request body
  (the body is required), or an absent or `null` `reason`, is `400 invalid_json`, and a blank or
  out-of-range `reason` (shorter than 3 or longer than 500 characters) is `400 validation_failed`.
  The request carries `reason` as a non-null `String` with `@NotBlank` and `@Size(min = 3,
  max = 500)`, as `RejectTenantRequest` does, so an omitted or `null` field fails in Jackson
  deserialization (`HttpMessageNotReadableException`, mapped by `ApiExceptionHandler.invalidJson`),
  while the rest is raised by Bean Validation and mapped by `ApiExceptionHandler`. No `422`
  mapping is added for it. Validation runs when the body is bound, before the service's checks,
  so the check order above applies to well-formed requests.

#### 3c. Return a pending tenant to draft (#181)

- **Transition.** One new organisation transition,
  `OrganisationLifecycleTransition.RETURN_FOR_CHANGES`, `PENDING_APPROVAL -> DRAFT`. `REJECT` is
  unchanged and **stays terminal**.
> **Amended by [ADR 0030](0030-mutation-permission-implies-view-permission.md):** the route also
> needs `tenant.view` in the platform organisation.

- **Route and permission.** `POST /api/v1/platform/tenants/{tenant_id}/return` with a required
  `reason` (3 to 500, as `RejectTenantRequest`), calling
  `OrganisationProvisioningService.returnForChanges`. It requires **`tenant.reject`**, the existing
  permission for the checker's non-approving decision, so no new code or migration is introduced.
- **Checker only.** The actor must not be the requester or the submitter, the same rule
  `approveProvisioning` applies (`requested_by` and `submitted_by` on the bootstrap record), and
  must not be the system actor. A maker cannot withdraw a tenant through this route: a tenant has
  no maker-side withdraw in this decision, because a maker can amend a `DRAFT` only, and nothing
  here pulls a submitted tenant back for them. This is deliberate and can be revisited on its own.
- **Resubmission.** A returned tenant is `DRAFT`. `PATCH /platform/tenants/{tenant_id}`
  (`amendDraft`) works on it again and `POST .../submit` resubmits it; no new submit route. The
  maker-checker rule is read from the bootstrap record at approval time, so it holds across the
  loop: the requester stays the requester, and the submitter becomes whoever resubmits.
- **Bootstrap record invariant.** `organisation_initial_administrator_bootstrap` is the draft's
  record of the initial administrator, and `amendDraft` overwrites its administrator block
  (`admin_email`, `admin_username`, `admin_display_name`, `admin_phone_e164`,
  `send_application_invite`). Returning a tenant must leave that record consistent with a draft,
  exactly as `reject` already does for a rejected one: status back to `DRAFT`, `submitted_by` and
  `submitted_at` cleared, `approved_by` and `approved_at` cleared, `requested_by` and the
  administrator block **untouched**. The invariant to preserve is that **the record describes the
  draft as currently amended, and its submitter is the actor of the current submission: set from
  `SUBMIT` until a return or a reject clears it**. Everything that depends on the administrator
  is evaluated against that record at the moment it is used, never cached from before the return:
  the approver-is-the-initial-administrator refusal (ADR 0028, point 10) and the bootstrap itself
  both read the record at approval, so an amend after a return that changes the administrator is
  picked up. If the amended administrator is the account of whoever then approves, that approval
  is refused with 403 `lifecycle.approver_is_initial_administrator`, as for any tenant; the check
  does not look at who returned or amended. The return and the record update commit in the same
  transaction as the transition, as `rejectProvisioning` already does.
- **Locking rule (#204).** Every provisioning decision that judges the bootstrap record or the
  organisation's state and then writes (`approveProvisioning`, `returnForChanges`,
  `rejectProvisioning`, `submitForApproval` and `amendDraft`) takes the organisation row's
  `FOR NO KEY UPDATE` lock **first**, before it reads the record or the state, and holds it to the
  end of its transaction through the transition. The return edge makes the record replaceable: a
  request can commit return, amend and resubmit and so change both the submitter and the named
  administrator. A decision that read them first and locked second would judge the previous
  submission and then move the new one, letting the new submitter, or the newly named
  administrator, approve. Locked first, it waits for that request and reads what it left. The
  lock is the one the transition's `UPDATE` takes anyway, taken early, so the FSM and its
  optimistic `row_version` check are unchanged; it does not block the foreign-key checks of rows
  inserted for the organisation. The lock order is always organisation, then bootstrap record
  (`reject` and `submit` used to take them the other way round, which could deadlock against
  approve). Approve, return and amend are proved against Postgres by
  `TenantApprovalReturnRaceIntegrationTests`; the order in reject and submit, and in all five, is
  pinned by `OrganisationProvisioningLockOrderTests`.
- **Amenders are not makers.** *(Superseded by #221, see the amendment at the top: an amender can
  neither approve nor reject; a checker who only returned the tenant still may.)* Only
  `requested_by` and the submitter are excluded from approving.
  A checker can return a tenant, amend it, and later approve it after a third party resubmits.
  That is pre-existing behaviour for any draft amended by someone other than its requester, and it
  is accepted: two distinct people (requester and submitter versus approver) still stand behind
  every approval.
- **Exposure.** **#181 itself adds** the additive `status_reason` field to the tenant detail
  response, so the maker can read why a tenant was returned. This is an acceptance condition of
  #181, not an option, and #181 does not depend on #166 landing first. The field is shared with
  #166 (tenant legal name, `PATCH` semantics, a separate P1 piece of work kept out of this
  stack): whichever of #166 and #181 lands first adds it, and the other reuses it rather than
  adding a second one.
  It is always present and nullable (`null` when the last transition recorded none, never
  omitted), on the platform tenant routes and on the tenant's own `GET /tenant`: the reason a
  checker gives is the tenant's own to read.
- **Audit and event.** The FSM writes `organisation.return_for_changes` with the `reason`. No
  event: the transition uses `internalEventFactories()` (see "Events" below).
- **Errors.** `403` when `tenant.reject` is missing or the actor is the requester or submitter
  (the same `forbidden` code as the existing approvals, until #156 adds a distinct one; since #221
  the maker refusal is `lifecycle.approver_is_tenant_maker`), `404` for
  an unknown tenant or the platform organisation, `409` when the tenant is not `PENDING_APPROVAL`
  (the FSM's conflict). Request validation is `400`, as for the branch routes, with the same
  non-null `reason` shape: a missing or unreadable body (required here), or an absent or `null`
  `reason`, is `400 invalid_json`; a blank `reason` or one shorter than 3 or longer than 500
  characters is `400 validation_failed`. No `422` mapping is added, and validation runs at
  binding, before the service's checks.
- **Out of scope.** Recovering a `REJECTED` tenant, and the tenant code it keeps taken, is **not**
  decided here. It belongs to #172 (P2). Returning is the recoverable path this ADR adds; a
  checker who wants the tenant to start over still rejects.

#### Events: the new transitions are internal

Both `RETURN_FOR_CHANGES` transitions use `internalEventFactories()`. The policy is the one in
[transactional-outbox-amqp.md](../architecture/transactional-outbox-amqp.md): externalize an
event when a real consumer needs it ("add one when a real consumer needs it", as for
`KeycloakUserProvisioned`), and `SUSPEND_PENDING_APPROVAL`, the other way out of a pending branch,
is internal for the same reason. ADR 0004 defines the pattern for events that are externalized;
it does not require every transition to have one. No consumer needs these: the only notification
listener is for membership activation.

The cost is accepted and stated: integration consumers of the
`finaxis.lifecycle.organisation.approval-requested` and
`finaxis.lifecycle.branch.approval-requested` events, which fan out on the generic
`finaxis.lifecycle.organisation-events` queue, will see a **repeat on every resubmit with no exit
event in between**. If a consumer needs to see the return, the follow-up is to externalize a
`*.returned-for-changes` event for each (a new `target`, a `RabbitOutboxRouting` route and an
exchange entry, symmetric with `SUBMIT` and `REJECT`), as a change of its own.

### 4. The two `require_maker_checker_*` settings are removed, not wired

Maker-checker is **unconditional** for membership approval and branch activation, as implemented
and as ADR 0028 relies on. The settings `require_maker_checker_for_user_invites` and
`require_maker_checker_for_branch_creation` therefore promise a control that does not exist and
that no tenant may turn off. They are **misleading and should be removed from the settings
catalogue** (`TenantSettingCatalog.kt`), not wired to behaviour.

- The removal is carried out in **#164** (the settings catalogue, P1), as a separate piece of work.
  This ADR does not change the catalogue. #164 also owns updating
  `docs/operations/tenant-settings.md`, which still lists the two keys as ordinary settings;
  until then that document carries an "inert, scheduled for removal" note, added with this ADR.
- Until #164 lands the two keys remain **inert**: stored values are ignored, and the operational
  and API documentation says so.
- **Removing them from the catalogue is not sufficient, and #164 must also retire the persisted
  rows.** Rows already stored for the two keys survive the catalogue change:
  `TenantSettingsService.list` deliberately appends current stored rows whose key is not in the
  catalogue (`extraViews`), `get` falls back to the stored row when the definition is gone, and
  `deactivate` (like `createOrUpdate`) calls `TenantSettingCatalog.require`, so once the
  definition is gone the mutation API rejects the key as unknown and cannot close the row. The
  misleading settings would stay visible indefinitely. #164 must therefore do one of: a
  forward-only migration (the next free number) that closes the existing effective rows for those
  two keys, or an
  explicit filter of the retired keys in the `list` and `get` paths. Which one is #164's
  decision; that one of them ships with the catalogue change is an **acceptance condition of
  #164**. This ADR itself still adds no migration.
- If #164 prefers to keep either key for a future tenant opt-out, that requires **its own ADR**,
  because making maker-checker optional per tenant relaxes segregation of duties and contradicts
  the assumption below.
- **Interaction with ADR 0028.** The platform-checker window is defined against an unconditional
  tenant rule: it counts tenant members and branches because every approval is two-person. A
  tenant setting that switched maker-checker off would change what the bound protects, and would
  have to be designed together with it.

### 5. Interaction with #153 and #156

- **#153 (ADR 0028).** The platform checker is a bounded exception to the tenant's maker-checker
  and is the only one. This ADR adds no second exception: remarks do not change who may approve,
  and the branch return of 3b is bounded like activation when a platform actor takes it as a
  checker.
- **#156 (maker exposure).** #156 will expose the maker (`created_by` and `submitted_by`) on
  memberships, branches and tenants and give maker-checker violations a distinct error code. This
  ADR needs only that **remarks and the return flows do not change who the maker is**: `created_by`
  and `requested_by` are never rewritten, and the submitter is the actor of the latest `SUBMIT`.
  The new `return` routes and any resubmission must raise the same dedicated code as the existing
  approvals once #156 introduces it. Since #221 the tenant already raises
  `lifecycle.approver_is_tenant_maker` and `lifecycle.approver_is_tenant_modifier`; #156 must reuse
  or replace those names, not add a third.

### 6. Sequencing

Implementation order, all in this effort:

1. **#179**, decision remarks on membership activate and tenant approve.
2. **#165**, branch update/amend (and address, `opened_on`, `closed_on`), which gives a returned
   draft its exit. Its design is its own ticket's; the only constraint from this ADR is that
   amend is allowed in `DRAFT`.
3. **#180**, branch return-for-changes and withdraw. It **must not be implemented before #165**:
   a returned `DRAFT` could otherwise not be amended, closed, suspended or recreated, and its code
   would stay taken.
4. **#181**, tenant return-for-changes. It adds the additive `status_reason` to the tenant
   detail itself (an acceptance condition of #181), shared with #166 but not dependent on it:
   whichever lands first adds the field, the other reuses it. #166 (tenant legal name, `PATCH`
   semantics) is a separate P1 piece of work and is **not** part of this sequence.

**#182** (assignments) is closed as not planned. **#164** carries the removal of the two settings,
the retirement of their persisted rows (see point 4) and the update of
`docs/operations/tenant-settings.md`; **#186** carries the target user on assignment audit rows.

## Alternatives rejected

- **Option A, a generic approval-request resource.** Rejected for now for the reasons under
  Decision 1. It also replaces, not extends, three working FSMs and their guarantees, and puts a
  second status beside each aggregate's own. It stays the path if assignments or settings need
  approval.
- **Pending assignments.** Rejected: it adds a state to the most frequently written and most
  permission-critical tables, every effective-permission read path
  (`EffectivePermissionResolver`, `/auth/me`, branch selection) would have to exclude it, the
  uniqueness indexes would need review, and it would be the first thing that forces Option A. The
  audit-trail requirement of Decision 2 covers the risk at far lower cost.
- **Wiring the settings flags.** Rejected: an optional maker-checker is a weaker control than the
  one in force, no tenant has asked for it, and ADR 0028 assumes the control is unconditional.
  Wiring them would build a relaxation nobody requested.
- **A terminal `REJECTED` branch state instead of return-to-draft.** Rejected, but not because it
  is the only one that keeps the branch code taken: a returned `DRAFT` that cannot be amended
  keeps it taken as well (see 3b, which is why #180 is ordered after #165). The honest reasons
  are that return-to-draft is the smaller change (one edge instead of a new state, its
  `chk_branch_status` constraint and every status switch), it reuses the existing `DRAFT` and
  `SUBMIT` machinery, and once #165 exists it lets the maker correct and resubmit, which a
  terminal state never could.
- **A separate `WITHDRAW` transition.** Rejected: it is the same state change, from
  `PENDING_APPROVAL` to `DRAFT`. One transition with an audited actor distinction
  (`branch.withdraw` beside the FSM row) is simpler than two edges that must be kept identical.

## Consequences

- Maker-checker stays three small, readable rules, one per resource, enforced where they are today;
  a reviewer does not need to learn an approval-request model.
- Checkers gain a recoverable decision for tenants and, once #165 and #180 land, for branches,
  and membership and tenant approvals can carry a remark that is stored and audited. On the
  asynchronous membership path the remark is on the `user.approve` audit row only.
- A maker will be able to withdraw their own pending branch, and a pending tenant can be returned
  to its maker. A maker cannot withdraw a pending tenant.
- **Rejecting is wider than returning.** *(Superseded by #221: reject now refuses the requester,
  the submitter and every amender.)* `rejectProvisioning` has no requester or submitter check,
  while return is checker-only, so a maker holding `tenant.reject` can terminally reject their own
  submitted tenant but cannot return it. This ADR does not widen return or narrow reject; changing
  reject's maker rule is separate work.
- Assignments remain a single-actor grant. The audit trail, which must carry the target user id
  (#186), is the control.
- A branch resubmitted by a different person has a new current submitter; the earlier submitter is
  no longer barred by the platform submitter rule, which reads the latest `SUBMIT`. The creator is
  barred on every route throughout. That follows the existing rule and is accepted.
- The settings catalogue is misleading until #164 removes the two keys and retires their persisted
  rows (catalogue removal alone leaves the stored rows listed and unmutable); they are inert
  meanwhile.
- Integration consumers see a repeated `approval-requested` event on every resubmit with no event
  for the return in between (see "Events").
- No table, column, migration or durable financial effect is added. **This overrides the
  acceptance criteria of #180 and #155 that ask for atomicity probes**: no
  `FinancialTransactionAtomicityFixture` probe is registered, because these are not financial
  write paths ([ADR 0018](0018-financial-transaction-atomicity-invariant.md) and `CLAUDE.md` scope
  probes to those). Each transition and its bootstrap record update commit or roll back inside the
  one service transaction. Integration tests for each of #179, #180 and #181 still follow
  `CLAUDE.md`, including the idempotent-replay and maker-checker-across-the-loop cases.
- Implementation plan: see "Sequencing" (#179, #165, #180, #181 in that order; #182 not planned).
