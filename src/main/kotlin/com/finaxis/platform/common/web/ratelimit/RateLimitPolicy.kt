package com.finaxis.platform.common.web.ratelimit

import java.time.Duration

/**
 * Token-bucket policy for a rate-limit identity class.
 */
data class RateLimitPolicy(
    val capacity: Long = 100,
    val refillTokens: Long = capacity,
    val refillPeriod: Duration = Duration.ofMinutes(1),
) {
    init {
        require(capacity > 0) { "Rate-limit capacity must be positive" }
        require(refillTokens > 0) { "Rate-limit refill tokens must be positive" }
        require(!refillPeriod.isZero && !refillPeriod.isNegative) {
            "Rate-limit refill period must be positive"
        }
    }
}
