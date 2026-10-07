# ADR 0030: A Mutation Permission Implies Its View Permission

## Status

Accepted

Date: 2026-10-04

Supersedes in part
[ADR 0028](0028-platform-checker-for-first-tenant-approvals.md) (the sentence "each route works
with its one permission alone" and the permission-free read-back it justified) and amends
[ADR 0029](0029-approval-model-per-resource-extensions.md) wherever it names the permission a
route needs. Builds on [ADR 0012](0012-keycloak-authentication-application-authorization.md) (the
application owns authorization, by permission code and never by role name) and on
[ADR 0018](0018-financial-transaction-atomicity-invariant.md) (a mutation and its stored response
commit or roll back together). Like ADR 0029 it records decisions and a plan: **nothing described
under "Decision" is implemented by this ADR**, and the code is unchanged by it. The rollout is the
stack under "Rollout"; each change takes effect as its pull request lands, and the supersessions
below take effect with the pull request that implements the sentence they replace.

## Context

### The defect: 23 routes mutate, then answer 403, and roll back

Many mutation routes return the resource they changed. They build that response by reading it back
through the same permission-gated query a `GET` uses, **inside** the idempotent transaction:

- `IdempotencyMutationAspect` runs the controller method inside `IdempotencyExecutor.execute`,
  which is `@Transactional` and so opens the outer transaction. The services are `@Transactional`
  with `REQUIRED` propagation and join it, and so does the read-back.
- A caller who holds the mutation permission but not the matching `*.view` therefore passes
  `@PreAuthorize`, passes the service's own check, **performs the mutation**, and is then refused
  by the read-back with `403 forbidden` and the generic "You are not permitted to perform this
  action." The exception leaves `execute`, and the whole transaction rolls back: the state change,
  its transition-log row, its audit rows, the outbox rows and the idempotency row.
- Nothing is stored for replay, so a retry re-executes and fails the same way. The client cannot
  tell "you may not do this" from "you did it, but you may not see it", and the refusal is logged
  at `debug` only.

Twenty-three routes have this shape: five tenant branch transitions, four tenant membership
transitions, five tenant role mutations, branch-assignment assign, and eight platform tenant
routes. Three more (role-permission remove, role-assignment revoke, branch-assignment revoke) read
**before** they mutate, which is a clean 403 but makes a view permission a hidden requirement.

Who is hit:

1. **Custom roles.** Tenants compose their own roles, and nothing requires a role that holds a
   mutation code to hold its view. (Every seeded bundle does.)
2. **Any role assigned at BRANCH scope.** The five tenant branch transitions authorise at the
   **target branch** (tenant grants plus grants on that branch), but the read-back needs the
   **tenant-scope** `branch.view`. A `BRANCH_MANAGER` assigned to branch A, pinned to A, suspends A
   and is refused afterwards: the deployment the branch-scope grant exists for cannot work.
3. **A direct `DENY` of the view code**, which the resolver applies last.
4. **Custom platform roles**, such as an ADR 0028/0029 checker role holding only `tenant.approve`
   and `tenant.reject`.

Seven routes escaped this by reading back **without** a permission gate (the
`get...AfterAuthorizedMutation` helpers, the platform checker routes, branch `PATCH` and branch
return, tenant return, platform membership activate), on ADR 0028's rule that each route works
with its one permission alone. That is the opposite policy on the other 23 routes, and it makes
the mutating caller a reader of data it holds no read permission for. It also hid a hazard: today
the buggy gated read-back is the only thing that rolls back a platform administrator suspending the
platform organisation itself (ADR 0028 point 6 and the platform-organisation guard of #205 now
refuse that directly).

### The owner's permission-model principle

> Every read requires its own `*.view` permission, and every mutation its own explicit permission
> (creation, approval and so on). Every route has an explicit permission. A role that holds a
> mutation permission must also hold the matching view permission, so the user can see their
> writes.

The defect is a violation of that principle, not of the routes: the model never guaranteed the
second sentence. The fix is to guarantee it, rather than to make reads permission-free.

## Decision

### 1. The rule

**Every permission code is exactly one of: a view, a context code, or a mutation that requires one
or more view codes. A role (and so an effective permission set) that holds a mutation code must
hold every view code the catalogue pairs with it.** Reads after mutations stay
**permission-gated**: a mutation returns its result by the same gated query a `GET` uses, at the
scope the mutation was checked at. A permission-free read-back was considered and **rejected**
(see "Alternatives rejected"). Because the rule makes the gate unfailable after the mutation (point
4), the gated read-back costs the mutating caller nothing it was not already entitled to.

### 2. Role composition rejects violations

`POST /tenant/roles` and `PATCH /tenant/roles/{id}` carry no permission codes, and seeded roles are
immutable, so a role is composed only by `assign-permission` and `remove-permission`. Those two
operations enforce the rule:

- **Assign** a mutation code whose views the role does not already hold (counting only `ACTIVE`
  codes, as runtime resolution does) answers **400 `validation_failed`**, listing every missing
  view code and the code that needs it, before anything is written.
- **Remove** a view code that another held mutation code needs answers **400 `validation_failed`**
  naming the dependants. Removing a mutation code, or a context code, never fails.
- Adding a view never fails. Adding a mutation fails only on **its own** missing views, so a legacy
  violating role (point 3) can be repaired one code at a time.
- Both operations take a row lock on the role first, so two concurrent requests (one removing
  `branch.view`, one adding `branch.suspend`) cannot each validate against their own snapshot and
  both commit a violating role.
- The permission catalogue API exposes each code's requirement as **`required_view_permissions`**
  (sorted; empty for views and context codes), so a UI can tell the user which views to grant
  together with a mutation.

### 3. Existing violators: report, do not backfill

There is **no backfill migration**. A role that already holds a mutation without its view stays as
it is. Role list and role detail gain **`missing_view_permissions`**, a sorted flat list of the
view codes the role needs and lacks (`[]` when it complies), computed for the roles on the page
only, so pagination is unaffected. Activating a legacy violating custom role stays allowed; the
report is the signal, and the mutation-time check (point 4) is the protection.

Roles are not the whole lock-out population. A membership's **effective** permission set is its
`ACTIVE` role grants plus its direct `ALLOW` overrides, minus its direct `DENY` overrides, so a
direct `ALLOW` of a mutation without its view, or a direct `DENY` of a view a held mutation needs,
violates the rule although every role the membership holds complies. `missing_view_permissions`
on roles cannot see that. Before the enforcement pull request ships, the plan therefore also
**reports violating memberships**: a membership-level `missing_view_permissions` on the membership
detail, or an operator report over the same effective set (the form is decided in the implementing
pull request), so such principals are discoverable before they meet a production 403.

### 4. Mutation-time check: central, with a named 403

Every check the application makes through the `PermissionGuard` adapters requires the mutation
code **and every view code the catalogue pairs with it, at the same scope**: tenant, target
branch, or the platform organisation. The check runs before any existence lookup, so a refusal
reveals nothing about whether an id exists.

- **Enforcement is central**, in the lifecycle permission guard adapter **and in the accounting
  guard adapter**, not typed again at each call site. Every route already names its mutation code
  explicitly; the view comes from the catalogue. A route cannot forget it, and the scope is
  whatever the call already uses. Accounting has no inbound web adapter yet, but it adopts the
  check now, so the first accounting route inherits it.
- The refusal is a new `MissingPermissionException` (a `ForbiddenOperationException`): `403`,
  `application/problem+json`, `code` still `forbidden`, with a `detail` that **names the first
  missing code** ("Missing permission: branch.view."), mutation code first. Every refusal the guard
  adapters make names the code it needs. Codes are public vocabulary (the catalogue, the OpenAPI
  document and `GET /auth/me` already list them), and the check precedes any existence lookup, so
  naming adds no oracle. `@PreAuthorize` coarse gates are unchanged and stay generic, **except the
  read endpoints made target-aware by point 5**, whose coarse gates are removed or replaced by
  authentication-only access plus the application-layer target-aware check. The view is **not**
  added to the gates of mutation routes (an unnamed 403, a duplicate of the application check, and
  the wrong scope for a branch-scoped caller who is not pinned). Each route's OpenAPI description
  lists both codes.
- A refusal now happens **before any change**, inside the transaction, so nothing is written and a
  retry re-evaluates. It sits first in the existing order: body validation (400), `@PreAuthorize`
  (403), then in the service the **mutation and view check (403, named)**, the platform-organisation
  404 or 409, resource existence (404), the ADR 0028 window (409), state (409), the mutation, and
  the gated read-back.
- Because the read-back asks for the same view at the same scope, it hits the same per-request
  permission memo as the pre-check and cannot fail on grants. It can fail only if the organisation
  stops being `ACTIVE` between the two (a concurrent committed suspension). That is a known, benign
  race: the whole request rolls back with a named 403, and a retry fails cleanly at the pre-check.
- A **direct `DENY`** of a view removes it from the resolved set, so the operator who holds a
  mutation and a `DENY` of its view cannot mutate either, by design. No endpoint writes direct
  overrides today. A future writer must apply the composition rule to the membership's effective
  set.
- **Replay** is unchanged: an idempotent replay returns the stored response of the caller's own
  completed write after `@PreAuthorize` only, even if the caller has since lost the view.

Some route families check only in the controller today (tenant membership transitions, platform
tenant transitions, `user.invite`, platform user lifecycle: `UserProvisioningService` performs no
permission check for suspend, reactivate or deactivate, which live only in the platform user
controller). Each moves its check into the application service in its own pull request (6b, 6d
and 6e below), so the central pre-check covers it.

### 5. Target-branch-aware views for branch resources

Branch resources are authorised at the branch they concern. For these views, the check is **a
tenant-wide grant OR a grant on that branch**:

- **`branch.view`** for `GET /branches/{id}`, for the mutation pre-check and for the read-back;
- **`branch_assignment.view`** for the assignment reads, the pre-check and the read-back;
- **`role_assignment.view`** for rows with `scope_type = BRANCH`; tenant-scope rows need the
  tenant-wide view.

A branch-scoped holder asking for a branch it holds nothing on, or for an unknown id, gets **403**;
a tenant-wide holder gets 404 for an unknown id. This is identical to the mutation routes, so there
is no existence oracle. **Lists show what the caller may see**: `GET /branches` and the
assignment lists return every branch the caller holds the view on (a tenant-wide grant means all),
with the restriction applied in the query, so pages and totals stay correct, and an explicit
`branch_id` filter outside the visible set is 403. A caller with no grant at all gets 403. One
rule for every tenant caller, tenant-wide or branch-scoped, covers the selected branch: with no
explicit `branch_id`, the branch-assignment list defaults to the selected branch **when the caller
may view it**, and is every viewable branch otherwise. It only ever narrows, so visibility alone
decides what may be seen. `scope_type` on the role-assignment list is matched like every
enum-like filter (exact value, otherwise an empty page).
Platform callers are unchanged: the platform view is checked in the platform organisation.

The read endpoints for branches, branch assignments and role assignments (list and by id) carry
an endpoint-level `@PreAuthorize("hasAuthority('...view')")` today, and that gate evaluates the
**pinned** branch's authority set. A caller pinned to A who holds the view only through a role
scoped to B would be answered `403` before the target-aware application query ran, which
contradicts "lists return every branch the caller may view". So these coarse gates are **removed
or replaced by authentication-only access**, and the application-layer target-aware check is the
only authorisation for those reads (it still answers `403` for a caller with no grant at all, so a
route is never open). Mutation routes keep their coarse gates (point 4).

This closes the BRANCH-scope case of the defect: a `BRANCH_MANAGER` assigned to A can read A,
suspend A and read it back, and still gets 403 on B. The branch pin keeps its meaning for what the
caller *does*; it no longer narrows what the target-aware reads may *see* (it is only the
default for an unfiltered list when the caller may view that branch, for every tenant caller
alike).

### 6. The permission-free read-backs go away

All seven routes that read back permission-free today, which include the platform checker routes
of ADR 0028, come under the same rule. They read back through the gated query like the other 23:
platform branch submit, activate and return; platform tenant return; platform membership activate;
tenant branch `PATCH` and return.

- The `get...AfterAuthorizedMutation` helpers (five declarations) are **deleted**, and an
  **ArchUnit** guard forbids them from coming back: no method of that name may exist; web adapters
  may call only query methods that take a caller and are gated by the `PermissionGuard`; a gated
  method must actually call the guard; and web adapters do not reach query or store ports
  directly (the platform tenant controllers read the initial-administrator bootstrap store
  directly today; that data folds into the gated tenant detail).
- The two "by first page of 100" read-backs (role permission grant, branch assignment assign)
  become by-key reads, which also removes a latent 404-after-mutation for a branch with more than
  100 assignments.
- **The ADR 0028 sentence "each route works with its one permission alone" is superseded** (see
  "Supersession"). The platform checker roles must hold the view codes too: `user.approve`
  with `membership.view` (and `user.view` for an invite), `branch.approve`, `branch.create` and
  `branch.update` with `branch.view`, `tenant.approve`, `tenant.reject` and the other tenant
  codes with `tenant.view`, all in the platform organisation. `PLATFORM_SUPER_ADMIN` holds every
  code and is unaffected.

### 7. The mapping, and where it lives

The map has **81 codes** at this writing (`FoundationSeedDataTests`; 80 `ACTIVE`, the deprecated
`branch.activate` being the other): 18 views, 3 context codes and 60 mutations. The full table
lives in the catalogue metadata (point 8), is published through the catalogue API, and is not
repeated here. By family:

- **Tenant**: `tenant.create`, `update_draft`, `submit_for_approval`, `approve`, `reject`,
  `suspend`, `reactivate`, `deprovision`, `bootstrap_retry`, `activate`. Requires `tenant.view`.
- **Branch**: `branch.create`, `update`, `approve`, `suspend`, `reactivate`, `close`, and the
  deprecated `branch.activate`. Requires `branch.view`. `branch.approve` is the one permission that
  approves a branch (tenant `/activate`, the platform checker route and the checker's half of
  `/return`) since V21; `branch.activate` is deprecated and checked nowhere.
- **Membership**: `user.approve`, `membership.suspend`, `reactivate`, `revoke`. Requires
  `membership.view`.
- **User (global)**: `user.activate`, `suspend`, `deactivate`. Requires `user.view`.
- **Roles**: `role.create`, `update`, `activate`, `deactivate`. Requires `role.view`.
- **Settings**: `settings.update`. Requires `settings.view`.
- **Business date**: `business_date.advance`, `reopen`, `cob.start`, `cob.complete`. Requires
  `business_date.view`.
- **Accounting**: `gl_account.*`, `fiscal_period.*`, `journal.*`, `posting_rule.*`,
  `reconciliation.*`, `accounting_report.export`. Requires the view of the same resource.

Views: `tenant.view`, `branch.view`, `user.view`, `membership.view`, `branch_assignment.view`,
`role.view`, `role_assignment.view`, `permission.view`, `settings.view`, `business_date.view`,
`audit.view`, `iam.profile.read` (a read by definition), and the six accounting views.

The exemptions and the pairings that needed a decision:

- `user.invite`: needs **both** `membership.view` and `user.view`: it creates a membership and its
  `Location` is the user. This is the reason the map is multi-valued.
- `user.assign_branch`, `user.revoke_branch`: `branch_assignment.view` (the resource written and
  returned; not also `branch.view` or `membership.view`, which would make seeded bundles
  non-compliant).
- `user.assign_role`, `user.revoke_role`: `role_assignment.view`, target-branch aware for
  BRANCH-scope rows.
- `role.assign_permission`, `role.remove_permission`: `role.view`, not also `permission.view`:
  browsing the catalogue is not seeing one's write.
- `tenant_setting.manage_platform`: **exempt context code**: it gates reading and writing a
  platform-only setting with the same code and is checked in PLATFORM, while `settings.view` lives
  in the tenant, so no role can hold both.
- `auth.select_organisation`, `auth.select_branch`: **exempt context codes**: they gate the caller's
  own context, and are their own read and write.
- `branch.approve`: mapped to `branch.view`. It is **live**: V21 made it the permission every
  branch approval checks, so it is a mutation like any other and its view is required at the target
  branch (or in the platform organisation on the platform checker route).
- `branch.activate`: **deprecated and ignored** (V21; runtime honours only `ACTIVE` codes and no
  route checks it). It is mapped to `branch.view` only because the catalogue keeps deprecated codes
  classified; the mapping has no runtime effect.
- `tenant.activate`: mapped to `tenant.view`. It is seeded and checked nowhere today, so it is a
  **dead code that stays mapped**; retiring it is a separate decision.
- `journal.post_prior_period`: `journal.view`: a break-glass modifier, but a write to the journal.
- `journal.create_manual`, `posting_rule.create`, `posting_rule.update`: own-resource view only;
  naming `gl_account.view` as well is a UI convenience, not "seeing one's write".

Under this mapping no seeded role violates the rule (the platform roles, the `local-admin` role and
every bootstrap tenant bundle). A catalogue test proves it (point 8).

### 8. The mapping is stored in the database

The mapping is **data in the permission catalogue**, delivered by **one forward-only migration**
of catalogue metadata (V22, in the next pull request). It adds `permission.kind` and
`permission.grant_scope`, both `NOT NULL` once every existing code is classified, and the join
table:

- a **`permission.kind`** column classifying each code `VIEW`, `MUTATION` or `CONTEXT`, so "needs
  nothing" is explicit and not implied by an absent row. The catalogue test requires every
  `MUTATION` code to have at least one row in `permission_view_requirement` and every `VIEW` or
  `CONTEXT` code to have none, so a new code cannot ship without a classification;
- a join table **`permission_view_requirement`** pairing a permission with each view it requires.
  A join table, not an array column, because `user.invite` needs two views, and so a pairing is a
  row that can be constrained (both ends reference `permission`) and queried;
- a **`permission.grant_scope`** column, classifying each code `TENANT` or `PLATFORM`. This is
  the other consumer of the same migration: the seeded admin role bundles are derived from it
  ("an admin holds every permission of its scope"). That rule is **decided separately** (see "Open
  items") and is referenced here only because it shares the catalogue metadata.

The map is **not** a Kotlin object. The reasons it was weighed against one and the cost accepted:

- The catalogue is already reference data in `permission`, seeded by migrations. The pairing is a
  property of a code, so it belongs next to the code, in the one place a new code is added.
- The catalogue API, the composition check, the role report, the guard adapters and the operator
  query all read the same rows. A Kotlin object would need a parallel copy in the database for the
  operator query, or operators would have no way to find violators without the application.
- The map is policy that must stay consistent with seeded data, and a migration that adds a code
  adds its requirement in the same file. A catalogue test closes the loop in both directions.
- **Cost accepted:** the table is runtime-editable by SQL, so a hotfix could silently weaken the
  rule. Mitigation: it is documented as reference data changed only by forward migration, and the
  catalogue test asserts the migrated database.
- The guard adapters read the map through a port and may cache it, since the catalogue changes only
  by migration. The lifecycle module keeps calling only the `PermissionGuard` port, so no new
  module dependency arises.

The **catalogue test** (Testcontainers) fails when a code is unclassified (a new code cannot ship
without its requirement), when a requirement names a missing code or a non-view, when a seeded or
bootstrap role breaks the rule, and when an `ACTIVE` mutation requires a view that is not `ACTIVE`
(runtime honours only `ACTIVE` codes, so a deprecated view would otherwise make its mutations
unusable).

### 9. Defaults that stand

- The guard **names the missing code on every application 403** it raises (point 4), not only on
  view-coupled denials. A resource-level `AccessDeniedException` that carries a resource reference
  stays generic, as its message holds ids.
- Activating a legacy violating custom role is allowed (point 3).
- Branch lists show every branch the caller holds the view on (point 5), not only the pinned one.
- The dead code `tenant.activate` and the deprecated `branch.activate` stay mapped.
- Platform roles have no composition or listing API, so they cannot be reported through
  `missing_view_permissions`. Their only signal is the named 403.

### 10. Future end state: the service returns the post-state

The cleanest end state is that a service command returns the post-state it read inside its own
authorised method, as `BusinessDateService` already does, so no read helper is exposed at all.
That is **recorded as the end state and not built now**: it changes service signatures and needs
new port types to carry `RoleDetail` and assignment details across the IAM boundary. The present
rule leaves it open and does not depend on it.

## Consequences

- **Lock-out risk at deploy.** Any custom tenant role, any platform checker role and any direct
  override that holds a mutation code without its view stops being able to mutate when the check
  ships. There is no backfill, so this is deliberate and visible. What the caller gets is a clean
  `403` naming the missing view and **no change**, never a mutation that rolls back. Mitigations:
  - ship the reporting (`required_view_permissions`, `missing_view_permissions`) and the
    composition rule **one release before** the check, so operators can see and repair roles
    first;
  - publish an **operator SQL query** that lists roles whose `ACTIVE` codes miss views, computed
    from `permission_view_requirement`, which also covers platform roles that no API lists, and
    that covers **effective membership overrides** as well: per membership, role grants UNION
    direct `ALLOW`s, minus direct `DENY`s, then the mutations held without a required view;
  - **report the violating memberships** (a membership-level `missing_view_permissions` or an
    operator report) in the same release as the roles report and **before** the enforcement pull
    request ships, so no principal is first discovered by a 403 in production;
  - platform checker roles (ADR 0028, ADR 0029) need the view codes added in the platform
    organisation before the check ships; `PLATFORM_SUPER_ADMIN` already has them.
- A `DENY` of a view now also blocks the mutations that need it.
- A deprecated or disabled view code makes every mutation that needs it unusable, because only
  `ACTIVE` codes grant anything. The catalogue test guards the migrated database; a runtime edit
  stays a SQL-level risk and is documented.
- The BRANCH-scope defect is fixed end to end: a branch-scoped manager can administer and see its
  branch, and nothing outside it.
- **Test impact.** Integration tests that grant a single mutation code and expect success (the
  platform-checker, branch-return, branch-update, tenant-return and branch-pinning fixtures, and
  about twenty accounting `grantDirectly` fixtures) either gain the view, through a helper that
  appends the catalogue's requirements, or flip to the named 403. Each family keeps an explicit
  negative test: a mutation code without its view answers 403 "Missing permission: <view>." and
  changes nothing (transition log, audit row and idempotency row all absent). WebMvc tests that
  stub the deleted helpers move to the gated reads. Three tests asserting the generic 403 text
  change where they exercise an application-layer denial. The ArchUnit rules and the catalogue test
  are new. A per-family test revokes the view between the pre-check and the read-back to prove the
  read-back never re-resolves.
- **No data backfill.** The single migration seeds metadata only; it changes no role.
- The role routes gain a lock and two 400 paths, and two additive response fields
  (`missing_view_permissions`, `required_view_permissions`). Public JSON stays `snake_case`.
- The 403 detail now names a code. `@PreAuthorize` denials stay generic, so a coarse gate can still
  answer the unnamed 403 first when the mutation code itself is absent from the pinned set.
- No financial write path is added, so no `FinancialTransactionAtomicityFixture` probe is
  registered; the changes commit or roll back inside the existing service transactions.

## Alternatives rejected

- **A permission-free read-back as the general fix.** Rejected by the owner. It makes the mutating
  caller a reader of data it holds no read permission for (a member's email on a
  `membership.suspend`-only role), contradicts "every read requires its own view", and leaves the
  BRANCH-scope role asymmetry in place for reads. It is the policy ADR 0028 adopted, and this ADR
  supersedes it.
- **A minimal acknowledgement body (`{id, status}`) instead of the resource.** Breaking on 23
  documented `/api/v1` responses, needs `/api/v2` or a documented break, and every UI would then
  re-`GET` the resource, which still needs the view.
- **An implicit view granted by the resolver (or by a backfill migration).** A holder of a
  mutation code would silently read everything of that kind, which widens read authority against
  least privilege, defeats a deliberate `DENY`, and still leaves the BRANCH-scope read at the wrong
  scope.
- **A pre-check only, without the composition rule.** It turns the mutate-then-403 into a clean
  403, but the model would still allow, and the catalogue API still not describe, a role that can
  never use its own mutation. The owner's rule is about what a role may be, not only about what a
  request meets.
- **The view in `@PreAuthorize`.** An unnamed 403, a duplicate of the application check, and wrong
  for a branch-scoped caller who is not pinned (the coarse gate reads the pinned set).
- **Explicit per-call-site view arguments** (a method family such as `requireTenantMutation`). The
  most visible at each site, but it touches every existing call and a site can forget it; the
  central adapter cannot.
- **The map as a Kotlin object.** The recommended alternative in the design work and the one the
  accounting permission constants follow. Rejected by the owner for the database, for the reasons
  in point 8: the data sits next to the code it describes, one query serves operators, and a
  migration adds a code and its requirement together. The cost is that it is editable by SQL.

## Rollout

A stacked series of pull requests, each one conventional commit, each green on its own base
(`CLAUDE.md`, "Pull requests and commits"). The ADR is the base. Later pull requests also update
the documents the supersessions below name (`authorization-model.md`, `foundation-api.md`,
`active-organisation-context.md`, `accounting-authorization.md`) and `CLAUDE.md`.

- **1.** `docs(adr): record that a mutation permission implies its view permission (ADR 0030)`
  This ADR, and status notes on ADR 0028 and 0029.
- **2.** `feat(iam): store the permission view requirements and publish them in the catalogue`
  The one migration (V22) adding `permission.kind` (`VIEW`, `MUTATION`, `CONTEXT`),
  `permission.grant_scope` (`TENANT`, `PLATFORM`) and the `permission_view_requirement` join table,
  the columns `NOT NULL` after classification, seeding the mapping; regenerated jOOQ;
  `required_view_permissions` on the catalogue DTOs, OpenAPI and docs; the catalogue test
  (unclassified code, every `MUTATION` has at least one requirement and `VIEW` or `CONTEXT` none,
  stale pairing, seeded role and bootstrap bundle compliance, ACTIVE views); the `CLAUDE.md`
  migration entry.
- **3.** `feat(iam): refuse role compositions that hold a mutation without its view`
  The role row lock; the assign and remove 400s; `missing_view_permissions` on role list and detail;
  the operator query, covering roles **and effective membership overrides**; the reporting of
  violating memberships (membership-level `missing_view_permissions` or an operator report); role
  section of the API docs. **Ships one release before 5.**

  *Status: implemented by this change (role lock, the assign and remove 400s,
  `missing_view_permissions` on role list and detail, and the operator SQL of
  `docs/operations/permission-view-gap-report.md`, which covers memberships and platform roles).
  The form decided for memberships is the operator report, not an API field.*
- **4.** `feat(lifecycle): authorise branch and assignment reads at the target branch`
  The branch visibility port and its IAM implementation; branch, branch-assignment and
  role-assignment reads and lists; the store id restriction; `active-organisation-context.md`.
  **It also removes (or replaces with authentication-only access) the pinned-context
  `@PreAuthorize("hasAuthority('...view')")` gates on those read endpoints** (`GET /branches`,
  `GET /branches/{id}`, the branch-assignment and role-assignment lists and by-id reads), because
  the gate evaluates the pinned branch's authorities and would answer 403 before the target-aware
  application query ran. The test expectation: a caller pinned to branch A who holds
  `branch.view`, `branch_assignment.view` or `role_assignment.view` only through a role scoped to
  branch B can list and get B's resources (and sees none of A's unless granted), while a caller
  with no grant at all still gets 403.

  *Status: implemented by this step's change.* `PermissionGuard.branchVisibility` is the port, the
  endpoint gates on the six read routes are removed, and `TargetAwareReadsIntegrationTests` is the
  full-stack proof.
- **5.** `feat(iam): require the view permission with every mutation permission`
  The pre-check in the lifecycle **and accounting** guard adapters; `MissingPermissionException`;
  fixture adaptation including the accounting fixtures; named-403 tests; the route tables listing
  both codes. After this, no route can mutate and then roll back on a read-back. Sits after the
  in-flight platform-organisation guard and branch-approval work it touches.
- **6a.** `refactor(lifecycle): read back branches through the gated query`
  Branch `PATCH`, return, and the platform branch routes.
- **6b.** `refactor(lifecycle): authorise memberships in the service and read them back gated`
  Tenant and platform membership routes; the controller-only checks and the invite check move into
  `UserProvisioningService`.
- **6c.** `fix(iam): look up grants and assignments by key after a mutation`
  The by-key reads; the three gated reads that precede a mutation stop being read-then-check.
  Role-permission remove already carries its role id, so its check simply moves first. Revoking a
  branch assignment or a role assignment takes only `assignment_id`: the target branch id or
  scope type that the target-scoped permission check needs comes from the assignment row itself
  (today from the gated read in `BranchAssignmentController.revoke` and
  `RoleAssignmentController.revokeRole`), so the reads cannot just be reordered after the check.
  Instead the service resolves the scope through an **authorised combined lookup**: an internal
  lookup, never returned to the caller, that finds the assignment's scope, applies the mutation and
  view check at **that** scope, and answers **identically** for "unknown or foreign assignment id"
  and "an assignment the caller may not touch", so existence is not an oracle (the exact status is
  decided in the implementing pull request). It must not restore an unrestricted existence read:
  no response, status or timing difference may distinguish the two cases for a caller without the
  permission.
- **6d.** `refactor(lifecycle): authorise platform tenant decisions in the service and read back
  gated`
  The platform tenant routes; an `actorId` on the three commands that lack one; the bootstrap status
  folded into the gated tenant detail.
- **6e.** `refactor(iam): authorise platform user lifecycle in the service and read back gated`
  Platform user suspend, reactivate and deactivate: their permission checks live only in the
  platform user controller and `UserProvisioningService` performs none. The mutation permission
  and `user.view` check moves into the application service, inside the mutation transaction, with
  a gated read-back, mirroring 6d.
- **7.** `test(architecture): forbid permission-free reads from web adapters`
  Delete the five helper declarations; the ArchUnit rules of point 6; the final documentation sweep
  turning "planned" into current.

Pull request 5 is safe before 6a to 6e (the old permission-free read-backs still answer, and the
gated ones can no longer fail), and pull request 7's rules pass only once 6a to 6e are in. The
migration number is the next free one at the time pull request 2 is cut.

## Supersession

### ADR 0028

Superseded in part. Each sentence below is amended **by the pull request that implements it**; the
status note added to ADR 0028 points here and its history is not rewritten.

- "Each route works with its one permission alone. The membership or branch it returns is read back
  without a second permission gate (`membership.view`, `branch.view`) inside the same transaction,
  because that gate would otherwise reject a role holding only the advertised permission after the
  mutation and roll it back." is **superseded**: each route needs its permission **and that
  permission's view** (`membership.view` for `user.approve`, `branch.view` for `branch.create` and
  `branch.approve`) in the platform organisation, and reads its result back through the gated
  query. The rest of that paragraph stands: the read stays bound to the path tenant, so an id of
  another tenant is still 404, and the response shape is unchanged.
- "The caller holds the step's permission **in the platform organisation**" (point 2 of "When the
  platform checker is open") is extended: and the permission's view, in the platform organisation.
  The permission check, with the view, still comes before any existence signal.
- The "Routes and permissions" table gains the view per route.
- "`PLATFORM_SUPER_ADMIN` already holds all three" (the three permissions) still holds, and it
  holds the views too. A custom platform checker role must now hold them as well.

### ADR 0029

Amended wherever it names the permission a route needs; the new requirement is **plus the view
code at the same scope**.

- 3b "Permission: no new code": the maker (withdraw) needs `branch.create` and `branch.view`, and
  anyone else (return) needs `branch.approve` and `branch.view`. "No new code" stays true; the
  view is the existing `branch.view`.
- 3b "Order of checks", step 2: "**Permission** for that class (`branch.create` or
  `branch.approve`)" becomes "**Permission and `branch.view`** for that class".
- 3b "Errors": "`403` when the class's permission is missing" becomes "or its view".
- 3b "Amendment (#203)": `branch.update` also needs `branch.view` at the target branch.
- 3c "Route and permission": "It requires **`tenant.reject`**" becomes "**`tenant.reject`** and
  `tenant.view`". Its "Errors" line gains the same.
- 2 "Assignments stay immediate": the authority to assign is `user.assign_branch` with
  `branch_assignment.view`, and `user.assign_role` with `role_assignment.view`.

Nothing in ADR 0029 about maker-checker, remarks, return-to-draft, events or the sequencing changes.

## Open items

- **The seeded-roles rule.** "An admin holds every permission of its scope", deriving the seeded
  admin role bundles from `permission.grant_scope`, is its own change and **is implemented by
  `V23`**. This ADR only shares the catalogue metadata migration with it and references it.
  A bundle derived that way trivially satisfies this ADR's rule as long as it contains the views,
  which it does by definition; `SeededRolesDriftTests` asserts it for every seeded bundle.
- **Classifying views and context codes in the database.** Settled and **implemented** by
  `V22__permission_catalogue_metadata.sql`: it adds a `permission.kind` column (`VIEW`, `MUTATION`
  or `CONTEXT`), `NOT NULL` once every existing code is classified, and `permission.grant_scope`
  (`TENANT` or `PLATFORM`) the same way. `PermissionCatalogueMetadataTests` requires every
  `MUTATION` code to have at least one row in `permission_view_requirement` and every `VIEW` or
  `CONTEXT` code to have none, so a new code cannot ship without a classification and "needs
  nothing" is explicit, not implied. The counts at the time of `V22` are the 18 views, 3 context
  codes and 60 mutations of point 7, and the catalogue API publishes all of it.
- **Dead codes.** Whether `tenant.activate` is retired, and when the deprecated `branch.activate`
  is deleted from the catalogue, are separate decisions. V21 copied every `branch.activate` grant
  to `branch.approve`, so a legacy role that held approval without `branch.view` now violates the
  rule through `branch.approve`; it is reported like any other violator (point 3).
- **A machine-readable refusal.** An optional `missing_permission` member on the 403, or a
  `violations` entry on the composition 400, is not required by the rule and is not decided here.
- **A future override writer** (direct `membership_permission` rows) must apply the composition
  rule to the membership's effective set.
- **Accounting inbound adapters**, when they land, inherit the central check and need no new
  decision; their routes document both codes.
