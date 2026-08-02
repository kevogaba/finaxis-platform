# Foundation Hardening and Greenfield Migration Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development
> (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use
> checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close out the greenfield phase — collapse fourteen Flyway migrations into three
production-ready files with a client-suppliable `guid` alternate key on every application table,
prove the foundation with tests across migrations, module boundaries, security, FSM, outbox, and
audit, and bring every document and ADR up to date.

**Architecture:** The schema reset is destructive by design: this is the last moment before first
deployment at which `flyway clean` is acceptable, so V1–V14 are deleted rather than amended. The
new `V1` carries all DDL, `V2` carries the immutable platform reference data (permission catalogue,
`PLATFORM` organisation, platform system roles), and `V3` carries the bootstrap tenant and first
administrator wired through to the Keycloak realm import. Every existing deterministic seed UUID is
preserved verbatim because 22 test and script files assert against them. jOOQ sources regenerate
automatically from the migrations at build time, so the schema change propagates to the persistence
adapters without hand-editing generated code.

**Tech Stack:** Kotlin 2.4 on Java 25, Spring Boot Web MVC, Spring Modulith, Spring Data JDBC +
jOOQ, Flyway, PostgreSQL 18.4, Testcontainers, RabbitMQ via Namastack Outbox, JobRunr, Keycloak
26.6, Redis, Gradle Kotlin DSL.

## Global Constraints

- Kotlin-first. Do not add Java to new code without a framework/runtime reason, documented inline.
- Hexagonal layers: `domain`, `application`, `adapter`, `config`. Never bypass an application
  service from an inbound adapter.
- Max line length 100 across Kotlin, Java, Gradle, YAML, XML, and Markdown.
- Detekt runs with `allRules = true` and zero findings. KDoc is required on public production
  classes and functions.
- Every module needs `package-info.java` with `@ApplicationModule` and explicit
  `allowedDependencies`. Modulith `verify()` and ArchUnit are first-class gates.
- Runtime authorization evaluates **permission codes, never role names**.
- The application stores no passwords and no credentials. Keycloak authenticates; the application
  authorizes.
- Public JSON is `snake_case`. Business dates use `dd-MM-yyyy`, times `HH:mm:ss`, datetimes
  ISO-8601 offset.
- Every listing endpoint paginates. Every mutation is idempotent via `Idempotency-Key`.
- Never log or persist secrets, bearer tokens, passwords, authorization headers, session cookies,
  API keys, or sensitive PII.
- Final gate: `./gradlew qualityGate` must pass.

## Frozen Decisions

These were decided before planning. Do not relitigate them during implementation.

1. **`guid` column — client-suppliable.** Every application-owned table gains
   `guid UUID NOT NULL DEFAULT uuidv7()` with a unique constraint. The PostgreSQL 18 function is
   `uuidv7()`, not `uuid7()`. Because a column `DEFAULT` only fires when the column is omitted
   from the INSERT, this single definition serves both cases: a client that supplies a `guid` has
   it stored verbatim, and one that omits it gets a generated value. No DTO or API surface
   *accepts* a client `guid` yet — that arrives with the microservices split — but the column and
   its uniqueness guarantee are in place now.

6. **`id` generation belongs to us, never to clients.** The primary key is ours to generate.
   Clients must never be able to supply it, and the preferred generation point is the database.

   - Every table that has an `id` primary key gets `id UUID PRIMARY KEY DEFAULT uuidv7()`. The
     database is the default generator and the application reads the value back.
   - Insert sites stop passing `ID` and read it back with `.returning(TABLE.ID).fetchOne()`.
   - Where an id is genuinely required *before* the insert — deterministic idempotency keys, or a
     multi-row aggregate whose children reference the parent inside one statement batch —
     application-side generation stays permitted, but it **must** use `uuidV7()` from
     `com.finaxis.platform.common.id.Uuids`, never `UUID.randomUUID()`. Both paths are the
     application generating the id; only the layer differs.
   - `UUID.randomUUID()` is banned in production code. There are 29 call sites in
     `src/main/kotlin` today; Task A5 replaces every one and adds a static-analysis rule.
   - **Already true, must stay true:** no `*Request` DTO declares an `id` property. Every
     `val id: UUID` in `adapter/inbound/web/dto` is on a `*Response` type. Task A5 adds a
     regression test that locks this in.

   Why `uuidv7()` and not `gen_random_uuid()`: v7 is time-ordered, so primary-key inserts append
   to the right edge of the B-tree instead of scattering random pages. On a core-banking write
   path that is a material difference in index bloat and cache behaviour.
2. **Framework tables stay auto-DDL.** `outbox_record` (Namastack), `event_publication` (Spring
   Modulith), and the JobRunr tables are created by their starters at runtime and are **not**
   moved into Flyway and **not** given a `guid` column. Outbox tests assert against the
   starter-created schema.
3. **Seed lives in production migrations.** `V3` seeds the bootstrap tenant and first
   administrator with deterministic IDs in a normal migration that runs in every environment.
4. **Every existing seed UUID is preserved.** 118 references across 22 files depend on them.
5. **Three migration files.** `V1` schema, `V2` platform reference data, `V3` bootstrap tenant.

## Known Blast Radius (verified, not assumed)

- **jOOQ insert style is safe.** Every `insertInto(TABLE)` in `src/main/kotlin` uses `.set(FIELD,
  value)`. There are zero `insertInto(TABLE).values(...)` positional inserts, so an extra `guid`
  column is simply omitted from generated INSERTs and the database default fills it.
- **`.value1()` / `.value2()` uses are safe.** They appear only on explicit projection records
  from `select(FIELD_A, FIELD_B)` in `JooqFoundationLifecyclePersistence.kt` and
  `JooqOrganisationBranchProvisioningStore.kt`, never on full table records.
- **Three `selectFrom(TABLE)` call sites** — `JooqInitialAdministratorBootstrapStore.kt:119`,
  `JooqIdempotencyStore.kt:147`, `JooqIamAdministrationQueries.kt:341` — read fields by name and
  tolerate an extra column.
- **Spring Data JDBC entities live in exactly one file**,
  `src/main/kotlin/com/finaxis/platform/common/persistence/FoundationJdbcEntities.kt` — and
  **none of the 18 entities are used by anything.** There is not a single `CrudRepository`,
  `ListCrudRepository`, or `JdbcRepository` in `src/main/kotlin`. Every real write goes through
  the hand-written jOOQ adapters. Consequently `@EnableJdbcAuditing` in
  `JdbcAuditingConfiguration.kt` is wired but inert: no callback ever fires, and `created_by` /
  `updated_by` are populated by the jOOQ adapters passing `SystemActor.ID` or the request actor
  explicitly. This materially changes what ADR 0014 can honestly claim — see Task C4.
- **Two tables have no `id` column at all.** `api_idempotency_record` has a composite primary key
  `(scope_organisation_id, idempotency_key)`, and `organisation_initial_administrator_bootstrap`
  uses `organisation_id` as its primary key. Both still get a `guid`, but neither participates in
  the `id UUID PRIMARY KEY DEFAULT uuidv7()` change. Do not invent surrogate `id` columns for
  them.
- **`iam.profile.read` has an id collision.** Old V2 seeded it as
  `66666666-6666-6666-6666-666666666601`; old V7 re-inserted it as
  `40000000-0000-0000-0000-000000000026` under `ON CONFLICT (permission_code) DO UPDATE`, so the
  V2 id always won and the V7 id was never used. The consolidated catalogue must keep
  `66666666-6666-6666-6666-666666666601`.
- **Old V12 and V13 cancel out.** V12 inserted a `PLATFORM`-organisation membership and role
  assignment for the bootstrap user; V13 deleted both. Net effect on a fresh database is nothing.
  Omit both from the new baseline — do not re-insert then delete. Note that
  `LocalPlatformSmokeMembershipSeeder.kt` and its two test classes still reference the
  `dddddddd-…` and `eeeeeeee-…` ids; Task A3 must decide whether that seeder is still wanted.
- **`@AuditedAction` is dead code.** The annotation
  (`common/audit/AuditedAction.kt`) and its aspect (`common/audit/AuditedActionAspect.kt`) are
  applied to **zero** production methods; all real auditing goes through explicit
  `auditService.record*(...)` calls in application services. Task C5 resolves this.
- **`logistics.shipment.approve`** is a leftover permission from an unrelated logistics domain,
  seeded by `V2`. Task A3 drops it from the catalogue.

## Application-Owned Tables (23)

All 23 get a `guid` column. Source migration shown for traceability only; all move into the new
`V1`.

| Table | From |
|---|---|
| `organisation` | V1 |
| `user_account` | V1 |
| `keycloak_identity_link` | V1 |
| `branch` | V1 |
| `user_organisation_membership` | V1 |
| `user_branch_assignment` | V1 |
| `permission` | V1 |
| `role` | V1 |
| `role_permission` | V1 |
| `membership_permission` | V1 |
| `user_role_assignment` | V1 |
| `organisation_setting` | V1 |
| `business_date` | V1 |
| `organisation_transition_log` | V1 |
| `branch_transition_log` | V1 |
| `user_account_transition_log` | V1 |
| `user_organisation_membership_transition_log` | V1 |
| `audit_event` | V1 |
| `reference_sequence` | V3 |
| `identity_dispatch_log` | V5 |
| `business_date_history` | V8 |
| `api_idempotency_record` | V9 |
| `organisation_initial_administrator_bootstrap` | V11 |

`flyway_schema_history` is Flyway-owned and excluded.

## File Structure

**Deleted:**
- `src/main/resources/db/migration/V1__create_iam_schema.sql` through
  `V14__iam_listing_order_indexes.sql` — all fourteen files.

**Created:**
- `src/main/resources/db/migration/V1__foundation_schema.sql` — every table, constraint, index, and
  comment. Single source of schema truth.
- `src/main/resources/db/migration/V2__platform_reference_data.sql` — permission catalogue,
  `PLATFORM` organisation, `PLATFORM_SUPER_ADMIN` and `PLATFORM_SUPPORT` roles and their grants.
- `src/main/resources/db/migration/V3__bootstrap_tenant_and_administrator.sql` — bootstrap tenant,
  head office and operations branches, first administrator, Keycloak identity link, membership,
  branch assignments, `local-admin` role and grants, organisation setting, business date.
- `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaConstraintTests.kt` — item 1
  constraint tests.
- `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaGuidTests.kt` — `guid` column
  contract across all 23 tables.
- `src/test/kotlin/com/finaxis/platform/foundation/FoundationSeedDataTests.kt` — seed completeness.
- `src/test/kotlin/com/finaxis/platform/architecture/ModuleDependencyRuleTests.kt` — item 2
  forbidden-dependency tests.
- `src/test/kotlin/com/finaxis/platform/security/TenantIsolationIntegrationTests.kt` — item 3.
- `src/test/kotlin/com/finaxis/platform/security/BranchIsolationIntegrationTests.kt` — item 3.
- `src/test/kotlin/com/finaxis/platform/security/LoginPreCheckIntegrationTests.kt` — item 3.
- `src/test/kotlin/com/finaxis/platform/lifecycle/domain/FoundationLifecycleTransitionTests.kt` —
  item 4 exhaustive legal/illegal transition matrix.
- `src/test/kotlin/com/finaxis/platform/lifecycle/TransitionLogPersistenceIntegrationTests.kt` —
  item 4 transition logs.
- `src/test/kotlin/com/finaxis/platform/lifecycle/OutboxSchemaAndRetryIntegrationTests.kt` — item 5.
- `src/test/kotlin/com/finaxis/platform/common/audit/HighRiskOperationAuditCoverageTests.kt` —
  item 6.
- `docs/adr/0010-greenfield-migration-reset-and-schema-rewrite.md`
- `docs/adr/0011-global-user-with-tenant-membership.md`
- `docs/adr/0012-keycloak-authentication-application-authorization.md`
- `docs/adr/0013-foundation-lifecycle-state-machines.md`
- `docs/adr/0014-spring-data-jdbc-auditing.md`
- `docs/adr/0015-client-suppliable-guid-alternate-key.md`

**Modified:** the thirteen documents named in the brief, plus `scripts/local-smoke.sh` and every
test that asserts the seeded permission set.

---

## Phase A — Schema Reset

### Task A1: Author the consolidated schema migration

**Files:**
- Create: `src/main/resources/db/migration/V1__foundation_schema.sql`
- Delete: `src/main/resources/db/migration/V1__create_iam_schema.sql`,
  `V3__add_organisation_reference_sequences.sql`,
  `V6__add_membership_invite_preferences.sql`,
  `V8__tenant_settings_and_business_date_foundation.sql` (DDL portion),
  `V9__foundation_api_idempotency.sql`, `V11__initial_administrator_bootstrap.sql`,
  `V14__iam_listing_order_indexes.sql`

**Interfaces:**
- Produces: 23 tables whose column sets are the **union** of the old V1 definition plus every
  later `ALTER TABLE`, plus the new `guid` column. Later tasks assert against these names.

The union must fold in these post-V1 alterations, which are easy to miss:
- `user_organisation_membership.pending_keycloak_invite BOOLEAN NOT NULL DEFAULT FALSE` (was V6)
- `user_organisation_membership.pending_application_invite BOOLEAN NOT NULL DEFAULT FALSE` (was V6)
- `reason TEXT` on `organisation_transition_log`, `branch_transition_log`,
  `user_account_transition_log`, `user_organisation_membership_transition_log`, and `audit_event`
  (was V4)

- [ ] **Step 1: Write the guid contract test first**

Create `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaGuidTests.kt`:

```kotlin
package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationSchemaGuidTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val applicationTables =
        listOf(
            "api_idempotency_record", "audit_event", "branch", "branch_transition_log",
            "business_date", "business_date_history", "identity_dispatch_log",
            "keycloak_identity_link", "membership_permission", "organisation",
            "organisation_initial_administrator_bootstrap", "organisation_setting",
            "organisation_transition_log", "permission", "reference_sequence", "role",
            "role_permission", "user_account", "user_account_transition_log",
            "user_branch_assignment", "user_organisation_membership",
            "user_organisation_membership_transition_log", "user_role_assignment",
        )

    @Test
    fun `every application table declares a non-null uuidv7 guid column`() {
        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT table_name, data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public' AND column_name = 'guid'
                ORDER BY table_name
                """.trimIndent(),
            )

        assertEquals(applicationTables, rows.map { it["table_name"] as String })
        rows.forEach { row ->
            assertEquals("uuid", row["data_type"], "guid type on ${row["table_name"]}")
            assertEquals("NO", row["is_nullable"], "guid nullability on ${row["table_name"]}")
            assertTrue(
                (row["column_default"] as String).contains("uuidv7()"),
                "guid default on ${row["table_name"]} was ${row["column_default"]}",
            )
        }
    }

    @Test
    fun `every guid column is backed by a unique index`() {
        val indexed =
            jdbcTemplate.queryForList(
                """
                SELECT DISTINCT t.relname AS table_name
                FROM pg_index i
                JOIN pg_class t ON t.oid = i.indrelid
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey)
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public'
                  AND a.attname = 'guid'
                  AND i.indisunique
                  AND i.indnatts = 1
                ORDER BY t.relname
                """.trimIndent(),
                String::class.java,
            )

        assertEquals(applicationTables, indexed)
    }

    @Test
    fun `guid defaults are distinct and time ordered across inserts`() {
        val guids =
            jdbcTemplate.queryForList(
                "SELECT uuidv7() FROM generate_series(1, 50)",
                java.util.UUID::class.java,
            )

        assertEquals(50, guids.toSet().size)
        assertEquals(guids.sortedBy { it.toString() }, guids)
    }
}
```

- [ ] **Step 2: Run it and confirm it fails**

```bash
./gradlew test --tests 'com.finaxis.platform.foundation.FoundationSchemaGuidTests'
```

Expected: FAIL — no `guid` columns exist yet, so the first assertion returns an empty list.

- [ ] **Step 3: Write `V1__foundation_schema.sql`**

Copy every `CREATE TABLE`, `CREATE INDEX`, `CREATE UNIQUE INDEX`, and `COMMENT` from the old V1,
V3, V5 (the `identity_dispatch_log` block only), V8 (the `business_date_history` block only), V9,
V11, and V14, fold in the V4 and V6 `ALTER TABLE` columns, and make two changes to every table.

First, give the 21 tables that have an `id` a database-generated primary key:

```sql
    id UUID PRIMARY KEY DEFAULT uuidv7(),
```

Skip this for `api_idempotency_record` and `organisation_initial_administrator_bootstrap`, which
have no `id` column.

Second, add to all 23 tables, immediately after the primary-key column:

```sql
    guid UUID NOT NULL DEFAULT uuidv7(),
```

with a matching table constraint:

```sql
    CONSTRAINT uq_<table_name>_guid UNIQUE (guid),
```

Order the file so foreign-key targets are created before their referents: `organisation`,
`user_account`, `keycloak_identity_link`, `branch`, `user_organisation_membership`,
`user_branch_assignment`, `permission`, `role`, `role_permission`, `membership_permission`,
`user_role_assignment`, `organisation_setting`, `business_date`, `business_date_history`, the four
transition logs, `audit_event`, `reference_sequence`, `identity_dispatch_log`,
`api_idempotency_record`, `organisation_initial_administrator_bootstrap`.

Add a header comment recording that this file replaces V1–V14 and pointing at ADR 0010.

- [ ] **Step 4: Add the database best-practice indexes**

Beyond the indexes carried over from V1 and V14, add a covering index for every foreign key that
does not already lead an index, because PostgreSQL does not index FKs automatically and unindexed
FKs make parent deletes and `ON DELETE` cascades sequential scans:

```sql
CREATE INDEX idx_membership_permission_organisation_permission
    ON membership_permission (organisation_id, permission_id);
CREATE INDEX idx_business_date_history_organisation
    ON business_date_history (organisation_id);
CREATE INDEX idx_identity_dispatch_log_user ON identity_dispatch_log (user_id);
CREATE INDEX idx_bootstrap_user ON organisation_initial_administrator_bootstrap (user_id);
CREATE INDEX idx_bootstrap_membership
    ON organisation_initial_administrator_bootstrap (membership_id);
CREATE INDEX idx_bootstrap_head_office
    ON organisation_initial_administrator_bootstrap (head_office_id);
CREATE INDEX idx_bootstrap_role ON organisation_initial_administrator_bootstrap (role_id);
CREATE INDEX idx_audit_event_organisation_entity
    ON audit_event (organisation_id, entity_type, entity_id, event_time DESC);
```

Do not add indexes speculatively beyond FK coverage and the existing listing indexes — every index
costs write throughput, and this schema has no production query telemetry yet.

- [ ] **Step 5: Delete the superseded DDL migrations**

```bash
git rm src/main/resources/db/migration/V1__create_iam_schema.sql \
       src/main/resources/db/migration/V3__add_organisation_reference_sequences.sql \
       src/main/resources/db/migration/V6__add_membership_invite_preferences.sql \
       src/main/resources/db/migration/V9__foundation_api_idempotency.sql \
       src/main/resources/db/migration/V11__initial_administrator_bootstrap.sql \
       src/main/resources/db/migration/V14__iam_listing_order_indexes.sql
```

- [ ] **Step 6: Verify the schema builds and jOOQ regenerates**

```bash
./gradlew jooqCodegen
```

Expected: PASS. Then confirm the generated table object carries the new field:

```bash
grep -n "GUID" build/generated-src/jooq/main/com/finaxis/platform/jooq/tables/Organisation.kt
```

Expected: a `val GUID: TableField<OrganisationRecord, UUID?>` declaration.

- [ ] **Step 7: Commit**

```bash
git add -A src/main/resources/db/migration src/test/kotlin/com/finaxis/platform/foundation
git commit -m "feat(db): consolidate foundation schema into V1 with guid alternate keys"
```

---

### Task A2: Author the platform reference-data migration

**Files:**
- Create: `src/main/resources/db/migration/V2__platform_reference_data.sql`
- Delete: `V4__seed_baseline_permissions_and_lifecycle_reasons.sql`,
  `V5__seed_platform_org_and_dispatch_log.sql`, `V7__seed_profile_read_permission.sql`,
  `V8__...` (seed portion), `V10__foundation_api_permissions.sql`

**Interfaces:**
- Produces: 55 permission rows, the `PLATFORM` organisation
  (`00000000-0000-0000-0000-000000000000`), `PLATFORM_SUPER_ADMIN`
  (`50000000-0000-0000-0000-000000000001`), `PLATFORM_SUPPORT`
  (`50000000-0000-0000-0000-000000000002`), and their `role_permission` grants. Task A3 and every
  authorization test consume these.

**The catalogue is 55 codes** — the union of V2, V4, V7, V8, and V10 minus
`logistics.shipment.approve`:

`audit.view`, `auth.select_branch`, `auth.select_organisation`, `branch.activate`,
`branch.approve`, `branch.close`, `branch.create`, `branch.reactivate`, `branch.suspend`,
`branch.view`, `branch_assignment.view`, `business_date.advance`, `business_date.reopen`,
`business_date.view`, `cob.complete`, `cob.start`, `iam.profile.read`, `iam.user.invite`,
`membership.reactivate`, `membership.revoke`, `membership.suspend`, `membership.view`,
`permission.view`, `role.activate`, `role.assign_permission`, `role.create`, `role.deactivate`,
`role.remove_permission`, `role.update`, `role.view`, `role_assignment.view`, `settings.update`,
`settings.view`, `tenant.activate`, `tenant.approve`, `tenant.bootstrap_retry`, `tenant.create`,
`tenant.deprovision`, `tenant.reactivate`, `tenant.reject`, `tenant.submit_for_approval`,
`tenant.suspend`, `tenant.update_draft`, `tenant.view`, `tenant_setting.manage_platform`,
`user.activate`, `user.approve`, `user.assign_branch`, `user.assign_role`, `user.deactivate`,
`user.invite`, `user.revoke_branch`, `user.revoke_role`, `user.suspend`, `user.view`.

Preserve the existing deterministic permission IDs where they exist
(`40000000-0000-0000-0000-0000000000NN` for the V4/V7/V8 codes,
`66666666-6666-6666-6666-6666666666NN` for the V2 codes) so nothing that hardcodes them breaks.

- [ ] **Step 1: Write the seed completeness test first**

Create `src/test/kotlin/com/finaxis/platform/foundation/FoundationSeedDataTests.kt` with a test
that asserts the exact sorted list of 55 permission codes, a test that asserts
`PLATFORM_SUPER_ADMIN` holds every permission except none (it is the superset role), and a test
that asserts `PLATFORM_SUPPORT` holds exactly `audit.view` and `business_date.view`.

```kotlin
@Test
fun `platform super admin holds the entire permission catalogue`() {
    val missing =
        jdbcTemplate.queryForList(
            """
            SELECT p.permission_code
            FROM permission p
            WHERE NOT EXISTS (
                SELECT 1 FROM role_permission rp
                WHERE rp.permission_id = p.id
                  AND rp.role_id = '50000000-0000-0000-0000-000000000001'
            )
            ORDER BY p.permission_code
            """.trimIndent(),
            String::class.java,
        )
    assertEquals(emptyList(), missing)
}
```

- [ ] **Step 2: Run and confirm it fails**

```bash
./gradlew test --tests 'com.finaxis.platform.foundation.FoundationSeedDataTests'
```

Expected: FAIL — `PLATFORM_SUPER_ADMIN` currently lacks the V10 permissions.

- [ ] **Step 3: Write `V2__platform_reference_data.sql`**

Single `INSERT INTO permission (...) VALUES (...)` covering all 55 codes with their existing IDs,
names, module codes, descriptions, and risk levels. Then the `PLATFORM` organisation, then the two
platform roles, then a set-based grant that gives `PLATFORM_SUPER_ADMIN` every row in `permission`
and `PLATFORM_SUPPORT` the two read permissions. Because this is a fresh database there is no need
for `ON CONFLICT` clauses — omit them so a duplicate seed fails loudly instead of silently.

- [ ] **Step 4: Delete the superseded seed migrations, run, and confirm the test passes**

```bash
git rm src/main/resources/db/migration/V4__seed_baseline_permissions_and_lifecycle_reasons.sql \
       src/main/resources/db/migration/V5__seed_platform_org_and_dispatch_log.sql \
       src/main/resources/db/migration/V7__seed_profile_read_permission.sql \
       src/main/resources/db/migration/V8__tenant_settings_and_business_date_foundation.sql \
       src/main/resources/db/migration/V10__foundation_api_permissions.sql
./gradlew test --tests 'com.finaxis.platform.foundation.FoundationSeedDataTests'
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A src/main/resources/db/migration src/test/kotlin/com/finaxis/platform/foundation
git commit -m "feat(db): seed the complete platform permission catalogue in V2"
```

---

### Task A3: Author the bootstrap tenant and first administrator migration

**Files:**
- Create: `src/main/resources/db/migration/V3__bootstrap_tenant_and_administrator.sql`
- Delete: `V2__seed_local_iam_smoke_data.sql`, `V12__seed_local_platform_smoke_membership.sql`,
  `V13__revoke_legacy_local_platform_smoke_membership.sql`
- Modify: `src/test/kotlin/com/finaxis/platform/iam/adapter/inbound/web/AuthFlowIntegrationTests.kt`
  (drop `logistics.shipment.approve` from the asserted permission set),
  `src/test/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqFoundationLifecyclePersistenceTests.kt`

**Interfaces:**
- Consumes: the permission rows and `PLATFORM` organisation from Task A2.
- Produces: these exact IDs, which 22 files already depend on —
  user `11111111-1111-1111-1111-111111111111`, identity link
  `11111111-1111-1111-1111-111111111112`, organisation
  `22222222-2222-2222-2222-222222222222`, head office branch
  `33333333-3333-3333-3333-333333333333`, operations branch
  `44444444-4444-4444-4444-444444444444`, membership
  `55555555-5555-5555-5555-555555555555`, role `77777777-7777-7777-7777-777777777777`,
  role assignment `99999999-9999-9999-9999-999999999999`, branch assignments
  `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1` and `aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa2`, setting
  `bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb`, business date
  `cccccccc-cccc-cccc-cccc-cccccccccccc`.

The Keycloak subject **must** stay `11111111-1111-1111-1111-111111111111` because that is the user
id in `docker/keycloak/import/finaxis-realm.json`. Verify with:

```bash
python3 -c "import json;d=json.load(open('docker/keycloak/import/finaxis-realm.json'));print([(u['id'],u['username']) for u in d['users']])"
```

- [ ] **Step 1: Write the bootstrap completeness test first**

Add to `FoundationSeedDataTests.kt` a test asserting the bootstrap administrator satisfies every
operating pre-check from `docs/development/common-project-context.md`: tenant `ACTIVE`, user
`ACTIVE`, membership `ACTIVE`, at least one active branch assignment, at least one active role
assignment whose role has at least one permission, and a linked Keycloak identity.

```kotlin
@Test
fun `bootstrap administrator satisfies every login pre-check`() {
    val row =
        jdbcTemplate.queryForMap(
            """
            SELECT o.status AS org_status,
                   u.status AS user_status,
                   m.membership_status,
                   (SELECT COUNT(*) FROM user_branch_assignment a
                     WHERE a.user_id = u.id AND a.status = 'ACTIVE') AS branch_count,
                   (SELECT COUNT(*) FROM user_role_assignment r
                     WHERE r.user_id = u.id AND r.status = 'ACTIVE') AS role_count,
                   (SELECT COUNT(*) FROM keycloak_identity_link k
                     WHERE k.user_id = u.id AND k.unlinked_at IS NULL) AS identity_count,
                   (SELECT COUNT(*) FROM role_permission rp
                     WHERE rp.role_id = '77777777-7777-7777-7777-777777777777') AS perm_count
            FROM user_account u
            JOIN user_organisation_membership m ON m.user_id = u.id
            JOIN organisation o ON o.id = m.organisation_id
            WHERE u.id = '11111111-1111-1111-1111-111111111111'
              AND o.id = '22222222-2222-2222-2222-222222222222'
            """.trimIndent(),
        )

    assertEquals("ACTIVE", row["org_status"])
    assertEquals("ACTIVE", row["user_status"])
    assertEquals("ACTIVE", row["membership_status"])
    assertTrue((row["branch_count"] as Long) >= 1)
    assertTrue((row["role_count"] as Long) >= 1)
    assertEquals(1L, row["identity_count"])
    assertTrue((row["perm_count"] as Long) > 0)
}
```

- [ ] **Step 2: Run and confirm it fails**

```bash
./gradlew test --tests 'com.finaxis.platform.foundation.FoundationSeedDataTests'
```

Expected: FAIL — the bootstrap tenant is not seeded yet.

- [ ] **Step 3: Write `V3__bootstrap_tenant_and_administrator.sql`**

Port the row content of the old V2 verbatim, preserving every UUID, but grant the `local-admin`
role the union of the permissions it received in old V2 and old V10 — everything except the
platform-reserved `tenant_setting.manage_platform` and the tenant-lifecycle permissions that only
`PLATFORM_SUPER_ADMIN` should hold. Do **not** port the old V12 `PLATFORM`-organisation membership;
V13 already revoked it and reinstating it would give the bootstrap user platform-wide authority.

Head the file with a comment stating that this is the first-deployment bootstrap tenant, that its
Keycloak credentials live in Keycloak and never in this database, and that operators must rotate
the `local.admin` identity before exposing the deployment.

- [ ] **Step 4: Delete the superseded seeds and fix the two affected assertions**

```bash
git rm src/main/resources/db/migration/V2__seed_local_iam_smoke_data.sql \
       src/main/resources/db/migration/V12__seed_local_platform_smoke_membership.sql \
       src/main/resources/db/migration/V13__revoke_legacy_local_platform_smoke_membership.sql
```

Remove `"logistics.shipment.approve"` from the expected list at
`AuthFlowIntegrationTests.kt:376` and from the DB-backed assertions in
`JooqFoundationLifecyclePersistenceTests.kt:698` and `:751`. Leave the unit-test fixtures in
`EffectivePermissionResolverTests.kt` and `UserProfileServiceTests.kt` alone — those are arbitrary
in-memory strings, not database rows.

- [ ] **Step 5: Confirm exactly three migrations remain**

```bash
ls src/main/resources/db/migration/
```

Expected: exactly `V1__foundation_schema.sql`, `V2__platform_reference_data.sql`,
`V3__bootstrap_tenant_and_administrator.sql`.

- [ ] **Step 6: Run the full suite against the reset schema**

```bash
./gradlew test
```

Expected: PASS. Any failure here is a real regression from the reset — fix it before continuing,
and prefer fixing the migration over weakening a test.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "feat(db): seed the bootstrap tenant and first administrator in V3"
```

---

### Task A4: Move identifier generation to the database and ban `UUID.randomUUID()`

**Files:**
- Modify: every jOOQ adapter under
  `src/main/kotlin/com/finaxis/platform/*/adapter/outbound/persistence/`, plus
  `src/main/kotlin/com/finaxis/platform/common/web/idempotency/JooqIdempotencyStore.kt`,
  `src/main/kotlin/com/finaxis/platform/common/audit/adapter/outbound/persistence/JooqAuditEventRepository.kt`,
  `src/main/kotlin/com/finaxis/platform/config/LocalPlatformSmokeMembershipSeeder.kt`
- Create: `src/test/kotlin/com/finaxis/platform/architecture/IdentifierGenerationRuleTests.kt`

**Interfaces:**
- Consumes: `id UUID PRIMARY KEY DEFAULT uuidv7()` from Task A1 and
  `com.finaxis.platform.common.id.uuidV7()`, which already exists.
- Produces: no new public API. Insert methods that previously accepted a caller-supplied id and
  returned `Unit` now return the database-generated `UUID`.

- [ ] **Step 1: Write the two guard tests first**

```kotlin
package com.finaxis.platform.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class IdentifierGenerationRuleTests {
    private val productionClasses: JavaClasses =
        ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.finaxis.platform")

    @Test
    fun `production code never generates random version 4 identifiers`() {
        noClasses()
            .that()
            .resideOutsideOfPackage("com.finaxis.platform.jooq..")
            .should()
            .callMethod(java.util.UUID::class.java, "randomUUID")
            .because(
                "identifiers are database-generated uuidv7 values; where an application-side " +
                    "identifier is unavoidable it must come from common.id.uuidV7()",
            )
            .check(productionClasses)
    }

    @Test
    fun `no inbound request DTO exposes a client suppliable primary key`() {
        val offenders =
            productionClasses
                .filter { it.packageName.contains(".adapter.inbound.web.dto") }
                .filter { it.simpleName.endsWith("Request") }
                .filter { candidate -> candidate.fields.any { it.name == "id" } }
                .map { it.simpleName }
                .sorted()

        assertEquals(
            emptyList(),
            offenders,
            "primary keys are generated by the application, never supplied by clients",
        )
    }
}
```

- [ ] **Step 2: Run and record the baseline**

```bash
./gradlew test --tests 'com.finaxis.platform.architecture.IdentifierGenerationRuleTests'
```

Expected: the DTO test PASSES already (verified — no `*Request` type declares `id`). The
`randomUUID` test FAILS with 29 violations. That asymmetry is the point: one invariant is being
locked in, the other is being established.

- [ ] **Step 3: Convert inserts to database-generated ids**

For each `insertInto(TABLE).set(TABLE.ID, someId)` call, drop the `.set(TABLE.ID, …)` line and
change the terminal `.execute()` to return the generated key:

```kotlin
// Before
dsl.insertInto(USER_ACCOUNT)
    .set(USER_ACCOUNT.ID, userId)
    .set(USER_ACCOUNT.USERNAME, username)
    // …
    .execute()
return userId

// After
return dsl.insertInto(USER_ACCOUNT)
    .set(USER_ACCOUNT.USERNAME, username)
    // …
    .returning(USER_ACCOUNT.ID)
    .fetchOne()
    ?.id
    ?: error("Insert into user_account returned no generated identifier")
```

Work adapter by adapter and run that adapter's test class after each one. Do **not** convert a
call site where the id is needed before the insert — those go to Step 4.

- [ ] **Step 4: Convert the remaining application-side generation to `uuidV7()`**

For call sites that genuinely need the id up front, replace `UUID.randomUUID()` with `uuidV7()`
and add the import `com.finaxis.platform.common.id.uuidV7`. Add a one-line comment at each such
site stating why the id cannot be database-generated — a reviewer must be able to see the reason
without reconstructing it.

- [ ] **Step 5: Confirm both guard tests pass**

```bash
./gradlew test --tests 'com.finaxis.platform.architecture.IdentifierGenerationRuleTests'
```

Expected: PASS.

- [ ] **Step 6: Run the full suite**

```bash
./gradlew test
```

Expected: PASS. Tests that asserted a caller-chosen id now assert on the returned id instead.

- [ ] **Step 7: Commit**

```bash
git add -A
git commit -m "refactor(persistence): generate identifiers in the database as uuidv7"
```

---

### Task A5: Reset the live development database and re-run the smoke script

**Files:**
- Modify: `scripts/local-smoke.sh` if any seeded id it references changed

- [ ] **Step 1: Drop and recreate the local database**

```bash
docker exec platform-postgres-1 psql -U finaxis -d postgres -c 'DROP DATABASE IF EXISTS platform;' -c 'CREATE DATABASE platform OWNER finaxis;'
```

- [ ] **Step 2: Boot the application so Flyway applies all three migrations from empty**

```bash
SPRING_PROFILES_ACTIVE=local ./gradlew bootRun
```

Expected: startup succeeds; the log shows three migrations applied and no auto-DDL errors from
Namastack or Modulith.

- [ ] **Step 3: Run the smoke script in a second shell and commit**

```bash
./scripts/local-smoke.sh
```

Expected: a Keycloak token is issued for `local.admin` and every smoke request returns 2xx.

```bash
git add -A && git commit -m "chore(db): verify greenfield reset against a live database"
```

---

## Phase B — Test Hardening

A gap analysis mapped all 116 existing test files against the six requested categories. **Item 3
(security) is already fully covered** and needs no new tests — see Task B3. The genuine gaps are
concentrated in items 1, 5, and 6. Read the per-task status notes before writing anything: the
fastest way to waste this phase is to duplicate coverage that already exists.

### Task B1: Database constraint tests (brief item 1)

**Coverage status.** Flyway-applies-cleanly is COVERED by
`FoundationSchemaMigrationTests.Flyway creates the organisation foundation on an empty PostgreSQL
database`. Cross-tenant branch assignment is COVERED twice, by
`OrganisationBranchProvisioningServiceTests.branch assignment is idempotent and cannot cross
organisation boundaries` and
`JooqFoundationLifecyclePersistenceTests.branch assignment is active once and never crosses
organisation boundaries` — but both test the *application* path, so the DB-level test below is
still worth having. Unique membership is PARTIAL: `UserProvisioningServiceTests.invitation rejects
duplicate active membership` checks the service pre-check against a fake store, never the Postgres
constraint. Unique tenant code, unique branch code per tenant, duplicate active role assignment,
and outbox schema are all MISSING.

**Files:**
- Create: `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaConstraintTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/foundation/FoundationSchemaMigrationTests.kt` —
  update the expected table list to all 23 and drop the `V10 migration seeds` test, which now
  belongs to `FoundationSeedDataTests`.

Each of the six named constraints gets a test that inserts a conflicting row and asserts
`DataIntegrityViolationException`. Named tests, one per bullet in the brief:

- `unique tenant code is rejected`
- `duplicate branch code within a tenant is rejected`
- `duplicate branch code across tenants is allowed`
- `duplicate membership for the same user and tenant is rejected`
- `branch assignment referencing another tenants branch is rejected`
- `second active role assignment for the same user role and scope is rejected`
- `inactive duplicate role assignment is allowed`

The cross-tenant test is the subtle one — it must prove the composite foreign key
`fk_user_branch_assignment_branch (organisation_id, branch_id)` actually blocks the write rather
than relying on application filtering:

```kotlin
@Test
fun `branch assignment referencing another tenants branch is rejected`() {
    val otherOrg = insertOrganisation(tenantCode = "OTHER-TENANT")
    val otherBranch = insertBranch(organisationId = otherOrg, branchCode = "OTHER-HQ")

    assertFailsWith<DataIntegrityViolationException> {
        jdbcTemplate.update(
            """
            INSERT INTO user_branch_assignment (
                id, organisation_id, user_id, branch_id, assignment_type, status,
                assigned_at, created_at, updated_at
            ) VALUES (?, ?, ?, ?, 'OPERATE', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            UUID.randomUUID(),
            BOOTSTRAP_ORGANISATION_ID,
            BOOTSTRAP_USER_ID,
            otherBranch,
        )
    }
}
```

- [ ] **Step 1:** Write all seven tests.
- [ ] **Step 2:** Run `./gradlew test --tests '*FoundationSchemaConstraintTests'`; every test must
      pass against the Task A1 schema without further DDL changes. If one fails, the constraint is
      genuinely missing — add it to `V1__foundation_schema.sql` rather than deleting the test.
- [ ] **Step 3:** Add the outbox schema assertions described in Task B4 Step 1.
- [ ] **Step 4:** Commit.

### Task B2: Spring Modulith boundary tests (brief item 2)

**Files:**
- Create: `src/test/kotlin/com/finaxis/platform/architecture/ModuleDependencyRuleTests.kt`
- Modify: `src/test/kotlin/com/finaxis/platform/architecture/ModulithArchitectureTest.kt` — add a
  test that writes the module documentation so drift is visible.

The existing `ModulithArchitectureTest` calls `verify()`, which catches illegal dependencies but
says nothing about the *specific* couplings the brief cares about. Add explicit assertions:

```kotlin
@Test
fun `domain packages do not depend on adapter or framework infrastructure`() {
    val rule =
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform..domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform..adapter..",
                "org.springframework..",
                "org.jooq..",
                "io.namastack..",
            )
    rule.check(productionClasses)
}

@Test
fun `notifications module does not reach back into lifecycle or iam internals`() {
    val rule =
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform.notifications..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.lifecycle..",
                "com.finaxis.platform.iam..",
            )
    rule.check(productionClasses)
}

@Test
fun `common audit does not depend on any domain module`() {
    val rule =
        noClasses()
            .that()
            .resideInAPackage("com.finaxis.platform.common.audit..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                "com.finaxis.platform.iam..",
                "com.finaxis.platform.lifecycle..",
                "com.finaxis.platform.notifications..",
            )
    rule.check(productionClasses)
}
```

- [ ] **Step 1:** Write the three rules plus a `ApplicationModules.of(...).writeDocumentation()`
      test.
- [ ] **Step 2:** Run them. If the audit or notifications rule fails, that is a **real coupling
      defect** — report it and fix the production code, do not relax the rule.
- [ ] **Step 3:** Commit.

### Task B3: Security tests (brief item 3) — VERIFY ONLY, DO NOT REWRITE

**Coverage status: all six bullets are already COVERED.** Do not create
`TenantIsolationIntegrationTests.kt`, `BranchIsolationIntegrationTests.kt`, or
`LoginPreCheckIntegrationTests.kt`. Writing them would duplicate:

| Requested | Existing test |
|---|---|
| Login pre-checks | `AuthFlowIntegrationTests`: `runtime resolution rejects suspended app user`, `… rejects suspended organisation`, `… rejects deprovisioned organisation`, `… activates invited user on first login`; `SecurityAdapterTests`: `principal loader rejects suspended users`, `… rejects suspended and deprovisioned organisations` |
| Permission enforcement | `MethodSecurityAuthorizerTests`: `organisation permission requires matching organisation and permission code`, `branch permission requires matching organisation branch and permission code`; `AuthorizationServiceTests`: `requirePermission throws when principal lacks permission` |
| Tenant isolation | `AuthFlowIntegrationTests.runtime resolution rejects context for another organisation`; `AuthorizationServiceTests.resource authorization blocks access to another organisation`; `MembershipControllerTests.getMembership returns safe 404 for a cross tenant membership` |
| Branch isolation | `AuthFlowIntegrationTests`: `runtime resolution rejects missing active branch assignment`, `… rejects inactive branch`; `BranchAssignmentControllerTests.searchBranchAssignments rejects a branch outside the active context` |
| Suspended denial | the suspended tests above plus `EffectivePermissionResolverTests.suspended membership denies all permissions` |
| No-role / no-permission | `AuthFlowIntegrationTests.method security rejects role without concrete permission`; `AuthSelectionServiceTests`: `select organisation denies when auth select_organisation permission is missing`, `select branch denies when …` |

- [ ] **Step 1:** Run the whole security surface and confirm it is green **after** the Phase A
      reset, since several of these tests mutate seeded rows by literal UUID:

```bash
./gradlew test --tests '*AuthFlowIntegrationTests' --tests '*SecurityAdapterTests' \
  --tests '*MethodSecurityAuthorizerTests' --tests '*AuthorizationServiceTests' \
  --tests '*EffectivePermissionResolverTests' --tests '*AuthSelectionServiceTests' \
  --tests '*BranchAssignmentControllerTests' --tests '*MembershipControllerTests'
```

- [ ] **Step 2:** `AuthFlowIntegrationTests` has a `@BeforeEach resetSeedState()` that UPDATEs
      `USER_ACCOUNT`, `ORGANISATION`, `BRANCH`, and `USER_BRANCH_ASSIGNMENT` rows by literal seed
      UUID. Confirm it still resolves against the V3 seed and fix the literals if any moved.
- [ ] **Step 3:** Add the one genuinely missing case — a suspended **branch** (as opposed to an
      inactive branch assignment) denying a branch-scoped operation — to
      `AuthFlowIntegrationTests` rather than a new file.
- [ ] **Step 4:** Commit.

### Task B4: FSM and outbox tests (brief items 4 and 5)

**Files:**
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/domain/FoundationLifecycleTransitionTests.kt`,
  `src/test/kotlin/com/finaxis/platform/lifecycle/TransitionLogPersistenceIntegrationTests.kt`,
  `src/test/kotlin/com/finaxis/platform/lifecycle/OutboxSchemaAndRetryIntegrationTests.kt`

The FSM test is table-driven over the four graphs in
`src/main/kotlin/com/finaxis/platform/lifecycle/domain/FoundationLifecycleDefinitions.kt`. For each
of `OrganisationLifecycleState`, `BranchLifecycleState`, `UserLifecycleState`, and
`MembershipLifecycleState`, enumerate the full cartesian product of (state, transition) and assert
that exactly the pairs declared in the graph succeed and **every other pair throws
`TransitionException`**. This is the only way to catch an accidentally-legal transition.

**Item 4 coverage status.** Valid and invalid transitions for tenant and branch are COVERED by
`OrganisationBranchProvisioningServiceTests` and `FoundationLifecycleServiceTests` (notably
`a transition from the wrong source state is audited and rejected as a conflict`,
`branch activation is rejected until its organisation is active or provisioning`). Transition logs
are COVERED by `TransitionExecutorTests.valid transition mutates persists logs and publishes event`
and `JooqFoundationLifecyclePersistenceTests.organisation submission durably writes transition log
and audit event`. **Invalid user-account transitions are PARTIAL** — there is no equivalent of the
sharp branch-level rejections, e.g. suspending an already-deactivated user. The exhaustive matrix
below closes that and guards the rest against regression.

**Item 5 coverage status.** Same-transaction outbox creation is COVERED six times over
(`OrganisationActivationOutboxIntegrationTests`, `BranchActivationOutboxIntegrationTests`,
`BusinessDateAdvancedOutboxIntegrationTests`, `OrganisationSettingsUpdatedOutboxIntegrationTests`,
`BusinessDateCobLifecycleIntegrationTests`, `HeadOfficeBootstrapIntegrationTests`). Idempotent
Keycloak provisioning is COVERED by
`KeycloakUserProvisioningHandlerTests.succeeded dispatch skips keycloak and lifecycle work` and
`KeycloakAdminGatewayTests.finds existing user by email before creating`.

Two real gaps and one known weakness:

- **Rollback is PARTIAL.** `OutboxTransactionRollbackIntegrationTests.a failed transaction does not
  persist its outbox event` proves the outbox row is absent, but its own doc-comment admits it
  never verifies the *business* row rolled back — "no test in this codebase has ever verified real
  `@Transactional` rollback-on-exception against the actual database." Close that: assert both
  sides of atomicity in one test.
- **Publisher retry is MISSING.** `TransitionModuleConfigurationTests` asserts the retry
  *configuration values* only. Nothing exercises retry behaviour.
- **Dead-letter after max retry is MISSING.**

- [ ] **Step 1:** Write the exhaustive FSM matrix test over all four graphs.
- [ ] **Step 2:** Strengthen `OutboxTransactionRollbackIntegrationTests` to assert the business row
      rolled back as well as the outbox row.
- [ ] **Step 3:** Write publisher-retry and dead-letter tests against a RabbitMQ Testcontainer,
      using `TestcontainersConfiguration` (the full-stack config with Rabbit), not
      `PostgresTestConfiguration`.
- [ ] **Step 4:** Add outbox schema assertions — required fields and indexes on the
      starter-created `outbox_record` table. Because the schema is owned by
      `io.namastack:namastack-outbox-starter-jdbc`, assert only the columns and indexes the
      application actually depends on, and comment that this test is a canary for a starter
      upgrade changing the contract.
- [ ] **Step 5:** Run both; commit.

### Task B5: Audit tests (brief item 6)

**Files:**
- Create: `src/test/kotlin/com/finaxis/platform/common/audit/HighRiskOperationAuditCoverageTests.kt`

The valuable test here is a coverage test, not another unit test of `AuditService`. Enumerate every
permission whose `risk_level` is `HIGH` or `CRITICAL` in the seeded catalogue, map each to the
application-service method that performs it, and assert an `audit_event` row is written when that
method succeeds. Where a mapping does not exist, the test must fail — that is the point.

Redaction and pagination are partially covered by `AuditServiceTests` and `AuditQueryServiceTests`;
extend rather than duplicate, adding a test that a bearer token placed in audit metadata is stored
masked, and a test that `GET /api/v1/audit-events` rejects an over-max page size.

- [ ] **Step 1:** Write the coverage test.
- [ ] **Step 2:** Run it. Expect failures for operations that genuinely do not audit — list them
      and fix the production services.
- [ ] **Step 3:** Commit.

---

## Phase C — Documentation and ADRs

### Task C1: Write the six new ADRs

Follow the existing house format exactly: `# ADR NNNN: Title`, `## Status`, `Accepted`, `Date:
2026-08-02`, `## Context`, `## Decision`, `## Consequences`.

- [ ] `0010-greenfield-migration-reset-and-schema-rewrite.md` — why collapsing V1–V14 was safe (no
      production deployment existed), what `flyway clean` is required, and the rule that this is
      the last such reset.
- [ ] `0011-global-user-with-tenant-membership.md` — `user_account` is global and unique on
      lowercased email and username; organisation access exists only as
      `user_organisation_membership`; a user may belong to many organisations.
- [ ] `0012-keycloak-authentication-application-authorization.md` — Keycloak owns authentication,
      SSO, and external IdP federation; the application owns users, memberships, roles,
      permissions, and every runtime authorization decision; the application stores no passwords.
- [ ] `0013-foundation-lifecycle-state-machines.md` — the four concrete graphs, why they are
      separate enums over the shared `common.transitions` engine, and the no-hard-delete rule
      inherited from ADR 0005.
- [ ] `0014-spring-data-jdbc-auditing.md` — `@EnableJdbcAuditing` plus the `AuditorAware` bound to
      request context; why `created_by`/`updated_by` are UUIDs and nullable for system writes; the
      relationship to the separate append-only `audit_event` trail.
- [ ] `0015-client-suppliable-guid-alternate-key.md` — the `guid` column, its `uuidv7()` default,
      why it exists alongside the UUID primary key ahead of the microservices split, and the
      explicit statement that it is not yet exposed on any API surface.

- [ ] **Validate the existing nine.** Read ADRs 0001–0009 and confirm each still matches the code.
      Add a `## Superseded By` or `## Amended` note to any that the reset invalidates — 0007 in
      particular references "Flyway `V1`, extended by `V4`", which is now wrong.

### Task C2: Update the thirteen named documents

A documentation audit read all 42 docs against the code. These are the **specific factual
contradictions** to fix, not a generic refresh. Everything not listed here was verified accurate
and needs only migration-number touch-ups.

- [ ] `docs/operations/business-date.md` — **worst offender.** Lines 8, 118, and 126 all claim
      "There is no public REST controller in `lifecycle` yet" / "There is no REST endpoint yet."
      `BusinessDateController.kt` exists and its routes are documented at
      `docs/api/foundation-api.md:788-827`. Rewrite those sections around the live endpoints.
- [ ] `docs/operations/tenant-settings.md` — same defect at lines 7 and 94.
      `TenantSettingsController.kt` exists.
- [ ] `docs/operations/tenant-provisioning.md` — the "Query API" section, lines 101-108, claims
      "not a documented public REST endpoint in this branch". `PlatformTenantController.kt`
      serves `GET/POST /api/v1/platform/tenants`.
- [ ] `docs/database/foundation-schema.md` — full rewrite. The ERD covers only the original V1/V2
      baseline and is missing `reference_sequence`, `identity_dispatch_log`,
      `organisation_initial_administrator_bootstrap`, `api_idempotency_record`,
      `business_date_history`, and `membership_permission`. Must document all 23 tables, the
      `guid` convention, the `id … DEFAULT uuidv7()` convention, and the index rationale.
- [ ] `docs/architecture/foundation-implementation-plan.md` — lines 85-124 describe a target of
      eight Modulith modules (`shared/kernel`, `tenancy`, `identity`, `iam`, `lifecycle`, `audit`,
      `integration`, `settings`). Five shipped: `iam`, `lifecycle`, `notifications`, `common`,
      `config`. Either delete that section or record that the simpler structure superseded it.
- [ ] `README.md` — tech-stack table says Kotlin `2.4.0`; `gradle/libs.versions.toml:2` says
      `2.4.10`. The documentation index at lines 138-159 omits 14 real docs: ADRs 0005-0009,
      `docs/architecture/transactional-outbox-amqp.md`, all four `docs/operations/*.md`, and
      `docs/security/audit-logging.md`, `authorization-model.md`, `login-prechecks.md`,
      `user-provisioning-keycloak.md`.
- [ ] `docs/development/static-analysis.md` — line 10 says Kotlin `2.4.0`; actual is `2.4.10`.
- [ ] `docs/development/common-project-context.md` — line 6 says migrations "exist as Flyway
      migration 1 and 2". After this work that becomes true again, but for a different reason;
      update it to describe the post-reset three-file baseline.
- [ ] `docs/architecture/api-versioning.md` — its endpoint inventory at lines 13-19 lists three
      auth endpoints and is a small fraction of the real surface. Reduce it to the versioning
      *rule* and point at `docs/api/foundation-api.md` for the inventory.
- [ ] `docs/security/authorization-model.md` — refresh to the 55-code catalogue and the role
      grants, including the five code-provisioned tenant roles (`TENANT_ADMIN`, `TENANT_AUDITOR`,
      `IAM_ADMIN`, `BRANCH_MANAGER`, `BRANCH_OPERATOR`) that live in
      `JooqOrganisationBranchProvisioningStore.kt` and appear in no migration.
- [ ] `docs/architecture/transactional-outbox-amqp.md` — add that the outbox and
      `event_publication` tables are starter-managed and deliberately outside Flyway.
- [ ] `docs/security/user-provisioning-keycloak.md`, `docs/security/audit-logging.md`,
      `docs/operations/branch-provisioning.md` — verified accurate; migration-reference
      touch-ups only.
- [ ] `AGENTS.md` — verified accurate as a pointer document. Update only if a `CLAUDE.md` rule
      changes.
- [ ] `CLAUDE.md` — update the implementation-status section, add the identifier-generation rule
      from Frozen Decision 6, and add the `guid` convention.

Search for stale migration references before declaring this done:

```bash
grep -rn "V1__\|V2__\|V4__\|V5__\|V8__\|V10__\|V11__\|V12__\|V13__\|V14__" docs README.md AGENTS.md CLAUDE.md
```

Known hits to fix outside the thirteen: `docs/adr/0005:84` ("Flyway migrations `V1`, `V4`, and
`V5`"), `docs/adr/0006:88` ("`V5` and `V6`"), `docs/adr/0007:11` and `:104` ("Flyway `V1`,
extended by `V4`").

Search for stale migration references before declaring this done:

```bash
grep -rn "V1__\|V2__\|V4__\|V5__\|V8__\|V10__\|V11__\|V12__\|V13__\|V14__" docs README.md AGENTS.md CLAUDE.md
```

Expected after the update: only references to the three surviving files.

### Task C3: Archive historical documentation

The audit found these are historical artifacts, not living references. **Do not delete them** —
they carry design rationale not repeated anywhere else (the Namastack jar-verification notes
inside the plans, the F-00x audit trail). Archive them instead.

- [ ] `docs/audits/repository-health-audit.md` — dated 2026-07-06, records findings F-001…F-011.
      Several are now false, e.g. F-009 "Durable audit persistence not implemented" shipped
      afterwards. Move to `docs/archive/` and add a banner: point-in-time snapshot, not current
      state.
- [ ] `docs/superpowers/plans/*.md` (5 files) and `docs/superpowers/specs/*.md` (4 files) — every
      slice they describe has shipped and is superseded by ADRs 0001-0009 and the reference docs.
      Move to `docs/archive/` with a completion banner naming the superseding ADR. This plan
      itself stays in `docs/superpowers/plans/` until it is executed.
- [ ] **Do not merge the two audit-logging docs.** The audit confirmed
      `docs/architecture/audit-logging.md` (56 lines, the "what to audit" overview) and
      `docs/security/audit-logging.md` (127 lines, the redaction and API reference) are an
      intentional two-tier pair that already cross-reference each other at
      `architecture/audit-logging.md:39` and `security/audit-logging.md:126`. Both are accurate.
      Add a one-line "architecture overview vs. implementation reference" note at the top of each
      so a reader knows which to open first, and leave the split alone.

### Task C4: Resolve the inert Spring Data JDBC auditing layer

ADR 0014 cannot honestly describe "the Spring Data JDBC auditing approach" until this is settled.
The audit found `FoundationJdbcEntities.kt` defines 18 `@Table` entities that **nothing uses** —
zero repositories exist — so `@EnableJdbcAuditing` in `JdbcAuditingConfiguration.kt` never fires a
single callback. Meanwhile `created_by` / `updated_by` *are* correctly populated, by the jOOQ
adapters passing `SystemActor.ID` or the request actor explicitly.

Pick one and write ADR 0014 to match reality, not aspiration:

- **Delete** `FoundationJdbcEntities.kt` and `JdbcAuditingConfiguration.kt`, and write ADR 0014 as
  "auditing is performed explicitly in the jOOQ persistence adapters" — describing
  `RequestContexts.actor()`, the `SystemActor.ID` fallback, and the `AuditedJdbcAggregate` column
  convention as a *schema* convention rather than a framework mechanism; or
- **Adopt** Spring Data JDBC for at least one aggregate so the auditing callbacks are genuinely
  exercised, and write ADR 0014 describing the hybrid.

Prefer deletion. Carrying 18 unused entity classes and an inert `@EnableJdbcAuditing` bean is a
trap for the next developer, who will reasonably assume writes flow through them. Note the
`ContextAuditorAwareTests` and `JdbcAuditingIntegrationTests` test classes go with whichever
choice is made.

### Task C5: Resolve the dead `@AuditedAction` annotation

The annotation, its aspect, and its tests exist but no production method uses it. Choose and
execute one:

- **Wire it** onto the high-risk application-service methods identified in Task B5, replacing the
  hand-written `auditService.record*` calls where the aspect can express the same intent; or
- **Delete** `AuditedAction.kt`, `AuditedActionAspect.kt`, and `AuditedActionAspectTests.kt` and
  record in ADR 0007 that explicit service-level auditing is the single mechanism.

Prefer deletion unless the aspect demonstrably reduces duplication — explicit audit calls at the
point of the decision are easier to review than an annotation whose behaviour is remote.

---

## Phase D — Verification

- [ ] **Step 1: Full quality gate**

```bash
./gradlew qualityGate
```

Expected: PASS. This runs `staticAnalysis`, `check`, `jacocoTestCoverageVerification` (IAM line
coverage ≥ 0.95), and `bootJar`.

- [ ] **Step 2: Confirm the migration count and the guid contract one final time**

```bash
ls src/main/resources/db/migration/ && ./gradlew test --tests '*FoundationSchema*'
```

- [ ] **Step 3: Live reset and smoke**

Repeat Task A4 from a dropped database to prove the three migrations apply cleanly to empty
PostgreSQL outside Testcontainers.

- [ ] **Step 4: Report** what passed, what was deliberately left out, and every production defect
      the new tests uncovered.

## Acceptance Criteria

- `./gradlew qualityGate` passes.
- Exactly three Flyway migrations exist and apply cleanly to an empty PostgreSQL 18 database.
- All 23 application tables carry a unique, indexed, `uuidv7()`-defaulted `guid` column.
- Every bullet in brief items 1–6 maps to a named, passing test.
- Every `id` primary key is `DEFAULT uuidv7()`; `UUID.randomUUID()` appears nowhere in
  `src/main/kotlin`; no `*Request` DTO exposes an `id`; both guard tests in
  `IdentifierGenerationRuleTests` pass.
- The thirteen named documents are current; historical documents are archived with banners, not
  deleted.
- ADRs 0010–0015 exist and 0001–0009 are validated, with the four stale migration references in
  ADRs 0005, 0006, and 0007 corrected.
- No financial modules (savings, shares, loans, accounting, teller, member onboarding, product
  configuration) are implemented.
