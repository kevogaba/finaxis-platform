package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.persistence.requireLockTimeoutBound
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Operational bounds for business-date mutations that now wait on a lock.
 *
 * Configurable rather than a constant for the same reason `finaxis.accounting`'s bounds are: the
 * right value depends on how long a deployment's posting transactions actually run, which is a
 * property of its products and its load, not of this code.
 */
@ConfigurationProperties(prefix = "finaxis.lifecycle")
data class BusinessDateProperties(
    val businessDateLockTimeout: Duration = DEFAULT_BUSINESS_DATE_LOCK_TIMEOUT,
) {
    init {
        // Validated at startup so a misconfigured deployment fails before the first
        // close-of-business waits, rather than discovering it has no bound at all. The rule and
        // the reason live in `requireLockTimeoutBound`.
        requireLockTimeoutBound(
            businessDateLockTimeout,
            "finaxis.lifecycle.business-date-lock-timeout",
        )
    }

    /** The shipped default, overridden per deployment. */
    companion object {
        /**
         * The bound on a business-date mutation's wait for in-flight postings.
         *
         * Issue #125 gave the posting path a shared lock on `business_date`, so a mutation of that
         * row - an advance, a `startCob`, a COB completion, a reopen - now queues behind every
         * posting in flight for the tenant. That is the semantics wanted; unbounded, it is an
         * operator waiting on a request that may never return.
         *
         * Not only about the waiter, either. PostgreSQL conflicts an incoming lock request against
         * the *pending* queue as well as against what is granted, so while this exclusive request
         * is queued behind an in-flight posting, every **new** posting for the tenant queues behind
         * it in turn. An unbounded wait here is therefore an unbounded stall of the tenant's whole
         * posting path, not just of the administrator - the same argument
         * `AccountingProperties.DEFAULT_CURRENCY_LOCK_TIMEOUT` is sized by, and the same ten
         * seconds.
         */
        val DEFAULT_BUSINESS_DATE_LOCK_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
