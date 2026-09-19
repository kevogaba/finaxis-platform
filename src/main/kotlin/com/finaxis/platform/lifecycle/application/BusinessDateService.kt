package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.persistence.TransactionLockBound
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.springframework.dao.CannotAcquireLockException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Manages the controlled organisation business date and its close-of-business status foundation.
 * Mutations require an active organisation, tenant-scoped permission, history, audit, and
 * externalized lifecycle-event records.
 *
 * **Every mutation of the row can now wait, and every mutation is therefore bounded.** Issue #125
 * gave the posting path a shared lock on `business_date`, so an advance, a `startCob`, a COB
 * completion and a reopen each queue behind the *current-dated* postings in flight for the tenant.
 * That is the point of the lock - a close-of-business that sails past postings already running is
 * the defect it closes - but an unbounded wait turns a fast status flip into a request that may
 * never return, and, because PostgreSQL queues incoming requests behind pending ones, stalls the
 * tenant's whole posting path while it waits. [BusinessDateProperties] sizes the bound; an expiry
 * surfaces as [LifecycleErrorCodes.BUSINESS_DATE_LOCK_TIMEOUT], which is retryable.
 *
 * Two things never make an operator wait here. `initialize` inserts a row that does not exist yet,
 * so it has nothing to wait on; and a **backdated** correction takes no lock on this row at all,
 * deliberately, because close-of-business must not deadlock corrections.
 */
@Service
class BusinessDateService(
    private val lifecycleStore: OrganisationLifecycleProvisioningStore,
    private val businessDateStore: BusinessDateStore,
    private val historyStore: BusinessDateHistoryStore,
    private val permissionGuard: PermissionGuard,
    private val auditService: AuditService,
    private val eventPublisher: TransitionEventPublisher,
    private val dailyBalanceBuilds: DailyBalanceBuildScheduler,
    private val lockTimeout: TransactionLockBound,
    private val properties: BusinessDateProperties,
    private val clock: Clock,
) {
    /** Initializes the singleton organisation business date in its OPEN state. */
    @Transactional
    fun initialize(command: InitializeBusinessDateCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, BUSINESS_DATE_ADVANCE_PERMISSION)
        if (!businessDateStore.initialize(
                command.organisationId,
                command.initialBusinessDate,
                command.actorId,
            )
        ) {
            throw ConflictException()
        }
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
    fun advance(command: AdvanceBusinessDateCommand): BusinessDateAdvanceResult =
        withBoundedLockWait { advanceBounded(command) }

    private fun advanceBounded(command: AdvanceBusinessDateCommand): BusinessDateAdvanceResult {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, BUSINESS_DATE_ADVANCE_PERMISSION)
        val current = requireCurrent(command.organisationId)
        conflictUnless(current.status == OPEN)
        invalidOperationUnless(command.newBusinessDate.isAfter(current.currentBusinessDate))
        conflictUnless(
            businessDateStore.advance(
                command.organisationId,
                command.newBusinessDate,
                current.rowVersion,
                command.actorId,
            ),
        )
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
        scheduleDailyBalanceBuild(command.organisationId, current.currentBusinessDate)
        return BusinessDateAdvanceResult(
            command.organisationId,
            current.currentBusinessDate,
            command.newBusinessDate,
        )
    }

    /** Moves an open business date into CLOSING and captures its COB date. */
    @Transactional
    fun startCob(command: StartCobCommand): BusinessDateView =
        withBoundedLockWait { startCobBounded(command) }

    private fun startCobBounded(command: StartCobCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, COB_START_PERMISSION)
        val current = requireCurrent(command.organisationId)
        if (current.status != OPEN) {
            throw ConflictException()
        }
        val started =
            businessDateStore.startCob(
                command.organisationId,
                current.currentBusinessDate,
                current.rowVersion,
                command.actorId,
            )
        if (!started) {
            throw ConflictException()
        }
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
    fun completeCob(command: CompleteCobCommand): BusinessDateView =
        withBoundedLockWait { completeCobBounded(command) }

    private fun completeCobBounded(command: CompleteCobCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, COB_COMPLETE_PERMISSION)
        val current = requireCurrent(command.organisationId)
        if (current.status != CLOSING) {
            throw ConflictException()
        }
        val completed =
            businessDateStore.changeStatus(
                command.organisationId,
                CLOSED,
                current.rowVersion,
                command.actorId,
            )
        if (!completed) {
            throw ConflictException()
        }
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
    fun reopen(command: ReopenBusinessDateCommand): BusinessDateView =
        withBoundedLockWait { reopenBounded(command) }

    private fun reopenBounded(command: ReopenBusinessDateCommand): BusinessDateView {
        requireActive(command.organisationId)
        requirePermission(command.actorId, command.organisationId, BUSINESS_DATE_REOPEN_PERMISSION)
        val current = requireCurrent(command.organisationId)
        if (current.status != CLOSED) {
            throw ConflictException()
        }
        val reopened =
            businessDateStore.changeStatus(
                command.organisationId,
                OPEN,
                current.rowVersion,
                command.actorId,
            )
        if (!reopened) {
            throw ConflictException()
        }
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
        if (query.page < 0) {
            throw InvalidPageRequestException()
        }
        if (query.size !in 1..MAXIMUM_PAGE_SIZE) {
            throw InvalidPageRequestException()
        }
        requirePermission(query.actorId, query.organisationId, BUSINESS_DATE_VIEW_PERMISSION)
        return historyStore.list(query.organisationId, query.page, query.size)
    }

    /**
     * Bounds this transaction's wait for the `business_date` row, and names the expiry.
     *
     * **Wrapped around the whole mutation, deliberately, because `SET LOCAL` is transaction-scoped
     * and the translation must be too.** An earlier revision wrapped only the store call, which
     * left the bound applying to every statement after it - the history insert, the audit insert,
     * the outbox write - while the `catch` covered none of them, so an expiry there would have
     * escaped as an uncategorised `500` instead of the retryable `409` this same method publishes.
     * A bound you cannot narrow has to be matched by a `catch` you widen.
     *
     * `CannotAcquireLockException`, not `QueryTimeoutException`: PostgreSQL raises `55P03` when
     * `lock_timeout` expires and Spring lists `55P03` under `cannotAcquireLockCodes`, while
     * `QueryTimeoutException` is a *sibling* under `TransientDataAccessException` and never a
     * supertype - the mistake `FiscalPeriodLifecycleService` made once and records in a comment,
     * where it left a published code nothing could raise. `40001` is *not* caught: a serialization
     * failure is a different decision and must keep propagating.
     */
    private fun <T> withBoundedLockWait(block: () -> T): T {
        lockTimeout.applyToCurrentTransaction(properties.businessDateLockTimeout)
        return try {
            block()
        } catch (ex: CannotAcquireLockException) {
            throw ConflictException(
                code = LifecycleErrorCodes.BUSINESS_DATE_LOCK_TIMEOUT,
                safeDetail =
                    "The organisation business date is busy with in-flight postings; " +
                        "retry shortly.",
                cause = ex,
            )
        }
    }

    /**
     * Hands the business date just left behind to accounting's derived balances.
     *
     * **Scheduled here, delivered after this transaction commits.** The adapter registers an
     * after-commit synchronisation rather than enqueueing inline, because JobRunr's storage
     * provider takes its own connection and an inline enqueue would survive a rolled-back advance -
     * scheduling a build for a business date the tenant never left, against which the build's
     * "this set is closed" reasoning does not hold. Running after the commit also keeps the rebuild
     * off the `business_date` row lock every current-dated posting queues behind.
     *
     * **The date left, not the date arrived at.** Once the tenant's business date has moved past a
     * day, no journal can ever again be recorded against it, so the set of journals the build
     * enumerates is closed. That is also why this sits on the advance rather than on
     * [completeCob]: a backdated posting into a still-open prior period is legal while the business
     * date is `CLOSED`, so at close-of-business the set is still filling.
     *
     * Failure to enqueue fails the advance, which is the safer direction: an advance whose
     * projection was never scheduled leaves a day that nothing will settle until the next rebuild
     * reaches back for it, and the operator would have no reason to suspect it.
     */
    private fun scheduleDailyBalanceBuild(
        organisationId: UUID,
        businessDateLeft: LocalDate,
    ) {
        dailyBalanceBuilds.scheduleBuild(organisationId, businessDateLeft)
    }

    private fun requireActive(organisationId: UUID) {
        if (lifecycleStore.lifecycleState(organisationId) != OrganisationLifecycleState.ACTIVE) {
            throw ConflictException()
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
        businessDateStore.current(organisationId) ?: throw ResourceNotFoundException()

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

private fun conflictUnless(condition: Boolean) {
    if (!condition) throw ConflictException()
}

private fun invalidOperationUnless(condition: Boolean) {
    if (!condition) throw InvalidOperationException()
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
