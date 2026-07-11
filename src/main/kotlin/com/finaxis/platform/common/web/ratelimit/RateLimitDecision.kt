package com.finaxis.platform.common.web.ratelimit

import java.time.Duration

/**
 * Result of attempting to consume one token from a distributed bucket.
 */
data class RateLimitDecision(
    val allowed: Boolean,
    val limit: Long,
    val remaining: Long,
    val retryAfter: Duration,
    val resetAfter: Duration,
)
