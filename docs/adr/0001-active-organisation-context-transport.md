# ADR 0001: Active Organisation Context Transport

## Status

Accepted

## Context

Finaxis uses Keycloak for authentication only. The application owns users, organisations, memberships, permissions, and authorization rules.

After login, a user must select an active organisation. Runtime authorization is scoped to the selected membership. If the membership has branch assignments, the user must also have an active branch context unless exactly one branch can be auto-selected. Browser clients and headless clients have different ergonomics:

- Browser clients work well with secure server-managed sessions.
- Mobile apps, CLI clients, and integrations work better with explicit request headers.

The active organisation must not be stored as a permanent field on `app_user`.

## Decision

Support a hybrid active organisation context transport:

1. If `X-Active-Organisation-Context` is present, resolve active context from the signed header token.
2. If the header is absent, resolve active context from the Redis-backed browser `HttpSession`.
3. If the header is present but invalid, reject the request with `403` and do not fall back to the session.
4. If neither transport provides context, leave the request authenticated by Keycloak but without application tenant authorities.

`POST /api/v1/auth/select-organisation` verifies ACTIVE membership, stores the selected context in `HttpSession`, and also returns a signed context token for headless clients. If exactly one branch is assigned, that branch is auto-selected. If multiple branches are assigned, clients call `POST /api/v1/auth/select-branch`; the service verifies the branch assignment and updates the same context.

## Consequences

Browser clients can rely on the `SESSION` cookie managed by Spring Session Redis. Headless clients can ignore cookies and send the signed context token explicitly.

The application builds the same `AppPrincipal`, including optional `branchId`, regardless of context source. Controllers and services do not change when context transport changes.

Invalid headers fail closed to avoid hiding client bugs or letting stale/forged context silently fall back to a different browser session.

## Tradeoffs

Pros:

- Good browser UX without custom client-side context storage.
- Good headless UX with explicit context headers.
- Central browser-session revocation through Redis.
- No application permissions or active organisation state in Keycloak tokens.
- Branch context can evolve independently of Keycloak and remains scoped to the active membership.
- Stable authorization internals through `ActiveOrganisationContextResolver`.

Cons:

- Two context transports must be tested and documented.
- Browser requests depend on Redis-backed session availability.
- Cookie/session security must be configured carefully in deployed environments.
- Header and session conflicts require a clear precedence rule.

## Alternatives Considered

Header only:

- Simpler and stateless.
- Less ergonomic for browser clients and harder to centrally revoke.

Session only:

- Simpler for browsers.
- Poor fit for mobile apps, CLI clients, and integrations.

Permanent `app_user.active_organisation_id`:

- Rejected because active organisation is session/request context, not user identity.
