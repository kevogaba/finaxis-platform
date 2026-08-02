# Foundation REST API Design

> **Archived historical artifact.** This records work that has since shipped. It is kept for
> the design rationale it contains, not as a description of current behaviour. For current
> state see the ADRs in `docs/adr/` and the reference docs under `docs/architecture/`,
> `docs/security/`, `docs/operations/`, and `docs/database/`.

- **Date:** 2026-07-18
- **Status:** Approved in brainstorming; pending written-spec review
- **Scope:** Versioned REST adapters and missing application/query support for the existing
  foundation platform use cases
- **Architecture:** Kotlin, Spring Boot Web MVC, Spring Modulith, JDBC/jOOQ, Keycloak,
  Namastack Outbox, RabbitMQ, and JobRunr

## 1. Decision

Expose the implemented foundation use cases by extending their owning modules. Do not create a
central `foundation-api` module and do not query persistence directly from controllers.

The implementation has four responsibilities:

1. Strengthen the shared HTTP contract: snake-case JSON, date/time formats, RFC 9457 errors,
   pagination, idempotency, configurable rate limiting, OpenAPI, and architecture enforcement.
2. Add the bounded query ports and projections missing from existing application services.
3. Add thin platform-administration and tenant-facing REST adapters over the application layer.
4. Complete tenant provisioning with a durable, asynchronous initial-administrator bootstrap
   after maker-checker approval and organisation activation.

The existing `/api/v1/auth/select-organisation`, `/api/v1/auth/select-branch`, and
`/api/v1/auth/me` behavior remains. Their HTTP representation and documented errors join the
shared snake-case and RFC 9457 contract.

## 2. Existing foundation to preserve

The design extends, rather than replaces, these existing paths:

- `OrganisationProvisioningService`: draft, submit, approve, reject, suspend, reactivate,
  deprovision, tenant lookup, and paginated tenant listing.
- `BranchProvisioningService`: branch lifecycle and branch assignments.
- `UserProvisioningService`: invitation, approval, Keycloak provisioning dispatch, user
  lifecycle, and membership revocation.
- `RoleManagementService`: role lifecycle, role permissions, and user-role assignments.
- `TenantSettingsService`: catalog validation, effective dating, redaction, audit, and outbox.
- `BusinessDateService`: current business date, paginated history, advance, COB, and reopen.
- `AuditQueryService`: bounded tenant-scoped audit searches.
- `FoundationLifecycleService`: deterministic FSM transitions, logs, audit, and transition events.
- Spring Modulith plus Namastack for transactional event externalization, RabbitMQ for transport,
  and JobRunr for durable background work.
- Keycloak as authentication and external identity only. The application never handles passwords.

Existing mutations remain authoritative. Missing reads use new application query ports and jOOQ
adapters rather than controller-owned SQL.

## 3. Module and dependency design

### 3.1 Shared web contract

`com.finaxis.platform.common.web.api` owns transport-wide components:

- page response and page metadata DTOs;
- RFC 9457 problem construction and safe error codes;
- date, time, and datetime serializers/deserializers;
- idempotency request metadata, annotation, executor, and persistence port;
- safe request fingerprinting and replay response contracts;
- common OpenAPI schemas and header definitions;
- context declarations used by architecture tests.

The durable idempotency adapter may use JDBC/jOOQ behind a common application port. Other modules
consume the executor without depending on its database implementation.

### 3.2 Inbound REST adapters

- `lifecycle.adapter.inbound.web`: tenant, branch, membership lifecycle, branch assignment,
  tenant settings, and business date.
- `iam.adapter.inbound.web`: users, profile, roles, role assignments, and permission catalogue.
- `common.audit.adapter.inbound.web`: tenant-scoped audit reads.

Controllers depend only on application services, application query ports/facades, common web
contracts, and request context. They never depend on jOOQ, repositories, JDBC entities, outbox
types, RabbitMQ, JobRunr, or Keycloak clients.

### 3.3 Authorization boundary

`lifecycle` does not depend on `iam`. Its existing `PermissionGuard` port is extended to express:

- tenant-scoped permission checks;
- branch-scoped permission checks;
- reserved-platform-organisation permission checks.

The IAM adapter implements the port through the local permission resolver. IAM-owned application
services use `AuthorizationService` directly. Controllers add coarse `@PreAuthorize` checks, but
application services remain responsible for resource-specific authorization and lifecycle rules.

## 4. Context and identity rules

### 4.1 Tenant-facing calls

Every tenant-facing business endpoint requires an authenticated active tenant context. Tenant-wide
queries constrain the tenant in the database predicate, not only after retrieval. A resource from
another tenant is indistinguishable from an unknown resource and returns a safe `404`.

The login/runtime precondition remains: ordinary operational callers have an active selected
branch. A branch-specific call additionally requires:

- the target branch equals the active branch context;
- the branch belongs to the active tenant and is in an allowed lifecycle state;
- the caller has an active branch assignment when the operation requires one;
- the effective branch-scoped permission contains the endpoint's permission code.

Authentication selection endpoints are bearer-authenticated exceptions because they establish
tenant and branch context; they are not tenant-scoped business endpoints.

### 4.2 Platform-administration calls

Every platform endpoint requires the reserved platform organisation as active context. The target
tenant is a separate path/resource identifier and never replaces the active context. Platform
calls require a platform-scoped permission and validate that every nested branch, user, or
membership belongs to the explicit target tenant.

### 4.3 Global user safety

`user_account` is global and may participate in multiple tenants. Tenant administrators may invite
and inspect users associated with their tenant, but suspend, reactivate, or revoke tenant access
through the membership lifecycle.

Only platform administrators may globally suspend, reactivate, or deactivate a user account.
This prevents one tenant from disabling a user's access to every other tenant.

## 5. Platform-administration API

All paths are under `/api/v1/platform` and require reserved-platform context.

### 5.1 Tenant lifecycle and detail

| Method | Path | Result |
| --- | --- | --- |
| `POST` | `/api/v1/platform/tenants` | Create tenant draft with mandatory initial admin; `201` |
| `GET` | `/api/v1/platform/tenants` | Paginated tenant search |
| `GET` | `/api/v1/platform/tenants/{tenant_id}` | Full safe tenant and bootstrap detail |
| `PATCH` | `/api/v1/platform/tenants/{tenant_id}` | Amend draft metadata/initial admin only |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/submit` | Submit and freeze payload |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/approve` | Approve and queue bootstrap; `202` |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/reject` | Reject with required reason |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/suspend` | Suspend with required reason |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/reactivate` | Reactivate after setup checks |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/deprovision` | Metadata-only deprovision |
| `POST` | `/api/v1/platform/tenants/{tenant_id}/bootstrap/retry` | Retry failed bootstrap |

Tenant filters include `q`, `status`, `country_code`, `created_from`, and `created_to`. Filter and
sort fields are allowlisted.

### 5.2 Nested tenant administration

- `/api/v1/platform/tenants/{tenant_id}/branches`
- `/api/v1/platform/tenants/{tenant_id}/branches/{branch_id}`
- `/api/v1/platform/tenants/{tenant_id}/users`
- `/api/v1/platform/tenants/{tenant_id}/users/{user_id}`

The collections provide paginated search/filtering. Details do not embed unbounded assignments,
memberships, roles, permissions, or audit history. Platform branch and tenant-user provisioning
commands reuse the same application services as the tenant-facing adapters, with platform-scoped
authorization instead of target-tenant impersonation.

### 5.3 Global user lifecycle

- `POST /api/v1/platform/users/{user_id}/suspend`
- `POST /api/v1/platform/users/{user_id}/reactivate`
- `POST /api/v1/platform/users/{user_id}/deactivate`

These are the only public global user-account lifecycle mutations.

## 6. Tenant-facing API

### 6.1 Current tenant and branches

- `GET /api/v1/tenant`
- `GET|POST /api/v1/branches`
- `GET /api/v1/branches/{branch_id}`
- `POST /api/v1/branches/{branch_id}/submit`
- `POST /api/v1/branches/{branch_id}/activate`
- `POST /api/v1/branches/{branch_id}/suspend`
- `POST /api/v1/branches/{branch_id}/reactivate`
- `POST /api/v1/branches/{branch_id}/close`

Branch list/create is tenant-wide. Branch detail and lifecycle actions require matching branch
context. Branch creation returns `201` with `Location`; lifecycle actions return the resulting
resource state.

### 6.2 Users, memberships, and assignments

- `GET|POST /api/v1/users`
- `GET /api/v1/users/{user_id}`
- `GET /api/v1/memberships`
- `GET /api/v1/memberships/{membership_id}`
- `POST /api/v1/memberships/{membership_id}/activate`
- `POST /api/v1/memberships/{membership_id}/suspend`
- `POST /api/v1/memberships/{membership_id}/reactivate`
- `POST /api/v1/memberships/{membership_id}/revoke`
- `GET|POST /api/v1/users/{user_id}/branch-assignments`
- `DELETE /api/v1/users/{user_id}/branch-assignments/{assignment_id}`
- `GET|POST /api/v1/users/{user_id}/role-assignments`
- `DELETE /api/v1/users/{user_id}/role-assignments/{assignment_id}`

`POST /users` performs local invitation intake. Membership activation invokes the existing
approval and identity-provisioning workflow. It returns `200` when local activation finishes or
`202` when Keycloak provisioning/invitation is durably queued. It never bypasses required identity,
branch, or role prerequisites.

`GET /api/v1/auth/me` remains the canonical current-user profile endpoint.

### 6.3 Roles and immutable permission catalogue

- `GET|POST /api/v1/roles`
- `GET|PATCH /api/v1/roles/{role_id}`
- `POST /api/v1/roles/{role_id}/activate`
- `POST /api/v1/roles/{role_id}/deactivate`
- `GET|POST /api/v1/roles/{role_id}/permissions`
- `DELETE /api/v1/roles/{role_id}/permissions/{permission_code}`
- `GET /api/v1/permissions`
- `GET /api/v1/permissions/{permission_code}`

The global permission catalogue has no REST mutation endpoint. Permission management means
paginated catalogue reads plus granting/removing catalogue permissions on tenant roles.

### 6.4 Settings, business date, and audit

- `GET /api/v1/settings`
- `GET|PUT|DELETE /api/v1/settings/{setting_key}`
- `GET /api/v1/business-date`
- `GET /api/v1/business-date/history`
- `POST /api/v1/business-date/advance`
- `POST /api/v1/business-date/cob/start`
- `POST /api/v1/business-date/cob/complete`
- `POST /api/v1/business-date/reopen`
- `GET /api/v1/audit-events`
- `GET /api/v1/audit-events/{audit_event_id}`

Settings, permission catalogue, assignments, role permissions, business-date history, and audit
events are paginated even when the current expected row count is small.

## 7. Success response, pagination, search, and naming

HTTP JSON uses `snake_case`; Kotlin remains idiomatic camelCase. Paths use lowercase plural nouns
and kebab-case action names. Query parameters use `snake_case`. UUIDs use canonical lowercase,
hyphenated representation. Country and currency codes use uppercase ISO codes. Phone numbers use
E.164.

Detail and command responses use explicit resource DTOs directly. They never expose persistence
entities, optimistic-lock versions, internal dispatch keys, exception types, or unrestricted
nested collections.

Every list returns:

```json
{
  "items": [],
  "page": 0,
  "size": 25,
  "total_items": 0,
  "total_pages": 0,
  "has_next": false,
  "has_previous": false
}
```

Pages are zero-based. Defaults and hard maximums come from the shared pagination configuration;
application services and MVC use the same values. Each endpoint documents allowlisted filter and
sort fields. `q` is trimmed, length-bounded, and searches only documented fields. Details expose
counts or links instead of embedding unbounded child lists.

Created resources return `201` and `Location`. Synchronous lifecycle actions return `200` with the
resulting resource state. Accepted asynchronous work returns `202` with a safe provisioning state.
Successful revocations/removals may return `204` when no resulting representation is useful.

## 8. Date and time contract

- Date-only JSON and query values: `dd-MM-yyyy`, for example `18-07-2026`.
- Time-only JSON: `HH:mm:ss`, using 24-hour time.
- Datetime JSON and query values: ISO 8601 with an explicit UTC offset, normally `Z`, for example
  `2026-07-18T14:30:00Z`.

Reusable serializers, deserializers, validators, and OpenAPI schemas enforce the same contract.
Ambiguous dates and datetimes without an offset are rejected rather than interpreted in the server
timezone. Internal persistence continues using `LocalDate`, `Instant`, and the shared UTC clock.

## 9. RFC 9457 error contract

All errors use `application/problem+json` and the standard `type`, `title`, `status`, `detail`, and
`instance` members. Safe extensions are `code`, `request_id`, and a bounded `errors` list for field
validation.

Example:

```json
{
  "type": "urn:finaxis:problem:validation-failed",
  "title": "Validation failed",
  "status": 400,
  "detail": "One or more request fields are invalid.",
  "instance": "/api/v1/branches",
  "code": "validation_failed",
  "request_id": "019f...",
  "errors": [
    {
      "field": "branch_code",
      "code": "size",
      "message": "Branch code must contain 2 to 30 characters."
    }
  ]
}
```

Expected mappings:

- `400`: malformed JSON, invalid parameters, and Bean Validation failures;
- `401`: missing or invalid authentication;
- `403`: permission or context denial;
- `404`: safely hidden unknown or cross-tenant resource;
- `409`: uniqueness, concurrent state, invalid lifecycle, or idempotency mismatch;
- `422`: valid input rejected by a business rule;
- `429`: distributed rate-limit rejection;
- `500`: unexpected failure with a generic client detail.

Application failures crossing the web boundary use typed exceptions with safe public codes. The
handler never returns rejected raw values, SQL text, Keycloak responses, JobRunr data, internal
permission-resolution details, exception class names, credentials, tokens, stack traces, or
sensitive PII. Unexpected errors are correlated and logged only server-side.

Rate-limit filters and security failures use the same problem writer instead of hand-written JSON
or servlet-container error pages.

## 10. Idempotency contract

Every `POST`, `PUT`, `PATCH`, and `DELETE` handler is an idempotent mutation.

- A client may supply `Idempotency-Key` as a UUID.
- The server generates a UUID when the header is absent.
- The effective key is always returned in the `Idempotency-Key` response header.
- An invalid UUID returns `400`.
- A replayed response includes `Idempotency-Replayed: true`.

A shared `@IdempotentMutation` interceptor and transactional executor:

1. Resolve the active organisation scope, actor, HTTP method, normalized path, and safe request
   fingerprint.
2. Acquire a database transaction-scoped lock for the scope/key pair.
3. Read the durable idempotency record.
4. Reject the same key with a different method, path, or payload as
   `409 idempotency_key_reused`.
5. Execute the application command and persist its successful response in the same transaction.
6. Replay the original status, selected headers, body, and key for an identical retry.

The unique durable scope is the active organisation plus key. Platform requests therefore remain
scoped to the reserved platform organisation; the fingerprint distinguishes target tenant paths.
Concurrent identical requests serialize and create one business result.

Only successful `2xx` responses are retained. Validation failures and rolled-back commands do not
consume the key. Retention is configurable and defaults to seven days. A JobRunr cleanup task may
delete only expired idempotency records; it is not part of command execution.

The table stores scope, key, request fingerprint, method, normalized path, status, selected
response headers, safe response JSON, timestamps, and expiry. It never stores authorization
headers, bearer tokens, cookies, credentials, or raw sensitive request bodies.

Server-generated keys provide correlation and replay after the client receives the first response.
Clients requiring reliable retry after a lost connection should generate and retain their UUID
before sending the mutation.

## 11. Validation and DTO design

All request DTOs use Jakarta Bean Validation, including nested values.

- Tenant codes, branch codes, usernames, setting keys, country codes, and currency codes have
  explicit patterns and length limits.
- Email uses `@Email`; phone uses an E.164 constraint.
- Names, descriptions, addresses, and reasons have documented bounds.
- Rejection, suspension, close, deprovision, and destructive actions require a non-blank reason.
- Dates use the shared `dd-MM-yyyy` deserializer and validator.
- Nested branch assignments, role assignments, addresses, and settings are typed DTOs, not
  arbitrary transport maps.
- Collection sizes are bounded and duplicate assignment/permission entries are rejected.
- Pagination, search, filter, and sort inputs are validated before query construction.

Application services repeat domain invariants. Bean Validation is transport validation, not a
replacement for tenant ownership, lifecycle, uniqueness, or authorization checks.

## 12. Permission catalogue and endpoint enforcement

Every endpoint has a named permission code. Existing stable codes are reused when their semantics
match. A Flyway migration adds missing read, lifecycle, removal, and platform-administration codes.
Read access is never inferred from a write permission.

The migration assigns platform capabilities to the seeded platform super-administrator and the
appropriate tenant capabilities to seeded tenant roles. Runtime authorization evaluates
permission codes only, never role names.

The permission catalogue itself remains migration/code managed and immutable through REST. System
roles remain protected from tenant mutation according to the existing role service rules.

## 13. Rate limiting

Redis-backed Bucket4j remains the only production rate limiter. Extend configuration with
allowlisted path policies for:

- general reads;
- ordinary mutations;
- provisioning and invitation commands;
- high-risk platform administration.

Keys remain shared across instances and scoped by authenticated actor and active organisation.
Policy values remain external configuration, not controller constants. Rejections return
`RateLimit-Limit`, `RateLimit-Remaining`, `RateLimit-Reset`, and `Retry-After` as applicable, plus
the common RFC 9457 body. Existing fail-open/fail-closed behavior remains configurable; fail-closed
also uses the shared safe problem response.

## 14. OpenAPI contract

Every operation declares:

- stable `operationId`, summary, description, and tags;
- bearer security and required context behavior;
- request/response schemas with snake-case examples;
- filters, sorting, pagination, and allowlisted values;
- `Idempotency-Key` for mutations and replay behavior;
- success status, `Location`, and asynchronous provisioning schemas;
- reusable RFC 9457 error schemas and documented response codes;
- date/time patterns and enum values.

Generated OpenAPI is the testable API contract. Scalar/OpenAPI remain available outside production
and disabled in production as currently configured.

## 15. Maker-checker and tenant bootstrap

### 15.1 Durable draft payload

Tenant draft creation requires initial-administrator details:

- display name;
- username;
- email;
- optional E.164 phone;
- Keycloak/application invite preferences supported by the existing user workflow.

No password is accepted or stored. The draft and a durable bootstrap request are written in one
transaction. Draft metadata and the initial administrator may be amended only in `DRAFT`.
Submission freezes the provisioning payload.

The draft records its maker. Approval requires a different actor with the approval permission.
Rejection requires a reason. The application service, not only the controller, enforces these
rules.

### 15.2 Synchronous local approval

Approval retains the existing atomic local prerequisites:

1. Transition to `PROVISIONING`.
2. Seed default settings and business date.
3. Create and activate Head Office.
4. Create reference sequences.
5. Create system roles and role-permission mappings.
6. Verify mandatory setup.
7. Transition to `ACTIVE`, causing the existing externalized activation event to commit through
   Spring Modulith and Namastack.

The activation event carries identifiers only. Initial-administrator PII is loaded later from the
bootstrap store and is not copied into outbox or RabbitMQ payloads.

### 15.3 Asynchronous initial administrator

A dedicated durable RabbitMQ queue binds only to
`finaxis.lifecycle.organisation.activated`. A thin listener validates identifiers and the outbox
record key, then schedules a deterministic JobRunr bootstrap job.

The job idempotently:

1. Locks and reads the tenant bootstrap request.
2. Resolves Head Office and the seeded `TENANT_ADMIN` role.
3. Creates or reuses the local user and pending tenant membership.
4. Creates or reuses the Head Office and `TENANT_ADMIN` assignments.
5. Starts the existing Keycloak provisioning and invitation flow.
6. Marks the tenant bootstrap complete only after Keycloak identity linking and required-action
   email dispatch succeed.
7. Audits and publishes safe completion or failure outcomes.

The design reuses `UserProvisioningService`, its identity dispatch log, deterministic Keycloak
JobRunr job, and idempotent Keycloak lookup/link behavior. It does not create a second identity
provisioning mechanism.

Bootstrap status is separate from organisation lifecycle:

- `pending_activation`;
- `queued`;
- `provisioning_identity`;
- `completed`;
- `failed`.

Tenant detail returns safe status and timestamps. It never returns raw Keycloak errors, broker
payloads, dispatch keys, JobRunr identifiers, or stack traces. The tenant remains `ACTIVE` while
bootstrap progresses; readiness is explicit. A platform-only retry command restarts only failed
bootstrap and remains idempotent.

Spring Modulith, Namastack Outbox, RabbitMQ, and JobRunr remain separate layers. There is no direct
Rabbit publication from business services and no application-owned outbox.

## 16. Missing application/query support

Add bounded, tenant-safe application queries and persistence adapters for:

- full tenant detail and bootstrap status;
- branch list and detail;
- tenant-associated user list and detail;
- membership list and detail;
- branch-assignment list and detail;
- role-assignment list and detail;
- role list/detail and paginated role permissions;
- permission catalogue list/detail;
- paginated tenant settings;
- audit-event detail.

Existing guard-oriented persistence methods are not reused as public read models. Query ports
return explicit projections and total counts. Tenant predicates are mandatory at the database
boundary. Application and MVC pagination limits are unified with `PaginationProperties`.

Add explicit membership suspend/reactivate application commands over the existing FSM. Public
membership activation delegates to the approval/identity-provisioning flow rather than directly
forcing an FSM state.

## 17. Architecture enforcement

Replace the current hard-coded controller lists in governance tests with dynamic controller
discovery. Automated checks enforce:

- every public controller uses `/api/vN`;
- every list returns the shared page envelope and never a raw collection;
- every business operation has `@PreAuthorize`;
- every mutation has `@IdempotentMutation`;
- web packages use DTOs and do not expose persistence/domain entities;
- controllers do not depend on jOOQ, repositories, JDBC entities, messaging, JobRunr, or Keycloak;
- tenant/platform context intent is declared and enforced;
- every operation has OpenAPI documentation;
- HTTP JSON uses snake_case and the documented date/time formats.

Spring Modulith verification and existing ArchUnit hexagonal rules remain first-class gates.

Record the resulting architecture rules in:

- `CLAUDE.md` as the repository source of truth;
- `docs/architecture/api-governance.md`;
- a new idempotency ADR;
- `docs/api/foundation-api.md`.

`AGENTS.md` continues to point to `CLAUDE.md`; it is changed only if its short orientation becomes
incorrect, not to duplicate the detailed rules.

## 18. Testing

### 18.1 Unit tests

- DTO validation, nested constraints, date parsing, snake-case serialization, and page mapping.
- Typed exception-to-problem mapping and safe message/redaction behavior.
- Permission/context guards and cross-tenant concealment decisions.
- Request fingerprinting, UUID generation, replay, mismatch, and expiry decisions.
- Lifecycle action-to-command mapping.
- Tenant bootstrap orchestration, idempotent reuse, and retry decisions.

### 18.2 Spring MVC tests

Every controller covers:

- happy path;
- unauthenticated and forbidden paths;
- missing tenant or branch context;
- cross-tenant and mismatched-branch rejection;
- malformed JSON and Bean Validation failures;
- pagination, filters, search, sorting, and maximum-page enforcement;
- success status, headers, response DTO, and RFC 9457 error body;
- idempotency header generation, validation, and replay.

### 18.3 Integration tests

- Idempotency record and business mutation commit atomically.
- Identical retries replay one result; concurrent duplicates create one resource.
- A reused key with another fingerprint returns `409`.
- Tenant-scoped queries cannot leak another tenant's data.
- Branch-scoped commands reject mismatched branch context.
- Every list query is bounded in jOOQ.
- Maker and approver must differ and submission freezes bootstrap data.
- Organisation activation externalizes through Namastack.
- RabbitMQ schedules one deterministic JobRunr bootstrap job.
- Initial user, membership, Head Office assignment, and `TENANT_ADMIN` assignment are idempotent.
- Keycloak success completes bootstrap; failure returns only a safe state and is retryable.
- Audit records remain append-only and sensitive values remain redacted.

### 18.4 Contract and architecture tests

Generated OpenAPI is checked for every route, security scheme, context/idempotency header,
pagination parameter, schema, date format, and problem response. Dynamic architecture tests cover
all controllers. Spring Modulith and ArchUnit verification must remain green.

## 19. Verification cadence

1. Focused unit and MVC tests after each component.
2. Relevant Testcontainers integration tests after each module slice.
3. Formatting and static analysis before cross-module integration.
4. Full `./gradlew qualityGate` on the final tree.
5. Updated local smoke flow covering context selection, profile, representative paginated reads,
   one idempotent mutation replay, and tenant bootstrap when local Keycloak administration is
   enabled.

Final reporting distinguishes focused tests, final-tree quality-gate evidence, runtime smoke
evidence, and any environment-dependent limitation.

## 20. Documentation deliverables

- `docs/api/foundation-api.md`: endpoint catalogue, permissions, contexts, request/response
  examples, pagination, filters, idempotency, date/time, errors, and bootstrap behavior.
- `docs/architecture/api-governance.md`: binding architecture rules.
- New ADR: durable HTTP mutation idempotency and response replay.
- Tenant provisioning and Keycloak operations docs: maker-checker and initial-admin bootstrap.
- Local development and smoke documentation for the expanded API.
- `CLAUDE.md`: concise authoritative rules with links to the detailed docs.

## 21. Explicitly out of scope

- Savings, shares, loans, accounting, teller, member onboarding, or product configuration.
- Password handling, credential storage, or an application-managed login flow.
- Runtime CRUD for the global permission catalogue.
- A second outbox, direct Rabbit publication, or using JobRunr as event externalization.
- Financial EOD/day-close processing beyond the existing COB status foundation.
- Unbounded export endpoints. Large exports require a separately designed asynchronous job.
- Cross-tenant platform impersonation.
- Embedding unbounded child resources inside detail responses.
