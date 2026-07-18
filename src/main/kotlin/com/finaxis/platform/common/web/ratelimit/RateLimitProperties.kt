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
    val policies: Map<String, RateLimitPolicy> = defaultPolicies(),
    val paths: RateLimitPathProperties = RateLimitPathProperties(),
)

private fun defaultPolicies(): Map<String, RateLimitPolicy> =
    mapOf(
        "auth-selection" to RateLimitPolicy(capacity = 20, refillTokens = 20),
        "platform-read" to RateLimitPolicy(capacity = 600, refillTokens = 600),
        "platform-command" to RateLimitPolicy(capacity = 120, refillTokens = 120),
        "tenant-read" to RateLimitPolicy(capacity = 600, refillTokens = 600),
        "tenant-command" to RateLimitPolicy(capacity = 120, refillTokens = 120),
    )
