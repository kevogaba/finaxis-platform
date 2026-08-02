# ADR 0012: Keycloak Authenticates, The Application Authorizes

## Status

Accepted

Date: 2026-08-02

## Context

A core-banking platform needs both a credible identity story — password policy, MFA, SSO,
federation to a customer's corporate directory — and fine-grained, auditable authorization over
tenant data. These are different problems with different rates of change and different regulatory
exposure.

Building authentication in-house means owning password hashing, credential rotation, brute-force
lockout, session revocation, MFA enrolment, and eventually SAML/OIDC federation. Every one of
those is a place to get security wrong, and none of them is a differentiator for a SACCO platform.

Conversely, pushing authorization into Keycloak means expressing per-tenant, per-branch,
per-membership permission rules as identity-provider roles and mappers. Those rules change
whenever an administrator edits a role, they must be auditable against the same trail as the data
they protect, and they must be transactionally consistent with the membership records that define
them. An identity provider is the wrong place for that.

## Decision

**Keycloak owns authentication and only authentication.** It issues the JWT, owns credentials and
credential policy, owns MFA, and owns SSO and external identity-provider federation. Any
customer-specific IdP configuration — SAML, LDAP, social, corporate OIDC — lives in Keycloak realm
configuration, not in this application. Adding a federated IdP for a customer is a Keycloak change
with no application deployment.

**The application owns authorization and everything it depends on**: users, organisations,
branches, memberships, roles, permissions, scopes, and every rule that turns a request into an
allow or a deny.

The application is an OAuth2 **resource server**. It validates the bearer JWT on every request and
takes exactly two things from it: the subject and the basic profile claims. It takes **no
authorization decision input from the token**. Realm roles, client roles, and group claims in the
JWT are ignored.

Concretely, this means:

- **No application-managed passwords.** No password endpoints, no password storage, no password
  columns, no password comparison anywhere in application code. `keycloak_identity_link` stores
  the provider subject and nothing secret.
- **Runtime authorization evaluates permission codes, never role names.** Roles are a grouping
  convenience for administrators; `EffectivePermissionResolver` resolves a membership to a set of
  permission codes, and every gate checks a code.
- **Every request authenticates via the bearer JWT.** The Redis-backed HTTP session carries
  active-organisation context only, never authentication state (ADR 0001).
- The application never receives or forwards end-user credentials. `scripts/local-smoke.sh`
  fetches a development token directly from Keycloak; that is a script talking to Keycloak, not
  the application handling a credential.

Keycloak is also a **downstream system the application provisions into**, not a source of truth it
reads from. When a user is approved locally, a durable outbox-driven job creates the corresponding
Keycloak user (ADR 0006). Local state is authoritative; Keycloak is kept in step.

## Consequences

Authorization rules are ordinary rows in ordinary tables, changed in the same transaction as the
memberships they describe and audited by the same append-only trail (ADR 0007). A permission
change is immediately effective and immediately visible in the audit log — no token refresh, no
IdP propagation delay.

Because permissions are not in the token, they must be resolved per request. `RequestPermissionCache`
and the `iam.effective-permissions` cache exist for exactly that cost. This is a deliberate trade:
per-request resolution buys immediate revocation, which a token-embedded permission set cannot
offer without short token lifetimes.

Onboarding a customer's corporate SSO requires no application change. Onboarding a new *permission*
requires no Keycloak change. The two systems evolve independently, which is the point.

A Keycloak outage stops new logins but does not stop the application from authorizing already-issued,
still-valid tokens, because authorization needs only the local database.

## Consequences For Anyone Extending This

Do not add a password field, a login endpoint, or a credential check. Do not read `realm_access`,
`resource_access`, or any role claim from the JWT to make a decision. If a decision needs data the
application does not have, add it to the application's own model — do not reach into the token.

## Alternatives Considered

Keycloak authorization services (resource/scope/policy evaluation in the IdP):

- Rejected. Per-tenant, per-branch rules would have to be mirrored into Keycloak and kept
  transactionally consistent with membership records. The audit trail would be split across two
  systems, and a permission change would depend on IdP availability.

Embed permissions as JWT claims at login:

- Rejected. Revocation becomes bounded by token lifetime, which is unacceptable for a
  CRITICAL-risk permission like `tenant.approve`. It also makes the token grow with the user's
  organisation count.

Build authentication in-house and skip Keycloak:

- Rejected. Owning credential security, MFA, and federation is a large, permanently maintained
  liability with no product upside.

## Verification

- `SecurityAdapterTests` — principal loading from the JWT subject; suspended user, suspended and
  deprovisioned organisation all rejected
- `AuthFlowIntegrationTests` — runtime resolution rejects context for another organisation, an
  inactive branch, and a role holding no concrete permission
- `MethodSecurityAuthorizerTests`, `AuthorizationServiceTests` — permission-code gates
- `IdentifierGenerationRuleTests` and the ArchUnit suite — no credential handling reaches the
  web adapters
- ADR 0001, ADR 0006, ADR 0011
