package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.config.AccountingProperties
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.FiscalPeriodAggregate
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodLifecycle
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.domain.FiscalPeriodTransition
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.common.transitions.TransitionCommand
import com.finaxis.platform.common.transitions.TransitionException
import com.finaxis.platform.common.transitions.TransitionExecution
import com.finaxis.platform.common.transitions.TransitionExecutor
import org.springframework.dao.CannotAcquireLockException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Open, close, reopen and lock, as transitions on the fiscal-period state machine.
 *
 * Every one of them goes through [FiscalPeriodStateChangeGuard], which takes the exclusive row
 * lock and returns the period as read **under** that lock. That is what makes a close wait for
 * in-flight postings rather than race them, and it is the contract issue #35 shipped for exactly
 * this service to use.
 *
 * The order inside each operation is deliberate and is the same every time: check the permission,
 * validate the request's own shape, take the lock, re-read under it, validate every state- or
 * history-dependent control against **that** snapshot, then transition. Validating against
 * anything read before the lock is the race the whole design exists to prevent - and that includes
 * the maker-checker lookup, not only the period's status.
 */
@Service
class FiscalPeriodLifecycleService(
    private val guard: FiscalPeriodStateChangeGuard,
    private val periods: FiscalPeriodStateStore,
    private val makers: FiscalPeriodMakerResolver,
    private val permissions: AccountingPermissionGuard,
    private val transitions: TransitionExecutor,
    private val lockTimeout: TransactionLockBound,
    private val auditService: AuditService,
    private val properties: AccountingProperties,
) {
    /** Opens a provisioned period for posting. */
    @Transactional
    fun open(command: FiscalPeriodStateChangeCommand): FiscalPeriodSnapshot {
        permissions.requireTenantPermission(
            command.actorId,
            command.key.organisationId,
            AccountingPermissions.FISCAL_PERIOD_OPEN,
        )
        return apply(command, FiscalPeriodTransition.OPEN)
    }

    /**
     * Closes an open period, waiting no longer than the configured bound for the lock.
     *
     * The bound is what ADR 0022 assigns to this issue. A close queues behind every in-flight
     * posting into the period, which is correct; unbounded, a long posting transaction delays it
     * forever and the caller simply hangs.
     */
    @Transactional
    fun close(command: FiscalPeriodStateChangeCommand): FiscalPeriodSnapshot {
        permissions.requireTenantPermission(
            command.actorId,
            command.key.organisationId,
            AccountingPermissions.FISCAL_PERIOD_CLOSE,
        )
        lockTimeout.applyToCurrentTransaction(properties.fiscalPeriodCloseLockTimeout)
        return try {
            apply(command, FiscalPeriodTransition.CLOSE)
        } catch (ex: CannotAcquireLockException) {
            // CannotAcquireLockException, not QueryTimeoutException. PostgreSQL raises SQLSTATE
            // 55P03 when `lock_timeout` expires, and Spring's PostgreSQL error codes list 55P03
            // under `cannotAcquireLockCodes` - QueryTimeoutException is a *sibling* under
            // TransientDataAccessException, never a supertype, so an earlier revision's catch was
            // dead code and PERIOD_LOCK_TIMEOUT was a published code nothing could raise.
            throw ConflictException(
                code = PostingErrorCodes.PERIOD_LOCK_TIMEOUT,
                safeDetail = "The fiscal period is busy with in-flight postings; retry shortly.",
                cause = ex,
            )
        }
    }

    /**
     * Reopens a closed period.
     *
     * Three controls, and each is required by a different rule. `fiscal_period.reopen` is a
     * `CRITICAL` break-glass code, so it is checked without the system-actor exemption an ordinary
     * permission check allows — a batch job must not reopen books. The reason is mandatory,
     * because a reopen with no stated cause is unauditable after the fact. And the actor must not
     * be the one who closed the period, which is how `INV-10`'s *"a checker whose identity is
     * persisted and who is not the maker"* is satisfied with the four codes `V5` seeded rather
     * than a `fiscal_period.submit` the catalogue does not have.
     */
    @Transactional
    fun reopen(command: FiscalPeriodStateChangeCommand): FiscalPeriodSnapshot {
        permissions.requireBreakGlassPermission(
            command.actorId,
            command.key.organisationId,
            AccountingPermissions.FISCAL_PERIOD_REOPEN,
        )
        requireReason(command)
        // Under the lock, not before it. See `requireDifferentActorFromTheClose`.
        return apply(command, FiscalPeriodTransition.REOPEN, ::requireDifferentActorFromTheClose)
    }

    /**
     * Locks a closed period permanently.
     *
     * Gated on `fiscal_period.close` because the catalogue `V5` seeded has no separate lock code,
     * and locking is the stronger form of the same act — but checked through the **break-glass**
     * path rather than the ordinary tenant one, and subject to the different-actor rule, so the
     * stronger act is not the more weakly controlled one.
     *
     * Recorded here rather than left implicit: a deployment that wants a role able to close but not
     * to finalise needs a `fiscal_period.lock` code and a migration, which the catalogue freeze and
     * the one-migration-per-issue rule put outside issue #39. Until it exists, no role should hold
     * `fiscal_period.close` unless it is also trusted to lock.
     */
    @Transactional
    fun lock(command: FiscalPeriodStateChangeCommand): FiscalPeriodSnapshot {
        // requireBreakGlassPermission, not requireTenantPermission. The ordinary check authorises
        // the system-actor sentinel before consulting any grant, which is right for a background
        // job advancing a business date and wrong for the one transition nothing reverses: a batch
        // path could otherwise permanently finalise a tenant's books with no principal holding the
        // CRITICAL authority. Reopen is already checked this way, and a lock is not the weaker act.
        permissions.requireBreakGlassPermission(
            command.actorId,
            command.key.organisationId,
            AccountingPermissions.FISCAL_PERIOD_CLOSE,
        )
        requireReason(command)
        // The same different-actor control reopen carries, and for a stronger reason. Reopen is
        // reversible; a lock is not - nothing transitions out of LOCKED, so one actor closing and
        // then locking would permanently freeze a tenant's books with no second pair of eyes and
        // no recovery short of hand-written SQL. An earlier revision gated the reversible act
        // three ways and the irreversible one only by permission.
        return apply(command, FiscalPeriodTransition.LOCK, ::requireDifferentActorFromTheClose)
    }

    /** Reads a period without locking it, permission-gated and tenant-scoped. */
    @Transactional(readOnly = true)
    fun get(
        key: FiscalPeriodKey,
        actorId: UUID,
    ): FiscalPeriodSnapshot {
        permissions.requireTenantPermission(
            actorId,
            key.organisationId,
            AccountingPermissions.FISCAL_PERIOD_VIEW,
        )
        // ResourceNotFoundException rather than the ConflictException the posting path raises for
        // the same code: on a read, a period that does not exist is a missing resource, and the
        // sibling chart-of-accounts service already answers 404 for exactly this.
        return periods.findById(key)
            ?: throw ResourceNotFoundException(
                code = PostingErrorCodes.PERIOD_NOT_FOUND,
                safeDetail = "The fiscal period does not exist.",
            )
    }

    /**
     * Takes the lock, validates against the snapshot read under it, and runs the transition.
     *
     * [FiscalPeriodStateChangeGuard.applyStatus] is what writes the new status, and the executor
     * is what writes the transition log — so the aggregate handed to the executor is already at
     * its new state and its `persist` step is a no-op. Doing it the other way round would have the
     * executor write the status through a path that never took the lock.
     */
    private fun apply(
        command: FiscalPeriodStateChangeCommand,
        transition: FiscalPeriodTransition,
        underLock: (FiscalPeriodStateChangeCommand) -> Unit = {},
    ): FiscalPeriodSnapshot {
        val current = guard.beginStateChange(command.key)
        val definition = requireLegal(current, transition)
        underLock(command)

        guard.applyStatus(current, definition.to, command.actorId, command.reason)

        val aggregate = FiscalPeriodAggregate(current.key, current.status)
        transitions.execute(
            TransitionExecution(
                aggregate = aggregate,
                graph = FiscalPeriodLifecycle.GRAPH,
                transition = transition,
                actor = TransitionActor(type = ACTOR_TYPE_USER, id = command.actorId.toString()),
                command =
                    TransitionCommand(
                        reason = command.reason,
                        metadata = mapOf(ORGANISATION_ID to command.key.organisationId.toString()),
                    ),
                persist = { it },
            ),
        )
        recordAudit(command, transition, current.status, definition.to)
        return current.copy(status = definition.to)
    }

    /**
     * Resolves the transition, reporting an illegal one through the published error contract.
     *
     * The graph raises `TransitionNotAllowedException`, which is reusable-infrastructure vocabulary
     * rather than part of accounting's RFC 9457 contract, so a caller would see a framework type
     * with no stable code. `LOCKED` gets its own code because it is not merely an illegal
     * transition — it is the permanent one, and the distinction between a period that may come
     * back and one that may not is the reason the four-value status set exists.
     *
     * [FiscalPeriodStateChangeGuard.applyStatus] refuses `LOCKED` as well. That is not redundant:
     * the guard is the concurrency contract any caller may go through, and it must not depend on
     * every caller having consulted this graph first.
     */
    private fun requireLegal(
        current: FiscalPeriodSnapshot,
        transition: FiscalPeriodTransition,
    ) = if (current.status == FiscalPeriodStatus.LOCKED) {
        throw ConflictException(
            code = PostingErrorCodes.PERIOD_LOCKED,
            safeDetail = "The fiscal period is locked and can no longer change state.",
        )
    } else {
        try {
            FiscalPeriodLifecycle.GRAPH.requireDefinition(current.status, transition)
        } catch (ex: TransitionException) {
            // ConflictException rather than InvalidOperationException: this refusal is about the
            // period's current state, which is precisely what a conflict means here - and it is
            // the only member of the sealed family that carries a cause, so the underlying
            // transition failure is not discarded.
            throw ConflictException(
                code = PostingErrorCodes.PERIOD_TRANSITION_NOT_ALLOWED,
                safeDetail =
                    "A ${current.status} fiscal period cannot undergo ${transition.name}.",
                cause = ex,
            )
        }
    }

    private fun requireReason(command: FiscalPeriodStateChangeCommand) {
        if (command.reason.isNullOrBlank()) {
            throw InvalidOperationException(
                code = PostingErrorCodes.PERIOD_REASON_REQUIRED,
                safeDetail = "This fiscal-period operation requires a reason.",
            )
        }
    }

    /**
     * Rejects an actor who is the period's most recent closer.
     *
     * **Must run while the period's exclusive row lock is held**, which is why `apply` invokes it
     * after `beginStateChange` rather than each entry point calling it first. Resolved before the
     * lock, the answer can go stale between check and write: an actor B who passes against closer A
     * can have the period reopened and re-closed by B in the interim, and the original request then
     * completes against a period whose latest closer is B - self-approval through the front door.
     *
     * Holding the lock closes that window, and not only for the period row. Every state change goes
     * through [FiscalPeriodStateChangeGuard], so a transition-log row for this period can only be
     * written by a transaction that first took this same lock. While it is held, the latest `CLOSE`
     * row cannot change, so the maker resolved here is still the maker at write time.
     */
    private fun requireDifferentActorFromTheClose(command: FiscalPeriodStateChangeCommand) {
        val closer = makers.lastActorFor(command.key, FiscalPeriodTransition.CLOSE)
        if (closer != null && closer == command.actorId) {
            throw ForbiddenOperationException(
                code = PostingErrorCodes.PERIOD_SELF_APPROVAL,
                safeDetail = "The actor who closed a period cannot reopen it.",
            )
        }
    }

    /**
     * Records every transition, in the same transaction as the state change.
     *
     * `record`, not `recordIndependently`, and the difference is the point. An independent audit
     * commits immediately in a `REQUIRES_NEW` transaction, so a reopen whose enclosing transaction
     * later rolled back would leave a permanent `SUCCESS` row claiming the books were reopened
     * while the period was still `CLOSED`. The prior-period posting audit uses the independent
     * form for a genuinely different reason — there the audited fact is *authority was exercised*,
     * and the journal's own outcome is audited elsewhere — but here the audited fact **is** the
     * transition, so the two must stand or fall together.
     *
     * An earlier revision audited only `reopen`, which left `close` and `lock` — the operations
     * that determine what can still be posted and what has been permanently reported — with no
     * `audit_event` row at all.
     */
    private fun recordAudit(
        command: FiscalPeriodStateChangeCommand,
        transition: FiscalPeriodTransition,
        from: FiscalPeriodStatus,
        to: FiscalPeriodStatus,
    ) {
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = command.actorId.toString(),
                tenantId = command.key.organisationId.toString(),
                action = AUDIT_ACTIONS.getValue(transition),
                resourceType = RESOURCE_TYPE_FISCAL_PERIOD,
                resourceId = command.key.fiscalPeriodId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = SEVERITIES.getValue(transition),
                reason = command.reason,
                metadata = mapOf("from" to from.name, "to" to to.name),
            ),
        )
    }

    private fun notFound() =
        ConflictException(
            code = PostingErrorCodes.PERIOD_NOT_FOUND,
            safeDetail = "The fiscal period does not exist.",
        )

    private companion object {
        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE_FISCAL_PERIOD = "FISCAL_PERIOD"
        const val ORGANISATION_ID = "organisationId"

        /** The audit action each transition records. */
        val AUDIT_ACTIONS =
            mapOf(
                FiscalPeriodTransition.OPEN to AccountingAuditActions.FISCAL_PERIOD_OPEN,
                FiscalPeriodTransition.CLOSE to AccountingAuditActions.FISCAL_PERIOD_CLOSE,
                FiscalPeriodTransition.REOPEN to AccountingAuditActions.FISCAL_PERIOD_REOPEN,
                FiscalPeriodTransition.LOCK to AccountingAuditActions.FISCAL_PERIOD_LOCK,
            )

        /**
         * Opening a period is `HIGH`; the three that end or reverse a reporting period are
         * `CRITICAL`, matching the severities `V5` seeded for their permissions.
         */
        val SEVERITIES =
            mapOf(
                FiscalPeriodTransition.OPEN to AuditSeverity.HIGH,
                FiscalPeriodTransition.CLOSE to AuditSeverity.CRITICAL,
                FiscalPeriodTransition.REOPEN to AuditSeverity.CRITICAL,
                FiscalPeriodTransition.LOCK to AuditSeverity.CRITICAL,
            )
    }
}

/** A request to move one fiscal period to its next state. */
data class FiscalPeriodStateChangeCommand(
    val key: FiscalPeriodKey,
    val actorId: UUID,
    val reason: String? = null,
)
