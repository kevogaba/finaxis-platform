# Active Organisation Context

All tenant-scoped requests require two pieces of information:

1. Keycloak JWT authentication in `Authorization: Bearer <jwt>`.
2. Application active organisation context from either a signed header token or a Redis-backed browser session.

## Browser Flow

Browser clients call:

```http
POST /auth/select-organisation
Authorization: Bearer <keycloak-jwt>
Content-Type: application/json

{
  "organisationId": "..."
}
```

The server verifies ACTIVE membership, stores active context in the Spring Session Redis `HttpSession`, and returns a response that also includes a signed context token.

If the membership has exactly one assigned branch, the branch is auto-selected and the returned context includes `branchId`.

If the membership has more than one assigned branch, the response sets `requiresBranchSelection` to `true` and includes `assignedBranchIds`. The browser then calls:

```http
POST /auth/select-branch
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
