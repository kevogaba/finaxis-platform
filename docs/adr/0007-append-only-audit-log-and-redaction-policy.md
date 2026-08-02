# ADR 0007: Append-Only Audit Log With Structural Redaction

## Status

Accepted

Date: 2026-07-15

## Context

`audit_event` already existed (then Flyway `V1`, extended by `V4`; now consolidated into
`V1__foundation_schema.sql` by [ADR 0010](0010-greenfield-migration-reset-and-schema-rewrite.md))
with a durable `AuditService` /
`JooqAuditEventRepository` adapter, but three things were missing before it could be called
production-grade: a consistent way for callers to record specific kinds of events without
duplicating `AuditCommand` boilerplate, a structural guarantee that sensitive values never reach
the table, and a paginated read side. `docs/architecture/audit-logging.md` also still described
the repository as "log-only until a durable adapter is introduced," which was already false.

Two further gaps were concrete and worth naming: `FoundationLifecycleService` only audited
successful transitions (a rejected guard left no trace at all), and Keycloak provisioning plus
invite/welcome-email dispatch had no audit call anywhere — only `identity_dispatch_log` tracked
their outcome.

## Decision

`audit_event` remains insert-only. No code path issues `UPDATE` or `DELETE` against it, and the
table has no `updated_at`/`updated_by` columns to make that mistake easy to introduce by copy.
`JooqAuditEventRepository.save` only inserts. A tenant-less audit command no longer disappears
silently — it now falls back to the reserved platform organisation and logs a `WARN`, so every
audit call is discoverable even when it's a bug.

Redaction happens once, at write time, inside `AuditService.record(...)` — the single choke
point every convenience method (`recordSuccess`, `recordFailure`, `recordLifecycleTransition`,
`recordSecurityEvent`, `recordIamChange`, `recordExternalDispatch`, `recordSettingsChange`)
funnels through. `SensitiveDataRedactor` masks values by a normalized key-name heuristic
(`token`, `secret`, `authorization`, `password`, `bearer`, `cookie`, `apikey`, `nationalid`,
`taxid`, `bankaccount`, `phone`, `email`) and by an explicit `Redacted(value)` wrapper for
anything the heuristic can't predict. Masking replaces the value rather than dropping the key, so
the audit trail still shows that a sensitive field changed.

> **Amended 2026-08-02.** `@AuditedAction`, `AuditedActionAspect`, and their tests have been
> **removed**. Both original call sites were later refactored to explicit `auditService` calls,
> leaving the annotation with zero production usage — dead code that still read like a live
> mechanism. Explicit service-level auditing is now the single mechanism, and
> `HighRiskOperationAuditCoverageTests` enforces that every `HIGH`/`CRITICAL` permission maps to an
> audited action. The paragraph below records the original decision.

`@AuditedAction` + `AuditedActionAspect` exist, but are used in exactly two places:
`OrganisationSettingsService.updateSettings` and `BusinessDateService.advance`. Both are simple,
non-FSM mutations with a clean before/after snapshot. Lifecycle transitions keep recording audit
events explicitly through `recordLifecycleTransition`, unchanged in spirit from the existing FSM
design, because a transition needs an explicit reason and from/to state a generic method wrapper
cannot infer, and because introducing an AOP proxy around the FSM's generic types risks
resurrecting the Spring Modulith 2.1.0 observability-proxy recursion already documented in
`TransitionModuleConfiguration`. Existing manual audit call sites are left alone.

`AuditQueryService` adds the paginated read side (`listByTenant`, `listByEntity`, `listByActor`,
`listByActionAndDateRange`, `search`), following the existing `OrganisationListFilter`/
`OrganisationPage` pagination idiom rather than introducing Spring's unused `Pageable`/`Page<T>`.
Every query is tenant-scoped; there is no unscoped audit read path.

## Consequences

Every audit call, including ones made without a resolvable tenant, is durable and visible in logs
even in a misconfigured edge case. No caller can accidentally defeat redaction by forgetting to
call a utility function — it is not optional. `FoundationLifecycleService` now audits guard and
policy rejections (`DENIED` for `TransitionGuardException`, `FAILURE` otherwise) with `toState`
recorded as `"N/A"` since the aggregate never reached it. Keycloak provisioning and invite/
welcome-email dispatch now produce an `audit_event` row on both success and failure.

`@AuditedAction`'s narrow scope means most of the codebase's audit call sites remain explicit,
manual, and already tested — this ADR does not ask for a broad refactor toward annotation-driven
auditing.

## Alternatives Considered

Redact at read time instead of write time:

- Rejected. It would leave sensitive values sitting in the database between write and read,
  and would require every future read path to remember to redact — the opposite of a structural
  guarantee.

Drop audit events with no tenant instead of falling back to the platform organisation:

- Rejected. Silent drops are indistinguishable from working correctly; a bug that stops recording
  audit events for a whole code path would go unnoticed. A `WARN` log plus a durable row under the
  reserved platform organisation keeps it both durable and visible.

Apply `@AuditedAction` broadly across existing manual call sites:

- Rejected for this change. `RoleManagementService` and the lifecycle services already call
  `AuditService` explicitly, are already tested, and gain nothing from a proxy indirection.
  Converting them is unrelated churn and reintroduces the FSM proxy-recursion risk for lifecycle
  code specifically.

Use Spring's `Pageable`/`Page<T>` for the query service:

- Rejected for consistency. No controller in this codebase consumes `Pageable` yet; the
  established convention (`OrganisationListFilter`/`OrganisationPage`) is a bounded, explicit
  `page`/`size` filter and an `items`/`totalItems` response.

## Verification

- `AuditServiceTests`
- `JooqAuditEventRepositoryTests`
- `JooqAuditEventQueriesTests`
- `AuditQueryServiceTests`
- `HighRiskOperationAuditCoverageTests` (added 2026-08-02)
- `FoundationLifecycleServiceTests`
- `KeycloakUserProvisioningHandlerTests`
- `ApplicationInviteHandlerTests`
- Flyway migration `V1__foundation_schema.sql` (the `audit_event` table and its `reason`
  column, originally split across `V1` and `V4`)
