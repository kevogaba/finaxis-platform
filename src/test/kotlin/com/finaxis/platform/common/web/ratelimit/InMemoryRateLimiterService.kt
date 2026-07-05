package com.finaxis.platform.common.web.ratelimit

internal class InMemoryRateLimiterService(
    properties: RateLimitProperties,
) : RateLimiterService {
    private val remainingByKey = mutableMapOf<String, Long>()

    init {
        require(properties.enabled)
    }

    override fun tryConsume(
        key: String,
        policy: RateLimitPolicy,
    ): RateLimitDecision {
        val remaining = remainingByKey.getOrPut(key) { policy.capacity }
        val allowed = remaining > 0
        if (allowed) {
            remainingByKey[key] = remaining - 1
        }
        return RateLimitDecision(
            allowed = allowed,
            limit = policy.capacity,
            remaining = if (allowed) remaining - 1 else 0,
            retryAfter = policy.refillPeriod,
            resetAfter = policy.refillPeriod,
        )
    }
}
