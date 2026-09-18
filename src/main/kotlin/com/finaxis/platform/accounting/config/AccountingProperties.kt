package com.finaxis.platform.accounting.config

import com.finaxis.platform.common.persistence.requireLockTimeoutBound
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
    val dailyBalanceTrailingDays: Int = DEFAULT_DAILY_BALANCE_TRAILING_DAYS,
) {
    init {
        require(dailyBalanceTrailingDays in 0..MAXIMUM_DAILY_BALANCE_TRAILING_DAYS) {
            "finaxis.accounting.daily-balance-trailing-days must be between 0 and " +
                "$MAXIMUM_DAILY_BALANCE_TRAILING_DAYS"
        }
        // Validated at startup so a misconfigured deployment fails before the first time something
        // waits on a lock, rather than discovering it has no bound at all. The rule and the reason
        // live in `requireLockTimeoutBound`.
        requireLockTimeoutBound(
            fiscalPeriodCloseLockTimeout,
            "finaxis.accounting.fiscal-period-close-lock-timeout",
        )
        requireLockTimeoutBound(
            functionalCurrencyLockTimeout,
            "finaxis.accounting.functional-currency-lock-timeout",
        )
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

        /**
         * How many business dates before the one being settled the daily-balance build re-scans.
         *
         * One is enough, and the reason is which postings can straddle a business-date advance.
         * `advance` takes the `business_date` row exclusively and a current-dated posting holds it
         * shared, so no current-dated posting can commit after the advance carrying the old date. A
         * **backdated** posting takes no lock on that row at all - deliberately, so that
         * close-of-business
         * cannot deadlock corrections - so one that read business date `B` before the advance may
         * commit after the build for `B` has run. Re-scanning `B - 1` on the next build catches it.
         *
         * Raising it costs only the series that actually moved in the extra days, because the
         * recompute is idempotent; lowering it to zero removes the safety net and is why zero is
         * permitted but not the default.
         */
        const val DEFAULT_DAILY_BALANCE_TRAILING_DAYS: Int = 1

        /**
         * A ceiling, so a misconfiguration cannot turn every rollover into a re-scan of a year.
         *
         * The window exists to cover a commit straddling one advance, which is bounded by a
         * transaction's own lifetime, so anything beyond a working week is a repair rather than a
         * routine build - and a repair has its own entry point that names its date range.
         */
        const val MAXIMUM_DAILY_BALANCE_TRAILING_DAYS: Int = 7
    }
}
