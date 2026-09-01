package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.ConflictException
import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Serialization point for fiscal-period close and reopen.
 *
 * Takes the exclusive row lock and returns the period as read under it, so a caller cannot decide
 * from a stale read. Because the lock conflicts with the shared lock every posting holds, a close
 * waits for in-flight postings to finish rather than racing them.
 *
 * Deliberately **not** a Spring bean yet, for the same reason as
 * [PostingPeriodResolver]: its [FiscalPeriodStateStore] dependency has no adapter until issue #36,
 * and registering it early would break context startup platform-wide.
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
     * defeat the point of the lock. A false return from the store means another transaction moved
     * the row first, which is reported as a conflict rather than silently ignored.
     */
    fun applyStatus(
        current: FiscalPeriodSnapshot,
        target: FiscalPeriodStatus,
    ) {
        requireChangeable(current, target)
        if (!periods.updateStatus(current.key, target)) {
            throw ConflictException(
                code = CONCURRENT_STATE_CHANGE,
                safeDetail = "The fiscal period was changed by another operation.",
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
        const val CONCURRENT_STATE_CHANGE = "accounting.fiscal_period_concurrent_change"
    }
}
