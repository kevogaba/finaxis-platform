# ADR 0034: Role Status Guard, Assignment Race, And JobRunr Exception Pass-Through

## Status

Accepted. Three review findings on the P2 bug pass of tracker #177 (#289, #290, #291) asked for more
than those changes built. The owner delegated the call on each to the engineer on 2026-10-18; this
record is that call, with the conditions under which it should be reopened.

**It describes behaviour introduced by #289 (`RoleManagementService.changeStatus` and the role
lock), #290 (the status check and permission-first order in `assignRoleToUser`) and #291 (the
welcome-email handler's failure handling), none of which is on `main` when this is written. Merge
it after those three**, so the record never describes code the branch does not have.

Date: 2026-10-18

## Context

The P2 bugs of #177 were fixed as small stacked changes. Automated review raised three findings
that were neither plainly right nor plainly wrong, each asking for a larger mechanism than the
change needed:

1. **#289 (Codex, P1): model the role status edges as `TransitionDefinition`s.** Activating a
   `DISABLED` role and disabling an `ACTIVE` one are now checked by a status guard in
   `RoleManagementService.changeStatus`, outside the common transition infrastructure.
2. **#290 (Sentry): lock the role while assigning it.** `assignRoleToUser` reads the role's status
   without a lock, so a concurrent `deactivateRole` can commit between the read and the insert.
3. **#291 (Codex): sanitise a `JobRunrException` thrown by a collaborator.**
   `SendWelcomeEmailJobRequestHandler.run` passes a `JobRunrException` through unchanged, as the
   lifecycle handlers do, so one raised below it would reach JobRunr with whatever message it holds.

## Decision

1. **Role status stays a guard, not an FSM, until roles gain a third state.** The transition
   infrastructure (ADR 0002) is for lifecycles with a transition-log table, an audit row per
   transition (ADR 0031) and often events: organisation, branch, user and membership. A role has
   none of those today and the schema has no `role_transition_log`; its two edges are reversible
   configuration changes that already write one `role.activate` / `role.deactivate` audit row. The
   guard sits in one private method, takes the role's row lock before reading the status (so of two
   concurrent calls the second is a clean 409), and is covered by unit and HTTP tests.

   Declaring the two edges as definitions would add a migration, a log table and a changed audit
   shape for no behaviour the guard does not already have. **Reopen it when role archive or delete
   is built** (`ARCHIVED` exists in the `chk_role_status` check and nothing writes it): a terminal
   state with a precondition is where a graph, a transition log and the audit shape of ADR 0031
   pay for themselves, and that change should move all three edges at once.
2. **Assigning a role takes no lock on the role.** The race ends in a state the system already
   allows. Disabling a role never removes its assignments; every permission query filters on
   `ROLE.STATUS = ACTIVE`, so an assignment on a disabled role is dormant and grants nothing, and
   re-enabling the role revives it exactly as for any assignment made before the role was
   disabled. A lock on every assignment would serialise assignments of one role against each other
   and against status changes to prevent that outcome.

   **The decision depends on there being no invariant between a role's status and its
   assignments.** Role archive, as specified in #171 ("only when it has no active assignments"),
   adds one. The change that builds it must take the role lock (`lockRole`) in `assignRoleToUser`
   as well as in the archive, or the race becomes a way to archive a role that has a live
   assignment.
3. **A handler passes through the `JobRunrException` it throws itself and nothing else needs
   wrapping today.** No collaborator the welcome-email handler calls throws one: the recipient
   directory is jOOQ, the email gateway throws by contract only `PermanentEmailDeliveryException`
   and `RetryableEmailDeliveryException`, `JobStepGuard` rethrows its action's failure and
   `AuditService` writes through a jOOQ repository. Only the handler (permanent failure) and the
   lifecycle invite handler construct one, so a foreign `JobRunrException` could come only from
   JobRunr's own framework code, which carries none of our data. The Keycloak provisioning handler
   passes one through the same way. Sanitising a foreign one would need a marker type to tell it
   from the handler's own, for a case no caller produces.

   **A collaborator that can throw a `JobRunrException` must not be added behind a handler
   without translating its failure at that boundary** (to a message-free exception of the handler's
   own, as the email gateway does), or the pass-through becomes a leak.

## Consequences

- No migration, no new type and no lock for these three; the code is the smaller one in each case.
- Each decision names the change that invalidates it, so the next engineer to build role archive
  or add a handler collaborator meets the condition in the record, not in a review thread.
- The three review threads are answered with a pointer here rather than left open, and the PRs'
  descriptions list them as decided.
- Reversing any of them is additive: a log table and graph for roles, a lock call in one method, or
  a wrapper in one handler.

## Not covered

The archive and delete half of #171 is not built and still needs its own decision (whether roles
get a terminal state, whether it requires no active assignments, whether the code becomes
reusable). This record only says what that change must do about the three points above.
