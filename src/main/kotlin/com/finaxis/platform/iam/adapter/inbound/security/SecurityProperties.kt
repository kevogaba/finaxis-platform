package com.finaxis.platform.iam.adapter.inbound.security

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.validation.annotation.Validated

/**
 * Cross-origin request policy for the servlet API.
 *
 * CORS remains disabled unless explicitly enabled so deployments must opt in to every allowed
 * browser origin.
 */
@ConfigurationProperties(prefix = "finaxis.security.cors")
@Validated
data class CorsProperties(
    val enabled: Boolean = false,
    val allowedOrigins: List<String> = emptyList(),
    val allowedMethods: List<String> = DEFAULT_ALLOWED_METHODS,
    val allowedHeaders: List<String> = DEFAULT_ALLOWED_HEADERS,
    val allowCredentials: Boolean = false,
) {
    /**
     * Conservative browser request defaults used after CORS has been explicitly enabled.
     */
    companion object {
        private val DEFAULT_ALLOWED_METHODS =
            listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        private val DEFAULT_ALLOWED_HEADERS =
            listOf("Authorization", "Content-Type", "X-Request-Id")
    }
}

/**
 * HTTP response header policy for browser-facing endpoints.
 */
@ConfigurationProperties(prefix = "finaxis.security.headers")
@Validated
data class SecurityHeadersProperties(
    val hstsEnabled: Boolean = false,
    val contentSecurityPolicy: String = "",
)
