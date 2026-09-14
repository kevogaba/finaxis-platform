# Production Hardening

The production profile is activated with `SPRING_PROFILES_ACTIVE=production`. It keeps browser
security decisions explicit while preserving bearer-JWT authentication for every client type.

## CORS

CORS is a browser policy, not a bearer-token authentication mechanism. It is disabled by default
and enabled in production with explicit origins from `FINAXIS_CORS_ALLOWED_ORIGINS`. It never
affects native or mobile clients that send bearer tokens directly.

## CSRF and session context

CSRF remains disabled. Authentication is bearer JWT, while the Redis-backed session carries only
the selected active-organisation context and never authentication state.

## Cookies and headers

Production session cookies are `Secure` and `SameSite=Strict`.

The application always sends content-type-options, frame-options, and referrer-policy headers.
Production additionally enables HSTS and CSP.

## Documentation and required secret

springdoc and Scalar stay enabled unconditionally in every profile, including production; both are
stateless, side-effect-free documentation endpoints. Unauthenticated access to the Scalar UI and
the OpenAPI document is instead gated at the Spring Security layer by
`finaxis.security.api-docs.public-access-enabled` (`FINAXIS_API_DOCS_PUBLIC_ACCESS_ENABLED`,
default `true`): when `false`, `/scalar/**`, `/v3/api-docs/**`, and `/swagger-ui/**` fall through to
`anyRequest().authenticated()` instead of being permitted. This lives at
`SecurityConfiguration.securityFilterChain`, a single unconditionally-registered bean, specifically
so the switch survives a Coolify container restart without an image rebuild — see
`docs/superpowers/specs/2026-09-06-production-readiness-coolify-deployment-design.md`. The
production profile also disables development tooling and Docker Compose support.

Scalar's HTML carries an inline `<script>` initializer with no nonce hook, and fetches the OpenAPI
document itself — both blocked outright by production's configured `default-src 'none'` CSP. While
`finaxis.security.api-docs.public-access-enabled` is `true`, `SecurityConfiguration` therefore
serves a docs-compatible CSP (`script-src`/`style-src 'self' 'unsafe-inline'`, `connect-src 'self'`)
in place of the configured one — on every response, not only `/scalar/**` — instead of the strict
one. This is the honest cost of exposing the docs publicly, not an oversight: setting the flag back
to `false` restores the strict CSP everywhere with the same container restart.

The active-organisation HMAC secret has no production default. Startup fails fast unless
`FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET` is configured.

This document is linked from `CLAUDE.md`.
