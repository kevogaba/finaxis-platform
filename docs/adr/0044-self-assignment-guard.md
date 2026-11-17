# ADR 0044: No Self-Assignment, Except For The Seeded Tenant Administrator

## Status

Accepted, **implemented** in the release containing this change (issue #342).

Date: 2026-11-17

The mirror image of [ADR 0043](0043-self-access-change-guard.md) (nobody removes their own
access). Builds on [ADR 0030](0030-mutation-permission-implies-view-permission.md) (a mutation
implies its view; the named 403) and the `V23` rule that the seeded administrator roles hold every
permission of their scope.

## Context

`RoleManagementService.assignRoleToUser` and `BranchProvisioningService.assignUser` checked the
caller's permission and never compared the caller with the user being assigned. The seeded
`IAM_ADMIN` bundle holds `role.create`, `role.assign_permission` and `user.assign_role`, so one
holder could compose a custom role with any tenant permission (both halves of accounting
maker-checker and the break-glass codes included) and assign it to themselves, or assign
themselves the seeded `TENANT_ADMIN`. A holder of `user.assign_branch` could likewise add
themselves to any branch, the precondition for a branch-scoped role. Every step was audited, but
nothing required a second person.

The owner's ruling: refuse self-assignment, with one intentional exception, the tenant's own super
user (the seeded `TENANT_ADMIN`, held tenant-wide in that tenant), who already holds every tenant
permission and may assign roles and branches to themselves. Everyone else is refused.

## Decision

1. **A new permission, `user.assign_self`**, carries the exception. `MUTATION`, `TENANT` scope,
   `HIGH`, module `iam`, requires `user.view`, id `40000000-…-000000000069`, seeded by `V39`.
   Runtime authorization still evaluates permission codes, never role names.
2. **Only the seeded administrator roles hold it.** `V39` grants it to `PLATFORM_SUPER_ADMIN` (which
   holds every code), to every existing tenant's seeded `TENANT_ADMIN`, and to the bootstrap
   `local-admin` (the `V23` rule). A new tenant's `TENANT_ADMIN` is every `ACTIVE` `TENANT`-scope
   code, so it gets it at seeding. No non-admin bundle names it, and
   `POST /api/v1/tenant/roles/{role_id}/permissions` refuses it for any role with 422
   `invalid_operation`; the seeded roles are not editable through the API. It therefore reaches a
   user only with a seeded administrator role: assigning `TENANT_ADMIN` to someone else hands it
   over, by design (two people, both audited), and no custom role can carry it.
3. **Self-assignment needs it, tenant-wide.** On `POST /api/v1/tenant/role-assignments` and
   `POST /api/v1/tenant/branch-assignments`, when `user_id` is the caller, the service also requires
   `user.assign_self` at tenant scope (`PermissionGuard.requireTenantPermission`, which implies
   `user.view`), right after the route's own permission check and before any other lookup (on the
   branch route, that check also answers 404 for an unknown branch, as before). `TENANT_ADMIN`
   held only at a branch, or in another tenant, does not count.
4. **So does adding to a role you hold, or re-enabling one.**
   `POST /api/v1/tenant/roles/{role_id}/permissions`, and `POST .../activate` on a `DISABLED` role,
   also require `user.assign_self` tenant-wide when the caller holds an `ACTIVE` assignment of that
   role at any scope, since either adds to the caller's own access just as an assignment would. On
   the grant it is checked after the role lock, the editability check and the 422 above, and before
   the permission lookup (so even a repeat of a grant the role already has is refused); on
   activation, after the lock and the editability check, before the transition. The holders are read
   under that lock, which every new assignment of the role also takes, so an assignment to the
   caller cannot land in between. Composing or re-enabling a role the caller does not hold, removing
   a permission from one they do, and deactivating it, are unchanged. Found in review: without it, a
   holder of `role.assign_permission` given a custom role by someone else could add any code to it
   alone, and a holder of `role.activate` could undo an administrator's disabling of it.
5. **The refusal is the standard one.** A caller without it gets `MissingPermissionException`:
   403 `forbidden`, detail `Missing permission: user.assign_self.`, recorded by
   `DeniedAccessAuditor` as one `api.access_denied` row naming the permission (ADR 0030 decision 4).
   No new error code: unlike ADR 0043's refusals, a permission now grants this action.
6. **An allowed self-assignment is audited as any assignment**: the existing `user.assign_role` or
   `branch.assign_user` row, whose actor and subject are the same user (and an allowed grant as the
   existing `role.assign_permission` row).
7. **Unchanged:** assigning someone else; the bootstrap, where the system actor assigns the first
   administrator; the invitation, whose invitee is never the inviter.

## Alternatives

- **Check the `TENANT_ADMIN` role directly.** Smallest, but it breaks "permission codes, never role
  names", and `FINAXIS-LOCAL`'s equivalent role (`local-admin`) would need a special case.
- **"Holds every tenant permission".** No new code or role name, but a custom role copying all of
  `TENANT_ADMIN` would qualify too, and nothing in the catalogue would show why.
- **A dedicated `lifecycle.self_*` refusal code**, as ADR 0043. Not needed: the named 403 already
  says which permission is missing.
- **Bounded delegation** (grant only what you hold) or **maker-checker on assignments** (issue #342
  options 2 and 3). Larger changes to what `IAM_ADMIN` is for; not chosen.

## Consequences

- One person below `TENANT_ADMIN` can no longer escalate by assigning themselves, or by adding to a
  role they hold; it now takes a second holder of `user.assign_role` (or `user.assign_branch`, or
  `role.assign_permission` for a role the second person does not hold), which is audited.
- A holder of `role.assign_permission` who also holds a custom role can no longer extend or
  re-enable it, but can still trim or disable it, and anyone else with the codes can extend or
  re-enable it.
- Not covered, because it takes two people: a holder may revoke their own assignment of a role
  (still allowed, ADR 0043), change it freely as a non-holder, and rely on a colleague re-assigning
  it without noticing the change. Both steps are audited.
- A `BRANCH_MANAGER` can no longer add itself to another branch; another holder of
  `user.assign_branch` has to.
- The catalogue is 86 codes, 85 `ACTIVE`. The permission catalogue API lists `user.assign_self`, and
  a grant drawer that offers it gets the 422.
