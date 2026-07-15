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
 * Advances the controlled organisation business date. This is a simple, non-FSM mutation: its
 * audit trail is recorded by [com.finaxis.platform.common.audit.AuditedActionAspect] rather than
 * an explicit [com.finaxis.platform.common.audit.AuditService] call.
 */
@Service
class BusinessDateService(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val businessDateStore: BusinessDateStore,
    private val eventPublisher: TransitionEventPublisher,
) {
    /** Advances the business date for an active organisation by one optimistic-locked step. */
    @Transactional
    @AuditedAction(
        action = "business_date.advance",
        resourceType = "BUSINESS_DATE",
        tenantId = "#command.organisationId",
        resourceId = "#command.organisationId",
        actorId = "#command.actorId",
        before = "@businessDateStore.current(#command.organisationId)",
        after = "#result",
        reason = "#command.reason",
    )
    fun advance(command: AdvanceBusinessDateCommand): BusinessDateAdvanceResult {
        require(
            lifecycleStore.lifecycleState(command.organisationId) ==
                OrganisationLifecycleState.ACTIVE,
        ) { "Business date can be advanced only for an active organisation." }
        val current =
            requireNotNull(businessDateStore.current(command.organisationId)) {
                "Business date was not found for the organisation."
            }
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

        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = BUSINESS_DATE_ADVANCED_TARGET,
                aggregateType = BUSINESS_DATE,
                aggregateId = command.organisationId.toString(),
                transition = "ADVANCE",
                fromState = current.currentBusinessDate.toString(),
                toState = command.newBusinessDate.toString(),
                actor = TransitionActor("USER", command.actorId.toString()),
                occurredAt = Instant.now(),
                metadata =
                    mapOf(
                        "organisationId" to command.organisationId.toString(),
                        "eventType" to "BusinessDateAdvanced",
                    ),
            ),
        )
        return BusinessDateAdvanceResult(
            command.organisationId,
            current.currentBusinessDate,
            command.newBusinessDate,
        )
    }

    private companion object {
        const val BUSINESS_DATE_ADVANCED_TARGET =
            "finaxis.lifecycle.organisation.business-date-advanced"
        const val BUSINESS_DATE = "BUSINESS_DATE"
    }
}
