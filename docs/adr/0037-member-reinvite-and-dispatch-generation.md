# ADR 0037: Re-Invite A Revoked Member On The Same Membership, With A Generation In The Dispatch Key

## Status

Accepted, **implemented** in the release containing this change (issue #172).

Date: 2026-10-25

Builds on [ADR 0004](0004-membership-activation-notification-pipeline.md) (the event pipeline the
invite jobs ride), [ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) (the
maker-checker rules that apply to the re-invitation as to any invitation),
[ADR 0029](0029-approval-model-per-resource-extensions.md) (its "Out of scope" paragraph named this
issue), [ADR 0030](0030-mutation-permission-implies-view-permission.md) (`user.invite` and the views
it implies) and [ADR 0031](0031-audit-one-row-per-transition-and-explicit-branch.md) (the FSM row is
the audit record). It supersedes nothing.

## Context

`REVOKED` is a dead end. No edge leaves it (`FoundationLifecycleDefinitions.membershipGraph` holds
`REVOKE`, `REVOKE_PENDING` and `REVOKE_SUSPENDED` into it and nothing out), and the invite route
refuses a user who has a membership row in any state, `REVOKED` included
(`InviteeResolution.findOrCreateInvitee`, the `409` "This user already has a membership in the
selected organisation."). A person who left the organisation and comes back cannot be invited
again, and `uq_membership_organisation_user` (`UNIQUE (organisation_id, user_id)`, `V1`) means
there is no second row to give them.

Issue #172 asked the same of a `REJECTED` tenant: it is also terminal, and its code stays taken.
The two halves have different answers (decisions 1 to 12 for the member, 13 for the tenant).

Reusing the row is not only a graph edge. Three things in the existing pipeline assume one
invitation per user and organisation, and each fails silently when the row is reused:

- **The dispatch key has no per-membership part.** The Keycloak provisioning key is
  `<organisation>:<user>:KEYCLOAK_PROVISIONING` and the application-invite key is
  `<user>:<organisation>:APPLICATION_INVITE`. `recordDispatch` is `ON CONFLICT (dispatch_key) DO
  NOTHING`, `ApplicationInviteJobRequestHandler` returns at once when the dispatch `SUCCEEDED`, and
  the JobRunr job id is `deterministicJobId(key)`. Dispatch happens at approval, not at invitation.
  So a re-approved member whose first invitation was delivered gets **no email at all**: the insert
  does nothing, the job id is the finished job's, and the handler returns early.
- **A first-life job can outlive the revoke.**
  `KeycloakUserProvisioningJobRequestHandler.cancelIfRevoked` cancels a queued job only while the
  membership is `REVOKED`. Once the row is `PENDING_APPROVAL` again, a first-life job that is
  retried or requeued would provision and then **activate** the member
  (the handler's `activateMembershipIfNeeded`, now `ProvisionedMembershipActivation.activate`),
  which bypasses the checker the new invitation requires.
- **A user can still be `PROVISIONING_IDP`.** A member revoked before the first job ran leaves the
  user in `PROVISIONING_IDP` and the dispatch `FAILED` (cancelled). A second approval on the same
  key would be read as a repeat of the first (`requeueFailedProvisioning`), not as the first
  approval of a new life.

## Decision

1. **A new edge, `REINVITE`: `REVOKED` to `PENDING_APPROVAL`.** It is the only edge out of `REVOKED`
   and `REVOKED` is its only source; every other state keeps the `409`. It declares
   `internalEventFactories()` only: nothing is announced until the approval, as for a first
   invitation.
2. **Through the existing invite route and permission.** `POST /api/v1/tenant/users` with
   `user.invite` (and the views it implies). No new route, no new permission code, no migration. The
   route's request and `201` response are unchanged; `membership_id` is the revoked membership's id.
   `findOrCreateInvitee` returns the revoked membership alongside the user, and
   `UserProvisioningService.invite` has one new branch, `reinviteMembership`, before the steps a
   first invitation also takes.
3. **The row is reused and reset, in one transaction.** `reinviteMembership` runs the FSM transition
   first (it is the check that the row is still revoked, and the branch-assignment step refuses a
   revoked membership), then `UserProvisioningStore.resetMembershipForReinvite`, on the same row:
   `membership_type` and `primary_branch_id` of the new invitation; `created_by` the re-inviter (the
   maker: `MembershipCheckerRule` and `hasActiveMembershipBeyondBootstrap` both read it);
   `joined_at`, `suspended_at`, `revoked_at` and `status_reason` cleared; `row_version` and
   `updated_*` advanced; and **every direct `membership_permission` override of the membership
   deleted**, so nothing granted to the member in the first life survives into the second. The
   invitation preferences are saved by the first-invitation step, `saveInvitationPreferences`, which
   is why it runs after the transition. `created_at` keeps the row's first creation.
4. **Assignments follow the first-invitation path.** The branch and role assignments of the first
   life were ended at revoke (`revokeBranchAssignmentsForMembership`,
   `revokeRoleAssignmentsForMembership`) and stay as history. The invitation creates fresh ones
   through the same code a first invitation uses, so an approval's prerequisites (an active branch
   and role assignment) are met by the new invitation's own, never by leftovers.
5. **One audit row: the FSM's.** The transition writes `membership.reinvite` (`MEMBERSHIP`, the
   membership id, the re-inviter as actor, `SUCCESS`) and the transition log row `REINVITE`; no
   `user.invite` row repeats it (ADR 0031). The first life's rows stay on the same membership key,
   so one membership's whole history, revoke and return included, reads from one entity.
6. **A checker approves again, and is never the maker.** The re-inviter is the maker now, so ADR
   0028's rules apply as to a fresh invitation: the re-inviter and the invited user are refused
   (`lifecycle.approver_is_membership_maker`, `..._beneficiary`, each with a `DENIED`/`HIGH` audit
   row), the platform checker window is judged on the new `created_by`, and the first life's maker
   or checker is an ordinary candidate again.
7. **A generation in the dispatch key, derived and not stored.** A membership's generation is the
   number of `REINVITE` rows in its transition log (`user_organisation_membership_transition_log`,
   `transition_name = 'REINVITE'`), read by a scalar subquery in `membershipSnapshot`
   (`MembershipProvisioningSnapshot.generation`). The log is append-only and the row is written in
   the transaction that changes the membership, so the generation moves exactly when the reset
   commits, with no column and no migration. The keys become
   `<organisation>:<user>:KEYCLOAK_PROVISIONING[:G<n>]` and
   `<user>:<organisation>:APPLICATION_INVITE[:G<n>]`. **Generation 0 adds nothing**: the keys, and
   the job ids derived from them, of a membership that was never re-invited are byte-for-byte what
   earlier releases wrote, so rows and jobs already stored for a live membership are undisturbed
   (`DispatchKeys.kt`). The job id formula is unchanged: it derives from the key, so each generation
   has its own.
8. **The generation travels with the work.** The approval event carries `generation` in its metadata
   (an event published before the field existed names none, read as 0), the listener copies it into
   the job request, and `ApplicationInviteJobRequest` and `KeycloakUserProvisioningJobRequest` carry
   `generation` last, defaulting to 0 so a job stored before the field reads as generation 0.
9. **A job of an earlier generation is refused, wherever it is found.** Both handlers compare the
   job's generation with the membership's current one and, when they differ, end the job
   **normally** (so JobRunr neither fails nor retries it), sending no email, linking nothing and
   activating nothing. A dispatch still `PENDING` is left `FAILED` with
   `CANCELLED:MembershipReinvited`, audited with that state change like the revoke's
   `CANCELLED:MembershipRevoked`; a dispatch that already succeeded or failed is left as it is. The
   Keycloak handler checks on entry and again at the activation:
   `ProvisionedMembershipActivation.activate` takes the membership row lock (`FOR NO KEY UPDATE`) in
   one statement and reads the membership, with its generation, in a second
   (`lockedMembershipSnapshot`), then compares and transitions in the same transaction. The two
   statements matter: read together, the generation subquery would run on the statement's original
   snapshot while the lock waited for a re-invitation to commit, and report the count from before it
   beside the row's new status. Taken apart, the read starts after the lock is granted (READ
   COMMITTED) and sees what the holder committed, so a job that was current when it began and became
   stale during its Keycloak call cannot activate the new life's row, and a revoke or re-invite
   cannot commit between the check and the transition.
10. **The first approval of a generation is not a repeat.** For a member re-invited while the user
    is still `PROVISIONING_IDP` (generation above 0 and no dispatch row under the new key),
    `approve` queues the provisioning and the invitation email under the new key. For generation 0 a
    missing row stays the refusal it always was (`requeueFailedProvisioning` answers `409`), and a
    repeat approval of a generation that has its row still resumes that generation's job.
11. **An approval locks the membership row too.** `approve` takes the same lock-then-read at its
    start and holds it to the end of the transaction, so the snapshot it reads, the maker-checker
    check made on that snapshot and the activation are all about one life of the membership: a
    revoke and a re-invitation cannot commit between them, which would have sent `ACTIVATE` to the
    next life, never checked against its new maker (possibly the approver). Lock order is
    organisation row, then membership row, on every path: the tenant approver already locked the
    organisation first, the platform approver now does before `approve` (it used to lock it later,
    for the window count, which after the new membership lock would have inverted the order), and
    revoke, re-invite, the provisioning job's activation and the deprovision cascade take the
    membership row or the organisation row first and never the other after it (the organisation lock
    is `FOR NO KEY UPDATE`, which the foreign-key `KEY SHARE` locks of the assignment inserts do not
    conflict with).
12. **The effective-permission cache is evicted when the membership is invited again or activated.**
    The cache key is `<membership id>:<branch>` and a re-invited member keeps the membership id; the
    principal loader serves cached permissions whenever the status is `ACTIVE`. A first-life entry
    was therefore masked while the member was `REVOKED` and live again once the new approval made
    the row `ACTIVE`, serving the first life's roles and the direct overrides this change deletes,
    possibly more than the new checker approved. The lifecycle module may not call IAM, so an IAM
    listener (`MembershipPermissionCacheEvictionListener`) reacts to the `REINVITE` and `ACTIVATE`
    transition events of a membership and calls `PermissionCacheInvalidator.evictMembership`,
    **after commit**, never before the transaction can still roll back. It is a plain
    `@EventListener` that registers a `TransactionSynchronization` whose `afterCommit` evicts (at
    once when no synchronisation is active), and deliberately not a `@TransactionalEventListener`:
    with Spring Modulith's persistent event multicaster that would store an `event_publication` row
    for every transition event of every aggregate in the publishing transaction, which ADR 0018
    keeps free of after-commit registry listeners. ADR 0018 is unchanged and still true.
13. **A rejected tenant stays terminal; no code change.** `REJECT` is the checker's deliberate
    "start over" outcome, and `RETURN_FOR_CHANGES` (ADR 0029) is the recoverable path, so a second
    way out of `REJECTED` would make the two decisions mean the same thing. The tenant code stays
    **reserved**: it stays on the rejected row (`uq_organisation_tenant_code` ignores status), so
    the way to reuse a name is a new code, as for every terminal state (ADR 0035 roles, ADR 0036
    branches). This is documented in `lifecycle-fsm.md`, `foundation-api.md` and ADR 0029 and
    changes nothing in code.

## Alternatives rejected

- **Delete the first life's dispatch rows at re-invite.** It loses the delivery record (who was sent
  what, and when), and it does not stop the stale job: the job id and the job itself are still in
  JobRunr, and with the row gone the stale handler would simply run to the end and activate.
- **A second membership row.** `uq_membership_organisation_user` forbids it, relaxing that
  constraint touches `V1` semantics for every reader of the table, and one person's history would be
  split over two rows.
- **A stored generation column.** It works, but it is a migration for a number the append-only log
  already holds exactly, and a second source of truth that must be written in the same transaction
  as the log row anyway.
- **A random or time-based suffix on the key.** The key must stay deterministic: the requeue, the
  repeat approval and the bootstrap retry all find a dispatch's job by recomputing its id.
- **Cancelling stale jobs in JobRunr at re-invite.** The cancel is an external write that commits at
  once, and a job already running is not stopped by it; the handler check is needed anyway.

## Consequences

- **A revoked member can come back**, with a record on the one membership of who revoked, who
  re-invited and who approved, and a re-approved member receives their invitation again.
- **Jobs in flight at the deploy are undisturbed.** Their keys, ids and (absent) generation are all
  generation 0, which is what a never-revoked membership is. A first-life job still queued for a
  member who is re-invited later ends as a no-op when it runs.
- **A stale job can leave a trace before it is refused.** A Keycloak job that became stale during
  its call has already found or created the Keycloak user, linked it and moved the user to
  `INVITED`: the identity belongs to the person, is idempotent and is what the new approval would
  create, so it is kept. Only the membership activation, which needs a checker, and the dispatch
  success are refused.
- **The permission cache is evicted on re-invitation and activation (decision 12), with the reach
  role edits already have.** The window it closes ran from the last first-life cache write (the
  cache's time-to-live is five minutes, counted from each write, not from the new approval). The
  eviction reaches the entries the instance that ran the transition cached
  (`EffectivePermissionResolver` remembers the branch selections it cached per instance), as
  `RoleManagementService`'s evictions do; an entry another instance wrote to the shared Redis cache
  is bounded by the time-to-live as before. A Redis failure at eviction is logged and not rethrown:
  the commit has happened.
- **`user.invite` is a first-invitation action only.** Counting invitations from audit rows reads
  `action = user.invite` for first invitations and `action = membership.reinvite` for
  re-invitations.
- **A bootstrap administrator revoked and invited again** is a membership of generation 1, while the
  bootstrap's own retry (`approveOrRepublish`) computes the generation-0 key. If the generation-0
  dispatch is `SUCCEEDED` the retry only completes the bootstrap; if no generation-0 dispatch
  exists, the retry's approval runs for the current generation and queues the new key; if it exists
  and is `PENDING` or `FAILED`, the retry requeues or republishes the generation-0 job, which both
  handlers refuse as stale, so the bootstrap is not completed and stays as it was (`FAILED`): the
  safe outcome, but a human has to recover it (re-approve the current membership). The bootstrap
  completes in the administrator's first life, so this is not reachable in normal operation.
- **Two jobs can overlap across generations.** A first-life Keycloak job still inside its Keycloak
  call when generation 1 is approved can run `findOrCreateUser` at the same time as generation 1's
  job. `requeueFailedProvisioning`'s rule that a new job id is never made for a dispatch stands
  *within* a generation (one key, one id, so one job); across generations the two ids differ by
  design. The overlap relies on what already guards a repeated identity call: the gateway searches
  by email and username before creating, the identity link insert is `ON CONFLICT DO NOTHING`, and a
  loser of a creation race fails, is retried by JobRunr and then finds the user. The stale job's
  activation is refused in any case.
- **The resend and recovery routes are unchanged.** They address the user's newest dispatch or the
  key of the membership's current generation, and a resend is for an `ACTIVE` member only.
- `UserProvisioningMembershipStore.membershipExists` is replaced by `existingMembership` (the row's
  id and status), and gains `resetMembershipForReinvite` and `lockedMembershipSnapshot`.

## Not covered

- A re-invite of a `REVOKED` member by the platform routes. The platform checker is an approver
  window for a first approval, and a platform operator invites through the tenant when needed.
- Undoing a re-invitation other than by revoking it again (`REVOKE_PENDING`), which is allowed and
  leaves the membership `REVOKED` and re-invitable once more.
- Recovering a `REJECTED` tenant, by decision 13.
