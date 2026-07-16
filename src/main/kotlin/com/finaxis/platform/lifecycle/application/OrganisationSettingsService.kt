package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditedAction
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * Updates organisation settings after provisioning. This is a simple, non-FSM mutation: its
 * audit trail is recorded by [com.finaxis.platform.common.audit.AuditedActionAspect] rather than
 * an explicit [com.finaxis.platform.common.audit.AuditService] call.
 */
@Service
class OrganisationSettingsService(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val settingsStore: OrganisationSettingsStore,
    private val eventPublisher: TransitionEventPublisher,
) {
    /** Updates the requested settings for an active organisation. */
    @Transactional
    @AuditedAction(
        action = "settings.update",
        resourceType = "ORGANISATION_SETTING",
        tenantId = "#command.organisationId",
        resourceId = "#command.organisationId",
        actorId = "#command.actorId",
        before =
            "@organisationSettingsStore.currentSettings(" +
                "#command.organisationId, #command.updates.keySet())",
        after = "#command.updates",
        reason = "#command.reason",
    )
    fun updateSettings(command: UpdateOrganisationSettingsCommand): OrganisationSettingsResult {
        require(command.updates.isNotEmpty()) { "At least one setting must be updated." }
        require(
            lifecycleStore.lifecycleState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        ) { "Settings can be updated only for an active organisation." }

        settingsStore.updateSettings(command.organisationId, command.updates, command.actorId)
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = SETTINGS_UPDATED_TARGET,
                aggregateType = ORGANISATION_SETTING,
                aggregateId = command.organisationId.toString(),
                transition = "UPDATE",
                fromState = "PREVIOUS",
                toState = "CURRENT",
                actor = TransitionActor("USER", command.actorId.toString()),
                occurredAt = Instant.now(),
                metadata =
                    mapOf(
                        "organisationId" to command.organisationId.toString(),
                        "eventType" to "TenantSettingsUpdated",
                        "keys" to command.updates.keys.joinToString(","),
                    ),
            ),
        )
        return OrganisationSettingsResult(command.organisationId, command.updates)
    }

    private companion object {
        const val SETTINGS_UPDATED_TARGET = "finaxis.lifecycle.organisation.settings-updated"
        const val ORGANISATION_SETTING = "ORGANISATION_SETTING"
    }
}
