package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.TransactionLockBound
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.time.Duration

/**
 * Bounds how long the current transaction will wait for a row lock.
 *
 * A fiscal-period close takes the exclusive lock, so it queues behind every in-flight posting into
 * that period. That is the correct outcome — a close must not race a posting — but it means a long
 * posting transaction can delay a close indefinitely, which ADR 0022 records as a starvation risk
 * and assigns the bound to issue #39. Without it, a close that will never acquire simply hangs the
 * request; with it, PostgreSQL raises `lock_not_available` and the caller gets a retryable failure
 * naming what happened.
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
        // Not `isZero`: PostgreSQL's unit here is milliseconds, and `toMillis()` truncates, so a
        // positive sub-millisecond duration such as `500us` converts to 0 - which PostgreSQL reads
        // as "no timeout at all", the exact opposite of what the caller asked for. The bound has to
        // be expressible in the unit it is sent in.
        val millis = timeout.toMillis()
        require(!timeout.isNegative && millis >= 1) {
            "A lock timeout below one millisecond truncates to zero, which disables the bound " +
                "rather than tightening it."
        }
        dsl.execute("SET LOCAL lock_timeout = $millis")
    }
}
