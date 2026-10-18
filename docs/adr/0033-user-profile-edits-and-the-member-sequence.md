# ADR 0033: User Profile Edits, Platform-Only Profile Data, And The MEMBER Sequence

## Status

Accepted, **implemented** in the release containing this change (issue #161).

Date: 2026-10-18

## Context

The platform stores a user's phone and last login but returned neither, and nothing about a user or
a membership could be edited after the invitation. The tenant sequence `MEMBER` is created for
every tenant at provisioning and consumed by nothing, and `user_organisation_membership` has no
member-number column. An invitation for an email that already has an account silently ignores the
phone, username and display name it carries.

## Decision

1. **Phone and last login are platform-only profile data.** `GET /api/v1/platform/users/{id}`
   returns `phone_e164` and `last_login_at`. No tenant route returns either: a user account is
   global, a tenant sees the people it invited, and a phone number is personal data that a tenant
   administrator has no need of. `last_login_at` is recorded only while an account is `ACTIVE` or
   `INVITED`, so it can be `null` for a user who has logged in since.
2. **Two edit permissions, with the scope of the thing edited.**
   - `user.update` (`MUTATION`, `PLATFORM` scope, `HIGH`, requires `user.view`) gates
     `PATCH /api/v1/platform/users/{user_id}`: display name and phone. A user account is global, so
     it is edited by the platform, like the lifecycle routes beside it (`user.suspend`).
   - `membership.update` (`MUTATION`, `TENANT` scope, `HIGH`, requires `membership.view`) gates
     `PATCH /api/v1/memberships/{membership_id}`: membership type and primary branch.
   Both follow ADR 0030 (the view is implied and checked first, centrally) and V23 (every seeded
   tenant administrator holds `membership.update`; `user.update` goes to no tenant role).
3. **An edit is not a transition.** It writes one `SUCCESS` audit row of its own (`user.update`,
   `membership.update`) naming the changed fields and never their values, with the user as the
   subject. There is no FSM row to repeat (ADR 0031). A phone is replaced, never cleared; the
   primary branch can be cleared with an explicit `null`.
4. **Limits of a membership edit.** A `REVOKED` or `SYSTEM` membership is not edited, `SYSTEM` is
   not assignable (it marks the platform's own accounts), and a primary branch must be an `ACTIVE`
   branch of the same tenant. An `ACTIVE` membership cannot take `STAFF` or `ADMIN` while the user
   has no active branch assignment (`409`), so an edit never leaves a state activation refuses to
   create. The membership row is locked for the check and the write. A user edit refuses a
   `DEACTIVATED` or `ARCHIVED` account and validates the display name after trimming.
5. **An invitation for an existing account keeps ignoring its profile fields, and says so** (no
   `409`): the account already has an owner of its profile. Changing the phone or name is a
   `user.update`, by the platform. The invite documentation states this.
6. **No member number is allocated, and the `MEMBER` sequence stays reserved.** There is no product
   need on record for a member or staff number, and a gapless counter nobody reads is cost with no
   reader. The sequence row stays (provisioning's setup check still requires it, and dropping it
   would be a schema change with no benefit); allocating from it, with a column and its own
   uniqueness rule, is a decision for the day a product needs the number. It is tracked as a
   follow-up issue, not built.

## Consequences

- A platform administrator can correct a user's name or phone without Keycloak or SQL, audited.
- A tenant administrator can fix a member's type or home branch without revoking and re-inviting.
- The catalogue is 83 codes (82 `ACTIVE`).
