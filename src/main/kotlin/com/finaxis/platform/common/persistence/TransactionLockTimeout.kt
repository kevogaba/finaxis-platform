package com.finaxis.platform.common.persistence

import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * Bounds how long the current transaction will wait for a row lock.
 *
 * Two callers today, and the same shape both times. A fiscal-period close takes the exclusive lock,
 * so it queues behind every in-flight posting into that period; a `startCob` updates
 * `business_date`, so it queues behind every in-flight posting in the tenant. Both are the correct
 * outcome — neither may race a posting — but unbounded, a long posting transaction delays them
 * forever and the caller simply hangs. With the bound, PostgreSQL raises `lock_not_available` and
 * the caller gets a retryable failure naming what happened.
 *
 * `SET LOCAL` scopes the change to the transaction, so it reverts at commit or rollback and no
 * other statement on the pooled connection inherits it.
 */
@Component
class TransactionLockTimeout(
    private val dsl: DSLContext,
) : TransactionLockBound {
    /**
     * Applies [timeout] to the current transaction.
     *
     * Requires an active transaction, because `SET LOCAL` outside one is silently discarded — the
     * statement succeeds, the caller believes it is bounded, and the close hangs exactly as it
     * would have without the call.
     */
    override fun applyToCurrentTransaction(timeout: Duration) {
        check(TransactionSynchronizationManager.isActualTransactionActive()) {
            "SET LOCAL is discarded outside a transaction, so a lock timeout set here would " +
                "silently not apply."
        }
        // The same rule the typed properties validate at startup, applied again at the call so a
        // bound built in code rather than read from configuration cannot slip past it.
        requireLockTimeoutBound(timeout, "A lock timeout")
        dsl.execute("SET LOCAL lock_timeout = ${timeout.toMillis()}")
    }
}
