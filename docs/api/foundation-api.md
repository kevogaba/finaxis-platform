# Foundation REST API

This guide is the canonical REST contract reference for the implemented foundation API.
It complements the generated OpenAPI document at `/v3/api-docs` and the local Scalar UI at
`/scalar`.

## Overview

All public endpoints are versioned under `/api/v1`. Future incompatible contracts must use a
new version path; unversioned public routes are not part of the contract.

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
to `0`. `size` defaults to `25`; valid values are `1` through `100`. Resource-specific filter,
search, and sort parameters are listed with each table below. In the OpenAPI document each list
response is a typed page schema such as `ApiPageRoleSummaryResponse` whose `items` reference the
item schema.

`sort_dir` accepts `ASC` or `DESC`, case-insensitively. `sort_by` values are **camelCase**, unlike
every other wire name, and are published as an enum per endpoint:

| Endpoint | `sort_by` values |
| --- | --- |
| `GET /tenant/roles` | `roleCode`, `roleName`, `status`, `createdAt` |
| `GET /tenant/permissions` | `permissionCode`, `permissionName`, `riskLevel`, `status`, `createdAt` |
| `GET /platform/tenants` | `tenantCode`, `displayName`, `countryCode`, `createdAt` |
| `GET /branches`, `GET /platform/tenants/{tenant_id}/branches` | `branchCode`, `branchName`, `branchType`, `status`, `createdAt` |

`GET /tenant/memberships` and `GET /tenant/branch-assignments` do not sort: they ignore
`sort_by` and `sort_dir` like any unknown query parameter, and the OpenAPI document does not
publish them. Other list endpoints do not accept sort parameters.

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

Framework-level errors include `authentication_required`, `access_denied`,
`invalid_active_tenant_context`, `rate_limit_exceeded`, `rate_limit_policy_unavailable`, and
`rate_limiter_unavailable`. Bean Validation failures return 400 with up to 100 violations.

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

`X-Request-Id` may be supplied by the client or generated by the server. The same value is echoed
on success and error responses.

## Endpoint Reference

The `Shape` column uses `page` for an `ApiPage<T>` collection, `item` for a single resource, and
`mutation` for state-changing operations.

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
paginated items contain `branch_id`, `branch_code`, `branch_name`, and `branch_status`.

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
  ]
}
```

### Branches

Base path: `/api/v1/branches`. List filters: `q`, `status`, `type`, `sort_by`, `sort_dir`,
`page`, `size`.

| Method | Path                      | Summary                              | Permission          | Shape    |
|--------|---------------------------|--------------------------------------|---------------------|----------|
| GET    | `/`                       | Search branches in the active tenant | `branch.view`       | page     |
| POST   | `/`                       | Create branch draft                  | `branch.create`     | mutation |
| GET    | `/{branch_id}`            | Get branch                           | `branch.view`       | item     |
| PATCH  | `/{branch_id}`            | Update branch                        | `branch.update`     | mutation |
| POST   | `/{branch_id}/submit`     | Submit branch draft                  | `branch.create`     | mutation |
| POST   | `/{branch_id}/activate`   | Activate branch                      | `branch.activate`   | mutation |
| POST   | `/{branch_id}/return`     | Return or withdraw a pending branch  | `branch.activate` (checker) or `branch.create` (maker) | mutation |
| POST   | `/{branch_id}/suspend`    | Suspend branch                       | `branch.suspend`    | mutation |
| POST   | `/{branch_id}/reactivate` | Reactivate branch                    | `branch.reactivate` | mutation |
| POST   | `/{branch_id}/close`      | Close branch                         | `branch.close`      | mutation |

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

The platform route has no equivalent: a platform administrator creates, submits and activates a
tenant's branch but does not edit it.

`opened_on` and `closed_on` are business dates (`dd-MM-yyyy`) taken from the tenant's current
business date, never the wall clock, and are `null` until they apply:

- `opened_on` is set when the branch first becomes `ACTIVE`, including the head office that tenant
  approval activates. A suspend and reactivate does not move it.
- `closed_on` is set when the branch is `CLOSED`. `CLOSED` only moves on to `ARCHIVED`, so it is
  never cleared.

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
| **Return for changes** | anyone else | `branch.activate` | `branch.return_for_changes` (FSM) |

- **No new permission code.** A creator who also holds `branch.activate` is still a maker for this
  branch, so is classified as withdrawing and needs `branch.create`. A creator or submitter who has
  since lost `branch.create` cannot withdraw, but a non-maker holding `branch.activate` can still
  return the branch.
- **Maker-checker.** Returning is the one decision a maker may take on their own branch: it hands
  the branch back to them and can never activate anything. The creator rule (and, on the platform
  route, the submitter rule) keeps applying to every later `ACTIVATE`; a branch the creator drafted
  cannot be activated by the creator however many times it loops.
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
- **Response and idempotency.** The detail is read back without `branch.view`, so a role holding
  only `branch.activate` or only `branch.create` gets the result of its own return. The route
  accepts an optional `Idempotency-Key`; a replay returns the stored response and returns the
  branch once.

The platform route is `POST /api/v1/platform/tenants/{tenant_id}/branches/{branch_id}/return`
(see [Platform Tenant Branches](#platform-tenant-branches)).

### Platform Tenant Administration

Base path: `/api/v1/platform/tenants`. List filters: `q`, `status`, `country`,
`created_from`, `created_to`, `sort_by`, `sort_dir`, `page`, `size`.

| Method | Path                           | Summary             | Permission                   | Shape         |
|--------|--------------------------------|---------------------|------------------------------|---------------|
| POST   | `/`                            | Create tenant draft | `tenant.create`              | mutation      |
| GET    | `/`                            | Search tenants      | `tenant.view`                | page          |
| GET    | `/{tenant_id}`                 | Get tenant          | `tenant.view`                | item          |
| PATCH  | `/{tenant_id}`                 | Amend tenant draft  | `tenant.update_draft`        | mutation      |
| POST   | `/{tenant_id}/submit`          | Submit tenant draft | `tenant.submit_for_approval` | mutation      |
| POST   | `/{tenant_id}/approve`         | Approve tenant      | `tenant.approve`             | mutation, 202 |
| POST   | `/{tenant_id}/reject`          | Reject tenant draft | `tenant.reject`              | mutation      |
| POST   | `/{tenant_id}/return`          | Return to draft     | `tenant.reject`              | mutation      |
| POST   | `/{tenant_id}/suspend`         | Suspend tenant      | `tenant.suspend`             | mutation      |
| POST   | `/{tenant_id}/reactivate`      | Reactivate tenant   | `tenant.reactivate`          | mutation      |
| POST   | `/{tenant_id}/deprovision`     | Deprovision tenant  | `tenant.deprovision`         | mutation      |
| POST   | `/{tenant_id}/bootstrap/retry` | Retry bootstrap     | `tenant.bootstrap_retry`     | mutation      |

Every `{tenant_id}` mutation above (`PATCH`, `submit`, `approve`, `reject`, `return`, `suspend`,
`reactivate`, `deprovision`, `bootstrap/retry`) answers
**`409` `lifecycle.platform_organisation_protected`** when `{tenant_id}` is the reserved platform
organisation (`00000000-0000-0000-0000-000000000000`), with the detail "The platform organisation
cannot be suspended, deprovisioned or otherwise changed through the tenant lifecycle.", and changes
nothing; the refused attempt is recorded as a `DENIED` audit event. It follows the permission
check (`403`) and precedes any existence or state check; the platform branch and user routes
under a tenant still answer `404` for it. See
[the platform organisation is never a tenant](../security/authorization-model.md#the-platform-organisation-is-never-a-tenant).

`POST /{tenant_id}/approve` takes an optional decision remark, see
[Decision remarks](#decision-remarks). `POST /{tenant_id}/return` takes a required reason, see
[Return tenant for changes](#return-tenant-for-changes).

`base_currency_code` is the tenant's functional currency for every future journal line, so create
and amend validate it against the ledger's own currency authority and not only against its shape:
beyond the `^[A-Z]{3}$` pattern the request body enforces as a `400`, it must be an ISO 4217 code
the JDK knows **and** must have a minor unit, which refuses `XXX` and the metals `XAU`, `XAG`,
`XPD` and `XPT`. A rejection is `422` with code `accounting.currency_invalid` - deliberately
accounting's own code, so provisioning and the `base_currency` tenant setting refuse the same string
identically.

A posting refuses these codes too, but not always under the same code: an unknown code such as
`ZZZ` fails as `accounting.currency_invalid`, while `XXX` and the metals are known to the JDK and
instead fail per-amount as `accounting.amount_precision_exceeded`, because no amount can be
expressed at a minor unit they do not have. Refusing them at provisioning is what stops a tenant
reaching that state at all.

`initial_settings` is the same tenant-setting surface as `PUT /api/v1/tenant/settings/{key}`, and
answers to the same catalogue: each key must be one the catalogue defines, each value must satisfy
that key's type rule, and the value is stored in the catalogue's canonical form under its declared
`value_type`. An unknown key or an invalid value is `422`, with the key's own error code - so
`{"base_currency": "XAU"}` is refused here exactly as it is on the settings endpoint.

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
  "country_code": "KE",
  "base_currency_code": "KES",
  "timezone": "Africa/Nairobi",
  "status": "ACTIVE",
  "status_reason": "KYC pack reviewed.",
  "bootstrap_status": "COMPLETED",
  "bootstrap_failure_code": null,
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:20:00Z"
}
```

`status_reason` is the reason recorded by the tenant's **last transition**, so a maker can read why
a tenant was returned to draft. It is **always present** and nullable: `null` when the last
transition recorded none, never omitted. An amend keeps it and the next transition (the
resubmission) replaces it, while the transition log and the audit trail keep the history. Every
route that answers a `TenantDetailResponse` carries it: the platform tenant detail, the tenant
detail that each platform mutation other than create returns, and the tenant's own
`GET /api/v1/tenant`, so the maker of a returned tenant and the tenant itself read the same value.
Its OpenAPI schema is a required, nullable string.

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
  decision, checked in the platform organisation by the application service. No new code.
- **Checker only.** The caller must be neither the tenant's requester nor the actor of its current
  submission (the rule `approve` applies, `403 forbidden`), and not the system actor. A maker
  cannot withdraw a tenant through this route: a tenant has no maker-side withdraw, a maker amends
  a `DRAFT` only. A maker holding `tenant.reject` can still terminally reject their own submission,
  as before.
- **Check order.** The permission (`403`, before any existence signal); the platform organisation
  (`409` `lifecycle.platform_organisation_protected`, before any lock or read); the tenant (`404`
  for an unknown id); the maker-checker rule (`403`); the state (`409`).
- **State.** Only `PENDING_APPROVAL` can be returned; any other state, including `REJECTED`, answers
  `409 conflict` and changes nothing. `reject` is unchanged and stays terminal (recovering a
  rejected tenant is a separate piece of work).
- **Bootstrap record.** The initial-administrator record becomes a draft again, as `reject` leaves
  it: status `DRAFT`, `submitted_by`/`submitted_at` and `approved_by`/`approved_at` cleared,
  `requested_by` and the administrator block untouched, in the same transaction.
- **Amend and resubmit.** A returned tenant is a `DRAFT`: `PATCH /{tenant_id}` amends it, which
  replaces the administrator block, and `POST /{tenant_id}/submit` resubmits it; `submit` is
  unchanged, so the submitter of the new request is whoever resubmits. The maker-checker rule and
  the `lifecycle.approver_is_initial_administrator` refusal are read from the record at approval,
  so they hold across the loop: the requester and the new submitter cannot approve, and if the
  amended administrator is the account of whoever approves, that approval is refused. The checker
  who returned a tenant, like any amender, is not a maker and may approve a later resubmission; so
  may the earlier submitter, who is not the submitter of the current request (an accepted
  consequence of reading the rule from the record at approval). A returned tenant cannot be
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
- **Response and idempotency.** The detail is read back without `tenant.view`, so a role holding
  only `tenant.reject` gets the result of its own return. The route accepts an optional
  `Idempotency-Key`; a replay returns the stored response and returns the tenant once.

### Platform Tenant Branches

Base path: `/api/v1/platform/tenants/{tenant_id}/branches`. List filters: `q`, `status`,
`type`, `sort_by`, `sort_dir`, `page`, `size`.

| Method | Path                    | Summary                                            | Permission        | Shape    |
|--------|-------------------------|----------------------------------------------------|-------------------|----------|
| GET    | `/`                     | Search branches for a platform-selected tenant     | `branch.view`     | page     |
| POST   | `/`                     | Create branch draft for a platform-selected tenant | `branch.create`   | mutation |
| GET    | `/{branch_id}`          | Get tenant branch                                  | `branch.view`     | item     |
| POST   | `/{branch_id}/submit`   | Submit a tenant branch draft for approval          | `branch.create`   | mutation |
| POST   | `/{branch_id}/activate` | Activate a tenant branch as platform checker       | `branch.activate` | mutation |
| POST   | `/{branch_id}/return`   | Return a pending tenant branch to draft, or withdraw it | `branch.activate` (checker) or `branch.create` (maker) | mutation |

The create request and branch responses use the same fields as tenant-facing branches. Every
permission above is checked in the **platform** organisation only: no tenant membership or tenant
role is needed, and a draft can be created while the tenant is `PROVISIONING` or `ACTIVE`
(404 for no such tenant, 409 for any other state). Submit and activate return the branch and
require the path tenant to own `branch_id` (404 otherwise; the platform organisation is never a
valid `tenant_id`). The optional body of submit and activate is validated (`reason` at most 500
characters, otherwise 400 `validation_failed`). The returned branch needs no `branch.view`: the
route works with its mutation permission alone. Submit needs an `ACTIVE` or `PROVISIONING` tenant (409 otherwise); activation
needs an `ACTIVE` tenant and a platform actor that neither created nor submitted the branch (403).
Submit and activate are also bounded: **409 `lifecycle.platform_checker_closed`** once the tenant
has an `ACTIVE` branch that the system actor did not create (the bootstrap head office does not
count). See
[Platform checker while a tenant has no approvers of its own](#platform-checker-while-a-tenant-has-no-approvers-of-its-own).

`return` takes the required `{"reason": "..."}` body and behaves as the tenant route
([Return or withdraw a pending branch](#return-or-withdraw-a-pending-branch)), with the permission
checked **in the platform organisation**: a platform actor who is neither the creator nor the
latest submitter **returns as the audited platform checker** (`branch.activate`) and is bounded
exactly like activation (**409 `lifecycle.platform_checker_closed`** once the tenant has its own
`ACTIVE` branch); one who is the creator or latest submitter **withdraws** (`branch.create`), which
is not bounded, because taking back one's own request grants nothing. A platform withdrawal writes
`branch.withdraw` with no `checkerScope`; only a return as a checker writes
`branch.return_for_changes_as_platform_checker` with `checkerScope = PLATFORM`. The check order is
the permission, then `404` for the platform organisation as `tenant_id`, then `404` for a branch
outside the path tenant, then (checker only) the window, then the tenant state (`ACTIVE` or
`PROVISIONING`, else `409`), then the branch state (`409`).

### Platform Tenant Memberships

Base path: `/api/v1/platform/tenants/{tenant_id}/memberships`.

| Method | Path                        | Summary                                         | Permission     | Shape    |
|--------|-----------------------------|-------------------------------------------------|----------------|----------|
| POST   | `/{membership_id}/activate` | Approve a tenant membership as platform checker | `user.approve` | mutation |

Takes an optional decision remark body (see [Decision remarks](#decision-remarks)) and returns the
membership as `GET /api/v1/tenant/memberships/{membership_id}` does: **200** when the membership became `ACTIVE`, **202** while Keycloak provisioning is queued.
The permission is checked in the platform organisation, and the returned membership needs no
`membership.view`: the route works with `user.approve` alone. `404` when the membership is not in the
path tenant, `409` when the tenant is not `ACTIVE`, the membership is not pending approval, or the
tenant already has an `ACTIVE` membership the system actor did not create (the bootstrap
administrator does not count; code `lifecycle.platform_checker_closed`), and
`403` when the platform actor invited the membership or is the invited user (the checker is
neither the maker nor the beneficiary).

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

Base path: `/api/v1/platform/users`.

| Method | Path                    | Summary                        | Permission        | Shape    |
|--------|-------------------------|--------------------------------|-------------------|----------|
| POST   | `/{user_id}/suspend`    | Suspend global user account    | `user.suspend`    | mutation |
| POST   | `/{user_id}/reactivate` | Reactivate global user account | `user.activate`   | mutation |
| POST   | `/{user_id}/deactivate` | Deactivate global user account | `user.deactivate` | mutation |

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

### Current Tenant

Base path: `/api/v1/tenant`.

| Method | Path | Summary                                | Permission    | Shape |
|--------|------|----------------------------------------|---------------|-------|
| GET    | `/`  | Get current tenant from active context | `tenant.view` | item  |

The response uses `TenantDetailResponse`, including `bootstrap_status` and
`bootstrap_failure_code`, and the always-present, nullable `status_reason` of the tenant's last
transition (see [Platform Tenant Administration](#platform-tenant-administration)).

### Tenant Users

Base path: `/api/v1/tenant/users`. List filters: `q`, `user_status`, `membership_status`,
`page`, `size`.

| Method | Path         | Summary                           | Permission    | Shape    |
|--------|--------------|-----------------------------------|---------------|----------|
| GET    | `/`          | Search users in the active tenant | `user.view`   | page     |
| POST   | `/`          | Invite tenant user                | `user.invite` | mutation |
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

### Memberships

Base path: `/api/v1/tenant/memberships`. List filters: `q`, `membership_status`,
`membership_type`, `page`, `size`. This list does not sort; see [Pagination](#pagination).

| Method | Path                          | Summary                         | Permission              | Shape    |
|--------|-------------------------------|---------------------------------|-------------------------|----------|
| GET    | `/`                           | Search tenant memberships       | `membership.view`       | page     |
| GET    | `/{membership_id}`            | Get membership                  | `membership.view`       | item     |
| POST   | `/{membership_id}/activate`   | Approve and activate membership | `user.approve`          | mutation |
| POST   | `/{membership_id}/suspend`    | Suspend membership              | `membership.suspend`    | mutation |
| POST   | `/{membership_id}/reactivate` | Reactivate                      | `membership.reactivate` | mutation |
| POST   | `/{membership_id}/revoke`     | Revoke membership               | `membership.revoke`     | mutation |

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

#### Decision remarks

Three approvals accept an **optional** JSON body carrying a remark. The body itself is optional and
so is the field, so a call without one behaves exactly as before:

| Route                                                                        | Permission       |
|------------------------------------------------------------------------------|------------------|
| `POST /api/v1/tenant/memberships/{membership_id}/activate`                   | `user.approve`   |
| `POST /api/v1/platform/tenants/{tenant_id}/memberships/{membership_id}/activate` | `user.approve` |
| `POST /api/v1/platform/tenants/{tenant_id}/approve`                          | `tenant.approve` |

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

The membership detail does not expose a status reason. The platform tenant detail does, as
`status_reason` (see [Platform Tenant Administration](#platform-tenant-administration)), and so
does the branch detail.

### Branch Assignments

Base path: `/api/v1/tenant/branch-assignments`. List filters: `branch_id`,
`assignment_type`, `status`, `page`, `size`. This list does not sort; see
[Pagination](#pagination).

| Method | Path               | Summary                   | Permission               | Shape    |
|--------|--------------------|---------------------------|--------------------------|----------|
| GET    | `/`                | Search branch assignments | `branch_assignment.view` | page     |
| GET    | `/{assignment_id}` | Get branch assignment     | `branch_assignment.view` | item     |
| POST   | `/`                | Assign user to branch     | `user.assign_branch`     | mutation |
| DELETE | `/{assignment_id}` | Revoke branch assignment  | `user.revoke_branch`     | mutation |

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
`sort_by`, `sort_dir`, `page`, `size`. Role permission list filters: `page`, `size`.

Endpoints:

- `GET /`: search roles. Permission `role.view`. Shape: page.
- `POST /`: create role. Permission `role.create`. Shape: mutation.
- `GET /{role_id}`: get role. Permission `role.view`. Shape: item.
- `PATCH /{role_id}`: update role metadata. Permission `role.update`. Shape: mutation.
- `POST /{role_id}/activate`: activate role. Permission `role.activate`. Shape: mutation.
- `POST /{role_id}/deactivate`: deactivate role. Permission `role.deactivate`.
  Shape: mutation.
- `GET /{role_id}/permissions`: list role permissions. Permission `role.view`.
  Shape: page.
- `POST /{role_id}/permissions`: grant role permission. Permission `role.assign_permission`.
  Shape: mutation.
- `DELETE /{role_id}/permissions/{role_permission_id}`: remove role permission grant.
  Permission `role.remove_permission`. Shape: mutation.

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
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:10:00Z"
}
```

### Role Assignments

Base path: `/api/v1/tenant/role-assignments`. List filters: `user_id`, `role_id`,
`branch_id`, `scope_type`, `status`, `page`, `size`.

| Method | Path               | Summary                 | Permission             | Shape    |
|--------|--------------------|-------------------------|------------------------|----------|
| GET    | `/`                | Search role assignments | `role_assignment.view` | page     |
| GET    | `/{assignment_id}` | Get role assignment     | `role_assignment.view` | item     |
| POST   | `/`                | Assign role to user     | `user.assign_role`     | mutation |
| DELETE | `/{assignment_id}` | Revoke role assignment  | `user.revoke_role`     | mutation |

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
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:00:00Z"
}
```

### Audit Events

Base path: `/api/v1/tenant/audit-events`. List filters: `entity_type`, `entity_id`,
`actor_id`, `action`, `occurred_from`, `occurred_to`, `page`, `size`.

| Method | Path          | Summary                    | Permission   | Shape |
|--------|---------------|----------------------------|--------------|-------|
| GET    | `/`           | Search tenant audit events | `audit.view` | page  |
| GET    | `/{event_id}` | Get tenant audit event     | `audit.view` | item  |

#### Platform audit events

Platform operators read audit logs through `/api/v1/platform/**`; the tenant routes above stay
tenant-only and answer a platform context with 403. Both platform search routes accept the same
filters and `page`/`size` bounds (`size` 1-100) as the tenant search and return the same page and
summary shapes; the detail route returns the audit detail response below.

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

Audit detail response:

```json
{
  "id": "bbbbbbbb-bbbb-7bbb-8bbb-bbbbbbbbbbbb",
  "organisation_id": "11111111-1111-7111-8111-111111111111",
  "occurred_at": "2026-07-25T08:12:00Z",
  "actor_user_id": "44444444-4444-7444-8444-444444444444",
  "actor_external_subject": "local.admin",
  "actor_type": "USER",
  "branch_id": null,
  "event_type": "TenantApproved",
  "entity_type": "ORGANISATION",
  "entity_id": "11111111-1111-7111-8111-111111111111",
  "action": "tenant.approve",
  "outcome": "SUCCESS",
  "severity": "INFO",
  "ip_address": "127.0.0.1",
  "user_agent": "curl/8.0",
  "correlation_id": "019f7d42-8db9-7ef7-9f5e-53211981bb54",
  "request_id": "019f7d42-8db9-7ef7-9f5e-53211981bb54",
  "before_json": null,
  "after_json": null,
  "metadata_json": "{}",
  "reason": "Approved for onboarding."
}
```

### Business Date

Base path: `/api/v1/tenant/business-date`.

| Method | Path            | Summary                    | Permission              | Shape    |
|--------|-----------------|----------------------------|-------------------------|----------|
| GET    | `/`             | Get current business date  | `business_date.view`    | item     |
| GET    | `/history`      | List business date history | `business_date.view`    | page     |
| POST   | `/advance`      | Advance business date      | `business_date.advance` | mutation |
| POST   | `/cob/start`    | Start close of business    | `cob.start`             | mutation |
| POST   | `/cob/complete` | Complete close of business | `cob.complete`          | mutation |
| POST   | `/reopen`       | Reopen business date       | `business_date.reopen`  | mutation |

Every mutation on this base path takes a row lock that postings in flight for the tenant may be
holding, so each can return `409` with `lifecycle.business_date_lock_timeout` when it cannot acquire
it within the configured bound. That is retryable and is not the same as the plain `conflict` an
optimistic-lock clash produces. See
[business date and COB status](../operations/business-date.md).

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

### Tenant Settings

Base path: `/api/v1/tenant/settings`. List filters: `page`, `size`.

| Method | Path     | Summary                         | Permission                    | Shape    |
|--------|----------|---------------------------------|-------------------------------|----------|
| GET    | `/`      | List tenant settings            | per-key service authorization | page     |
| GET    | `/{key}` | Get tenant setting              | per-key service authorization | item     |
| PUT    | `/{key}` | Create or update tenant setting | per-key service authorization | mutation |
| DELETE | `/{key}` | Deactivate tenant setting       | per-key service authorization | mutation |

Setting update request and response:

```json
{
  "value": "17:00:00",
  "reason": "Align cutoff with operating policy."
}
```

```json
{
  "key": "statement.cutoff_time",
  "value": "17:00:00",
  "value_type": "STRING",
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
   [Return tenant for changes](#return-tenant-for-changes)). A rejected tenant stays rejected.
4. Approval returns 202 and queues asynchronous initial-administrator bootstrap.
5. Failed bootstrap can be retried with
   `POST /api/v1/platform/tenants/{tenant_id}/bootstrap/retry`.

The maker-checker rule is enforced by the application service: the actor who requested or
submitted a tenant draft cannot approve it, and neither can the platform user whose own account the
draft names (by email) as its initial administrator, because the bootstrap approves that
administrator's membership in the approver's name and nobody may approve their own membership. That
refusal is 403 with code `lifecycle.approver_is_initial_administrator`, returned before any state
change (the tenant stays `PENDING_APPROVAL`); if no account exists yet for the administrator email
there is nothing to compare. Self-approval returns 403:

```json
{
  "type": "urn:finaxis:problem:forbidden",
  "title": "Forbidden",
  "status": 403,
  "detail": "The requested operation is not allowed.",
  "instance": "/api/v1/platform/tenants/11111111-1111-7111-8111-111111111111/approve",
  "code": "forbidden",
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

`FAILED` records a safe `bootstrap_failure_code` and allows retry through
`tenant.bootstrap_retry`.

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
