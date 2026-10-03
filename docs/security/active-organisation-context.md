# Active Organisation Context

All tenant-scoped requests require two pieces of information:

1. Keycloak JWT authentication in `Authorization: Bearer <jwt>`.
2. Application active organisation context from either a signed header token or a Redis-backed browser session.

## Browser Flow

After authenticating with Keycloak, clients discover selectable organisations without an active
context:

```http
GET /api/v1/auth/organisations?page=0&size=25
Authorization: Bearer <keycloak-jwt>
```

The response is paginated and contains only active organisations where the user has an active
membership and `auth.select_organisation` permission. Clients pass the selected
`organisation_id` to the selection call.

Browser clients call:

```http
POST /api/v1/auth/select-organisation
Authorization: Bearer <keycloak-jwt>
Content-Type: application/json

{
  "organisationId": "..."
}
```

The server verifies ACTIVE membership, stores active context in the Spring Session Redis `HttpSession`, and returns a response that also includes a signed context token.

Selection is a convenience step, not the final security boundary. `AuthSelectionService` verifies
that the requested organisation is ACTIVE, the membership is ACTIVE, and branch selections are
currently assigned to an ACTIVE branch in the selected organisation.

If the membership has exactly one assigned branch, the branch is auto-selected and the returned context includes `branchId`.

If the membership has more than one assigned branch, the response sets `requiresBranchSelection` to `true` and includes `assignedBranchIds`.

Before selecting, clients may discover the assigned branches:

```http
GET /api/v1/auth/branches?page=0&size=25
Authorization: Bearer <keycloak-jwt>
```

This request uses the active organisation context stored in the browser session. Headless clients
also send `X-Active-Organisation-Context`.

The browser then calls:

```http
POST /api/v1/auth/select-branch
Authorization: Bearer <keycloak-jwt>
Content-Type: application/json

{
  "branch_id": "..."
}
```

The server verifies that the branch is assigned to the active membership and updates the same Redis-backed session context.

### Clearing the branch selection

`branch_id` is optional. Calling `POST /api/v1/auth/select-branch` with `{}` (or
`{"branch_id": null}`) re-issues the same organisation context with **no** branch, for any user,
including a single-branch user who was auto-selected by `select-organisation`. The response
carries an explicit `"branch_id": null` (the property is present, not omitted) and a fresh
`context_token`; the Redis-backed session is updated the same way as for a branch selection. The
caller still needs the tenant-scope `auth.select_branch`, and no assigned-branch check applies
because no branch is being selected. Auto-selection on `select-organisation` is unchanged:
operational users are still pinned to their single branch on first login, and clearing the pin is
an explicit action. A cleared context can be narrowed again by selecting a branch. A blank or
malformed `branch_id` is rejected with `400`; only an omitted or JSON-null value clears.

## What the selected branch does and does not do

The selected branch **narrows operational authority; it does not scope tenant administration**.

- *Effective permissions* are resolved for the selected branch: tenant-scope role assignments
  always apply, and branch-scope role assignments apply only for the selected branch. With no
  branch selected only tenant-scope assignments apply. This is what `@PreAuthorize` and the
  `/auth/me` permission list read, so operational work stays narrowed to the working branch.
- *Administration of branches, branch assignments and BRANCH-scope role assignments* is **not**
  hidden by the selected branch. A pinned caller can `GET`/submit/activate/suspend/reactivate/
  close any branch of the tenant, and use `/tenant/branch-assignments` and BRANCH-scope
  `/tenant/role-assignments` against any branch, provided the permission check passes. Previously
  these returned a safe `404` for any branch other than the selected one (issue #154).
- *Authorization is still evaluated at the right scope.* The controller's `@PreAuthorize` is only
  a coarse gate on the selected-branch authority set. The application layer then decides, per
  target: branch lifecycle transitions and BRANCH-scope role assignment check the permission
  against the **target** branch (`PermissionGuard.requireBranchPermission`: tenant-scope grants
  plus branch-scope grants on that branch); branch assignment and revocation first require the
  tenant-scope `user.assign_branch`/`user.revoke_branch`, then the permission on the target
  branch; reads such as `GET /branches/{id}` and `GET /tenant/branch-assignments/{id}` check the
  tenant-scope view permission. A caller whose only grant is a BRANCH-scope role on branch A
  therefore passes the coarse gate while pinned to A, but is denied with `403` when targeting
  branch B (covered by `BranchPinningIntegrationTests`).
- *Unknown targets.* Once the permission check passes, a branch that does not exist in the active
  tenant (or belongs to another tenant) answers `404` for submit, activate, suspend, reactivate,
  close and assign/revoke; callers without the permission get `403` first, so the response never
  reveals whether a branch exists. Genuine state conflicts (for example closing a draft) remain
  `409`. BRANCH-scope role assignment is the exception: once the target-branch permission check
  passes, `RoleManagementService.validateScope` requires the user to hold an active assignment to
  the target branch and answers `409` when they do not (including an unknown or foreign
  `branch_id`), not `404`.
- *Branch-assignment search* `GET /tenant/branch-assignments`: an explicit `branch_id` filters by
  that branch whatever is selected; without `branch_id` the list defaults to the selected branch
  (when one is selected) and to all branches otherwise. To list across every branch, clear the
  selection.

Subsequent browser requests can rely on the `SESSION` cookie. They do not need to send `X-Active-Organisation-Context`.

## Headless Flow

Mobile apps, CLI clients, and integrations use the same selection endpoints, then send:

```http
Authorization: Bearer <keycloak-jwt>
X-Active-Organisation-Context: <contextToken>
```

The header token is not authentication. It only identifies the selected app membership and optional branch context, and is valid only with the matching authenticated Keycloak subject.

## Runtime Resolution Prechecks

Every request with an active organisation context is rechecked by
`ActiveOrganisationContextFilter` and `AppPrincipalLoader` before an `AppPrincipal` is installed:

- user context: the Keycloak subject must resolve to the context `userId`, and the app user must
  be ACTIVE or INVITED. SUSPENDED, LOCKED, DEACTIVATED, and other non-login states are rejected.
- first login: INVITED users are activated through the lifecycle module's public
  `UserFirstLoginActivation` API before the principal is built. ACTIVE users use the same API to
  refresh `user_account.last_login_at`.
- membership context: the context `membershipId` must belong to the resolved user and selected
  organisation, and the membership must be ACTIVE.
- organisation context: `organisation.status` must be ACTIVE. SUSPENDED and DEPROVISIONED
  organisations cannot resolve principals, which is the runtime deprovisioning login block.
- branch context: when `branchId` is present, the branch must belong to the selected organisation,
  be ACTIVE, and have an ACTIVE assignment for the resolved membership's user.
- permissions: the principal contains effective permission codes only. Role names are never used
  as authorities, and method security still denies requests missing the concrete permission code.

## Precedence

If `X-Active-Organisation-Context` is present, it wins over the browser session.

If the header is invalid, the request fails with `403`; the server does not fall back to the session.

If the header is absent, the server uses the Redis-backed session context when present.

## Authorization

After context resolution, the application builds `AppPrincipal` with:

- `userId`
- `keycloakSubject`
- `organisationId`
- `membershipId`
- `branchId`
- `email`
- `fullName`
- effective permission codes

Spring Security exposes permission codes as authorities. Controllers should use:

```kotlin
@PreAuthorize("hasAuthority('logistics.shipment.approve')")
```

Services must still call `AuthorizationService` for resource-specific checks.
