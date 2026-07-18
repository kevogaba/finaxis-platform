package com.finaxis.platform.common.web.idempotency

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/** Typed retention, recovery, cleanup, and safe-header settings for mutation idempotency. */
@ConfigurationProperties(prefix = "finaxis.api.idempotency")
data class IdempotencyProperties(
    val retention: Duration = Duration.ofHours(DEFAULT_RETENTION_HOURS),
    val inProgressTimeout: Duration = Duration.ofMinutes(DEFAULT_IN_PROGRESS_TIMEOUT_MINUTES),
    val cleanupSchedule: String = "0 */15 * * * *",
    val cleanupBatchSize: Int = 500,
    val safeDomainHeaders: Set<String> = setOf("X-Domain-Reference"),
) {
    init {
        require(!retention.isNegative && !retention.isZero) { "Retention must be positive" }
        require(!inProgressTimeout.isNegative && !inProgressTimeout.isZero) {
            "In-progress timeout must be positive"
        }
        require(retention > inProgressTimeout) {
            "Retention must outlast the in-progress timeout"
        }
        require(cleanupSchedule.isNotBlank()) { "Cleanup schedule must not be blank" }
        require(cleanupBatchSize > 0) { "Cleanup batch size must be positive" }
        require(safeDomainHeaders.all { it.startsWith("X-Domain-") }) {
            "Safe domain response headers must use the X-Domain- prefix"
        }
    }

    private companion object {
        const val DEFAULT_RETENTION_HOURS: Long = 24
        const val DEFAULT_IN_PROGRESS_TIMEOUT_MINUTES: Long = 5
    }
}
