package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.ConflictException
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

/**
 * Serialization point for fiscal-period close and reopen.
 *
 * Takes the exclusive row lock and returns the period as read under it, so a caller cannot decide
 * from a stale read. Because the lock conflicts with the shared lock every posting holds, a close
 * waits for in-flight postings to finish rather than racing them.
 *
 * Registered as a bean in
 * [com.finaxis.platform.accounting.config.AccountingModuleConfiguration] now that
 * `accounting_fiscal_period` and its store adapter exist.
 *
 * The close and reopen use cases themselves belong to issue #39; this guard is the concurrency
 * contract they must go through.
 */
class FiscalPeriodStateChangeGuard(
    private val periods: FiscalPeriodStateStore,
) {
    /** Locks the period exclusively and returns its current committed state. */
    fun beginStateChange(key: FiscalPeriodKey): FiscalPeriodSnapshot {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "A fiscal-period state change takes an exclusive row lock that must be held until " +
                "commit, so it requires an active transaction."
        }
        return periods.lockForStateChange(key) ?: throw notFound()
    }

    /**
     * Applies a status change under the already-held exclusive lock.
     *
     * [current] must be the snapshot returned by [beginStateChange]; passing an earlier read would
     * defeat the point of the lock.
     *
     * A false return from the store means the write matched no row **for that tenant**. It cannot
     * mean a concurrent status change: the caller holds `FOR UPDATE`, so nothing else can move the
     * row - or delete it - until this transaction commits, and [current] carries the same key the
     * lock was taken on. The branch is therefore defensive rather than a race that can be
     * provoked, and it is reported as a conflict rather than ignored because a state change that
     * matched no row has not happened and the caller must not proceed as though it had.
     */
    fun applyStatus(
        current: FiscalPeriodSnapshot,
        target: FiscalPeriodStatus,
        actorId: UUID,
    ) {
        requireChangeable(current, target)
        if (!periods.updateStatus(current.key, target, actorId)) {
            throw ConflictException(
                code = STATE_CHANGE_MATCHED_NO_ROW,
                safeDetail = "The fiscal period is no longer available for a state change.",
            )
        }
    }

    private fun requireChangeable(
        current: FiscalPeriodSnapshot,
        target: FiscalPeriodStatus,
    ) {
        // LOCKED is terminal. The canonical design says a closed period may be reopened and a
        // locked one may not, so without this the reopen flow would happily resurrect books that
        // were permanently finalised - and the immutable journals under them.
        if (current.status == FiscalPeriodStatus.LOCKED) {
            throw ConflictException(
                code = PERIOD_LOCKED,
                safeDetail = "The fiscal period is locked and can no longer change state.",
            )
        }
        if (current.status == target) {
            throw ConflictException(
                code = ALREADY_IN_STATE,
                safeDetail = "The fiscal period is already in the requested state.",
            )
        }
    }

    private fun notFound() =
        ConflictException(
            code = PERIOD_NOT_FOUND,
            safeDetail = "The fiscal period does not exist.",
        )

    private companion object {
        const val PERIOD_NOT_FOUND = PostingErrorCodes.PERIOD_NOT_FOUND
        const val ALREADY_IN_STATE = "accounting.fiscal_period_already_in_state"
        const val PERIOD_LOCKED = "accounting.fiscal_period_locked"
        const val STATE_CHANGE_MATCHED_NO_ROW = "accounting.fiscal_period_state_change_failed"
    }
}
