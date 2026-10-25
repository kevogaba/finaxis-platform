# ADR 0035: Role Archive As A Terminal State, Role Status As An FSM, And The Assignment Lock

## Status

Accepted, **implemented** in the release containing this change (issue #297, the archive half of
#171).

Date: 2026-10-25

**Supersedes [ADR 0034](0034-role-status-guard-assignment-race-and-job-exception-pass-through.md)
decisions 1 and 2**, not only its "Not covered" text: role status is no longer a guard outside the
transition infrastructure (decision 1 there), and assigning a role now takes the role's row lock
(decision 2 there). Decision 3 of ADR 0034 (job exception pass-through) stands unchanged. ADR 0034
named this change as the one that invalidates its decisions ("Reopen it when role archive or delete
is built", "The change that builds it must take the role lock"); this record is that change.

Builds on [ADR 0002](0002-fsm-transition-infrastructure.md) (the transition infrastructure),
[ADR 0030](0030-mutation-permission-implies-view-permission.md) (the permission and its view) and
[ADR 0031](0031-audit-one-row-per-transition-and-explicit-branch.md) (the FSM row is the audit
record).

## Context

A role could be disabled and re-enabled and nothing else. `ARCHIVED` has been allowed by
`chk_role_status` since `V1` and nothing wrote it; a tenant that no longer wants a role could only
disable it, and the role stayed in every list. #171 asked for archive "only when it has no active
assignments".

That precondition is what ADR 0034 said changes the picture. Until now there was no invariant
between a role's status and its assignments, so a disabled role holding assignments was harmless
(every permission query filters on `ROLE.STATUS = ACTIVE`, so they are dormant and revive on
re-enable). `ARCHIVED` is terminal and could never revive them, so **an ACTIVE assignment to an
ARCHIVED role must never exist**. Two facts make that invariant impossible to hold without a lock:

- Archiving counts the role's ACTIVE assignments and then writes the status; assigning checks the
  status and then inserts. Each side can pass its check against what the other has not yet
  committed.
- The foreign key from `user_role_assignment` to `role` does not help. An insert takes `FOR KEY
  SHARE` on the role row, and the lock the role code already takes, `FOR NO KEY UPDATE`
  (`lockRole`), does not conflict with it. Both succeed, concurrently.

There are two paths that create an assignment: `RoleManagementService.assignRoleToUser`, and the
invitation (`UserProvisioningService` through `JooqUserProvisioningStore`), which checks the role
and inserts later in the same transaction. Neither took a lock.

## Decision

1. **Role status is a finite state machine.** `RoleTransition` names four edges over the existing
   three statuses: `ACTIVATE` (`DISABLED` to `ACTIVE`), `DEACTIVATE` (`ACTIVE` to `DISABLED`),
   `ARCHIVE` (`ACTIVE` to `ARCHIVED`) and `ARCHIVE_DISABLED` (`DISABLED` to `ARCHIVED`). A
   transition graph maps one name to one source state, so archiving, legal from two states, has two
   names, as `REVOKE` and `REVOKE_PENDING` do for memberships. The graph lives in `iam.domain`
   (`RoleLifecycle`), runs through the shared `TransitionExecutor`, and writes one row to the new
   `role_transition_log` (`V33`, written by `JooqRoleTransitionLogWriter`) in the same transaction
   as the status. All three of the earlier edges moved at once, as ADR 0034 asked.
2. **One audit row per transition, from the FSM, and the action names do not change.** The row is
   written by `recordLifecycleTransition` (ADR 0031) with `ROLE` as the resource type, so
   `role.activate` and `role.deactivate` are exactly the actions they were, now also carrying the
   `from` and `to` states. **Both archive edges audit as `role.archive`**, so every archive is one
   search, the `from` state in the row says which state it left, and the transition log keeps the
   exact edge (`ARCHIVE` or `ARCHIVE_DISABLED`). A refused transition (a repeat, an archived role,
   a count above zero) writes and audits nothing, as before; this change does not add the
   `DENIED` rows the lifecycle service writes for its own refusals.
3. **`ARCHIVED` is terminal.** Reachable from `ACTIVE` or `DISABLED`; no edge leaves it, so
   activate, deactivate and a second archive are all `409 conflict`. Archive is refused with
   `409` **naming the count** ("The role has 2 active assignments; revoke them before archiving
   it") while the role has any ACTIVE assignment, of any member and at any scope, a `DISABLED`
   role included (its dormant assignments would never revive). A system role is `409`, like
   every other change to one. There is **no cascade revoke** (it would strip users of access as a
   side effect of a housekeeping act) and **no hard delete** (it ignores the assignments, the
   audit trail and the transition log that name the role).
4. **An archived role is final, hidden and keeps its code.**
   - Its permissions and its name and description are not edited: assign-permission,
     remove-permission and update are `409`.
   - It is hidden from the default role list; `status=ARCHIVED` lists it (the list's `status`
     filter is otherwise unchanged), and `GET /roles/{id}` still reads it.
   - It cannot be assigned (`422`, as for a `DISABLED` role) or invited to.
   - **Its code stays reserved.** `uq_role_organisation_code` ignores status, so creating a role
     with an archived role's code is `409`, and the message says so. This follows the owner's
     decision that a terminal state keeps its unique code taken (no partial unique index).
5. **Every path that creates an assignment takes the role lock before it reads the status.**
   `assignRoleToUser` reads the role through `lockRole` (instead of the unlocked `findRole`), and
   the invitation's role check, `UserProvisioningStore.roleExists`, now reads `FOR NO KEY UPDATE`
   and holds the lock until the transaction ends, which is past the assignments it inserts. The
   archive takes the same lock first, then counts. Whichever commits first decides: an assignment
   that committed is counted (so the archive is refused), and an archive that committed is seen
   (so the assignment is refused). An invitation naming several roles locks them in id order, so
   two invitations naming the same roles in different orders cannot deadlock.
6. **A permission, `role.archive`.** `MUTATION`, `TENANT` scope, risk `HIGH`, requires `role.view`
   (so the central check of ADR 0030 refuses a caller who holds one without the other, naming the
   first missing code). `V33` seeds it with its view requirement and grants it to
   `PLATFORM_SUPER_ADMIN`, to every tenant's seeded `TENANT_ADMIN` and to the bootstrap
   `local-admin`, the grants `V23` requires of any later permission; new tenants get it from the
   catalogue. No other role holds it. The route is `POST /api/v1/tenant/roles/{role_id}/archive`,
   idempotent like its siblings, with no request body. The archive command carries no reason (the
   siblings take none), so `role_transition_log.reason` stays `NULL` for now.
7. **No event.** Nothing consumes a role status change, so the graph declares no `eventFactories`
   and publishes nothing to the broker.

## Consequences

- A tenant can retire a role for good, with a record of who did it and from which state. The
  role stays in the database, its code reserved, and the audit trail and transition log stay
  attached to it.
- **The cost of the lock.** Assigning a role (and inviting someone to it) now serialises against
  other assignments of the same role and against its status changes and composition, for the length
  of one short transaction. It is a row lock on one role, not a table lock, and nothing else waits.
- ADR 0034's "re-enabling revives a dormant assignment" argument still holds for `DISABLED`; it no
  longer applies to a role that can be archived, which is why the lock is needed.
- The audit row of activate and deactivate gains `from` and `to` metadata. The action names, the
  resource type and the resource key are unchanged, so no consumer of those rows has to change.
- The catalogue is 84 codes (83 `ACTIVE`), and `PermissionCatalogueMetadataTests`,
  `FoundationSeedDataTests` and `JooqIamPermissionQueriesTests` pin the new size.

## Rules for the next change

- **A new path that creates a `user_role_assignment` must take the role lock first**
  (`RolePersistence.lockRole`, or `FOR NO KEY UPDATE` on the role row in its own store) and read
  the status after it. The race tests (`RoleArchiveRaceIntegrationTests`) prove the two existing
  paths and fail if either loses its lock; a new path needs its own case.
- **Unarchive, hard delete and code reuse are not built** and each needs its own decision.
  Unarchive would add an edge out of `ARCHIVED` and revisit the invariant above. Code reuse would
  need a partial unique index, which the owner has ruled out for terminal states generally.

## Not covered

- Bulk operations on roles. A bulk assignment would have to take the lock per role, in id order.
- Revoking a role's assignments in bulk before archiving it: a tenant revokes them with the
  existing revoke route, then archives.
