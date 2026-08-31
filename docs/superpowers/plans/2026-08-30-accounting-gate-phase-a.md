# Accounting Gate — Phase A Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking. Each issue below is a separate PR — do not batch them.

**Goal:** Clear all six Phase A gate issues of epic #55 — #28 (BIAN semantic map), #29 (prove
financial transaction atomicity and reconcile #12), #30 (freeze accounting invariants, canonical
ERD and ledger architecture), #31 (Spring Modulith accounting boundary and financial ports),
#34 (accounting permissions and privileged-operation policy), #35 (accounting-date, fiscal-period
and posting concurrency semantics) — so that Phase B can create the chart of accounts and fiscal
calendar against a frozen, proven foundation.

**Architecture:** Phase A adds **no general-ledger schema and no posting behaviour**. It adds one
new Spring Modulith module (`accounting`) that owns contracts only; three inverted cross-module
ports that `iam` and `lifecycle` implement on accounting's behalf (permission checks, the tenant
business date, and organisation/branch postability); a pure date/period policy layer with a real
PostgreSQL row-lock primitive; a reusable atomicity proof harness that #41's PostingEngine adopts
unchanged; one Flyway migration seeding the accounting permission catalogue; and the design
records — a canonical ERD and invariant set — that every later accounting issue must consume
rather than reinvent.

The load-bearing architectural decision is the **direction of the module dependency**: accounting
declares its ports and `iam`/`lifecycle` supply the adapters, so the Modulith edges are
`iam → accounting` and `lifecycle → accounting`, never the reverse. This mirrors the existing
`lifecycle.PermissionGuard` ← `iam.LifecyclePermissionGuardAdapter` inversion and leaves room for
close-of-business to call into accounting in #39 without creating a cycle.

**Tech Stack:** Kotlin 2.4 on Java 25, Spring Boot 4.1.1, Spring Modulith 2.1, jOOQ 3.21.7,
Flyway, PostgreSQL 18.4, Testcontainers, ArchUnit 1.5, JUnit 5, Namastack Outbox, JobRunr,
Gradle Kotlin DSL.

## Baseline

`main` is at `086e226` (PR #56, SMTP email). This is **past** the baseline the epic records
(`ba699d7`). Migrations are `V1`–`V4`, so the next free version is **V5** — but per the epic's
universal migration rule, re-check `main` immediately before implementing #34 and take the version
from reality.

## Delivery

One PR per issue, in dependency order, **stacked**: only #28 branches from `main`, and each
subsequent branch is cut from the one below it, with its PR targeting that branch rather than
`main`. Reviewing a PR therefore shows only its own issue's work.

| Order | Issue | Branch | Base | ADR(s) | Migration |
| --- | --- | --- | --- | --- | --- |
| 1 | #28 | `claude/accounting-gate-phase-a-lgrbse-28` | `main` | 0017 | none |
| 2 | #29 | `claude/accounting-gate-phase-a-lgrbse-29` | `…-28` | 0018 | none |
| 3 | #30 | `claude/accounting-gate-phase-a-lgrbse-30` | `…-29` | 0019, 0020 | none |
| 4 | #31 | `claude/accounting-gate-phase-a-lgrbse-31` | `…-30` | none | none |
| 5 | #34 | `claude/accounting-gate-phase-a-lgrbse-34` | `…-31` | 0021 | **V5 (the only one)** |
| 6 | #35 | `claude/accounting-gate-phase-a-lgrbse-35` | `…-34` | 0022 | none |

Because the chain is stacked, they merge bottom-up: merging out of order leaves a later PR
targeting a branch that no longer exists, and a broken commit anywhere in the chain propagates
upward. Each branch's quality gate must therefore be green before the next is cut from it.

As delivered, the chain is [#60][pr-60] (#28) → [#61][pr-61] (#29) → [#62][pr-62] (#30) →
[#63][pr-63] (#31) → [#64][pr-64] (#34) → [#65][pr-65] (#35).

**ADR numbers are allocated here, once.** Independent designs for #28 and #29 both proposed 0017;
the table above is authoritative. If a PR merges out of order, renumber before merging rather than
colliding. Confirm the next free number against `docs/adr/` at the start of each issue.

## Environment prerequisites

Two things must be true of the machine before `./gradlew qualityGate` can run at all. Neither is
obvious from a clean checkout, and both were discovered the hard way.

- **A JDK 25 must be installed.** `build.gradle.kts` pins `JavaLanguageVersion.of(25)` and
  `settings.gradle.kts` declares no toolchain resolver, so Gradle cannot provision one and the
  build fails at *configuration* time, not at compile time. `.sdkmanrc` pins `25.0.2-graalce`,
  which CI installs by pointing `actions/setup-java` at that file.
- **A Docker daemon must be running.** Without one, roughly 198 integration tests fail with
  `Previous attempts to find a Docker environment failed` — a message that looks like a code defect
  and is not. Pre-pulling the five pinned images (`postgres:18.4`, `redis:8.8.0`,
  `rabbitmq:4.3.2-management`, `greenmail/standalone:2.1.13`, `grafana/otel-lgtm:0.28.0`) avoids a
  slow first run.

## Global Constraints

- Kotlin-first. Java only for `package-info.java` module descriptors — the repository has **zero**
  Java classes and all 22 Java files are package descriptors. Any new Java needs a documented
  framework/runtime reason.
- KDoc on every public production class and function (Detekt `UndocumentedPublicClass` and
  `UndocumentedPublicFunction` are active with `allRules = true`). Max line length **100** across
  Kotlin, Java, Gradle, YAML and Markdown, comments included.
- Detekt limits that will bite: `LongMethod` 60 lines, `LargeClass` 600 lines (**this applies to
  test classes too**), `LongParameterList` 5 function / 6 constructor params (`ignoreDataClasses`
  is on), `TooManyFunctions` 10 per file, `ThrowsCount` 2, `ComplexCondition` 3,
  `NestedBlockDepth` 3, no broad `catch (Exception)`/`catch (RuntimeException)`, and
  `ForbiddenComment` bans `TODO:` / `FIXME:` / `STOPSHIP:` — write "issue #41 delivers …" instead.
- Run `./gradlew spotlessApply` before every commit touching Kotlin or Java. Finish every issue
  with `./gradlew qualityGate` green.
- `UUID.randomUUID()` is banned outside `com.finaxis.platform.jooq` by
  `IdentifierGenerationRuleTests`. Use the database `DEFAULT uuidv7()` with
  `.returning(TABLE.ID).fetchOne()?.id`, or `com.finaxis.platform.common.id.uuidV7()` with a
  comment saying why. Never add an `id` field to a `*Request` DTO.
- Never edit `V1`–`V4` or any migration already on `main`. **At most one new migration in this
  entire plan** (#34's V5). If a second becomes necessary, stop and open a follow-up issue.
  Regenerate jOOQ from Flyway only; never hand-edit generated sources.
- Cross-module rule: the **consumer** declares the port in its own package; the **provider** ships
  the `@Component` adapter under `<provider>/adapter/outbound/<topic>/` and lists the consumer in
  *its own* `@ApplicationModule(allowedDependencies = …)`. That is why `iam` lists `lifecycle` and
  **no module lists `iam`**.
- Events only via `TransitionDefinition.eventFactories` → `ExternalizedTransitionEvent(target)`.
  Never a hand-written outbox table, never a direct broker publish.
- Accounting must not depend on `iam`, `lifecycle`, or `notifications` in any direction that would
  create a cycle. `slices().matching("com.finaxis.platform.(*)..").should().beFreeOfCycles()` is
  the test that enforces it.

## Test-context budget (the top schedule risk)

Only **two** Spring test contexts are in use. Every new `@Import` / `properties` / `@ActiveProfiles`
combination forks another one — new containers, a fresh Flyway run, roughly a minute each against
CI's single 30-minute `qualityGate` budget (`maxHeapSize = "2g"`, no `maxParallelForks`).
**This plan adds zero new contexts.**

- **Bucket P** (35 classes): `@Import(PostgresTestConfiguration::class) @SpringBootTest
  @TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)`, nothing else. Every new test
  here. No RabbitMQ in this bucket, which is fine: outbox assertions are row-existence checks on
  `outbox_record`, written at commit regardless of whether the poller can publish.
- **Bucket T-throw** (1 class): `@Import(TestcontainersConfiguration::class,
  ThrowingSettingsEventPublisherConfiguration::class)`. That second class is public and top-level
  in `OutboxTransactionRollbackIntegrationTests.kt`, so a sibling in the same package with
  identical annotations joins the same cached context.

Never add `@Primary` beans or `properties` overrides to Bucket P. Service-level integration tests
must wrap permission-gated calls in `withRequestContext { }` from
`src/test/kotlin/com/finaxis/platform/lifecycle/TenantAdminOrganisationFixture.kt`, because
`PermissionGuard` touches a `@RequestScope` bean.

## Hazards this plan resolves

Verified against the tree, not speculative. Each is owned by the issue named.

1. **`HighRiskOperationAuditCoverageTests` will fail on #34.** It enforces two invariants: every
   `HIGH`/`CRITICAL` permission in the seeded catalogue must appear in `auditedActionByPermission`,
   **and** every mapped action must be reachable in production — as a string literal at an
   `auditService` call site anywhere under `src/main/kotlin`, or as a declared transition on one of
   five registered prefixes (`organisation`, `branch`, `user_account`, `user`, `membership`). #34
   seeds HIGH/CRITICAL accounting codes while creating no accounting behaviour, so there is no
   literal and no registered transition enum. **Owned by #34 — decide deliberately, do not discover
   it at CI.**
2. **`FoundationSeedDataTests` freezes the catalogue exactly**: `EXPECTED_CATALOGUE_SIZE = 54`, the
   full `expectedPermissionCodes` set, `expectedLocalAdminPermissionCodes` (23 after V4), and
   "platform super admin holds the entire permission catalogue". V2's superset grant was a one-time
   `INSERT … SELECT … FROM permission`, so V5 codes need their own explicit grant. **Owned by #34.**
3. **Org-local roles are Kotlin, not SQL.** `OrganisationBootstrapDefaults` in
   `lifecycle/adapter/outbound/persistence/JooqOrganisationBranchProvisioningStore.kt` builds
   `TENANT_ADMIN` / `TENANT_AUDITOR` / `IAM_ADMIN` / `BRANCH_MANAGER` / `BRANCH_OPERATOR` at
   **organisation-approval time only**. V5 codes not added to `BASELINE_PERMISSION_CODES` reach no
   existing tenant role, and already-approved tenants need a backfill. **Owned by #34.**
4. **No cross-module business-date port exists.** `BusinessDateStore` / `BusinessDateSnapshot` live
   in `lifecycle/application/OrganisationBranchProvisioningPorts.kt` and are called only by
   lifecycle's own `BusinessDateService`; nothing outside lifecycle can read the tenant business
   date and no other table carries a `business_date` column. **Owned by #31**, consumed by #35.
5. **The advisory-lock key space is shared and unnamespaced.** Both existing sites hash into one
   `bigint` space via `hashtextextended(text, 0)` — blocking `pg_advisory_xact_lock` for tenant
   settings, and bounded-wait `pg_try_advisory_xact_lock` + `LockSupport.parkNanos` for
   idempotency. **Owned by #35 — do not add a third unnamespaced domain.**
6. **Money is a clean slate.** Zero `NUMERIC`/`DECIMAL` columns, zero `BigDecimal` in
   `src/main/kotlin`, no money type, no currency table. Currency exists only as
   `organisation.base_currency_code CHAR(3)` (regex `^[A-Z]{3}$`, `PLATFORM` seeded `XXX`) and a
   `base_currency` tenant setting validated against `Currency.getAvailableCurrencies()`.
   **Owned by #30 outright.**
7. **`TransitionPolicy` and `TransitionEffect` are declared but wired nowhere.**
   `TransitionEffect`'s KDoc says it "must be atomic with state mutation … runs inside the
   transition executor before event publication" — the natural hook for a period lock, and #35
   would be its first consumer.
8. **JaCoCo verification covers `iam/**` only** at 95% line.
   `docs/development/static-analysis.md` says new business modules should add their own narrow
   rule. **Recommendation: defer** the accounting rule to #40/#41 — at #31 the module is contracts
   plus two adapters, and a ratio over that set is vacuous or misleading. Record the deferral.
9. **`AuditService.recordIndependently` has a latent FK hazard.** It is `REQUIRES_NEW`, and
   `audit_event.organisation_id` is `NOT NULL REFERENCES organisation (id)`, so an independent
   audit referencing an organisation created in the still-uncommitted enclosing transaction fails
   the FK. Every #29 test uses a **pre-committed** organisation. File the underlying fragility as a
   follow-up; do not fix it inside #29.
10. **`HexagonalArchitectureTest`'s web-adapter allow-list literally names
    `com.finaxis.platform.lifecycle`.** No Phase A issue adds an accounting controller, so no edit
    is needed — but the first accounting controller (#52) must extend that list. Record it.

---

## Issue #28 — BIAN service landscape and semantic architecture map

**Zero migrations. Zero production Kotlin.** Docs, one ADR, one convention test, and four existing
`package-info.java` Javadoc blocks.

### Source situation — better than the issue assumes

The issue's cited URL is unusable, but the official **v14.0.0 Service Domain definitions are
publicly fetchable from GitHub with no registration**. Verified by fetch on 2026-08-30:

| Source | Status | Provides |
| --- | --- | --- |
| `bian.org/deliverables/service-landscape/` | 200 HTML | Release history; names v14.0 (February 2026) current |
| `bian.org/wp-content/uploads/2026/02/BIAN-v14.0-Release-Notes-v1.0_-Final-Version.pdf` | 200 PDF, 395 KB | Direct download, no registration — **the only place to confirm the service-domain count** |
| `raw.githubusercontent.com/bian-official/public/master/release14.0.0/semantic-apis/asyncapi-3.x/yamls/FinancialAccounting.yaml` | 200 | `info.version: 14.0.0`; SD "takes in financial facts and based on these, creates accounting instructions that will update the general ledger and sub ledger accounts"; channels `FinancialBookingLog/Created|Updated`, `LedgerPosting/Created|Updated` |
| …`/PositionKeeping.yaml` | 200 | v14.0.0 Position Keeping: "maintains a log of monetary or value transactions … Reconciled financial transactions are subsequently used for posting to the accounting systems" |
| `raw.githubusercontent.com/bianapis/wave1/master/sd-position-keeping.yaml` | 200 | The richest prose: product fulfilment domains "delegate transaction posting/track to the Position Keeping service domain" — **the crux of the Finaxis deviation** |
| `bian.org/servicelandscape-11-0-0/object_41.html?object=43420` | 200, empty | Confirmed JavaScript-only "InSite" shell — cite as not machine-fetchable |

Do **not** use `api.github.com` (blocked by the agent proxy) or `curl` on GitHub HTML (403 through
the proxy) — use `raw.githubusercontent.com` paths, or `WebFetch` for GitHub HTML pages.

Enumerate Service Domain names by probing
`$base/<PascalCaseName>.yaml` with `curl -sS -o /dev/null -w "%{http_code}" -L`. Verified to exist
in v14.0.0: `FinancialAccounting`, `PositionKeeping`, `CustomerRelationshipManagement`,
`PartyReferenceDataDirectory`, `PartyAuthentication`, `PartyLifecycleManagement`, `CurrentAccount`,
`SavingsAccount`, `ConsumerLoan`, `CorporateLoan`, `PaymentOrderInitiation`,
`BranchCurrencyManagement`, `BranchLocationOperations`, `CustomerBilling`, `ProductDirectory`,
`FinancialStatementAssessment`, `ContactHandler`, `CorporateTreasury`. Verified **not** to exist —
do not invent them: `PaymentExecution`, `CashManagement`, `TellerOperations`, `FinancialStatements`,
`FinancialControl`, `SubLedgerAccounting`, `LedgerAccounting`, `ManagementAccounting`. **Teller/Cash
and "Financial Statements" have no 1:1 v14 Service Domain**, so record them as
"no direct SD; nearest is …" rather than guessing.

### Tasks

- [ ] **Task 28.1 — Pin the evidence base.** Fetch the release-notes PDF and extract the exact
  release date and service-domain count. `bian.org` only states 322 for v11.0, so **do not publish
  an unsourced "340"** — write what the PDF states, or record the count as portal-reported and
  registration-gated. Fetch the four SD YAMLs and copy their `description` blocks verbatim for
  quoting. Run the probe loop over every capability in the issue's requirement (4). Record each URL
  with its HTTP status and fetch date.
- [ ] **Task 28.2 — `docs/adr/0017-bian-semantic-reference-architecture.md`.** Title:
  *BIAN as Semantic Reference Architecture, Not Deployment Topology*. Decision bullets: BIAN is
  semantic/capability reference only, never topology, package structure, transport shape or schema;
  **no microservice split** — an SD maps to at most a module, package or named interface, and
  changing that requires a superseding ADR; version pinned to Service Landscape 14.0 (February
  2026) with the portal recorded as registration-gated and JavaScript-rendered; the evidence base
  is the public `bian-official/public` repository; a re-verification trigger (a BIAN release above
  14.0, a new `release*` directory, a new financial `@ApplicationModule`, or 12 months — whichever
  first) with a "Sources verified on" date that must be bumped; `Financial Accounting` is the
  accounting anchor, adopted for its *fact-in / instruction-out* semantic and **adapted** to reject
  the implied downstream/batch timing; `Position Keeping` is evaluated and deliberately **not**
  adopted as a Finaxis boundary; BIAN nouns are borrowed but BIAN Service Operation verbs, control
  record ids, behaviour-qualifier URL segments and ISO 20022 payload schemas are banned from the
  domain model; deviations are recorded with stable ids; every `@ApplicationModule` must declare
  its mapping in its `package-info.java` Javadoc; platform-specific boundaries are legitimate.
- [ ] **Task 28.3 — `docs/architecture/bian-service-landscape.md`.** Headings: Scope and Non-Goals
  → BIAN Release Used (version and access, publicly reachable sources, sources that are not
  machine-fetchable, re-verification trigger, "Sources verified on") → How Finaxis Uses BIAN
  (mapping levels, adoption qualifiers, the modular-monolith constraint) → Capability Landscape →
  Module Mappings (one `### <module>` per module — the heading text must equal the module directory
  name, see Task 28.6) → Accounting Semantic Anchor → **BIAN Position Keeping Evaluation** →
  Deviation Register → Mapping Template → Naming Guidance → Glossary → Enforcement → Related
  Documents.
  Module mappings: `iam` → Party Authentication / Party Lifecycle Management / Party Reference Data
  Directory (adapted — Keycloak authenticates, Finaxis authorizes; BIAN has no permission-code RBAC,
  no multi-tenant membership, no active-organisation context); `lifecycle` → **none**,
  platform-specific (tenant/branch/user FSMs, provisioning, controlled business date and COB —
  nearest neighbours are Branch Location Operations and Corporate Treasury, neither of which covers
  SaaS tenant lifecycle); `notifications` → Contact Handler (adapted, outbound transactional email
  only); `common`, `config`, `jooq` → none, not boundaries; `accounting` → Financial Accounting
  (adapted).
- [ ] **Task 28.4 — The Position Keeping deviation, stated plainly.** The section must say, close
  to verbatim: BIAN's Position Keeping is a transaction journal that product fulfilment domains
  delegate posting to, whose reconciled transactions are "subsequently used for posting to the
  accounting systems". **Finaxis rejects that sequencing.** The Finaxis GL journal is written
  synchronously, inside the same PostgreSQL transaction as the source-domain mutation and the
  product sub-ledger effect. There is no window in which a position exists that the ledger does not
  know about, and no reconciliation step that promotes positions into the ledger. Interval and
  summary bookings — daily rollups, period aggregates, current-balance caches — are derived
  optimisations built from immutable journal lines, never the only source of truth, and must be
  fully reconstructable by replaying the journal. **Any design that makes a rollup authoritative is
  a defect, not a variation.**
- [ ] **Task 28.5 — Mermaid capability/context diagram, glossary, mapping template.** The diagram
  shows one `flowchart TB` subgraph labelled as a single deployable, containing platform
  foundation, the accounting kernel, and planned product modules, each node annotated with its BIAN
  line. Solid arrows are consumer → provider public API; **dotted arrows are inverted ports** where
  the consumer declares the interface and the provider ships the adapter. Add a callout that it is
  a capability map, not a topology map. The glossary maps Finaxis → BIAN terms with a relationship
  column (organisation/tenant → none, Finaxis-only; posting intent → financial fact, adapted;
  journal entry → Financial Booking Log entry, adapted; journal line → Ledger Posting, adapted;
  GL account → financial account, adopted; sub-ledger position → Financial Position Log, *shape
  adopted, sequencing rejected*; share account → none, SACCO-only; …). The mapping template is a
  fenced block the implementer copies per new module, with fields for the exact v14.0.0 SD name,
  source URL and fetch date, adoption qualifier, what is adopted/adapted/rejected, deviation ids,
  accounting relationship, sub-ledger ownership, transaction participation, tenant/branch scoping,
  ports declared and implemented, event targets, and the verbatim `package-info.java` BIAN line.
  Include a worked example filled in for `accounting`.
- [ ] **Task 28.6 — Machine-checkable mapping (requirement 7).**
  Create `src/test/kotlin/com/finaxis/platform/architecture/BianModuleMappingTests.kt`: a plain
  JUnit test that walks `src/main/java/com/finaxis/platform/**/package-info.java`, filters to files
  containing `@org.springframework.modulith.ApplicationModule`, and asserts (a) at least 4 are
  found — guarding against a path typo making the whole test vacuous, (b) every one matches
  `Regex("""^\s*\*\s*BIAN:\s*\S.*$""", RegexOption.MULTILINE)`, and (c) each module directory name
  has a matching `### <name>` section in the landscape doc, tying code to documentation in both
  directions.
  **Rejected: a custom `@BianMapping` annotation.** Kotlin's `AnnotationTarget` has no `PACKAGE`
  member, so the annotation would have to be written in Java — and the repo's only Java is package
  descriptors. It would also duplicate the Javadoc that must exist anyway for Modulith's
  `Documenter`. Relative paths work: `ModulithArchitectureTest` already uses
  `File("build/spring-modulith-docs")`.
- [ ] **Task 28.7 — Add the `BIAN:` Javadoc line** to the four existing `package-info.java`
  descriptors (`iam`, `lifecycle`, `notifications`, `jooq`). Annotations untouched. Run
  `./gradlew spotlessApply checkstyleMain` immediately after — google-java-format reflows Javadoc
  and can push a line past Checkstyle's limit; keep BIAN lines short and put detail in the doc.
- [ ] **Task 28.8 — `CLAUDE.md` and `README.md`.** One new bullet under
  `## Modules (Spring Modulith)` making the `BIAN:` line canonical and naming the enforcing test —
  this is the *only* `CLAUDE.md` change, and it qualifies because a test enforces it repo-wide. Add
  the new doc and ADR 0017 to `README.md`'s tables (and, opportunistically, the missing ADR 0016
  and `docs/architecture/email-delivery.md` — the index is currently stale).
- [ ] **Task 28.9 — Verify.** `./gradlew spotlessApply`, then
  `./gradlew test --tests '*BianModuleMappingTests*' --tests '*ModulithArchitectureTest*'`, then
  `./gradlew qualityGate`. `ApplicationModules.verify()` must be unchanged — no annotation edits.

---

## Issue #29 — Prove financial transaction atomicity, reconcile #12

**Zero migrations.** Tests and docs only.

### Premise correction — verified, and it changes the issue's framing

#29 is written as if the atomicity guarantee were unproven. **It is partly proven already, and two
green tests on `main` are direct counter-evidence to #12:**

- `JooqIdempotencyStoreTests."rolled back acquisition leaves no durable record"` — a real jOOQ
  write inside a `TransactionTemplate`, then `error("business mutation failed")`, then
  `assertEquals(0, dsl.fetchCount(API_IDEMPOTENCY_RECORD))`.
- `InitialAdministratorBootstrapFailureRecorderIntegrationTests` — `assertEquals(0, record.attempts)`
  proves the jOOQ `ATTEMPTS = ATTEMPTS + 1` update inside the failed transaction was reverted, while
  the `REQUIRES_NEW` failure row survived as designed.

**#12 is also not reproducible verbatim.** Both code paths it named are gone from `main`:
`OrganisationSettingsService` was superseded by `TenantSettingsService`, and
`@AuditedAction`/`AuditedActionAspect` were deleted on 2026-08-02 (ADR 0007, amended). Only the
*invariant* can be re-proved — and the *observation* can be reproduced as an artifact.

A third finding explains the specific gap in the existing rollback test: at the original commit,
`CreateOrganisationDraftCommand.initialSettings` defaults to `emptyMap()` and the fixture passes
none, so `base_currency` had **zero** rows before the failing update and `closeCurrentSetting`
updated nothing. The "UPDATE half is untested" gap is real, and its cause is a missing seed.

### Tasks

- [ ] **Task 29.1 — Assert the production jOOQ transaction wiring.** This is the genuine gap:
  nothing asserts it today. Create
  `src/test/kotlin/com/finaxis/platform/common/persistence/JooqSpringTransactionWiringTests.kt`
  (Bucket P). Assert the `ConnectionProvider` is a `DataSourceConnectionProvider`; its
  `dataSource()` is a `TransactionAwareDataSourceProxy`; the `TransactionProvider`'s **simple name**
  is `SpringTransactionProvider` (the class is package-private in `spring-boot-jooq` — comment
  why); `AopUtils.isAopProxy(tenantSettingsService)` with a `TransactionInterceptor` in its advisor
  chain; and the decisive behavioural assertion — `pg_current_xact_id()` returns the **same** value
  for two `dsl.fetchValue` calls inside one `TransactionTemplate` and **different** values for two
  calls outside any transaction.
- [ ] **Task 29.2 — The reusable atomicity fixture.** Create
  `src/test/kotlin/com/finaxis/platform/accounting/support/FinancialTransactionAtomicityFixture.kt`
  and `FoundationAtomicityProbes.kt`. The fixture takes
  `AtomicityProbe(name, countRows: (DSLContext) -> Long)` values and exposes `snapshot()`,
  `assertRollsBackAtomically(expected, operation)` and
  `assertVisibleOnlyAfterCommit(expectedDeltas, operation)`. **Probes are declared per test**, so
  #41's PostingEngine tests add `posting_request`, `journal_entry`, `journal_line` and sub-ledger
  probes without editing the fixture — that is the issue's "reusable by the PostingEngine"
  requirement made structural. `assertVisibleOnlyAfterCommit` copies the two-virtual-thread plus
  `CountDownLatch` shape of `JooqIdempotencyStoreTests` verbatim; the second thread reuses the same
  `dsl` bean, and because `TransactionAwareDataSourceProxy` binds per thread, a thread with no
  transaction gets a fresh pooled connection. **That connection affinity is exactly what #12 got
  wrong — say so in the KDoc.** `outbox_record` and `event_publication` probes must use raw SQL:
  those tables are starter-created outside Flyway and have no generated jOOQ metadata.
- [ ] **Task 29.3 — The four required rollback cases.** Create
  `src/test/kotlin/com/finaxis/platform/accounting/FinancialTransactionAtomicityIntegrationTests.kt`
  (Bucket P). Seed a **committed** organisation and a committed `base_currency` row first (hazard 9).
  (a) Runtime exception after a successful update via `TenantSettingsService`.
  (b) PostgreSQL constraint violation — a duplicate `(organisation_id, setting_key, effective_from)`
  against `uq_organisation_setting_effective`, plus an `audit_event` FK-violation variant. Comment
  that after a constraint error PostgreSQL aborts the transaction, so no further statement may run
  inside it — assert only from a fresh connection after rollback.
  (c) Failure after an accounting event is registered — drive `BusinessDateService.advance`, whose
  `record(...)` does history → audit → publish in one transaction, then throw.
  (d) Nested `@Transactional` services — call `submitForApproval` twice so the FSM rejects.
  Case (d) asserts one *surviving* row on purpose: the `recordIndependently` FSM-rejection
  `audit_event`. Comment it as the single documented, intentional exception to the invariant.
- [ ] **Task 29.4 — Observable only after commit.** In the same class, hold the transaction open
  and assert from a second connection that `outbox_record`, `business_date_history` and the
  `SUCCESS` audit row are all absent; then commit and assert the deltas. Add the same-connection
  contrast — visible *before* commit on the same connection — and label it as the exact observation
  #12 mistook for a durable write.
- [ ] **Task 29.5 — Close the two gaps in the existing rollback test.** Modify
  `OutboxTransactionRollbackIntegrationTests.kt` (Bucket T-throw, no new context). Seed a
  `base_currency` row by direct jOOQ insert on the autocommit connection — it cannot go through
  `TenantSettingsService`, whose `@Primary` publisher double throws for that target. Add
  `countOpenSettings` filtering `EFFECTIVE_TO.isNull`, assert the surviving row's value, assert
  zero `settings.update`/`SUCCESS` audit rows, and assert `event_publication` unchanged. This is
  the only case injecting failure at a pure `@Transactional` proxy boundary — the direct analogue
  of #12's mechanism 3.
- [ ] **Task 29.6 — The experiment that settles #12.** Create
  `src/test/kotlin/com/finaxis/platform/accounting/RollbackObservationArtifactIntegrationTests.kt`
  — three tests over the *same* failing operation, differing **only** in where the assertion's
  connection sits. **E1 control:** no ambient transaction, assert from a fresh connection after the
  exception → row absent, refuting "genuine defect". **E2 reproduce the artifact:** wrap the call in
  an outer `TransactionTemplate`, assert *inside* that the write **is** visible, then assert
  *outside* after rollback that it is gone. **E3 proxy probe:**
  `TransactionSynchronizationManager.isActualTransactionActive()` is true inside the service.
  E2 is the point — it converts "cannot reproduce" into "**the observation is reproducible and it
  is an artifact**", and one confound (an ambient transaction shared by all three of #12's
  supposedly independent mechanisms) explains why they failed identically. Write that in E2's KDoc.
  **Escalation if E1 fails:** bisect with `pg_current_xact_id()` before/after the write and
  `connection.autoCommit` inside the `@Transactional` method. If `autoCommit == true`, the proxy is
  genuinely absent, #12 is real, and the gate stops.
- [ ] **Task 29.7 — Docs.** ADR `docs/adr/0018-financial-transaction-atomicity-invariant.md`
  (Decision: one runtime and one PostgreSQL transaction for all effects; the production
  Spring/jOOQ wiring is the only transaction path; `REQUIRES_NEW` is the sole permitted exception
  and each use needs explicit justification; every future financial write path registers probes in
  the fixture; external publication is a post-commit-only observation. Consequences: no
  after-commit listener exists today and adding one changes the guarantee; the `recordIndependently`
  FK hazard; a constraint violation aborts the whole transaction so no compensating statement can
  run inside it). Plus `docs/architecture/financial-transaction-atomicity.md` as the
  implementer guide — wiring diagram, fixture API, the probe table, how to add a probe for a new
  accounting table, and the #12 reconciliation narrative. Fix the stale `OrganisationSettingsService`
  references in `docs/architecture/transactional-outbox-amqp.md` and `docs/adr/0008-…md`.
- [ ] **Task 29.8 — Reconcile #12.** Post the comment matching the outcome. **Resolved as
  artifact** (expected): cite the two pre-existing green tests, the new wiring assertions, the E2
  reproduction, and the fact that both named suspects no longer exist; close as completed,
  superseded by #29. **Still broken:** keep open, relabel P0, block #29/#41, and post the isolation
  data (`ConnectionProvider`, `TransactionProvider`, `autoCommit`, `pg_current_xact_id()` before and
  after). **Indeterminate:** keep open, relabel `needs-investigation`, list the hypotheses
  eliminated, and state that #29 proceeds on the positive proofs. Do **not** close on this outcome.

### Rows to assert (reference for every #29 test)

| Table | Predicate | Expected after rollback |
| --- | --- | --- |
| `organisation_setting` | `organisation_id = ? AND setting_key = ?` | unchanged (1 when seeded) |
| `organisation_setting` | `… AND effective_to IS NULL` | 1, and `setting_value` is the seeded value |
| `business_date` | `organisation_id = ?` | `current_business_date`, `status`, `row_version` unchanged |
| `business_date_history` | `organisation_id = ?` | unchanged |
| `audit_event` | `… AND action = ? AND outcome = 'SUCCESS'` | 0 |
| `audit_event` | `… AND outcome IN ('FAILURE','DENIED')` | 1 **only** for the `recordIndependently` path (case d) |
| `organisation_transition_log` | `entity_id = ?` | unchanged |
| `outbox_record` | `payload LIKE '%'‖aggregateId‖'%'` (raw SQL) | 0 |
| `event_publication` | `count(*)` (raw SQL) | unchanged (currently always 0) |
| `organisation` | `id = ?` → `status` | unchanged (`DRAFT`) in case (d) |

---

## Issue #30 — Freeze accounting invariants, canonical ERD and ledger architecture

**Zero migrations, zero Kotlin.** This is the design authority every later accounting issue
consumes. Deliverables: `docs/architecture/accounting-foundation.md`,
`docs/database/accounting-erd.md`, and ADRs 0019 (+0020 if the invariants split from the
architecture record).

### The decisions — take these, do not re-open them

**Money.** `NUMERIC(23, 6)` mapped to `BigDecimal`. Six fractional digits covers every ISO 4217
exponent (max 4 — CLF, UYW) plus two digits of accrual/allocation headroom so per-line rounding
residue cannot accumulate; 17 integer digits survives any SACCO-scale aggregate. Rates are
`NUMERIC(20, 10)` with `CHECK (exchange_rate > 0)` — a rate is a ratio, not a quantity of money.
**Binary floating point is banned** in accounting schema and code, enforced by an ArchUnit rule (no
`Double`/`Float` under `com.finaxis.platform.accounting..`) and a schema test (no
`double precision`/`real` column in an accounting table).

**Rounding.** `HALF_EVEN` at the currency's minor unit for anything presented or settled;
intermediates stay at scale 6. `BigDecimal.divide` without an explicit scale and `RoundingMode` is
banned. Allocation residue goes to the leg the posting rule marks `is_residual`; the total is never
re-rounded.

**Direction, not sign.** `direction TEXT NOT NULL CHECK (direction IN ('DEBIT','CREDIT'))` plus
`amount NUMERIC(23,6) NOT NULL CHECK (amount > 0)`. A signed amount makes "debit or credit" depend
on the account's normal balance, which is a derived reading; direction makes the balance invariant
directly expressible and makes zero-value lines impossible. Add a **STORED generated**
`signed_functional_amount` for ad-hoc and reconciliation SQL — computed by the database, so it
cannot drift.

**Currency.** `CHAR(3)` with the same `^[A-Z]{3}$` regex already on
`organisation.base_currency_code`. **No `currency` reference table** — validation stays in
`java.util.Currency.getAvailableCurrencies()` where `TenantSettingCatalog` already does it. A second
source of truth for currency codes is a bug factory.

**Multi-currency — the one "pay now" decision.** Every money-bearing row carries five columns from
day one, always populated: `currency_code`, `amount`, `functional_currency_code`,
`functional_amount`, `exchange_rate`. Single-currency tenants set them equal with `rate = 1`. This
is OFBiz's `origAmount`/`origCurrencyUomId` shape. **The double-entry invariant is enforced on
`functional_amount` only.** Not speculative: retrofitting a functional-currency column onto 200M+
immutable rows is a backfill of unknowable data. Three columns now, zero speculative tables, zero
speculative behaviour.

**Dates.** `created_at` (technical, never drives accounting), `posted_at` (instant the journal
became immutable — a distinct column because a `posting_request` may be created and posted at
different instants), **`business_date`** (the accounting date, read from the `business_date` table
and never from a clock — *it alone selects the fiscal period*), `transaction_date` (the source
module's event date, descriptive), `value_date` (interest/float effect, **never** selects a
period). `business_date ≠ transaction_date` is normal and both are retained. All `TIMESTAMPTZ` are
UTC; `DATE` columns are tenant-local and never timezone-converted.

**Balance invariant.** Cross-row, so not a single `CHECK`. Enforced by a denormalised balanced
header — `total_debit_functional`, `total_credit_functional`, `line_count` with
`CHECK (total_debit_functional = total_credit_functional)`, `CHECK (total_debit_functional > 0)`,
`CHECK (line_count >= 2)` — plus a repeatable header/line proof query shipped as an integration
test and an operational check. **Rejected: a deferred constraint trigger.** The repository has zero
triggers; PL/pgSQL is invisible business logic and the opposite of the declarative-CHECK culture V1
established.

**Immutability.** `journal_entry` and `journal_line` carry `created_at` + `created_by` and **no
`updated_at`, no `updated_by`, no `row_version`** — the `business_date_history` precedent exactly,
and the strongest statement the schema conventions allow while still satisfying
`FoundationSchemaGuidTests`. Physically backed by `REVOKE UPDATE, DELETE` on a dedicated
application role (declarative, visible in `\dp`); a raise-exception trigger is the rejected
alternative, same reason as above. **Note:** the app currently connects as `postgres` locally, so
the least-privilege role is an operations prerequisite for #54 — until it exists the guarantee is
application-level plus a test asserting no UPDATE/DELETE is ever built against these tables.

**Reversal.** Contra-journal, never mutation. A reversal is a **new** `journal_entry` with
`entry_type='REVERSAL'` and `reverses_journal_entry_id`; its lines mirror the original with
`direction` flipped and the **same positive amounts** — never negative amounts, because
negative-amount storno makes turnover reporting wrong (a reversed 1,000 debit must show as 1,000
debit + 1,000 credit of turnover, not zero). The original is never updated; "is it reversed" is
derived, and enforced at most once by
`CREATE UNIQUE INDEX … ON journal_entry (organisation_id, reverses_journal_entry_id) WHERE reverses_journal_entry_id IS NOT NULL`
— one index that both serves the lookup and enforces the rule. **Correction = reversal + re-post**;
there is no edit and no `CORRECTION` entry type. Correction lineage lives on the *mutable* table as
`posting_request.corrects_posting_request_id`, keeping the immutable journal minimal. Record the
deviation from Fineract's `reversed`/`reversal_id` columns, which are an UPDATE of posted history.

**Lineage and idempotency.** business transaction → `posting_request` (accounting-owned, mutable,
FSM'd) → `journal_entry` (1:0..1) → `journal_line` (1:N) → product-owned subledger position
(referenced softly, never FK'd from accounting). **Exactly one idempotency mechanism:**
`posting_request.source_reference TEXT NOT NULL` with
`UNIQUE (organisation_id, source_module, source_reference)`, reusing the proven
`identity_dispatch_log.dispatch_key` pattern. `source_entity_type`/`source_entity_id` are
descriptive drill-down columns — **not** the key and **not** foreign keys, because they point at
modules that may not exist. This is *domain-level* idempotency for in-process module→module calls,
a different layer from `api_idempotency_record`, which handles the HTTP `Idempotency-Key`.

**Journal numbering.** Reuse the existing `reference_sequence` code `JOURNAL`, already seeded per
organisation by `OrganisationBootstrapDefaults.SEQUENCE_CODES`. `entry_number BIGINT NOT NULL`,
`UNIQUE (organisation_id, entry_number)`. Not a PostgreSQL sequence — auditors require gapless
numbering and a sequence loses it on rollback. State the honest cost: the
`UPDATE … RETURNING` holds a row lock until commit, so **journal creation is serialised per
tenant**. At the target envelope (~50 postings/s peak, ~5 ms transaction ⇒ ~200/s ceiling) that is
not the bottleneck. Revisit only on measured posting p99 > 20 ms *and* `reference_sequence`
lock-wait > 10% of it.

**Tenant scoping.** Every accounting table carries `organisation_id UUID NOT NULL REFERENCES
organisation (id)`; every parent reference is a **composite FK on `(organisation_id, parent_id)`**;
every table that is ever a parent declares `uq_<table>_organisation_id UNIQUE (organisation_id, id)`
purely as the FK target — exactly as `branch`, `role` and `user_organisation_membership` do.
`gl_account` is **tenant-scoped, never branch-scoped** — one chart per tenant, branch is a
*dimension on the posting* (matching Fineract, which has no office on `acc_gl_account`).
`journal_entry.branch_id` and `journal_line.branch_id` are both `NOT NULL`.

**Denormalisation on `journal_line` — the key performance decision.** The line carries `branch_id`,
`business_date`, `fiscal_period_id`, `currency_code` and `functional_currency_code` denormalised
from the header. Because the table is append-only with a hard no-UPDATE rule these **cannot drift**,
and they remove a join from every aggregate query. OFBiz does the opposite and its
`AcctgTransEntrySums` view exists precisely to paper over that join.

### Tables deliberately not created

- **`account_mapping`** — Fineract ships *both* `acc_accounting_rule` and `acc_product_mapping`, and
  a reviewer cannot tell which governs a given posting. Finaxis has **one** resolution path:
  `posting_rule` → effective `posting_rule_version` → `posting_rule_leg` → account, with
  `account_resolution = 'PRODUCT_PARAMETER'` as the single indirection point.
- **`control_account`** — a control account *is* a GL account with no independent lifecycle.
  Classification lives on `gl_account.is_control_account` / `control_subledger_kind`.
- **`gl_account_balance` (current balance)** — a stored running balance is a per-account write
  hotspot and a divergence risk; the daily-balance projection gives an as-of balance in one
  index-only row read. (Rejects OFBiz's `GlAccount.postedBalance`.)
- **Any member/product subledger table** — PRODUCT-OWNED-FUTURE. Accounting creates none and holds
  no FK to them.

### Tasks

- [ ] **Task 30.1 — `docs/architecture/accounting-foundation.md`.** Sections: Scope and Non-Goals →
  Benchmark Matrix → Money and Currency → Accounting Dates and Timestamps → Ledger Architecture
  (general ledger, subsidiary ledgers and positions, control accounts, derived balances and
  rollups, reconciliation proof contracts) → Canonical Invariants (numbered INV-1…INV-16 so later
  issues can cite them) → Posting Lifecycle (context diagram, representative transaction sequence,
  reversal sequence) → Fiscal Periods and Concurrency (contract only; #35 owns the proof) →
  Maker-Checker and Privileged Operations (contract only; #34 owns the catalogue) → Performance and
  Query Patterns → **Consuming Issues** → Deeper References.
- [ ] **Task 30.2 — The benchmark matrix.** A real table: rows are the 15 design questions
  (money representation; sign vs direction; multi-currency; transaction container; immutability;
  reversal mechanism; fiscal periods; posting-rule indirection; balance storage; branch dimension;
  trial balance; subledger ownership; control accounts; idempotency and lineage; maker-checker on
  configuration), columns are BIAN / Fowler / Fineract / OFBiz / **Finaxis decision + rationale**.
  The decisions above are the Finaxis column. Notable positions to argue explicitly: adopt
  header+lines (Fowler/OFBiz) and **reject Fineract's headerless `acc_gl_journal_entry`**, because
  without a header there is nowhere for balanced totals, entry number, reversal link or period
  binding, and every "show me this journal" becomes a self-join; adopt Fowler's strict immutability
  and **reject Fineract's in-place `reversed` flag**; adopt Fowler's Posting Rule and **reject
  Fineract's dual mechanism**; adopt OFBiz's dual-amount currency shape; **reject OFBiz's stored
  `postedBalance`** and its `AcctgTransEntrySums` view.
- [ ] **Task 30.3 — `docs/database/accounting-erd.md`.** Mirror `foundation-schema.md`
  section-for-section: planned-migration table (which issue creates what) → Entity Relationships
  (one `erDiagram`, UPPER_SNAKE nodes, relationship lines only, no attribute blocks, lowercase verb
  labels — the repo's exact style) → **Table Classification** (AUTHORITATIVE vs PROJECTION ×
  ACCOUNTING-OWNED vs PRODUCT-OWNED-FUTURE) → Identifier Conventions (deltas only, pointing at
  `foundation-schema.md`) → Tenant Isolation → Table Notes → **Column-Level Design** in V1's
  declarative DDL style so #36/#40/#44 lift it directly → Audit Columns and Immutability → Indexing
  → Query Patterns Served → Tables Deliberately Not Created → Cross-Links To Implementing Issues.
  Tables to design: `accounting_fiscal_year`, `accounting_fiscal_period`, `gl_account`,
  `posting_request`, `journal_entry`, `journal_line`, `posting_rule`, `posting_rule_version`,
  `posting_rule_leg`, `control_account_reconciliation_run`, `gl_account_daily_balance`
  (**the only projection**), and three `*_transition_log` tables following the V1 shape. Use short
  index names — PostgreSQL's identifier limit is 63 characters and V1 already shortens
  (`idx_membership_transition_log_organisation_entity`).
  Two constraint details worth flagging now: `accounting_fiscal_period` wants an
  `EXCLUDE USING gist (organisation_id WITH =, daterange(start_date, end_date, '[]') WITH &&)` for
  non-overlap, which needs `btree_gist` — **verify it is available in both the Zonky codegen
  container and the Testcontainers image before #36**, with the fallback being application-level
  enforcement via `SELECT … FOR UPDATE` on the fiscal year plus a concurrency test. And
  `posting_rule_version` wants the same `EXCLUDE` scoped `WHERE (status = 'ACTIVE')`, because
  **posting rules must be effective-dated**: a prior-period correction has to re-post with the rule
  that was effective on that transaction's business date, not today's.
- [ ] **Task 30.4 — Performance section.** State target volumes as *assumptions*: 10 tenants;
  largest tenant 250k members, 40 branches, 500–2,000 GL accounts, 200k financial transactions/day,
  ~4 lines each ⇒ **800k `journal_line` rows/day, ~200M/year, ~1.4B over 7-year retention**
  (~40 GB heap/year plus indexes); ~50 postings/s peak. Then the seven query patterns, each with
  the index or structure that serves it and its shape. Q2 (member/product statement) is
  **explicitly not served by the GL** — it belongs to the product-owned subledger in #50, and
  `journal_line.subledger_reference` exists only for drill-down and reconciliation.
  **Index discipline:** #40 creates only the `guid` unique, `uq_journal_line_entry_number`, the FK
  indexes, and `idx_journal_line_account_date (organisation_id, gl_account_id, business_date, id)
  INCLUDE (direction, functional_amount, branch_id, journal_entry_id)`. The branch-first index
  (#49) and the subledger index (#46) are **specified here but created by the issue that introduces
  the query**, each with an `EXPLAIN (ANALYZE, BUFFERS)` plan in its PR — V1's "no speculative
  indexes" rule applied forward.
  **Exactly one projection is recommended**, and the justification is arithmetic not taste: a
  monthly trial balance scans ~16M lines (~1–3 s warm) and is survivable; an as-of balance over 1.4B
  rows is not. `gl_account_daily_balance` is written only for (account, branch, currency, day)
  combinations that had movement, built by the existing COB pipeline, and **rebuildable from
  `journal_line` by a documented deterministic query that ships alongside it**.
  **Keyset pagination contract:** sort key `(business_date DESC, id DESC)`; the predicate is the
  row comparison `(business_date, id) < (:cursorDate, :cursorId)`, not the expanded OR form, so
  PostgreSQL uses one backward index range scan; the cursor is an opaque base64 and `OFFSET` is
  banned; `from`/`to` are mandatory with a capped window (default 366 days); page size caps at
  `MAXIMUM_PAGE_SIZE = 100` from `common/web/api/ApiPage.kt`.
- [ ] **Task 30.5 — EXPLAIN validation plan.** Specify `AccountingQueryPlanTests`, introduced in #40
  and extended by #46/#47/#49: seed ~200k lines across 24 months / 40 branches / 500 accounts with
  `generate_series`, `ANALYZE`, then for each pattern run `EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)`
  and assert no `Seq Scan` on `journal_line`/`journal_entry`, the expected index name appears, the
  top node is an index scan, and shared-block reads are under a documented per-pattern budget.
  **Do not assert `Heap Fetches = 0`** — it is visibility-map dependent and flaky. Put the budget
  numbers in the doc, not only the test, so a regression is a review conversation.
- [ ] **Task 30.6 — Partitioning posture.** **Do not partition in Phase B**, and be honest about
  why "partition-friendly" is limited here: PostgreSQL requires the partition key in every unique
  constraint, and this repo mandates `id UUID PRIMARY KEY` plus a **single-column** unique on `guid`
  (asserted by `FoundationSchemaGuidTests`) — which is incompatible with declarative RANGE
  partitioning on `business_date`. What Phase B actually delivers is: `business_date` is NOT NULL,
  immutable and present on both tables; every reporting query carries a date-range predicate; **no
  table outside accounting holds an FK into `journal_line`**, so a future split breaks nothing; and
  retention is expressible as a date range. Remedy sequence: archive closed fiscal years into an
  index-light cold table first (no convention conflict, reversible); only then write an ADR
  superseding the `guid` convention for the two hot tables. **Evidence threshold — any one, measured:**
  `journal_line` > 500M rows in one database; largest tenant's heap+indexes > 500 GB; autovacuum
  cannot finish inside the COB window; or Q1 p95 > 500 ms with a warm cache and a confirmed
  index-only plan. Absent all four, do not partition.
- [ ] **Task 30.7 — ADR(s) and the citation contract.** ADR 0019 covers the ledger architecture and
  benchmark positioning; split the money/immutability invariants into 0020 if 0019 grows past
  reviewable length. Add a `## Consuming Issues` table mapping each later issue to the section it
  implements (#31 → ledger architecture + INV-11/INV-12; #35 → fiscal periods + dates; #36 →
  column-level design for the calendar and chart; #40 → column-level design for the journal + the
  #40 index set only; #41/#42 → lineage and idempotency; #43 → the reversal model; #44/#45 → posting
  rules; #46 → control accounts; #47 → rollups; #49/#50/#51 → query patterns and pagination; #54 →
  the `REVOKE` prerequisite and the partitioning threshold). That literally satisfies the issue's
  "later accounting issues are cross-linked" criterion.
- [ ] **Task 30.8 — What #30 must NOT do.** No migration; no Kotlin under `src/main`; no addition to
  `FoundationApplicationTables.APPLICATION_TABLES`; no change to `FoundationSeedDataTests`; no
  permission code; no dependency; no `accounting` package (that is #31). It *may* add cross-links
  from `docs/database/foundation-schema.md` and a one-line pointer in `CLAUDE.md`.

---

## Issue #31 — Spring Modulith accounting boundary and financial ports

**Zero migrations, zero accounting tables, zero generated jOOQ accounting types.**

### Package skeleton

```
src/main/kotlin/com/finaxis/platform/accounting/
├── AccountingPermissionGuard.kt              port — iam supplies the adapter
├── AccountingBusinessDateLookup.kt           port — lifecycle supplies the adapter
├── AccountingTenantLookup.kt                 port — lifecycle supplies the adapter
├── domain/{MonetaryAmount,PostingSide,AccountingContext}.kt
├── application/posting/{PostingService,PostingCommands,PostingReceipt,PostingErrorCodes}.kt
├── application/port/outbound/{AccountingContextLookup,AccountingAuditTrail}.kt
├── adapter/outbound/context/RequestContextAccountingContextLookup.kt
├── adapter/outbound/audit/AuditServiceAccountingAuditTrail.kt
└── config/AccountingModuleConfiguration.kt

src/main/java/com/finaxis/platform/accounting/
├── package-info.java                         @ApplicationModule("Accounting")
├── domain/package-info.java                  @NamedInterface("domain")
└── application/posting/package-info.java     @NamedInterface("posting")
```

`adapter/inbound/` is deliberately absent — #31 adds no controller, so the
`HexagonalArchitectureTest` web-adapter allow-list needs no edit (hazard 10).

`allowedDependencies` for accounting: `common::application` (error codes flow through the sealed
`ApplicationException` family so `ApiExceptionHandler` maps accounting failures to the same problem
contract without accounting owning a web adapter), `common::audit`, `common::context` (only
`RequestContextAccountingContextLookup` may touch the thread-local), and `common::id`. Deliberately
excluded: `lifecycle`/`iam` (inverted ports instead), `jooq` (no tables yet — #40 adds it),
`common::transitions` (no FSM, publishes nothing — #41 adds it if needed),
`common::persistence`/`web-*`/`jobs`.

**Clock and identifiers get no port.** Accounting constructor-injects `java.time.Clock` (the single
bean in `config/ApplicationConfiguration.kt`) and calls `common.id.uuidV7()`. `Clock` *is* the
platform abstraction — a JDK type, injectable, substitutable with `Clock.fixed(...)` — and
`uuidV7()` is already guarded by ArchUnit. Wrapping either adds indirection with no boundary
benefit. Record this in the module KDoc so a reviewer sees it was decided, not forgotten.

### Tasks

- [ ] **Task 31.1 — Package skeleton and the three `package-info.java` files.** Consumers will
  declare `"accounting::posting"` and `"accounting::domain"` — the same two-entry shape `iam`
  already uses for `lifecycle::application` + `lifecycle::domain`.
  `application.port.outbound`, `adapter.**` and `config` get **no** `@NamedInterface` and are
  therefore Modulith-internal.
- [ ] **Task 31.2 — Cross-module ports accounting declares** (root package, mirroring
  `lifecycle/PermissionGuard.kt`): `AccountingPermissionGuard` (`requireTenantPermission`,
  `requireBranchPermission` — adapter in `iam`); `AccountingBusinessDateLookup` returning
  `AccountingBusinessDate(organisationId, businessDate, postingAllowed)` — **read-only by
  construction, accounting can never advance, reopen or close a business date**, and
  `postingAllowed` is lifecycle's judgement so accounting never interprets lifecycle's status
  vocabulary; `AccountingTenantLookup` (`isOrganisationPostable`, `isBranchPostable` —
  boolean-only, so accounting never sees lifecycle state enums).
- [ ] **Task 31.3 — Accounting's own narrow ports and adapters.** `AccountingContextLookup`
  (`current()`, `require()`) with `RequestContextAccountingContextLookup`; `AccountingAuditTrail`
  (`recordSuccess`, `recordRejection` — the latter using `recordIndependently` so the row survives
  caller rollback) with `AuditServiceAccountingAuditTrail`; and `AccountingModuleConfiguration` as
  the single wiring point, with adapters free of Spring stereotypes so unit tests can construct
  them directly.
  **Flag in the PR description:** `AccountingAuditTrail` is indirection the repo does not use
  elsewhere — `iam` and `lifecycle` call `AuditService` directly. It is justified because the issue
  requires an audit port and accounting will audit heavily, but the fallback (inject `AuditService`
  directly, delete the port and adapter) should be offered to the reviewer.
- [ ] **Task 31.4 — The public posting-intent API.** `PostingService.post(PostFinancialFactsCommand)`
  / `.reverse(ReversePostingCommand)` returning `PostingReceipt`, with
  `PostingIntent` as a sealed interface of `Facts(eventCode, List<FinancialFact>)` — the preferred
  form, where the product module states what economically happened and accounting resolves the
  posting rule — and `Legs(List<PostingLeg>)` for callers that legitimately own their double-entry
  shape, still validated for balance and account postability. `MonetaryAmount(BigDecimal, currency)`
  stays **policy-free**: scale and rounding are #30's to freeze.
  What the contract deliberately does not do: no journal id as input except for reversal; account
  *codes*, never ids; no `insert`/`update`/`delete` verbs; no jOOQ, `Record`, `DSLContext` or
  `Result` types; no `select`/`where`/`orderBy` shapes. That is "posting intent, not SQL-oriented
  commands and not journal CRUD" made structural.
- [ ] **Task 31.5 — Resolve the business-date access problem (hazard 4).**
  **Chosen: accounting declares the port, lifecycle ships the adapter.** Add `"accounting"` to
  `lifecycle`'s and `iam`'s `allowedDependencies`; create
  `lifecycle/adapter/outbound/accounting/LifecycleAccountingBusinessDateAdapter.kt` and
  `LifecycleAccountingTenantAdapter.kt`, and `iam/adapter/outbound/authorization/AccountingPermissionGuardAdapter.kt`.
  **Rejected: a new `lifecycle::businessdate` named interface with accounting depending on
  lifecycle** — it reverses the repo's inversion convention and points the arrow the wrong way for
  #39, where close-of-business will need lifecycle to ask accounting whether all periods are
  closed; that later need would become a hard cycle. **Rejected: moving business date into
  `common`** — it would drag the store, its jOOQ adapter, its history store, its permission checks
  and its COB state machine into a package with no `@ApplicationModule` and no authorization.
  Resulting graph stays acyclic: `iam → lifecycle`, `iam → accounting`, `lifecycle → accounting`,
  `lifecycle → notifications`, all → `common`, persistence → `jooq`. Add accounting's
  `package-info.java` **before** adding `"accounting"` to lifecycle/iam, and run
  `ApplicationModules.verify()` after each step — it fails loudly on an unresolvable
  `allowedDependencies` string.
- [ ] **Task 31.6 — ArchUnit rules.** Create
  `src/test/kotlin/com/finaxis/platform/architecture/AccountingBoundaryRuleTests.kt` with five
  rules: nothing outside accounting depends on `accounting.adapter..`, `accounting.config..` or
  `accounting.application.port..`; only `accounting.adapter.outbound.persistence..` may touch
  generated accounting jOOQ tables (matched by a `DescribedPredicate` over a reserved
  `ACCOUNTING_TABLE_TYPES` name set, KDoc-linked to #30's ERD); accounting does not depend on
  `iam`, `lifecycle` or `notifications`; accounting does not depend on
  `org.springframework.amqp..`, `io.namastack.outbox..` or `org.jobrunr..` (no broker or background
  job in the posting critical path); and accounting `domain` stays free of framework and
  persistence types.
  Add to `ModuleDependencyRuleTests.kt` the **global no-direct-RabbitTemplate rule** the issue asks
  for — there is none today beyond the web-adapter package restriction, and with zero
  `RabbitTemplate` usages in `src/main` it passes immediately and locks the invariant in. A second,
  broader "only messaging adapters depend on AMQP at all" rule may already be violated by an
  existing AMQP `@Configuration`; run it in isolation first and narrow the exclusion list rather
  than deleting it.
  Bump `BianModuleMappingTests.MINIMUM_MODULES` from 4 to 5.
- [ ] **Task 31.7 — Tests.** Unit tests for `RequestContextAccountingContextLookup` (null context,
  null tenant, null actor, full context round-trip, absent branch still succeeds, `require()` throws
  with `NO_ACTIVE_CONTEXT`, and the context is restored after `with(...)` so no ThreadLocal leaks);
  `AuditServiceAccountingAuditTrail` (success uses `record`, rejection uses `recordIndependently`,
  captured `AuditCommand` field mapping); `LifecycleAccountingBusinessDateAdapter` (`postingAllowed`
  true only for `OPEN`). Plus `AccountingModuleContextTests` asserting both accounting beans are
  injectable **and that `getBeanNamesForType(PostingService::class.java)` is empty** — an explicit
  assertion that #31 ships the contract without an implementation, so a later accidental partial
  implementation is a visible change.
- [ ] **Task 31.8 — Docs.** `docs/architecture/accounting-module-boundary.md` (what the module owns,
  what crosses the boundary with the exact `allowedDependencies` entries a consumer declares, the
  port→module→adapter table, the business-date decision with both rejected options, the clock/id
  no-port decision, the guarding architecture tests, a checklist for adding a product module that
  posts, and what is not yet implemented). Fill the `### accounting` section in the BIAN landscape
  doc. Record the JaCoCo deferral in `docs/development/static-analysis.md` (hazard 8). Update
  `README.md` and add `accounting` to `CLAUDE.md`'s module list as "boundary and posting contracts
  only".

### Can this pass `ApplicationModules.verify()` with no tables and no beans of substance?

**Yes.** Every type references only `java.*`, `kotlin.*`, `org.springframework.context.annotation.*`
and `common.{application,audit,context}` — no jOOQ, nothing to generate. Modulith's verification is
a static ArchUnit-based analysis over compiled types, not a bean-graph check, so even a
pure-interface module verifies; the real failure modes are an `allowedDependencies` string that
resolves to nothing (fails loudly, naming the string) and a cycle. What makes it a live module
rather than a folder of interfaces is two real beans from `AccountingModuleConfiguration`, both
constructible with no database. Nothing autowires `PostingService`, so its missing implementation
cannot fail context startup. `Documenter` simply emits one more `module-accounting.adoc`.

---

## Issue #34 — Accounting permissions and privileged-operation policy

**The only migration in this plan.** Re-check `main` for the next free version at implementation
time — currently V5. Depends on #31 (needs the `com.finaxis.platform.accounting` package).

### The catalogue — 26 codes, `module_code = 'accounting'`

Introduce `accounting` as a new `module_code` rather than reusing `settings`: the existing values
name the owning capability area, and `cob.start → settings` is exactly the mismatch this repository
already regrets. #31 creates a real accounting Modulith module; the permission's module code should
name it.

**UUID block `41000000-0000-0000-0000-0000000000NN`, NN = 01–26, contiguous.** Keeps the leading `4`
meaning "permission catalogue", freezes `40000000-…` as the foundation block, and gives every future
domain an obvious non-colliding block (`42000000` savings, `43000000` loans). Put the block-allocation
rule in a comment at the top of the migration. **Rejected: filling V2's gaps (26–29, 34–39)** — it
interleaves accounting codes into the IAM numbering and makes `ORDER BY id` unreadable.

| Area | Codes | Risk |
| --- | --- | --- |
| Chart of accounts | `gl_account.view` LOW, `.create` MED, `.update` MED, `.submit` MED, `.approve` **HIGH**, `.deactivate` **HIGH** | approval *is* activation |
| Fiscal calendar | `fiscal_period.view` LOW, `.open` **HIGH**, `.close` **CRIT**, `.reopen` **CRIT** | year and period share these |
| Journals | `journal.view` LOW, `.create_manual` **HIGH**, `.submit` MED, `.approve` **CRIT**, `.reverse` **CRIT**, `.post_prior_period` **CRIT** | approval *is* posting |
| Posting rules | `posting_rule.view` LOW, `.create` **HIGH**, `.update` **HIGH**, `.submit` MED, `.approve` **CRIT** | highest blast radius |
| Reconciliation | `reconciliation.view` LOW, `.run` MED, `.resolve` **CRIT** | resolve can hide a real error |
| Reporting | `accounting_report.view` LOW, `.export` MED | export differs in exfiltration risk |

Totals: 6 LOW, 7 MEDIUM, 6 HIGH, 7 CRITICAL ⇒ **13 HIGH/CRITICAL**. Catalogue 54 → **80**.

**Grouped, and why it does not weaken control:** `activate` folds into `approve` for both the chart
of accounts and posting rules, because the FSM has one `PENDING_APPROVAL → ACTIVE` transition and a
separate code would gate nothing an approver does not already do. `post` folds into
`journal.approve` — **the only code the issue lists that is deliberately removed** — because an
approved-but-unposted journal is a promise the ledger cannot audit; if deferred posting is ever
needed the right model is a scheduled `posting_request`, not an unposted journal. Argue this in the
ADR. Fiscal *year* and *period* share four codes because the year is a container and no realistic
role closes periods but not years. Trial balance, GL ledger, statements and drill-down share
`accounting_report.view` because they are the same data at different aggregations.

**Kept separate, deliberately:** `create` vs `update` (matches `tenant.create`/`tenant.update_draft`
and `role.create`/`role.update`); `submit` vs `create` everywhere, which is what supports a real
SACCO three-eyes control (clerk prepares, supervisor submits, approver approves) — removing
`submit` collapses it to two eyes; `journal.post_prior_period` apart from `journal.approve` so
break-glass is independently grantable and revocable; `reconciliation.run` (MEDIUM) apart from
`.resolve` (CRITICAL), because running a proof is safe and accepting a break is the control failure.

### Tasks

- [ ] **Task 34.1 — The migration.** Copy V4's structure exactly: a fail-fast `DO $$` block, the
  inserts, the grants, then a **post-condition `DO $$` block**. Preconditions to assert (each one a
  fact this migration actually depends on): the two V2 platform roles exist and are `ACTIVE`; the V3
  bootstrap `local-admin` role exists and is `ACTIVE`; `PLATFORM_SUPER_ADMIN` currently holds the
  *entire* catalogue — the invariant this migration must preserve, so fail here rather than later;
  and no `accounting` code or `41000000-…` id already exists. **Rejected: an exact `COUNT(*) = 54`
  precondition** — it would break any deployment carrying a legitimate out-of-band permission and
  asserts something V5 does not depend on. Post-conditions: 26 accounting rows exist,
  `PLATFORM_SUPER_ADMIN` is missing nothing, and `local-admin` holds exactly 10 accounting grants.
  Making the migration enforce what the tests assert means a mistake surfaces in every environment,
  not only in CI.
- [ ] **Task 34.2 — Grants.** `PLATFORM_SUPER_ADMIN` gets all 26 via a set-based
  `INSERT … SELECT … WHERE module_code = 'accounting'` with
  `ON CONFLICT ON CONSTRAINT uq_role_permission DO NOTHING` — V2's superset grant ran once and
  cannot pick these up (hazard 2). **`PLATFORM_SUPPORT` deliberately receives nothing**: platform
  staff must not read tenant financial data by default; a support-escalation role is a separate,
  audited change. Bootstrap `local-admin` receives exactly the ten **tenant-configuration** codes —
  the six `gl_account.*`, `fiscal_period.view`, `fiscal_period.open`, `posting_rule.view`,
  `accounting_report.view` — including `approve`, because `local.checker` already exists as a
  distinct actor (that is precisely why V4 seeded it), so the maker-checker chart-of-accounts flow
  is exercisable out of the box. `journal.*`, `posting_rule.approve`, `fiscal_period.close/reopen`,
  `journal.post_prior_period` and `reconciliation.resolve` stay out: operational and break-glass,
  not configuration. This satisfies "bootstrap remains operable without granting all users
  accounting privileges" without repeating the V3→V4 mistake.
- [ ] **Task 34.3 — Resolve the existing-tenant backfill (hazard 3).** `grantPermissions` runs only
  inside `approveProvisioning`, so no already-ACTIVE organisation receives new codes.
  **Rejected: a set-based `INSERT … SELECT` over all organisations in the migration** — it would have
  to create the new org-local roles in SQL, duplicating `OrganisationBootstrapDefaults` and creating
  a second source of truth for role bundles, which is exactly the defect ADR 0010 documents.
  **Rejected: a Kotlin startup backfill** — invisible, untestable mutation of authorization data at
  boot. **Recommended:** the only ACTIVE organisations that exist are `PLATFORM` (V2) and
  `FINAXIS-LOCAL` (V3), both handled explicitly by id in this migration, and ADR 0010 records that
  the platform has never been deployed. Every organisation created after V5 gets the codes from
  `createDefaultRoles`. Document the general mechanism to build once there are multiple live
  tenants: an idempotent, platform-permission-gated `reseedOrganisationDefaults(organisationId)`
  command that re-invokes the already-idempotent `createDefaultRoles`. **Verify at implementation
  time that `main` still has no live tenants beyond these two.**
- [ ] **Task 34.4 — `OrganisationBootstrapDefaults`.** Add an `ACCOUNTING_ADMINISTRATION_CODES` set
  and extend the bundles: `TENANT_ADMIN` += administration codes but **not**
  `journal.create_manual`/`approve`/`reverse`/`post_prior_period`, `reconciliation.resolve` or
  `fiscal_period.reopen` (it holds `role.assign_permission` so it can self-grant anyway — making the
  split real in the default bundle is safe-by-default posture, not a security claim);
  `TENANT_AUDITOR` += the six view codes; `BRANCH_MANAGER` += `accounting_report.view`;
  `BRANCH_OPERATOR` unchanged. Add **two new org-local system roles** created at approval time like
  the existing five: `ACCOUNTING_OPERATOR` (maker — view/create/update/submit across areas, plus
  `reconciliation.run` and `accounting_report.export`) and `ACCOUNTING_APPROVER` (checker —
  approve/deactivate/reverse, `fiscal_period.open`/`close`, `reconciliation.resolve`). **Neither
  holds `fiscal_period.reopen` nor `journal.post_prior_period`** — break-glass is granted
  deliberately, per tenant. These are composable bundles; nothing anywhere evaluates the role names.
- [ ] **Task 34.5 — Kotlin scaffolding.** `AccountingPermissions` (the repository's **first** central
  permission-code constants object — 26 `const val`s plus grouped sets, each KDoc'd),
  `AccountingPrivilegedOperations` (a registry of operation → permission, risk, maker-checker
  required, audit action, with an explicit `MAKER_CHECKER_REQUIRED` set), and
  `AccountingAuditActions` (the action-name constants).
- [ ] **Task 34.6 — Maker-checker: adopt the persisted-checker shape, and move the check into the
  FSM.** Use the `organisation_initial_administrator_bootstrap` shape — persisted
  `submitted_by`/`submitted_at`/`approved_by`/`approved_at` plus a declarative
  `CHECK (approved_by IS NULL OR approved_by <> submitted_by)` on every approvable accounting
  aggregate (the columns are specified in #30's ERD; the DDL lands in #36/#40/#44). **Reject the
  `approveUser` shape** for accounting because of its three verified escape hatches: a NULL
  `created_by` silently disables the check, `SystemActor.ID` is exempt, and the check sits in the
  service rather than the FSM so any other path into the transition bypasses it. Acceptable for user
  invitations; not for financial approval. An auditor asking "who approved journal 12345" must get
  the answer from the row, not from a correlated `audit_event` scan.
  **Then build `SeparationOfDutiesGuard` in `common.transitions`** — verified viable:
  `TransitionContext` already carries `actor: TransitionActor` (line 13), populated by
  `TransitionExecutor.buildContext` (line 56), and `validate` runs `definition.guards` before any
  state mutation. A `TransitionGuard` parameterised with a `makerOf: (A) -> UUID?` lambda, attached
  to each accounting graph's `APPROVE` transition, declares the rule **next to the transition** so
  no other code path can bypass it. Defaults: `systemActorExempt = false` (a `SystemActor` approval
  of a manual journal is never legitimate) and a null maker is a **rejection, not a pass**.
  `LifecyclePrerequisites` needs no change — the guard takes the actor from the context.
  Ships here with unit tests against a fake aggregate; wired to real graphs in #38/#39/#43/#45/#48.
- [ ] **Task 34.7 — Resolve the audit-coverage collision (hazard 1).** All 13 new HIGH/CRITICAL codes
  need entries in `auditedActionByPermission`. Invariant 2 is satisfied by `literalAuditActions()`,
  which does a plain text `source.contains("\"$it\"")` over `src/main/kotlin` — so shipping
  `AccountingAuditActions.kt` makes it pass, **but that is a text match, not a call site**. Be honest
  about that in the test's KDoc and add a **ratchet** so the gap cannot persist silently: a
  `callSiteAuditActions()` helper that runs the same walk while excluding
  `AccountingAuditActions.kt` by filename, a `pendingEnforcement` map from each of the 13 actions to
  the issue that will wire it (`"journal.approve" to "#48"`, `"fiscal_period.close" to "#39"`, …),
  and a third test asserting `pendingEnforcement.keys intersect callSiteAuditActions()` is empty.
  The set can only shrink: the moment #38 adds a real `auditService.recordSuccess(action = …)` call,
  the test fails until that entry is deleted. That is what makes critical actions genuinely *visible*
  to the coverage test rather than merely present in it.
- [ ] **Task 34.8 — Tests that must change in lockstep** (hazard 2). `FoundationSeedDataTests`:
  `EXPECTED_CATALOGUE_SIZE` 54 → **80**, `expectedPermissionCodes` += 26,
  `expectedLocalAdminPermissionCodes` 23 → **33**. The `platform support holds only its two
  operational read permissions` and `platform super admin holds the entire permission catalogue`
  tests stay **unchanged** — the second is the one that catches a missing grant.
  New tests: `AccountingPermissionCatalogueTests` (26 codes exist and are ACTIVE, ids form a
  contiguous block with no gaps, risk levels match the documented matrix);
  `AccountingPermissionConstantsTests` (every constant exists in the DB **and vice versa** — closes
  the inline-string-literal drift gap for accounting from day one);
  **`OrganisationBootstrapRolePermissionTests`** asserting every code named in
  `ROLE_PERMISSIONS` exists and is ACTIVE in `permission` — *this is the test that would have caught
  the ADR-0010 V8 bug and is the single most valuable regression here*; a property test that **no
  default role holds both sides of any maker/checker pair** and that neither break-glass code appears
  in any default role; and `SeparationOfDutiesGuardTests`.
  Explicitly **not** changed (no tables added): `FoundationApplicationTables`,
  `FoundationSchemaGuidTests`, `FoundationSchemaMigrationTests`, `FoundationJdbcEntities`.
  Do **not** widen JaCoCo scope here.
- [ ] **Task 34.9 — Docs.** New `docs/security/accounting-authorization.md` (its peer is
  `authorization-model.md`, so `docs/security/` not `docs/architecture/`). ADR 0021 covering the
  catalogue, the grouping arguments — especially folding `post` into `approve` — the
  no-role-name-checks rule, the PLATFORM_SUPPORT decision, the bootstrap posture, break-glass, the
  persisted-checker + FSM-guard choice, and the backfill posture.
  **Update `docs/security/authorization-model.md`**, which currently hard-codes stale facts: "54
  permission codes" → 80; module codes gain `accounting`; the org-local role list gains the two new
  roles. **Fix a pre-existing bug in the same PR:** the doc says `TENANT_AUDITOR` holds `audit.view`
  and `business_date.view`, but `ROLE_PERMISSIONS["TENANT_AUDITOR"]` actually holds 14 codes.
  Add the V5 row to `docs/database/foundation-schema.md`'s migration table and to `CLAUDE.md`'s
  V1–V4 enumeration — **that `CLAUDE.md` line is the lockstep item most easily missed.**

**Known gap to record, not fix:** `AuthorizationService.can(principal, code, ResourceRef)` checks
only organisation equality, and `ResourceRef.branchId`/`warehouseId`/`ownerId` are unused. Accounting
will need branch-scoped resource checks (branch trial balance, branch-scoped manual journals). Do
**not** change `AuthorizationService` in #34 — record the gap in the ADR and open a follow-up for #52.

---

## Issue #35 — Accounting-date, fiscal-period and posting concurrency semantics

**Zero migrations.** Ships policy, ports, a real lock primitive and real concurrency proofs — no
fiscal-period tables, which are #36's.

### The concurrency decision

**Row locks, not a per-period advisory lock.** `pg_advisory_xact_lock` is exclusive-only, so keying
it on the fiscal period would serialize *every posting in that period* behind one lock — a silent
throughput cliff, unacceptable for a ledger. The issue's wording ("row lock/advisory-lock") leaves
the choice open; take the row lock.

| Actor | Statement | Why |
| --- | --- | --- |
| Posting | `SELECT … FOR SHARE` on the period row | Shared: N postings never block each other. Conflicts with `FOR UPDATE` **and** `FOR NO KEY UPDATE`, so a close blocks even if a future path forgets its explicit lock |
| Close / reopen | `SELECT … FOR UPDATE`, then `UPDATE` | Exclusive: waits out every in-flight posting |

`FOR KEY SHARE` is rejected — it does not conflict with `FOR NO KEY UPDATE`, so a bare
`UPDATE status = 'CLOSED'` would slip past it. Global `SERIALIZABLE` is rejected per the issue.

**The protocol is correct only at READ COMMITTED, and that is load-bearing.** Sequence: unlocked
`findCovering` → `lockForPosting` (`FOR SHARE`) → re-validate the status **returned by the locking
statement** → write. Under READ COMMITTED, acquiring the lock triggers PostgreSQL's `EvalPlanQual`
re-read so the status is the latest *committed* value; under REPEATABLE READ it would return the
stale snapshot or raise a serialization failure. The repo is entirely READ COMMITTED today.
`FiscalPeriodStateStore.lockForPosting` returns a freshly read snapshot precisely so a caller cannot
reuse the stale one — **the port makes the mistake unrepresentable**. A test asserts the isolation
level, so anyone who later escalates it breaks a named test instead of correctness.

**Advisory-lock namespacing (hazard 5):** new domains use the **two-int** form
`pg_advisory_xact_lock(classid, objid)`. PostgreSQL keeps the 64-bit single-key space and the
32-bit-pair space structurally separate (`pg_locks.objsubid` 1 vs 2), so a new domain cannot collide
with the two existing sites **by construction rather than by hash luck**. A new
`common/persistence/AdvisoryLockNamespace.kt` is the registry; `objid` is the low 32 bits of
`hashtextextended(...)`, whose worst case is false sharing (over-serialization), never incorrect
behaviour. Migrating the two legacy single-key sites is a follow-up issue, not #35.

### The accounting-date model

| Date | Source | Authoritative for | Rule |
| --- | --- | --- | --- |
| `recordedAt` | the `Clock` bean (`systemUTC`) | `created_at` only | Never selects a period |
| `businessDate` | `business_date.current_business_date` via the #31 port | the tenant's "today" | Read **once** per posting, carried in the command |
| `transactionDate` | caller | when the event occurred | Defaults to `businessDate`; must not exceed it |
| `valueDate` | caller | interest and accrual effect | May be before **or after** `postingDate`; never selects a period |
| **`postingDate`** | derived | **the fiscal period, and only this** | Defaults to `businessDate` |

Rules: future `postingDate` → reject (`posting_date_in_future`), no forward-dated GL; equal →
`CURRENT`, no extra permission; earlier → `BACKDATED`, requires `journal.post_prior_period` **and**
an OPEN period; a `CLOSED` period → reject (`fiscal_period_closed`) **regardless of permission**,
because reopening is an explicit audited operation and never implicit; no covering period → reject
(`fiscal_period_not_found`), because periods are provisioned and never auto-created by a posting;
`business_date.status != 'OPEN'` → reject `CURRENT` postings (`business_date_not_open`) while still
allowing backdated corrections, so COB does not deadlock.

**Business date rolling in flight:** the posting reads the business date **without a lock** —
deliberately, so one `business_date` row never serializes the ledger — and captures `postingDate` at
transaction start. A concurrent `advance` (unchanged optimistic `row_version` CAS) may commit
meanwhile; the posting still commits with its captured date, which is correct because yesterday's
period is still OPEN until closed. The invariant the row lock protects is narrower and stronger:
*no journal is ever committed into a period the same transaction observed as closed.*

### Tasks

- [ ] **Task 35.1 — Extend the accounting module** created in #31 (do not duplicate its
  `package-info.java`). If #31 has not merged, create it per Task 31.1 and reconcile.
- [ ] **Task 35.2 — Pure domain policy.** `accounting/domain/AccountingDates.kt`
  (`AccountingDates`, `PostingDateClassification{CURRENT,BACKDATED,FUTURE_DATED}`,
  `PostingDateRequest`), `PostingDatePolicy.kt` (`resolve`, `classify` — no Spring, no clock, no
  persistence, so every rule above is unit-testable; push each rejection into its own private
  helper to stay under Detekt's `ThrowsCount` of 2), and `AccountingPermissions.kt` holding the
  code constants so policy compiles before #34's migration exists.
- [ ] **Task 35.3 — Fiscal-period ports.** `accounting/application/FiscalPeriodPorts.kt`
  (`FiscalPeriodKey`, `FiscalPeriodStatus{FUTURE,OPEN,CLOSED}` — `SOFT_CLOSED` deliberately omitted
  pending #30, `FiscalPeriodSnapshot`, and `FiscalPeriodStateStore` with `findCovering` /
  `lockForPosting` / `lockForStateChange` / `updateStatus`), `PostingPeriodResolver.kt` carrying the
  linearizability guarantee, and `FiscalPeriodStateChangeGuard.kt` as the serialization point #39's
  close/reopen use cases must go through.
- [ ] **Task 35.4 — The lock primitive.** `accounting/adapter/outbound/persistence/PostgresRowLock.kt`,
  parameterised over table and id field so it ships before #36 creates the table, guarded by the
  same `requireActiveTransaction()` check `JooqIdempotencyStore` uses — a row lock on an autocommit
  connection is released instantly, which is exactly the failure mode that guard exists for. Plus
  `common/persistence/AdvisoryLockNamespace.kt`.
  **Rejected: shipping only the interface and deferring all SQL to #36** — it would leave #35 with
  nothing real to prove against PostgreSQL, which the issue explicitly forbids.
- [ ] **Task 35.5 — Real concurrency proofs without the table.** A test-only `FiscalPeriodStandIn`
  binds the production `PostgresRowLock` to an existing tenant-scoped table using plain jOOQ — **not**
  via `OrganisationSettingsStore`, which would drag in its own advisory lock and conflate two lock
  domains. The only thing substituted is where the `status` byte lives; the lock statements, lock
  strengths, the lookup→lock→revalidate order, the READ COMMITTED re-read, the transaction manager,
  the pool and PostgreSQL 18.4 are all production. #36 deletes this one file and adds a real adapter.
  `FiscalPeriodConcurrencyIntegrationTests.kt` (Bucket P, latch-driven, no sleeps, generous absolute
  timeouts with *relative* ordering assertions):
  **S1** posting wins — close acquires only after the posting commits.
  **S2** close wins, the critical one — assert the pre-lock read was `OPEN` and the post-lock read
  is `CLOSED`, the resolver throws `fiscal_period_closed`, and zero journal rows exist.
  **S3** two postings do not serialize.
  **S4** concurrent closes serialize and only one succeeds.
  **S5** a bare `UPDATE` still blocks behind `FOR SHARE`.
  **S6** reopen after a rejected posting resurrects nothing.
  **S7** business date rolls in flight — the roll is never blocked and the posting keeps its
  captured date.
  **S8** calling the lock with no active transaction throws.
- [ ] **Task 35.6 — Namespace disjointness and isolation proofs.** Take the legacy single-key lock
  and a two-int lock whose keys are numerically identical, assert two `pg_locks` rows with
  `objsubid` 1 and 2, and assert the cross pairing does not conflict. Assert
  `show transaction_isolation` returns `read committed`, with a KDoc pointing at the ADR precondition.
- [ ] **Task 35.7 — Unit tests.** `PostingDatePolicyTests` (every rule, including `valueDate` after
  `postingDate` and all three non-OPEN business-date statuses) and `PostingPeriodResolverTests` with
  fakes. The decisive unit assertion: make the fake return `OPEN` from `findCovering` and `CLOSED`
  from `lockForPosting`, and assert rejection — the unit twin of S2.
- [ ] **Task 35.8 — Docs.** ADR `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md`
  (Decision: the four-date model with `postingDate` sole authority; the six posting rules; row locks
  with the explicit rejection of a per-period exclusive advisory lock and of global `SERIALIZABLE`;
  the two-int advisory key space; the lifecycle-implemented business-date port; lock-free business
  date read. Consequences: **correct only at READ COMMITTED**; a close blocks behind long postings,
  so bound posting transaction duration in #41 and set `lock_timeout` on the close path in #39;
  32-bit `objid` false sharing over-serializes but never mis-serializes; the two legacy advisory
  domains remain unmigrated; `SOFT_CLOSED` is deliberately absent pending #30; a posting that begins
  before a business-date roll may commit with the previous date, and that is correct; the
  `FiscalPeriodStandIn` helper must be deleted by #36). Plus
  `docs/architecture/accounting-dates-and-periods.md` with worked examples, the rejection table with
  error codes, a Mermaid sequence of the lock protocol, and a "what #36/#39 must not change"
  section; a new section in `docs/operations/business-date.md`; `README.md` and `CLAUDE.md` updates.

### Ships now vs defers

Ships: the module extension, the date policy, the permission constants, the fiscal-period port
contracts, both concurrency services, the lock primitive, the namespace registry, the ADR and every
test above. Defers: the jOOQ `FiscalPeriodStateStore` adapter and period materialization (#36); the
close/reopen use cases with their FSM, audit and events (#39); seeding the permission codes (#34).

---

## Final task: full quality gate

- [ ] Run `./gradlew spotlessApply` then `./gradlew qualityGate` on each branch before opening its
  PR. Expect Detekt to surface missing KDoc on new public types first; add the KDoc rather than a
  suppression, and keep any unavoidable suppression narrow and explained.
- [ ] `ApplicationModules.of(PlatformApplication::class.java).verify()` and all ArchUnit classes in
  `src/test/kotlin/com/finaxis/platform/architecture/` must pass on every branch.
- [ ] Confirm no branch adds a second Flyway migration and that only #34 adds one at all.

[pr-60]: https://github.com/kevogaba/finaxis-platform/pull/60
[pr-61]: https://github.com/kevogaba/finaxis-platform/pull/61
[pr-62]: https://github.com/kevogaba/finaxis-platform/pull/62
[pr-63]: https://github.com/kevogaba/finaxis-platform/pull/63
[pr-64]: https://github.com/kevogaba/finaxis-platform/pull/64
[pr-65]: https://github.com/kevogaba/finaxis-platform/pull/65
