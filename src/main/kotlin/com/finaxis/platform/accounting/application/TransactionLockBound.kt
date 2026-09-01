package com.finaxis.platform.accounting.application

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
 * Declared here, in the application layer, and implemented in `adapter/outbound/persistence`.
 * [FiscalPeriodLifecycleService] previously imported the jOOQ component directly, which pointed the
 * dependency the wrong way: an application service is not allowed to know that the bound is a
 * PostgreSQL `SET LOCAL`, any more than it knows that a period lives in a jOOQ table. The service
 * already takes its period storage and maker resolution as ports; this is the third.
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
