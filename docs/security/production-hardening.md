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

The production profile disables the API documentation UI and OpenAPI endpoint. It also disables
development tooling and Docker Compose support.

The active-organisation HMAC secret has no production default. Startup fails fast unless
`FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET` is configured.

This document is linked from `CLAUDE.md`.
