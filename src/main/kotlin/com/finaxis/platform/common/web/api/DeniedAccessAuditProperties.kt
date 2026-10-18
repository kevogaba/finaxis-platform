package com.finaxis.platform.common.web.api

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * How many denied-access audit rows one caller may write (#184). The bucket is per actor and
 * tenant, so a client probing routes in a loop writes [capacity] rows, then one more every
 * [refillPeriod] divided by [capacity], however fast it asks; the request rate limiter bounds the
 * requests themselves. The rows that are skipped are counted in a message-free log line.
 */
@ConfigurationProperties(prefix = "finaxis.audit.denied-access")
data class DeniedAccessAuditProperties(
    val enabled: Boolean = true,
    val capacity: Long = 10,
    val refillPeriod: Duration = Duration.ofMinutes(1),
) {
    init {
        require(capacity > 0) { "Denied-access audit capacity must be positive" }
        require(!refillPeriod.isZero && !refillPeriod.isNegative) {
            "Denied-access audit refill period must be positive"
        }
    }
}
