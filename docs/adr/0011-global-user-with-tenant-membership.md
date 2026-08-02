# ADR 0011: Global User Account With Tenant Membership

## Status

Accepted

Date: 2026-08-02

## Context

A SACCO platform operator, an auditor, and a group treasurer can all legitimately need access to
more than one organisation. The obvious alternative — one user row per organisation — forces a
person with three organisations to hold three accounts, three passwords in the identity provider,
and three separate audit trails that nothing links together. It also makes "who is this human"
unanswerable across tenants, which is exactly the question a fraud investigation asks.

The platform also authenticates through Keycloak, which issues one subject per human. A
per-organisation user model would either need one Keycloak identity per organisation, or a
many-to-one mapping from application users to a single subject — reintroducing the global user
concept in a less explicit place.

## Decision

`user_account` is global. It is unique on `LOWER(email)` and `LOWER(username)` across the whole
platform, and it carries no organisation column. A user exists once, regardless of how many
organisations they can reach.

Organisation access is represented exclusively by `user_organisation_membership`, unique on
`(organisation_id, user_id)`. A membership carries its own lifecycle status, membership type,
primary branch, and audit trail. **No membership means no access** — there is no implicit access
path from a `user_account` row to organisation data.

Everything organisation-scoped hangs off the membership or off `(organisation_id, user_id)`, never
off `user_id` alone:

- `user_branch_assignment` is keyed `(organisation_id, user_id, branch_id, assignment_type)` and
  its foreign key `fk_user_branch_assignment_membership (organisation_id, user_id)` targets the
  membership, so an assignment cannot exist without one.
- `user_role_assignment` is organisation-scoped with composite foreign keys to both the membership
  and the organisation's own `role` row.
- `membership_permission` layers direct allow/deny overrides on a specific membership.

Roles are organisation-owned, not global. The same `role_code` may exist independently in every
organisation, and `uq_role_organisation_code` scopes uniqueness to `(organisation_id, role_code)`.
Only the reserved `PLATFORM` organisation holds the two global platform roles.

Composite foreign keys carry the tenant boundary into the database rather than relying on
application filtering. `fk_user_branch_assignment_branch (organisation_id, branch_id)` makes it
physically impossible to assign a user in organisation A to a branch in organisation B, even if
an application-layer bug tried.

Because a user is global but access is per-organisation, a request must resolve an **active
organisation context** before any tenant data is read or written — see ADR 0001.

## Consequences

A user's effective permissions are only meaningful relative to a membership, so every
authorization decision needs organisation context. `EffectivePermissionResolver` resolves
permissions per membership, and a suspended membership denies everything regardless of the roles
attached to it.

Deactivating a person is two distinct operations with different blast radius: suspending the
global `user_account` locks them out everywhere, while revoking one membership removes access to
one organisation only. Both are modelled as separate lifecycle transitions (ADR 0013).

Email and username uniqueness is a platform-wide constraint, so two organisations cannot
independently onboard the same email address as separate people. This is intentional — they are
the same human — but it means onboarding must handle "this user already exists, add a membership"
rather than always creating a user.

The composite-foreign-key approach costs an extra unique index per parent table
(`uq_branch_organisation_id`, `uq_membership_organisation_id`, `uq_role_organisation_id`) purely
to give the composite keys a target. That is a deliberate trade of a little write cost for a
tenant-isolation guarantee the application cannot bypass.

## Alternatives Considered

One user row per organisation:

- Rejected. Multiplies identities for multi-organisation humans, fragments the audit trail, and
  conflicts with Keycloak issuing one subject per person.

Global roles shared across organisations:

- Rejected. Organisations need to define their own roles without coordinating with each other or
  with the platform. Global permission *codes* with organisation-owned *roles* gives a stable
  authorization vocabulary and local flexibility.

Rely on application-layer `WHERE organisation_id = ?` filtering instead of composite foreign keys:

- Rejected. One missing predicate becomes a cross-tenant data leak. The composite keys make the
  class of bug unrepresentable in the database.

## Verification

- `FoundationSchemaConstraintTests` — unique membership per tenant, cross-tenant branch assignment
  rejected at the database level, duplicate active role assignment rejected
- `EffectivePermissionResolverTests` — suspended membership denies all permissions; direct deny
  overrides role allow
- `AuthFlowIntegrationTests` — context for another organisation rejected; missing active branch
  assignment rejected
- `UserProvisioningServiceTests` — duplicate active membership rejected
- ADR 0001, ADR 0012, ADR 0013
