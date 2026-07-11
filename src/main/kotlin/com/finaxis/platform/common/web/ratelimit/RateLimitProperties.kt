package com.finaxis.platform.common.web.ratelimit

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Typed configuration for distributed API rate limiting.
 */
@ConfigurationProperties(prefix = "finaxis.rate-limit")
data class RateLimitProperties(
    val enabled: Boolean = true,
    val includeHeaders: Boolean = true,
    val failOpen: Boolean = true,
    val anonymous: RateLimitPolicy = RateLimitPolicy(),
    val authenticated: RateLimitPolicy =
        RateLimitPolicy(
            capacity = DEFAULT_AUTHENTICATED_CAPACITY,
            refillTokens = DEFAULT_AUTHENTICATED_CAPACITY,
        ),
    val paths: RateLimitPathProperties = RateLimitPathProperties(),
) {
    private companion object {
        private const val DEFAULT_AUTHENTICATED_CAPACITY = 1_000L
    }
}
