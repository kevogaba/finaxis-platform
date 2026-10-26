# Foundation REST API

This guide is the canonical REST contract reference for the implemented foundation API.
It complements the generated OpenAPI document at `/v3/api-docs` and the local Scalar UI at
`/scalar`.

## Overview

All public endpoints are versioned under `/api/v1`. Future incompatible contracts must use a
new version path; unversioned public routes are not part of the contract. The one exception is
the OAuth 2.0 protected-resource metadata, `GET /.well-known/oauth-protected-resource`: RFC 9728
fixes its path, so it is intentionally public and unversioned (see
[Protected-Resource Metadata](#protected-resource-metadata)).

Every request authenticates with an OAuth2 bearer JWT issued by Keycloak:

```http
Authorization: Bearer <token>
X-Active-Organisation-Context: <context-token>
```

The `X-Active-Organisation-Context` header is required for active-tenant endpoints unless the
caller already has an established active-organisation session. Browser clients may rely on the
Redis-backed session instead of sending the header; headless clients must send it. The
selection endpoints establish that context and therefore do not require it themselves.

Keycloak authenticates the user. This application owns users, organisations, memberships,
roles, permissions, scopes, tenant context, branch context, and authorization. It never stores
or checks passwords.

## Context Model

Platform routes under `/api/v1/platform/**` require an active membership in the reserved
platform organisation. Tenant routes under `/api/v1/tenant/**`, `/api/v1/branches/**`, and the
role, branch-assignment, membership, permission, and audit resources require an active tenant
context.

Clients establish context in this order:

1. Authenticate with Keycloak and call `GET /api/v1/auth/organisations` to discover selectable
   organisations.
2. Call `POST /api/v1/auth/select-organisation` with the selected `organisation_id`.
3. If the response requires branch selection, call `POST /api/v1/auth/select-branch`.
4. Send subsequent tenant requests with either the browser session or the
   `X-Active-Organisation-Context` token returned by the selection call.

Browser clients use the Redis-backed HTTP session for active organisation state. Headless
clients present the opaque signed context token in `X-Active-Organisation-Context`. The full
transport model is documented in
[active organisation context](../security/active-organisation-context.md).

The selected branch narrows operational authority (effective permissions are resolved for it),
but it does not hide tenant administration: branch lifecycle, branch-assignment and BRANCH-scope
role-assignment endpoints accept any branch of the active tenant, and the application layer
evaluates the permission against the target branch (or tenant scope for reads). A caller without
the permission for that branch gets a `403`; once permitted, a branch lifecycle or branch-assignment
target that does not exist in the active tenant (or belongs to another tenant) is a `404`, never a
`500`. A BRANCH-scope role assignment is the exception: `RoleManagementService.validateScope`
requires the user to hold an active assignment to the target branch and answers `409` when they do
not, including for an unknown or foreign `branch_id`. See
[active organisation context](../security/active-organisation-context.md).

### Selection Exceptions

`POST /api/v1/auth/select-organisation` and `POST /api/v1/auth/select-branch` intentionally have
no `@PreAuthorize` annotation. At selection time the caller has only a bare Keycloak principal,
not a permission-resolved application principal. `AuthSelectionService` enforces
`auth.select_organisation` or `auth.select_branch` against the target membership or branch.

Tenant settings also do not use per-route `@PreAuthorize`. Their authorization is
data-dependent: `TenantSettingsService.authorize()` evaluates each setting key, including
platform-admin-only keys. See
[ADR 0009](../adr/0009-tenant-settings-scope-and-authn-boundary.md).

## Conventions

JSON field names are `snake_case` at the API boundary. Kotlin DTO properties such as
`tenantCode`, `bootstrapStatus`, and `sendApplicationInvite` serialize as `tenant_code`,
`bootstrap_status`, and `send_application_invite`. The published OpenAPI document uses the same
names: `OpenApiWireNamingContractTests` fails if a schema property is not `snake_case`, if a list
endpoint's `items` is not a named schema, or if a request body built from the published
property names is rejected by the codec.

Date and time formats:

| Kind | Format | Example |
| --- | --- | --- |
| Business date | `dd-MM-yyyy`, strict resolver style | `25-07-2026` |
| Time | `HH:mm:ss` | `17:45:00` |
| Datetime | ISO-8601 offset | `2026-07-25T08:00:00Z` |

### Pagination

Every collection endpoint returns `ApiPage<T>`:

```json
{
  "items": [],
  "page": {
    "number": 0,
    "size": 25,
    "total_items": 137,
    "total_pages": 6,
    "has_next": true,
    "has_previous": false
  }
}
```

Collection query parameters always include `page` and `size`. `page` is zero-based and defaults
to `0`. `size` defaults to `25`; valid values are `1` through the configured
`finaxis.pagination.max-page-size` (`100` by default, and never above `100`). Resource-specific
filter, search, and sort parameters are listed with each table below. In the OpenAPI document each
list response is a typed page schema such as `ApiPageRoleSummaryResponse` whose `items` reference
the item schema.

`sort_dir` accepts `ASC` or `DESC`, case-insensitively. `sort_by` values are **camelCase**, unlike
every other wire name, and are published as an enum per endpoint:

| Endpoint | `sort_by` values |
| --- | --- |
| `GET /tenant/roles` | `roleCode`, `roleName`, `status`, `createdAt` |
| `GET /tenant/permissions` | `permissionCode`, `permissionName`, `riskLevel`, `status`, `createdAt` |
| `GET /platform/tenants` | `tenantCode`, `displayName`, `countryCode`, `createdAt` |
| `GET /branches`, `GET /platform/tenants/{tenant_id}/branches` | `branchCode`, `branchName`, `branchType`, `status`, `createdAt` |
| `GET /tenant/memberships` | `createdAt`, `membershipStatus`, `membershipType` |
| `GET /tenant/branch-assignments` | `assignedAt`, `assignmentType`, `status` |
| `GET /tenant/users` | `createdAt`, `username`, `displayName`, `email`, `userStatus`, `membershipStatus` |

On the three lists changed by #190 (`GET /tenant/memberships`, `GET /tenant/branch-assignments`
and `GET /tenant/users`), `sort_dir` alone is ignored and the default order is kept, because these
lists ignored both parameters until now: without `sort_by` the list is newest first. With a
`sort_by`, an absent `sort_dir` sorts descending. On the other lists above, `sort_dir` alone sets
the direction of the default key, `createdAt` (descending when absent). Every sort ends with the
row id, descending, so rows that tie on the sort key keep a stable order across pages. Other list
endpoints do not accept `sort_by`.

**Invalid paging or sort input is a client error, never a 500.** A `page` that is negative or not
an integer, a `size` outside `1` to `max-page-size` (`100` by default) or not an integer, a
`sort_by` that is not one of the values published for the endpoint, and a `sort_dir` other than
`ASC` or `DESC` all answer `400 Bad Request` with the standard problem body and `code`
`invalid_parameter`. When a `page` or `size` parameter is repeated, any one bad value counts. For
`sort_by` and `sort_dir` the problem carries one violation whose `field` is the parameter and whose
`message` names the accepted values without echoing the rejected one; paging problems carry no
violation. Absent parameters take their defaults, and valid requests are unaffected.

**Behaviour change (#190):** the memberships, branch-assignments and tenant-users lists used to
ignore `sort_by` and `sort_dir`, so an unknown `sort_by` on them answered `200`. It now answers
`400 invalid_parameter`, and a bad `sort_dir` answers `400` on them too. A client that sent an
unknown `sort_by` to one of these lists must correct the value.

### List Items And Counts

List items carry the fields a screen needs without a read per row (#189, #191). Every field below
is additive and snake_case. Names and dates are PII-adjacent, so each list keeps the `.view`
permission it already required.

| List | Added to each item |
|------|--------------------|
| `GET /tenant/users`, `/platform/tenants/{id}/users` | `membership_id`, `membership_type`, `created_at` (the account's), `membership_created_at` |
| `GET /tenant/memberships` | `username`, `email`, `display_name`, `primary_branch_name` (`null` with no primary branch), `created_at` |
| `GET /tenant/branch-assignments` | `username`, `display_name`, `branch_name`, `assigned_at` |
| `GET /branches`, `/platform/tenants/{id}/branches` | `parent_branch_id`, `timezone`, `opened_on`, `closed_on` (`dd-MM-yyyy`), `active_user_count` |
| `GET /platform/tenants` | `base_currency_code`, `timezone`, `bootstrap_status` (`null` with no bootstrap record), `active_branch_count`, `active_user_count` |
| `GET /tenant/roles` | `permission_count`, `active_user_count` |

The detail of a tenant user and of a branch assignment carries the same fields as its list item
(`POST /tenant/branch-assignments` answers with the summary shape).

**Counts.** Each count is computed in the same statement (or one grouped statement per page) as the
list, so a list costs the same number of queries at any page size.

- `active_user_count` on a **role** is the distinct users holding it through an `ACTIVE`
  assignment; `permission_count` is the permissions granted to it, whatever their catalogue
  status.
- `active_user_count` on a **branch** is the distinct users with an `ACTIVE` assignment to it.
- `active_branch_count` and `active_user_count` on a **tenant** count its `ACTIVE` branches and
  `ACTIVE` memberships. A suspended member or a draft branch is not counted.
- A count is **`null`**, always present, when the caller could not list the rows it counts, so a
  count never reveals what the list behind it would refuse: a role's `active_user_count` needs
  `role_assignment.view` held tenant-wide; a branch's needs `branch_assignment.view` on that
  branch; a tenant's `active_branch_count` needs `branch.view` and its `active_user_count`
  `user.view`, both tenant-wide (in the platform organisation for a platform caller). A
  `permission_count` needs only the `role.view` the list already requires.

### Makers

Who made an approvable record is on its items, so an approvals screen can show it without reading
the audit log (#156). Each is `{ "id", "display_name" }`, always present, and `null` when there is
no maker to show.

| Resource | Fields | Source |
|----------|--------|--------|
| membership, tenant user | `created_by` | who invited the member |
| branch | `created_by`, `submitted_by` | its creator; the actor of its latest `SUBMIT` |
| tenant | `requested_by`, `submitted_by` | the draft's requester; the submitter of the current submission |

A maker is `null` when the **system** made the record (the bootstrap head office, a system-run
invitation: it has no account), when the record was **never submitted**, or when the caller may
not see them. The last rule protects staff: **a tenant caller sees a maker only if they belong to
that tenant**, so a platform operator who created a branch or invited a member on the tenant's
behalf is shown to platform callers only. A tenant's `requested_by` and `submitted_by` are platform
users, so a tenant caller never sees them. Names come from the account's `display_name`.

### Errors

Errors use RFC 9457 `application/problem+json` with Finaxis extensions:

```json
{
  "type": "urn:finaxis:problem:validation_failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Request validation failed.",
  "instance": "/api/v1/platform/tenants",
  "code": "validation_failed",
  "request_id": "019f7d3b-3fa4-7f91-a0a4-49b038244bd0",
  "violations": [
    {
      "field": "admin.email",
      "code": "Email",
      "message": "must be a well-formed email address"
    }
  ]
}
```

```json
{
  "type": "urn:finaxis:problem:conflict",
  "title": "Conflict",
  "status": 409,
  "detail": "The requested state change conflicts with the current resource state.",
  "instance": "/api/v1/tenant/business-date/advance",
  "code": "conflict",
  "request_id": "019f7d3d-8f16-7b79-b8e2-c73744af3598"
}
```

```json
{
  "type": "urn:finaxis:problem:rate_limit_exceeded",
  "title": "Too Many Requests",
  "status": 429,
  "detail": "Too many requests. Retry after the reset time.",
  "instance": "/api/v1/auth/select-organisation",
  "code": "rate_limit_exceeded",
  "request_id": "019f7d3e-dc16-745e-a1c1-c81899dcc4ad"
}
```

Application exception mappings:

| Exception                     | HTTP status |
|-------------------------------|-------------|
| `ResourceNotFoundException`   | 404         |
| `ConflictException`           | 409         |
| `ForbiddenOperationException` | 403         |
| `InvalidOperationException`   | 422         |
| `InvalidRequestException`     | 400         |
| `RequestTooLargeException`    | 413         |

#### A mutation needs its view: the named 403

Every mutation requires its own permission **and every view permission the catalogue pairs with
it** (`required_view_permissions` on `GET /api/v1/tenant/permissions`; for example
`branch.suspend` needs `branch.view`, `user.invite` needs `membership.view` and `user.view`) at the
same scope: the tenant, the target branch, or the platform organisation (ADR 0030). The route
tables below list both codes. The check is central, runs first in the service (after body
validation and the endpoint's `@PreAuthorize` gate, before the platform-organisation 404 or 409,
any existence lookup, the ADR 0028 window and the state check), and writes nothing when it
refuses, so an unknown id answers `403` and not `404` to a caller without the permission. That
holds for role-permission remove too (its permission check is the first thing the service does,
before the grant is read), and for the two revoke routes that take only an assignment id, which
resolve the assignment's scope through an authorised combined lookup (see
[Branch Assignments](#branch-assignments) and [Role Assignments](#role-assignments)). Tenant
settings resolve the setting key and organisation state first; refusals of the target-aware reads
stay unnamed. The OpenAPI `403` response of every such route lists its mutation code and its views.
The refusal is `403 application/problem+json` with `code` `forbidden` and a
`detail` naming the first missing code, the mutation code first and then each view in code order:

```json
{
  "type": "urn:finaxis:problem:forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "Missing permission: branch.view.",
  "code": "forbidden",
  "request_id": "..."
}
```

Nothing is changed: no state, transition log, audit success row, outbox row or idempotency row is
written, so a retry re-evaluates. A direct `DENY` of a view blocks the mutations that need it. The
unnamed `403` of a `@PreAuthorize` gate (the mutation code absent from the caller's selected
context) and a resource-level refusal that carries a resource reference stay generic. An
idempotent replay still returns the stored response of the caller's own completed write after the
gate only. A custom role that holds a mutation without its view gets this `403` until the view is
added to it (see the operator report shipped with the role-composition change).

Framework-level errors include `authentication_required`, `access_denied`,
`invalid_active_tenant_context`, `rate_limit_exceeded`, `rate_limit_policy_unavailable`,
`rate_limiter_unavailable`, and `request_rejected` (400: Spring Security's firewall refused the
request, for example a header value carrying CR, LF or a C1 control, or an unnormalised path such
as one with `;` or `//`). Its body names nothing from the request: `instance` is the fixed
`about:blank`, never the request path (which may be the refused value), and `request_id` is the
resolved request id. A path is refused before the security headers are written, so this response
sets its own: `X-Content-Type-Options: nosniff`, `Cache-Control: no-store`, `X-Frame-Options:
DENY`, the referrer policy, `Content-Security-Policy: default-src 'none'; frame-ancestors 'none'`
and, when enabled, HSTS. It also applies the configured CORS policy, which `CorsFilter` never got
to: an allowed `Origin` gets its `Access-Control-*` headers, any other origin none. The firewall
checks a header value only when it is read, so a value the access log reads last (`Referer`,
`User-Agent`) is refused after the response was written: a committed response is then left as
written, with a message-free `WARN` naming the request id in place of the access-log line, and an
uncommitted one has its body discarded before the problem is written. Bean Validation failures
return 400 with up to 100 violations.

`code` is always a stable machine value and never a sentence; the human text is in `detail`.
Two `403` codes name a route that was called from the wrong organisation context:

- `platform_context_required` (403): a platform route (`/api/v1/platform/**`) was called with no
  context or with a tenant context. Idempotent platform mutations answer it too: the idempotency
  scope check runs before the controller and refuses with the same code and detail.
- `tenant_context_required` (403): a tenant route (roles, permissions, users, memberships,
  branches, settings, audit, business date, ...) was called with no active tenant context, or with
  the reserved platform organisation as context (`detail`: `This route is restricted to
  non-platform tenant context.`; it used to say `Branch operations ...` on every such route).

Both codes are returned only where the request reaches the context check: the idempotency scope
check or the controller. Method security runs first, so a request that lacks the route's
`@PreAuthorize` authority is refused before either, with the generic `forbidden` (detail `You are
not permitted to perform this action.`). A request with no active-organisation context carries no
permission authority, so on a gated tenant mutation (`POST /api/v1/branches`, for example) it gets
`forbidden`, not `tenant_context_required`; the ungated tenant mutations (`POST
/api/v1/auth/select-branch`, `PUT` and `DELETE /api/v1/tenant/settings/{key}`) and a platform
context that holds the route's code do reach the check and get the context code.

Before this change both put the sentence in `code` (for example `Active tenant context is
required for this route.`); clients that matched on it must match the codes above. Statuses and
the `forbidden` code with its `Missing permission: <code>.` detail are unchanged.

A bearer token the resource server rejects (malformed, expired, bad signature) answers `401` with
the same `application/problem+json` body (`code` `authentication_required`) and `X-Request-Id`
header as a missing token, plus the RFC 6750 `WWW-Authenticate: Bearer ...` challenge. It used to
carry only the challenge, with an empty body and no request id.

Both `401`s carry an RFC 6750 challenge with the `resource_metadata` URL
(`<origin>/.well-known/oauth-protected-resource`). `<origin>` is the scheme, host and port the
request arrived with; `X-Forwarded-Proto`/`-Host`/`-Port` change it only when the direct peer is
a listed trusted proxy, and `Forwarded` and `X-Forwarded-Prefix` never do (#256,
`docs/security/production-hardening.md`, "Forwarded headers"):

- no token: `WWW-Authenticate: Bearer resource_metadata="..."`, with **no** `error` (RFC 6750
  section 3: a request without credentials is not told why). It used to carry no challenge.
- rejected token: `error="invalid_token"` and a fixed
  `error_description="The access token is invalid or has expired."`. The decoder's own message
  (expiry instants, failing claims) is never sent; it used to be echoed verbatim.

The advertised URL serves the [protected-resource metadata](#protected-resource-metadata). Its
location follows the request's origin; the document's own `resource` is configured (falling back
to the request's origin only when it is not set).

A challenge with a status other than `401` (an RFC 6750 `invalid_request`, `400`, such as two
tokens in one request) gets the body `code` `invalid_request`, `detail` `Malformed request.`.
It is unreachable while the resolver reads tokens from the `Authorization` header only, as it does.

### Idempotency

Every mutation endpoint, meaning every `POST`, `PUT`, `PATCH`, and `DELETE`, accepts an optional
UUID request header:

```http
Idempotency-Key: 018f7d3f-4e8e-7bf1-b0a8-9ac8087f2299
```

If the header is omitted, the server generates a UUID and echoes it back in the
`Idempotency-Key` response header. Reusing the same key, request fingerprint, and scope within
the durable window returns the original response and sets:

```http
Idempotency-Replayed: true
```

Scopes are server-owned:

| Scope                    | Used by                                              |
|--------------------------|------------------------------------------------------|
| `PLATFORM`               | Platform mutations under `/api/v1/platform/**`       |
| `TENANT`                 | Tenant mutations after active organisation selection |
| `ORGANISATION_SELECTION` | `POST /api/v1/auth/select-organisation`              |

Ordinary mutations use `EXACT_RESPONSE` replay. Organisation and branch selection use
`REISSUE_CONTEXT_TOKEN`, because context tokens are sensitive and time-bound. Durable replay
stores only safe selection state, revalidates it, restores the browser session, and issues a
fresh valid context token for the same selection.

Before a response is stored it is checked for credential-like field names (`key`, `token`,
`secret`, `session`, and so on, matched per word, so `api_key` is rejected). The one exemption is
the `key` field of a response that is exactly the tenant-setting shape (`key`, `value`,
`value_type`, `sensitive`, `platform_admin_only`) returned by `PUT /api/v1/tenant/settings/{key}`,
where it names the setting rather than carrying a credential.

### Rate Limiting And Correlation

Every response includes:

```http
RateLimit-Limit: 100
RateLimit-Remaining: 99
RateLimit-Reset: 1721905200
X-Request-Id: 019f7d42-8db9-7ef7-9f5e-53211981bb54
```

429 responses also include `Retry-After`. Named policies distinguish platform reads and writes,
tenant reads and writes, and the dedicated `AUTH_SELECTION` policy for organisation and branch
selection. Full policy detail is in [rate limiting](../architecture/rate-limiting.md).

`X-Request-Id` may be supplied by the client or generated by the server. A client value is
accepted only if it is 8 to 64 characters of `[A-Za-z0-9._-]`; any other value (spaces, quotes,
CR/LF, non-ASCII, too short or too long) is replaced by a generated UUIDv7, so the application
never echoes, logs, puts in the MDC or stores a rejected value (an error monitor such as Sentry
may still attach the raw request headers to an event it records, and a trace exporter records
the firewall's exception message, which holds a refused value, on the request's span). The one
id chosen
is echoed on success and error responses, used as the problem body's `request_id`, and carried in
the request context that audit events and posting requests record. A value carrying CR or LF does
not reach the application: the HTTP parser ends or refuses the header line there, and inside the
container Spring Security's firewall refuses the request with a `400` `request_rejected` problem
whose `request_id` (and `X-Request-Id` header) is a generated one.

`X-Correlation-Id` is optional and is never echoed in a response (the audit read API returns the
recorded `correlation_id`). It takes the same shape as `X-Request-Id`
(8 to 64 characters of `[A-Za-z0-9._-]`, #252) and, when accepted, is the `correlation_id` that
the MDC, audit events and posting requests record for an authenticated request.
When it is absent or any other value, the request id is used instead, and a rejected value is
never logged, put in the MDC, stored or echoed. The header is read only for an authenticated
request with an active organisation context: there a value carrying CR or LF is refused by the
firewall with the same `400` `request_rejected` problem, and on any other request it is never
read, so it is ignored.

## Endpoint Reference

The `Shape` column uses `page` for an `ApiPage<T>` collection, `item` for a single resource, and
`mutation` for state-changing operations.

### Protected-Resource Metadata

`GET /.well-known/oauth-protected-resource` (#253) is the RFC 9728 document the `401`
challenge's `resource_metadata` points at. It is **intentionally public and unversioned**, the
only route outside `/api/vN`: the standard fixes the path, and a client reads it before it has a
token. It is not in the OpenAPI document, since Spring Security's own filter serves it (ahead of
bearer authentication, the active-organisation filter and the rate limiter, though after the
security-header and CORS filters), not a controller.
A bearer token or context header sent with it is ignored. Only `GET` is served.

| Method | Path | Auth | Shape |
| --- | --- | --- | --- |
| `GET` | `/.well-known/oauth-protected-resource` | none | item |

`200`, `Content-Type: application/json`, exactly these members:

```json
{
  "resource": "https://api.finaxis.example",
  "authorization_servers": ["https://id.finaxis.example/realms/finaxis"],
  "bearer_methods_supported": ["header"]
}
```

- `resource`: `finaxis.security.protected-resource.resource`
  (`FINAXIS_PROTECTED_RESOURCE_URL`), the canonical origin (plus the request's path for a
  path-suffixed request, see below), lower-cased without a trailing `/` or
  the scheme's default port (`:443`, `:80`), so it reads like the challenge's URL. When it is set,
  **nothing in the request changes it**: no `Host`, `X-Forwarded-*` or `Forwarded` header, from a
  trusted proxy or not. When it is not set (production has no default; startup logs one `WARN`),
  `resource` falls back to Spring's value: the origin the request arrived with, as believed
  through the trusted-proxy list (#256). A set but invalid value fails startup.
- `authorization_servers`: the Keycloak issuer the JWT decoder trusts
  (`spring.security.oauth2.resourceserver.jwt.issuer-uri`); omitted if none is configured.
- `bearer_methods_supported`: `header` only; tokens in a query parameter or form body are not
  read.
- Absent on purpose: `tls_client_certificate_bound_access_tokens` (Spring's default claims `true`;
  the application does not bind tokens to client certificates) and `scopes_supported` (access is
  decided by application permission codes, not OAuth scopes).

RFC 9728 section 3.3 requires `resource` to be identical to the resource identifier the client
derived the metadata URL from, and a client to reject a mismatch. Spring also answers path-suffixed
requests (`/.well-known/oauth-protected-resource/<path>`), so `resource` keeps the request's path
after the well-known segment:

- `GET /.well-known/oauth-protected-resource` names the origin (`https://api.finaxis.example`);
- `GET /.well-known/oauth-protected-resource/api/v1/auth/me` names
  `https://api.finaxis.example/api/v1/auth/me`.

When the property is set, only that path comes from the request: scheme, host and port are always
the configured ones. A path that is not already normalised (dot segments) or does not parse is not
reflected: the document then names the bare origin, which such a client rejects. Unset, Spring's
request-derived value is used as is. The `401` challenge advertises the root URL, whose `resource`
is the origin; a client that called `https://api.finaxis.example/api/v1/...` and compares
literally would refuse it, so clients following the challenge are expected to match `resource` as
the origin of the API URLs they call (the RFC's section 5.1 example, Spring's own root default, and
what MCP-style clients do).

### Auth And Profile

Base path: `/api/v1/auth`.

| Method | Path                   | Summary                                           | Permission                    | Shape    |
|--------|------------------------|---------------------------------------------------|-------------------------------|----------|
| GET    | `/me`                  | Current user profile and effective access         | `iam.profile.read`            | item     |
| GET    | `/organisations`       | List available organisations                      | `auth.select_organisation`    | page     |
| GET    | `/branches`            | List available branches for selected organisation | `auth.select_branch`          | page     |
| POST   | `/select-organisation` | Select organisation                               | `auth.select_organisation`    | mutation |
| POST   | `/select-branch`       | Select or clear the active branch                 | service: `auth.select_branch` | mutation |

Available organisation discovery is context-free and requires only the bearer JWT:

```http
GET /api/v1/auth/organisations?page=0&size=25
Authorization: Bearer <token>
```

The response includes only active organisations where the authenticated user has an active
membership and `auth.select_organisation` permission:

```json
{
  "items": [
    {
      "organisation_id": "11111111-1111-7111-8111-111111111111",
      "membership_id": "22222222-2222-7222-8222-222222222222",
      "tenant_code": "acme-corp",
      "display_name": "Acme Financial Services",
      "organisation_status": "ACTIVE",
      "membership_status": "ACTIVE"
    }
  ],
  "page": {
    "number": 0,
    "size": 25,
    "total_items": 1,
    "total_pages": 1,
    "has_next": false,
    "has_previous": false
  }
}
```

After selecting an organisation, clients can discover assigned active branches before calling
the branch-selection mutation:

```http
GET /api/v1/auth/branches?page=0&size=25
Authorization: Bearer <token>
X-Active-Organisation-Context: <context-token>
```

This endpoint requires the active organisation context and `auth.select_branch` permission. Its
paginated items contain `branch_id`, `branch_code`, `branch_name`, and `branch_status`. Each
branch is listed (and counted in `page.total_items`) once, however many ACTIVE assignment rows
(`HOME`, `OPERATE`, ...) the user holds on it, and only while the branch itself is `ACTIVE`.

Selection request and response:

```json
{
  "organisation_id": "11111111-1111-7111-8111-111111111111"
}
```

```json
{
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "membership_id": "22222222-2222-7222-8222-222222222222",
  "context_token": "opaque-signed-context-token",
  "context_header": "X-Active-Organisation-Context",
  "branch_id": null,
  "requires_branch_selection": true,
  "assigned_branch_ids": [
    "33333333-3333-7333-8333-333333333333"
  ]
}
```

`assigned_branch_ids` holds each ACTIVE assigned branch once. A user with exactly one distinct
ACTIVE branch is auto-selected (`branch_id` set, `requires_branch_selection` false) even when
that branch is held through several assignment rows, and a suspended or closed branch is not a
choice: with one ACTIVE and one SUSPENDED branch the ACTIVE one is auto-selected. With two or
more distinct ACTIVE branches `branch_id` is `null` and `requires_branch_selection` is true.

`POST /select-branch` takes an optional `branch_id`. A UUID selects that assigned branch. Omitting
it (`{}`) or sending `null` clears the selection (a blank or malformed `branch_id` is a `400`, it
never clears) and returns an institution-level context for any
user, including a single-branch user auto-selected by `select-organisation`:

```json
{
  "branch_id": "33333333-3333-7333-8333-333333333333"
}
```

```json
{
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "membership_id": "22222222-2222-7222-8222-222222222222",
  "branch_id": "33333333-3333-7333-8333-333333333333",
  "context_token": "opaque-signed-context-token",
  "context_header": "X-Active-Organisation-Context"
}
```

Clearing response (`{}` or `{"branch_id": null}`):

```json
{
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "membership_id": "22222222-2222-7222-8222-222222222222",
  "branch_id": null,
  "context_token": "opaque-signed-context-token",
  "context_header": "X-Active-Organisation-Context"
}
```

The response echoes the selected `branch_id`; after a clear the property is present with an
explicit `"branch_id": null` (like the `select-organisation` response above), never omitted.

Profile response example:

```json
{
  "user_id": "44444444-4444-7444-8444-444444444444",
  "keycloak_subject": "local.admin",
  "email": "admin@finaxis.test",
  "full_name": "Local Administrator",
  "organisation": {
    "id": "11111111-1111-7111-8111-111111111111",
    "tenant_code": "acme-corp",
    "display_name": "Acme Financial Services",
    "status": "ACTIVE"
  },
  "membership": {
    "id": "22222222-2222-7222-8222-222222222222",
    "status": "ACTIVE",
    "membership_type": "ADMIN"
  },
  "selected_branch": null,
  "branches": [],
  "roles": [],
  "permissions": [
    "iam.profile.read"
  ],
  "permission_scopes": [
    {
      "code": "branch.suspend",
      "scope": "BRANCH",
      "branch_ids": ["33333333-3333-7333-8333-333333333333"]
    },
    {
      "code": "iam.profile.read",
      "scope": "TENANT"
    }
  ]
}
```

`branches` and `roles` list each entry once and only while it is current: a branch appears only
while it is `ACTIVE` (a `SUSPENDED` or `CLOSED` branch is omitted, as it is from the selection
lists), and a role only while it is `ACTIVE` (a `DISABLED` or `ARCHIVED` role is omitted), however
many assignment rows (several branch assignment types, or the same role at tenant and branch
scope) connect them to the membership. `permissions` is unchanged: it is the effective set
the runtime authorises with, which already counts only ACTIVE roles and permissions.

`permission_scopes` (issue #175) says **where** each permission is held, which the flat list cannot:
`permissions` is narrowed to the selected branch (tenant-wide grants plus that branch's), so a
caller pinned to one branch never sees a grant that exists only on another. Each entry is
`{code, scope, branch_ids?}`:

- `scope: "TENANT"`: held across the tenant (a tenant-scope role assignment, or a direct `ALLOW`
  override, which is always tenant-wide). `branch_ids` is absent.
- `scope: "BRANCH"`: held only on the branches in `branch_ids` (ascending), through branch-scope
  role assignments. `branch_ids` is present only for this scope.

A code held both tenant-wide and on a branch is reported once, as `TENANT`. Only `ACTIVE` branches
count, the rule of #242: a grant held only on a suspended or closed branch is absent, and a
branch drops out of `branch_ids` as it leaves `ACTIVE`. A direct `DENY` override removes the code
altogether, as it does from `permissions`. A membership that is not `ACTIVE` reports none. The
list is sorted by `code`, covers every `ACTIVE` branch whatever branch is selected, and is read
fresh on each request (never from the effective-permission cache). It is informational, like the
rest of the profile: authorisation still happens on the server per request and never trusts it.

### Branches

Base path: `/api/v1/branches`. List filters: `q`, `status`, `type`, `sort_by`, `sort_dir`,
`page`, `size`.

| Method | Path                      | Summary                              | Permission          | Shape    |
|--------|---------------------------|--------------------------------------|---------------------|----------|
| GET    | `/`                       | Search branches in the active tenant | `branch.view`       | page     |
| POST   | `/`                       | Create branch draft                  | `branch.create` + `branch.view` | mutation |
| GET    | `/{branch_id}`            | Get branch                           | `branch.view`       | item     |
| PATCH  | `/{branch_id}`            | Update branch                        | `branch.update` + `branch.view` | mutation |
| POST   | `/{branch_id}/submit`     | Submit branch draft                  | `branch.create` + `branch.view` | mutation |
| POST   | `/{branch_id}/activate`   | Approve and activate branch          | `branch.approve` + `branch.view` | mutation |
| POST   | `/{branch_id}/return`     | Return or withdraw a pending branch  | `branch.approve` (checker) or `branch.create` (maker) + `branch.view` | mutation |
| POST   | `/{branch_id}/suspend`    | Suspend branch                       | `branch.suspend` + `branch.view` | mutation |
| POST   | `/{branch_id}/reactivate` | Reactivate branch                    | `branch.reactivate` + `branch.view` | mutation |
| POST   | `/{branch_id}/close`      | Close branch                         | `branch.close` + `branch.view` | mutation |
| POST   | `/{branch_id}/discard`    | Discard a draft branch               | `branch.create` + `branch.view` | mutation |

#### Reading branches (target-aware, ADR 0030)

`GET /branches` and `GET /branches/{branch_id}` are authorised **at the branch they concern**, not
against the branch the caller is pinned to. The check is a **tenant-wide `branch.view` grant or a
`branch.view` grant on that branch** (a role assigned at `BRANCH` scope on it); the endpoint
carries no coarse `@PreAuthorize` gate, so the application-layer check is the only authorisation
and a caller with no grant at all still gets `403`.

- `GET /branches/{branch_id}`: a tenant-wide holder gets `404` for an unknown id. A branch-scoped
  holder gets `403` for a branch it holds nothing on and for an unknown id alike, so the response
  never reveals whether a branch exists (identical to the mutation routes). No grant: `403`.
- `GET /branches`: returns every branch the caller holds `branch.view` on (all of them for a
  tenant-wide grant). The restriction is part of the query, so `page.total_items`, `total_pages`
  and every page are exact for what the caller may see. No grant: `403`. Sort, filter and the
  `400` paging validation are unchanged.
- The selected branch keeps its meaning for what the caller *does*; it does not narrow what these
  reads see. A caller pinned to branch A whose only `branch.view` is scoped to branch B lists and
  gets B, and sees nothing of A unless granted.
- A grant scoped to a branch counts only while that branch is `ACTIVE` (issue #242). A
  branch-scoped holder no longer sees a `SUSPENDED`, `CLOSED` (or otherwise non-`ACTIVE`) branch:
  it is absent from `GET /branches` and `GET /branches/{branch_id}` answers `403`, as for any
  branch the caller holds nothing on; it is visible again once reactivated. A tenant-wide holder
  sees every branch whatever its status. The mutation routes apply the same target-branch check,
  so a branch-scoped holder cannot act on such a branch either (a reactivate is `403`
  `Missing permission: branch.view.`), while one that suspends or closes its `ACTIVE` branch still
  gets the branch back in the response.
- Platform routes (`/platform/tenants/{tenant_id}/branches`) are unchanged: the platform
  `branch.view` is checked in the platform organisation.

Create branch request and detail response:

```json
{
  "branch_code": "HEAD-OFFICE",
  "branch_name": "Head Office Branch",
  "branch_type": "HEAD_OFFICE",
  "parent_branch_id": null,
  "timezone": "Africa/Nairobi",
  "address": {
    "city": "Nairobi",
    "line_1": "Kenyatta Avenue"
  }
}
```

```json
{
  "id": "33333333-3333-7333-8333-333333333333",
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "branch_code": "HEAD-OFFICE",
  "branch_name": "Head Office Branch",
  "branch_type": "HEAD_OFFICE",
  "parent_branch_id": null,
  "status": "ACTIVE",
  "timezone": "Africa/Nairobi",
  "address": {
    "city": "Nairobi",
    "line_1": "Kenyatta Avenue"
  },
  "opened_on": "25-07-2026",
  "closed_on": null,
  "status_reason": null,
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:10:00Z"
}
```

`address` is a free-form map of string values: the detail response (tenant and platform `GET
/branches/{branch_id}`, and every lifecycle response that returns the detail) echoes exactly the
keys and values that were stored on create. A stored value that cannot be read back as such a map
is answered as `{}` rather than failing the read.

#### Update branch

`PATCH /api/v1/branches/{branch_id}` changes a branch's name, parent, timezone or address in place
and answers `200` with the branch detail above, so the response carries the stored address and the
`opened_on`/`closed_on` dates.

```json
{
  "branch_name": "Riverside Branch",
  "parent_branch_id": "33333333-3333-7333-8333-333333333333",
  "timezone": "Africa/Kampala",
  "address": { "city": "Kampala", "line_1": "3 Lake Road" }
}
```

- **Partial update.** Every field is optional and an absent field is unchanged. At least one must
  be present: `{}` is `400 validation_failed` and a missing body is `400 invalid_json`. A
  supplied `address` **replaces** the stored map as a whole; it is not merged key by key.
- **Clearing the parent.** `parent_branch_id` is the one field with a "none" value: an explicit
  JSON `null` detaches the branch from its parent, while leaving the key out keeps the parent. For
  the other fields `null` is the same as absent.
- **Not updatable.** `branch_code` and `branch_type` are fixed once created; sending either is
  `400 invalid_json` (unknown field), as is any other unknown field. Status changes only through
  the lifecycle routes.
- **State gate.** Only a `DRAFT` or `ACTIVE` branch can be updated (`409 conflict` otherwise:
  `PENDING_APPROVAL`, `SUSPENDED`, `CLOSED`, `ARCHIVED`). `DRAFT` is allowed so a draft returned
  for changes (ADR 0029) can be amended by its maker. The update does not change the status and
  is not an FSM transition.
- **Validation.** `branch_name` is 2-100 characters and not blank (`400 validation_failed`);
  `timezone` must be a valid zone id (`422 invalid_operation`). `parent_branch_id` must be a
  branch of the same organisation (`404 resource_not_found` otherwise, as on create), must not be
  the branch itself, and must not be a descendant of it (`422 invalid_operation`), and must not be
  `CLOSED` or `ARCHIVED` (`409 conflict`: closing refuses a branch with active children, so this
  is the same business rule, reported the way a closure guard reports it). The check applies to a
  `DRAFT` branch too; create itself only requires that the parent exists. Moving a branch
  serialises with other parent changes in the tenant, so two concurrent moves cannot together
  form a cycle.
- **Permission.** `branch.update` (#203), a dedicated code: `branch.create` alone is `403`, so a
  maker-only role cannot edit a live branch with no checker. Migration `V19` seeds it and copies it
  to every role that held `branch.create` at the time, so existing callers keep access; revoke
  `branch.update` to take it away. It is checked in the application service against the **target
  branch** whatever branch the caller has selected, then a missing or foreign branch is `404`
  (after the permission, so a caller without it learns nothing about which ids exist), then the
  state is checked (`409`).
- **Audit and idempotency.** Writes a `branch.update` audit row whose metadata lists the changed
  field **names** (`changedFields`, for example `branch_name,address`) and never their values.
  Accepts an optional `Idempotency-Key`; a replay returns the stored response and applies the
  update once. The row version is incremented by each update, and a status change that commits
  first makes the update `409` rather than overwriting it.

The response is the branch detail produced by the same gated read as `GET /branches/{branch_id}`
at the branch the update was checked against (so a branch-scoped editor sees the branch it
edited).

The platform route has no equivalent: a platform administrator creates, submits and activates a
tenant's branch but does not edit it.

#### Approving a branch (`activate`)

`POST /branches/{branch_id}/activate` (tenant) and
`POST /platform/tenants/{tenant_id}/branches/{branch_id}/activate` (platform checker) share one
maker-checker rule. The order is: the permission (403), the branch (404), on the platform route
the window (409), the maker-checker rule (403), then the tenant state (409) and the branch state
(409):

- `403 lifecycle.approver_is_branch_maker` ("The approver cannot be the branch's creator or
  submitter.") when the approver **created** the branch, and, on the platform route, when it
  **submitted** it. A tenant submitter is not refused (ADR 0028). Before #156 this was the generic
  `forbidden`, which a missing permission also answers.
- `403 lifecycle.approver_is_branch_modifier` when the approver **amended** the branch, on either
  route: anyone with a successful `branch.update` audit event on it, however many amends came
  later (the row's `updated_by` is not used: a checker's return and every submit stamp it, so at
  approval time it is always the submitter). The checker who only *returned* the draft amended
  nothing and may approve its resubmission. An amender whose edit was later overwritten is still
  refused, so a tiny tenant may need a third person or the platform checker.
- `409 lifecycle.platform_checker_closed`, `409 conflict` (tenant not `ACTIVE`, branch not
  `PENDING_APPROVAL`) as before.

Each of the two maker-checker refusals writes a `DENIED`, `HIGH` audit row (action
`branch.activate`, resource the branch, `branch_id` the target branch rather than the caller's
pinned one, reason the response code: `lifecycle.approver_is_branch_maker` or
`lifecycle.approver_is_branch_modifier`) in
its own transaction, so the request's rollback does not take it (#251, as the tenant decisions
do). A refusal earlier in the order (permission, 404, window) writes none. Until #251 these
refusals left no audit row. An audit search on `action=branch.activate` therefore also returns
refused attempts; add `outcome=SUCCESS` for activations only.

`opened_on` and `closed_on` are business dates (`dd-MM-yyyy`) taken from the tenant's current
business date, never the wall clock, and are `null` until they apply:

- `opened_on` is set when the branch first becomes `ACTIVE`, including the head office that tenant
  approval activates. A suspend and reactivate does not move it.
- `closed_on` is set when the branch is `CLOSED`. `CLOSED` only moves on to `ARCHIVED`, so it is
  never cleared. A discarded draft (below) has a `closed_on` and no `opened_on`.

Branches that predate this behaviour were backfilled once, best-effort, by migration `V18`: where
a date was still `null`, it is the day of the branch's earliest transition into `ACTIVE` (or
`CLOSED`) in the organisation's timezone. Those dates are approximate (the day the transition was
recorded, not the business date at that moment), and a branch with no such transition on record
keeps `null`. Dates stamped by the application are exact.

#### Return or withdraw a pending branch

`POST /api/v1/branches/{branch_id}/return` sends a branch that is `PENDING_APPROVAL` back to
`DRAFT` ([ADR 0029](../adr/0029-approval-model-per-resource-extensions.md), issue #180) and answers
`200` with the branch detail (`status` `DRAFT`, `status_reason` the reason). The body is required:

```json
{ "reason": "Branch name has a typo." }
```

`reason` is 3 to 500 characters and not blank. A missing or unreadable body, or an absent or `null`
`reason`, is `400 invalid_json`; a blank or out-of-range `reason` is `400 validation_failed`.
There is no `422`. Validation runs when the body is bound, before any permission or existence
check.

One route serves two intents, told apart by **who the caller is** relative to the branch, never by
a flag in the request:

| Intent | Caller | Permission | Audit rows |
|--------|--------|------------|------------|
| **Withdraw** | the branch's creator, or the actor of its latest `SUBMIT` | `branch.create` | `branch.return_for_changes` (FSM) and `branch.withdraw`, both with the reason |
| **Return for changes** | anyone else | `branch.approve` | `branch.return_for_changes` (FSM) |

- **No new permission code.** A creator who also holds `branch.approve` is still a maker for this
  branch, so is classified as withdrawing and needs `branch.create`. A creator or submitter who has
  since lost `branch.create` cannot withdraw, but a non-maker holding `branch.approve` can still
  return the branch.
- **Maker-checker.** Returning is the one decision a maker may take on their own branch: it hands
  the branch back to them and can never activate anything. The creator rule (and, on the platform
  route, the submitter rule) keeps applying to every later `ACTIVATE`; a branch the creator drafted
  cannot be activated by the creator however many times it loops. Anyone who amended the
  draft is barred too (`403 lifecycle.approver_is_branch_modifier`); the checker who returned it
  is not, unless they also amended it.
- **Check order.** The permission asked depends on the caller, so the caller is classified first
  (reading `created_by` and the latest submitter, scoped to the path organisation; a branch absent
  from it has neither, so it classifies as a return and a missing branch looks the same as another
  tenant's). Then: the permission for that intent against the **target branch** (`403`, before any
  existence signal); the branch (`404`); the organisation state (`409`); the branch state (`409`).
- **State.** Only `PENDING_APPROVAL` can be returned; `DRAFT`, `ACTIVE`, `SUSPENDED`, `CLOSED` and
  `ARCHIVED` answer `409 conflict` and change nothing. The branch keeps its code, its parent and
  its `created_by`, and **the code stays taken** (`uq_branch_organisation_code` ignores status), so
  a withdrawn branch is amended, not recreated.
- **Organisation state.** The tenant must be `ACTIVE` or `PROVISIONING`, as for creating or
  platform-submitting a branch (`409`, or `404` for no such organisation); a `SUSPENDED` or
  `DEPROVISIONING` tenant is frozen. Permission resolution already refuses a tenant caller whose
  organisation is not `ACTIVE` (`403`), so the `409` is the platform route's in practice.
- **Amend and resubmit.** A returned branch is a `DRAFT`: `PATCH /api/v1/branches/{branch_id}`
  (see [Update branch](#update-branch)) amends it, for a caller holding `branch.update`, and
  `POST .../submit` resubmits it. `submit` is unchanged, so the submitter of the new request is
  whoever resubmits.
- **Events.** None. The transition publishes an internal event only; consumers of
  `finaxis.lifecycle.branch.approval-requested` see a repeat per resubmission with no event for the
  return in between (see [transactional outbox](../architecture/transactional-outbox-amqp.md)).
- **Response and idempotency.** The caller needs `branch.view` at the target branch in addition to
  `branch.approve` or `branch.create` (the named 403 above); a role holding only the mutation code
  is refused before anything changes. The route
  accepts an optional `Idempotency-Key`; a replay returns the stored response and returns the
  branch once.

The platform route is `POST /api/v1/platform/tenants/{tenant_id}/branches/{branch_id}/return`
(see [Platform Tenant Branches](#platform-tenant-branches)).

#### Discard a draft branch

`POST /api/v1/branches/{branch_id}/discard` abandons a branch that is a `DRAFT`
([ADR 0036](../adr/0036-discard-a-draft-branch.md), issue #222): it moves to `CLOSED` through its
own `DISCARD` transition and answers `200` with the branch detail (`status` `CLOSED`,
`status_reason` the reason, `closed_on` the tenant's business date, `opened_on` `null`). The body
is required, as for `return` and `close`:

```json
{ "reason": "Created against the wrong organisation." }
```

`reason` is 3 to 500 characters and not blank. A missing or unreadable body, or an absent or `null`
`reason`, is `400 invalid_json`; a blank or out-of-range `reason` is `400 validation_failed`.
Validation runs when the body is bound, before any permission or existence check.

- **Permission: `branch.create`, plus its view `branch.view`**, both against the **target branch**
  (`403` naming the first missing code, before any existence signal), as for submit and withdraw.
  No new code and no migration. A grant scoped to a branch counts only while that branch is
  `ACTIVE`, so a draft is discarded by a tenant-wide holder.
- **Check order.** Permission (`403`); the branch in the path organisation (`404`); the organisation
  state, which must be `ACTIVE` or `PROVISIONING` (`409`; permission resolution already refuses a
  tenant caller whose organisation is not `ACTIVE` with `403`); the head office (`409
  lifecycle.head_office_branch_protected`: the bootstrap seeds it as a draft and tenant approval
  activates it); then the branch state and the closure guard (`409`).
- **State.** Only `DRAFT` can be discarded; `PENDING_APPROVAL` (withdraw it first), `ACTIVE`,
  `SUSPENDED`, `CLOSED` and `ARCHIVED` answer `409 conflict` and change nothing. A draft with an
  `ACTIVE` child branch, or an active assignment, is `409` with a message that says to close or
  re-parent the child (or reassign) "before discarding this branch".
- **The code stays taken.** The discarded branch keeps its `branch_code` and
  `uq_branch_organisation_code` ignores status, so creating a branch with that code is `409
  conflict`, as it is for any closed branch. It is not freed, and there is no hard delete.
- **Audit and log.** One FSM row, action `branch.discard`, with the reason and the actor, and one
  `branch_transition_log` row named `DISCARD` (`DRAFT` to `CLOSED`), which tells a branch that never
  opened from one that was closed. No service row repeats it (ADR 0031).
- **Events.** None. The transition publishes an internal event only; unlike `close` it emits no
  `finaxis.lifecycle.branch.closed`, because a branch that never opened has nothing to announce.
- **Response and idempotency.** The route accepts an optional `Idempotency-Key`; a replay returns
  the stored response and discards the branch once. A fresh key against the now-closed branch is
  `409`. A discarded branch can still be archived like any closed one.

There is no platform route for discard.

### Platform Tenant Administration

Base path: `/api/v1/platform/tenants`. List filters: `q`, `status`, `country`,
`created_from`, `created_to`, `include_platform`, `sort_by`, `sort_dir`, `page`, `size`.

`GET /` leaves the reserved `PLATFORM` organisation out, and `total_items` with it, so a client sees
only tenants and no short page (#160). `include_platform=true` lists it; reading it by id
(`GET /{tenant_id}`) is unaffected. A tenant caller's own search never lists it.

| Method | Path                           | Summary             | Permission                   | Shape         |
|--------|--------------------------------|---------------------|------------------------------|---------------|
| POST   | `/`                            | Create tenant draft | `tenant.create` + `tenant.view` | mutation      |
| GET    | `/`                            | Search tenants      | `tenant.view`                | page          |
| GET    | `/{tenant_id}`                 | Get tenant          | `tenant.view`                | item          |
| PATCH  | `/{tenant_id}`                 | Amend tenant draft  | `tenant.update_draft` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/submit`          | Submit tenant draft | `tenant.submit_for_approval` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/approve`         | Approve tenant      | `tenant.approve` + `tenant.view` | mutation, 202 |
| POST   | `/{tenant_id}/reject`          | Reject tenant draft | `tenant.reject` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/return`          | Return to draft     | `tenant.reject` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/suspend`         | Suspend tenant      | `tenant.suspend` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/reactivate`      | Reactivate tenant   | `tenant.reactivate` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/deprovision`     | Deprovision tenant  | `tenant.deprovision` + `tenant.view` | mutation      |
| POST   | `/{tenant_id}/bootstrap/retry` | Retry bootstrap     | `tenant.bootstrap_retry` + `tenant.view` | mutation      |

Every route here authorises in `OrganisationProvisioningService`, inside the mutation
transaction and before any existence signal: the mutation code with `tenant.view` in the platform
organisation (a named `403`, `Missing permission: tenant.view.`), then the platform-organisation
refusal below, then the tenant (`404`) and the state (the ADR 0028 window does not apply to tenant
decisions). The `@PreAuthorize` gate stays the coarse one. Every `{tenant_id}` mutation answers
with the same gated detail `GET /{tenant_id}` returns, `bootstrap_status` and
`bootstrap_failure_code` included, read at `tenant.view`; create answers its own draft result.

Every `{tenant_id}` mutation above (`PATCH`, `submit`, `approve`, `reject`, `return`, `suspend`,
`reactivate`, `deprovision`, `bootstrap/retry`) answers
**`409` `lifecycle.platform_organisation_protected`** when `{tenant_id}` is the reserved platform
organisation (`00000000-0000-0000-0000-000000000000`), with the detail "The platform organisation
cannot be suspended, deprovisioned or otherwise changed through the tenant lifecycle.", and changes
nothing; the refused attempt is recorded as a `DENIED` audit event. It follows the permission
check (`403`) and precedes any existence or state check; the platform branch and user routes
under a tenant still answer `404` for it. See
[the platform organisation is never a tenant](../security/authorization-model.md#the-platform-organisation-is-never-a-tenant).

`POST /` (create draft) and `PATCH /{tenant_id}` (amend draft) answer **`409 conflict`** with the
detail "A tenant with that tenant code already exists." when `tenant_code` is held by another
organisation, and change nothing: no new row on create, the draft untouched on amend (an amend that
keeps the draft's own code is not a clash). The lookup is a courtesy; two concurrent requests that
both pass it get the same `409` from the database's unique index on the loser (#211; both were a
`500`).

`suspend`, `reactivate` and `deprovision` on a tenant id that does not exist are
`404 resource_not_found` (#207); `suspend` used to be a `500`, the other two a `409`. The permission
check runs first, so a caller without the route's permission gets `403` for any id, known or not,
and the reserved platform organisation id still answers `409` as above.

`POST /{tenant_id}/approve` takes an optional decision remark, see
[Decision remarks](#decision-remarks). `POST /{tenant_id}/return` takes a required reason, see
[Return tenant for changes](#return-tenant-for-changes).

**Who may decide a pending tenant (#221).** `approve`, `reject` and `return` are checker
decisions, and the tenant mirrors the branch rule
([Approving a branch](#approving-a-branch-activate)). After the permission, the platform
organisation refusal and the tenant lock, each refuses, before any state check and changing
nothing:

- `403 lifecycle.approver_is_tenant_maker` ("The checker cannot be the tenant's requester or
  submitter.") when the caller **requested** the draft or **submitted** the current submission,
  on all three routes;
- `403 lifecycle.approver_is_tenant_modifier` ("The checker cannot be someone who amended the
  tenant.") on `approve` and `reject` when the caller **amended** the draft: anyone with a
  successful `organisation.amend_draft` audit event on the tenant, however many returns, amends
  and resubmissions came later. A checker who only *returned* the tenant amended nothing and may
  decide its resubmission; an amender may still `return` it, as a branch amender may, since
  returning approves nothing.

Each refusal writes a `DENIED`, `HIGH` audit row (action `organisation.approve`,
`organisation.reject` or `organisation.return_for_changes`, reason the code) in its own
transaction, so the request's rollback does not take it. Until #221 a maker was refused with the
generic `403 forbidden`, and `reject` had no maker or amender rule at all.

`base_currency_code` is the tenant's functional currency for every future journal line, so create
and amend validate it against the ledger's own currency authority and not only against its shape:
beyond the `^[A-Z]{3}$` pattern the request body enforces as a `400`, it must be an ISO 4217 code
the JDK knows **and** must have a minor unit, which refuses `XXX` and the metals `XAU`, `XAG`,
`XPD` and `XPT`. A rejection is `422` with code `accounting.currency_invalid` - deliberately
accounting's own code, so provisioning and a posting refuse the same string identically.

A posting refuses these codes too, but not always under the same code: an unknown code such as
`ZZZ` fails as `accounting.currency_invalid`, while `XXX` and the metals are known to the JDK and
instead fail per-amount as `accounting.amount_precision_exceeded`, because no amount can be
expressed at a minor unit they do not have. Refusing them at provisioning is what stops a tenant
reaching that state at all.

`initial_settings` is the same tenant-setting surface as `PUT /api/v1/tenant/settings/{key}`, and
answers to the same catalogue: each key must be one the catalogue defines, each value must satisfy
that key's type rule, and each value is stored in the catalogue's canonical form under its declared
`value_type`, except a `base_currency` entry, which is checked and not stored (below). An unknown
key or an invalid value is `422`, with the key's own error code - so
`{"base_currency": "XAU"}` is refused here with `accounting.currency_invalid`, the code a posting
gives the same string. A `base_currency` entry must also equal `base_currency_code` once
canonicalised (`" kes "` matches `KES`); a different currency is `422 invalid_operation`, because
the draft's own column is the one the ledger reads and no second currency can be chosen. An equal
entry is accepted and not stored, because the column carries it. This is a
create input only: an amend refuses a non-empty `initial_settings` with `422 invalid_operation`
(see [Amend](#amend-tenant-draft)), and tenant settings after creation go through
`PUT /api/v1/tenant/settings/{key}`, where `base_currency` is a read-only mirror and is refused.

`legal_name` and `registration_number` have **presence semantics** on the amend route
(`PATCH /{tenant_id}`): an absent key leaves the stored value unchanged, an explicit JSON `null`
clears it, and a string sets it. `legal_name` is at most 100 characters and `registration_number`
at most 50; a longer value is `400 validation_failed` and changes nothing. Create takes them as
before. An amend that omits both keeps a tenant's legal identity, so a maker who corrects only the
display name does not erase it.

<a id="amend-tenant-draft"></a>

**Amend (`PATCH /{tenant_id}`).** An amend does not need an administrator. Its request is the
tenant's draft fields, and the rules for the optional parts are:

- `admin` is **optional**. Absent (or an explicit `null`) leaves the stored administrator draft
  (email, username, display name, phone and the invite flag) exactly as it is. When sent, it is a
  **full replace** of that draft, and `send_application_invite` must be stated: omitting it inside
  `admin` is `400 validation_failed`, so an omitted flag can never reset a stored `true` to `false`.
- `initial_settings` is refused with `422 invalid_operation` when it holds any key. An empty `{}` is
  accepted and changes nothing.
- `business_date` is applied when it holds a value (#271): it sets the date the tenant opens on,
  creating the row when the draft has none. Absent or an explicit `null` changes nothing. A draft
  that never names a date has **none** until approval, which derives it from the timezone the tenant
  then has, so a timezone amended on the draft needs no business date. A date named on create or
  amend is kept as stated. Drafts created before this change were seeded a derived date at create;
  amend them with `business_date` if their timezone changed.

A refused amend writes nothing: the draft, its administrator and its settings stay as they were.
Amend without `admin`:

```json
{
  "tenant_code": "acme-corp",
  "display_name": "Acme Financial Services (renamed)",
  "country_code": "KE",
  "base_currency_code": "KES",
  "timezone": "Africa/Nairobi"
}
```

Tenant draft request and response:

```json
{
  "tenant_code": "acme-corp",
  "display_name": "Acme Financial Services",
  "legal_name": "Acme Financial Services Limited",
  "registration_number": "REG-123456",
  "country_code": "KE",
  "base_currency_code": "KES",
  "timezone": "Africa/Nairobi",
  "admin": {
    "email": "admin@acme.test",
    "username": "admin",
    "display_name": "Initial Administrator",
    "phone_e164": "+254700000000",
    "send_application_invite": true
  },
  "initial_settings": {
    "base_currency": "KES"
  },
  "business_date": "25-07-2026"
}
```

```json
{
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "status": "DRAFT"
}
```

Tenant detail response:

```json
{
  "id": "11111111-1111-7111-8111-111111111111",
  "tenant_code": "acme-corp",
  "display_name": "Acme Financial Services",
  "legal_name": "Acme Financial Services Limited",
  "registration_number": "REG-123456",
  "country_code": "KE",
  "base_currency_code": "KES",
  "timezone": "Africa/Nairobi",
  "status": "ACTIVE",
  "status_reason": "KYC pack reviewed.",
  "bootstrap_status": "COMPLETED",
  "bootstrap_failure_code": null,
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:20:00Z",
  "admin": null
}
```

`admin` is the initial administrator as drafted (`email`, `username`, `display_name`, `phone_e164`,
`send_application_invite`: the fields `PATCH` takes), so an amend form can be pre-filled (#271). It
is personal data and is **always present, `null` unless all of these hold**: the route is a platform
route (never `GET /api/v1/tenant`), the tenant is a `DRAFT`, and the caller holds
`tenant.update_draft` in the platform organisation. A viewer with `tenant.view` alone, a tenant
caller, and any detail of a submitted, active or later tenant see `null`, and no permission is named.

`status_reason` is the reason recorded by the tenant's **last transition**, so a maker can read why
a tenant was returned to draft. It is **always present** and nullable: `null` when the last
transition recorded none, never omitted. An amend keeps it and the next transition (the
resubmission) replaces it, while the transition log and the audit trail keep the history. Every
route that answers a `TenantDetailResponse` carries it: the platform tenant detail, the tenant
detail that each platform mutation other than create returns, and the tenant's own
`GET /api/v1/tenant`, so the maker of a returned tenant and the tenant itself read the same value.
Its OpenAPI schema is a required, nullable string.

`legal_name` and `registration_number` are exposed on the same detail responses (the platform
tenant detail and `GET /api/v1/tenant`) as additive, nullable strings. Like `status_reason` they are
**always present**: `null` when no value is recorded, never omitted. The administrator's email and
phone are not exposed on any tenant response.

#### Return tenant for changes

`POST /api/v1/platform/tenants/{tenant_id}/return` sends a tenant that is `PENDING_APPROVAL` back
to `DRAFT` ([ADR 0029](../adr/0029-approval-model-per-resource-extensions.md), issue #181) and
answers `200` with the tenant detail (`status` `DRAFT`, `status_reason` the reason). The body is
required:

```json
{ "reason": "Registration number has a typo." }
```

`reason` is 3 to 500 characters and not blank. A missing or unreadable body, or an absent or `null`
`reason`, is `400 invalid_json`; a blank or out-of-range `reason` is `400 validation_failed`. There
is no `422`. Validation runs when the body is bound, before any permission or existence check.

- **Permission.** `tenant.reject`, the existing permission for the checker's non-approving
  decision, together with `tenant.view`, checked in the platform organisation by the application
  service. No new code.
- **Checker only.** The caller must be neither the tenant's requester nor the actor of its current
  submission (the rule `approve` and `reject` apply, `403 lifecycle.approver_is_tenant_maker`), and
  not the system actor. An amender may return a tenant. A maker cannot withdraw a tenant through
  this route: a tenant has no maker-side withdraw, a maker amends a `DRAFT` only, and since #221 a
  maker can no longer reject their own submission either.
- **Check order.** The permission (`403`, before any existence signal); the platform organisation
  (`409` `lifecycle.platform_organisation_protected`, before any lock or read); the tenant (`404`
  for an unknown id); the maker-checker rule (`403 lifecycle.approver_is_tenant_maker`); the state
  (`409`).
- **State.** Only `PENDING_APPROVAL` can be returned; any other state, including `REJECTED`, answers
  `409 conflict` and changes nothing. `reject` is unchanged and stays terminal, and the tenant
  code of a rejected tenant stays reserved (#172, ADR 0037): `reject` is the deliberate "start
  over" and `return` the recoverable path, so a rejected tenant's name is reused only under a new
  code.
- **Bootstrap record.** The initial-administrator record becomes a draft again, as `reject` leaves
  it: status `DRAFT`, `submitted_by`/`submitted_at` and `approved_by`/`approved_at` cleared,
  `requested_by` and the administrator block untouched, in the same transaction.
- **Amend and resubmit.** A returned tenant is a `DRAFT`: `PATCH /{tenant_id}` amends it, and
  replaces the administrator block when `admin` is sent (optional on amend, see
  [Amend](#amend-tenant-draft)), and `POST /{tenant_id}/submit` resubmits it; `submit` is
  unchanged, so the submitter of the new request is whoever resubmits. The maker-checker rule and
  the `lifecycle.approver_is_initial_administrator` refusal are read from the record at approval,
  so they hold across the loop: the requester and the new submitter cannot approve, and if the
  amended administrator is the account of whoever approves, that approval is refused. Anyone who
  amended the draft in any loop can neither approve nor reject it
  (`403 lifecycle.approver_is_tenant_modifier`, #221). The checker who only returned a tenant is
  not a maker and may approve a later resubmission; so may the earlier submitter, who is not the
  submitter of the current request (an accepted consequence of reading the rule from the record at
  approval). A returned tenant cannot be
  approved until it is resubmitted (`409`). Approve, return, reject, submit and amend each lock the
  tenant row before they read the record or the state, so a decision racing another is judged
  against whatever the other committed (ADR 0029, "Locking rule").
- **System actor.** The system actor is refused with `422 invalid_operation`; this is effectively
  unreachable, because the permission check runs first and a sentinel actor holds no platform grant.
- **Events.** None. The transition publishes an internal event only; consumers of
  `finaxis.lifecycle.organisation.approval-requested` see a repeat per resubmission with no event
  for the return in between (see
  [transactional outbox](../architecture/transactional-outbox-amqp.md)).
- **Audit.** The FSM writes `organisation.return_for_changes` with the reason.
- **Response and idempotency.** The caller needs `tenant.view` in the platform organisation in
  addition to `tenant.reject` (the named 403 above); a role holding only `tenant.reject` is refused
  before anything changes. The route accepts an optional
  `Idempotency-Key`; a replay returns the stored response and returns the tenant once.

### Platform Tenant Branches

Base path: `/api/v1/platform/tenants/{tenant_id}/branches`. List filters: `q`, `status`,
`type`, `sort_by`, `sort_dir`, `page`, `size`.

| Method | Path                    | Summary                                            | Permission        | Shape    |
|--------|-------------------------|----------------------------------------------------|-------------------|----------|
| GET    | `/`                     | Search branches for a platform-selected tenant     | `branch.view`     | page     |
| POST   | `/`                     | Create branch draft for a platform-selected tenant | `branch.create` + `branch.view` | mutation |
| GET    | `/{branch_id}`          | Get tenant branch                                  | `branch.view`     | item     |
| POST   | `/{branch_id}/submit`   | Submit a tenant branch draft for approval          | `branch.create` + `branch.view` | mutation |
| POST   | `/{branch_id}/activate` | Activate a tenant branch as platform checker       | `branch.approve` + `branch.view` | mutation |
| POST   | `/{branch_id}/return`   | Return a pending tenant branch to draft, or withdraw it | `branch.approve` (checker) or `branch.create` (maker) + `branch.view` | mutation |

The create request and branch responses use the same fields as tenant-facing branches. Every
permission above is checked in the **platform** organisation only: no tenant membership or tenant
role is needed, and a draft can be created while the tenant is `PROVISIONING` or `ACTIVE`
(404 for no such tenant, 409 for any other state). Submit and activate return the branch and
require the path tenant to own `branch_id` (404 otherwise; the platform organisation is never a
valid `tenant_id`). The optional body of submit and activate is validated (`reason` at most 500
characters, otherwise 400 `validation_failed`). The caller needs the mutation permission **and
`branch.view`**, both in the platform organisation (the named 403 above). Submit needs an `ACTIVE`
or `PROVISIONING` tenant (409 otherwise); activation needs an `ACTIVE` tenant and a platform actor
that neither created, submitted nor amended the
branch (403; `lifecycle.approver_is_branch_modifier` for an amender; each refusal is audited
`DENIED` as on the tenant route, see [Approving a branch](#approving-a-branch-activate)).
Submit and activate are also bounded: **409 `lifecycle.platform_checker_closed`** once the tenant
has an `ACTIVE` branch that the system actor did not create (the bootstrap head office does not
count). See
[Platform checker while a tenant has no approvers of its own](#platform-checker-while-a-tenant-has-no-approvers-of-its-own).

`return` takes the required `{"reason": "..."}` body and behaves as the tenant route
([Return or withdraw a pending branch](#return-or-withdraw-a-pending-branch)), with the permission
checked **in the platform organisation**: a platform actor who is neither the creator nor the
latest submitter **returns as the audited platform checker** (`branch.approve`) and is bounded
exactly like activation (**409 `lifecycle.platform_checker_closed`** once the tenant has its own
`ACTIVE` branch); one who is the creator or latest submitter **withdraws** (`branch.create`), which
is not bounded, because taking back one's own request grants nothing. A platform withdrawal writes
`branch.withdraw` with no `checkerScope`; only a return as a checker writes
`branch.return_for_changes_as_platform_checker` with `checkerScope = PLATFORM`. The check order is
the permission, then `404` for the platform organisation as `tenant_id`, then `404` for a branch
outside the path tenant, then (checker only) the window, then the tenant state (`ACTIVE` or
`PROVISIONING`, else `409`), then the branch state (`409`).

Platform branch submit, activate and return answer with the branch detail produced by the same
gated read as `GET /platform/tenants/{tenant_id}/branches/{branch_id}` (`branch.view` in the
platform organisation, bound to the path tenant), so there is no permission-free read-back.

### Platform Tenant Memberships

Base path: `/api/v1/platform/tenants/{tenant_id}/memberships`.

| Method | Path                        | Summary                                         | Permission     | Shape    |
|--------|-----------------------------|-------------------------------------------------|----------------|----------|
| POST   | `/{membership_id}/activate` | Approve a tenant membership as platform checker | `user.approve` + `membership.view` | mutation |

Takes an optional decision remark body (see [Decision remarks](#decision-remarks)) and returns the
membership as `GET /api/v1/tenant/memberships/{membership_id}` does, read back through the same
gated query (`membership.view` in the platform organisation, bound to the path tenant): **200**
when the membership became `ACTIVE`, **202** while Keycloak provisioning is queued.
The permission is checked in the platform organisation, together with `membership.view` in the
same organisation (the named 403 above): `user.approve` alone is refused. `404` when the
membership is not in the path tenant, `409` when the tenant is not `ACTIVE`, the membership is not
pending approval, the
user has no active branch or role assignment yet or the user account is not in a state that allows
provisioning or activation (the same prerequisites as the tenant route, see
[Memberships](#memberships)), or the tenant already has an `ACTIVE` membership the system actor did
not create (the bootstrap administrator does not count; code
`lifecycle.platform_checker_closed`), and
`403` when the platform actor invited the membership (`lifecycle.approver_is_membership_maker`) or
is the invited user (`lifecycle.approver_is_membership_beneficiary`): the checker is neither the
maker nor the beneficiary.

### Platform Tenant Users

Base path: `/api/v1/platform/tenants/{tenant_id}/users`. List filters: `q`, `user_status`,
`membership_status`, `page`, `size`.

| Method | Path         | Summary                                    | Permission  | Shape |
|--------|--------------|--------------------------------------------|-------------|-------|
| GET    | `/`          | Search users in a platform-selected tenant | `user.view` | page  |
| GET    | `/{user_id}` | Get user in a platform-selected tenant     | `user.view` | item  |

User response:

```json
{
  "id": "44444444-4444-7444-8444-444444444444",
  "username": "admin",
  "email": "admin@acme.test",
  "display_name": "Initial Administrator",
  "user_status": "ACTIVE",
  "membership_status": "ACTIVE"
}
```

### Platform Global Users

Base path: `/api/v1/platform/users`. List filters: `q`, `user_status`, `organisation_id`,
`sort_by`, `sort_dir`, `page`, `size`.

| Method | Path                    | Summary                        | Permission        | Shape    |
|--------|-------------------------|--------------------------------|-------------------|----------|
| GET    | `/`                     | Search user accounts, all organisations | `user.view` (platform) | page |
| GET    | `/{user_id}`            | Get a user account and its memberships  | `user.view` (platform) | item |
| PATCH  | `/{user_id}`            | Edit display name and phone    | `user.update` + `user.view` | mutation |
| POST   | `/{user_id}/suspend`    | Suspend global user account    | `user.suspend` + `user.view` | mutation |
| POST   | `/{user_id}/reactivate` | Reactivate global user account | `user.activate` + `user.view` | mutation |
| POST   | `/{user_id}/deactivate` | Deactivate global user account | `user.deactivate` + `user.view` | mutation |

**Profile edit (#161).** `PATCH /api/v1/platform/users/{user_id}` takes `display_name` (2 to 100
characters after trimming; the trimmed value is stored) and `phone_e164` (E.164); a `DEACTIVATED`
or `ARCHIVED` account is not edited (`422`); an absent key leaves the field as it is, a phone is replaced
and never cleared, and at least one field is required (`422`). It needs `user.update` and `user.view`
in the platform organisation (`user.update` without `user.view` is `403` naming `user.view`), is
idempotent, answers the directory detail, and is audited as `user.update` naming the changed fields
and never their values. The directory detail returns the platform-only `phone_e164` and
`last_login_at` (both always present, nullable; no tenant route returns them). An invitation for an
email that already has an account keeps ignoring the phone, username and display name it carries:
the account's profile is edited here, not by inviting (ADR 0033).

**Directory reads (#160).** `GET /` pages user accounts across every organisation, newest first by
default (`sort_by`: `createdAt`, `username`, `displayName`, `email`, `userStatus`; `sort_dir` applies
only with a `sort_by`). `q` matches username, email or display name; `organisation_id` narrows to
accounts holding a membership there, in any status. Each item is `id`, `username`, `email`,
`display_name`, `user_status`, `created_at` and `membership_count` (every membership, any
organisation and status); the page costs a constant number of statements. `GET /{user_id}` adds
`memberships` (`membership_id`, `organisation_id`, `tenant_code`, `organisation_name`,
`membership_status`, `membership_type`, `created_at`), newest first and capped at 100, while
`membership_count` stays the full count. Both need `user.view` in the platform organisation, checked
first in the service (403 before `sort_by` is judged or any row is read, and never 404 for an
unknown id; a malformed `page` or `size` is `400` for any authenticated caller, as on every list,
because the pagination interceptor runs first); an unknown account
is `404`. Email is personal data, so the code is the same one that gates the per-tenant user reads.
Rate limiting is the platform read policy, by path prefix.

Lifecycle request and response:

```json
{
  "reason": "Compromised account review."
}
```

```json
{
  "user_id": "44444444-4444-7444-8444-444444444444",
  "status": "SUSPENDED"
}
```

`suspend` and `deactivate` require the `reason` (3 to 500 characters). The `reactivate` body is
**optional**, like the other reactivate routes: an absent body, `{}` and a valid `reason` all
succeed, and a `reason` over 500 characters is `400 validation_failed` (#207; a body-less call
used to be `400 invalid_json`).

An unknown `user_id` is `404 resource_not_found` on all three routes (#207; it used to be a
`500`). The permission check runs first, inside the application service. A caller whose token
lacks the route's permission is refused by the endpoint's `@PreAuthorize` gate with the unnamed
`403`; a caller who holds the route's permission but not `user.view` in the platform organisation
gets `403` naming it (`Missing permission: user.view.`) for any id, known or not, and nothing is
written. The `status` in the response is read back from the user account through the same gated
query (`user.view`, platform organisation), so it is the stored status, and it needs no platform
membership of the target.

### Current Tenant

Base path: `/api/v1/tenant`.

| Method | Path | Summary                                | Permission    | Shape |
|--------|------|----------------------------------------|---------------|-------|
| GET    | `/`  | Get current tenant from active context | `tenant.view` | item  |

The response uses `TenantDetailResponse`, including `bootstrap_status` and
`bootstrap_failure_code` (a closed code, see
[Maker-Checker Tenant Onboarding](#maker-checker-tenant-onboarding)), and the always-present,
nullable `status_reason` of the tenant's last transition (see
[Platform Tenant Administration](#platform-tenant-administration)).

### Tenant Users

Base path: `/api/v1/tenant/users`. List filters: `q`, `user_status`, `membership_status`,
`sort_by`, `sort_dir`, `page`, `size`. Sorting is described under [Pagination](#pagination).

| Method | Path         | Summary                           | Permission    | Shape    |
|--------|--------------|-----------------------------------|---------------|----------|
| GET    | `/`          | Search users in the active tenant | `user.view`   | page     |
| POST   | `/`          | Invite tenant user                | `user.invite` + `membership.view` + `user.view` | mutation |
| GET    | `/{user_id}` | Get tenant user                   | `user.view`   | item     |

Invite request and response:

```json
{
  "email": "member@acme.test",
  "username": "member01",
  "display_name": "Member One",
  "phone_e164": "+254711000000",
  "membership_type": "STAFF",
  "primary_branch_id": "33333333-3333-7333-8333-333333333333",
  "branch_assignments": [
    {
      "branch_id": "33333333-3333-7333-8333-333333333333",
      "assignment_type": "HOME"
    }
  ],
  "role_assignments": [
    {
      "role_id": "55555555-5555-7555-8555-555555555555",
      "scope_type": "TENANT",
      "branch_id": null
    }
  ],
  "send_keycloak_invite": true,
  "send_application_invite": false
}
```

```json
{
  "user_id": "66666666-6666-7666-8666-666666666666",
  "membership_id": "77777777-7777-7777-8777-777777777777",
  "user_status": "DRAFT",
  "membership_status": "PENDING_APPROVAL"
}
```

A `role_assignments[]` entry with `scope_type` `BRANCH` must carry a `branch_id`; without one the
invite is `400 validation_failed` with the violation attributed to
`role_assignments[n].branch_id`, and nothing is created (#207; it used to be a `500`). A `TENANT`
entry that names a `branch_id` is `422 invalid_operation` (also formerly a `500`). See
[Role Assignments](#role-assignments).

Every other client mistake in the invite is refused with a `4xx` problem (`code`, `detail`,
`request_id`), and **atomically**: a refused invitation leaves no user, membership, branch
assignment, role assignment or audit row behind (#211; each used to be a `500`). Permission checks
still run first, so a caller without `user.invite` gets `403` for a well-formed body (a body that
fails Bean Validation is a `400` before any permission check, as on every route).

- **`409 conflict`**: the user (matched by email, ignoring case) already has a membership in the
  organisation in any state but `REVOKED` ("This user already has a membership in the selected
  organisation."; a revoked member is re-invited, below); the `username` is already held by another
  user
  (usernames are global and case-insensitive); or the organisation is not `ACTIVE`. The email and
  username lookups are courtesies that give the specific message; two concurrent invitations for
  one new email or username can both pass them, and the loser, refused by the database's unique
  index, gets the same `409` with "A user with this email or username already exists; retry the
  invitation."
- **`422 invalid_operation`**: a `role_id` is unknown, belongs to another organisation or is not
  `ACTIVE`; a `branch_id` (`primary_branch_id`, a branch assignment or a branch-scoped role) is
  unknown, belongs to another organisation or is not `ACTIVE`; or a `TENANT` role names a branch.
  The detail never echoes the offending value.
- **`400 validation_failed`**: an email the service refuses though the DTO accepts it (for example
  `user@localhost`), no branch assignment for a member type that needs one, or no role assignment.

**Re-inviting a revoked member (ADR 0037, #172).** The same route and permission invite a user whose
membership in the organisation is `REVOKED`: the response is the same `201`, `membership_id` is the
revoked membership's id (no second membership is made), and `membership_status` is
`PENDING_APPROVAL` again. The membership type, primary branch, preferences and assignments come from
the new request, the caller becomes the maker (`created_by`), the membership's dates and
`status_reason` are cleared and its direct permission overrides are deleted; the first life's
assignments stay as ended history. A checker other than the re-inviter approves it again
(`lifecycle.approver_is_membership_maker` for the re-inviter, as for any invitation), and the
approval sends a fresh invitation: the dispatch keys, and so the background job ids, carry the
membership's generation (`:G<n>` from the first re-invitation; a membership never re-invited keeps
the keys it always had). The audit row is the transition's own, `membership.reinvite`, not
`user.invite`. A membership in any other state is still the `409` above.

### Memberships

Base path: `/api/v1/tenant/memberships`. List filters: `q`, `membership_status`,
`membership_type`, `user_id`, `sort_by`, `sort_dir`, `page`, `size`. `user_id` narrows the list to
that user's membership in the active organisation (a malformed UUID is `400`; a user with no
membership here is an empty page). Sorting is described under [Pagination](#pagination).

| Method | Path                          | Summary                         | Permission              | Shape    |
|--------|-------------------------------|---------------------------------|-------------------------|----------|
| GET    | `/`                           | Search tenant memberships       | `membership.view`       | page     |
| GET    | `/{membership_id}`            | Get membership                  | `membership.view`       | item     |
| POST   | `/{membership_id}/activate`   | Approve and activate membership | `user.approve` + `membership.view` | mutation |
| POST   | `/{membership_id}/suspend`    | Suspend membership              | `membership.suspend` + `membership.view` | mutation |
| POST   | `/{membership_id}/reactivate` | Reactivate                      | `membership.reactivate` + `membership.view` | mutation |
| POST   | `/{membership_id}/revoke`     | Revoke membership               | `membership.revoke` + `membership.view` | mutation |
| PATCH  | `/{membership_id}`            | Edit type and primary branch    | `membership.update` + `membership.view` | mutation |

**Edit (`PATCH /{membership_id}`, #161).** The body takes `membership_type` (`STAFF`, `ADMIN` or
`AUDITOR`) and `primary_branch_id`; an absent key leaves the field as it is, an explicit
`primary_branch_id: null` clears it, and at least one field is required (else `422`). A revoked or
`SYSTEM` membership, a `SYSTEM` type and a branch that is not an ACTIVE branch of the tenant are
`422 invalid_operation`; an ACTIVE membership cannot move to `STAFF` or `ADMIN` while the user holds
no active branch assignment (`409`, as activation requires one; an `AUDITOR` may have none); an
unknown membership is `404`. Idempotent, answers the membership detail,
and is audited as `membership.update` naming the changed fields (ADR 0033).

**Invitation status and recovery (#274).** A membership detail carries `invitation`: `status`
(`AWAITING_APPROVAL`, `IN_PROGRESS`, `FAILED`, `COMPLETED` or `NOT_REQUIRED`), `attempts`
(provisioning attempts plus resends), `last_error_code` and `updated_at`. It is derived from the
membership and its Keycloak provisioning dispatch, not a new lifecycle state (the user and
membership state machines are unchanged): `FAILED` is a membership still awaiting approval whose
user waits on provisioning and whose last attempt failed (a failed dispatch behind an `ACTIVE` or
`SUSPENDED` member is stale and reads `COMPLETED`). The membership and its dispatch are read by
one statement. `last_error_code` is the closed failure
code (`IDENTITY_PROVIDER_FAILED`, `CONFLICT`, `NOT_FOUND`, `INVALID_STATE`, `DATABASE_ERROR`,
`UNEXPECTED`, `CANCELLED` for a job ended because its membership was revoked, or `UNKNOWN` for a
failure recorded before codes existed) and only while `FAILED`:
never the failure's text. It is `null` on the answer of a mutation that does not read it.

| Method | Path                                  | Summary                       | Permission                           | Shape    |
|--------|---------------------------------------|-------------------------------|--------------------------------------|----------|
| POST   | `/{membership_id}/invitation/retry`   | Run failed provisioning again | `user.approve` + `membership.view`   | mutation |
| POST   | `/{membership_id}/invitation/resend`  | Send the invitation email again | `user.invite` + `membership.view` + `user.view` | mutation |

`retry` runs the failed job again under its own id (a job still scheduled or running is left
alone, a job that is gone is queued again). It is no approval: it is `409` unless the membership is
awaiting approval with provisioning pending and a recorded dispatch that did not succeed, and it
changes no state of its own. `resend` applies to an account that exists in Keycloak and has not
signed in (`INVITED`), whose membership here is `ACTIVE` and which has a succeeded provisioning
dispatch in any organisation (the account is global: a tenant that activated an
already-provisioned user has none of its own), else
`409 lifecycle.invitation_resend_not_applicable` (a revoked or suspended member is never mailed). It
is rate limited per account, a second resend inside the cooldown
(`finaxis.invitation.resend-cooldown`, 5 minutes) being `409 lifecycle.invitation_resend_too_soon`,
and each resend is one durable job that carries no personal data: it emails the account's linked
Keycloak subject, only if the account is still `INVITED` when it runs, and fails for reconciliation
rather than creating an identity when no subject is linked. Both answer the
membership detail, are idempotent, and are audited (`user.invitation_retry`,
`user.invitation_resend`). A membership revoked while its job waited ends the job without calling
Keycloak, audited as a failed `user.keycloak_provisioning`, and shows `FAILED` with `last_error_code`
`CANCELLED`, unless the dispatch had already
succeeded, which a redelivered job leaves as it is.

Membership response and revoke request:

```json
{
  "id": "77777777-7777-7777-8777-777777777777",
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "user_id": "66666666-6666-7666-8666-666666666666",
  "username": "member01",
  "email": "member@acme.test",
  "display_name": "Member One",
  "user_status": "ACTIVE",
  "membership_status": "ACTIVE",
  "membership_type": "STAFF",
  "primary_branch_id": "33333333-3333-7333-8333-333333333333",
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:10:00Z"
}
```

```json
{
  "reason": "User left the organisation."
}
```

Activating (approving) a membership answers `409 conflict`, and changes nothing, when the
membership is not `PENDING_APPROVAL`; when the user has no active branch assignment (member types
`SYSTEM` and `AUDITOR` are exempt) or no active role assignment yet; or when the user account is not
in a state that allows identity provisioning (a user without a linked identity must be `DRAFT` or
`PENDING_APPROVAL`) or the membership activation (a user with a linked identity must be `ACTIVE` or
`INVITED`), except a `PROVISIONING_IDP` user whose dispatch is unfinished, which answers `202` (see
Repeating a 202 approval). The detail names the missing prerequisite, for example `The membership cannot be
approved until the user has an active role assignment.` These were `500`s before #211. The
permission check (`403`) and the unknown-membership check (`404`) still come first.

Revoking a membership that is already `REVOKED` is `409 conflict` with the detail
`This membership has already been revoked.`, and changes nothing: no state change, no transition
row, no audit row (#207; it used to be a `500`). A second revoke under a new `Idempotency-Key` is
refused rather than repeated; replaying the original key still returns the original response. A
revoked member is not lost for good: the invite route re-invites them on the same membership (ADR
0037, see [Re-inviting a revoked member](#tenant-users)).

#### Decision remarks

**Maker-checker refusals on a membership (#156).** `POST .../memberships/{id}/activate`, on the
tenant and the platform route, refuses with `403 lifecycle.approver_is_membership_maker` ("The
approver cannot be the member's inviter.") or `403 lifecycle.approver_is_membership_beneficiary`
("The approver cannot be the invited user."); a missing permission is still `403 forbidden`. Each
refusal writes a `DENIED`, `HIGH` audit row (action `user.approve`, the invited user as subject,
the code as reason) in its own transaction, so the request's rollback does not take it; a refusal
before the rule (permission, 404) writes none. An audit search on `action=user.approve` therefore
also returns refused attempts; add `outcome=SUCCESS` for approvals only.

Three approvals accept an **optional** JSON body carrying a remark. The body itself is optional and
so is the field, so a call without one behaves exactly as before:

| Route | Permission |
|-------|------------|
| `POST /api/v1/tenant/memberships/{membership_id}/activate` | `user.approve` + `membership.view` |
| `POST /api/v1/platform/tenants/{tenant_id}/memberships/{membership_id}/activate` | `user.approve` + `membership.view` |
| `POST /api/v1/platform/tenants/{tenant_id}/approve` | `tenant.approve` + `tenant.view` |

```json
{
  "reason": "Checked against the signed request form."
}
```

`reason` is at most 500 characters; a longer one is `400` (`validation_failed`) and nothing is
changed. The remark never changes who may approve. Replaying a request with the same
`Idempotency-Key` and body returns the original response; the same key with a different body is
the usual idempotency mismatch.

Where the remark lands depends on the path, because a membership approval does not always activate
the membership itself:

| Route and outcome                                        | `status_reason`                      | Audit `reason`                            |
|----------------------------------------------------------|--------------------------------------|-------------------------------------------|
| Membership activate, **200** (invitee already has an identity) | membership `ACTIVATE` transition | `user.approve` and `membership.activate`  |
| Membership activate, **202** (identity provisioning queued) | none; the membership stays `PENDING_APPROVAL` | `user.approve` only; the later job-driven `membership.activate` row carries no reason |
| Tenant approve, **202**                                  | the organisation and its head office | the `START_PROVISIONING`, head office and organisation `ACTIVATE` transition rows |

**Repeating a 202 approval (#162).** A membership whose user is `PROVISIONING_IDP` because an
earlier approval queued identity provisioning answers the same route again with **`202`** and the
membership detail, never `409` for that state. The repeat recovers a failed provisioning job: a
`FAILED` job is enqueued again under its existing JobRunr id, so the same job runs again; a job
still scheduled for a JobRunr retry, enqueued or processing is left alone, and nothing is published.
A repeat requests no second application invitation, which the first approval already requested. A
user whose provisioning dispatch has already `SUCCEEDED`, or has no dispatch, still answers
**`409 conflict`** and changes nothing. Maker-checker, window and prerequisite checks run as on the
first approval. Each repeat buys one attempt: a requeued job that fails again goes straight to
`FAILED`. A job still `SCHEDULED` for its retry can be hours away (`3^n` seconds after the n-th
failure), and a repeat before then answers `202` and changes nothing. A repeat sent with the same
`Idempotency-Key` as an earlier approval replays that stored `202` and requeues nothing, so send a
fresh key or omit the header. The requeue commits on JobRunr's connection at once, so a later
rollback of the approval can leave the job running without a committed `user.approve` row. See
[Failed Keycloak provisioning](../operations/tenant-provisioning.md#failed-keycloak-provisioning).

The membership detail does not expose a status reason. The platform tenant detail does, as
`status_reason` (see [Platform Tenant Administration](#platform-tenant-administration)), and so
does the branch detail.

#### Reason bounds

Every lifecycle `reason` is validated twice: by Bean Validation on the request body, and again by
the application layer's value objects, so a non-HTTP caller cannot bypass the bound. A rejection
from either layer is `400` (`validation_failed`) and changes nothing: no state change, no
transition row, no audit row. Whether a reason is required is carried by the type: `Reason`
(required, non-null) or `DecisionRemark` (optional, nullable). The text is trimmed before it is
stored or audited. The minimum is measured after trimming; the maximum is checked on the raw
request text by Bean Validation and again after trimming by the value object, so a reason padded
past 500 characters is rejected even if its trimmed text would fit.

- **Required, 3 to 500 characters and not blank (`Reason`):** tenant suspend, return, reject and
  deprovision; branch suspend, close, discard and return (withdraw; return also on the platform
  routes);
  membership suspend and revoke; user suspend and deactivate.
- **Optional, at most 500 characters (`DecisionRemark`):** branch submit, activate and reactivate
  (submit and activate also on the platform routes), tenant approve and reactivate, membership
  activate and reactivate, user reactivate.

An optional `reason` may be omitted: `{}`, `{"reason": null}` and a blank `reason` are all
accepted and record no reason. An absent body is accepted wherever the body itself is optional
(the branch, tenant approve and reactivate, and membership activate and reactivate routes); user
reactivate still requires a body, `{}` being enough. The tenant-setting, business-date and role
reasons are not bounded by this rule.

Behaviour changes (#206). The optional bodies of
`POST /api/v1/branches/{branch_id}/submit`, `.../activate` and `.../reactivate` and of
`POST /api/v1/platform/tenants/{tenant_id}/reactivate` previously skipped Bean Validation, so
the advertised 500 character limit was not enforced:

- a `reason` of 501 to 1000 characters was accepted, stored, echoed and audited (`200`); it is now
  `400 validation_failed`;
- on tenant reactivate a `reason` of 1001 or more characters overflowed
  `organisation.status_reason VARCHAR(1000)` and surfaced as `500`; it is now
  `400 validation_failed`;
- a padded `reason` is now stored and audited trimmed (`"  Audit done "` is recorded as
  `"Audit done"`);
- a blank optional `reason` (`""`, `"   "`) is now recorded as no reason (`NULL`); it used to be
  stored as the blank string;
- the bounds are measured after trimming, so `"  ab  "` on a required route is now
  `400 validation_failed`, where it used to pass the 3 character minimum.

As on every route that validates its body, validation runs before the permission check, so a
caller without the permission who sends an over-long `reason` receives `400`, not `403`. Every
`@RequestBody` DTO that carries constraints must be `@Valid`; the architecture test
`RequestBodyValidationArchitectureTest` enforces it.

### Branch Assignments

Base path: `/api/v1/tenant/branch-assignments`. List filters: `branch_id`,
`assignment_type`, `status`, `user_id`, `sort_by`, `sort_dir`, `page`, `size`. Sorting is
described under [Pagination](#pagination).

| Method | Path               | Summary                   | Permission               | Shape    |
|--------|--------------------|---------------------------|--------------------------|----------|
| GET    | `/`                | Search branch assignments | `branch_assignment.view` | page     |
| GET    | `/{assignment_id}` | Get branch assignment     | `branch_assignment.view` | item     |
| POST   | `/`                | Assign user to branch     | `user.assign_branch` + `branch_assignment.view` | mutation |
| DELETE | `/{assignment_id}` | Revoke branch assignment  | `user.revoke_branch` + `branch_assignment.view` | mutation |

Reads are target-aware (ADR 0030, decision 5) and carry no coarse `@PreAuthorize` gate: the
check is a tenant-wide `branch_assignment.view` **or** that permission on the assignment's branch,
and the application layer is the only authorisation.

- `GET /{assignment_id}`: a tenant-wide holder gets `404` for an unknown id; a branch-scoped holder
  gets `403` for an assignment on another branch and for an unknown id alike (no existence
  oracle); no grant: `403`.
- `GET /`: lists the assignments of every branch the caller holds the view on, restricted in the
  query so pages and totals are exact. An explicit `branch_id` outside that set is `403`; no grant
  is `403`. Without `branch_id`, one rule applies to every tenant caller: the list defaults to the
  selected branch when the caller may view it, and is every viewable branch otherwise (clear the
  selection to list across all). A `user_id` filter switches that default off: the user's
  assignments are listed across every branch the caller may view, and only an explicit `branch_id`
  narrows them (#188).
- A grant scoped to a branch counts only while the branch is `ACTIVE` (issue #242): the
  assignments of a suspended or closed branch are visible to a tenant-wide holder only, and to a
  branch-scoped holder they are absent from the list and `403` by id or by `branch_id`.

- `POST /` reads the assignment it wrote **by its id**, gated like `GET /{assignment_id}` at that
  assignment's branch, so a branch with more than a page of assignments can no longer answer `404`
  after a successful assign.
- **Behaviour change (ADR 0030 step 6c).** `POST /` now authorises at the **target branch**, like
  every branch-targeted mutation: `user.assign_branch` and `branch_assignment.view`, each held
  tenant-wide **or** on that branch. Before, the controller also demanded the tenant-wide
  `user.assign_branch`, so a branch-scoped holder was refused at its own branch; it can now assign
  at **its** branch (and gets a named `403` for any other branch). `DELETE /{assignment_id}` changed
  the same way: a branch-scoped revoker, refused before, now revokes at its own branch.
- `DELETE /{assignment_id}` is an authorised combined lookup, like the role-assignment revoke: the
  service resolves where the caller may revoke (`user.revoke_branch` **and**
  `branch_assignment.view`, tenant-wide or per branch; `403` naming the first code held nowhere),
  then reads the row, never returned to the caller. A tenant-wide holder gets `404` for an unknown
  id. A branch-scoped holder gets the **same** `403` (`Missing permission: user.revoke_branch.`,
  same status and body apart from `request_id` and the echoed `instance` path) for an unknown id
  and for an assignment on a branch it may not act on, so existence is no oracle; at its own
  branch it revokes. A caller with no grant at all gets `403` naming the first missing code.

Assign request and response:

```json
{
  "user_id": "66666666-6666-7666-8666-666666666666",
  "branch_id": "33333333-3333-7333-8333-333333333333",
  "assignment_type": "HOME"
}
```

```json
{
  "id": "88888888-8888-7888-8888-888888888888",
  "user_id": "66666666-6666-7666-8666-666666666666",
  "branch_id": "33333333-3333-7333-8333-333333333333",
  "assignment_type": "HOME",
  "status": "ACTIVE"
}
```

### Roles

Base path: `/api/v1/tenant/roles`. List filters: `q`, `status`, `system_role`,
`sort_by`, `sort_dir`, `page`, `size`. Role permission list filters: `page`, `size`. Without a
`status` filter the list omits `ARCHIVED` roles; `status=ARCHIVED` lists them. A role's `status` is
`ACTIVE`, `DISABLED` or `ARCHIVED` (terminal); every change is one `role_transition_log` row.

Endpoints:

- `GET /`: search roles. Permission `role.view`. Shape: page.
- `POST /`: create role. Permission `role.create` + `role.view`. Shape: mutation.
- `GET /{role_id}`: get role. Permission `role.view`. Shape: item.
- `PATCH /{role_id}`: update role metadata. Permission `role.update` + `role.view`. Shape: mutation.
- `POST /{role_id}/activate`: re-enable a `DISABLED` role. Permission `role.activate` +
  `role.view`. Shape: mutation. Any other status (including a repeat) is 409 `conflict`.
- `POST /{role_id}/deactivate`: disable an `ACTIVE` role. Permission `role.deactivate` +
  `role.view`. Shape: mutation. Any other status (including a repeat) is 409 `conflict`.
- `POST /{role_id}/archive`: archive a role, a **terminal** state (ADR 0035, #297). Permission
  `role.archive` + `role.view`. Shape: mutation, no request body. Allowed from `ACTIVE` or
  `DISABLED`; refused `409 conflict` while the role has any `ACTIVE` assignment (the `detail`
  names how many: "The role has 2 active assignments; revoke them before archiving it"), for a
  system role, and for a role that is already archived. Nothing leaves `ARCHIVED`: activate,
  deactivate and archive are `409` after it, and the role's permissions, name and description are
  not edited (`409`) and it cannot be assigned or invited to (`422`). An archived role is hidden
  from the default list (`GET /` with no `status`), is listed by `status=ARCHIVED` and is still
  read by `GET /{role_id}`. **Its `role_code` stays reserved:** creating a role with an archived
  role's code is `409`, and the message says so. Assignments are not revoked on the caller's
  behalf; revoke them first. Audit action `role.archive` (for both source states, with the source
  in the row's `from` metadata), one row per call.
- `GET /{role_id}/permissions`: list role permissions. Permission `role.view`.
  Shape: page.
- `POST /{role_id}/permissions`: grant role permission. Permission `role.assign_permission` +
  `role.view`. Shape: mutation. Refused with `400 validation_failed` when it grants a mutation
  permission whose required views the role does not hold (see "Role composition" below).
- `DELETE /{role_id}/permissions/{role_permission_id}`: remove role permission grant.
  Permission `role.remove_permission` + `role.view`. Shape: mutation. The permission check is
  first (`403` naming `Missing permission: role.view.` when the view is missing), then the role
  and the grant are looked up (`404`). Refused with `400 validation_failed` when it removes a view
  that a held mutation permission needs.

`POST /{role_id}/permissions` reads the grant it wrote **by role and permission code**, gated by
`role.view`, so a role holding more than a page of grants can no longer answer `404` after a
successful grant.

Create role and assign-permission examples:

```json
{
  "role_code": "LOAN_OFFICER",
  "role_name": "Loan Officer",
  "description": "Can operate loan origination workflows."
}
```

```json
{
  "permission_code": "loan.application.view"
}
```

Role detail response:

```json
{
  "id": "55555555-5555-7555-8555-555555555555",
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "role_code": "LOAN_OFFICER",
  "role_name": "Loan Officer",
  "description": "Can operate loan origination workflows.",
  "system_role": false,
  "status": "ACTIVE",
  "missing_view_permissions": [],
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:10:00Z"
}
```

#### Role composition and `missing_view_permissions`

A role that holds a mutation permission must hold every view permission the catalogue pairs with
it (`required_view_permissions` on [Permissions](#permissions); [ADR
0030](../adr/0030-mutation-permission-implies-view-permission.md)), so the user can see their
writes. The two composition routes enforce it, counting only `ACTIVE` permissions, and take a row
lock on the role first so concurrent requests cannot both commit a violating role:

- **Grant** a mutation whose views are not all held: `400`, `code` `validation_failed`, nothing
  written. The `detail` lists every missing view and the permission that needs it:

```json
{
  "type": "urn:finaxis:problem:validation_failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "Missing view permissions: branch.view (required by branch.suspend).",
  "instance": "/api/v1/tenant/roles/55555555-5555-7555-8555-555555555555/permissions",
  "code": "validation_failed",
  "request_id": "019f7d3b-3fa4-7f91-a0a4-49b038244bd0"
}
```

  The problem carries no `violations` member: the `detail` lists them in a fixed format.

  With several missing views the entries are joined by `; `, one per view and needing mutation.

- **Remove** a view that another held mutation needs: `400`, `code` `validation_failed`, `detail`
  `Permission branch.view is required by held permissions: branch.close, branch.suspend.`
- Granting a view never fails, and granting a **new** mutation fails only on **its own** missing
  views, so a role that already violates the rule can be repaired one permission at a time.
  Re-granting a mutation the role already holds is an idempotent no-op; granting one that is not
  `ACTIVE` (it grants nothing) is written without the check. Neither is ever refused. Removing a
  mutation or a context permission never fails.

Role list items and the role detail carry **`missing_view_permissions`**: the sorted flat list of
view codes the role's held `ACTIVE` mutation permissions need and the role lacks (`[]` when it
complies). It is computed for the roles on the returned page only, so pagination is unaffected.
Activating a role that violates the rule stays allowed; the field is the signal. Memberships carry
no such field: operators find violating memberships (direct overrides) and platform roles, which no
API lists, with [the operator view-gap report](../operations/permission-view-gap-report.md).

### Role Assignments

Base path: `/api/v1/tenant/role-assignments`. List filters: `user_id`, `role_id`,
`branch_id`, `scope_type`, `status`, `page`, `size`.

| Method | Path               | Summary                 | Permission             | Shape    |
|--------|--------------------|-------------------------|------------------------|----------|
| GET    | `/`                | Search role assignments | `role_assignment.view` | page     |
| GET    | `/{assignment_id}` | Get role assignment     | `role_assignment.view` | item     |
| POST   | `/`                | Assign role to user     | `user.assign_role` + `role_assignment.view` | mutation |
| DELETE | `/{assignment_id}` | Revoke role assignment  | `user.revoke_role` + `role_assignment.view` | mutation |

Reads are target-aware (ADR 0030, decision 5) and carry no coarse `@PreAuthorize` gate. Rows with
`scope_type = BRANCH` need a tenant-wide `role_assignment.view` **or** that permission on the
row's branch; `TENANT`-scope rows need the tenant-wide view.

- `GET /{assignment_id}`: a tenant-wide holder gets `404` for an unknown id; a branch-scoped holder
  gets `403` for a row on another branch, for a `TENANT`-scope row and for an unknown id alike; no
  grant: `403`.
- `GET /`: a tenant-wide holder lists every row. A branch-scoped holder lists only `BRANCH`-scope
  rows on the branches it holds the view on, restricted in the query so totals are exact; an
  explicit `branch_id` outside that set is `403`. `scope_type` is matched exactly like every
  enum-like filter, so for such a holder any value other than `BRANCH` (`TENANT`, `tenant`, an
  unknown value) is an empty page. No grant: `403`.
- A grant scoped to a branch counts only while the branch is `ACTIVE` (issue #242): the rows on a
  suspended or closed branch are visible to a tenant-wide holder only. To a branch-scoped holder
  they are absent from the list and `403` by id or by `branch_id`, and the revoke below treats
  them as rows it may not touch.

- `POST /`: the permission is checked before the user, role or branch is looked up. A role that is
  not `ACTIVE` (`DISABLED` or `ARCHIVED`) is `422 invalid_operation` when the assignment would be
  created: it grants nothing, but would start to once it was re-enabled. This matches the
  invitation path. Repeating an assignment that already exists returns it, whatever the role's
  status, so the call stays idempotent. The role row is locked before its status is read (the
  invitation's role check does the same), so an archive that runs at the same time either counts
  this assignment and is refused, or commits first and this call is refused (ADR 0035).

**Revoke is an authorised combined lookup.** `DELETE /{assignment_id}` takes only the assignment
id, and the permission check depends on the assignment's scope, so the service resolves that scope
itself: it first works out, from the caller's grants alone, where the caller may revoke (the
mutation `user.revoke_role` **and** `role_assignment.view`, tenant-wide or per branch; `403` naming
the first code held nowhere), then reads the row, which is never returned to the caller, and
revokes only a row the caller may touch: a `BRANCH`-scope row on a branch where it holds both, or
any row for a tenant-wide holder. Consequences:

- A **tenant-wide** holder gets `404` for an unknown id.
- A **branch-scoped** holder gets the **same** `403` (`Missing permission: user.revoke_role.`,
  same status, same body apart from the per-request `request_id` and the echoed `instance` path)
  for an unknown id, for a row on a branch it may not act on and for a `TENANT`-scope row, so the
  response is no existence oracle.
- A caller with **no grant at all** gets `403` naming the first missing code, whatever id it asks
  for.
- A branch-scoped revoker holding both codes at its own branch revokes a row there and gets the
  gated detail back (`role_assignment.view` at that branch), as before.

Assign role request and response:

```json
{
  "user_id": "66666666-6666-7666-8666-666666666666",
  "role_id": "55555555-5555-7555-8555-555555555555",
  "scope_type": "TENANT",
  "branch_id": null
}
```

```json
{
  "id": "99999999-9999-7999-8999-999999999999",
  "user_id": "66666666-6666-7666-8666-666666666666",
  "role_id": "55555555-5555-7555-8555-555555555555",
  "branch_id": null,
  "scope_type": "TENANT",
  "status": "ACTIVE"
}
```

`branch_id` is required when `scope_type` is `BRANCH` and is checked together with the other body
fields (#207). A `BRANCH` assignment without one is `400 validation_failed`, with the violation
attributed to `branch_id`, and nothing is written (it used to be a `500`):

```json
{
  "type": "urn:finaxis:problem:validation_failed",
  "title": "Bad Request",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/v1/tenant/role-assignments",
  "code": "validation_failed",
  "request_id": "019f7d47-2c3e-7a1b-9d64-5e0f8b7a31c2",
  "violations": [
    {
      "field": "branch_id",
      "code": "BranchScopeRequiresBranch",
      "message": "A branch is required when the scope type is BRANCH."
    }
  ]
}
```

The application layer enforces the same rule for a caller that skipped body validation, with the
same `400 validation_failed` code. A `TENANT` assignment that names a `branch_id` is not a body
violation: the application layer refuses it as `422 invalid_operation`, as before. The invite
request's `role_assignments[]` entries (see [Tenant Users](#tenant-users)) follow the same two
rules: a `BRANCH` entry without a `branch_id` is `400 validation_failed`, attributed to
`role_assignments[n].branch_id`, and a `TENANT` entry that names a `branch_id` is
`422 invalid_operation`, nothing being created in either case.

### Permissions

Base path: `/api/v1/tenant/permissions`. List filters: `q`, `risk_level`, `status`,
`sort_by`, `sort_dir`, `page`, `size`.

| Method | Path               | Summary                        | Permission        | Shape |
|--------|--------------------|--------------------------------|-------------------|-------|
| GET    | `/`                | Search permission catalogue    | `permission.view` | page  |
| GET    | `/{permission_id}` | Get permission catalogue entry | `permission.view` | item  |

Permission response:

```json
{
  "id": "aaaaaaaa-aaaa-7aaa-8aaa-aaaaaaaaaaaa",
  "permission_code": "tenant.view",
  "permission_name": "View Tenant",
  "module_code": "lifecycle",
  "description": "View active tenant metadata.",
  "risk_level": "LOW",
  "status": "ACTIVE",
  "kind": "VIEW",
  "grant_scope": "TENANT",
  "required_view_permissions": [],
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:00:00Z"
}
```

Catalogue metadata (ADR 0030, `V22`), on both the list items and the detail, with the same
fields:

- `kind`: `VIEW` (a read), `MUTATION` (changes state) or `CONTEXT` (gates the caller's own session
  context or a platform-only setting; requires nothing).
- `grant_scope`: `TENANT` (evaluated in a tenant organisation) or `PLATFORM` (only ever evaluated
  in the PLATFORM organisation, so a tenant role holding it gains nothing).
- `required_view_permissions`: the codes of the view permissions a `MUTATION` implies, sorted
  (`user.invite` has two: `membership.view` and `user.view`). Always present; empty for a `VIEW`
  or `CONTEXT` code.

```json
{
  "permission_code": "user.invite",
  "kind": "MUTATION",
  "grant_scope": "TENANT",
  "required_view_permissions": ["membership.view", "user.view"]
}
```

Paging, sorting and the filters are unchanged: the pairings are read for the page's rows in one
extra bounded lookup. See
[the authorization model](../security/authorization-model.md#catalogue-metadata).

### Audit Events

Base path: `/api/v1/tenant/audit-events`. List filters: `entity_type`, `entity_id`,
`actor_id`, `subject_user_id`, `action`, `action_prefix`, `occurred_from`, `occurred_to`, `outcome`,
`severity`, `min_severity`, `branch_id`, `q`, `actor_type`, `actor_subject`, `sort_dir`, `page`,
`size`.

| Method | Path          | Summary                    | Permission   | Shape |
|--------|---------------|----------------------------|--------------|-------|
| GET    | `/`           | Search tenant audit events | `audit.view` | page  |
| GET    | `/{event_id}` | Get tenant audit event     | `audit.view` | item  |

#### Audit search filters

Every filter is optional, filters combine with AND, and every search stays inside one
organisation's log (the caller's active tenant, or the organisation the platform route names)
and is paginated (#183):

- `action`: the action, exactly (`branch.update`).
- `action_prefix`: a literal prefix of the action, 2 to 64 characters (`branch.`); `%` and `_`
  match themselves. This is the event type filter: `event_type` is the action. Every membership
  revocation after the cut-over of ADR 0031 is `action_prefix=membership.revoke&outcome=SUCCESS`:
  it matches `membership.revoke`, `membership.revoke_pending` and `membership.revoke_suspended`,
  and not the cascade row `membership.assignments_revoked`. Before the cut-over the same search
  also matches the old service `membership.revoke` row, which has no `from` or `to` in its metadata.
- `outcome`: `SUCCESS`, `FAILURE` or `DENIED`, exactly.
- `severity`: `INFO`, `LOW`, `MEDIUM`, `HIGH` or `CRITICAL`, exactly.
- `min_severity`: that severity or above, in the order `INFO` < `LOW` < `MEDIUM` < `HIGH` <
  `CRITICAL`. Not together with `severity`.
- `branch_id`: the branch the row was recorded against (a UUID).
- `q`: case-insensitive text, 3 to 64 characters, anywhere in the action, the entity type or the
  reason; `%` and `_` match themselves.
- `actor_type`: `USER` (a person) or `SYSTEM` (a job, a listener or the bootstrap).
- `actor_subject`: the identity-provider subject (`actor_external_subject`), exactly, 1 to 255
  characters. **Tenant route only.**
- `subject_user_id`: the user the events are **about**, a UUID, exactly (#186). It is the user
  whose account, membership, branch assignment or role assignment the row changed, and it is
  never the administrator who made the change: `actor_id` answers "who did it", this answers
  "who is it about". One query, `subject_user_id=<user>`, returns the rows about that user across
  the tenant's log, one row per transition. It is accepted on the tenant route and on both
  platform routes, because it reveals no withheld field. **Cut-over:** a row written before
  `V29` has no subject and is not backfilled, so this filter does not find it; ADR 0031 lists the
  older keys those rows can still be found by. A tenant log holds the tenant's rows only: the
  platform user lifecycle rows (`user_account.suspend`, `.reactivate`, `.start_deactivation`,
  `.complete_deactivation`) are written under the reserved PLATFORM organisation, so they are
  found on the platform log, not here; the onboarding rows (`.submit`, `.start_idp_provisioning`,
  `.invite`, `.activate`) are tenant rows.
- `sort_dir`: `DESC` (default, newest first) or `ASC`, on `occurred_at`, then the id.

`outcome`, `severity`, `min_severity`, `actor_type` and `sort_dir` are case-insensitive; the
upper-case form is canonical and is the enum the OpenAPI document publishes.

- **`q` is bounded.** It needs `occurred_from`, at most 31 days before `occurred_to` (or now, when
  `occurred_to` is absent): the substring match has no index, so the window is what bounds its cost.
- **400, never 500.** A value outside its set or its length bounds, `severity` together with
  `min_severity`, `q` without a window of at most 31 days, a malformed `branch_id` or
  `subject_user_id`, or `actor_subject` on a platform route answers `400 invalid_parameter` with
  one violation naming the parameter. The message never repeats the rejected value.
- **No side channel to withheld fields.** There is no filter over `before_json`, `after_json`,
  `metadata_json`, `user_agent` or `ip_address`, and the platform searches refuse `actor_subject`
  because they withhold `actor_external_subject`.
- `branch_id` only narrows: `audit.view` is checked tenant-wide, as before, and the filter adds no
  way to see rows the caller could not already read. A row's `branch_id` is the branch the event is
  about, never the branch the caller has selected: a branch's own events, its assignments and the
  branch-scoped role assignments name that branch, and tenant-wide events carry none. Rows written
  before #186 may carry the selected branch, and are not rewritten
  (`docs/architecture/audit-logging.md`).
- **Behaviour change:** the audit searches used to ignore `sort_dir`; a value other than `ASC` or
  `DESC` is now a 400. `action` stays an exact match.

#### Platform audit events

Platform operators read audit logs through `/api/v1/platform/**`; the tenant routes above stay
tenant-only and answer a platform context with 403. Both platform search routes accept the same
filters and `page`/`size` bounds (`size` 1-100) as the tenant search, except `actor_subject`
(a 400 there), and return the same page and item shapes; the detail route returns the audit event
shape below.

| Method | Path                                                | Permission   | Shape |
|--------|-----------------------------------------------------|--------------|-------|
| GET    | `/api/v1/platform/audit-events`                     | `audit.view` | page  |
| GET    | `/api/v1/platform/audit-events/{event_id}`          | `audit.view` | item  |
| GET    | `/api/v1/platform/tenants/{tenant_id}/audit-events` | `audit.view` | page  |

`audit.view` is checked in the reserved **platform** organisation (held by `PLATFORM_SUPER_ADMIN`
and `PLATFORM_SUPPORT`), not in the tenant. The platform log holds the rows written for platform
actions (for example platform user lifecycle changes); a tenant's log also holds the rows platform
operators wrote against it. A tenant user (or a platform user without the permission) gets 403.
`/platform/audit-events/{event_id}` answers 404 for any event outside the platform log, and an
unknown `tenant_id` yields an empty page. Platform reads fall under the `platform-read` rate-limit
policy.

#### Audit event shape

A search item and a detail response carry **the same fields under the same names** (#187), so
a client can render a page row and a detail view from one type. Example (the tenant approval:
the FSM transition `ACTIVATE` on the organisation, written by a platform checker):

```json
{
  "id": "bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb",
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "occurred_at": "2026-07-25T08:12:00Z",
  "actor_type": "USER",
  "actor_id": "44444444-4444-7444-8444-444444444444",
  "actor_user_id": "44444444-4444-7444-8444-444444444444",
  "subject_user_id": null,
  "actor_external_subject": "6f1c2a9e-0d4b-4c3e-9a51-2b7d8e4f1a03",
  "branch_id": null,
  "event_type": "organisation.activate",
  "action": "organisation.activate",
  "resource_type": "ORGANISATION",
  "resource_id": "11111111-1111-7111-8111-111111111111",
  "entity_type": "ORGANISATION",
  "entity_id": "11111111-1111-7111-8111-111111111111",
  "outcome": "SUCCESS",
  "severity": "INFO",
  "reason": "Approved for onboarding.",
  "ip_address": null,
  "user_agent": "curl/8.0",
  "correlation_id": "019f7d42-8db9-7ef7-9f5e-53211981bb54",
  "request_id": "019f7d42-8db9-7ef7-9f5e-53211981bb54",
  "before_json": null,
  "after_json": null,
  "metadata_json": "{\"to\": \"ACTIVE\", \"from\": \"PENDING_APPROVAL\"}"
}
```

- **`event_type`** is the domain event name of the row: its `action`. A lifecycle transition is
  `<aggregate>.<transition>` in lower case (`organisation.activate`, `branch.suspend`); any other
  audited operation uses its own action (`user.invite`, `settings.update`, `journal.approve`).
  Rows written before this release carry the resource type (the `entity_type` value) here
  instead; they are not rewritten. **Behaviour change:** `event_type` used to repeat
  `entity_type` on every row.
- **Deprecated aliases.** `resource_type`, `resource_id` and `actor_id` are kept so existing
  clients keep working, and always hold the same value as `entity_type`, `entity_id` (as a string)
  and `actor_user_id`. Read the canonical names in new code; the aliases are marked deprecated in
  the OpenAPI document and may be dropped by a later API version. The search filters keep their
  names (`entity_type`, `entity_id`, `actor_id`); `actor_id` filters on `actor_user_id`.
- **`entity_id` holds UUIDs only.** A resource id that is not a UUID (a tenant-setting audit row
  is keyed by the setting key, for example) is not stored, so such a row reads `null` in both
  `entity_id` and `resource_id`; its `entity_type`, `action` and `before_json`/`after_json` still
  identify it. There is no text column for it and none is planned in this change.
- **`subject_user_id`** is the user the row is about (#186), a UUID, or `null` for an event about
  no user (an organisation, a branch, a tenant setting) and for a row written before `V29`, which
  is not backfilled. It is the same on the tenant and the platform routes, because a user id is
  not a withheld field (as `actor_user_id` is not). Subject and actor are different questions: an
  administrator who revokes a membership is the `actor_user_id` of that row, and the member is its
  `subject_user_id`; the actor-only search (`actor_id`) finds the first and the subject search finds
  the second.
- `actor_user_id` (and `actor_id`) is `null` when a system process acted (`actor_type`
  `SYSTEM`). `ip_address` is the client address of the request that wrote the row (personal
  data, #185): the direct peer, or the `X-Forwarded-For` client only when the peer is a configured
  trusted proxy (`finaxis.security.client-ip.trusted-proxies`, empty by default). IPv4 dotted
  quad or RFC 5952 IPv6, at most 39 characters; `null` for rows written outside an HTTP request
  and for rows written before #185. `user_agent` is stored as sent, silently cut to 512
  characters; it never fails the request.
- `before_json`, `after_json` and `metadata_json` are JSON documents as strings, after redaction.
- The tenant search (`/api/v1/tenant/audit-events`) and both platform searches return this shape
  in `items`; `GET .../{event_id}` returns it as the body.
- **Withheld on the platform pages (owner decision).** In the items of both platform searches
  (`/api/v1/platform/audit-events` and `/api/v1/platform/tenants/{tenant_id}/audit-events`),
  `before_json`, `after_json`, `metadata_json`, `user_agent`, `ip_address` and
  `actor_external_subject` are always `null`, even when the row holds values, until the owner
  decides otherwise. The keys stay, so the shape is the same. Every other field, including
  `organisation_id`, `request_id` and `correlation_id`, is returned. The tenant routes return all
  fields. `GET /api/v1/platform/audit-events/{event_id}` (the platform's own log only) is
  unchanged and returns all fields, as it did before.

### Business Date

Base path: `/api/v1/tenant/business-date`.

| Method | Path            | Summary                    | Permission              | Shape    |
|--------|-----------------|----------------------------|-------------------------|----------|
| GET    | `/`             | Get current business date  | `business_date.view`    | item     |
| GET    | `/history`      | List business date history | `business_date.view`    | page     |
| GET    | `/cob/readiness` | Get close-of-business readiness | `cob.start` + `business_date.view` | item |
| POST   | `/advance`      | Advance business date      | `business_date.advance` + `business_date.view` | mutation |
| POST   | `/cob/start`    | Start close of business    | `cob.start` + `business_date.view` | mutation |
| POST   | `/cob/complete` | Complete close of business | `cob.complete` + `business_date.view` | mutation |
| POST   | `/reopen`       | Reopen business date       | `business_date.reopen` + `business_date.view` | mutation |

Every mutation on this base path takes a row lock that postings in flight for the tenant may be
holding, so each can return `409` with `lifecycle.business_date_lock_timeout` when it cannot acquire
it within the configured bound. That is retryable and is not the same as the plain `conflict` an
optimistic-lock clash produces. See
[business date and COB status](../operations/business-date.md).

Close-of-business readiness (#168, [ADR 0038](../adr/0038-cob-readiness-and-approval-counts.md)):
`GET /cob/readiness` reports whether the tenant is ready to start close-of-business. It is one
object, so it is not paginated, and it is **advice**: a point-in-time snapshot read without a lock,
which `cob/start` and `cob/complete` do not consult (no new `409`). `ready` is `true` when no
`BLOCKING` check failed. A `BLOCKING` check can `FAIL`, an `ADVISORY` check can only `WARN`, and a
check that finds nothing is `PASS`. `count` is present on the three accounting checks (`0` when
they pass) and omitted on the two lifecycle checks. It needs `cob.start` and the
`business_date.view` it implies (a missing code is the named 403), tenant-wide. A tenant with no
accounting data is `ready` with every check `PASS`.

```json
{
  "business_date": "25-07-2026",
  "status": "OPEN",
  "ready": false,
  "checks": [
    {"code": "business_date_open", "severity": "BLOCKING", "status": "PASS",
     "detail": "The business date is OPEN."},
    {"code": "organisation_active", "severity": "BLOCKING", "status": "PASS",
     "detail": "The organisation is ACTIVE."},
    {"code": "manual_journals_pending_approval", "severity": "BLOCKING", "status": "FAIL",
     "detail": "2 manual journal(s) await approval; decide them before closing.", "count": 2},
    {"code": "manual_journals_draft", "severity": "ADVISORY", "status": "WARN",
     "detail": "1 draft manual journal(s) are not yet submitted.", "count": 1},
    {"code": "reconciliation_breaks", "severity": "ADVISORY", "status": "PASS",
     "detail": "No control account's latest reconciliation run is a break.", "count": 0}
  ]
}
```

`reconciliation_breaks` counts a control account only when its **latest** run is a `BREAK`; a break
a later run superseded does not count. There is deliberately no check for pending posting requests
(none is ever committed `PENDING`) and none for background jobs or the audit stream (no signal).

Advance request and response:

```json
{
  "new_business_date": "26-07-2026",
  "reason": "Start next operating day."
}
```

```json
{
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "previous_business_date": "25-07-2026",
  "new_business_date": "26-07-2026"
}
```

### Approval Counts

Two small reads for an approvals badge (#169, [ADR 0038](../adr/0038-cob-readiness-and-approval-counts.md)).
They are **not an inbox**: one bounded count per resource, no list, no item ids, and no new
permission code. Each is one object, so neither is paginated; list the resource itself (for
example `GET /api/v1/branches?status=PENDING_APPROVAL`) for the items.

| Method | Path                                    | Summary                          | Permission                  | Shape |
|--------|-----------------------------------------|----------------------------------|-----------------------------|-------|
| GET    | `/api/v1/tenant/approvals/pending-count`   | Count pending memberships and branches | `user.approve` and/or `branch.approve` | item |
| GET    | `/api/v1/platform/approvals/pending-count` | Count pending tenants            | `tenant.approve` (platform) | item  |

```json
{ "memberships": 3, "branches": 1 }
```

```json
{ "tenants": 2 }
```

- `memberships` is present for a caller holding `user.approve` (and the `membership.view` it
  implies); `branches` for a caller holding `branch.approve` **tenant-wide** (and `branch.view`). A
  key the caller may not act on is **omitted**, never `0`, so a caller learns nothing about a
  resource it cannot approve. A tenant caller who holds neither is refused with the named 403.
- Branch approval is tenant-wide only: a branch-scope grant counts only while its branch is
  `ACTIVE`, and a `PENDING_APPROVAL` branch has none, so a branch-scoped holder sees no `branches`.
- `tenants` is counted across tenants for a `tenant.approve` holder (with `tenant.view`) in the
  platform organisation. A tenant context calling it is refused (403).
- **Counts exclude what the caller made** (memberships it invited, branches it created, tenants it
  requested or last submitted), and are an **upper bound** on what the caller can approve: a branch
  or tenant the caller amended, the platform checker's bootstrap-only window and an organisation
  that is not `ACTIVE` can still refuse an approval the count included. A tenant that switched its
  maker-checker control off (ADR 0032) counts the caller's own items too, since they are approvable.
- Accounting approvals (manual journals, posting rules) are not included.

### Global Search

One call for the tenant directory (#167, [ADR 0039](../adr/0039-global-search-and-audit-export.md)).
It is a fan-out read over four types, not a paginated list: **one bounded page per type**, no
`page`, `size` or sort parameter, and no cross-type paging.

| Method | Path                       | Summary                        | Permission                                   | Shape |
|--------|----------------------------|--------------------------------|----------------------------------------------|-------|
| GET    | `/api/v1/tenant/search?q=` | Search the tenant directory    | any of `user.view`, `branch.view`, `role.view`, `membership.view` | item  |

`q` is required, trimmed, and 2 to 64 characters after trimming, with no control character
(NUL, newline, DEL and the like); a missing, blank, out-of-range or control-bearing value is `400`
`invalid_parameter` with a violation on `q` (the message never echoes the value). It is checked
**before** permissions, so it is a `400` for every caller. It is plain text matched
case-insensitively anywhere in the columns below: `%`, `_` and `\` match themselves (the
per-resource `q` filters still treat `%` and `_` as wildcards). `q` is not written to the
application log by the search, and the HTTP access log (`http_access`) records the route's query
string as `query="[redacted]"` for every spelling of the route that is routed to the search
handler (the path is compared decoded, so `/api/v1/tenant/%73earch` is covered); it can still
appear in the request data an error monitor such as Sentry attaches to a failed request, so treat
it as personal data.

```json
{
  "users": [
    { "id": "...", "label": "Alice Mwangi", "status": "ACTIVE", "email": "alice@acme.test" }
  ],
  "branches": [
    { "id": "...", "label": "Nairobi Main", "status": "ACTIVE", "branch_code": "NBO-01" }
  ],
  "roles": [
    { "id": "...", "label": "Teller", "status": "ACTIVE", "role_code": "TELLER" }
  ],
  "memberships": [
    { "id": "...", "label": "Alice Mwangi", "status": "ACTIVE", "membership_type": "STAFF" }
  ]
}
```

| Key           | Permission        | Matches on                                  | `id`          | `label`        | `status`            | Secondary         |
|---------------|-------------------|---------------------------------------------|---------------|----------------|---------------------|-------------------|
| `users`       | `user.view`       | username, email, display name               | user id       | `display_name` | `user_status`       | `email`           |
| `branches`    | `branch.view`     | branch code, branch name                    | branch id     | `branch_name`  | `status`            | `branch_code`     |
| `roles`       | `role.view`       | role code, role name                        | role id       | `role_name`    | `status`            | `role_code`       |
| `memberships` | `membership.view` | username, email, display name               | membership id | `display_name` | `membership_status` | `membership_type` |

- Each array holds **at most 10 hits**, in the order that type's own list returns by default
  (newest first, no relevance ranking). There is **no count and no `has_more`**: ten hits means ten
  shown, and a caller who wants more uses that resource's own list with the same `q`.
- A type the caller may not view has **no key**. An empty array always means "viewable, no match",
  so a refused type never looks permitted and the caller learns nothing it did not know.
- Each type keeps the scope rule of its own list: `user.view`, `role.view` and `membership.view`
  tenant-wide; `branch.view` target-aware, so a branch-scoped caller gets only the branches it holds
  `branch.view` on while they are `ACTIVE` (and no `branches` key when it holds none). Archived
  roles are never hits.
- A caller who may view **none** of the four types gets `403` `forbidden`, detail `Missing
  permission: user.view.` (the first code in the order users, branches, roles, memberships),
  audited as `api.access_denied` like any other refusal. A caller who may view at least one type
  never gets a `403`, and the omitted types write no audit row.
- A user and a membership of the same person are two hits with two different ids.
- The route carries no `@PreAuthorize` (no single authority gates it), writes no audit row for an
  answered search, and runs under the existing `tenant-read` rate-limit policy.

### Tenant Settings

Base path: `/api/v1/tenant/settings`. List filters: `page`, `size`.

Every setting carries `label`, `description` and `group` (all `null` for a stored key outside the
catalogue) and `is_default`: `true` when `value` is the catalogue default because nothing is
stored, so an unset and an explicitly stored value are told apart. A description says whether the
platform applies the setting yet; the maker-checker switches are applied (ADR 0032).

**Platform route (#273).** `/api/v1/platform/tenants/{tenant_id}/settings` (`GET /`, `GET /{key}`,
`PUT /{key}`, `DELETE /{key}`) lets a platform administrator who is no member of the tenant read and
change the platform-only keys (`audit_retention_days`). It requires `tenant_setting.manage_platform`
in the platform organisation, checked first (403, never 404 for an unknown tenant), then answers
`404` for an unknown tenant and `422 invalid_operation` for a key that is not platform-only. Writes
need an `ACTIVE` tenant (`409` otherwise), are idempotent, and are audited as `settings.update`
with the platform administrator as actor. `GET /` lists only the platform-only keys.

| Method | Path     | Summary                         | Permission                    | Shape    |
|--------|----------|---------------------------------|-------------------------------|----------|
| GET    | `/`      | List tenant settings            | per-key service authorization | page     |
| GET    | `/{key}` | Get tenant setting              | per-key service authorization | item     |
| PUT    | `/{key}` | Create or update tenant setting | per-key service authorization | mutation |
| DELETE | `/{key}` | Deactivate tenant setting       | per-key service authorization | mutation |

A write to a catalogue setting requires `settings.update` and its view `settings.view`; a read
requires `settings.view`. A platform-only key (`audit_retention_days`) is checked instead against
`tenant_setting.manage_platform` in the platform organisation, for both reads and writes, and that
code is a context code with no view. `GET /` masks a set platform-only value as `***REDACTED***`
for a caller without that permission, and reports an unset one as `null`.
`GET /{key}` authorises per key before it reads the stored row: an unknown key without
`settings.view` answers 403 (it was 400 before ADR 0030 step 7), the same refusal as a known key,
so it reveals nothing; with the permission it is `422 invalid_operation`.

`base_currency` is a **read-only mirror** of the organisation's `base_currency_code`, the column the
ledger reads. Reads report that column; `PUT /{key}` and `DELETE /{key}` for it answer
`422 invalid_operation` with the detail `base_currency is derived from the organisation's base
currency and cannot be changed here.`, before anything is written. The base currency is set at
draft creation and amended only while the tenant is a draft, so a tenant that has posted cannot
change it.

`settings.operational` is an internal marker the platform writes at approval. It is not a tenant
setting: `GET /` leaves it out, and `GET /settings.operational` is refused as an unknown key is,
after the `settings.view` check (403 without it, `422 invalid_operation` with it). A `PUT` of that
key is `422 invalid_operation` before any permission check. After approval the list totals the
catalogue's six keys.

Setting update request and response:

```json
{
  "value": "Africa/Nairobi",
  "reason": "Align the operating timezone with head office."
}
```

```json
{
  "key": "default_timezone",
  "value": "Africa/Nairobi",
  "value_type": "TIMEZONE",
  "sensitive": false,
  "platform_admin_only": false
}
```

## Maker-Checker Tenant Onboarding

Tenant onboarding is a platform workflow:

1. A maker creates a draft with `POST /api/v1/platform/tenants`.
2. The maker submits it with `POST /api/v1/platform/tenants/{tenant_id}/submit`.
3. A different checker approves, rejects, or returns it for changes. A returned tenant is a draft
   again: the maker amends it with `PATCH` and resubmits it, and the checker's reason is readable
   as `status_reason` on the tenant detail (see
   [Return tenant for changes](#return-tenant-for-changes)). A rejected tenant stays rejected:
   `REJECTED` is terminal and its tenant code stays reserved (#172, ADR 0037).
4. Approval returns 202 and queues asynchronous initial-administrator bootstrap.
5. Failed bootstrap can be retried with
   `POST /api/v1/platform/tenants/{tenant_id}/bootstrap/retry`.

The maker-checker rule is enforced by the application service: the actor who requested or
submitted a tenant draft cannot approve, reject or return it
(`lifecycle.approver_is_tenant_maker`), anyone who amended the draft can neither approve nor
reject it (`lifecycle.approver_is_tenant_modifier`), and neither can the platform user whose own
account the draft names (by email) as its initial administrator, because the bootstrap approves that
administrator's membership in the approver's name and nobody may approve their own membership. That
refusal is 403 with code `lifecycle.approver_is_initial_administrator`, returned before any state
change (the tenant stays `PENDING_APPROVAL`), and writes a `DENIED`, `HIGH` audit row (action
`organisation.approve`, reason the code) in its own transaction like the other tenant refusals (#156); if
no account exists yet for the administrator email there is nothing to compare. Self-approval
returns 403:

```json
{
  "type": "urn:finaxis:problem:lifecycle.approver_is_tenant_maker",
  "title": "Forbidden",
  "status": 403,
  "detail": "The checker cannot be the tenant's requester or submitter.",
  "instance": "/api/v1/platform/tenants/11111111-1111-7111-8111-111111111111/approve",
  "code": "lifecycle.approver_is_tenant_maker",
  "request_id": "019f7d45-f87d-7b55-9a68-208779281375"
}
```

### Platform checker while a tenant has no approvers of its own

The bootstrap administrator is the maker of every invitation and branch the new tenant creates, so
nobody inside it can approve them. A platform administrator can, as an audited checker
([ADR 0028](../adr/0028-platform-checker-for-first-tenant-approvals.md)):

1. the tenant administrator creates and submits a branch (`POST /api/v1/branches`,
   `POST /api/v1/branches/{branch_id}/submit`);
2. a platform administrator activates it
   (`POST /api/v1/platform/tenants/{tenant_id}/branches/{branch_id}/activate`);
3. the tenant administrator invites the second person (`POST /api/v1/tenant/users`);
4. a platform administrator approves the invitation
   (`POST /api/v1/platform/tenants/{tenant_id}/memberships/{membership_id}/activate`).

There are two separate bounds, one per kind of item, and each answers **409
`lifecycle.platform_checker_closed`** without changing anything once exceeded:

- **membership activate** works only while the tenant has no `ACTIVE` membership that the system
  actor did not create (the bootstrap administrator does not count);
- **branch submit, activate and return as a checker** work only while the tenant has no `ACTIVE`
  branch that the system actor did not create (the bootstrap head office does not count). A
  platform **withdrawal** of one's own pending branch is not bounded.

The two do not depend on each other, so the four steps above all pass: step 2 does not close the
membership bound, and step 4 does not need a branch bound. Only `ACTIVE` rows count, so suspending
or revoking them reopens the route; a membership approved for a user with no Keycloak identity
stays `PENDING_APPROVAL` (202) until the identity job activates it, during which the bound is still
open. Creating a branch draft through the platform route and the tenant's own approvers are not
bounded. If a tenant loses its only approver while its own members stay `ACTIVE`, that is a support
matter.

Tenant users still cannot approve their own invitations or activate their own branches (403). A
platform administrator cannot approve what it created, its own membership, or a branch it
submitted. The approval is attributed to the platform actor in the tenant's audit log, with
`checkerScope = PLATFORM` in the `user.approve` metadata and, for a branch, a
`branch.submit_as_platform_checker` row on submission, a `branch.activate_as_platform_checker` row
on activation and a `branch.return_for_changes_as_platform_checker` row for a branch it returned. A
membership approved this way raises the same activation event as a tenant approval.

Approval creates the durable tenant prerequisites atomically and queues the bootstrap. The
bootstrap state is exposed on `TenantDetailResponse.bootstrap_status`:

```text
DRAFT -> PENDING_ACTIVATION -> QUEUED -> PROVISIONING_IDENTITY -> COMPLETED
```

`FAILED` records a `bootstrap_failure_code` and allows retry through
`tenant.bootstrap_retry`. Only the bootstrap administrator's own failure sets it: another
invitee's failed Keycloak job fails that user's dispatch alone, and a `COMPLETED` record is never
overwritten by a `FAILED` write. Records the earlier behaviour wrongly failed are found with
[the false-FAILED report](../operations/bootstrap-false-failed-report.md).

`bootstrap_failure_code` is a **closed code**, never free text: `null` when no attempt failed,
otherwise one of `IDENTITY_PROVIDER_FAILED` (the identity provider could not be reached, refused the
call or is not enabled), `CONFLICT` (a username already in use, a membership already held, a state
conflict), `NOT_FOUND` (a record the bootstrap needs is missing), `INVALID_STATE` (an internal
invariant or precondition failed, such as a rejected transition or state), `DATABASE_ERROR` (the
database refused a read or write) or `UNEXPECTED` (anything else). The code is chosen from the
exception's type, never its message, and a database `CHECK` (`V24`) refuses any other value. It is
published as an `enum` in the OpenAPI schema. Tenant members holding `tenant.view` read it on
`GET /tenant`, and platform operators on the platform tenant routes, so the exception text (which
can carry SQL, identity-provider output or an email address) is deliberately in no response. The
`ERROR` log line the recorder writes holds the organisation id, the code, the exception and
root-cause class names and the stack frames, never an exception message; the job handlers hand
JobRunr, for the failures they catch, only the closed code or the exception class name (no message;
no cause but a message-free `InterruptedException` where the original had one) and
`ApiExceptionHandler` logs the class and frames only. A failed Keycloak or invite job stores the
closed code and the exception class name in `identity_dispatch_log.last_error`, never the message.
Rows written before that change keep the raw message; no API returns either. A failed
`tenant.bootstrap_retry` also writes an audit row with the code and the exception class name only
(never the message); the failed `user.keycloak_provisioning` audit row carries the exception class
name but no code. See [tenant provisioning](../operations/tenant-provisioning.md).

Successful initial-administrator bootstrap durably creates or resolves:

- the initial administrator application user record;
- a Keycloak user provisioning request with `send_keycloak_invite = true`;
- an optional application-invite dispatch when `send_application_invite = true`;
- the head-office branch assignment;
- the `TENANT_ADMIN` role assignment.

The mechanics follow the reference FSM and event pipeline:
`eventFactory -> ExternalizedTransitionEvent -> outbox -> RabbitMQ -> JobRunr`. See
[ADR 0004](../adr/0004-membership-activation-notification-pipeline.md) for the event and worker
pattern.

## Safe Error Disclosure

Public error details must be safe, stable, and non-sensitive. They may identify validation
fields, public problem codes, invalid state transitions, missing active context, and retry
guidance. They must not disclose bearer tokens, signed context tokens, session cookies, raw
authorization headers, password-like values, Keycloak admin details, or internal SQL/broker
errors.

Tenant and branch isolation takes precedence over diagnostic precision. Cross-tenant and
cross-branch resource lookups should use the existing `ResourceNotFoundException` pattern where
confirming existence would leak data. A caller who lacks permission to operate on a resource gets
a safe 403; a caller who should not know the resource exists gets a safe 404.

## Deeper References

- [API governance](../architecture/api-governance.md)
- [API versioning](../architecture/api-versioning.md)
- [Rate limiting](../architecture/rate-limiting.md)
- [Audit logging](../architecture/audit-logging.md)
- [FSM transitions](../architecture/fsm-transitions.md)
- [Active organisation context](../security/active-organisation-context.md)
- [Production hardening](../security/production-hardening.md)
- [ADR 0001: active organisation context](../adr/0001-active-organisation-context-transport.md)
- [ADR 0003](../adr/0003-api-governance-rate-limiting-versioning-pagination.md)
- [ADR 0004](../adr/0004-membership-activation-notification-pipeline.md)
- [ADR 0009: tenant settings auth boundary](../adr/0009-tenant-settings-scope-and-authn-boundary.md)
