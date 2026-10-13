# ADR 0031: Audit One Row Per Transition, Keyed By The Aggregate, With An Explicit Branch

## Status

Accepted, **implemented** in the release containing this change (issue #186, PR 4).

Date: 2026-10-13

Cut-over: **from the release containing this change.** Rows written before that release keep the
shape they were written with (see "Historic rows"). Nothing is backfilled or rewritten.

The release notes record the deploy timestamp of that release, which is the boundary. The rows
themselves give a queryable marker per tenant, but neither is the boundary itself; each bounds it:
the last old-shape row (`entity_type = USER` and `action = user.suspend`) is at or before the
cut-over, because the new code never writes that shape, and the first
`membership.assignments_revoked` row is at or after it. A tenant with no such rows has no marker, so it uses the release timestamp.

Builds on [ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) and
[ADR 0030](0030-mutation-permission-implies-view-permission.md) (the checker and view rules read
audit rows as control inputs, so the shape of those rows matters), and on the FSM event pattern in
`docs/architecture/fsm-transitions.md`.

## Context

A user or membership transition was recorded twice. The FSM wrote its row through
`FoundationLifecycleService.recordOutcome`, and the application service next to it wrote a second
row for the same call. The duplicates, each shown as the FSM row kept and the service row removed:

- user suspend: `user_account.suspend` (`USER_ACCOUNT`) and `user.suspend` (`USER`);
- user reactivate: `user_account.reactivate` (`USER_ACCOUNT`) and `user.reactivate` (`USER`);
- user deactivate: `user_account.start_deactivation` and `user_account.complete_deactivation`
  (`USER_ACCOUNT`) and `user.deactivate` (`USER`);
- membership suspend: `membership.suspend` (`MEMBERSHIP`) and `membership.suspend` (`USER`);
- membership reactivate: `membership.reactivate` (`MEMBERSHIP`) and
  `membership.reactivate` (`USER`);
- membership revoke: `membership.revoke`, `membership.revoke_pending` or
  `membership.revoke_suspended` (`MEMBERSHIP`) and `membership.revoke` (`MEMBERSHIP`, carrying
  the cascade counts).

Three defects follow from that:

1. **Two rows per transition.** A consumer that counts a transition gets 2. For a revoke both rows
   were `membership.revoke` on the same `MEMBERSHIP` key, so the action could not tell them apart.
2. **Two keys for one resource.** The service rows were keyed `USER`, the FSM rows `USER_ACCOUNT`
   and `MEMBERSHIP`, so one membership's history was split across two entity types.
3. **Branch stamping.** `branch_id` was taken from the caller's selected branch when the event did
   not name one (#186, fixed in the change before this one: the audit adapter now reads only the
   event's own `branchId`).

`HighRiskOperationAuditCoverageTests` hid the first defect. It mapped `user.suspend`,
`user.activate` and `user.deactivate` through a `user` prefix that no FSM writes, so the
permission-to-action map stayed green whatever the FSM did.

## Decision

1. **The FSM row is the transition's record, and a service row never repeats it.** Every user,
   membership, branch and organisation transition the lifecycle FSM executes writes its row through
   `recordOutcome` (success) or `recordTransitionFailure` (rejection or failure). The service rows
   that repeated a user or membership transition are removed: `user.suspend`, `user.reactivate`,
   `user.deactivate`, `membership.suspend` and `membership.reactivate`. The FSM row carries the
   from-state and to-state, which the service row never did. A service row may stay next to an FSM
   row only when it adds a fact the FSM row cannot carry, and then it has its own action name. The
   companion rows kept, and why each exists:
   - `branch.submit_as_platform_checker` and `branch.activate_as_platform_checker`: the checker
     marker (`checkerScope`) that ADR 0028's review filter reads.
   - `branch.withdraw`: the maker's withdrawal of a pending branch. The FSM row for that call is
     `branch.return_for_changes`; this row adds that it was a withdrawal. It never carries the
     checker marker.
   - `user.approve`: the approval intent (the checker scope, and whether the membership was
     activated at once).
   - `membership.assignments_revoked`: the cascade counts of a revoke (Decision 3).
   - `user.first_login_activation`: beside `user_account.activate`, it records the user as the
     actor. The FSM row records the system as actor, because the principal loads before a request
     actor is bound (found in review). Merging the two rows is a follow-up, not part of this change.
2. **The resource key is the aggregate the transition acts on.** A user transition is keyed
   `USER_ACCOUNT` and its id. A membership transition is keyed `MEMBERSHIP` and the membership id.
   `USER` stays the key of rows that act on the account without a transition of its own
   (`user.invite`, `user.approve`, and the external dispatch rows), which this change does not
   touch.
3. **The revoke cascade is its own row, renamed.** A successful membership revoke writes the FSM row
   first, then one `membership.assignments_revoked` row (`MEMBERSHIP`, the membership id) carrying
   `userId`, `branchAssignmentsRevoked` and `roleAssignmentsRevoked`. It replaces the service row
   that was also named `membership.revoke`. It is written once per successful revoke, even when both
   counts are zero, and never for a refused or failed revoke. The name follows
   `user.deactivation_assignment_revoked` and `organisation.deprovision_assignment_revoked`.
4. **`branch_id` is explicit only** (ADR-level restatement of the #186 change). A row names a
   branch only when the event carries that branch. User and membership rows name none, whatever
   branch the approver or revoker has selected. The audit adapter never reads the caller's branch.
5. **Historic rows keep the old shape.** Rows written before the cut-over are not rewritten or
   backfilled. A consumer that needs one history across the cut-over reads both shapes (see the
   consumer table).

## Consumers that must switch

Any query, report, export or saved filter written against the old shape must change at the cut-over.
New rows use only the right-hand column.

Previously, then now (the new rows use only the new form):

- `entity_type = USER` and `action = user.suspend`: now `entity_type = USER_ACCOUNT` and
  `action = user_account.suspend`.
- `entity_type = USER` and `action = user.reactivate`: now `entity_type = USER_ACCOUNT` and
  `action = user_account.reactivate`.
- `action = user.deactivate`: now `action = user_account.complete_deactivation`, which follows
  `user_account.start_deactivation`. The deactivation's assignment rows are unchanged
  (`user.deactivation_assignment_revoked`).
- `entity_type = USER` and `action = membership.suspend` or `membership.reactivate`: now
  `entity_type = MEMBERSHIP` and the same action. The key moves to the membership id.
- `action = membership.revoke` as the cascade row with its counts: now
  `action = membership.assignments_revoked` (`MEMBERSHIP`). The counts are in `metadata_jsonb`.
- `action = membership.revoke` as "every revocation": now
  `action IN (membership.revoke, membership.revoke_pending, membership.revoke_suspended)`. A revoke
  of a pending or suspended membership used to write `membership.revoke` as well. It now writes
  only its FSM name.

The last bullet is the one most likely to be missed. Counting revocations by
`action = membership.revoke` undercounts after the cut-over for pending and suspended memberships,
because only the ACTIVE transition keeps that name.

Every revocation, after the cut-over, is one search:
`action_prefix=membership.revoke&outcome=SUCCESS`. The prefix matches `membership.revoke`,
`membership.revoke_pending` and `membership.revoke_suspended` (`startsWith`, see
`JooqAuditEventQueries`), and not `membership.assignments_revoked`. The `outcome` filter leaves out
the FSM's `DENIED` and `FAILURE` rows of the same names. Organisation deprovisioning revokes
memberships through the same FSM transitions, so its revocations are counted too, as they should be.
For rows from before the cut-over, the search also matches the old service `membership.revoke` row,
which carries no `from` and no `to` in `metadata_jsonb`; count only the rows that carry them (the
search has no metadata filter, so this is a query over `metadata_jsonb`, not the API).

Do not count the cascade row as a revocation. Organisation deprovisioning revokes memberships
through the FSM and writes no `membership.assignments_revoked` row, so that row is not a complete
count of revocations.

## Consequences

- Each transition has one FSM row. Two rows **with the same action** for one transition are a
  historic row (written before the cut-over) or a defect. The companion rows of Decision 1 have
  their own action names and are not duplicates, so a reader counts them as different events.
- `user.reactivate` was the service action name for `POST .../reactivate`; the permission it is
  checked against is `user.activate`. The FSM action is `user_account.reactivate`.
- The coverage registry (`HighRiskOperationAuditCoverageTests`) maps `user.suspend` to
  `user_account.suspend`, `user.activate` to `user_account.reactivate`, and `user.deactivate` to
  `user_account.complete_deactivation`. The `user` prefix is gone. The proof that a transition
  writes its row is behavioural: `FoundationLifecycleServiceTests` covers the success row and the
  `DENIED` and `FAILURE` rows, and `UserMembershipTransitionAuditIntegrationTests` asserts the exact
  rows of each user and membership route.
- No migration, no trigger, no change to `V1`-`V28`. The `audit_event` columns are unchanged.
- `UserMembershipTransitionAuditIntegrationTests` drives the real routes and asserts the exact rows
  each one writes; a duplicate or a missing row fails it.

## Alternatives rejected

- **Keep the service rows and drop the FSM rows.** The FSM row carries the from-state and to-state,
  and it is written for every transition, including rejections. Removing it would lose the
  transition record that the organisation and branch FSMs already keep.
- **Keep both rows and label them.** Two rows for one transition still double every count, and the
  label would have to be read by every consumer.
- **Backfill the historic rows to the new shape.** The rows are evidence of what was written at the
  time, and a checker rule reads them (ADR 0028). Rewriting them would change evidence after the
  fact, so the cut-over is the date and nothing is rewritten.
- **Key the transitions by the user for both aggregates.** A membership has its own lifecycle,
  and a user can hold several memberships, so one key per user would merge two histories.

## Open items

- A consumer of the platform audit log that filters on `entity_type = USER` for suspensions needs
  the switch above in the same release; there is no compatibility alias.
- Rows from before the cut-over still say `user.suspend` with `USER`. A report spanning the cut-over
  reads both shapes. Nothing purges audit rows yet (`audit_retention_days` has no job, #164), so
  the old shape stays readable for as long as the rows are kept.
- **Until the subject column (#186), a user's membership suspensions cannot be found from the
  user.** The old rows were keyed `USER` with the user id, so `entity_id=<userId>` found them. The
  FSM membership rows carry
  no user id (only the from and to states), so the same search no longer finds them. The revoke
  cascade row names the user in its metadata, but it is keyed by the membership. The typed
  `subject_user_id` column and filter are the fix; this change does not add them.

## Addendum (2026-10-13): the subject user (#186)

The open item above is closed by a typed column, not by metadata. An event about a user names that
user in `audit_event.subject_user_id`, written from `AuditCommand.subjectUserId`. The subject query
returns the events the writers name as about that user. It is not a complete history of a user:
item 3 states the exclusion.

1. **The subject is the user an event is about, never the actor by default.** A membership
   transition's row is keyed `MEMBERSHIP`, and its subject is the member. A user-account row's
   subject is the account. A branch or role assignment, and its revocation, names the target user.
   A deactivation or deprovisioning revocation names the user whose assignment it ended. An event
   about no user (an organisation, branch lifecycle, tenant setting, business date, role
   definition, accounting record) carries `NULL`. The writer table and the event families are in
   `docs/architecture/audit-logging.md` ("The subject user").
2. **The FSM rows carry the subject too.** The membership transition's subject is the user the
   transition already reads once, so no extra lookup is made. Failure and `DENIED` rows carry the
   same subject as the success row.
3. **Exclusion, and a cut-over.** Roles that `user.invite` grants are written through the store
   with no audit row. The invite's `user.invite` row (subject = the invitee) records the membership
   id only, so those grants appear in no subject query. This is pre-existing and not changed here;
   auditing the invitation's role grants as `user.assign_role` is a follow-up. Rows written before
   the release containing `V29` keep a `NULL` subject. They are not rewritten, and a subject is not
   recovered from metadata, because that would be a guess. The older rows are still found by the
   keys this ADR lists (`entity_id`, `metadata_jsonb.userId`, by SQL:
   no API filter reads metadata).
4. **No foreign key.** An audit row outlives the user it names, so `subject_user_id` has none
   (`V29`), and the partial index that serves the subject query is `V30`. Owner ruling: the user
   is an identity Keycloak owns and `user_account` mirrors, so the column is a plain reference to
   `user_account.id`, indexed only where it is not `NULL`.
5. **Subject and actor differ.** The actor answers "who did it", and the subject "who is it
   about". A user's manual-journal activity carries no subject (the event is about a journal) and
   is found by the actor filter.

The Open item "Until the subject column (#186), a user's membership suspensions cannot be found from
the user" is
resolved by this change: a membership's rows carry the member as their subject. The
`subject_user_id` search filter is added by #186.
