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
            "/scalar/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
        ),
    val rules: List<RateLimitPathRule> = defaultRateLimitPathRules(),
)

/** Allowlisted HTTP method and path mapping to a named rate-limit policy. */
data class RateLimitPathRule(
    val method: String,
    val path: String,
    val policy: RateLimitPolicyId,
)

private fun defaultRateLimitPathRules(): List<RateLimitPathRule> =
    listOf(
        RateLimitPathRule(
            "POST",
            "/api/v1/auth/select-organisation",
            RateLimitPolicyId.AUTH_SELECTION,
        ),
        RateLimitPathRule(
            "POST",
            "/api/v1/auth/select-branch",
            RateLimitPolicyId.AUTH_SELECTION,
        ),
        RateLimitPathRule("GET", "/api/v1/auth/**", RateLimitPolicyId.PLATFORM_READ),
        RateLimitPathRule("POST", "/api/v1/auth/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("PUT", "/api/v1/auth/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("PATCH", "/api/v1/auth/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("DELETE", "/api/v1/auth/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("GET", "/api/v1/platform/**", RateLimitPolicyId.PLATFORM_READ),
        RateLimitPathRule("POST", "/api/v1/platform/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("PUT", "/api/v1/platform/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("PATCH", "/api/v1/platform/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("DELETE", "/api/v1/platform/**", RateLimitPolicyId.PLATFORM_COMMAND),
        RateLimitPathRule("GET", "/api/v1/**", RateLimitPolicyId.TENANT_READ),
        RateLimitPathRule("POST", "/api/v1/**", RateLimitPolicyId.TENANT_COMMAND),
        RateLimitPathRule("PUT", "/api/v1/**", RateLimitPolicyId.TENANT_COMMAND),
        RateLimitPathRule("PATCH", "/api/v1/**", RateLimitPolicyId.TENANT_COMMAND),
        RateLimitPathRule("DELETE", "/api/v1/**", RateLimitPolicyId.TENANT_COMMAND),
    )
