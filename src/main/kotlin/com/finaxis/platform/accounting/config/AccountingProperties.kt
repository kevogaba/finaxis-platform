package com.finaxis.platform.accounting.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Operational bounds for accounting paths that wait on a lock.
 *
 * Configurable rather than a constant because the right bound depends on how long a deployment's
 * posting transactions actually run, which is a property of its products and its load, not of this
 * code.
 */
@ConfigurationProperties(prefix = "finaxis.accounting")
data class AccountingProperties(
    val fiscalPeriodCloseLockTimeout: Duration = DEFAULT_CLOSE_LOCK_TIMEOUT,
    val functionalCurrencyLockTimeout: Duration = DEFAULT_CURRENCY_LOCK_TIMEOUT,
) {
    init {
        // At least one millisecond, not merely positive: `SET LOCAL lock_timeout` is expressed in
        // milliseconds and the conversion truncates, so `500us` would arrive as `0` - which
        // PostgreSQL reads as no timeout at all. Caught here so a misconfigured deployment fails at
        // startup rather than the first time something waits on a lock.
        require(
            !fiscalPeriodCloseLockTimeout.isNegative &&
                fiscalPeriodCloseLockTimeout.toMillis() >= 1,
        ) {
            "The fiscal-period close lock timeout must be at least 1ms; below that it truncates " +
                "to zero, which disables the bound."
        }
        require(
            !functionalCurrencyLockTimeout.isNegative &&
                functionalCurrencyLockTimeout.toMillis() >= 1,
        ) {
            "The functional-currency lock timeout must be at least 1ms; below that it truncates " +
                "to zero, which disables the bound."
        }
    }

    /** The shipped default, overridden per deployment. */
    companion object {
        /**
         * Long enough for an ordinary posting transaction to finish and release its shared lock,
         * short enough that a close blocked behind a stuck one fails rather than hangs.
         */
        val DEFAULT_CLOSE_LOCK_TIMEOUT: Duration = Duration.ofSeconds(10)

        /**
         * The bound on a base-currency change's exclusive wait.
         *
         * This one is not merely about the waiter. PostgreSQL makes a lock request wait when it
         * conflicts with the *pending* queue as well as with what is granted, so while this
         * exclusive request is queued behind an in-flight posting, every **new** posting for the
         * tenant queues behind it in turn. An unbounded wait here is therefore an unbounded stall
         * of the tenant's whole posting path, not just of the administrator.
         */
        val DEFAULT_CURRENCY_LOCK_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
