# Production Hardening

The production profile is activated with `SPRING_PROFILES_ACTIVE=production`. It keeps browser
security decisions explicit while preserving bearer-JWT authentication for every client type.

## CORS

CORS is a browser policy, not a bearer-token authentication mechanism. It is disabled by default
and enabled in production with explicit origins from `FINAXIS_CORS_ALLOWED_ORIGINS`. It never
affects native or mobile clients that send bearer tokens directly. A request the firewall refuses
before `CorsFilter` runs (a `400` `request_rejected`, see "Cookies and headers") gets the same
policy applied by its handler, through the same `CorsConfigurationSource` and Spring's
`DefaultCorsProcessor`: an allowed origin gets its `Access-Control-*` headers and `Vary: Origin`,
any other origin none, and the problem stays a `400` either way.

## CSRF and session context

CSRF remains disabled. Authentication is bearer JWT, while the Redis-backed session carries only
the selected active-organisation context and never authentication state.

## Cookies and headers

Production session cookies are `Secure` and `SameSite=Strict`.

The application always sends content-type-options, frame-options, and referrer-policy headers.
Production additionally enables HSTS and CSP. HSTS (`Strict-Transport-Security`) is written only on
a response to a request the application sees as secure: one that arrived over HTTPS, or, when TLS
is terminated upstream (Coolify's Traefik), one a **listed** trusted proxy forwarded with
`X-Forwarded-Proto: https`. Behind an unlisted proxy no response carries it (see "Forwarded
headers").

## Forwarded headers

`server.forward-headers-strategy` is `native` (#256): Tomcat's `RemoteIpValve` rewrites the remote
address, scheme, host and port from `X-Forwarded-For`, `X-Forwarded-Proto`, `X-Forwarded-Host` and
`X-Forwarded-Port`, and it does so **only when the direct peer is listed in
`finaxis.security.client-ip.trusted-proxies`** (`FINAXIS_CLIENT_IP_TRUSTED_PROXIES`:
comma-separated addresses or CIDR ranges, IPv4 or IPv6). `TrustedProxyValveCustomizer` builds the
valve's internal-proxies pattern from that list, replacing Tomcat's default (which trusts every
private range, `127.0.0.0/8` included) and pinning the four header names, so the
`server.tomcat.remoteip.*` properties cannot widen it. The pattern is a regular expression, never
Tomcat's CIDR form, which resolves each header entry through DNS.

- **Empty list (the default): no forwarded header is believed from anyone.** `getRemoteAddr()` is
  the peer and scheme, host and port are what the connector saw. Everything that reads them is
  then correct by construction for a directly reached application: the access log's
  `remoteAddress` and MDC `client.address`, the anonymous rate-limit key
  (`rate-limit:<policy>:anon:<remoteAddr>`), and every absolute URL built from the request (the
  `resource_metadata` of the `401` challenge, the OpenAPI server URL, any `Location`).
- **Listed peer:** the client is the right-most `X-Forwarded-For` entry that is not itself a listed
  proxy (listed hops are skipped; all listed gives the left-most), and the scheme, host and port
  come from the other three headers.
- **Never read, from anyone:** the RFC 7239 `Forwarded` header and `X-Forwarded-Prefix`. A proxy
  that strips a path prefix is not supported by this setting. This holds for today's
  configuration: enabling springdoc's swagger-ui would bring back a reader of both
  (`ForwardedHeaderUtils`), enabling Sentry's send-default-pii would record the left-most,
  client-chosen `X-Forwarded-For` as the user's address, and a separate `management.server.port`
  builds a second Tomcat this customizer is not proven to narrow. Re-check before turning any on.
- **Every strategy but `native` is refused at startup**, unset included; the message names
  `server.forward-headers-strategy` and the required `native`. `framework` installs Spring's
  `ForwardedHeaderFilter`, which has no trusted-proxy list and believes all of these headers,
  `Forwarded` first, from any peer, so any client could choose its rate-limit bucket, its logged
  address and the scheme and host of the URLs the application builds. `none` (and unset) installs
  no valve, so the list would no longer govern `getRemoteAddr()`, scheme, host, port, HSTS or the
  anonymous rate-limit key while the audit resolver still applied it: inconsistent data. Startup
  also fails if, with `native`, Tomcat has no `RemoteIpValve`. The checks run when the embedded
  Tomcat is built, which every deployment does.
- An entry the pattern does not recognise (a port, brackets, `::ffff:a.b.c.d`, text, an empty
  entry) is never trusted: the valve stops there and that entry's text, verbatim, is the remote
  address (and so the access log's address and the anonymous rate-limit key; an empty entry gives
  an empty one). Only an entry to the right of which every entry is a listed hop can be reached.
  A listed IPv6 hop written with a `%zone` is recognised (Tomcat writes a link-local peer that
  way), so the valve passes over it where the audit resolver alone would stop; its address is
  listed either way.

**A deployment behind a reverse proxy (Coolify's Traefik) must list that proxy**, or the client is
the proxy connection:

- every audit row, access-log line and anonymous rate-limit bucket names the proxy's address (all
  anonymous callers then share one bucket);
- URLs the application builds carry the scheme and port Traefik connected with (`http`, `8081`)
  instead of the public `https` ones; the host stays right, since Traefik passes the public `Host`
  through;
- **HSTS is not sent**: no request is secure to the application;
- **a same-origin browser request is refused as CORS** (`403`): the browser's `Origin` is the
  public `https://` one and the application sees `http://…:8081`, so the request counts as
  cross-origin and is refused unless that origin is in `FINAXIS_CORS_ALLOWED_ORIGINS` (the
  docs page's "try it" is such a request). Both are reproduced by
  `ForwardedHeadersIntegrationTests`.

The `production` profile logs one `WARN` at startup when the list is empty
(`TrustedProxyValveCustomizer`); a direct deployment with no proxy stays legal. Set the list
before, or together with, the release that brings this setting.
Pin Traefik's container address (a static IP), or put Traefik and the application on a dedicated
network that only the two of them join and trust that network. Do **not** trust the shared Docker
network Coolify attaches every co-hosted resource to, nor its range: any other container on it
could connect straight to the application with forged headers and be believed, and the range also
holds the bridge gateway, which Docker's userland proxy uses as the source of host-published
traffic. Too wide a list lets a client name any address, scheme or host it likes. A malformed
entry fails startup, and so does a range of every address (`0.0.0.0/0`, `::/0`). The proxy must
append the address it received the request from to `X-Forwarded-For` and set `X-Forwarded-Proto`
and `X-Forwarded-Host`, which Traefik does by default; the application must not be reachable on
`8081` except through it.

A request Spring Security's firewall refuses never reaches the header writer, so its `400`
`request_rejected` problem sets the same headers itself, with a fixed `default-src 'none';
frame-ancestors 'none'` CSP in every profile and HSTS when it is enabled (see
[`foundation-api.md`](../api/foundation-api.md)). The rejection still marks the request's
observation with the firewall's exception, whose message embeds the refused header value or path:
with a trace exporter configured, the span records it.

## Client address behind a reverse proxy

Audit rows record the client address (`audit_event.ip_address`, #185), resolved by
`ClientIpResolver` with the same list (see "Forwarded headers" above for the deployment
requirement). Behind a listed peer the valve has already applied the list; the resolver continues
the same walk and never trusts more, parses strictly and normalises the address. Unset, every audit
row records the proxy's address, not the client's. The access log, the rate-limit key and the audit
row name the same address for the same request whenever the listed proxy writes plain, canonical
IP literals (Traefik does). When a listed hop writes a port, `::ffff:a.b.c.d`, brackets or
non-canonical text, that text verbatim is the access log's address and the rate-limit key (a port
then makes the anonymous bucket per source port), while the audit row holds the normalised address
the resolver continues to; a proxy that appends ports is unsupported.

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
