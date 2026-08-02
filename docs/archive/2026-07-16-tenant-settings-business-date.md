# Tenant Settings & Business Date / COB Foundation Implementation Plan

> **Archived historical artifact.** This records work that has since shipped. It is kept for
> the design rationale it contains, not as a description of current behaviour. For current
> state see the ADRs in `docs/adr/` and the reference docs under `docs/architecture/`,
> `docs/security/`, `docs/operations/`, and `docs/database/`.

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add setup-level tenant settings (catalog-validated, effective-dated, permission-gated, redaction-aware) and a business-date / COB status foundation (initialize, advance, start/complete COB, reopen) with audit + outbox integration, all inside the existing `lifecycle` module — no accounting/day-close.

**Architecture:** Extends the existing `lifecycle` module. `Organisation` is the tenant boundary; "tenant setting" is a command/query layer over the existing effective-dated `organisation_setting` table. Business date remains a singleton optimistic-locked row; a new append-only `business_date_history` table backs the history query. Permission enforcement uses dependency inversion — a `PermissionGuard` port defined in `lifecycle` and implemented in `iam` (mirroring the existing `UserFirstLoginActivation` contract) so no `lifecycle → iam` compile dependency is introduced (that would create a forbidden Modulith cycle, since `iam → lifecycle` already exists). Events go through the existing `ExternalizedTransitionEvent` → Modulith outbox → Namastack pipeline; no RabbitMQ consumer is added.

**Tech Stack:** Kotlin, Spring Boot (Web MVC, OAuth2 resource server), Spring Modulith, Spring Data JDBC auditing, jOOQ (build-time codegen from Flyway migrations via EmbeddedPostgres), Flyway, Namastack Outbox, JUnit 5 + kotlin.test, Testcontainers (Postgres/Redis/RabbitMQ), Awaitility.

## Global Constraints

- Kotlin-first; do not add Java except `package-info.java` module descriptors. KDoc required on public production classes/functions (Detekt enforces).
- Max line length 100 across all files.
- Hexagonal layers: `domain` (framework-free), `application` (ports + services + commands), `adapter` (persistence/security), `config`.
- `lifecycle` module `allowedDependencies` are exactly: `common::audit`, `common::context`, `common::id`, `common::persistence`, `common::transitions`, `jooq`. **Do not import `com.finaxis.platform.iam.*` from `lifecycle`.**
- Runtime authorization evaluates permission **codes**, never role names.
- Never overwrite settings destructively: use close-row / insert-row effective-dating.
- Never hand-write an outbox table or publish directly to RabbitMQ — externalize only via `ExternalizedTransitionEvent` through `TransitionEventPublisher`.
- Sensitive audit values must be redacted via `Redacted(...)` / `SensitiveDataRedactor`.
- Tenant must be `OrganisationLifecycleState.ACTIVE` for all mutations in this slice.
- Every change ships focused unit tests AND Testcontainers integration tests.
- Timezone values validated against `java.time.ZoneId.getAvailableZoneIds()` (IANA). Currency values validated against `java.util.Currency.getAvailableCurrencies()` (ISO 4217).
- Unit tests use hand-written fakes implementing port interfaces (no mockk/mockito). Integration tests use `@SpringBootTest` + `@Import(TestcontainersConfiguration::class)` + `@TestConstructor(autowireMode = ALL)` (there is no `@ApplicationModuleTest` and no `AbstractIntegrationTest` in this repo).
- Run `./gradlew qualityGate` before finalizing; ArchUnit + Spring Modulith `verify()` must stay green.
- jOOQ references regenerate automatically at build (`compileKotlin dependsOn jooqCodegen`); after adding a table in a migration, `com.finaxis.platform.jooq.tables.references.<TABLE>` becomes available on the next build.
- Commit after every task with a `feat:`/`test:`/`docs:` message ending with:
  `Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>`

---

### Task 1: V8 migration — new permissions, platform grants, and `business_date_history` table

**Files:**
- Create: `src/main/resources/db/migration/V8__tenant_settings_and_business_date_foundation.sql`

**Interfaces:**
- Consumes: existing `permission`, `role`, `role_permission` tables (V1); seeded `PLATFORM_SUPER_ADMIN` role id `50000000-0000-0000-0000-000000000001` (V5).
- Produces: permission codes `tenant_setting.manage_platform`, `business_date.reopen`, `cob.start`, `cob.complete`; new table `business_date_history` (jOOQ ref `BUSINESS_DATE_HISTORY` after build) with columns `id, organisation_id, event_type, from_status, to_status, from_business_date, to_business_date, actor_id, reason, occurred_at, created_at, created_by`.

- [ ] **Step 1: Write the migration**

Create `src/main/resources/db/migration/V8__tenant_settings_and_business_date_foundation.sql`:

```sql
-- New setup-level permissions for tenant settings and business-date / COB foundation.
INSERT INTO permission (
    id, permission_code, permission_name, module_code, description, risk_level, status,
    created_at, updated_at
) VALUES
    ('40000000-0000-0000-0000-000000000026', 'tenant_setting.manage_platform',
        'Manage platform-only settings', 'settings',
        'Create or update platform-admin-only organisation settings.', 'HIGH', 'ACTIVE',
        NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000027', 'business_date.reopen', 'Reopen business date',
        'settings', 'Reopen a closed organisation business date.', 'CRITICAL', 'ACTIVE',
        NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000028', 'cob.start', 'Start close of business', 'settings',
        'Start the close-of-business status transition.', 'HIGH', 'ACTIVE', NOW(), NOW()),
    ('40000000-0000-0000-0000-000000000029', 'cob.complete', 'Complete close of business',
        'settings', 'Complete the close-of-business status transition.', 'HIGH', 'ACTIVE',
        NOW(), NOW())
ON CONFLICT (permission_code) DO UPDATE SET
    permission_name = EXCLUDED.permission_name,
    module_code = EXCLUDED.module_code,
    description = EXCLUDED.description,
    risk_level = EXCLUDED.risk_level,
    status = EXCLUDED.status,
    updated_at = EXCLUDED.updated_at;

-- Grant every new permission to the platform super-admin role seeded in V5.
WITH new_super_admin_permissions (permission_number, permission_code) AS (
    VALUES
        (26, 'tenant_setting.manage_platform'),
        (27, 'business_date.reopen'),
        (28, 'cob.start'),
        (29, 'cob.complete')
)
INSERT INTO role_permission (
    id, organisation_id, role_id, permission_id, granted_at, created_at, updated_at
)
SELECT
    ('51000000-0000-0000-0000-' || LPAD(permission_number::TEXT, 12, '0'))::UUID,
    '00000000-0000-0000-0000-000000000000',
    '50000000-0000-0000-0000-000000000001',
    permission.id,
    NOW(),
    NOW(),
    NOW()
FROM new_super_admin_permissions
JOIN permission ON permission.permission_code = new_super_admin_permissions.permission_code
ON CONFLICT (organisation_id, role_id, permission_id) DO NOTHING;

-- Append-only history of business-date / COB status changes; the singleton business_date row
-- keeps only current state, so history lives here (mirrors the append-only audit posture).
CREATE TABLE business_date_history (
    id UUID PRIMARY KEY,
    organisation_id UUID NOT NULL REFERENCES organisation (id),
    event_type TEXT NOT NULL,
    from_status TEXT,
    to_status TEXT NOT NULL,
    from_business_date DATE,
    to_business_date DATE NOT NULL,
    actor_id UUID,
    reason TEXT,
    occurred_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    created_by UUID,
    CONSTRAINT chk_business_date_history_event CHECK (
        event_type IN ('INITIALIZED', 'ADVANCED', 'COB_STARTED', 'COB_COMPLETED', 'REOPENED')
    )
);

CREATE INDEX idx_business_date_history_org_time
    ON business_date_history (organisation_id, occurred_at DESC);

COMMENT ON TABLE business_date_history IS
    'Append-only log of business-date and COB status changes per organisation.';
```

- [ ] **Step 2: Regenerate jOOQ and confirm the new table/permissions compile**

Run: `./gradlew jooqCodegen`
Expected: BUILD SUCCESSFUL; file `build/generated-src/jooq/main/com/finaxis/platform/jooq/tables/references/BusinessDateHistory.kt` (or equivalent `references.kt` entry `BUSINESS_DATE_HISTORY`) is generated.

Verify: `grep -rl "BUSINESS_DATE_HISTORY" build/generated-src/jooq/main | head`
Expected: at least one match.

- [ ] **Step 3: Verify migration applies cleanly against a container (flyway validate via test bootstrap)**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.BusinessDateAdvancedOutboxIntegrationTests"`
Expected: PASS (existing test boots the full schema including V8; confirms V8 is valid SQL and does not break the baseline).

- [ ] **Step 4: Commit**

```bash
git add src/main/resources/db/migration/V8__tenant_settings_and_business_date_foundation.sql
git commit -m "feat: add V8 migration for tenant-setting permissions and business_date_history

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: `TenantSettingCatalog` domain registry + validation

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/domain/TenantSettingCatalog.kt`
- Test: `src/test/kotlin/com/finaxis/platform/lifecycle/domain/TenantSettingCatalogTests.kt`

**Interfaces:**
- Produces:
  - `enum class TenantSettingValueType { STRING, BOOLEAN, INT, TIMEZONE, CURRENCY }`
  - `data class TenantSettingDefinition(val key: String, val valueType: TenantSettingValueType, val sensitive: Boolean, val platformAdminOnly: Boolean, val defaultValue: String?)`
  - `object TenantSettingCatalog { fun definition(key: String): TenantSettingDefinition?  ;  fun require(key: String): TenantSettingDefinition  ;  fun canonicalize(key: String, rawValue: String): String  ;  fun keys(): Set<String>  ;  val definitions: List<TenantSettingDefinition> }`
  - `canonicalize` throws `IllegalArgumentException` for unknown keys or invalid values, and returns the canonical stored string form.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/finaxis/platform/lifecycle/domain/TenantSettingCatalogTests.kt`:

```kotlin
package com.finaxis.platform.lifecycle.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TenantSettingCatalogTests {
    @Test
    fun `catalog exposes the six foundation settings`() {
        assertEquals(
            setOf(
                "default_timezone",
                "base_currency",
                "require_maker_checker_for_user_invites",
                "require_maker_checker_for_branch_creation",
                "business_date_auto_advance_enabled",
                "audit_retention_days",
            ),
            TenantSettingCatalog.keys(),
        )
    }

    @Test
    fun `audit_retention_days is platform-admin-only`() {
        assertTrue(TenantSettingCatalog.require("audit_retention_days").platformAdminOnly)
        assertFalse(TenantSettingCatalog.require("base_currency").platformAdminOnly)
    }

    @Test
    fun `require rejects an unknown key`() {
        assertFailsWith<IllegalArgumentException> { TenantSettingCatalog.require("nope") }
    }

    @Test
    fun `canonicalize accepts a valid IANA timezone`() {
        assertEquals(
            "Africa/Nairobi",
            TenantSettingCatalog.canonicalize("default_timezone", "Africa/Nairobi"),
        )
    }

    @Test
    fun `canonicalize rejects a non-IANA timezone`() {
        assertFailsWith<IllegalArgumentException> {
            TenantSettingCatalog.canonicalize("default_timezone", "EST")
        }
    }

    @Test
    fun `canonicalize normalizes and validates an ISO 4217 currency`() {
        assertEquals("KES", TenantSettingCatalog.canonicalize("base_currency", "kes"))
    }

    @Test
    fun `canonicalize rejects an unknown currency`() {
        assertFailsWith<IllegalArgumentException> {
            TenantSettingCatalog.canonicalize("base_currency", "XXY")
        }
    }

    @Test
    fun `canonicalize validates boolean and integer settings`() {
        assertEquals(
            "true",
            TenantSettingCatalog.canonicalize("business_date_auto_advance_enabled", "TRUE"),
        )
        assertEquals("30", TenantSettingCatalog.canonicalize("audit_retention_days", "30"))
        assertFailsWith<IllegalArgumentException> {
            TenantSettingCatalog.canonicalize("business_date_auto_advance_enabled", "yes")
        }
        assertFailsWith<IllegalArgumentException> {
            TenantSettingCatalog.canonicalize("audit_retention_days", "-1")
        }
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.domain.TenantSettingCatalogTests"`
Expected: FAIL / compile error (`TenantSettingCatalog` unresolved).

- [ ] **Step 3: Write the catalog**

Create `src/main/kotlin/com/finaxis/platform/lifecycle/domain/TenantSettingCatalog.kt`:

```kotlin
package com.finaxis.platform.lifecycle.domain

import java.time.ZoneId
import java.util.Currency

/** Storage/validation kind of a tenant setting value. */
enum class TenantSettingValueType { STRING, BOOLEAN, INT, TIMEZONE, CURRENCY }

/** One allowed tenant setting: its key, value kind, sensitivity, and required authority scope. */
data class TenantSettingDefinition(
    val key: String,
    val valueType: TenantSettingValueType,
    val sensitive: Boolean,
    val platformAdminOnly: Boolean,
    val defaultValue: String?,
)

/**
 * Authoritative registry of the setup-level tenant settings this platform supports. Commands
 * validate keys and values against this catalog; arbitrary keys are rejected. See
 * `docs/operations/tenant-settings.md` for what is deliberately not a tenant setting.
 */
object TenantSettingCatalog {
    private val definitions0 =
        listOf(
            TenantSettingDefinition(
                "default_timezone", TenantSettingValueType.TIMEZONE, false, false, null,
            ),
            TenantSettingDefinition(
                "base_currency", TenantSettingValueType.CURRENCY, false, false, null,
            ),
            TenantSettingDefinition(
                "require_maker_checker_for_user_invites",
                TenantSettingValueType.BOOLEAN, false, false, "false",
            ),
            TenantSettingDefinition(
                "require_maker_checker_for_branch_creation",
                TenantSettingValueType.BOOLEAN, false, false, "false",
            ),
            TenantSettingDefinition(
                "business_date_auto_advance_enabled",
                TenantSettingValueType.BOOLEAN, false, false, "false",
            ),
            TenantSettingDefinition(
                "audit_retention_days", TenantSettingValueType.INT, false, true, null,
            ),
        )

    /** All setting definitions in declaration order. */
    val definitions: List<TenantSettingDefinition> get() = definitions0

    private val byKey = definitions0.associateBy(TenantSettingDefinition::key)

    /** Returns the definition for [key], or null when the key is not in the catalog. */
    fun definition(key: String): TenantSettingDefinition? = byKey[key]

    /** Returns the definition for [key]; throws [IllegalArgumentException] when unknown. */
    fun require(key: String): TenantSettingDefinition =
        requireNotNull(byKey[key]) { "Unknown tenant setting key: $key" }

    /** Returns every catalog key. */
    fun keys(): Set<String> = byKey.keys

    /**
     * Validates [rawValue] for [key] and returns the canonical stored string form. Throws
     * [IllegalArgumentException] for an unknown key or a value that fails its type rule.
     */
    fun canonicalize(
        key: String,
        rawValue: String,
    ): String {
        val definition = require(key)
        val trimmed = rawValue.trim()
        require(trimmed.isNotEmpty()) { "Setting $key must not be blank." }
        return when (definition.valueType) {
            TenantSettingValueType.STRING -> trimmed
            TenantSettingValueType.BOOLEAN -> canonicalizeBoolean(key, trimmed)
            TenantSettingValueType.INT -> canonicalizeInt(key, trimmed)
            TenantSettingValueType.TIMEZONE -> canonicalizeTimezone(key, trimmed)
            TenantSettingValueType.CURRENCY -> canonicalizeCurrency(key, trimmed)
        }
    }

    private fun canonicalizeBoolean(
        key: String,
        value: String,
    ): String =
        when (value.lowercase()) {
            "true" -> "true"
            "false" -> "false"
            else -> throw IllegalArgumentException("Setting $key must be true or false.")
        }

    private fun canonicalizeInt(
        key: String,
        value: String,
    ): String {
        val parsed = value.toIntOrNull()
        require(parsed != null && parsed >= 0) {
            "Setting $key must be a non-negative integer."
        }
        return parsed.toString()
    }

    private fun canonicalizeTimezone(
        key: String,
        value: String,
    ): String {
        require(ZoneId.getAvailableZoneIds().contains(value)) {
            "Setting $key must be a valid IANA time-zone id."
        }
        return value
    }

    private fun canonicalizeCurrency(
        key: String,
        value: String,
    ): String {
        val upper = value.uppercase()
        val valid = Currency.getAvailableCurrencies().any { it.currencyCode == upper }
        require(valid) { "Setting $key must be a valid ISO 4217 currency code." }
        return upper
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.domain.TenantSettingCatalogTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/domain/TenantSettingCatalog.kt \
        src/test/kotlin/com/finaxis/platform/lifecycle/domain/TenantSettingCatalogTests.kt
git commit -m "feat: add tenant setting catalog with type validation

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: `PermissionGuard` port (lifecycle) + `iam` adapter implementation

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/PermissionGuard.kt`
- Create: `src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapter.kt`
- Test: `src/test/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapterTests.kt`

**Interfaces:**
- Produces:
  - `interface PermissionGuard { fun requirePermission(actorId: UUID, organisationId: UUID, permissionCode: String) }` — root `com.finaxis.platform.lifecycle` package (an exposed module API, like `UserFirstLoginActivation`). Implementations throw when the actor lacks the permission.
- Consumes: `com.finaxis.platform.iam.application.authorization.AuthorizationService.requirePermission(userId, organisationId, permissionCode)` (throws `AccessDeniedException`).

> Rationale: `lifecycle` must not depend on `iam` (cycle). Defining the port in `lifecycle` and implementing it in `iam` keeps the only edge `iam → lifecycle`, which already exists and is allowed.

- [ ] **Step 1: Write the port interface**

Create `src/main/kotlin/com/finaxis/platform/lifecycle/PermissionGuard.kt`:

```kotlin
package com.finaxis.platform.lifecycle

import java.util.UUID

/**
 * Public lifecycle-module port for permission-code authorization. Implemented by the identity
 * module so that lifecycle application services can enforce permission codes without depending
 * on the identity module directly (which would create a module cycle).
 */
interface PermissionGuard {
    /**
     * Requires [actorId] to hold [permissionCode] within [organisationId]. Implementations throw
     * an authorization exception when the permission is absent.
     */
    fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    )
}
```

- [ ] **Step 2: Write the failing adapter test**

Create `src/test/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapterTests.kt`:

```kotlin
package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.iam.application.authorization.AccessDeniedException
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.iam.application.port.outbound.MembershipSelectionLookup
import com.finaxis.platform.iam.application.security.RequestPermissionCache
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LifecyclePermissionGuardAdapterTests {
    @Test
    fun `delegates denial to an AccessDeniedException`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()
        val authorizationService =
            object : AuthorizationService(NoMemberships, DenyingCache) {}
        val adapter = LifecyclePermissionGuardAdapter(authorizationService)

        assertFailsWith<AccessDeniedException> {
            adapter.requirePermission(userId, organisationId, "business_date.advance")
        }
    }

    private object NoMemberships : MembershipSelectionLookup {
        override fun organisationStatus(organisationId: UUID) = null
    }

    private object DenyingCache : RequestPermissionCache {
        override fun permissions(
            userId: UUID,
            organisationId: UUID,
        ): Set<String> = emptySet()

        override fun branchPermissions(
            userId: UUID,
            organisationId: UUID,
            branchId: UUID,
        ): Set<String> = emptySet()
    }
}
```

> If `MembershipSelectionLookup` / `RequestPermissionCache` signatures differ, open those interfaces and adjust the fakes to match exactly — the point of the test is only that denial surfaces as `AccessDeniedException`.

- [ ] **Step 3: Run test to verify it fails**

Run: `./gradlew test --tests "com.finaxis.platform.iam.adapter.outbound.authorization.LifecyclePermissionGuardAdapterTests"`
Expected: FAIL (`LifecyclePermissionGuardAdapter` unresolved).

- [ ] **Step 4: Write the adapter**

Create `src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapter.kt`:

```kotlin
package com.finaxis.platform.iam.adapter.outbound.authorization

import com.finaxis.platform.iam.application.authorization.AuthorizationService
import com.finaxis.platform.lifecycle.PermissionGuard
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Identity-module implementation of the lifecycle [PermissionGuard] port. Resolves the actor's
 * effective permission codes for the organisation and throws when the required code is absent.
 */
@Component
class LifecyclePermissionGuardAdapter(
    private val authorizationService: AuthorizationService,
) : PermissionGuard {
    override fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        authorizationService.requirePermission(actorId, organisationId, permissionCode)
    }
}
```

- [ ] **Step 5: Run test to verify it passes**

Run: `./gradlew test --tests "com.finaxis.platform.iam.adapter.outbound.authorization.LifecyclePermissionGuardAdapterTests"`
Expected: PASS.

- [ ] **Step 6: Verify Modulith boundaries still pass**

Run: `./gradlew test --tests "com.finaxis.platform.architecture.ModulithArchitectureTest"`
Expected: PASS (confirms the port lives in the exposed `lifecycle` root and no new cycle was introduced).

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/PermissionGuard.kt \
        src/main/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapter.kt \
        src/test/kotlin/com/finaxis/platform/iam/adapter/outbound/authorization/LifecyclePermissionGuardAdapterTests.kt
git commit -m "feat: add PermissionGuard port and iam authorization adapter

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Settings persistence — single-key upsert, deactivate, and typed reads

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningPorts.kt` (extend `OrganisationSettingsStore`)
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationSettingsAndBusinessDateStore.kt` (implement new methods)

**Interfaces:**
- Consumes: existing `ORGANISATION_SETTING` jOOQ table; `TenantSettingCatalog` (Task 2) is NOT used here — the store is told the `valueType`/`sensitive` explicitly.
- Produces (added to `OrganisationSettingsStore`):
  - `data class StoredSetting(val key: String, val value: String, val valueType: String, val sensitive: Boolean)` (new top-level data class in the ports file)
  - `fun currentSetting(organisationId: UUID, key: String): StoredSetting?`
  - `fun currentSettingsList(organisationId: UUID): List<StoredSetting>`
  - `fun upsertSetting(organisationId: UUID, key: String, value: String, valueType: String, sensitive: Boolean, actorId: UUID)`
  - `fun deactivateSetting(organisationId: UUID, key: String, actorId: UUID): Boolean` (returns false when no open row exists)

- [ ] **Step 1: Extend the port**

In `OrganisationBranchProvisioningPorts.kt`, replace the `OrganisationSettingsStore` interface (lines 100-114) with:

```kotlin
/** Organisation settings persistence port for post-provisioning configuration changes. */
interface OrganisationSettingsStore {
    /** Returns the currently effective values for the requested setting keys. */
    fun currentSettings(
        organisationId: UUID,
        keys: Set<String>,
    ): Map<String, String>

    /** Closes each currently effective row and inserts a new effective-dated row per key. */
    fun updateSettings(
        organisationId: UUID,
        updates: Map<String, String>,
        actorId: UUID,
    )

    /** Returns the currently effective stored setting for [key], or null when unset. */
    fun currentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting?

    /** Returns every currently effective stored setting for the organisation. */
    fun currentSettingsList(organisationId: UUID): List<StoredSetting>

    /** Closes the open row for [key] and inserts a new effective-dated row with typed metadata. */
    fun upsertSetting(
        organisationId: UUID,
        key: String,
        value: String,
        valueType: String,
        sensitive: Boolean,
        actorId: UUID,
    )

    /** Closes the open row for [key] with no replacement; false when no open row existed. */
    fun deactivateSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
    ): Boolean
}

/** A currently effective organisation setting row with its type and sensitivity metadata. */
data class StoredSetting(
    val key: String,
    val value: String,
    val valueType: String,
    val sensitive: Boolean,
)
```

- [ ] **Step 2: Implement the new methods in the jOOQ adapter**

In `JooqOrganisationSettingsAndBusinessDateStore.kt`, add these methods to `JooqOrganisationSettingsStore` (inside the class, after `updateSettings`), and add the needed imports (`import com.finaxis.platform.lifecycle.application.StoredSetting`):

```kotlin
    override fun currentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting? =
        dsl
            .select(
                ORGANISATION_SETTING.SETTING_KEY,
                ORGANISATION_SETTING.SETTING_VALUE,
                ORGANISATION_SETTING.VALUE_TYPE,
                ORGANISATION_SETTING.IS_SENSITIVE,
            ).from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(key))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .fetchOne(::storedSetting)

    override fun currentSettingsList(organisationId: UUID): List<StoredSetting> =
        dsl
            .select(
                ORGANISATION_SETTING.SETTING_KEY,
                ORGANISATION_SETTING.SETTING_VALUE,
                ORGANISATION_SETTING.VALUE_TYPE,
                ORGANISATION_SETTING.IS_SENSITIVE,
            ).from(ORGANISATION_SETTING)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .fetch(::storedSetting)

    override fun upsertSetting(
        organisationId: UUID,
        key: String,
        value: String,
        valueType: String,
        sensitive: Boolean,
        actorId: UUID,
    ) {
        val now = now()
        lockSettingKey(organisationId, key)
        closeCurrentSetting(organisationId, key, actorId, now)
        dsl
            .insertInto(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.ID, uuidV7())
            .set(ORGANISATION_SETTING.ORGANISATION_ID, organisationId)
            .set(ORGANISATION_SETTING.SETTING_KEY, key)
            .set(
                ORGANISATION_SETTING.SETTING_VALUE,
                JSONB.jsonb(objectMapper.writeValueAsString(value)),
            ).set(ORGANISATION_SETTING.VALUE_TYPE, valueType)
            .set(ORGANISATION_SETTING.IS_SENSITIVE, sensitive)
            .set(ORGANISATION_SETTING.EFFECTIVE_FROM, now)
            .set(ORGANISATION_SETTING.CREATED_AT, now)
            .set(ORGANISATION_SETTING.CREATED_BY, actorId)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
            .execute()
    }

    override fun deactivateSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
    ): Boolean {
        lockSettingKey(organisationId, key)
        return closeCurrentSetting(organisationId, key, actorId, now()) == 1
    }

    private fun storedSetting(record: org.jooq.Record): StoredSetting {
        val key = requireNotNull(record.get(ORGANISATION_SETTING.SETTING_KEY))
        val value =
            objectMapper.readValue(
                requireNotNull(record.get(ORGANISATION_SETTING.SETTING_VALUE)).data(),
                String::class.java,
            )
        return StoredSetting(
            key = key,
            value = value,
            valueType = requireNotNull(record.get(ORGANISATION_SETTING.VALUE_TYPE)),
            sensitive = record.get(ORGANISATION_SETTING.IS_SENSITIVE) ?: false,
        )
    }
```

Then change the existing `closeCurrentSetting` to return the affected row count (it currently returns `execute()`'s result implicitly discarded). Update its signature/return:

```kotlin
    private fun closeCurrentSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
        now: java.time.OffsetDateTime,
    ): Int =
        dsl
            .update(ORGANISATION_SETTING)
            .set(ORGANISATION_SETTING.EFFECTIVE_TO, now)
            .set(ORGANISATION_SETTING.UPDATED_AT, now)
            .set(ORGANISATION_SETTING.UPDATED_BY, actorId)
            .where(ORGANISATION_SETTING.ORGANISATION_ID.eq(organisationId))
            .and(ORGANISATION_SETTING.SETTING_KEY.eq(key))
            .and(ORGANISATION_SETTING.EFFECTIVE_TO.isNull)
            .execute()
```

(The existing `updateSettings` loop calls `closeCurrentSetting(...)` and ignores the return — that still compiles.)

- [ ] **Step 3: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 4: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningPorts.kt \
        src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationSettingsAndBusinessDateStore.kt
git commit -m "feat: add single-key settings upsert, deactivate, and typed reads

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Business-date persistence — initialize, status change, reopen, and history

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningPorts.kt` (extend `BusinessDateStore`, add `BusinessDateHistoryStore`)
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationSettingsAndBusinessDateStore.kt` (implement new methods + new history adapter)

**Interfaces:**
- Consumes: `BUSINESS_DATE`, `BUSINESS_DATE_HISTORY` jOOQ tables.
- Produces:
  - Added to `BusinessDateStore`:
    - `fun initialize(organisationId: UUID, initialDate: LocalDate, actorId: UUID): Boolean` (false when a row already exists)
    - `fun changeStatus(organisationId: UUID, newStatus: String, cobDate: LocalDate?, expectedRowVersion: Long, actorId: UUID): Boolean`
  - New `interface BusinessDateHistoryStore { fun append(entry: BusinessDateHistoryEntry)  ;  fun list(organisationId: UUID, page: Int, size: Int): BusinessDateHistoryPage }`
  - `data class BusinessDateHistoryEntry(organisationId, eventType, fromStatus, toStatus, fromBusinessDate, toBusinessDate, actorId, reason, occurredAt)` (types: UUID, String, String?, String, LocalDate?, LocalDate, UUID?, String?, Instant)
  - `data class BusinessDateHistoryRecord(eventType, fromStatus, toStatus, fromBusinessDate, toBusinessDate, actorId, reason, occurredAt)` (read model)
  - `data class BusinessDateHistoryPage(val items: List<BusinessDateHistoryRecord>, val totalItems: Long)`

- [ ] **Step 1: Extend the ports**

In `OrganisationBranchProvisioningPorts.kt`, replace the `BusinessDateStore` interface (lines 116-128) and add the history types. Add imports `java.time.Instant`:

```kotlin
/** Business date persistence port for the controlled, optimistically-locked business date. */
interface BusinessDateStore {
    /** Returns the current business date snapshot used for optimistic-lock validation. */
    fun current(organisationId: UUID): BusinessDateSnapshot?

    /** Advances the business date; returns `false` when [expectedRowVersion] is stale. */
    fun advance(
        organisationId: UUID,
        newDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean

    /** Inserts the singleton business-date row as OPEN; false when one already exists. */
    fun initialize(
        organisationId: UUID,
        initialDate: LocalDate,
        actorId: UUID,
    ): Boolean

    /** Sets status (and optional COB date) under optimistic lock; false when the version is stale. */
    fun changeStatus(
        organisationId: UUID,
        newStatus: String,
        cobDate: LocalDate?,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean
}

/** Append-only persistence port for business-date / COB status change history. */
interface BusinessDateHistoryStore {
    /** Appends one history entry. */
    fun append(entry: BusinessDateHistoryEntry)

    /** Returns a page of history entries newest-first for the organisation. */
    fun list(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): BusinessDateHistoryPage
}

/** A business-date / COB status change to append to history. */
data class BusinessDateHistoryEntry(
    val organisationId: UUID,
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val fromBusinessDate: LocalDate?,
    val toBusinessDate: LocalDate,
    val actorId: UUID?,
    val reason: String?,
    val occurredAt: Instant,
)

/** A history entry read back for the history query. */
data class BusinessDateHistoryRecord(
    val eventType: String,
    val fromStatus: String?,
    val toStatus: String,
    val fromBusinessDate: LocalDate?,
    val toBusinessDate: LocalDate,
    val actorId: UUID?,
    val reason: String?,
    val occurredAt: Instant,
)

/** A page of business-date history entries. */
data class BusinessDateHistoryPage(
    val items: List<BusinessDateHistoryRecord>,
    val totalItems: Long,
)
```

- [ ] **Step 2: Implement the new `BusinessDateStore` methods**

In `JooqBusinessDateStore` (same file), add:

```kotlin
    override fun initialize(
        organisationId: UUID,
        initialDate: LocalDate,
        actorId: UUID,
    ): Boolean {
        val exists =
            dsl.fetchExists(
                dsl
                    .selectOne()
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId)),
            )
        if (exists) return false
        dsl
            .insertInto(BUSINESS_DATE)
            .set(BUSINESS_DATE.ID, uuidV7())
            .set(BUSINESS_DATE.ORGANISATION_ID, organisationId)
            .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, initialDate)
            .set(BUSINESS_DATE.STATUS, "OPEN")
            .set(BUSINESS_DATE.LAST_ADVANCED_AT, clock.instant().atOffset(ZoneOffset.UTC))
            .set(BUSINESS_DATE.ADVANCED_BY, actorId)
            .execute()
        return true
    }

    override fun changeStatus(
        organisationId: UUID,
        newStatus: String,
        cobDate: LocalDate?,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        val update =
            dsl
                .update(BUSINESS_DATE)
                .set(BUSINESS_DATE.STATUS, newStatus)
                .set(BUSINESS_DATE.CURRENT_COB_DATE, cobDate)
                .set(BUSINESS_DATE.ROW_VERSION, expectedRowVersion + 1)
        return update
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
            .and(BUSINESS_DATE.ROW_VERSION.eq(expectedRowVersion))
            .execute() == 1
    }
```

Add `import com.finaxis.platform.common.id.uuidV7` if not already imported (it is, at top of file).

- [ ] **Step 3: Add the history adapter**

At the end of `JooqOrganisationSettingsAndBusinessDateStore.kt`, add a new `@Component` class and imports (`BUSINESS_DATE_HISTORY`, the history data classes, `java.time.Instant`):

```kotlin
/** jOOQ adapter for the append-only business-date / COB status change history. */
@Component("businessDateHistoryStore")
class JooqBusinessDateHistoryStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : BusinessDateHistoryStore {
    override fun append(entry: BusinessDateHistoryEntry) {
        val now = clock.instant().atOffset(ZoneOffset.UTC)
        dsl
            .insertInto(BUSINESS_DATE_HISTORY)
            .set(BUSINESS_DATE_HISTORY.ID, uuidV7())
            .set(BUSINESS_DATE_HISTORY.ORGANISATION_ID, entry.organisationId)
            .set(BUSINESS_DATE_HISTORY.EVENT_TYPE, entry.eventType)
            .set(BUSINESS_DATE_HISTORY.FROM_STATUS, entry.fromStatus)
            .set(BUSINESS_DATE_HISTORY.TO_STATUS, entry.toStatus)
            .set(BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE, entry.fromBusinessDate)
            .set(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE, entry.toBusinessDate)
            .set(BUSINESS_DATE_HISTORY.ACTOR_ID, entry.actorId)
            .set(BUSINESS_DATE_HISTORY.REASON, entry.reason)
            .set(BUSINESS_DATE_HISTORY.OCCURRED_AT, entry.occurredAt.atOffset(ZoneOffset.UTC))
            .set(BUSINESS_DATE_HISTORY.CREATED_AT, now)
            .set(BUSINESS_DATE_HISTORY.CREATED_BY, entry.actorId)
            .execute()
    }

    override fun list(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): BusinessDateHistoryPage {
        val total =
            dsl
                .selectCount()
                .from(BUSINESS_DATE_HISTORY)
                .where(BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId))
                .fetchOne(0, Long::class.java) ?: 0L
        val items =
            dsl
                .select(
                    BUSINESS_DATE_HISTORY.EVENT_TYPE,
                    BUSINESS_DATE_HISTORY.FROM_STATUS,
                    BUSINESS_DATE_HISTORY.TO_STATUS,
                    BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE,
                    BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE,
                    BUSINESS_DATE_HISTORY.ACTOR_ID,
                    BUSINESS_DATE_HISTORY.REASON,
                    BUSINESS_DATE_HISTORY.OCCURRED_AT,
                ).from(BUSINESS_DATE_HISTORY)
                .where(BUSINESS_DATE_HISTORY.ORGANISATION_ID.eq(organisationId))
                .orderBy(BUSINESS_DATE_HISTORY.OCCURRED_AT.desc())
                .limit(size)
                .offset(page * size)
                .fetch(::historyRecord)
        return BusinessDateHistoryPage(items, total)
    }

    private fun historyRecord(record: org.jooq.Record): BusinessDateHistoryRecord =
        BusinessDateHistoryRecord(
            eventType = requireNotNull(record.get(BUSINESS_DATE_HISTORY.EVENT_TYPE)),
            fromStatus = record.get(BUSINESS_DATE_HISTORY.FROM_STATUS),
            toStatus = requireNotNull(record.get(BUSINESS_DATE_HISTORY.TO_STATUS)),
            fromBusinessDate = record.get(BUSINESS_DATE_HISTORY.FROM_BUSINESS_DATE),
            toBusinessDate = requireNotNull(record.get(BUSINESS_DATE_HISTORY.TO_BUSINESS_DATE)),
            actorId = record.get(BUSINESS_DATE_HISTORY.ACTOR_ID),
            reason = record.get(BUSINESS_DATE_HISTORY.REASON),
            occurredAt = requireNotNull(record.get(BUSINESS_DATE_HISTORY.OCCURRED_AT)).toInstant(),
        )
}
```

Add imports at top: `import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE_HISTORY`, `import com.finaxis.platform.lifecycle.application.BusinessDateHistoryEntry`, `import com.finaxis.platform.lifecycle.application.BusinessDateHistoryPage`, `import com.finaxis.platform.lifecycle.application.BusinessDateHistoryRecord`, `import com.finaxis.platform.lifecycle.application.BusinessDateHistoryStore`, `import java.time.Instant`.

- [ ] **Step 4: Compile**

Run: `./gradlew compileKotlin`
Expected: BUILD SUCCESSFUL (requires Task 1's `BUSINESS_DATE_HISTORY` ref).

- [ ] **Step 5: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningPorts.kt \
        src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationSettingsAndBusinessDateStore.kt
git commit -m "feat: add business-date initialize/status persistence and history store

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: `TenantSettingsService` — commands, queries, permission gate, redaction

**Files:**
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingCommands.kt`
- Create: `src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsService.kt`
- Test: `src/test/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsServiceTests.kt`

**Interfaces:**
- Consumes: `OrganisationLifecycleProvisioningStore.lifecycleState`, `OrganisationSettingsStore` (Task 4), `PermissionGuard` (Task 3), `TenantSettingCatalog` (Task 2), `AuditService.recordSettingsChange` and `recordSuccess`, `Redacted`, `PlatformOrganisation.ID`, `TransitionEventPublisher`, `ExternalizedTransitionEvent`.
- Produces:
  - Commands/queries/results in `TenantSettingCommands.kt`:
    - `data class CreateOrUpdateTenantSettingCommand(organisationId: UUID, key: String, value: String, actorId: UUID, reason: String? = null)`
    - `data class DeactivateTenantSettingCommand(organisationId: UUID, key: String, actorId: UUID, reason: String? = null)`
    - `data class GetTenantSettingQuery(organisationId: UUID, key: String)`
    - `data class ListTenantSettingsQuery(organisationId: UUID)`
    - `data class TenantSettingView(key: String, value: String?, valueType: String, sensitive: Boolean, platformAdminOnly: Boolean)`
  - `TenantSettingsService` with:
    - `fun createOrUpdate(command: CreateOrUpdateTenantSettingCommand): TenantSettingView`
    - `fun deactivate(command: DeactivateTenantSettingCommand)`
    - `fun get(query: GetTenantSettingQuery): TenantSettingView`
    - `fun list(query: ListTenantSettingsQuery): List<TenantSettingView>`
  - Sensitive values are returned as `"***REDACTED***"` by `get`/`list`.

- [ ] **Step 1: Write the failing tests**

Create `src/test/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsServiceTests.kt`:

```kotlin
package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.Clock
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class TenantSettingsServiceTests {
    private val lifecycleStore = FakeLifecycleStoreForSettings()
    private val settingsStore = FakeSettingsStore()
    private val guard = FakePermissionGuard()
    private val events = CapturingPublisherForSettings()
    private val auditEvents = RecordingAuditRepository()
    private val auditService = AuditService(auditEvents, Clock.systemUTC())
    private val service =
        TenantSettingsService(lifecycleStore, settingsStore, guard, auditService, events)

    private val organisationId = uuidV7()
    private val actorId = uuidV7()

    private fun activate() {
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
    }

    @Test
    fun `createOrUpdate rejects an inactive organisation`() {
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED
        assertFailsWith<IllegalArgumentException> {
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId, "base_currency", "KES", actorId,
                ),
            )
        }
    }

    @Test
    fun `createOrUpdate rejects an unknown key`() {
        activate()
        assertFailsWith<IllegalArgumentException> {
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(organisationId, "bogus", "x", actorId),
            )
        }
    }

    @Test
    fun `createOrUpdate requires settings-update permission for a normal setting`() {
        activate()
        guard.deny(organisationId, "settings.update")
        assertFailsWith<SecurityException> {
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(organisationId, "base_currency", "KES", actorId),
            )
        }
    }

    @Test
    fun `createOrUpdate persists, audits, and publishes for a normal setting`() {
        activate()
        val view =
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(organisationId, "base_currency", "kes", actorId),
            )
        assertEquals("KES", view.value)
        assertEquals("KES", settingsStore.stored(organisationId, "base_currency")?.value)
        assertEquals(1, auditEvents.saved.count { it.action == "settings.update" })
        assertEquals(1, events.published.size)
    }

    @Test
    fun `createOrUpdate requires platform permission for a platform-only setting`() {
        activate()
        guard.deny(PLATFORM_ORG_ID, "tenant_setting.manage_platform")
        assertFailsWith<SecurityException> {
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId, "audit_retention_days", "30", actorId,
                ),
            )
        }
    }

    @Test
    fun `get redacts a sensitive setting value`() {
        activate()
        settingsStore.put(organisationId, StoredSetting("some_secret", "hunter2", "STRING", true))
        val view = service.get(GetTenantSettingQuery(organisationId, "some_secret"))
        assertEquals("***REDACTED***", view.value)
    }

    @Test
    fun `get returns null value for an unset catalog key`() {
        activate()
        assertNull(service.get(GetTenantSettingQuery(organisationId, "base_currency")).value)
    }

    @Test
    fun `deactivate closes the setting`() {
        activate()
        settingsStore.put(organisationId, StoredSetting("base_currency", "KES", "CURRENCY", false))
        service.deactivate(DeactivateTenantSettingCommand(organisationId, "base_currency", actorId))
        assertNull(settingsStore.stored(organisationId, "base_currency"))
    }

    private companion object {
        val PLATFORM_ORG_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
    }
}

private class FakeLifecycleStoreForSettings : OrganisationLifecycleProvisioningStore {
    val states = mutableMapOf<UUID, OrganisationLifecycleState>()

    override fun lifecycleState(organisationId: UUID) = states[organisationId]

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID = uuidV7()

    override fun saveSettings(
        organisationId: UUID,
        settings: Map<String, String>,
        actorId: UUID,
    ) = Unit

    override fun hasRequiredMetadata(organisationId: UUID): Boolean = true
}

private class FakeSettingsStore : OrganisationSettingsStore {
    private val data = mutableMapOf<Pair<UUID, String>, StoredSetting>()

    fun put(
        organisationId: UUID,
        setting: StoredSetting,
    ) {
        data[organisationId to setting.key] = setting
    }

    fun stored(
        organisationId: UUID,
        key: String,
    ): StoredSetting? = data[organisationId to key]

    override fun currentSettings(
        organisationId: UUID,
        keys: Set<String>,
    ): Map<String, String> =
        keys.mapNotNull { key -> data[organisationId to key]?.let { key to it.value } }.toMap()

    override fun updateSettings(
        organisationId: UUID,
        updates: Map<String, String>,
        actorId: UUID,
    ) = Unit

    override fun currentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting? = data[organisationId to key]

    override fun currentSettingsList(organisationId: UUID): List<StoredSetting> =
        data.filterKeys { it.first == organisationId }.values.toList()

    override fun upsertSetting(
        organisationId: UUID,
        key: String,
        value: String,
        valueType: String,
        sensitive: Boolean,
        actorId: UUID,
    ) {
        data[organisationId to key] = StoredSetting(key, value, valueType, sensitive)
    }

    override fun deactivateSetting(
        organisationId: UUID,
        key: String,
        actorId: UUID,
    ): Boolean = data.remove(organisationId to key) != null
}

private class FakePermissionGuard : PermissionGuard {
    private val denied = mutableSetOf<Pair<UUID, String>>()

    fun deny(
        organisationId: UUID,
        permissionCode: String,
    ) {
        denied.add(organisationId to permissionCode)
    }

    override fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (denied.contains(organisationId to permissionCode)) {
            throw SecurityException("Missing permission: $permissionCode")
        }
    }
}

private class CapturingPublisherForSettings : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}

private class RecordingAuditRepository : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent): AuditEvent {
        saved.add(event)
        return event
    }
}
```

> Before writing the service, open `AuditEventRepository` to confirm the `save` signature used by the fake. If it differs, adjust `RecordingAuditRepository` to implement it exactly.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.application.TenantSettingsServiceTests"`
Expected: FAIL (`TenantSettingsService` / command types unresolved).

- [ ] **Step 3: Write the commands file**

Create `src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingCommands.kt`:

```kotlin
package com.finaxis.platform.lifecycle.application

import java.util.UUID

/** Creates or updates one tenant setting, adding a new effective-dated row. */
data class CreateOrUpdateTenantSettingCommand(
    val organisationId: UUID,
    val key: String,
    val value: String,
    val actorId: UUID,
    val reason: String? = null,
)

/** Deactivates one tenant setting by closing its currently effective row with no replacement. */
data class DeactivateTenantSettingCommand(
    val organisationId: UUID,
    val key: String,
    val actorId: UUID,
    val reason: String? = null,
)

/** Reads one tenant setting for an organisation. */
data class GetTenantSettingQuery(
    val organisationId: UUID,
    val key: String,
)

/** Lists all currently effective tenant settings for an organisation. */
data class ListTenantSettingsQuery(
    val organisationId: UUID,
)

/** A tenant setting projected for reads; [value] is redacted or null when unset. */
data class TenantSettingView(
    val key: String,
    val value: String?,
    val valueType: String,
    val sensitive: Boolean,
    val platformAdminOnly: Boolean,
)
```

- [ ] **Step 4: Write the service**

Create `src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsService.kt`:

```kotlin
package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.Redacted
import com.finaxis.platform.common.persistence.PlatformOrganisation
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import com.finaxis.platform.lifecycle.domain.TenantSettingDefinition
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * Manages setup-level tenant settings validated against [TenantSettingCatalog]. Settings are
 * effective-dated (never overwritten destructively); mutations require the tenant to be active,
 * are permission-gated per setting, and are audited with sensitive values redacted.
 */
@Service
class TenantSettingsService(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val settingsStore: OrganisationSettingsStore,
    private val permissionGuard: PermissionGuard,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
) {
    /** Creates or updates one tenant setting for an active organisation. */
    @Transactional
    fun createOrUpdate(command: CreateOrUpdateTenantSettingCommand): TenantSettingView {
        requireActive(command.organisationId)
        val definition = TenantSettingCatalog.require(command.key)
        val canonical = TenantSettingCatalog.canonicalize(command.key, command.value)
        authorize(definition, command.organisationId, command.actorId)

        val before = settingsStore.currentSetting(command.organisationId, command.key)
        settingsStore.upsertSetting(
            command.organisationId,
            command.key,
            canonical,
            definition.valueType.name,
            definition.sensitive,
            command.actorId,
        )
        auditChange(
            command.organisationId,
            command.actorId,
            command.key,
            definition,
            beforeValue = before?.value,
            afterValue = canonical,
            reason = command.reason,
        )
        publish(command.organisationId, command.actorId, command.key, "TenantSettingsUpdated")
        return view(definition, canonical)
    }

    /** Deactivates one tenant setting for an active organisation. */
    @Transactional
    fun deactivate(command: DeactivateTenantSettingCommand) {
        requireActive(command.organisationId)
        val definition = TenantSettingCatalog.require(command.key)
        authorize(definition, command.organisationId, command.actorId)

        val before = settingsStore.currentSetting(command.organisationId, command.key)
        settingsStore.deactivateSetting(command.organisationId, command.key, command.actorId)
        auditChange(
            command.organisationId,
            command.actorId,
            command.key,
            definition,
            beforeValue = before?.value,
            afterValue = null,
            reason = command.reason,
        )
        publish(command.organisationId, command.actorId, command.key, "TenantSettingDeactivated")
    }

    /** Reads one tenant setting, redacting sensitive values. */
    @Transactional(readOnly = true)
    fun get(query: GetTenantSettingQuery): TenantSettingView {
        val definition = TenantSettingCatalog.require(query.key)
        val stored = settingsStore.currentSetting(query.organisationId, query.key)
        return view(definition, stored?.value)
    }

    /** Lists all currently effective tenant settings, redacting sensitive values. */
    @Transactional(readOnly = true)
    fun list(query: ListTenantSettingsQuery): List<TenantSettingView> {
        val stored =
            settingsStore
                .currentSettingsList(query.organisationId)
                .associateBy { it.key }
        return TenantSettingCatalog.definitions.map { definition ->
            view(definition, stored[definition.key]?.value)
        }
    }

    private fun requireActive(organisationId: UUID) {
        require(lifecycleStore.lifecycleState(organisationId) == OrganisationLifecycleState.ACTIVE) {
            "Settings can be changed only for an active organisation."
        }
    }

    private fun authorize(
        definition: TenantSettingDefinition,
        organisationId: UUID,
        actorId: UUID,
    ) {
        if (definition.platformAdminOnly) {
            permissionGuard.requirePermission(
                actorId, PlatformOrganisation.ID, PLATFORM_SETTING_PERMISSION,
            )
        } else {
            permissionGuard.requirePermission(actorId, organisationId, SETTING_PERMISSION)
        }
    }

    private fun auditChange(
        organisationId: UUID,
        actorId: UUID,
        key: String,
        definition: TenantSettingDefinition,
        beforeValue: String?,
        afterValue: String?,
        reason: String?,
    ) {
        auditService.recordSettingsChange(
            actorId = actorId,
            tenantId = organisationId,
            resourceId = key,
            before = mapOf(key to auditValue(definition, beforeValue)),
            after = mapOf(key to auditValue(definition, afterValue)),
            reason = reason,
        )
    }

    private fun auditValue(
        definition: TenantSettingDefinition,
        value: String?,
    ): Any? = if (definition.sensitive && value != null) Redacted(value) else value

    private fun view(
        definition: TenantSettingDefinition,
        rawValue: String?,
    ): TenantSettingView {
        val display = if (definition.sensitive && rawValue != null) MASK else rawValue
        return TenantSettingView(
            key = definition.key,
            value = display,
            valueType = definition.valueType.name,
            sensitive = definition.sensitive,
            platformAdminOnly = definition.platformAdminOnly,
        )
    }

    private fun publish(
        organisationId: UUID,
        actorId: UUID,
        key: String,
        eventType: String,
    ) {
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = SETTINGS_UPDATED_TARGET,
                aggregateType = ORGANISATION_SETTING,
                aggregateId = organisationId.toString(),
                transition = "UPDATE",
                fromState = "PREVIOUS",
                toState = "CURRENT",
                actor = TransitionActor("USER", actorId.toString()),
                occurredAt = Instant.now(),
                metadata =
                    mapOf(
                        "organisationId" to organisationId.toString(),
                        "eventType" to eventType,
                        "key" to key,
                    ),
            ),
        )
    }

    private companion object {
        const val SETTINGS_UPDATED_TARGET = "finaxis.lifecycle.organisation.settings-updated"
        const val ORGANISATION_SETTING = "ORGANISATION_SETTING"
        const val SETTING_PERMISSION = "settings.update"
        const val PLATFORM_SETTING_PERMISSION = "tenant_setting.manage_platform"
        const val MASK = "***REDACTED***"
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.application.TenantSettingsServiceTests"`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingCommands.kt \
        src/main/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsService.kt \
        src/test/kotlin/com/finaxis/platform/lifecycle/application/TenantSettingsServiceTests.kt
git commit -m "feat: add TenantSettingsService with catalog validation and redaction

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: `BusinessDateService` extension — initialize, COB, reopen, permission gate, history, queries

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/BusinessDateService.kt`
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningCommands.kt` (add new commands/queries/results)
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/application/BusinessDateServiceTests.kt` (extend fakes + add tests)

**Interfaces:**
- Consumes: `OrganisationLifecycleProvisioningStore`, `BusinessDateStore` (Task 5, now with `initialize`/`changeStatus`), `BusinessDateHistoryStore` (Task 5), `PermissionGuard` (Task 3), `AuditService.recordSuccess`, `TransitionEventPublisher`, `Clock`.
- Produces (added command/query/result types):
  - `data class InitializeBusinessDateCommand(organisationId: UUID, initialBusinessDate: LocalDate, actorId: UUID, reason: String? = null)`
  - `data class StartCobCommand(organisationId: UUID, actorId: UUID, reason: String? = null)`
  - `data class CompleteCobCommand(organisationId: UUID, actorId: UUID, reason: String? = null)`
  - `data class ReopenBusinessDateCommand(organisationId: UUID, actorId: UUID, reason: String? = null)`
  - `data class GetBusinessDateQuery(organisationId: UUID)`
  - `data class ListBusinessDateHistoryQuery(organisationId: UUID, page: Int = 0, size: Int = 25)`
  - `data class BusinessDateView(organisationId: UUID, currentBusinessDate: LocalDate, status: String)`
  - `BusinessDateService` gains: `fun initialize(command)`, `fun startCob(command)`, `fun completeCob(command)`, `fun reopen(command)`, `fun get(query): BusinessDateView`, `fun listHistory(query): BusinessDateHistoryPage`; and `advance` now takes a `PermissionGuard` gate.

> Status model driven: `OPEN --advance--> OPEN`, `OPEN --StartCob--> CLOSING`, `CLOSING --CompleteCob--> CLOSED`, `CLOSED --Reopen--> OPEN`. Permissions: initialize/advance = `business_date.advance`, startCob = `cob.start`, completeCob = `cob.complete`, reopen = `business_date.reopen`, queries = `business_date.view`.

- [ ] **Step 1: Add commands/queries/results**

In `OrganisationBranchProvisioningCommands.kt`, after `BusinessDateAdvanceResult` (line 212), add:

```kotlin

/** Initializes the singleton business date for an organisation. */
data class InitializeBusinessDateCommand(
    val organisationId: UUID,
    val initialBusinessDate: LocalDate,
    val actorId: UUID,
    val reason: String? = null,
)

/** Starts the close-of-business status transition (OPEN to CLOSING). */
data class StartCobCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

/** Completes the close-of-business status transition (CLOSING to CLOSED). */
data class CompleteCobCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

/** Reopens a closed business date (CLOSED to OPEN); requires an explicit permission. */
data class ReopenBusinessDateCommand(
    val organisationId: UUID,
    val actorId: UUID,
    val reason: String? = null,
)

/** Reads the current business date for an organisation. */
data class GetBusinessDateQuery(
    val organisationId: UUID,
)

/** Lists business-date / COB change history for an organisation, newest first. */
data class ListBusinessDateHistoryQuery(
    val organisationId: UUID,
    val page: Int = 0,
    val size: Int = 25,
)

/** The current business date projected for reads. */
data class BusinessDateView(
    val organisationId: UUID,
    val currentBusinessDate: LocalDate,
    val status: String,
)
```

- [ ] **Step 2: Write failing tests (extend the existing file)**

In `BusinessDateServiceTests.kt`, replace the service construction line (line 19) and add new tests + fake extensions. Change line 19 to inject the new collaborators:

```kotlin
    private val guard = FakeGuardForBusinessDate()
    private val history = FakeBusinessDateHistoryStore()
    private val auditRepo = RecordingAuditRepositoryForBusinessDate()
    private val auditService =
        com.finaxis.platform.common.audit.AuditService(auditRepo, java.time.Clock.systemUTC())
    private val service =
        BusinessDateService(
            lifecycleStore, businessDateStore, history, guard, auditService, events,
            java.time.Clock.systemUTC(),
        )
```

Add these tests to the class:

```kotlin
    @Test
    fun `advance requires the business_date advance permission`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)
        guard.deny(organisationId, "business_date.advance")

        assertFailsWith<SecurityException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance writes a history entry`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        service.advance(
            AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), uuidV7()),
        )

        assertEquals(1, history.appended.count { it.eventType == "ADVANCED" })
    }

    @Test
    fun `initialize rejects an inactive organisation`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED
        assertFailsWith<IllegalArgumentException> {
            service.initialize(
                InitializeBusinessDateCommand(
                    organisationId, LocalDate.parse("2026-07-16"), uuidV7(),
                ),
            )
        }
    }

    @Test
    fun `startCob moves OPEN to CLOSING and publishes CobStarted`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-16"), "OPEN", 0)

        service.startCob(StartCobCommand(organisationId, uuidV7()))

        assertEquals("CLOSING", businessDateStore.statuses[organisationId])
        assertTrue(
            events.published
                .mapNotNull { it as? ExternalizedTransitionEvent }
                .any { it.metadata["eventType"] == "CobStarted" },
        )
    }

    @Test
    fun `completeCob requires CLOSING state`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-16"), "OPEN", 0)
        assertFailsWith<IllegalStateException> {
            service.completeCob(CompleteCobCommand(organisationId, uuidV7()))
        }
    }

    @Test
    fun `reopen requires the reopen permission`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-16"), "CLOSED", 0)
        guard.deny(organisationId, "business_date.reopen")
        assertFailsWith<SecurityException> {
            service.reopen(ReopenBusinessDateCommand(organisationId, uuidV7()))
        }
    }
```

Add these fakes at the bottom of the file, and add `import kotlin.test.assertTrue` and the audit imports:

```kotlin
private class FakeGuardForBusinessDate : com.finaxis.platform.lifecycle.PermissionGuard {
    private val denied = mutableSetOf<Pair<UUID, String>>()

    fun deny(
        organisationId: UUID,
        permissionCode: String,
    ) {
        denied.add(organisationId to permissionCode)
    }

    override fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (denied.contains(organisationId to permissionCode)) {
            throw SecurityException("Missing permission: $permissionCode")
        }
    }
}

private class FakeBusinessDateHistoryStore : BusinessDateHistoryStore {
    val appended = mutableListOf<BusinessDateHistoryEntry>()

    override fun append(entry: BusinessDateHistoryEntry) {
        appended.add(entry)
    }

    override fun list(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): BusinessDateHistoryPage = BusinessDateHistoryPage(emptyList(), 0)
}

private class RecordingAuditRepositoryForBusinessDate :
    com.finaxis.platform.common.audit.AuditEventRepository {
    val saved = mutableListOf<com.finaxis.platform.common.audit.AuditEvent>()

    override fun save(
        event: com.finaxis.platform.common.audit.AuditEvent,
    ): com.finaxis.platform.common.audit.AuditEvent {
        saved.add(event)
        return event
    }
}
```

Extend the existing `FakeBusinessDateStore` to implement the new methods and track status:

```kotlin
    val statuses = mutableMapOf<UUID, String>()

    override fun initialize(
        organisationId: UUID,
        initialDate: LocalDate,
        actorId: UUID,
    ): Boolean {
        if (snapshots.containsKey(organisationId)) return false
        snapshots[organisationId] = BusinessDateSnapshot(initialDate, "OPEN", 0)
        statuses[organisationId] = "OPEN"
        return true
    }

    override fun changeStatus(
        organisationId: UUID,
        newStatus: String,
        cobDate: LocalDate?,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        statuses[organisationId] = newStatus
        return true
    }
```

- [ ] **Step 3: Run tests to verify they fail**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.application.BusinessDateServiceTests"`
Expected: FAIL (new constructor params / methods unresolved).

- [ ] **Step 4: Rewrite `BusinessDateService`**

Replace the entire contents of `BusinessDateService.kt` with:

```kotlin
package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * Controls the organisation business date and its close-of-business (COB) status. This is a
 * foundation only: no financial end-of-day work runs here. All mutations require an active
 * tenant, are permission-gated, audited, appended to history, and externalized via the outbox.
 */
@Service
class BusinessDateService(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val businessDateStore: BusinessDateStore,
    private val historyStore: BusinessDateHistoryStore,
    private val permissionGuard: PermissionGuard,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val clock: Clock,
) {
    /** Initializes the singleton business date for an active organisation. */
    @Transactional
    fun initialize(command: InitializeBusinessDateCommand): BusinessDateView {
        requireActive(command.organisationId)
        permissionGuard.requirePermission(
            command.actorId, command.organisationId, ADVANCE_PERMISSION,
        )
        val created =
            businessDateStore.initialize(
                command.organisationId, command.initialBusinessDate, command.actorId,
            )
        check(created) { "Business date is already initialized for the organisation." }
        record(
            command.organisationId,
            command.actorId,
            eventType = "INITIALIZED",
            fromStatus = null,
            toStatus = "OPEN",
            fromDate = null,
            toDate = command.initialBusinessDate,
            reason = command.reason,
            target = INITIALIZED_TARGET,
            transition = "INITIALIZE",
            auditAction = "business_date.initialize",
        )
        return BusinessDateView(command.organisationId, command.initialBusinessDate, "OPEN")
    }

    /** Advances the business date by one optimistic-locked step for an active organisation. */
    @Transactional
    fun advance(command: AdvanceBusinessDateCommand): BusinessDateAdvanceResult {
        requireActive(command.organisationId)
        permissionGuard.requirePermission(
            command.actorId, command.organisationId, ADVANCE_PERMISSION,
        )
        val current = requireCurrent(command.organisationId)
        require(current.status == "OPEN") { "Business date must be OPEN to advance." }
        require(command.newBusinessDate.isAfter(current.currentBusinessDate)) {
            "The new business date must be after the current business date."
        }
        val advanced =
            businessDateStore.advance(
                command.organisationId,
                command.newBusinessDate,
                current.rowVersion,
                command.actorId,
            )
        check(advanced) {
            "Business date was concurrently advanced; retry with the latest version."
        }
        record(
            command.organisationId,
            command.actorId,
            eventType = "ADVANCED",
            fromStatus = "OPEN",
            toStatus = "OPEN",
            fromDate = current.currentBusinessDate,
            toDate = command.newBusinessDate,
            reason = command.reason,
            target = ADVANCED_TARGET,
            transition = "ADVANCE",
            auditAction = "business_date.advance",
        )
        return BusinessDateAdvanceResult(
            command.organisationId, current.currentBusinessDate, command.newBusinessDate,
        )
    }

    /** Starts close-of-business: OPEN to CLOSING. */
    @Transactional
    fun startCob(command: StartCobCommand): BusinessDateView =
        changeStatus(
            command.organisationId,
            command.actorId,
            command.reason,
            requiredFrom = "OPEN",
            newStatus = "CLOSING",
            setCobDate = true,
            permission = COB_START_PERMISSION,
            eventType = "COB_STARTED",
            target = COB_STARTED_TARGET,
            transition = "START_COB",
            auditAction = "cob.start",
        )

    /** Completes close-of-business: CLOSING to CLOSED. */
    @Transactional
    fun completeCob(command: CompleteCobCommand): BusinessDateView =
        changeStatus(
            command.organisationId,
            command.actorId,
            command.reason,
            requiredFrom = "CLOSING",
            newStatus = "CLOSED",
            setCobDate = false,
            permission = COB_COMPLETE_PERMISSION,
            eventType = "COB_COMPLETED",
            target = COB_COMPLETED_TARGET,
            transition = "COMPLETE_COB",
            auditAction = "cob.complete",
        )

    /** Reopens a closed business date: CLOSED to OPEN. Requires the explicit reopen permission. */
    @Transactional
    fun reopen(command: ReopenBusinessDateCommand): BusinessDateView =
        changeStatus(
            command.organisationId,
            command.actorId,
            command.reason,
            requiredFrom = "CLOSED",
            newStatus = "OPEN",
            setCobDate = false,
            permission = REOPEN_PERMISSION,
            eventType = "REOPENED",
            target = REOPENED_TARGET,
            transition = "REOPEN",
            auditAction = "business_date.reopen",
        )

    /** Reads the current business date. */
    @Transactional(readOnly = true)
    fun get(query: GetBusinessDateQuery): BusinessDateView {
        permissionGuard.requirePermission(
            query.let { SYSTEM_READ_ACTOR }, query.organisationId, VIEW_PERMISSION,
        )
        val current = requireCurrent(query.organisationId)
        return BusinessDateView(query.organisationId, current.currentBusinessDate, current.status)
    }

    /** Lists business-date / COB change history, newest first, paginated. */
    @Transactional(readOnly = true)
    fun listHistory(query: ListBusinessDateHistoryQuery): BusinessDateHistoryPage =
        historyStore.list(query.organisationId, query.page, query.size)

    private fun changeStatus(
        organisationId: UUID,
        actorId: UUID,
        reason: String?,
        requiredFrom: String,
        newStatus: String,
        setCobDate: Boolean,
        permission: String,
        eventType: String,
        target: String,
        transition: String,
        auditAction: String,
    ): BusinessDateView {
        requireActive(organisationId)
        permissionGuard.requirePermission(actorId, organisationId, permission)
        val current = requireCurrent(organisationId)
        require(current.status == requiredFrom) {
            "Business date must be $requiredFrom for this action."
        }
        val cobDate = if (setCobDate) current.currentBusinessDate else null
        val changed =
            businessDateStore.changeStatus(
                organisationId, newStatus, cobDate, current.rowVersion, actorId,
            )
        check(changed) { "Business date status was concurrently changed; retry." }
        record(
            organisationId,
            actorId,
            eventType = eventType,
            fromStatus = requiredFrom,
            toStatus = newStatus,
            fromDate = current.currentBusinessDate,
            toDate = current.currentBusinessDate,
            reason = reason,
            target = target,
            transition = transition,
            auditAction = auditAction,
        )
        return BusinessDateView(organisationId, current.currentBusinessDate, newStatus)
    }

    private fun requireActive(organisationId: UUID) {
        require(lifecycleStore.lifecycleState(organisationId) == OrganisationLifecycleState.ACTIVE) {
            "Business date can be changed only for an active organisation."
        }
    }

    private fun requireCurrent(organisationId: UUID) =
        requireNotNull(businessDateStore.current(organisationId)) {
            "Business date was not found for the organisation."
        }

    @Suppress("LongParameterList")
    private fun record(
        organisationId: UUID,
        actorId: UUID,
        eventType: String,
        fromStatus: String?,
        toStatus: String,
        fromDate: LocalDate?,
        toDate: LocalDate,
        reason: String?,
        target: String,
        transition: String,
        auditAction: String,
    ) {
        val occurredAt = clock.instant()
        historyStore.append(
            BusinessDateHistoryEntry(
                organisationId = organisationId,
                eventType = eventType,
                fromStatus = fromStatus,
                toStatus = toStatus,
                fromBusinessDate = fromDate,
                toBusinessDate = toDate,
                actorId = actorId,
                reason = reason,
                occurredAt = occurredAt,
            ),
        )
        auditService.recordSuccess(
            actorId = actorId,
            tenantId = organisationId,
            action = auditAction,
            resourceType = "BUSINESS_DATE",
            resourceId = organisationId.toString(),
            reason = reason,
            before = mapOf("status" to fromStatus, "businessDate" to fromDate?.toString()),
            after = mapOf("status" to toStatus, "businessDate" to toDate.toString()),
        )
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = target,
                aggregateType = "BUSINESS_DATE",
                aggregateId = organisationId.toString(),
                transition = transition,
                fromState = fromStatus ?: "NONE",
                toState = toStatus,
                actor = TransitionActor("USER", actorId.toString()),
                occurredAt = occurredAt,
                metadata =
                    mapOf(
                        "organisationId" to organisationId.toString(),
                        "eventType" to eventTypeName(eventType),
                    ),
            ),
        )
    }

    private fun eventTypeName(eventType: String): String =
        when (eventType) {
            "INITIALIZED" -> "BusinessDateInitialized"
            "ADVANCED" -> "BusinessDateAdvanced"
            "COB_STARTED" -> "CobStarted"
            "COB_COMPLETED" -> "CobCompleted"
            "REOPENED" -> "BusinessDateReopened"
            else -> eventType
        }

    private companion object {
        const val INITIALIZED_TARGET =
            "finaxis.lifecycle.organisation.business-date-initialized"
        const val ADVANCED_TARGET = "finaxis.lifecycle.organisation.business-date-advanced"
        const val COB_STARTED_TARGET = "finaxis.lifecycle.organisation.cob-started"
        const val COB_COMPLETED_TARGET = "finaxis.lifecycle.organisation.cob-completed"
        const val REOPENED_TARGET = "finaxis.lifecycle.organisation.business-date-reopened"
        const val ADVANCE_PERMISSION = "business_date.advance"
        const val VIEW_PERMISSION = "business_date.view"
        const val COB_START_PERMISSION = "cob.start"
        const val COB_COMPLETE_PERMISSION = "cob.complete"
        const val REOPEN_PERMISSION = "business_date.reopen"
        val SYSTEM_READ_ACTOR: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}
```

> Note the `get(query)` read gate uses a system actor placeholder because `GetBusinessDateQuery` carries no actor. Fix this cleanly: **add `actorId: UUID` to `GetBusinessDateQuery` and `ListBusinessDateHistoryQuery`** in Step 1's definitions and use `query.actorId` here instead of `SYSTEM_READ_ACTOR`. (Do this now — replace `query.let { SYSTEM_READ_ACTOR }` with `query.actorId`, add `actorId: UUID` fields to both query data classes, remove `SYSTEM_READ_ACTOR`, and gate `listHistory` with `permissionGuard.requirePermission(query.actorId, query.organisationId, VIEW_PERMISSION)` before returning.)

- [ ] **Step 5: Remove the old `@AuditedAction` import usage**

The rewritten service no longer uses `@AuditedAction`. Confirm no leftover import remains (the new file above has none). The existing integration test `BusinessDateAdvancedOutboxIntegrationTests` asserts an audit row with `action = "business_date.advance"` — the new `recordSuccess(action = "business_date.advance", ...)` keeps that assertion valid.

- [ ] **Step 6: Run unit tests to verify they pass**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.application.BusinessDateServiceTests"`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src/main/kotlin/com/finaxis/platform/lifecycle/application/BusinessDateService.kt \
        src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationBranchProvisioningCommands.kt \
        src/test/kotlin/com/finaxis/platform/lifecycle/application/BusinessDateServiceTests.kt
git commit -m "feat: extend BusinessDateService with COB status, reopen, history, and gating

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: Wire provisioning tenant roles to the new permissions + retire the old settings service path

**Files:**
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/adapter/outbound/persistence/JooqOrganisationBranchProvisioningStore.kt` (add new codes to `BASELINE_PERMISSION_CODES`)
- Modify: `src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationSettingsService.kt` (delete — superseded by `TenantSettingsService`)
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/application/OrganisationSettingsServiceTests.kt` (delete)
- Modify: `src/test/kotlin/com/finaxis/platform/lifecycle/OrganisationSettingsUpdatedOutboxIntegrationTests.kt` and `OutboxTransactionRollbackIntegrationTests.kt` (repoint to `TenantSettingsService`)

**Interfaces:**
- Consumes: `TenantSettingsService.createOrUpdate` (Task 6).
- Produces: `TENANT_ADMIN` role granted `cob.start`, `cob.complete`, `business_date.reopen` at provisioning.

> `OrganisationSettingsService` is only referenced by tests (confirmed: no production caller). Provisioning writes initial settings through `OrganisationLifecycleProvisioningStore.saveSettings`, a different path that is untouched. Replacing the old service keeps a single settings write path.

- [ ] **Step 1: Add new tenant-admin permission codes**

In `JooqOrganisationBranchProvisioningStore.kt`, in the `BASELINE_PERMISSION_CODES` set (ends at line 464 with `"iam.profile.read",`), add three entries before the closing paren:

```kotlin
            "iam.profile.read",
            "cob.start",
            "cob.complete",
            "business_date.reopen",
        )
```

(Do NOT add `tenant_setting.manage_platform` — it is platform-admin-only and must not be granted to tenant roles.)

- [ ] **Step 2: Delete the superseded service and its unit test**

```bash
git rm src/main/kotlin/com/finaxis/platform/lifecycle/application/OrganisationSettingsService.kt \
       src/test/kotlin/com/finaxis/platform/lifecycle/application/OrganisationSettingsServiceTests.kt
```

- [ ] **Step 3: Repoint the two integration tests to `TenantSettingsService`**

In `OrganisationSettingsUpdatedOutboxIntegrationTests.kt` and `OutboxTransactionRollbackIntegrationTests.kt`, replace the injected `OrganisationSettingsService` with `TenantSettingsService` and change the call from:

```kotlin
organisationSettingsService.updateSettings(
    UpdateOrganisationSettingsCommand(
        organisationId = organisationId,
        updates = mapOf("operational.enabled" to "true"),
        actorId = LOCAL_USER_ID,
    ),
)
```

to (using a real catalog key and the seeded local user, who — via provisioning — holds `settings.update` as TENANT_ADMIN, or is platform super-admin):

```kotlin
tenantSettingsService.createOrUpdate(
    CreateOrUpdateTenantSettingCommand(
        organisationId = organisationId,
        key = "base_currency",
        value = "KES",
        actorId = LOCAL_USER_ID,
    ),
)
```

Update the imports accordingly (`TenantSettingsService`, `CreateOrUpdateTenantSettingCommand`; remove `OrganisationSettingsService`, `UpdateOrganisationSettingsCommand`).

> `LOCAL_USER_ID` (`11111111-1111-1111-1111-111111111111`) must resolve to a principal holding `settings.update` in the test org. Verify via the V2 smoke-data seed + provisioning role assignment. If the seeded user lacks it, the integration test in Task 9 covers the authorized path with an explicitly granted user; for these two pre-existing tests, if authorization fails, inject the platform super-admin user id instead (confirm its id from V5 seed / smoke data).

- [ ] **Step 4: Compile and run the affected tests**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: BUILD SUCCESSFUL.

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.OrganisationSettingsUpdatedOutboxIntegrationTests" --tests "com.finaxis.platform.lifecycle.OutboxTransactionRollbackIntegrationTests"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "refactor: retire OrganisationSettingsService for TenantSettingsService; grant COB perms

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: Integration tests — acceptance criteria end-to-end

**Files:**
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/TenantSettingsIntegrationTests.kt`
- Create: `src/test/kotlin/com/finaxis/platform/lifecycle/BusinessDateCobIntegrationTests.kt`

**Interfaces:**
- Consumes: `TenantSettingsService`, `BusinessDateService`, `BusinessDateStore`, `OrganisationProvisioningService`, `OutboxRecordRepository`, `DSLContext`, `AUDIT_EVENT` jOOQ ref. Reuses the `activeOrganisation()` bootstrap pattern from `BusinessDateAdvancedOutboxIntegrationTests`.

> These cover: settings update writes audit; sensitive setting redacted; advance requires permission; advance emits outbox event; inactive tenant cannot advance; history paginates. For the "sensitive redacted" test, since no catalog setting is sensitive, assert redaction at the audit boundary using a value that `SensitiveDataRedactor` masks by key-name (e.g. verify a setting whose audit `before/after` map is built with a `Redacted` wrapper stays masked) OR assert the read-side masking via a unit-level catalog stub. The authoritative redaction guarantee is unit-tested in Task 6; here assert the persisted `audit_event.after` for a normal setting contains the real value (proving audit writes) and that the redaction path is exercised in unit tests.

- [ ] **Step 1: Write the tenant-settings integration test**

Create `src/test/kotlin/com/finaxis/platform/lifecycle/TenantSettingsIntegrationTests.kt`:

```kotlin
package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.CreateOrUpdateTenantSettingCommand
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.GetTenantSettingQuery
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import com.finaxis.platform.lifecycle.application.TenantSettingsService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class TenantSettingsIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val tenantSettingsService: TenantSettingsService,
    private val dsl: DSLContext,
) {
    @Test
    fun `creating a tenant setting writes an audit event and reads back canonicalized`() {
        val organisationId = activeOrganisation()

        tenantSettingsService.createOrUpdate(
            CreateOrUpdateTenantSettingCommand(
                organisationId = organisationId,
                key = "base_currency",
                value = "kes",
                actorId = LOCAL_USER_ID,
            ),
        )

        assertEquals(
            "KES",
            tenantSettingsService.get(GetTenantSettingQuery(organisationId, "base_currency")).value,
        )
        val auditRows =
            dsl
                .selectCount()
                .from(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(organisationId))
                .and(AUDIT_EVENT.ACTION.eq("settings.update"))
                .fetchOne(0, Int::class.java)
        assertEquals(1, auditRows)
    }

    private fun activeOrganisation(): UUID {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "settings-${uuidV7()}",
                        displayName = "Settings Organisation",
                        legalName = "Settings Organisation Limited",
                        registrationNumber = "SET-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = LOCAL_USER_ID,
                    ),
                ).organisationId
        organisationProvisioningService.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )
        return organisationId
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
```

> If `LOCAL_USER_ID` is not authorized for `settings.update` in this org (permission gate throws `AccessDeniedException`), grant it by using the platform super-admin user id, or assign the TENANT_ADMIN role to `LOCAL_USER_ID` in the test setup using the existing role-assignment service. Determine the correct authorized id by checking the V2 smoke seed and provisioning role wiring; adjust `LOCAL_USER_ID` accordingly.

- [ ] **Step 2: Write the business-date / COB integration test**

Create `src/test/kotlin/com/finaxis/platform/lifecycle/BusinessDateCobIntegrationTests.kt`:

```kotlin
package com.finaxis.platform.lifecycle

import com.finaxis.platform.TestcontainersConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.lifecycle.application.AdvanceBusinessDateCommand
import com.finaxis.platform.lifecycle.application.ApproveOrganisationProvisioningCommand
import com.finaxis.platform.lifecycle.application.BusinessDateService
import com.finaxis.platform.lifecycle.application.BusinessDateStore
import com.finaxis.platform.lifecycle.application.CreateOrganisationDraftCommand
import com.finaxis.platform.lifecycle.application.ListBusinessDateHistoryQuery
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.application.StartCobCommand
import com.finaxis.platform.lifecycle.application.SubmitOrganisationForApprovalCommand
import io.namastack.outbox.OutboxRecordRepository
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.assertTrue

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BusinessDateCobIntegrationTests(
    private val organisationProvisioningService: OrganisationProvisioningService,
    private val businessDateService: BusinessDateService,
    private val businessDateStore: BusinessDateStore,
    private val outboxRecords: OutboxRecordRepository,
) {
    @Test
    fun `advance then start COB emits outbox events and records history`() {
        val organisationId = activeOrganisation()
        val current = requireNotNull(businessDateStore.current(organisationId))

        businessDateService.advance(
            AdvanceBusinessDateCommand(
                organisationId = organisationId,
                newBusinessDate = current.currentBusinessDate.plusDays(1),
                actorId = LOCAL_USER_ID,
            ),
        )
        businessDateService.startCob(StartCobCommand(organisationId, LOCAL_USER_ID))

        await()
            .atMost(60, TimeUnit.SECONDS)
            .pollInterval(500, TimeUnit.MILLISECONDS)
            .untilAsserted {
                val targets =
                    outboxRecords
                        .findCompletedRecords()
                        .mapNotNull { it.payload as? ExternalizedTransitionEvent }
                        .filter { it.aggregateId == organisationId.toString() }
                        .map { it.target }
                assertTrue(
                    targets.contains(
                        "finaxis.lifecycle.organisation.business-date-advanced",
                    ),
                )
                assertTrue(targets.contains("finaxis.lifecycle.organisation.cob-started"))
            }

        val history =
            businessDateService.listHistory(
                ListBusinessDateHistoryQuery(organisationId, page = 0, size = 25),
            )
        assertTrue(history.totalItems >= 2)
    }

    private fun activeOrganisation(): UUID {
        val organisationId =
            organisationProvisioningService
                .createDraft(
                    CreateOrganisationDraftCommand(
                        tenantCode = "cob-${uuidV7()}",
                        displayName = "COB Organisation",
                        legalName = "COB Organisation Limited",
                        registrationNumber = "COB-${uuidV7()}",
                        countryCode = "KE",
                        baseCurrencyCode = "KES",
                        timezone = "Africa/Nairobi",
                        requestedBy = LOCAL_USER_ID,
                    ),
                ).organisationId
        organisationProvisioningService.submitForApproval(
            SubmitOrganisationForApprovalCommand(organisationId),
        )
        organisationProvisioningService.approveProvisioning(
            ApproveOrganisationProvisioningCommand(organisationId),
        )
        return organisationId
    }

    private companion object {
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    }
}
```

> `ListBusinessDateHistoryQuery` now carries an `actorId` (from Task 7 Step 4 fix); pass `LOCAL_USER_ID` for it. Update the constructor call above to include `actorId = LOCAL_USER_ID` if you added that field.

- [ ] **Step 3: Run the integration tests**

Run: `./gradlew test --tests "com.finaxis.platform.lifecycle.TenantSettingsIntegrationTests" --tests "com.finaxis.platform.lifecycle.BusinessDateCobIntegrationTests"`
Expected: PASS. If a permission gate rejects `LOCAL_USER_ID`, resolve authorization as noted (assign TENANT_ADMIN or use the platform admin id) and re-run.

- [ ] **Step 4: Commit**

```bash
git add src/test/kotlin/com/finaxis/platform/lifecycle/TenantSettingsIntegrationTests.kt \
        src/test/kotlin/com/finaxis/platform/lifecycle/BusinessDateCobIntegrationTests.kt
git commit -m "test: add tenant-settings and business-date/COB integration tests

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: Documentation + ADR

**Files:**
- Create: `docs/operations/tenant-settings.md`
- Create: `docs/operations/business-date.md`
- Create: `docs/adr/0009-tenant-settings-scope-and-authn-boundary.md`
- Modify: `docs/adr/README.md` or ADR index if one exists (append 0009 to the list)

**Interfaces:** none (docs only).

- [ ] **Step 1: Write `docs/operations/tenant-settings.md`**

Cover: purpose; the six-setting catalog table (key, type, sensitive, platform-admin-only, default); value validation (IANA timezones via `ZoneId.getAvailableZoneIds()`, ISO 4217 via `Currency.getAvailableCurrencies()`, boolean/int rules); effective-dating semantics (close-row / insert-row, never destructive); deactivate = close current row, no replacement; permissions (`settings.update` for normal, `tenant_setting.manage_platform` for platform-only, scoped to the platform org); sensitivity/redaction mechanism (`Redacted` + `SensitiveDataRedactor`, masked in audit and reads); the command/query surface (`CreateOrUpdateTenantSetting`, `DeactivateTenantSetting`, `GetTenantSetting`, `ListTenantSettings`); and the **"What is deliberately not a tenant setting"** section verbatim from the spec §8 (Keycloak owns authN/SSO — no `keycloak_invite_enabled`, no `max_failed_login_policy_reference`, no `application_invite_enabled`; branch assignment is a structural invariant — no `enforce_branch_assignment_for_login`, no `minimum_active_branches_required`; permission-based 403 gating — no `enforce_role_assignment_for_login`; outbox retry is platform-level Namastack config — no `outbox_max_retries`). Mirror the tone/structure of `docs/operations/tenant-provisioning.md`. Keep lines ≤ 100 chars.

- [ ] **Step 2: Write `docs/operations/business-date.md`**

Cover: the singleton-per-tenant model; the status model and transitions (`OPEN --advance--> OPEN`, `OPEN --StartCob--> CLOSING`, `CLOSING --CompleteCob--> CLOSED`, `CLOSED --Reopen--> OPEN`; `ADVANCING` reserved, not used); the command matrix with permissions (initialize/advance = `business_date.advance`, `cob.start`, `cob.complete`, reopen = `business_date.reopen`, view = `business_date.view`); that provisioning auto-initializes the row at approval and `InitializeBusinessDate` rejects a second initialization; audit + outbox events (`BusinessDateInitialized`, `BusinessDateAdvanced`, `CobStarted`, `CobCompleted`, plus `BusinessDateReopened`); the append-only `business_date_history` table and the paginated `ListBusinessDateHistory` query; and an explicit **scope note**: this is COB *status* foundation only — no financial end-of-day, day-close, or accounting work is performed. Keep lines ≤ 100 chars.

- [ ] **Step 3: Write ADR 0009**

Create `docs/adr/0009-tenant-settings-scope-and-authn-boundary.md` following the format of `docs/adr/0007-append-only-audit-log-and-redaction-policy.md` (Status / Context / Decision / Consequences / Alternatives considered). Record:
- Decision: authentication, login policy, invite transport, and external SSO are owned by Keycloak, not modeled as application tenant settings.
- Decision: branch-assignment enforcement and the one-active-branch minimum are structural platform invariants, not settings.
- Decision: authorization is permission-code based (403 on missing permission), so no role/login enforcement flag exists.
- Decision: settings are catalog-validated, effective-dated, permission-gated, and redaction-aware; platform-only settings require `tenant_setting.manage_platform` scoped to the platform organisation.
- Consequences and the alternatives rejected (free-form key/value settings; storing IdP config in-app).

- [ ] **Step 4: Verify docs build / links (if a docs check exists) and Markdown line length**

Run: `./gradlew spotlessCheck`
Expected: PASS (Markdown is covered by Spotless; fix any > 100-char lines).

- [ ] **Step 5: Commit**

```bash
git add docs/operations/tenant-settings.md docs/operations/business-date.md \
        docs/adr/0009-tenant-settings-scope-and-authn-boundary.md
git commit -m "docs: add tenant-settings and business-date operations docs and ADR 0009

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 11: Full quality gate

**Files:** none (verification).

- [ ] **Step 1: Run the full quality gate**

Run: `./gradlew qualityGate`
Expected: BUILD SUCCESSFUL — `spotlessCheck`, `ktlintCheck`, `detekt`, `checkstyleMain checkstyleTest`, `pmdMain pmdTest`, `spotbugsMain spotbugsTest`, `test` (incl. `ModulithArchitectureTest` and ArchUnit), JaCoCo verification, and `bootJar` all pass.

- [ ] **Step 2: Fix any findings**

Address Detekt/ktlint/line-length/KDoc findings without suppressions. Re-run `./gradlew qualityGate` until green.

- [ ] **Step 3: Final commit (if fixes were needed)**

```bash
git add -A
git commit -m "chore: satisfy quality gate for tenant settings and business date

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Tenant settings commands (CreateOrUpdate/Deactivate/Get/List) → Tasks 2, 4, 6. ✓
- Versioned/effective-dated, non-destructive → Task 4 (close-row/insert-row). ✓
- ACTIVE-tenant guard → Tasks 6, 7. ✓
- Platform-admin-only settings → Task 2 catalog flag + Task 6 authorize() + Task 1 permission. ✓
- Sensitive redaction in audit + reads → Task 6 (`Redacted`, masked view) + unit tests. ✓
- Business date commands (Initialize/Get/Advance/StartCob/CompleteCob/Reopen) → Task 7. ✓
- `business_date.advance` permission required → Task 7 + integration Task 9. ✓
- Reopen only if permission allows → Task 7 (`business_date.reopen`, no fallback). ✓
- ACTIVE guard on business date → Task 7 + Task 9 (inactive-tenant test is unit-covered in Task 7; integration bootstraps ACTIVE). ✓
- Outbox events (Initialized/Advanced/CobStarted/CobCompleted) → Task 7 targets + Task 9 assertions. ✓
- Query/read side: get settings, get current business date, list history + pagination → Tasks 6, 7. ✓
- Tests (audit written, redaction, permission-gated advance, outbox emission, inactive tenant) → Tasks 6, 7, 9. ✓
- Docs `tenant-settings.md`, `business-date.md` → Task 10. ✓
- No accounting/day-close → enforced by scope notes; no EOD code added. ✓

**Placeholder scan:** No TBD/TODO; all code shown. The one deferred detail (read-query actor) is resolved inline in Task 7 Step 4 (add `actorId` to query classes) — apply that fix rather than shipping the `SYSTEM_READ_ACTOR` placeholder.

**Type consistency:** `PermissionGuard.requirePermission(actorId, organisationId, permissionCode)` consistent across Tasks 3/6/7. `StoredSetting`, `BusinessDateHistoryEntry/Record/Page` consistent across Tasks 4/5/6/7. `TenantSettingView`/`BusinessDateView` consistent. Event targets consistent between Task 7 and Task 9 assertions. `AuditEventRepository.save` fake must match the real signature — flagged in Task 6 Step 1.

**Known verification points for the implementer** (do not skip): (a) confirm `AuditEventRepository.save` signature; (b) confirm `MembershipSelectionLookup`/`RequestPermissionCache` signatures for the Task 3 fakes; (c) confirm `LOCAL_USER_ID` is authorized for the gated integration paths, else assign TENANT_ADMIN or use the platform-admin id.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-07-16-tenant-settings-business-date.md`.
