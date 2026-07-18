package com.finaxis.platform.common.web.ratelimit

/**
 * Resolved rate-limit key and policy for a request.
 */
data class RateLimitIdentity(
    val key: String,
    val policyId: RateLimitPolicyId,
    val policy: RateLimitPolicy,
    val authenticated: Boolean,
) : RateLimitResolution

/** Typed outcome when no allowlisted policy matches a request. */
data class NoRateLimitPolicy(
    val method: String,
    val path: String,
) : RateLimitResolution

/** Result of resolving an inbound request against the policy allowlist. */
sealed interface RateLimitResolution
