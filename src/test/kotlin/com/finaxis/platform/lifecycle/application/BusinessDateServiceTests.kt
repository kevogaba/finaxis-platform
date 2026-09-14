package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BusinessDateServiceTests {
    private val lifecycleStore = FakeOrganisationLifecycleStoreForBusinessDate()
    private val businessDateStore = FakeBusinessDateStore()
    private val historyStore = FakeBusinessDateHistoryStore()
    private val guard = FakePermissionGuardForBusinessDate()
    private val events = CapturingTransitionPublisherForBusinessDate()
    private val auditEvents = RecordingAuditRepositoryForBusinessDate()
    private val clock = Clock.fixed(Instant.parse("2026-07-17T10:15:30Z"), ZoneOffset.UTC)
    private val service =
        BusinessDateService(
            lifecycleStore,
            businessDateStore,
            historyStore,
            guard,
            AuditService(auditEvents, clock),
            events,
            clock,
        )

    @Test
    fun `initialize rejects an organisation that is not active`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED

        assertFailsWith<ConflictException> {
            service.initialize(
                InitializeBusinessDateCommand(
                    organisationId,
                    LocalDate.parse("2026-07-15"),
                    uuidV7(),
                ),
            )
        }
    }

    @Test
    fun `initialize rejects a business date that is already initialized`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        assertFailsWith<ConflictException> {
            service.initialize(
                InitializeBusinessDateCommand(
                    organisationId,
                    LocalDate.parse("2026-07-16"),
                    uuidV7(),
                ),
            )
        }
    }

    @Test
    fun `advance rejects an organisation that is not active`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED

        assertFailsWith<ConflictException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance requires business-date advance permission`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)
        guard.deny(organisationId, "business_date.advance")

        assertFailsWith<SecurityException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), actorId),
            )
        }
    }

    @Test
    fun `advance rejects a date that does not move forward`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        assertFailsWith<InvalidOperationException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-15"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance fails when the row version was concurrently changed`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)
        businessDateStore.advanceSucceeds = false

        assertFailsWith<ConflictException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance persists the new date and publishes the externalized event`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        val result =
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), actorId),
            )

        assertEquals(LocalDate.parse("2026-07-15"), result.previousBusinessDate)
        assertEquals(LocalDate.parse("2026-07-16"), result.newBusinessDate)
        assertEquals(LocalDate.parse("2026-07-16"), businessDateStore.advancedTo[organisationId])
        val event = assertIs<ExternalizedTransitionEvent>(events.published.single())
        assertEquals("finaxis.lifecycle.organisation.business-date-advanced", event.target)
        assertEquals("BusinessDateAdvanced", event.metadata["eventType"])
    }

    @Test
    fun `start COB moves open business date to closing and sets the COB date`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        service.startCob(StartCobCommand(organisationId, actorId))

        assertEquals("CLOSING", businessDateStore.current(organisationId)?.status)
        assertEquals(LocalDate.parse("2026-07-15"), businessDateStore.cobDates[organisationId])
        val event = assertIs<ExternalizedTransitionEvent>(events.published.single())
        assertEquals("CobStarted", event.metadata["eventType"])
    }

    @Test
    fun `complete COB requires closing status`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        assertFailsWith<ConflictException> {
            service.completeCob(CompleteCobCommand(organisationId, uuidV7()))
        }
    }

    @Test
    fun `complete COB moves closing business date to closed`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "CLOSING", 0)

        service.completeCob(CompleteCobCommand(organisationId, uuidV7()))

        assertEquals("CLOSED", businessDateStore.current(organisationId)?.status)
        assertEquals("CobCompleted", publishedEvent().metadata["eventType"])
    }

    @Test
    fun `reopen requires business-date reopen permission`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "CLOSED", 0)
        guard.deny(organisationId, "business_date.reopen")

        assertFailsWith<SecurityException> {
            service.reopen(ReopenBusinessDateCommand(organisationId, actorId))
        }
    }

    @Test
    fun `reopen requires closed status`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        assertFailsWith<ConflictException> {
            service.reopen(ReopenBusinessDateCommand(organisationId, uuidV7()))
        }
    }

    @Test
    fun `reopen moves closed business date to open`() {
        val organisationId = activeOrganisation()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "CLOSED", 0)

        service.reopen(ReopenBusinessDateCommand(organisationId, uuidV7()))

        assertEquals("OPEN", businessDateStore.current(organisationId)?.status)
        assertEquals("BusinessDateReopened", publishedEvent().metadata["eventType"])
    }

    @Test
    fun `get requires business-date view permission`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        guard.deny(organisationId, "business_date.view")

        assertFailsWith<SecurityException> {
            service.get(GetBusinessDateQuery(organisationId, actorId))
        }
    }

    @Test
    fun `get returns the current business date snapshot`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 4)

        val view = service.get(GetBusinessDateQuery(organisationId, actorId))

        assertEquals(organisationId, view.organisationId)
        assertEquals(LocalDate.parse("2026-07-15"), view.currentBusinessDate)
        assertEquals("OPEN", view.status)
    }

    @Test
    fun `list history requires business-date view permission`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        guard.deny(organisationId, "business_date.view")

        assertFailsWith<SecurityException> {
            service.listHistory(ListBusinessDateHistoryQuery(organisationId, actorId))
        }
    }

    @Test
    fun `list history delegates to the history store`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()
        val page = BusinessDateHistoryPage(emptyList(), 7)
        historyStore.listedPage = page

        val result =
            service.listHistory(ListBusinessDateHistoryQuery(organisationId, actorId, 2, 10))

        assertEquals(page, result)
        assertEquals(Triple(organisationId, 2, 10), historyStore.lastListQuery)
    }

    @Test
    fun `list history rejects a negative page`() {
        val organisationId = activeOrganisation()

        assertFailsWith<InvalidPageRequestException> {
            service.listHistory(ListBusinessDateHistoryQuery(organisationId, uuidV7(), page = -1))
        }
    }

    @Test
    fun `list history rejects a zero size`() {
        val organisationId = activeOrganisation()

        assertFailsWith<InvalidPageRequestException> {
            service.listHistory(ListBusinessDateHistoryQuery(organisationId, uuidV7(), size = 0))
        }
    }

    @Test
    fun `list history rejects a size above the maximum`() {
        val organisationId = activeOrganisation()

        assertFailsWith<InvalidPageRequestException> {
            service.listHistory(ListBusinessDateHistoryQuery(organisationId, uuidV7(), size = 101))
        }
    }

    @Test
    fun `every mutation appends one history entry with its event type`() {
        val organisationId = activeOrganisation()
        val actorId = uuidV7()

        service.initialize(
            InitializeBusinessDateCommand(organisationId, LocalDate.parse("2026-07-15"), actorId),
        )
        service.advance(
            AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), actorId),
        )
        service.startCob(StartCobCommand(organisationId, actorId))
        service.completeCob(CompleteCobCommand(organisationId, actorId))
        service.reopen(ReopenBusinessDateCommand(organisationId, actorId))

        assertEquals(
            listOf("INITIALIZED", "ADVANCED", "COB_STARTED", "COB_COMPLETED", "REOPENED"),
            historyStore.entries.map { it.eventType },
        )
    }

    private fun activeOrganisation(): UUID =
        uuidV7().also {
            lifecycleStore.states[it] = OrganisationLifecycleState.ACTIVE
        }

    private fun publishedEvent(): ExternalizedTransitionEvent =
        assertIs<ExternalizedTransitionEvent>(events.published.single())
}

private class FakeOrganisationLifecycleStoreForBusinessDate :
    OrganisationLifecycleProvisioningStore {
    val states = mutableMapOf<UUID, OrganisationLifecycleState>()

    override fun lifecycleState(organisationId: UUID): OrganisationLifecycleState? =
        states[organisationId]

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID = uuidV7()

    override fun saveSettings(
        organisationId: UUID,
        settings: List<StoredSetting>,
        actorId: UUID,
    ) = Unit

    override fun hasRequiredMetadata(organisationId: UUID): Boolean = true
}

private class FakeBusinessDateStore : BusinessDateStore {
    val snapshots = mutableMapOf<UUID, BusinessDateSnapshot>()
    val advancedTo = mutableMapOf<UUID, LocalDate>()
    val cobDates = mutableMapOf<UUID, LocalDate>()
    var advanceSucceeds = true

    override fun current(organisationId: UUID): BusinessDateSnapshot? = snapshots[organisationId]

    override fun advance(
        organisationId: UUID,
        newDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        val current = snapshots[organisationId] ?: return false
        if (!advanceSucceeds || current.rowVersion != expectedRowVersion) return false
        snapshots[organisationId] =
            current.copy(
                currentBusinessDate = newDate,
                rowVersion = current.rowVersion + 1,
            )
        advancedTo[organisationId] = newDate
        return true
    }

    override fun initialize(
        organisationId: UUID,
        initialDate: LocalDate,
        actorId: UUID,
    ): Boolean {
        if (snapshots.containsKey(organisationId)) return false
        snapshots[organisationId] = BusinessDateSnapshot(initialDate, "OPEN", 0)
        return true
    }

    override fun changeStatus(
        organisationId: UUID,
        newStatus: String,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        val current = snapshots[organisationId] ?: return false
        if (current.rowVersion != expectedRowVersion) return false
        snapshots[organisationId] =
            current.copy(status = newStatus, rowVersion = current.rowVersion + 1)
        return true
    }

    override fun startCob(
        organisationId: UUID,
        cobDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        val current = snapshots[organisationId] ?: return false
        if (current.rowVersion != expectedRowVersion) return false
        snapshots[organisationId] =
            current.copy(status = "CLOSING", rowVersion = current.rowVersion + 1)
        cobDates[organisationId] = cobDate
        return true
    }
}

private class FakeBusinessDateHistoryStore : BusinessDateHistoryStore {
    val entries = mutableListOf<BusinessDateHistoryEntry>()
    var listedPage = BusinessDateHistoryPage(emptyList(), 0)
    var lastListQuery: Triple<UUID, Int, Int>? = null

    override fun append(entry: BusinessDateHistoryEntry) {
        entries.add(entry)
    }

    override fun list(
        organisationId: UUID,
        page: Int,
        size: Int,
    ): BusinessDateHistoryPage {
        lastListQuery = Triple(organisationId, page, size)
        return listedPage
    }
}

private class FakePermissionGuardForBusinessDate : PermissionGuard {
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

private class CapturingTransitionPublisherForBusinessDate : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}

private class RecordingAuditRepositoryForBusinessDate : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved.add(event)
    }
}
