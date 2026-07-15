package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionEvent
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class OrganisationSettingsServiceTests {
    private val lifecycleStore = FakeOrganisationLifecycleStore()
    private val settingsStore = FakeOrganisationSettingsStore()
    private val events = CapturingTransitionPublisherForSettings()
    private val service = OrganisationSettingsService(lifecycleStore, settingsStore, events)

    @Test
    fun `updateSettings rejects an organisation that is not active`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.SUSPENDED

        assertFailsWith<IllegalArgumentException> {
            service.updateSettings(
                UpdateOrganisationSettingsCommand(
                    organisationId = organisationId,
                    updates = mapOf("settings.operational" to "false"),
                    actorId = uuidV7(),
                ),
            )
        }
    }

    @Test
    fun `updateSettings rejects an empty update map`() {
        val organisationId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE

        assertFailsWith<IllegalArgumentException> {
            service.updateSettings(
                UpdateOrganisationSettingsCommand(
                    organisationId = organisationId,
                    updates = emptyMap(),
                    actorId = uuidV7(),
                ),
            )
        }
    }

    @Test
    fun `updateSettings persists the update and publishes the externalized event`() {
        val organisationId = uuidV7()
        val actorId = uuidV7()
        lifecycleStore.states[organisationId] = OrganisationLifecycleState.ACTIVE

        val result =
            service.updateSettings(
                UpdateOrganisationSettingsCommand(
                    organisationId = organisationId,
                    updates = mapOf("settings.operational" to "false"),
                    actorId = actorId,
                ),
            )

        assertEquals(mapOf("settings.operational" to "false"), result.updated)
        assertEquals(
            mapOf("settings.operational" to "false"),
            settingsStore.updates[organisationId],
        )
        val event = assertIs<ExternalizedTransitionEvent>(events.published.single())
        assertEquals("finaxis.lifecycle.organisation.settings-updated", event.target)
        assertEquals("TenantSettingsUpdated", event.metadata["eventType"])
    }
}

private class FakeOrganisationLifecycleStore : OrganisationLifecycleProvisioningStore {
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

private class FakeOrganisationSettingsStore : OrganisationSettingsStore {
    val updates = mutableMapOf<UUID, Map<String, String>>()

    override fun currentSettings(
        organisationId: UUID,
        keys: Set<String>,
    ): Map<String, String> = emptyMap()

    override fun updateSettings(
        organisationId: UUID,
        updates: Map<String, String>,
        actorId: UUID,
    ) {
        this.updates[organisationId] = updates
    }
}

private class CapturingTransitionPublisherForSettings : TransitionEventPublisher {
    val published = mutableListOf<TransitionEvent>()

    override fun publish(event: TransitionEvent) {
        published.add(event)
    }
}
