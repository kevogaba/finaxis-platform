package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class BusinessDateServiceTests {
    private val lifecycleStore = FakeOrganisationLifecycleStoreForBusinessDate()
    private val businessDateStore = FakeBusinessDateStore()
    private val events = CapturingTransitionPublisherForBusinessDate()
    private val service = BusinessDateService(lifecycleStore, businessDateStore, events)

    @Test
    fun `advance rejects an organisation that is not active`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED

        assertFailsWith<IllegalArgumentException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance rejects a date that does not move forward`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)

        assertFailsWith<IllegalArgumentException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-15"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance fails when the row version was concurrently changed`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
        businessDateStore.snapshots[organisationId] =
            BusinessDateSnapshot(LocalDate.parse("2026-07-15"), "OPEN", 0)
        businessDateStore.advanceSucceeds = false

        assertFailsWith<IllegalStateException> {
            service.advance(
                AdvanceBusinessDateCommand(organisationId, LocalDate.parse("2026-07-16"), uuidV7()),
            )
        }
    }

    @Test
    fun `advance persists the new date and publishes the externalized event`() {
        val organisationId = uuidV7()
        val actorId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE
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
}

private class FakeOrganisationLifecycleStoreForBusinessDate :
    OrganisationLifecycleProvisioningStore {
    val states = mutableMapOf<UUID, OrganisationLifecycleState>()

    override fun lifecycleState(organisationId: UUID): OrganisationLifecycleState? =
        states[organisationId]

    override fun createDraft(command: CreateOrganisationDraftCommand): UUID = uuidV7()

    override fun saveSettings(
        organisationId: UUID,
        settings: Map<String, String>,
        actorId: UUID,
    ) = Unit

    override fun hasRequiredMetadata(organisationId: UUID): Boolean = true
}

private class FakeBusinessDateStore : BusinessDateStore {
    val snapshots = mutableMapOf<UUID, BusinessDateSnapshot>()
    val advancedTo = mutableMapOf<UUID, LocalDate>()
    var advanceSucceeds = true

    override fun current(organisationId: UUID): BusinessDateSnapshot? = snapshots[organisationId]

    override fun advance(
        organisationId: UUID,
        newDate: LocalDate,
        expectedRowVersion: Long,
        actorId: UUID,
    ): Boolean {
        if (!advanceSucceeds) return false
        advancedTo[organisationId] = newDate
        return true
    }
}

private class CapturingTransitionPublisherForBusinessDate : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}
