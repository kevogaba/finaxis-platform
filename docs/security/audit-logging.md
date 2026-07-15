# Audit Logging

This is the production-grade audit-logging reference: the event shape, redaction policy,
append-only guarantee, `AuditService` API, the query service, and the narrow `@AuditedAction`
annotation. Read it with [audit architecture](../architecture/audit-logging.md),
[ADR 0007](../adr/0007-append-only-audit-log-and-redaction-policy.md), and
[JDBC auditing and context](../architecture/jdbc-auditing-and-context.md).

## Event shape

Every audit event maps to one row in `audit_event` (`public` schema; there is no separate
`audit` Postgres schema — the table name alone carries that meaning):

- `id`, `event_time` — identity and when it occurred (UTC);
- `organisation_id`, `branch_id` — tenant and optional branch;
- `actor_type`, `actor_user_id`, `actor_external_subject` — who acted;
- `event_type`, `entity_type`, `entity_id` — what was acted on;
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

## `@AuditedAction` (narrow scope, by design)

`@AuditedAction` + `AuditedActionAspect` wrap a method call with SpEL-evaluated `tenantId`,
`resourceId`, `actorId`, `before` (evaluated before the call), `after` (evaluated after, with
`#result` bound), and `reason`. It records one `recordSuccess`/`recordFailure` call per
invocation.

It is used in exactly two places today: `OrganisationSettingsService.updateSettings` and
`BusinessDateService.advance` — both simple, non-FSM mutations with a clean before/after
snapshot and no meaningful "reason for rejection" concept beyond a thrown exception.

It is **not** used anywhere near `common.transitions` or the lifecycle FSM services. Lifecycle
transitions keep recording audit events explicitly through `recordLifecycleTransition`, because a
transition needs an explicit reason and from/to state a generic method wrapper cannot infer, and
because wrapping the FSM's generic types in a second AOP proxy risks resurrecting the Spring
Modulith 2.1.0 observability-proxy recursion already documented in `TransitionModuleConfiguration`.
Existing manual audit call sites (`RoleManagementService`, the lifecycle services) are left
alone — they already work and are already tested; converting them would be unrelated churn.

## Query service

`AuditQueryService` is the paginated read side: `listByTenant`, `listByEntity`, `listByActor`,
`listByActionAndDateRange`, and a general `search(filter)`. Every method is tenant-scoped —
`AuditEventFilter.organisationId` is required, matching the "never expose an unscoped lookup"
convention every other tenant-owned repository in this codebase follows.

Pagination follows the existing `OrganisationListFilter`/`OrganisationPage` idiom (zero-based
`page`, bounded `size`) rather than Spring's unused `Pageable`/`Page<T>` machinery, for
consistency with `OrganisationProvisioningService.list`. `size` must be `1..100`; out-of-bounds
values are rejected with `IllegalArgumentException` before any query runs.

No REST controller is exposed by this change — `iam` has no administration controllers yet for
any resource, and this task does not add a UI.

## What must be audited

- every lifecycle transition (success and guard/policy rejection);
- role, permission, membership, and branch-assignment changes;
- organisation settings changes and business-date advances;
- Keycloak provisioning success and failure;
- invite and welcome-email dispatch success and failure.

Do not audit routine successful `GET` requests — see
[audit architecture](../architecture/audit-logging.md) for the full "what to audit" list, which
this document does not repeat.
