package com.finaxis.platform.common.web.ratelimit

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * Typed configuration for distributed API rate limiting.
 *
 * Spring AOT registers the annotated properties type itself but not the types reached through
 * `paths`, so a native image binds `finaxis.rate-limit.paths.rules` against classes Kotlin
 * reflection cannot then load (`KotlinReflectionInternalError: Class not found`). Naming them here
 * keeps the registration next to the declaration that needs it.
 */
@ConfigurationProperties(prefix = "finaxis.rate-limit")
@RegisterReflectionForBinding(RateLimitPathProperties::class, RateLimitPathRule::class)
data class RateLimitProperties(
    val enabled: Boolean = true,
    val includeHeaders: Boolean = true,
    val failOpen: Boolean = true,
    /**
     * The command timeout of the rate limiter's own Redis connection: how long a request waits on
     * a Redis that stops answering before the limiter gives up (and fails open). Kept apart from
     * `spring.data.redis.timeout`, the cache's and the session's, because every API request waits
     * on this one; Spring Boot has no property for a second client's timeout.
     */
    val redisTimeout: Duration = Duration.ofSeconds(DEFAULT_REDIS_TIMEOUT_SECONDS),
    val policies: Map<RateLimitPolicyId, RateLimitPolicy> = defaultPolicies(),
    val paths: RateLimitPathProperties = RateLimitPathProperties(),
) {
    init {
        require(policies.keys == RateLimitPolicyId.entries.toSet()) {
            "Rate-limit policies must contain exactly the supported policy IDs"
        }
        require(!redisTimeout.isZero && !redisTimeout.isNegative) {
            "The rate limiter's Redis timeout must be positive"
        }
    }

    private companion object {
        const val DEFAULT_REDIS_TIMEOUT_SECONDS = 5L
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

    /** Bulk extraction of the tenant audit log (ADR 0039): tighter than an ordinary read. */
    TENANT_EXPORT("tenant-export"),
}

private fun defaultPolicies(): Map<RateLimitPolicyId, RateLimitPolicy> =
    mapOf(
        RateLimitPolicyId.AUTH_SELECTION to RateLimitPolicy(capacity = 20, refillTokens = 20),
        RateLimitPolicyId.PLATFORM_READ to RateLimitPolicy(capacity = 600, refillTokens = 600),
        RateLimitPolicyId.PLATFORM_COMMAND to RateLimitPolicy(capacity = 120, refillTokens = 120),
        RateLimitPolicyId.TENANT_READ to RateLimitPolicy(capacity = 600, refillTokens = 600),
        RateLimitPolicyId.TENANT_COMMAND to RateLimitPolicy(capacity = 120, refillTokens = 120),
        RateLimitPolicyId.TENANT_EXPORT to RateLimitPolicy(capacity = 5, refillTokens = 5),
    )
