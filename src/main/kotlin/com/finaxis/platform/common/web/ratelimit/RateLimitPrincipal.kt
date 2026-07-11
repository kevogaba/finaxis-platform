package com.finaxis.platform.common.web.ratelimit

/**
 * Principal contract used by common rate limiting without depending on an application module.
 */
interface RateLimitPrincipal {
    /** Stable application user identifier. */
    val rateLimitUserId: String

    /** Stable tenant or organisation identifier when the authenticated context has one. */
    val rateLimitTenantId: String?
}
