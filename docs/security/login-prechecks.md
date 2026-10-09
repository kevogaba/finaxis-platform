# Login prechecks

Keycloak authenticates the bearer JWT. Finaxis then resolves a local application principal before
controllers and method security can make organisation-scoped decisions. These checks prevent a
valid JWT from bypassing local lifecycle, membership, branch, or permission state.

Read this with [active organisation context](active-organisation-context.md),
[authorization model](authorization-model.md), and
[user provisioning](user-provisioning-keycloak.md).

```mermaid
sequenceDiagram
    participant Client
    participant Keycloak
    participant App as Finaxis app
    participant Local as Local IAM store
    participant Perms as Permission resolver

    Client->>Keycloak: authenticate
    Keycloak-->>Client: JWT
    Client->>App: request with JWT and active organisation context
    App->>App: validate bearer JWT
    App->>App: resolve header or session active context
    App->>Local: find user by Keycloak subject
    Local-->>App: local user
    App->>Local: find selected membership
    Local-->>App: membership
    App->>Local: check organisation ACTIVE
    App->>Local: check branch ACTIVE and assigned when selected
    App->>Perms: resolve effective permission codes
    Perms-->>App: permissions
    App->>App: install AppPrincipal and request context
    App-->>Client: allow or deny
```

## Active context transport

The active organisation context is not authentication. It selects the local organisation,
membership, and optional branch for a request that has already authenticated with Keycloak.

The resolver checks, in order:

1. `X-Active-Organisation-Context`, a signed headless-client token;
2. the Redis-backed browser session attribute `iam.activeOrganisationContext`.

An invalid header fails closed with `403`. If neither transport is present, the request remains
JWT-authenticated but is not upgraded to an application principal.

## Principal prechecks

`AppPrincipalLoader` only creates an `AppPrincipal` when all implemented checks pass:

- the Keycloak subject maps to an active `keycloak_identity_link`;
- the context user id matches the local user resolved from the subject;
- the membership id exists and belongs to that user and organisation;
- the local user status is `ACTIVE` or `INVITED`;
- the membership status is `ACTIVE`;
- the organisation status is `ACTIVE`;
- if a branch is selected, the branch is `ACTIVE` and assigned to that membership's user;
- effective permissions resolve for the selected membership and branch.

For an `INVITED` user, `UserFirstLoginActivationService` performs the first-login activation
after these checks identify a valid active membership and organisation. The service transitions
the user to `ACTIVE` through the lifecycle FSM and updates `last_login_at`.

## Authorization checks after login

The resulting `AppPrincipalAuthenticationToken` exposes permission codes as Spring authorities.
Runtime checks use concrete permission codes:

- `hasAuthority('code')` for coarse controller gates;
- `@authz.hasPermission(organisationId, code)` for organisation checks;
- `@authz.hasPermission(organisationId, branchId, code)` for branch checks;
- `AuthorizationService` for service-level resource checks.

Cross-organisation access is rejected because principal organisation id must match the resource or
method-security organisation id. Branch-scoped method security also requires the selected branch id
to match.

## Failure shape and context propagation

JWT failures are handled by Spring Security's resource-server path and return `401` with the
shared problem body (`authentication_required`), `X-Request-Id` and the RFC 6750 challenge:
`error="invalid_token"` with the fixed `error_description` `The access token is invalid or has
expired.`, never the decoder's message (expiry instants and claims stay server-side). A request
with no token gets `WWW-Authenticate: Bearer resource_metadata="..."` and no error code (RFC 6750
section 3). A non-`401` challenge (`400` `invalid_request`) gets body code `invalid_request`; it is
unreachable while tokens are read from the `Authorization` header only. Local
precheck failures return `403` from the active-organisation filter with generic messages such as
`Invalid active organisation context`; the code does not expose tenant lookup detail to callers.

A request with no active-organisation context passes the filter but carries no permission
authority. Method security runs before the idempotency scope check and the controller, so a tenant
route gated by `@PreAuthorize` refuses it with the generic `403` `forbidden`; only a route that
reaches the scope check or the controller (an ungated one such as `POST
/api/v1/auth/select-branch`) answers `tenant_context_required`. See the error codes in
[`foundation-api.md`](../api/foundation-api.md).

When a principal is installed, `RequestContexts` carries tenant, optional branch, actor, and
correlation data for downstream code. `X-Request-Id` and `X-Correlation-Id` are propagated into
the request context and MDC-aware logging path; cleanup happens at the end of the request. A client
`X-Request-Id` is used only if it is 8 to 64 characters of `[A-Za-z0-9._-]`
(`ClientRequestIds`, applied in `ApiProblemFactory.requestId`, the one place the header is read);
otherwise a UUIDv7 replaces it and the application never logs, stores, puts in the MDC or echoes
the rejected value. `X-Correlation-Id` takes the same shape (`ApiProblemFactory.correlationId`,
the one place it is read, #252); a rejected or absent value is replaced by the request id, again
without the rejected value being logged, stored, put in the MDC or echoed. A header value Spring
Security's firewall refuses (CR or LF) gets the `400` `request_rejected` problem with a generated
request id. The firewall checks a value only when it is read, and `X-Correlation-Id` is read
only on an authenticated request with an active organisation context; on any other request it is
ignored.
