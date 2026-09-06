# Project Guidance

Canonical rules for this repository. `AGENTS.md` points here; keep this file authoritative
and update it (not a copy elsewhere) when a rule changes. Prefer pointing to `docs/` over
restating detail here.

Kotlin-first Spring Boot Web MVC OAuth2 resource server on Java 25. Prefer Kotlin for
application code; do not add Java to new code without a specific framework/runtime reason,
and document that reason in the change.

Hexagonal layers: `domain` (framework-independent model/value objects), `application` (ports
and use-case services), `adapter` (persistence/web/security/messaging inbound & outbound),
`config`. Before changing code, ask whether the approach is overly complex; simplify while
preserving security boundaries and testability.

## Implementation status

Modules (`com.finaxis.platform`): `iam` (identity, authorization, active-organisation context,
roles, permissions, and user REST adapters), `lifecycle` (organisation/branch/user/membership
FSMs, tenant setup, business date, audit views, and REST adapters), `accounting`
(general-ledger boundary, posting contracts, the fiscal-calendar and chart-of-accounts schema
with their lifecycles, the journal schema, the synchronous posting engine, versioned posting rules,
reversal, control-account reconciliation and manual journals - see
`docs/architecture/accounting-module-boundary.md`), `notifications` (RabbitMQ
listener → JobRunr job), `common` (reusable transitions/audit/context/persistence/web infra),
`config`.

The membership-activation pipeline is the **reference event pattern** — copy it for new
domain events rather than inventing another mechanism: transition `eventFactory` →
`ExternalizedTransitionEvent` → Modulith/Namastack outbox → RabbitMQ (routed by the event's
`target`) → thin `@RabbitListener` → application service → JobRunr job. See
`docs/adr/0004-membership-activation-notification-pipeline.md`.

Known follow-ups (do not treat as bugs): `config` intentionally does not declare an
`@ApplicationModule` because it is infrastructure wiring rather than a domain module; JaCoCo
coverage verification is scoped to `iam` only; the 18 Spring Data JDBC entities in
`FoundationJdbcEntities.kt` are convention scaffolding, not live write paths (all production
writes use jOOQ) — see `docs/adr/0014-spring-data-jdbc-auditing.md`. `notifications` sends real
welcome and organisation-invite emails over SMTP; see
`docs/adr/0016-email-delivery-transport-and-retry-classification.md` and
`docs/architecture/email-delivery.md`.

## Database and identifiers

The greenfield window is **closed** — `V1`–`V3` are the frozen base and every further change is a
forward-only `V4+` migration. Never edit `V1`–`V3`.

- `V1__foundation_schema.sql` — all 23 application tables, constraints, indexes, comments
- `V2__platform_reference_data.sql` — 54-code permission catalogue, `PLATFORM` organisation, the
  two platform roles and their grants
- `V3__bootstrap_tenant_and_administrator.sql` — bootstrap tenant and first administrator
- `V4__grant_local_admin_invite_approve_and_seed_checker.sql` — grants `user.invite`/
  `user.approve`/`user.assign_branch` to the bootstrapped `local-admin` role, missing from `V3`,
  and seeds a second bootstrap actor (`local.checker`) holding the same role, since `local.admin`
  cannot approve its own invitations
- `V5__accounting_permission_catalogue.sql` — seeds the 26-code accounting permission catalogue
  under a new `accounting` module code in the `41000000-…` identifier block, grants them to
  `PLATFORM_SUPER_ADMIN`, and gives the bootstrap `local-admin` the tenant-configuration subset.
  Reference data only; see `docs/security/accounting-authorization.md`
- `V6__accounting_fiscal_calendar_and_chart_of_accounts.sql` — the first accounting schema:
  `accounting_fiscal_year`, `accounting_fiscal_period`, `gl_account` and the two transition logs,
  transcribed from `docs/database/accounting-erd.md`. Installs `btree_gist` into a dedicated
  `extensions` schema, because jOOQ codegen reads `inputSchema = "public"` and an extension there
  would be generated into `com.finaxis.platform.jooq` on every build
- `V7__accounting_journal_schema.sql` — the immutable double-entry kernel: `posting_request`,
  `journal_entry` and `journal_line`, transcribed from `docs/database/accounting-erd.md`, plus the
  `reference_sequence` backfill that gives the SQL-created `PLATFORM` and bootstrap organisations
  the `JOURNAL` counter gapless numbering locks on
- `V8__accounting_posting_rules.sql` — versioned, effective-dated posting rules: `posting_rule`,
  `posting_rule_version`, `posting_rule_leg`, their transition log, and the `posting_request`
  rule-version foreign key `V7` left for the table to exist
- `V9__accounting_control_accounts_and_reconciliation.sql` — `is_control_account` and
  `control_subledger_kind` on `gl_account`, the `control_account_reconciliation_run` evidence
  table, and `idx_journal_line_subledger`
- `V10__accounting_manual_journals.sql` — the manual-journal draft aggregate: `manual_journal`,
  `manual_journal_line` and their transition log; approval posts through the engine

Identifier rules, enforced by `IdentifierGenerationRuleTests`:

- `id` is the primary key, `UUID PRIMARY KEY DEFAULT uuidv7()`. **The application owns generation;
  clients never supply it.** Prefer the database default and read it back with
  `.returning(TABLE.ID).fetchOne()?.id`. Where an id is genuinely needed before the insert, use
  `uuidV7()` from `common.id` and say why in a comment.
- **Not every table has an `id`.** `api_idempotency_record` keys on
  `(scope_organisation_id, idempotency_key)` and `organisation_initial_administrator_bootstrap`
  keys on `organisation_id`. Neither has a `TABLE.ID` field to return; both still carry `guid`.
- `guid` is a unique alternate key, `UUID NOT NULL DEFAULT uuidv7()`, present on every application
  table. Clients **may** supply it; the default fills it when omitted. No API accepts one yet.
- **`UUID.randomUUID()` is banned in production code** — it is v4 and scatters index writes. Use
  `uuidV7()`.
- Never add an `id` field to a `*Request` DTO.
- jOOQ sources are generated from the migrations at build time; never hand-edit generated code.
- `outbox_record`, `event_publication`, and JobRunr tables are starter-managed, outside Flyway,
  and get no `guid`.

See `docs/database/foundation-schema.md`, `docs/adr/0010-...`, and `docs/adr/0015-...`.
`docs/database/accounting-erd.md` is the design authority every accounting migration implements,
and `docs/architecture/accounting-foundation.md` holds the invariants. `V6` created the fiscal
calendar and the chart of accounts from it, `V7` the journal tables, `V8` the posting-rule tables,
`V9` control accounts and their reconciliation evidence, and `V10` manual-journal drafts; the
projection table is still design-only. Do not invent accounting tables or columns outside those
documents.

## Authorization

- Keycloak authenticates users only. This app is an OAuth2 resource server: no
  application-managed passwords, password endpoints, password storage, or password checks.
  Smoke scripts may fetch dev-only Keycloak tokens; application code must not handle
  credentials.
- The application owns users, organisations, memberships, roles, permissions, scopes, and
  authorization rules. Runtime authorization evaluates **permission codes, never role names**.
- Active organisation is request/session context, not a permanent `app_user` field. The
  Redis-backed HTTP session carries active-organisation context only — never authentication
  (every request authenticates via the bearer JWT).
- Controllers use permission authorities for coarse gates; application services enforce
  resource-specific authorization.

## FSM / events / async

- Reusable transition infrastructure lives in `com.finaxis.platform.common.transitions`.
  Domain modules define their own state/transition enums, graphs, guards, policies,
  persistence adapters, and explicit domain events.
- Declare every legal source state, transition name, and target state in a
  `TransitionDefinition`; direction must be deterministic. Attach events via
  `eventFactories` — publish `InternalTransitionEvent` for in-process signals and
  `ExternalizedTransitionEvent` (with a `target`) for integration events.
- **Never** hand-write a module-private outbox table or ad-hoc broker publish. Externalize
  only through `eventFactories` + Spring Modulith + Namastack Outbox. The RabbitMQ exchange is
  chosen by a Namastack `RabbitOutboxRouting` bean keyed on the event `target` (the Modulith
  bridge drops the exchange), so a new externalized event needs a matching route.
- Keep RabbitMQ listeners thin: deserialize, validate, delegate to an application service,
  ensure idempotency (deterministic job/keys), ack/nack on outcome.
- Use JobRunr for durable background work (email, SMS, reports, imports/exports, retries,
  recurring). Do not use JobRunr as the outbox/externalization engine.
- Keep state mutation, transition validation, log creation, event publication, broker
  publishing, and background jobs separated.
- Read `docs/architecture/fsm-transitions.md` and
  `docs/adr/0002-fsm-transition-infrastructure.md` before touching transitions, events,
  Modulith boundaries, outbox, RabbitMQ, or background processing.

## Modules (Spring Modulith)

- Every module must declare its boundary in a `package-info.java` with `@ApplicationModule`
  and explicit `allowedDependencies`. New modules without it will fail Modulith verification.
- Spring Modulith `verify()` and ArchUnit hexagonal boundaries are first-class quality gates,
  equal to the static-analysis tools.
- Every `@ApplicationModule` `package-info.java` must carry a `BIAN:` line in its Javadoc, naming
  the BIAN Service Domain(s) it maps to with `(adopted)`/`(adapted)`, or `BIAN: none —` plus why
  the boundary is platform-specific. Enforced by `BianModuleMappingTests`. See
  `docs/architecture/bian-service-landscape.md` and
  `docs/adr/0017-bian-semantic-reference-architecture.md`.

## API governance

- All public endpoints versioned under `/api/v1`, `/api/v2`, … — never add an unversioned
  public endpoint. Document all public APIs with Springdoc/OpenAPI; route API errors through
  centralized exception handling.
- All listing APIs must paginate; never return unbounded collections.
- Use DTOs at API boundaries unless explicitly documented otherwise. Use Bean Validation for
  request DTOs and typed configuration properties.
- Update smoke tests, docs, and examples whenever endpoint paths change.
- See `docs/architecture/api-governance.md` and `docs/architecture/api-versioning.md`.

### Foundation REST contract

- `docs/api/foundation-api.md` is the canonical OpenAPI/REST contract reference for the
  foundation endpoints.
- Keep adapters thin and module-owned; inbound web adapters validate and delegate to application
  services instead of bypassing domain/application boundaries.
- Every endpoint must have an explicit application-layer permission check. The intentional
  exceptions are auth organisation/branch selection, checked inside `AuthSelectionService`, and
  tenant settings, checked per setting key inside `TenantSettingsService.authorize()`.
- Every collection endpoint is paginated, and every query must stay bounded and tenant-filtered.
- Every mutation is idempotent with optional/generated UUID `Idempotency-Key` handling.
- Tenant and branch context must be enforced before returning or mutating tenant data.
- Public JSON is `snake_case`; business dates use `dd-MM-yyyy`, times use `HH:mm:ss`, and
  datetimes use ISO-8601 offset format.

## Security, rate limiting, logging, audit

- Distributed rate limiting via Bucket4j + Redis (Lettuce-based, so standalone/Sentinel/
  Cluster stay options). No per-instance or in-memory-only limiting on production paths;
  rate-limit values must be configurable. See `docs/architecture/rate-limiting.md`.
- Preserve request correlation via `X-Request-Id` with MDC cleanup. Never log secrets, bearer
  tokens, passwords, authorization headers, session cookies, API keys, or sensitive PII.
- Audit admin actions and all critical state-changing operations through the common audit
  service or event listeners — not scattered in controllers. See
  `docs/architecture/audit-logging.md`.
- The `production` profile (`SPRING_PROFILES_ACTIVE=production`) turns on browser hardening:
  CORS (explicit origins), secure/strict session cookie, HSTS + CSP, and a fail-fast
  active-organisation secret (no default). API-docs (Scalar UI, OpenAPI document) unauthenticated
  access is a Spring Security gate (`finaxis.security.api-docs.public-access-enabled`), not a
  springdoc/Scalar enable flag — see `docs/security/production-hardening.md`. CSRF stays disabled
  because auth is bearer-JWT only. See also `docs/security/active-organisation-context.md`.
- Sentry is off for local development by default; enable it via environment config elsewhere.

## Testing

- Every change ships focused unit tests **and** Spring integration tests. Integration tests
  use Testcontainers (Postgres/Redis/RabbitMQ/observability) — never rely on manually running
  local infrastructure.
- Because the architecture is hexagonal, every module's public interfaces (inbound adapters
  and cross-boundary application ports) need regression-focused integration coverage. Prefer
  tests of stable external behavior over tests coupled to private implementation.
- Every new financial write path must register its durable effects as probes in
  `FinancialTransactionAtomicityFixture` and prove they commit or roll back together. See
  `docs/architecture/financial-transaction-atomicity.md` and
  `docs/adr/0018-financial-transaction-atomicity-invariant.md`.

## Pull requests and commits

**One pull request carries exactly one conventional commit over its base.** Squash before pushing;
do not stack fix-ups, review responses or merge commits on top. A reviewer reads one commit message
that describes the whole change, and `main` keeps one commit per unit of work.

**Prefer a stack of small pull requests over one large one.** When a unit of work has internal
sequence — a design record, then the migration that transcribes it, then the adapter that uses
it — split it there and branch each pull request off the previous one, rather than shipping the
whole thing as one reviewable lump. Each branch stays small enough to review on its own terms,
a reviewer can disagree with the design before the SQL exists, and a defect found late is
re-pushed to one branch instead of unpicked from a monolith. Branch off `main` only for the base
of the stack. Merge bottom-up, and rebase the rest of the stack after each merge.

Consequences worth stating, because they are where this rule is usually broken:

- Review feedback is folded into the existing commit by amending, not added as a follow-up commit.
  The history of *how* the change evolved belongs in the pull request conversation, not in `main`.
- A stacked pull request rebases onto its base rather than merging it, so the chain stays linear
  and each pull request's diff shows only its own work.
- Because rebasing rewrites history, push with `--force-with-lease`, never a bare `--force`: a
  concurrent change is then rejected rather than silently discarded.
- Every branch in a stack must compile and pass `./gradlew qualityGate` **on its own base**. Green
  at the top of a stack says nothing about the branches below it, and a branch can pass while
  asserting something that is not yet true of itself — a module listed as current before its
  descriptor exists, or a constant sized for a later branch.

## Static analysis & quality gates

**`./gradlew qualityGate` must pass locally before any push.** Not after, and not "CI will tell
me": a red pipeline costs a full cycle and a reviewer's attention to learn something the same
command answers in about fifteen minutes on the machine that made the change. Push only once it
comes back clean.

That gate runs the Testcontainers suites, so **the Docker daemon has to be up**. If `docker ps`
fails with a missing socket, start it (`dockerd` in the background, or the platform's service
manager) and wait for `docker ps` to answer before running the gate. A sandbox that ships the
Docker client with no running daemon looks exactly like a machine without Docker; check for the
daemon rather than assuming. Substituting an embedded database for the real containers is a last
resort, tells you less, and must be said out loud in the change if it happens.

Run before finalizing any change. Shortcut: `./gradlew qualityGate` (staticAnalysis + check +
JaCoCo verification + `bootJar`). Individually: `spotlessCheck`, `ktlintCheck`, `detekt`,
`checkstyleMain checkstyleTest`, `pmdMain pmdTest`, `spotbugsMain spotbugsTest`, `test`.

- Kotlin: Spotless (formatter), ktlint (via Spotless), Detekt (all rules, zero findings, KDoc
  required on public production classes/functions).
- Java: Checkstyle, PMD, SpotBugs, Error Prone, tests.
- Architecture-sensitive changes: ArchUnit **and** Spring Modulith verification must pass.
- Max line length 100 across Kotlin/Java/Gradle/YAML/XML/Markdown where feasible.
- Prefer cleanup over suppressions; keep any unavoidable suppression narrow and explained.
- Do not bypass, disable, weaken, or suppress these checks without documenting why.
- See `docs/development/static-analysis.md`.
