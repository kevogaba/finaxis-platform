package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.accounting.AccountingLedgerActivity
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TenantSettingsServiceTests {
    private val lifecycleStore = FakeLifecycleStoreForSettings()
    private val settingsStore = FakeSettingsStore()
    private val guard = FakePermissionGuard()
    private val events = CapturingPublisherForSettings()
    private val auditEvents = RecordingAuditRepository()
    private val clock = Clock.fixed(Instant.parse("2026-07-17T10:15:30Z"), ZoneOffset.UTC)
    private val auditService = AuditService(auditEvents, clock)
    private val ledger = FakeLedgerActivity()
    private val service =
        TenantSettingsService(
            lifecycleStore,
            settingsStore,
            guard,
            auditService,
            events,
            clock,
            ledger,
        )

    private val organisationId = uuidV7()
    private val actorId = uuidV7()

    private fun activate() {
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
    }

    @Test
    fun `the base currency is frozen once the ledger reports a posted journal`() {
        // The functional-currency freeze the accounting foundation requires: journal lines are
        // immutable, so a later line in another unit would make every balance a sum of
        // incompatible units. Lifecycle asks accounting and refuses; every other key is untouched.
        activate()
        ledger.posted = true

        val failure =
            assertFailsWith<ConflictException> {
                service.createOrUpdate(
                    CreateOrUpdateTenantSettingCommand(
                        organisationId,
                        "base_currency",
                        "USD",
                        actorId,
                    ),
                )
            }
        assertEquals("accounting.functional_currency_frozen", failure.code)

        service.createOrUpdate(
            CreateOrUpdateTenantSettingCommand(organisationId, "default_timezone", "UTC", actorId),
        )
    }

    @Test
    fun `createOrUpdate rejects an inactive organisation`() {
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED
        assertFailsWith<ConflictException> {
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId,
                    "base_currency",
                    "KES",
                    actorId,
                ),
            )
        }
    }

    @Test
    fun `createOrUpdate rejects an unknown key`() {
        activate()
        assertFailsWith<InvalidOperationException> {
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
                    organisationId,
                    "audit_retention_days",
                    "30",
                    actorId,
                ),
            )
        }
    }

    @Test
    fun `get redacts a sensitive setting value`() {
        activate()
        settingsStore.put(organisationId, StoredSetting("some_secret", "hunter2", "STRING", true))
        val view = service.get(GetTenantSettingQuery(organisationId, "some_secret", actorId))
        assertEquals("***REDACTED***", view.value)
    }

    @Test
    fun `get returns null value for an unset catalog key`() {
        activate()
        assertNull(
            service.get(GetTenantSettingQuery(organisationId, "base_currency", actorId)).value,
        )
    }

    @Test
    fun `get requires settings-view permission`() {
        activate()
        guard.deny(organisationId, "settings.view")

        assertFailsWith<SecurityException> {
            service.get(GetTenantSettingQuery(organisationId, "base_currency", actorId))
        }
    }

    @Test
    fun `list requires settings-view permission`() {
        activate()
        guard.deny(organisationId, "settings.view")

        assertFailsWith<SecurityException> {
            service.list(ListTenantSettingsQuery(organisationId, actorId))
        }
    }

    @Test
    fun `list masks platform-only values without platform permission`() {
        activate()
        guard.deny(PLATFORM_ORG_ID, "tenant_setting.manage_platform")
        settingsStore.put(
            organisationId,
            StoredSetting("audit_retention_days", "30", "INT", false),
        )

        val page = service.list(ListTenantSettingsQuery(organisationId, actorId))

        assertEquals("***REDACTED***", page.items.single { it.key == "audit_retention_days" }.value)
    }

    @Test
    fun `list returns empty page for extreme page offset`() {
        activate()

        val page =
            service.list(
                ListTenantSettingsQuery(
                    organisationId,
                    actorId,
                    page = Int.MAX_VALUE,
                    size = 100,
                ),
            )

        assertEquals(emptyList(), page.items)
        assertTrue(page.totalItems > 0)
    }

    @Test
    fun `get returns a catalog default when no stored row exists`() {
        activate()

        val view =
            service.get(
                GetTenantSettingQuery(
                    organisationId,
                    "business_date_auto_advance_enabled",
                    actorId,
                ),
            )

        assertEquals("false", view.value)
    }

    @Test
    fun `deactivate closes the setting`() {
        activate()
        settingsStore.put(organisationId, StoredSetting("base_currency", "KES", "CURRENCY", false))
        service.deactivate(DeactivateTenantSettingCommand(organisationId, "base_currency", actorId))
        assertNull(settingsStore.stored(organisationId, "base_currency"))
    }

    @Test
    fun `deactivate is a no-op when no setting is currently active`() {
        activate()

        service.deactivate(DeactivateTenantSettingCommand(organisationId, "base_currency", actorId))

        assertEquals(0, auditEvents.saved.size)
        assertEquals(0, events.published.size)
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
    ): StoredSetting? {
        val before = data[organisationId to key]
        data[organisationId to key] = StoredSetting(key, value, valueType, sensitive)
        return before
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

    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    ) {
        val platformOrgId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        requirePermission(actorId, platformOrgId, permissionCode)
    }
}

private class CapturingPublisherForSettings : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}

private class FakeLedgerActivity : AccountingLedgerActivity {
    var posted = false

    override fun hasPostedJournals(organisationId: java.util.UUID) = posted
}

private class RecordingAuditRepository : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved.add(event)
    }
}
