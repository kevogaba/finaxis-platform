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

Branch-scoped tenant resources also validate the caller's active branch against the target
resource's branch context. Cross-branch lookups return a safe 403 or 404 instead of confirming
that a resource exists outside the caller's branch context.

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
`bootstrap_status`, and `send_application_invite`.

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
search, and sort parameters are listed with each table below.

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
| POST   | `/select-branch`       | Select active branch                              | service: `auth.select_branch` | mutation |

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
| POST   | `/{branch_id}/submit`     | Submit branch draft                  | `branch.create`     | mutation |
| POST   | `/{branch_id}/activate`   | Activate branch                      | `branch.activate`   | mutation |
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
| POST   | `/{tenant_id}/suspend`         | Suspend tenant      | `tenant.suspend`             | mutation      |
| POST   | `/{tenant_id}/reactivate`      | Reactivate tenant   | `tenant.reactivate`          | mutation      |
| POST   | `/{tenant_id}/deprovision`     | Deprovision tenant  | `tenant.deprovision`         | mutation      |
| POST   | `/{tenant_id}/bootstrap/retry` | Retry bootstrap     | `tenant.bootstrap_retry`     | mutation      |

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
  "bootstrap_status": "COMPLETED",
  "bootstrap_failure_code": null,
  "created_at": "2026-07-25T08:00:00Z",
  "updated_at": "2026-07-25T08:20:00Z"
}
```

### Platform Tenant Branches

Base path: `/api/v1/platform/tenants/{tenant_id}/branches`. List filters: `q`, `status`,
`type`, `sort_by`, `sort_dir`, `page`, `size`.

| Method | Path           | Summary                                            | Permission      | Shape    |
|--------|----------------|----------------------------------------------------|-----------------|----------|
| GET    | `/`            | Search branches for a platform-selected tenant     | `branch.view`   | page     |
| POST   | `/`            | Create branch draft for a platform-selected tenant | `branch.create` | mutation |
| GET    | `/{branch_id}` | Get tenant branch                                  | `branch.view`   | item     |

The create request and branch responses use the same fields as tenant-facing branches.

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
`bootstrap_failure_code`.

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
`membership_type`, `sort_by`, `sort_dir`, `page`, `size`.

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

### Branch Assignments

Base path: `/api/v1/tenant/branch-assignments`. List filters: `branch_id`,
`assignment_type`, `status`, `sort_by`, `sort_dir`, `page`, `size`.

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
3. A different checker approves or rejects it.
4. Approval returns 202 and queues asynchronous initial-administrator bootstrap.
5. Failed bootstrap can be retried with
   `POST /api/v1/platform/tenants/{tenant_id}/bootstrap/retry`.

The maker-checker rule is enforced by the application service: the actor who requested or
submitted a tenant draft cannot approve it. Self-approval returns 403:

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
