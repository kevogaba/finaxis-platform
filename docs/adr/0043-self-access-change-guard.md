# ADR 0043: The Self-Access Guard, Nobody Suspends, Revokes Or Deactivates Their Own Access

## Status

Accepted, **implemented** in the release containing this change (issue #341, the admin frontend's
BG-35).

Date: 2026-11-16

Builds on [ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) and
[ADR 0032](0032-per-tenant-maker-checker-switches.md) (the maker-checker refusals whose shape
this copies) and
[ADR 0031](0031-audit-one-row-per-transition-and-explicit-branch.md) (the audit actions the
refusal rows reuse). Its mirror image, a caller *adding* to their own access, is #342 and is not
decided here.

## Context

Every path that ends a membership or an account checked only the caller's permission, then
transitioned. Nothing compared the caller with the subject, so a caller could:

- suspend their own membership (`POST /api/v1/tenant/memberships/{membership_id}/suspend`): the
  next request re-validates an `ACTIVE` membership and the caller's context is gone;
- revoke it (`.../revoke`): the cascade ends every branch and role assignment at once. Since #304 a
  revoked member can be re-invited, but only by someone else;
- suspend their own account (`POST /api/v1/platform/users/{user_id}/suspend`): no sign-in anywhere;
- deactivate it (`.../deactivate`): permanent, and for the last `PLATFORM_SUPER_ADMIN` recovery is
  SQL.

The admin frontend disables these controls on the caller's own record, but nothing stopped a
direct call. A lock-out by mistake, or by a script, needs a second administrator or an operator to
undo, and the deactivation can't be undone at all.

## Decision

1. **Four actions refuse a caller acting on themselves**, each with a `403` and its own code:
   - membership suspend and revoke, when the membership's user is the caller:
     `lifecycle.self_membership_change`, "A member cannot suspend or revoke their own membership.";
   - account suspend and deactivate, when the account is the caller's:
     `lifecycle.self_account_change`, "A user cannot suspend or deactivate their own account."
2. **The check runs after the permission and before any write.** The order is the permission check
   (a caller without it still gets `403 forbidden`, mutation code named), then the lookup (an
   unknown membership is still `404`), then this check, then the transition. Nothing is written
   and nothing cascades.
3. **The refusal is audited once, durably.** `SelfAccessGuard` records one `DENIED`, `HIGH` row
   through `AuditService.recordIndependently` (so the caller's rollback can't take it) under the
   action of the first transition the request would have run (`membership.suspend`,
   `membership.revoke`, `user_account.suspend`, `user_account.start_deactivation`; a deactivation
   is two transitions, and the refusal stops it before the first), keyed by the aggregate
   (`MEMBERSHIP` or `USER_ACCOUNT`), with the code as `reason` and the caller as
   `subject_user_id`.
   `DeniedAccessAuditor` writes no second `api.access_denied` row for a `lifecycle.self_` code, as
   it already skips `lifecycle.approver_is_`.
4. **Still allowed, on purpose:**
   - reactivating (a suspended caller can't reach the route anyway) and editing one's own
     membership (`PATCH`, type and primary branch: no access is removed);
   - revoking one's own role or branch assignment. It is recoverable by any other holder of
     `user.assign_role` or `user.assign_branch`, it is how someone drops a role they no longer
     need, and the frontend already warns before it;
   - assigning a role or a branch to oneself, which is #342.

## Alternatives

- **`409 conflict`.** Rejected: nothing about the resource's state conflicts. The caller is not
  allowed to do this to this subject, which is what the `403` maker-checker refusals
  (`lifecycle.approver_is_*`) already say for approvals.
- **Refuse every self change, assignment revokes and grants included.** Rejected for now: it blocks
  harmless clean-up, and self-grants are a separate decision about escalation (#342).
- **A last-administrator guard** (refuse suspending the last `TENANT_ADMIN` or
  `PLATFORM_SUPER_ADMIN`, whoever does it). Out of scope: it needs a count under a lock across
  roles, and suspending someone else is an intended, audited administrator action.
- **Frontend-only.** Rejected: the API is the boundary.

## Consequences

- A caller can no longer lock themselves out through these four routes. Another administrator
  still can, which is the point of administration.
- The frontend's own-record controls now match the API. Its fake API should answer the two codes
  when it adopts the change.
- Two new problem codes. A client that keyed on `forbidden` for these routes now also sees
  `lifecycle.self_*`.
