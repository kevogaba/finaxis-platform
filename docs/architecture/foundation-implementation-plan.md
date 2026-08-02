# Foundation Implementation Plan

**Status:** implemented and verified on 2026-07-13; amended 2026-08-02 for the greenfield
migration reset. This document retains the original audit decision and records the completed
foundation outcome. It is a historical record of *why* the foundation was built this way, not a
description of the current schema — for that see
[Foundation schema](../database/foundation-schema.md) and the ADRs in `docs/adr/`.

## Scope and decision

This foundation establishes organisation isolation, global user identity, identity-provider links,
role-based permissions, lifecycle persistence, durable audit storage, the transactional outbox,
settings, business date, JDBC auditing, and request context. It deliberately excludes controllers
and banking-product modules.

The recommended approach was to **replace the then-current Flyway migrations while the project is
greenfield**. The
current migrations were created for the local IAM smoke path, but cannot safely evolve into the
required schema without temporary compatibility columns, placeholder branch scopes, nullable role
ownership, and a second migration wave. A single coherent baseline is safer and more maintainable.

This is safe only if all of the following hold:

- no production, customer, or legally retained data exists;
- no shared environment has data that must be preserved;
- every local developer resets the `platform` database after pulling the baseline;
- the Keycloak import and local smoke seed are updated atomically with the new schema.

Local reset for the Compose-managed development stack:

```bash
docker compose down -v
docker compose up -d postgres redis rabbitmq keycloak
./gradlew bootRun
./scripts/local-smoke.sh
```

Do not run the reset command against a non-local environment. If any durable data exists, stop and
replace this greenfield decision with forward-only V3+ migrations.

## Current repository audit

### Structure and existing foundations

- Kotlin-first Spring Boot Web MVC application on Java 25 with Spring Data JDBC, Flyway, Spring
  Security resource-server support, Spring Modulith, Namastack Outbox, RabbitMQ, Redis, JobRunr,
  jOOQ, Testcontainers, ArchUnit, and strict static-analysis tasks.
- `common.transitions` already provides deterministic graphs, transition definitions, guards,
  policies, effects, a log port, a transition executor, Spring event publication, and selective
  Modulith/Namastack externalization. It has focused unit tests but no durable log adapter.
- `common.audit` has a write-only application service and log-only fallback repository. It has no
  Flyway table, JDBC adapter, before/after payloads, correlation ID, or durable query path.
- `iam` currently owns the app-user, organisation, membership, permission, role, context, JWT-to-
  principal conversion, and effective-permission read path. It is a useful compatibility target,
  but currently mixes the future `identity`, `iam`, and `tenancy` boundaries.
- Security is an OAuth2 resource server. It resolves a Keycloak subject plus signed/session active
  organisation context into `AppPrincipal`; permissions are local application permissions. This
  separation is correct and must be preserved.
- AMQP, Modulith outbox mode, Namastack Rabbit support, publisher confirms, and Testcontainers
  RabbitMQ dependencies are configured. There are no named lifecycle integration events, outbox
  schema ownership checks, listeners, or broker routing-contract tests yet.
- Request IDs are logged through `HttpAccessLogFilter`; the filter does not establish tenant,
  branch, actor, or correlation context for the whole request lifecycle.

### Flyway baseline (as audited in July 2026, before the rewrite)

The migration names in this subsection describe the **pre-rewrite** baseline this plan set out to
replace. None of them exist today; see
[ADR 0010](../adr/0010-greenfield-migration-reset-and-schema-rewrite.md) for the current
three-file layout.

`V1__create_iam_schema.sql` was intentionally minimal: `app_user`, `organisation`, membership,
permissions, roles, role assignments, placeholder branch/warehouse scopes, and branch. It lacks
the requested organisation fields, tenant-safe relationships, identity-link separation,
optimistic-lock columns, lifecycle/audit/outbox/settings/business-date tables, lifecycle checks,
and most foreign-key indexes. `V2__seed_local_iam_smoke_data.sql` depends exactly on those names
and columns.

The rewrite will retain the fixed local IDs and logical seed identities where practical, update the
Keycloak-compatible local admin seed, and keep `/api/v1/auth/me` as an end-to-end compatibility
check. It will not preserve the old physical schema.

### Gaps

| Area | Current gap | Foundation outcome |
| --- | --- | --- |
| Schema | No complete organisation ownership model; branch, assignment, setting, business-date, identity-link, lifecycle, and audit tables are absent. | One PostgreSQL baseline with tenant-safe FKs, checks, comments, indexes, and seed data. |
| Lifecycle | Common FSM is unbound to real aggregates and logs only through an in-memory/logging port. | Organisation, branch, user, and membership graphs with persistence-backed logs and guards. |
| Security | Keycloak subject and selected context are resolved, but user/membership lifecycle status and branch/role assignment state are not uniformly enforced. | Context-resolution port plus explicit active status checks and tenant-safe access patterns. |
| Audit | Audit events are only structured logs. JDBC auditing is not enabled. | Auditing callbacks, durable audit-event adapter, system actor, and contextual MDC. |
| Events/outbox | Transport dependencies/config exist but no lifecycle event contracts or durable business outbox records exist. | Explicit Modulith events selected for Namastack/Rabbit externalization and integration tests. |
| Documentation | FSM and context ADRs exist; no foundation ERD, auditing/context guide, lifecycle diagrams, or final boundary map. | Schema ERD, auditing/context guide, lifecycle FSM diagrams, updated local-reset instructions. |

## Module boundaries as shipped

The original plan targeted eight Spring Modulith modules — `shared/kernel`, `tenancy`, `identity`,
`iam`, `lifecycle`, `audit`, `integration`, and `settings`. **That decomposition was not adopted.**
Five modules shipped instead, and the simpler structure is the one to build on:

```text
common          <- iam, lifecycle, notifications
iam             <- lifecycle (via explicit allowed dependencies)
lifecycle       <- notifications (only through externalized events, never internals)
config          infrastructure wiring; deliberately not an @ApplicationModule
```

| Module | Owns |
| --- | --- |
| `common` | Reusable transitions, audit, request context, persistence conventions, web infrastructure (pagination, idempotency, rate limiting, versioning, error handling) |
| `iam` | Identity, authorization, active-organisation context, roles, permissions, user REST adapters |
| `lifecycle` | Organisation, branch, user, and membership FSMs; tenant setup; business date; tenant settings; audit views; REST adapters |
| `notifications` | RabbitMQ listener and JobRunr welcome-email job |
| `config` | Application configuration and infrastructure wiring |

The eight-module split was abandoned because it would have separated `tenancy`, `identity`, and
`settings` into modules that share the same aggregates and transaction boundaries, forcing
cross-module calls for operations that belong in one transaction. Splitting further is a decision
to revisit only when a module genuinely needs an independent deployment or release cadence.

Each module declares its boundary in `package-info.java` with `@ApplicationModule` and explicit
`allowedDependencies`. `ModulithArchitectureTest` and `ModuleDependencyRuleTests` enforce the
direction.

## Implementation sequence

1. Replace the original migrations, write a Testcontainers Flyway migration test first, add the
   complete foundation schema plus seed, and document the ERD in
   `docs/database/foundation-schema.md`. (Superseded by the 2026-08-02 three-file reset —
   see [ADR 0010](../adr/0010-greenfield-migration-reset-and-schema-rewrite.md).)
2. Split persistence records into their owning modules. Add explicit JDBC column mappings,
   `@Version`/`row_version`, tenant-scoped repository APIs, and test cross-organisation rejection.
3. Enable Spring Data JDBC auditing. Supply the current actor through an `AuditorAware<UUID>`, UTC
   time through `DateTimeProvider`, and a stable system actor for migration, outbox, and job paths.
4. Add request-scoped tenant, branch, actor, and correlation contexts; populate/clear MDC in the
   servlet chain; keep the existing signed/session active-organisation transport as the adapter.
5. Add durable audit and transition-log adapters, then make the existing FSM drive organisation,
   branch, user-account, and membership lifecycle services. Each transition runs in one
   transaction, persists aggregate plus log, records audit, and publishes an explicit event.
6. Route only named lifecycle events through Modulith/Namastack to RabbitMQ. Add producer/outbox
   integration tests; do not send with `RabbitTemplate` in lifecycle services.
7. Add lifecycle diagrams in `docs/architecture/lifecycle-fsm.md`, re-run module verification,
   migration tests, full tests, and `./gradlew qualityGate`.

## Lifecycle design decisions to implement

- Use text columns with named `CHECK` constraints rather than PostgreSQL enum types. The state
  vocabulary will evolve; forward-only checks are simple to alter and map directly to Kotlin enums.
- Use a global `user_account` and a separate `keycloak_identity_link`; a user can have multiple
  organisation memberships but never carries a permanent active-organisation field.
- Every tenant-scoped FK includes `tenant_id` in the child. Composite unique keys on
  `(tenant_id, id)` in parent tables support tenant-safe foreign keys such as membership primary
  branch, branch assignment, role assignment, and transition logs.
- Lifecycle logs are append-only per aggregate: `organisation_transition_log`,
  `branch_transition_log`, `user_account_transition_log`, and
  `user_organisation_membership_transition_log`. The organisation log uses a nullable branch ID;
  branch-bound logs require it. A generic log table is not sufficient for traceable ownership.
- `audit_event` remains append-only and can reference any entity. Audit event payloads exclude
  credentials, bearer tokens, cookies, and sensitive PII.
- Transition `eventFactories` publish in-process events or selected externalized events. Spring
  Modulith and Namastack perform the transactional externalization path; do not build a competing
  application-owned outbox publisher.

## Verification plan

- Pure unit tests: every legal/illegal FSM direction and guard, audit/context providers, system
  actor, tenant-safe lookup contracts, and event factories.
- JDBC/Testcontainers integration tests: Flyway baseline applies from empty PostgreSQL; audit
  columns are populated; `row_version` detects concurrent writes; composite tenant FKs reject
  cross-organisation writes; durable audit and transition logs are written.
- Spring integration tests: security-to-context resolution, inactive/suspended user and membership
  rejection, Modulith boundaries, selected externalization contracts, and local auth smoke
  compatibility.
- Final quality gate: `./gradlew qualityGate` plus `./scripts/local-smoke.sh` against a reset local
  stack.

## Implementation and verification result

The greenfield schema baseline, Spring Data JDBC auditing/context, tenant-safe jOOQ persistence
adapters, durable audit records, and the four lifecycle services are implemented. The existing FSM
executor is reused for all status mutation; each transition writes its log and audit event, then
publishes events through transition `eventFactories` for Modulith/Namastack externalization.

`./gradlew --no-daemon --max-workers=1 qualityGate` passed on 2026-07-13. It ran PostgreSQL jOOQ
code generation, compilation, `bootJar`, Checkstyle, Detekt, PMD, SpotBugs, Spotless, ktlint,
the full test suite (including Spring Modulith and ArchUnit verification), JaCoCo reporting, and
the configured IAM 95% line-coverage verification. No JUnit failure or error markup was
present in `build/test-results/test` after the run.

## Next steps

- [x] Confirm the greenfield/no-production-data assumption before replacing the migrations.
- [x] Implement and verify the schema baseline, auditing/context, tenant-safe persistence, and
  lifecycle foundation.
- [x] Run the full quality gate, including architecture and JaCoCo verification.
- [ ] Before the first non-local deployment, capture the greenfield assumption in release
  approval and prohibit destructive Flyway reset commands there.
- [ ] Add public API controllers only through versioned DTO boundaries and the established
  lifecycle application ports; do not bypass lifecycle services.
