package com.finaxis.platform.common.web.ratelimit

/**
 * Port for consuming tokens from a distributed rate-limit bucket.
 */
fun interface RateLimiterService {
    /**
     * Attempts to consume a request token for the supplied key and policy.
     */
    fun tryConsume(
        key: String,
        policy: RateLimitPolicy,
    ): RateLimitDecision
}
