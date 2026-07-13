# Finaxis Platform — event pipeline, production security profile, docs refresh

Validated 2026-07-13 against the codebase by three independent exploration passes.

## Global Constraints

- Kotlin-first; hexagonal packages (`domain`, `application`, `adapter`, `config`).
- Runtime authorization uses permission codes, never role names.
- Line length 100; Spotless is the formatter; zero Detekt findings; KDoc required for
  public production Kotlin classes/functions.
- Every implementation task ships unit tests and (where a wired path changes) Spring
  integration tests using Testcontainers (`TestcontainersConfiguration.kt` already
  provides Postgres/Redis/RabbitMQ/LGTM `@ServiceConnection` beans).
- `./gradlew qualityGate` must pass at the end (staticAnalysis + check + JaCoCo
  verification (95% line, scoped to `com/finaxis/platform/iam/**`) + bootJar).
- ArchUnit `HexagonalArchitectureTest` and Modulith `ModulithArchitectureTest`
  (`ApplicationModules.verify()`) must pass; new modules need `package-info.java`
  with `@ApplicationModule(allowedDependencies = ...)`.
- Greenfield migration convention: edit `V1__create_iam_schema.sql` in place; no new
  migration for removals.
- Do not log secrets/tokens/PII. Audit through the common audit service, not controllers.

## Task 1 — Remove the dead outbox; publish real transition events

Delete the competing outbox (zero readers confirmed):
- `lifecycle/application/FoundationLifecyclePersistence.kt`: remove
  `LifecycleOutboxEventStore` (lines ~71-74) and `LifecycleOutboxEvent` (~77-85).
- `lifecycle/adapter/outbound/persistence/JooqFoundationLifecyclePersistence.kt`:
  remove `enqueue(...)` (~308-324), the `OUTBOX_EVENT` import (line 13), and the
  `LifecycleOutboxEventStore` supertype.
- `lifecycle/application/FoundationLifecycleService.kt`: remove the `outbox`
  constructor param, `recordOutcome`'s outbox branch, `eventType`/`lifecycleEvents`
  map, and `roleAssigned`/`branchAssigned`/`enqueueAssignmentEvent` (no production
  callers; delete their tests in `FoundationLifecycleServiceTests.kt`).
- `common/persistence/FoundationJdbcEntities.kt`: remove the unused
  `OutboxEventJdbcEntity` (~342-362).
- `V1__create_iam_schema.sql`: remove the `outbox_event` table, its two indexes, and
  comment (lines ~489-516). Update `FoundationSchemaMigrationTests.kt` (lines ~35, 58)
  to stop asserting the table exists.
- Regenerate jOOQ classes if the build requires it (`OUTBOX_EVENT` generated table
  becomes stale); follow whatever the existing codegen Gradle task is.
- Namastack (`namastack-outbox-starter-jdbc` 1.7.1) provisions its own schema; do not
  add Flyway DDL for it. Verify at build time; if it *does* need DDL, add a new
  versioned migration (do not edit V1 for that).

Wire events (infrastructure already exists and is verified working in
`TransitionExecutor.execute()` / `TransitionModuleConfiguration`):
- `lifecycle/domain/FoundationLifecycleDefinitions.kt`: the private `definition()`
  helper currently never sets `eventFactories`. Add factories so every transition that
  had an entry in the deleted `lifecycleEvents` map publishes an
  `InternalTransitionEvent`: org SUBMIT/START_PROVISIONING/ACTIVATE/SUSPEND/
  START_DEPROVISIONING, branch ACTIVATE/SUSPEND, user START_IDP_PROVISIONING/INVITE/
  ACTIVATE/SUSPEND, membership INVITE/SUSPEND (keep parity with the old map).
- `MembershipLifecycleTransition.ACTIVATE` instead publishes an
  `ExternalizedTransitionEvent` with `target = "finaxis.lifecycle.membership.activated"`
  and metadata: membershipId, userId, organisationId, branchId, occurredAt. userId is
  resolved via `reader.membershipUserId(organisationId, membershipId)` — the event
  factory receives the transition context; thread userId through the same way the old
  outbox metadata was built in `recordOutcome`.
- `RoutingTarget.forTarget(target).withoutKey()` (already configured) means the target
  string is the AMQP **exchange name** with an **empty routing key**.

Tests: update `FoundationLifecycleServiceTests.kt` and
`JooqFoundationLifecyclePersistenceTests.kt`; assert transitions publish the expected
`InternalTransitionEvent`/`ExternalizedTransitionEvent` (use a recording
`TransitionEventPublisher` or Spring's `ApplicationEvents` as existing tests do).

## Task 2 — `notifications` module: RabbitMQ listener → JobRunr stub

New top-level module `com.finaxis.platform.notifications`:
- `package-info.java`: `@ApplicationModule(displayName = "Notifications",
  allowedDependencies = {"common::transitions"})` (add `common::context` only if used).
- `application/NotificationService.kt` + `application/WelcomeEmailCommand.kt`: map
  event → command, call outbound port.
- `application/port/outbound/WelcomeEmailScheduler.kt`: port interface
  `scheduleWelcomeEmail(command)`.
- `adapter/inbound/messaging/MembershipActivatedNotificationListener.kt`:
  `@RabbitListener` on queue `finaxis.notifications.membership-activated`; thin —
  deserialize JSON body into `ExternalizedTransitionEvent` with a locally injected
  Jackson `ObjectMapper` (no global converter change), validate required metadata
  fields, delegate to `NotificationService`, let exceptions propagate (Spring AMQP
  default nack/requeue).
- AMQP topology `@Bean`s (fanout exchange `finaxis.lifecycle.membership.activated`,
  durable queue, binding) in a `@Configuration` inside the notifications module
  (adapter/inbound/messaging).
- `adapter/outbound/jobrunr/JobRunrWelcomeEmailScheduler.kt`: implements the port with
  JobRunr `JobRequestScheduler`, enqueuing with a deterministic job UUID
  (`UUID.nameUUIDFromBytes` over `membershipId:transition:occurredAt`) so redelivered
  messages don't double-enqueue.
- `adapter/outbound/jobrunr/SendWelcomeEmailJobRequest.kt` +
  `SendWelcomeEmailJobRequestHandler.kt`: handler logs
  "would send welcome email" with userId/organisationId (no PII beyond IDs); email
  integration is a documented follow-up.
- `application.yaml`: JobRunr block — background-job-server enabled, dashboard enabled
  (local), database managed by JobRunr on the existing datasource.
- Unit tests: NotificationService, JobRunrWelcomeEmailScheduler (mock scheduler,
  assert deterministic id), handler (log/behavior), listener
  (deserialize/validate/delegate; malformed payload rejected).

## Task 3 — End-to-end integration test

One Spring Boot + Testcontainers test: execute membership ACTIVATE through
`FoundationLifecycleService` → Namastack outbox externalizes to RabbitMQ →
listener consumes → JobRunr job enqueued and processed → assert the stub behavior
(e.g. via a test-observable seam or log capture) with Awaitility-style polling.
Reuse `TestcontainersConfiguration`. This is the required regression coverage for the
new module's inbound adapter. Note: JobRunr dashboard/server may need enabling in the
test profile; keep polling intervals tight for test speed.

## Task 4 — Production security profile

- New `src/main/resources/application-production.yaml`:
  - `finaxis.iam.active-organisation-context.secret:
    ${FINAXIS_ACTIVE_ORGANISATION_CONTEXT_SECRET}` (no default → fail-fast).
  - `server.servlet.session.cookie.secure: true`, `same-site: strict`.
  - `spring.devtools.restart.enabled: false` (devtools is a dev-only dependency but be
    explicit), `spring.docker.compose.enabled: false`.
  - `springdoc.api-docs.enabled: false`, `scalar.enabled: false`.
  - HSTS + CSP enabled via properties consumed by the security config (see below).
  - `finaxis.security.cors.enabled: true` with `allowed-origins:
    ${FINAXIS_CORS_ALLOWED_ORIGINS}` (no default → explicit deployment decision).
- Base `application.yaml`: `scalar.telemetry: false`; add `finaxis.security.cors`
  defaults (`enabled: false`).
- New `@ConfigurationProperties` (validated, following `ActiveOrganisationContextProperties`
  pattern): `finaxis.security.cors` (enabled, allowed-origins, allowed-methods,
  allowed-headers, allow-credentials) and `finaxis.security.headers` (hsts-enabled,
  content-security-policy) — placed in `iam/adapter/inbound/security` (or
  `common/web` if ArchUnit prefers; match existing conventions).
- `SecurityConfiguration.securityFilterChain`: add `.cors { }` wired to a
  `CorsConfigurationSource` bean (active only when enabled), and a `.headers { }`
  block — content-type-options, frame-options DENY, referrer-policy
  `strict-origin-when-cross-origin` always; HSTS and CSP (`default-src 'none'`) only
  when the properties enable them (production).
- CSRF stays disabled — authentication is bearer-JWT only; the Redis session carries
  active-org context, never authentication. Document this.
- Tests: unit/web-slice tests for header presence and CORS behavior (enabled vs
  disabled); a config test that the production yaml parses (e.g. context run with the
  profile and required env vars supplied as test properties).

## Task 5 — Docs: CLAUDE.md canonical, AGENTS.md pointer, README, ADR, sync

- `CLAUDE.md` (canonical, short): keep the rule sets but tighten: forbid
  module-private outbox tables (use `TransitionEventFactory` + Modulith
  externalization + Namastack); new modules must have explicit `package-info.java`
  (`iam`/`config` are known implicit-module gaps); production profile expectations
  (CORS/CSRF/headers/secrets → point to `docs/security/production-hardening.md`);
  audit rule covers all state-changing endpoints; current implementation status
  (modules: iam, lifecycle, notifications, common, config; membership-activation
  pipeline is the reference event pattern — copy it); point to docs/ instead of
  repeating content.
- `AGENTS.md`: shrink to Kotlin/Java-25/hexagonal basics + Keycloak-authenticates/
  app-authorizes + permission-codes-not-roles + "authoritative rules live in
  CLAUDE.md; read it before changes; keep in sync". Fix/remove the stale
  "Kotlin 2.3.21" (catalog pins 2.4.0).
- `README.md` (new): overview (SACCO/core-banking modular monolith, per
  docs/development/common-project-context.md), architecture/module map + status
  caveat, tech stack table (verified versions: Kotlin 2.4.0, Spring Boot 4.1.0,
  Java 25, jOOQ 3.21.6, Modulith 2.1.0, Namastack 1.7.1, JobRunr 8.7.1, Bucket4j
  8.19.0, springdoc 3.0.3), prerequisites (.sdkmanrc java 25.0.2-graalce, Docker),
  compose service/port table (postgres 5433, rabbitmq 5673/15672, redis 6379,
  keycloak 8080/9000, grafana-lgtm 3000/4318), getting started (compose up →
  bootRun → scripts/local-smoke.sh, app on 8081, Keycloak admin/admin,
  local.admin/local-admin), API docs (/docs Scalar, /v3/api-docs) + versioning,
  testing, quality gates (qualityGate + Qodana CI), docs index table, no LICENSE
  (omit license section). Every claim verified against the repo.
- Delete `HELP.md` (Spring Initializr leftover).
- `docs/adr/0004-membership-activation-notification-pipeline.md`: the reference
  pipeline pattern.
- `docs/security/production-hardening.md`: CORS/CSRF/session/headers/secrets
  reasoning (incl. why CSRF stays off and what the session actually carries).
- Sync: remove `outbox_event` from `docs/architecture/foundation-implementation-plan.md`
  (~line 159) and `docs/database/foundation-schema.md` (~lines 37, 55); describe the
  new event path in `docs/architecture/lifecycle-fsm.md`; fix "Kotlin 2.3.21" in
  `docs/development/static-analysis.md` (2 places).
- Documented follow-ups (list in README status or ADR): no inbound adapter yet for
  lifecycle transitions (activation currently test-only), email provider integration,
  explicit `@ApplicationModule` for iam/config, JaCoCo scope extension beyond iam.

## Verification (final)

- `./gradlew qualityGate` passes.
- Integration test proves: ACTIVATE → ExternalizedTransitionEvent → Namastack →
  RabbitMQ → listener → JobRunr → stub executed.
- `./gradlew bootRun` starts clean with compose infra (JobRunr dashboard reachable);
  production profile fails fast without required env vars (assert via test, not manual).
- CLAUDE.md vs AGENTS.md: no duplicated/contradicting rules.
- README claims cross-checked against compose.yaml / version catalog / docs filenames.
