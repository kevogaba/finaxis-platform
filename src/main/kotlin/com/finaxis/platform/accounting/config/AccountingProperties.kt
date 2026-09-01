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
) {
    init {
        require(!fiscalPeriodCloseLockTimeout.isNegative && !fiscalPeriodCloseLockTimeout.isZero) {
            "The fiscal-period close lock timeout must be positive; zero disables the bound."
        }
    }

    /** The shipped default, overridden per deployment. */
    companion object {
        /**
         * Long enough for an ordinary posting transaction to finish and release its shared lock,
         * short enough that a close blocked behind a stuck one fails rather than hangs.
         */
        val DEFAULT_CLOSE_LOCK_TIMEOUT: Duration = Duration.ofSeconds(10)
    }
}
