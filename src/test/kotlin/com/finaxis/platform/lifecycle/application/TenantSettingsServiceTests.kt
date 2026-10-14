package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.BranchVisibility
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import com.finaxis.platform.lifecycle.domain.TenantSettingCatalog
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
    private val organisations = FakeBootstrapStore()
    private val service =
        TenantSettingsService(
            lifecycleStore,
            settingsStore,
            organisations,
            guard,
            auditService,
            events,
            clock,
        )

    private val organisationId = uuidV7()
    private val actorId = uuidV7()

    private fun activate() {
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
    }

    @Test
    fun `base_currency is a read-only mirror, so createOrUpdate refuses it before any write`() {
        // The organisation's base_currency_code is the column the ledger reads. A setting row
        // could only ever disagree with it, so the write is refused outright, with nothing stored,
        // audited or published.
        activate()
        organisations.currency[organisationId] = "KES"

        val failure =
            assertFailsWith<InvalidOperationException> {
                service.createOrUpdate(
                    CreateOrUpdateTenantSettingCommand(
                        organisationId,
                        "base_currency",
                        "USD",
                        actorId,
                    ),
                )
            }

        assertEquals(BASE_CURRENCY_MIRROR_DETAIL, failure.safeDetail)
        assertNull(settingsStore.stored(organisationId, "base_currency"))
        assertEquals(0, auditEvents.saved.size)
        assertEquals(0, events.published.size)
    }

    @Test
    fun `base_currency is a read-only mirror, so deactivate refuses it and closes nothing`() {
        activate()
        organisations.currency[organisationId] = "KES"
        settingsStore.put(organisationId, StoredSetting("base_currency", "KES", "CURRENCY", false))

        val failure =
            assertFailsWith<InvalidOperationException> {
                service.deactivate(
                    DeactivateTenantSettingCommand(organisationId, "base_currency", actorId),
                )
            }

        assertEquals(BASE_CURRENCY_MIRROR_DETAIL, failure.safeDetail)
        assertEquals("KES", settingsStore.stored(organisationId, "base_currency")?.value)
        assertEquals(0, auditEvents.saved.size)
        assertEquals(0, events.published.size)
    }

    @Test
    fun `get of base_currency shows the organisation column, not a stale stored row`() {
        activate()
        organisations.currency[organisationId] = "USD"
        settingsStore.put(organisationId, StoredSetting("base_currency", "KES", "CURRENCY", false))

        val view = service.get(GetTenantSettingQuery(organisationId, "base_currency", actorId))

        assertEquals("USD", view.value)
        assertEquals("CURRENCY", view.valueType)
    }

    @Test
    fun `get of base_currency does not read the stored row it ignores`() {
        activate()
        organisations.currency[organisationId] = "USD"
        settingsStore.put(organisationId, StoredSetting("base_currency", "KES", "CURRENCY", false))

        service.get(GetTenantSettingQuery(organisationId, "base_currency", actorId))

        assertFalse(
            settingsStore.readKeys.contains("base_currency"),
            "a read of the mirror key must not touch the stored row it ignores",
        )
    }

    @Test
    fun `list shows base_currency from the organisation column`() {
        activate()
        organisations.currency[organisationId] = "EUR"
        settingsStore.put(organisationId, StoredSetting("base_currency", "KES", "CURRENCY", false))

        val page = service.list(ListTenantSettingsQuery(organisationId, actorId))

        assertEquals("EUR", page.items.single { it.key == "base_currency" }.value)
    }

    @Test
    fun `settings operational is hidden from list and refused by get as an unknown key`() {
        // The platform writes this marker at approval; it is not a tenant setting, so it counts
        // toward nothing and reads as no key at all.
        activate()
        settingsStore.put(
            organisationId,
            StoredSetting("settings.operational", "true", "STRING", false),
        )

        val page = service.list(ListTenantSettingsQuery(organisationId, actorId))
        assertEquals(TenantSettingCatalog.keys().size.toLong(), page.totalItems)
        assertFalse(page.items.any { it.key == "settings.operational" })

        assertFailsWith<InvalidOperationException> {
            service.get(GetTenantSettingQuery(organisationId, "settings.operational", actorId))
        }
    }

    @Test
    fun `an unset platform-only setting lists as null, not as the redaction mask`() {
        activate()
        guard.deny(PLATFORM_ORG_ID, "tenant_setting.manage_platform")

        val page = service.list(ListTenantSettingsQuery(organisationId, actorId))

        assertNull(page.items.single { it.key == "audit_retention_days" }.value)
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
                CreateOrUpdateTenantSettingCommand(
                    organisationId,
                    "default_timezone",
                    "Africa/Nairobi",
                    actorId,
                ),
            )
        }
    }

    @Test
    fun `base_currency is forbidden without settings-update, before the mirror answer`() {
        // The permission check precedes the mirror refusal on both write paths, so a caller who may
        // not write learns nothing: it gets the same 403 it gets for any key, never the 422 that
        // would tell it the key is a read-only mirror.
        activate()
        organisations.currency[organisationId] = "KES"
        guard.deny(organisationId, "settings.update")

        assertFailsWith<SecurityException> {
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(organisationId, "base_currency", "USD", actorId),
            )
        }
        assertFailsWith<SecurityException> {
            service.deactivate(
                DeactivateTenantSettingCommand(organisationId, "base_currency", actorId),
            )
        }
    }

    @Test
    fun `createOrUpdate persists, audits, and publishes for a normal setting`() {
        activate()
        val view =
            service.createOrUpdate(
                CreateOrUpdateTenantSettingCommand(
                    organisationId,
                    "default_timezone",
                    " Africa/Nairobi ",
                    actorId,
                ),
            )
        assertEquals("Africa/Nairobi", view.value)
        assertEquals(
            "Africa/Nairobi",
            settingsStore.stored(organisationId, "default_timezone")?.value,
        )
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
            service.get(GetTenantSettingQuery(organisationId, "default_timezone", actorId)).value,
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
    fun `get of an unknown key is refused as forbidden before the key is judged when denied`() {
        activate()
        guard.deny(organisationId, "settings.view")

        assertFailsWith<SecurityException> {
            service.get(GetTenantSettingQuery(organisationId, "no_such_key", actorId))
        }
    }

    @Test
    fun `get of an unknown key is invalid when the caller may read settings`() {
        activate()

        assertFailsWith<InvalidOperationException> {
            service.get(GetTenantSettingQuery(organisationId, "no_such_key", actorId))
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
        settingsStore.put(
            organisationId,
            StoredSetting("default_timezone", "Africa/Nairobi", "TIMEZONE", false),
        )
        service.deactivate(
            DeactivateTenantSettingCommand(organisationId, "default_timezone", actorId),
        )
        assertNull(settingsStore.stored(organisationId, "default_timezone"))
    }

    @Test
    fun `deactivate is a no-op when no setting is currently active`() {
        activate()

        service.deactivate(
            DeactivateTenantSettingCommand(organisationId, "default_timezone", actorId),
        )

        assertEquals(0, auditEvents.saved.size)
        assertEquals(0, events.published.size)
    }

    private companion object {
        val PLATFORM_ORG_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000000")
        const val BASE_CURRENCY_MIRROR_DETAIL =
            "base_currency is derived from the organisation's base currency " +
                "and cannot be changed here."
    }
}

private class FakeLifecycleStoreForSettings : OrganisationLifecycleProvisioningStore {
    val states = mutableMapOf<UUID, OrganisationLifecycleState>()

    override fun lifecycleState(organisationId: UUID) = states[organisationId]

    override fun lockOrganisation(organisationId: UUID) = Unit

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID = uuidV7()

    override fun saveSettings(
        organisationId: UUID,
        settings: List<StoredSetting>,
        actorId: UUID,
    ) = Unit

    override fun hasRequiredMetadata(organisationId: UUID): Boolean = true

    override fun hasAmendedOrganisation(
        organisationId: UUID,
        actorId: UUID,
    ): Boolean = false
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

    /** Every key [currentSetting] was asked for, in order. */
    val readKeys = mutableListOf<String>()

    override fun currentSetting(
        organisationId: UUID,
        key: String,
    ): StoredSetting? {
        readKeys += key
        return data[organisationId to key]
    }

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

    override fun branchVisibility(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): BranchVisibility =
        if (denied.contains(organisationId to permissionCode)) {
            BranchVisibility.Branches(emptySet())
        } else {
            BranchVisibility.AllBranches
        }

    override fun mutationBranchVisibility(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): BranchVisibility = branchVisibility(actorId, organisationId, permissionCode)
}

private class CapturingPublisherForSettings : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}

/** The organisation's base currency column: the one thing [OrganisationBootstrapStore] answers. */
private class FakeBootstrapStore : OrganisationBootstrapStore {
    val currency = mutableMapOf<UUID, String>()

    override fun baseCurrencyCode(organisationId: UUID): String? = currency[organisationId]

    override fun lockBaseCurrencyCode(organisationId: UUID): String? = currency[organisationId]

    override fun ensureBusinessDate(
        organisationId: UUID,
        date: LocalDate,
    ) = unused()

    override fun timezone(organisationId: UUID): String = unused()

    override fun ensureHeadOfficeDraft(organisationId: UUID): HeadOfficeDraftResult = unused()

    override fun createDefaultReferenceSequences(organisationId: UUID) = unused()

    override fun createDefaultRoles(organisationId: UUID) = unused()

    override fun headOfficeState(organisationId: UUID): BranchLifecycleState? = unused()

    private fun unused(): Nothing = error("not used by tenant settings")
}

private class RecordingAuditRepository : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved.add(event)
    }
}
