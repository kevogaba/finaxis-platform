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
coverage verification is scoped to `iam` and `accounting`, each with its own 95% task, since a
`BUNDLE` rule cannot be scoped by package; the 18 Spring Data JDBC entities in
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
- `V11__accounting_control_account_uniqueness.sql` — replaces `idx_gl_account_control` with the
  unique `uq_gl_account_control_kind`, so a tenant has at most one control account per sub-ledger
  class. `SubledgerProofQuery` names the class, not the account, so a second one of a class could
  only ever be proven against an aggregate that is not its own
- `V12__accounting_manual_journal_external_reference.sql` — `manual_journal.external_reference`:
  one bounded column, so a document number has somewhere to live other than inside the narrative
- `V13__accounting_journal_line_append_guard.sql` — `fn_journal_line_append_guard` and
  `trg_journal_line_append_guard`, **the repository's first and only trigger**: an
  `AFTER INSERT … FOR EACH STATEMENT` guard on `journal_line` refusing any statement that leaves a
  journal holding more lines than its header's `line_count` declares. It closes the late append
  `REVOKE UPDATE, DELETE` cannot reach; because `line_count` is itself mutable, it is complete only
  once #54 lands. A trigger is admitted here only against the five conditions in
  `docs/adr/0024-journal-line-append-guard-and-trigger-policy.md` — the old "this repository has
  zero triggers" rule is withdrawn, and `V7`'s frozen header comment saying otherwise is superseded
  by `V13`'s own header
- `V14__accounting_gl_account_daily_balance.sql` — `gl_account_daily_balance`, the **one** derived
  balance projection the accounting foundation approves, plus `idx_journal_entry_business_date`
  (which its incremental build enumerates through, and which carries `posting_date` in `INCLUDE`
  for the reader) and `idx_gl_account_daily_balance_watermark`. Sparse (written only for an account,
  branch, currency and posting date that moved) and carrying an opening balance forward, so an
  as-of balance is a checkpoint plus a bounded journal delta rather than a scan from inception.
  The checkpoint is taken strictly **before** the earliest posting date any journal recorded since
  the watermark touches, never at the latest projected posting date: a posting backdated onto an
  already-projected day would otherwise be invisible to both halves of the read. Never
  authoritative: it is rebuildable from `journal_line` by a documented query, and when the two
  disagree the journal wins. Built by the business-date **advance**, not by close-of-business
  completing, since a backdated posting is legal while the date is `CLOSED` — see
  `docs/adr/0027-derived-balance-projection-and-its-build-trigger.md`

- `V15__accounting_branch_trial_balance_index.sql` — `idx_journal_line_branch_account_date`
  `(organisation_id, branch_id, posting_date, gl_account_id) INCLUDE (direction,
  functional_amount)`: the branch-scoped trial balance's own aggregate. The account-leading
  `idx_journal_line_account_date` answers a *tenant-wide* report, and asked for one branch it has
  to scan every account's whole window and discard the other branches; this one leads with the
  branch, so the report is a single index-only range scan of exactly that branch's rows
- `V16__accounting_posting_request_fingerprint_comment.sql` — one `COMMENT ON COLUMN`, no schema
  change: restates `posting_request.request_fingerprint` as the ADR 0023 digest of the caller's
  asserted inputs, never the resolved legs, superseding `V7`'s frozen comment that listed the legs
- `V17__accounting_posting_rule_version_cancelled.sql` — widens `chk_posting_rule_version_status`
  for `CANCELLED`, the terminal state a withdrawn draft moves to, so a draft whose author can no
  longer act on it does not block its rule's next version for good
- `V18__branch_opened_closed_on_backfill.sql` — a **data-only, best-effort backfill** of
  `branch.opened_on` and `branch.closed_on` for branches that predate the release that began
  stamping them on transition (#165). Where the column is still `NULL` it derives the date from the
  earliest `branch_transition_log` row entering `ACTIVE` / `CLOSED`, as that row's `created_at`
  converted to a date in the **organisation's** timezone (a zone not in the IANA list falls back to
  UTC; this includes offset ids such as `+03:00`, `UTC+03:00` or `GMT+3`, which can be a day off).
  The dates are **approximate** — a wall-clock day, not the business date at that moment — and a
  branch with no matching log row stays `NULL`. A derived `closed_on` earlier than `opened_on` is
  clamped up to it (and a derived `opened_on` later than a stored `closed_on` down to it) so
  `chk_branch_dates` can never fail the upgrade. Idempotent; writes no other column, not even
  `updated_at` or `row_version`
- `V19__branch_update_permission.sql` — **reference data only, no schema** (#203): seeds the
  `branch.update` permission (module `branch`, risk `HIGH`, id `40000000-…-000000000064`, the next
  free id after the highest the frozen `V2` foundation block uses) that now gates
  `PATCH /api/v1/branches/{id}` and `BranchProvisioningService.update` in place of `branch.create`,
  so a custom maker-only role can no longer edit a live branch with no checker. The grant-copy
  rule: `PLATFORM_SUPER_ADMIN` is granted it, and **every role (and every direct
  `membership_permission` override, with the same `ALLOW`/`DENY` effect) that holds `branch.create`
  at migration time is given it too**, so nobody who could `PATCH` before silently loses the
  ability; a role without `branch.create` receives nothing, and the bootstrap `local-admin` (which
  never held `branch.create`) and `PLATFORM_SUPPORT` are unchanged. After it runs the two codes are
  independent: to take `PATCH` away from a role, revoke `branch.update`; revoking `branch.create`
  no longer does. New organisations get it through `OrganisationBootstrapDefaults` on
  `TENANT_ADMIN` and `BRANCH_MANAGER` only. The new code takes `branch.create`'s status, not a
  literal `ACTIVE`, so a non-active `branch.create` cannot be widened by the copy. Idempotent
  (`ON CONFLICT DO NOTHING`), asserts its preconditions and post-conditions in-file, and
  supersedes the #165 docs' "reuses `branch.create`" — see `docs/security/authorization-model.md`
  ("Branch update"). A permission migration bypasses the Redis `iam.effective-permissions` cache
  (no TTL), so from `V19` that cache is **namespaced by the applied schema version**
  (`iam.effective-permissions:v<N>::…`, so an old rolling-deploy instance never feeds a stale set
  to a new one) **and cleared on every start** (`EffectivePermissionCacheStartupClearer`, after
  Flyway, before traffic; a Redis outage then is a logged warning, not a failed boot): the new
  permission is effective on a new instance's first request with no operator action. SQL run
  outside Flyway needs the cache flushed (restart, or delete `iam.effective-permissions:*`)
- `V20__platform_organisation_stays_active.sql` — **one table CHECK, no data** (#205):
  `chk_organisation_platform_always_active` on `organisation`,
  `id <> '00000000-…-000000000000' OR status = 'ACTIVE'`, so the reserved `PLATFORM` organisation
  can never leave `ACTIVE` (a suspended or deprovisioned platform organisation refuses every
  platform principal, and deprovisioning revokes every platform membership; recovery would be only
  by SQL). It is the database layer of three: `OrganisationProvisioningService` refuses the
  platform organisation on every tenant-id method with a 409
  `lifecycle.platform_organisation_protected`, after recording a durable `DENIED` audit row
  (`AuditService.recordIndependently`, so the rollback cannot take it), and the organisation
  transition graph carries a guard on every edge, so each holds without the others. It
  constrains one row and nothing else (every tenant keeps the full lifecycle), asserts in-file
  that the platform row exists and is `ACTIVE` before adding the constraint (which validates
  existing rows), and fails an offending
  `UPDATE` with SQLSTATE 23514 naming the constraint. It is a declarative CHECK, not a trigger, so
  ADR 0024 does not apply, and it does not cover `DELETE`, which no code performs and the foreign
  keys refuse while the seeded platform roles exist. Supersedes nothing — see
  `docs/security/authorization-model.md` and `docs/architecture/lifecycle-fsm.md`
- `V21__branch_approve_permission.sql` — **reference data only, no schema** (#208):
  `branch.approve` is now THE permission that approves a pending branch (tenant and platform
  `/activate`, and the checker's half of `/return`; a maker's withdraw stays `branch.create`,
  reactivating a suspended branch `branch.reactivate`), and `branch.activate` is
  **DEPRECATED**: runtime honours only `ACTIVE` permissions and no route checks it. It adds no
  code, so no id is allocated. Approval authority is **rebuilt from `branch.activate`**: while
  it is `ACTIVE`, every pre-existing `branch.approve` row, which conferred nothing because
  nothing checked it, is **discarded** (role grants and membership overrides, `ALLOW` and `DENY`
  alike, that have no `branch.activate` counterpart), then every `role_permission` row (system
  roles of existing tenants included) and every `membership_permission` override of
  `branch.activate` is copied with the same effect, and `branch.approve` is repaired to `ACTIVE`.
  Every principal's effective approval ability is therefore identical before and after: nobody
  widened, nobody narrowed, and an admin who composed an approver role around the dead code must
  re-grant it knowingly. While it rebuilds, V21 takes `LOCK TABLE role_permission,
  membership_permission IN SHARE ROW EXCLUSIVE MODE`, so grant writes by instances of the old
  release wait for the commit instead of being copied stale. A `DISABLED` source disables
  `branch.approve` and discards nothing. A `DEPRECATED` source with `branch.approve` `ACTIVE`
  **raises whatever the rows say** (the old runtime authorized nobody through a deprecated
  code, and matching rows cannot prove V21 completed earlier, so accepting them would open
  approval to every holder at once; the message says to disable `branch.approve` by hand or
  re-activate `branch.activate` and run again), which also makes a hand re-run refuse. The
  `branch.activate` rows are left in place, harmless. The effective-permission cache is
  namespaced by schema version and cleared at start by the mechanism introduced with V19.
  During a rolling deploy, old instances check the deprecated `branch.activate` and refuse
  approvals until replaced: fail-safe. The readiness
  check behind tenant reactivation counts only the codes of the current bundle and ignores
  extra rows, so `branch.activate` simply leaves `OrganisationBootstrapDefaults` (new tenants
  get `branch.approve` and not the deprecated code) and existing tenants stay ready. Asserts its
  preconditions and post-conditions in-file; supersedes the docs that named `branch.activate`
  as the checker's permission — see `docs/security/authorization-model.md` ("Branch approval")
  and ADRs 0028 and 0029
- `V22__permission_catalogue_metadata.sql` — **catalogue metadata, no grant changes** (ADR 0030
  decisions 7 and 8): adds `permission.kind` (`VIEW`, `MUTATION` or `CONTEXT`: 18 views, 3 context
  codes, 60 mutations), `permission.grant_scope` (`TENANT`, or `PLATFORM` for the 14 codes only ever
  evaluated in the PLATFORM organisation) and the join table `permission_view_requirement`
  (`permission_id` → `required_view_permission_id`, both `permission (id)`, unique pair, never
  equal; a join table because `user.invite` needs `membership.view` **and** `user.view`), and
  seeds all of them for the 81 live codes. **Both columns are `NOT NULL`, so a future permission
  migration must insert `kind`, `grant_scope` and, for a `MUTATION`, its
  `permission_view_requirement` rows in the same file, or it fails to apply**;
  `PermissionCatalogueMetadataTests` also fails until every mutation is paired with a view, every
  required code is a `VIEW`, and an `ACTIVE` mutation requires only `ACTIVE` views. A kind cannot
  be a cross-table `CHECK` and no trigger is allowed (ADR 0024), so the migration and that test
  are the enforcement. **No data backfill**: no `role_permission` or `membership_permission` row
  is touched, nothing about who can do what changes, and the rule itself (role-composition
  check, named 403, `missing_view_permissions`) lands in later changes that read these rows. The
  catalogue API (`GET /api/v1/tenant/permissions`) publishes `kind`, `grant_scope` and
  `required_view_permissions`. Re-runnable, with pre- and post-condition asserts in-file; it
  raises, naming the codes, if the catalogue holds a permission it cannot classify — see
  `docs/security/authorization-model.md` ("Catalogue metadata") and ADR 0030
- `V23__seeded_admin_roles_hold_every_permission_of_their_scope.sql` — **data only, no schema**:
  the owner's rule that an administrator role holds EVERYTHING in its scope. Every
  existing tenant's seeded `TENANT_ADMIN` and the bootstrap `local-admin` (organisation
  `FINAXIS-LOCAL`, pinned by organisation, code and `system_role`) are granted every `ACTIVE`
  `grant_scope = 'TENANT'` code they lack (accounting maker and checker and the two break-glass
  codes included) and lose the platform-only `tenant.*` codes, the file's only deletion
  (access-neutral: they are evaluated only in the PLATFORM organisation);
  `PLATFORM_SUPER_ADMIN` is topped up to every `ACTIVE` code; `PLATFORM_SUPPORT` gains
  `auth.select_organisation`, `iam.profile.read` and the platform reads (still no accounting);
  `IAM_ADMIN` + `branch.view`, `BRANCH_MANAGER` + `user.revoke_branch` and `user.view`,
  `BRANCH_OPERATOR` + `branch.view` in every tenant. A seeded role is `system_role` plus its seeded
  code; a tenant-customised (`system_role = FALSE`) role is never touched, and the API cannot edit
  a seeded one. `local.admin` and `local.checker` become full tenant administrators: rotate or
  deactivate them in production. Idempotent, with pre- and post-condition asserts in-file; the
  effective-permission cache is cleared on every start, so it applies on the first request. **A
  later migration that adds an `ACTIVE` permission must grant it to the administrators it belongs
  to**, copying `V23`'s step 1a and step 2 for every tenant's `TENANT_ADMIN` (the drift test sees
  only `PLATFORM_SUPER_ADMIN` and `local-admin`) — see `docs/security/authorization-model.md` and
  ADR 0030

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
`V9` control accounts and their reconciliation evidence, `V10` manual-journal drafts, `V11` the
one-control-account-per-class uniqueness, `V12` the manual-journal external reference, `V13`
the journal-line append guard, `V14` the daily-balance projection, `V15` the branch
trial-balance index, `V16` the corrected fingerprint comment, and `V17` the cancelled draft
state. `V18` is a data backfill of the branch lifecycle dates, `V19` a foundation permission
seed (`branch.update`), `V20` a foundation CHECK pinning the platform organisation to `ACTIVE`,
`V21` the move of branch approval to `branch.approve` (deprecating `branch.activate`), `V22`
the permission catalogue's kind, grant scope and view requirements, and `V23` the seeded
administrator roles' grants; none is accounting. Do not invent accounting tables or columns
outside those documents.

## Authorization

- Keycloak authenticates users only. This app is an OAuth2 resource server: no
  application-managed passwords, password endpoints, password storage, or password checks.
  Smoke scripts may fetch dev-only Keycloak tokens; application code must not handle
  credentials.
- The application owns users, organisations, memberships, roles, permissions, scopes, and
  authorization rules. Runtime authorization evaluates **permission codes, never role names**.
  The effective-permission cache is namespaced by schema version and cleared at start, so a
  permission migration needs no operator step; a manual SQL grant outside Flyway needs a flush —
  see `docs/security/authorization-model.md` ("Caching and invalidation").
- A migration that adds a permission code must also insert its `kind`, its `grant_scope` and, for
  a `MUTATION`, the `permission_view_requirement` rows naming the view(s) it implies (ADR 0030;
  `V22` made the columns `NOT NULL`). `PermissionCatalogueMetadataTests` fails otherwise.
- The seeded **administrator roles hold every permission of their scope** and are derived, never
  listed: a newly approved tenant's `TENANT_ADMIN` is every `ACTIVE` permission with
  `grant_scope = 'TENANT'`, read from the catalogue at seeding time (and by the readiness check);
  `PLATFORM_SUPER_ADMIN` is every `ACTIVE` code. The non-admin bundles stay purpose-built lists in
  `OrganisationBootstrapDefaults`. No NON-admin default bundle may hold a break-glass code
  (`fiscal_period.reopen`, `journal.post_prior_period`); the administrators do, and use stays
  audited and lock-checked. `V23` applied this to existing tenants.
- Maker-checker: a platform-context actor holding the permission in the platform organisation may
  be the audited checker of a pending membership only while the tenant has no ACTIVE member beyond
  its bootstrap administrator, and of a pending branch only while it has no ACTIVE branch beyond
  the bootstrap head office; the checker is never the maker or the beneficiary — ADR 0028. On
  either route a branch's approver is also never anyone who amended it (a successful
  `branch.update` audit event on it, 403 `lifecycle.approver_is_branch_modifier`).
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
  request DTOs and typed configuration properties. Every `@RequestBody` DTO carrying
  constraints must be `@Valid` (nested ones `@field:Valid`), enforced by
  `RequestBodyValidationArchitectureTest`.
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
both JaCoCo verifications + `bootJar`). Individually: `spotlessCheck`, `ktlintCheck`, `detekt`,
`checkstyleMain checkstyleTest`, `pmdMain pmdTest`, `spotbugsMain spotbugsTest`, `test`.

- Kotlin: Spotless (formatter), ktlint (via Spotless), Detekt (all rules, zero findings, KDoc
  required on public production classes/functions).
- Java: Checkstyle, PMD, SpotBugs, Error Prone, tests.
- Architecture-sensitive changes: ArchUnit **and** Spring Modulith verification must pass.
- Max line length 100 across Kotlin/Java/Gradle/YAML/XML/Markdown where feasible.
- Prefer cleanup over suppressions; keep any unavoidable suppression narrow and explained.
- Do not bypass, disable, weaken, or suppress these checks without documenting why.
- See `docs/development/static-analysis.md`.
