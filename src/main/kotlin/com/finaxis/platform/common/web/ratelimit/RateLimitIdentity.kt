package com.finaxis.platform.common.web.ratelimit

/**
 * Resolved rate-limit key and policy for a request.
 */
data class RateLimitIdentity(
    val key: String,
    val policy: RateLimitPolicy,
    val authenticated: Boolean,
)
