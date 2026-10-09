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

## Client address behind a reverse proxy

Audit rows record the client address (`audit_event.ip_address`, #185). The application believes
`X-Forwarded-For` only from a direct peer listed in `finaxis.security.client-ip.trusted-proxies`
(`FINAXIS_CLIENT_IP_TRUSTED_PROXIES`: comma-separated addresses or CIDR ranges, IPv4 or IPv6),
and then takes the right-most entry that is not itself a trusted proxy. The list is empty by
default, and the header is then ignored.

**A deployment behind a reverse proxy (Coolify's Traefik) must set it to that proxy's own
address**: pin Traefik's container address (a static IP), or put Traefik and the application on a
dedicated network that only the two of them join and trust that network. Do **not** trust the
shared Docker network Coolify attaches every co-hosted resource to, nor its range: any other
container on it could connect straight to the application with a forged `X-Forwarded-For` and be
believed, and the range also holds the bridge gateway, which Docker's userland proxy uses as the
source of host-published traffic. Unset, every audit row records the proxy's address, not the
client's; too wide, a client can name any address it likes. A malformed entry fails startup, and
so does a range of every address (`0.0.0.0/0`, `::/0`). The proxy must append the address it
received the request from to `X-Forwarded-For`, which Traefik does by default. Spring's own
`ForwardedHeaderFilter` (`server.forward-headers-strategy: framework`) is not used for this: it
believes the left-most entry from any peer (#256, owner decision pending).

## Bootstrap identities

`V3` seeds a demo tenant, `FINAXIS-LOCAL`, with two demo identities, `local.admin` and
`local.checker`, linked to Keycloak subjects. Since `V23` both are **full tenant
administrators** of that tenant: the `local-admin` role they hold carries every `ACTIVE`
tenant-scope permission, `role.assign_permission`, `business_date.reopen` and the accounting
break-glass codes included. They were already powerful (the first administrator and the second
actor that can approve the first's invitations); they now hold everything in the tenant.

**A production deployment must rotate or deactivate both identities, or deactivate the
`FINAXIS-LOCAL` memberships, before the deployment is exposed.** `V3`'s header already says to
rotate `local.admin`; the widening makes leaving them in place more costly. A deployment that never
needs the demo tenant should suspend or deprovision it.

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
