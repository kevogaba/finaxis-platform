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
import java.time.LocalDate
import java.util.UUID

/**
 * Manages the controlled organisation business date and its close-of-business status foundation.
 * Mutations require an active organisation, tenant-scoped permission, history, audit, and
 * externalized lifecycle-event records.
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
    /** Initializes the singleton organisation business date in its OPEN state. */
    @Transactional
    fun initialize(command: InitializeBusinessDateCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, BUSINESS_DATE_ADVANCE_PERMISSION)
        check(
            businessDateStore.initialize(
                command.organisationId,
                command.initialBusinessDate,
                command.actorId,
            ),
        ) { "Business date is already initialized for the organisation." }
        record(
            BusinessDateTransition(
                organisationId = command.organisationId,
                actorId = command.actorId,
                eventType = "INITIALIZED",
                target = BUSINESS_DATE_INITIALIZED_TARGET,
                transition = "INITIALIZE",
                fromStatus = null,
                toStatus = OPEN,
                fromBusinessDate = null,
                toBusinessDate = command.initialBusinessDate,
                action = "business_date.initialize",
                reason = command.reason,
                externalEventType = "BusinessDateInitialized",
            ),
        )
        return BusinessDateView(command.organisationId, command.initialBusinessDate, OPEN)
    }

    /** Advances an active, open organisation business date by one optimistic-locked step. */
    @Transactional
    fun advance(command: AdvanceBusinessDateCommand): BusinessDateAdvanceResult {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, BUSINESS_DATE_ADVANCE_PERMISSION)
        val current = requireCurrent(command.organisationId)
        require(current.status == OPEN) { "Business date can be advanced only while it is open." }
        require(command.newBusinessDate.isAfter(current.currentBusinessDate)) {
            "The new business date must be after the current business date."
        }
        check(
            businessDateStore.advance(
                command.organisationId,
                command.newBusinessDate,
                current.rowVersion,
                command.actorId,
            ),
        ) { "Business date was concurrently advanced; retry with the latest version." }
        record(
            BusinessDateTransition(
                organisationId = command.organisationId,
                actorId = command.actorId,
                eventType = "ADVANCED",
                target = BUSINESS_DATE_ADVANCED_TARGET,
                transition = "ADVANCE",
                fromStatus = current.status,
                toStatus = current.status,
                fromBusinessDate = current.currentBusinessDate,
                toBusinessDate = command.newBusinessDate,
                action = "business_date.advance",
                reason = command.reason,
                externalEventType = "BusinessDateAdvanced",
                eventFromState = current.currentBusinessDate.toString(),
                eventToState = command.newBusinessDate.toString(),
            ),
        )
        return BusinessDateAdvanceResult(
            command.organisationId,
            current.currentBusinessDate,
            command.newBusinessDate,
        )
    }

    /** Moves an open business date into CLOSING and captures its COB date. */
    @Transactional
    fun startCob(command: StartCobCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, COB_START_PERMISSION)
        val current = requireCurrent(command.organisationId)
        require(current.status == OPEN) {
            "COB can be started only while the business date is open."
        }
        check(
            businessDateStore.startCob(
                command.organisationId,
                current.currentBusinessDate,
                current.rowVersion,
                command.actorId,
            ),
        ) { "Business date was concurrently changed; retry with the latest version." }
        record(
            BusinessDateTransition(
                organisationId = command.organisationId,
                actorId = command.actorId,
                eventType = "COB_STARTED",
                target = COB_STARTED_TARGET,
                transition = "START_COB",
                fromStatus = OPEN,
                toStatus = CLOSING,
                fromBusinessDate = current.currentBusinessDate,
                toBusinessDate = current.currentBusinessDate,
                action = "cob.start",
                reason = command.reason,
                externalEventType = "CobStarted",
            ),
        )
        return BusinessDateView(command.organisationId, current.currentBusinessDate, CLOSING)
    }

    /** Moves a CLOSING business date into CLOSED without changing its recorded COB date. */
    @Transactional
    fun completeCob(command: CompleteCobCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, COB_COMPLETE_PERMISSION)
        val current = requireCurrent(command.organisationId)
        require(current.status == CLOSING) {
            "COB can be completed only while the business date is closing."
        }
        check(
            businessDateStore.changeStatus(
                command.organisationId,
                CLOSED,
                current.rowVersion,
                command.actorId,
            ),
        ) { "Business date was concurrently changed; retry with the latest version." }
        record(
            BusinessDateTransition(
                organisationId = command.organisationId,
                actorId = command.actorId,
                eventType = "COB_COMPLETED",
                target = COB_COMPLETED_TARGET,
                transition = "COMPLETE_COB",
                fromStatus = CLOSING,
                toStatus = CLOSED,
                fromBusinessDate = current.currentBusinessDate,
                toBusinessDate = current.currentBusinessDate,
                action = "cob.complete",
                reason = command.reason,
                externalEventType = "CobCompleted",
            ),
        )
        return BusinessDateView(command.organisationId, current.currentBusinessDate, CLOSED)
    }

    /** Reopens a CLOSED business date without changing its recorded COB date. */
    @Transactional
    fun reopen(command: ReopenBusinessDateCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, BUSINESS_DATE_REOPEN_PERMISSION)
        val current = requireCurrent(command.organisationId)
        require(current.status == CLOSED) {
            "Business date can be reopened only while it is closed."
        }
        check(
            businessDateStore.changeStatus(
                command.organisationId,
                OPEN,
                current.rowVersion,
                command.actorId,
            ),
        ) { "Business date was concurrently changed; retry with the latest version." }
        record(
            BusinessDateTransition(
                organisationId = command.organisationId,
                actorId = command.actorId,
                eventType = "REOPENED",
                target = BUSINESS_DATE_REOPENED_TARGET,
                transition = "REOPEN",
                fromStatus = CLOSED,
                toStatus = OPEN,
                fromBusinessDate = current.currentBusinessDate,
                toBusinessDate = current.currentBusinessDate,
                action = "business_date.reopen",
                reason = command.reason,
                externalEventType = "BusinessDateReopened",
            ),
        )
        return BusinessDateView(command.organisationId, current.currentBusinessDate, OPEN)
    }

    /** Reads the current business date after verifying tenant-scoped view permission. */
    @Transactional(readOnly = true)
    fun get(query: GetBusinessDateQuery): BusinessDateView {
        requirePermission(query.actorId, query.organisationId, BUSINESS_DATE_VIEW_PERMISSION)
        val current = requireCurrent(query.organisationId)
        return BusinessDateView(query.organisationId, current.currentBusinessDate, current.status)
    }

    /** Lists bounded business-date history after verifying tenant-scoped view permission. */
    @Transactional(readOnly = true)
    fun listHistory(query: ListBusinessDateHistoryQuery): BusinessDateHistoryPage {
        require(query.page >= 0) { "Page must not be negative." }
        require(query.size in 1..MAXIMUM_PAGE_SIZE) {
            "Page size must be between 1 and $MAXIMUM_PAGE_SIZE."
        }
        requirePermission(query.actorId, query.organisationId, BUSINESS_DATE_VIEW_PERMISSION)
        return historyStore.list(query.organisationId, query.page, query.size)
    }

    private fun requireActive(organisationId: UUID) {
        require(
            lifecycleStore.lifecycleState(organisationId) == OrganisationLifecycleState.ACTIVE,
        ) {
            "Business date can be changed only for an active organisation."
        }
    }

    private fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        permissionGuard.requirePermission(actorId, organisationId, permissionCode)
    }

    private fun requireCurrent(organisationId: UUID): BusinessDateSnapshot =
        requireNotNull(businessDateStore.current(organisationId)) {
            "Business date was not found for the organisation."
        }

    private fun record(transition: BusinessDateTransition) {
        val occurredAt = clock.instant()
        historyStore.append(
            BusinessDateHistoryEntry(
                transition.organisationId,
                transition.eventType,
                transition.fromStatus,
                transition.toStatus,
                transition.fromBusinessDate,
                transition.toBusinessDate,
                transition.actorId,
                transition.reason,
                occurredAt,
            ),
        )
        auditService.recordSuccess(
            actorId = transition.actorId,
            tenantId = transition.organisationId,
            action = transition.action,
            resourceType = BUSINESS_DATE,
            resourceId = transition.organisationId.toString(),
            actorType = USER_ACTOR,
            reason = transition.reason,
            before = businessDateState(transition.fromStatus, transition.fromBusinessDate),
            after = businessDateState(transition.toStatus, transition.toBusinessDate),
            metadata = mapOf("eventType" to transition.externalEventType),
        )
        eventPublisher.publish(
            ExternalizedTransitionEvent(
                target = transition.target,
                aggregateType = BUSINESS_DATE,
                aggregateId = transition.organisationId.toString(),
                transition = transition.transition,
                fromState = transition.eventFromState,
                toState = transition.eventToState,
                actor = TransitionActor(USER_ACTOR, transition.actorId.toString()),
                occurredAt = occurredAt,
                metadata =
                    mapOf(
                        "organisationId" to transition.organisationId.toString(),
                        "eventType" to transition.externalEventType,
                    ),
            ),
        )
    }

    private fun businessDateState(
        status: String?,
        businessDate: LocalDate?,
    ): Map<String, Any?> = mapOf("status" to status, "businessDate" to businessDate?.toString())

    private companion object {
        const val BUSINESS_DATE = "BUSINESS_DATE"
        const val USER_ACTOR = "USER"
        const val OPEN = "OPEN"
        const val CLOSING = "CLOSING"
        const val CLOSED = "CLOSED"
        const val BUSINESS_DATE_ADVANCE_PERMISSION = "business_date.advance"
        const val BUSINESS_DATE_REOPEN_PERMISSION = "business_date.reopen"
        const val BUSINESS_DATE_VIEW_PERMISSION = "business_date.view"
        const val COB_START_PERMISSION = "cob.start"
        const val COB_COMPLETE_PERMISSION = "cob.complete"
        const val MAXIMUM_PAGE_SIZE = 100
        const val BUSINESS_DATE_INITIALIZED_TARGET =
            "finaxis.lifecycle.organisation.business-date-initialized"
        const val BUSINESS_DATE_ADVANCED_TARGET =
            "finaxis.lifecycle.organisation.business-date-advanced"
        const val COB_STARTED_TARGET = "finaxis.lifecycle.organisation.cob-started"
        const val COB_COMPLETED_TARGET = "finaxis.lifecycle.organisation.cob-completed"
        const val BUSINESS_DATE_REOPENED_TARGET =
            "finaxis.lifecycle.organisation.business-date-reopened"
    }
}

/** Descriptor for one business-date/COB state change, recorded as history, audit, and an event. */
private data class BusinessDateTransition(
    val organisationId: UUID,
    val actorId: UUID,
    val eventType: String,
    val target: String,
    val transition: String,
    val fromStatus: String?,
    val toStatus: String,
    val fromBusinessDate: LocalDate?,
    val toBusinessDate: LocalDate,
    val action: String,
    val reason: String?,
    val externalEventType: String,
    val eventFromState: String = fromStatus ?: "NONE",
    val eventToState: String = toStatus,
)
