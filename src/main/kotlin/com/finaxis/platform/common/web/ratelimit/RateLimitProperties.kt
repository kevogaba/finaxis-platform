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
    val policies: Map<RateLimitPolicyId, RateLimitPolicy> = defaultPolicies(),
    val paths: RateLimitPathProperties = RateLimitPathProperties(),
) {
    init {
        require(policies.keys == RateLimitPolicyId.entries.toSet()) {
            "Rate-limit policies must contain exactly the supported policy IDs"
        }
    }
}

/** Closed set of externally configurable rate-limit policy identifiers. */
enum class RateLimitPolicyId(
    val externalId: String,
) {
    AUTH_SELECTION("auth-selection"),
    PLATFORM_READ("platform-read"),
    PLATFORM_COMMAND("platform-command"),
    TENANT_READ("tenant-read"),
    TENANT_COMMAND("tenant-command"),
}

private fun defaultPolicies(): Map<RateLimitPolicyId, RateLimitPolicy> =
    mapOf(
        RateLimitPolicyId.AUTH_SELECTION to RateLimitPolicy(capacity = 20, refillTokens = 20),
        RateLimitPolicyId.PLATFORM_READ to RateLimitPolicy(capacity = 600, refillTokens = 600),
        RateLimitPolicyId.PLATFORM_COMMAND to RateLimitPolicy(capacity = 120, refillTokens = 120),
        RateLimitPolicyId.TENANT_READ to RateLimitPolicy(capacity = 600, refillTokens = 600),
        RateLimitPolicyId.TENANT_COMMAND to RateLimitPolicy(capacity = 120, refillTokens = 120),
    )
