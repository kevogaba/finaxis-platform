# Audit Logging

> **Implementation reference.** This document covers *how* auditing works. For the "what to audit"
> list and how the pieces fit together, see
> [audit architecture](../architecture/audit-logging.md).

This is the production-grade audit-logging reference: the event shape, redaction policy,
append-only guarantee, the `AuditService` API, and the query service. Read it with [audit architecture](../architecture/audit-logging.md),
[ADR 0007](../adr/0007-append-only-audit-log-and-redaction-policy.md), and
[JDBC auditing and context](../architecture/jdbc-auditing-and-context.md).

## Event shape

Every audit event maps to one row in `audit_event` (`public` schema; there is no separate
`audit` Postgres schema — the table name alone carries that meaning):

- `id`, `event_time` — identity and when it occurred (UTC);
- `organisation_id`, `branch_id` — tenant and optional branch;
- `actor_type`, `actor_user_id`, `actor_external_subject` — who acted;
- `event_type` — the domain event name: the row's `action` (`AuditEvent.eventType`), for
  example `organisation.activate`. Rows written before #187 hold the resource type here instead
  and are not rewritten;
- `entity_type`, `entity_id` — what was acted on. `entity_id` is a `UUID`: a resource id that is
  not one (a tenant-setting key, for example) is stored as `NULL`, by design, rather than
  rejected; there is no text column for it;
- `action`, `outcome`, `severity` — what happened and how it should be triaged;
- `reason`, `correlation_id`, `request_id`, `ip_address`, `user_agent` — request context;
- `before_jsonb`, `after_jsonb` — optional before/after state summaries;
- `metadata_jsonb` — everything else, including `sourceModule`, `commandName`, and
  `externalSystemReference` (see `AuditMetadataKeys` in `common.audit`).

`tenant_id`/`branch_id`/`actor_id`/`correlation_id`/`request_id` are filled from an explicit
`AuditCommand` field when the caller supplies one, and otherwise fall back to the ambient
`RequestContexts` snapshot installed by `ActiveOrganisationContextFilter`. Background workers
(JobRunr handlers, Namastack-triggered listeners) run with no HTTP request thread, so they must
pass `tenantId`/`actorId` explicitly — see the Keycloak and invite-dispatch handlers for the
pattern.

## Append-only guarantee

`audit_event` has no `updated_at`/`updated_by` columns and no application code ever issues an
`UPDATE` or `DELETE` against it. `JooqAuditEventRepository.save` only inserts. Treat any future
code that mutates an existing audit row as a bug — see
[ADR 0007](../adr/0007-append-only-audit-log-and-redaction-policy.md).

An audit event with no tenant id would otherwise violate `audit_event.organisation_id`'s `NOT
NULL` constraint and be silently dropped. `JooqAuditEventRepository` instead falls back to the
reserved platform organisation (`PlatformOrganisation.ID`,
`00000000-0000-0000-0000-000000000000`) and logs a `WARN`, so a tenant-less call is still
durable and discoverable rather than disappearing without a trace.

## Redaction

`AuditService.record(...)` is the single choke point every convenience method funnels through,
and `SensitiveDataRedactor` runs there — on `metadata`, `before`, and `after` — before an event
ever reaches its repository. Redaction is structural, not a documentation convention.

Two ways a value gets masked to `"***REDACTED***"`:

1. **Name-based heuristic.** The key is normalized (lowercased, `_`/`-` stripped) and matched
   against a fragment list: `token`, `secret`, `authorization`, `password`, `bearer`, `cookie`,
   `apikey`, `nationalid`, `taxid`, `bankaccount`, `phone`, `email`.
2. **Explicit marking.** Wrap any value in `Redacted(value)` to force masking regardless of its
   key name — the escape hatch for a sensitive field the name heuristic can't predict.

Redaction recurses into nested `Map` values. Values are masked, not dropped, so the audit trail
still shows that a sensitive field changed without exposing its content.

Never rely on redaction to save you from putting a secret in an audit call in the first place:
bearer tokens, passwords, session cookies, and API keys must never be constructed into an
`AuditCommand` at all.

## `AuditService` methods

All seven methods build an `AuditCommand` and delegate to `record(...)`:

| Method | Use for |
| --- | --- |
| `recordSuccess` | A successful security-sensitive or state-changing action. |
| `recordFailure` | A failed or denied action (`outcome` defaults to `FAILURE`; pass `DENIED` explicitly for an access denial). |
| `recordLifecycleTransition` | An FSM transition outcome — success or rejection — with `fromState`/`toState`. Used by `FoundationLifecycleService` for every organisation/branch/user/membership transition. |
| `recordSecurityEvent` | A security-sensitive event such as a denied access attempt; defaults to `AuditSeverity.HIGH`. |
| `recordIamChange` | Role, permission, membership, or assignment changes, with optional `before`/`after`. |
| `recordExternalDispatch` | The outcome of dispatching work to an external system (Keycloak, invite/email provider); `externalSystemRef` identifies the system. |
| `recordSettingsChange` | An organisation settings change with both `before` and `after`. |

Every method accepts `actorId: UUID?` and derives `actorType` (`"USER"` vs `"SYSTEM"`) via
`SystemActor.isSystemActor(actorId)` unless the caller overrides it explicitly — services no
longer need to duplicate that check at every call site.

## Auditing is explicit, by design

There is exactly **one** mechanism: application services call `AuditService` directly at the point
of the decision.

A `@AuditedAction` AOP annotation existed until 2026-08-02, wrapping a method with SpEL-evaluated
`tenantId`, `resourceId`, `before`, and `after`. It was applied to two non-FSM mutations; both were
subsequently refactored to explicit `auditService` calls, leaving the annotation, its aspect, and
its tests as dead code that a reader could easily mistake for a live mechanism. It has been
removed.

Explicit calls are the standard because an audit record's `action`, `reason`, and before/after
state are decisions the service is making, not metadata a generic wrapper can infer. That is
especially true of lifecycle transitions, which need an explicit reason and from/to state and use
`recordLifecycleTransition`. Wrapping the FSM's generic types in a second AOP proxy also risked the
Spring Modulith observability-proxy recursion documented in `TransitionModuleConfiguration`.

`HighRiskOperationAuditCoverageTests` enforces the resulting contract: every permission with
`risk_level` `HIGH` or `CRITICAL` must map to an audited application action, so a new high-risk
operation that forgets to audit fails the build.

## Query service

`AuditQueryService` is the paginated read side: `listByTenant`, `listByEntity`, `listByActor`,
`listByActionAndDateRange`, and a general `search(filter)`. Every method is tenant-scoped —
`AuditEventFilter.organisationId` is required, matching the "never expose an unscoped lookup"
convention every other tenant-owned repository in this codebase follows.

Search and by-id reads return one projection, `AuditEventDetail`, so a page item and a detail
response carry the same fields under the same names. On the wire both also repeat three older
names as deprecated aliases (`resource_type` = `entity_type`, `resource_id` = `entity_id` as a
string, `actor_id` = `actor_user_id`); see the
[foundation API contract](../api/foundation-api.md#audit-event-shape).

Pagination follows the existing `OrganisationListFilter`/`OrganisationPage` idiom (zero-based
`page`, bounded `size`) rather than Spring's unused `Pageable`/`Page<T>` machinery, for
consistency with `OrganisationProvisioningService.list`. `size` must be `1..100`; out-of-bounds
values are rejected with `InvalidPageRequestException` (a 400) before any query runs.

`AuditEventFilter` also carries the #183 filters (`outcome`, `severity`/`min_severity`,
`branch_id`, `action_prefix`, `q`, `actor_type`, `actor_subject`, `sort_dir`). The order of
checks is the same on all three search routes: the endpoint's coarse `audit.view` gate, then the
caller's context (a tenant context on a platform route, or the reverse, is a 403 before any filter
is looked at), then the closed-set values (`outcome`, `severity`, `min_severity`, `actor_type`,
`sort_dir`), which the web adapter parses through `AuditSearchParameters` and answers with a 400,
then the application-layer `audit.view` check in `AuditQueryService`, and only then the bounds
(`requireValid`: lengths, `severity` with `min_severity`, the `q` window, the platform
`actor_subject` refusal). So a bad closed-set value can be a 400 for a caller in the right context
whom the application-layer check would refuse; the 400 names only the parameter and its public set.
A malformed UUID or instant is refused by Spring's binding before the handler runs. No filter
reads anything before both checks pass. Their design, bounds and index support are recorded in
[audit architecture](../architecture/audit-logging.md#search-filters-183). Two of them are
security rules, not conveniences: the free-text `q` must name a window of at most 31 days
(`occurred_from` is required), so one request cannot make the database scan a tenant's whole log;
and `searchForPlatform` refuses `actor_subject`, which would otherwise reveal the withheld
`actor_external_subject` of platform page items one guess at a time. No filter reads
`before_jsonb`, `after_jsonb`, `metadata_jsonb`, `user_agent` or `ip_address`.

### REST read endpoints and the platform permission model

Two controllers expose the query service, both read-only, paginated and bounded:

- `GET /api/v1/tenant/audit-events` (+ `/{event_id}`): the caller's active tenant only.
  `AuditQueryService.search/get` force the filter to the caller's organisation and require
  `audit.view` in it. Platform context is refused on this route.
- `GET /api/v1/platform/audit-events` (+ `/{event_id}`) and
  `GET /api/v1/platform/tenants/{tenant_id}/audit-events`: platform operators only.
  `AuditQueryService.searchForPlatform/getForPlatform` read the named organisation's log but
  authorise the actor in the reserved PLATFORM organisation. The controller resolves the caller
  with `getPlatformCaller()`, so a tenant context is refused with 403 before any query.

Permission decision: **`audit.view` is reused, held in the PLATFORM organisation; no new code and
no migration.** `PLATFORM_SUPER_ADMIN` already holds every catalogue row and `PLATFORM_SUPPORT`
holds `audit.view` (`V2`), so both platform roles can read the platform and per-tenant logs
without a catalogue change. A single code is enough because the scope is carried by *where* the
permission is held, not by its name: `audit.view` in a tenant reads that tenant; `audit.view` in
PLATFORM reads the platform log and any tenant's. Tenant users cannot gain it by holding
`audit.view` in their own organisation, because the platform check resolves permissions against
the PLATFORM organisation's memberships. Splitting read access between "platform log" and "tenant
logs" would need a second code and a forward-only migration; no requirement asks for it yet.

Platform actions on a tenant are written to that tenant's log, and platform user lifecycle
actions to the PLATFORM log, so between them the platform endpoints make every row readable by
someone. A single tenant event has no platform detail route: platform operators read it through
the list filters (`entity_id`, `action`, ...) or add a route if a client needs one.

**The sensitive set is withheld on the platform pages, by owner decision.** A page item now has the
detail's fields (#187), which would let any holder of `audit.view` in PLATFORM (`PLATFORM_SUPPORT`
included) bulk-read every tenant's state and request context. So the items of both platform
searches carry `before_json`, `after_json`, `metadata_json`, `user_agent`, `ip_address` and
`actor_external_subject` as `null` until the owner decides otherwise. The keys stay, so the shape
is stable. The rule is in one place, `toPlatformSummaryResponse` in the lifecycle web adapter.
The tenant routes return every field. The platform detail route reads only the platform's own log
and returns every field, as it did before.

## What must be audited

- every lifecycle transition (success and guard/policy rejection);
- role, permission, membership, and branch-assignment changes;
- organisation settings changes and business-date advances;
- Keycloak provisioning success and failure;
- invite and welcome-email dispatch success and failure.

Do not audit routine successful `GET` requests — see
[audit architecture](../architecture/audit-logging.md) for the full "what to audit" list, which
this document does not repeat.
