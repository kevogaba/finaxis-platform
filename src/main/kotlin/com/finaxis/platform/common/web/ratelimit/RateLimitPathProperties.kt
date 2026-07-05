package com.finaxis.platform.common.web.ratelimit

/**
 * Path matching configuration for application-wide rate limiting.
 */
data class RateLimitPathProperties(
    val enabled: Boolean = true,
    val excluded: List<String> =
        listOf(
            "/actuator/health/**",
            "/actuator/prometheus",
            "/error",
            "/docs/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
        ),
)
