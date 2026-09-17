package com.finaxis.platform.common.persistence

import java.time.Duration

/**
 * Bounds how long the current transaction will wait for a row lock.
 *
 * A fiscal-period close takes the exclusive lock, so it queues behind every in-flight posting into
 * that period. That is the correct outcome — a close must not race a posting — but it means a long
 * posting transaction can delay a close indefinitely, which ADR 0022 records as a starvation risk
 * and assigns the bound to issue #39. Without it, a close that will never acquire simply hangs the
 * request; with it, the database raises a lock-acquisition failure and the caller gets a retryable
 * error naming what happened.
 *
 * Declared as a port rather than as the jOOQ component itself, because an application service is
 * not allowed to know that the bound is a PostgreSQL `SET LOCAL`, any more than it knows that a
 * period lives in a jOOQ table. `FiscalPeriodLifecycleService` previously imported the component
 * directly, which pointed the dependency the wrong way.
 *
 * It lives in `common::persistence`, beside `AdvisoryLockNamespace`, because a second module now
 * needs it. Issue #125 gave the posting path a shared lock on `business_date`, which makes
 * `BusinessDateService.startCob` a waiter with exactly the fiscal-period close's problem: correct
 * to queue behind in-flight postings, unacceptable to queue behind them forever. One
 * implementation serving both is the alternative to two copies of a `SET LOCAL` and two copies of
 * the reasoning about why sub-millisecond bounds disable it.
 */
fun interface TransactionLockBound {
    /**
     * Applies [timeout] to the current transaction, which must be active.
     *
     * An implementation whose mechanism is transaction-scoped is required to fail rather than
     * silently no-op outside one: a caller that believes its wait is bounded and is not would hang
     * exactly as it would have without the call.
     */
    fun applyToCurrentTransaction(timeout: Duration)
}

/**
 * Rejects a lock-timeout that cannot be expressed in the unit it is sent in.
 *
 * PostgreSQL's `lock_timeout` is milliseconds and [java.time.Duration.toMillis] truncates, so a
 * positive sub-millisecond bound such as `500us` arrives as `0` - which PostgreSQL reads as **no
 * timeout at all**, the exact opposite of what the caller asked for. A bound that silently disables
 * itself is worse than no bound, because the operator believes they have one.
 *
 * Declared once, here, and called from every typed-properties `init` block that carries such a
 * value as well as from [TransactionLockTimeout] itself. It was four verbatim copies of the same
 * rule and the same paragraph before, which is exactly how the unit and the message drift apart.
 *
 * [name] names the property in the failure so a misconfigured deployment is told which one.
 */
fun requireLockTimeoutBound(
    timeout: Duration,
    name: String,
) {
    require(!timeout.isNegative && timeout.toMillis() >= 1) {
        "$name must be at least 1ms; below that it truncates to zero, which disables the bound " +
            "rather than tightening it."
    }
}
