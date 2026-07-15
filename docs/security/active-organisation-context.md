# Active Organisation Context

All tenant-scoped requests require two pieces of information:

1. Keycloak JWT authentication in `Authorization: Bearer <jwt>`.
2. Application active organisation context from either a signed header token or a Redis-backed browser session.

## Browser Flow

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

If the membership has more than one assigned branch, the response sets `requiresBranchSelection` to `true` and includes `assignedBranchIds`. The browser then calls:

```http
POST /api/v1/auth/select-branch
Authorization: Bearer <keycloak-jwt>
Content-Type: application/json

{
  "branchId": "..."
}
```

The server verifies that the branch is assigned to the active membership and updates the same Redis-backed session context.

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
