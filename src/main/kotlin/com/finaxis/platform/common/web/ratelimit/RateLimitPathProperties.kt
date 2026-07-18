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
    val rules: List<RateLimitPathRule> = defaultRateLimitPathRules(),
)

/** Allowlisted HTTP method and path mapping to a named rate-limit policy. */
data class RateLimitPathRule(
    val method: String,
    val path: String,
    val policy: String,
)

private fun defaultRateLimitPathRules(): List<RateLimitPathRule> =
    listOf(
        RateLimitPathRule("POST", "/api/v1/auth/select-organisation", "auth-selection"),
        RateLimitPathRule("POST", "/api/v1/auth/select-branch", "auth-selection"),
        RateLimitPathRule("GET", "/api/v1/auth/**", "platform-read"),
        RateLimitPathRule("GET", "/api/v1/**", "tenant-read"),
        RateLimitPathRule("POST", "/api/v1/**", "tenant-command"),
        RateLimitPathRule("PUT", "/api/v1/**", "tenant-command"),
        RateLimitPathRule("PATCH", "/api/v1/**", "tenant-command"),
        RateLimitPathRule("DELETE", "/api/v1/**", "tenant-command"),
    )
