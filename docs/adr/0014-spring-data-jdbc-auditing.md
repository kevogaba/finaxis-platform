# ADR 0014: Spring Data JDBC Auditing Alongside jOOQ Write Paths

## Status

Accepted

Date: 2026-08-02

## Context

Every mutable table in the foundation schema carries the same four audit columns — `created_at`,
`created_by`, `updated_at`, `updated_by` — plus a `row_version` for optimistic locking. Something
has to populate them consistently, and "the developer remembers to set them" is not a mechanism.

The platform has two persistence styles. Spring Data JDBC provides declarative auditing via
`@EnableJdbcAuditing`, `@CreatedDate`/`@CreatedBy`/`@LastModifiedDate`/`@LastModifiedBy`, and
`@Version`. jOOQ provides type-safe SQL and is what every current production write path actually
uses, because the foundation's queries are tenant-filtered, paginated, and frequently need
composite-key predicates that read better as explicit SQL.

Both need the same actor. Requests carry an authenticated actor; background jobs, listeners, and
migrations do not.

## Decision

**Configure Spring Data JDBC auditing, and populate the same columns explicitly in the jOOQ write
paths.** Both routes resolve the actor identically.

`JdbcAuditingConfiguration` enables `@EnableJdbcAuditing` with two beans:

- `ContextAuditorAware` resolves `RequestContexts.actor()?.userId`, falling back to
  `SystemActor.ID` (`00000000-0000-0000-0000-000000000001`) when there is no request actor. It
  never returns empty, so an audit column is never silently null because a job had no request.
- `utcDateTimeProvider` supplies `clock.instant()` from the injected `Clock`, so timestamps are
  UTC and testable rather than reading the wall clock directly.

The jOOQ adapters set `CREATED_AT` / `CREATED_BY` / `UPDATED_AT` / `UPDATED_BY` explicitly on every
insert and `UPDATED_AT` / `UPDATED_BY` / `ROW_VERSION` on every update, using the same
`RequestContexts` actor or `SystemActor.ID`. The shared `AuditedJdbcAggregate` interface in
`FoundationJdbcEntities.kt` documents the column contract that both routes honour.

`row_version` is application-managed in the jOOQ paths — updates carry
`.set(X.ROW_VERSION, rowVersion + 1).and(X.ROW_VERSION.eq(rowVersion))` and treat a zero row count
as an optimistic-lock failure. Spring Data JDBC's `@Version` handles it automatically on the
entity route.

These row-level audit columns answer "when was this row last touched and by whom". They are **not**
the audit trail. Business and security events go to the separate append-only `audit_event` table
via `AuditService` (ADR 0007). The two are complementary: the columns tell you the current row's
provenance, the trail tells you the history of decisions.

## Current State, Stated Plainly

Every production write today goes through jOOQ. No Spring Data JDBC repository interface exists in
`src/main/kotlin`. The Spring Data JDBC auditing path is exercised by `JdbcAuditingIntegrationTests`
through `JdbcAggregateTemplate` against a real PostgreSQL container — so it is configured, working,
and proven, but not yet on a production code path.

`FoundationJdbcEntities.kt` carries a `@Table` entity for every mutable foundation table. Only
`OrganisationJdbcEntity` is currently persisted through — by `JdbcAuditingIntegrationTests`, which
proves the auditing wiring actually fires against a real table rather than merely being configured.

The others are **retained deliberately, as a readable schema reference.** A typed Kotlin data class
with accurate nullability is a far better answer to "what does this table hold" than 700 lines of
DDL, and the compiler checks it. Deleting them was considered and rejected: the readability value is
real, and the drift risk that motivated deletion is better solved by verification than by removal.

`guid` is deliberately **not** mapped on those entities. Spring Data JDBC omits unmapped columns
from its generated `INSERT`, so the database `DEFAULT uuidv7()` fills the column. Mapping it would
force a `null` into a `NOT NULL` column. Same reasoning applies to `id` — see ADR 0015.

## Consequences

Either persistence style can be used for a new aggregate without changing how audit columns are
populated or how the actor is resolved.

The cost is that the jOOQ route is manual, and a new adapter that forgets `CREATED_BY` will compile
and pass a naive test. The mitigation is the shared `now()` / actor helpers in each adapter and
review attention on new insert statements. If that proves insufficient, the escalation is a
schema-level `DEFAULT` or trigger, or moving the aggregate to Spring Data JDBC.

The entities cannot silently drift from the migrations. `FoundationJdbcEntitySchemaTests` reflects
over every `@Table` class, asserts the table exists, asserts every mapped property resolves to a
real column, and asserts every mutable application table has an entity. Adding a column to `V1`
without adding it here is still allowed; renaming or removing one, or adding an entity property
with no column behind it, fails the build. That converts the entities from documentation that
*claims* to match the schema into documentation that is *checked* against it.

The residual cost is a genuine one: a developer adding a column to `V1` should add it here too, and
nothing forces them to. That is a deliberate asymmetry — an entity that lags the schema is
incomplete but not wrong, whereas an entity that names a column the database does not have is
actively misleading, and only the latter fails.

## Alternatives Considered

Delete the Spring Data JDBC entities and auditing configuration entirely, since jOOQ does all the
writing:

- Rejected. The configuration is working and tested, not dead. Deleting it would remove a proven
  capability the moment a future aggregate wants declarative auditing, and would delete the only
  written record of the audit-column convention, and the entities are the most readable
  description of the schema the codebase has.

Trim the entities to only the one that is persisted through:

- Rejected. It would discard the readable schema reference, which is their main day-to-day value.
  The drift concern that motivated trimming is addressed by `FoundationJdbcEntitySchemaTests`
  instead.

Database triggers or column `DEFAULT`s for `created_at` / `created_by`:

- Rejected for the actor columns. The database cannot know the application actor without a session
  variable, which is more moving parts than an `AuditorAware`. Timestamps could default in the
  database, but splitting timestamp and actor across two mechanisms is worse than keeping both in
  one place.

Return `Optional.empty()` from `AuditorAware` when there is no request actor:

- Rejected. Null `created_by` would be indistinguishable from a bug. An explicit `SystemActor.ID`
  makes "the system did this" a first-class, queryable answer.

## Verification

- `JdbcAuditingIntegrationTests` — timestamps and `SystemActor.ID` written without a request
  context; the authenticated actor written when one is present
- `FoundationJdbcEntitySchemaTests` — every `@Table` entity matches a real table and real columns,
  and every mutable application table has an entity
- `ContextAuditorAwareTests` — actor resolution and system fallback
- `JooqFoundationLifecyclePersistenceTests`, `JooqIamAdministrationPersistenceTests` — audit
  columns populated on the jOOQ write paths
- ADR 0007 (append-only `audit_event`), ADR 0015 (identifier generation)
