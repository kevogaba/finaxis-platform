package com.finaxis.platform.accounting.adapter.outbound.persistence

import org.springframework.transaction.support.TransactionSynchronizationManager

/**
 * Fails fast when no Spring transaction is active.
 *
 * A row lock on an autocommit connection is released the moment the statement returns, so a caller
 * that believes it holds the row until commit holds nothing. The same applies to a write that only
 * makes sense under such a lock: [JooqFiscalPeriodStateStore.updateStatus] documents that the
 * caller is holding `FOR UPDATE`, and outside a transaction that claim is false. Shared rather than
 * duplicated, because the linearizability guarantee is only as strong as its weakest participant.
 *
 * Every locking adapter in this package opens with it, [JooqFiscalPeriodStateStore.updateStatus]
 * and [JooqFiscalPeriodStateStore.lockCoveringForPosting] among them. That is not defensive
 * repetition: the lock and the assertion have to be in the same method, because a caller that
 * reached the adapter outside a transaction gets a statement that succeeds and a lock that is
 * already gone, which is indistinguishable from success at every layer above.
 *
 * This file used to declare a `PostgresRowLock` component alongside it, a boolean-returning
 * primitive that took a lock in one statement so a caller could read the row in the next. That
 * shape is deleted rather than deprecated: it is what made "lock, then read" representable, and the
 * only way to keep it unwriteable is for it not to exist. Locking adapters now take the lock and
 * project the columns in one statement.
 *
 * @param operation named in the failure message so the caller can tell which contract it broke.
 */
internal fun requireActiveTransaction(operation: String) {
    check(TransactionSynchronizationManager.isActualTransactionActive()) {
        "$operation requires an active transaction; on an autocommit connection the row lock it " +
            "relies on is released before the caller can act on it."
    }
}
