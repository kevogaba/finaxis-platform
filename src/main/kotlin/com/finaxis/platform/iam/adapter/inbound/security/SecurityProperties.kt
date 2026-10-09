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
    val exposedHeaders: List<String> = DEFAULT_EXPOSED_HEADERS,
    val allowCredentials: Boolean = false,
) {
    /**
     * Conservative browser request defaults used after CORS has been explicitly enabled.
     */
    companion object {
        private val DEFAULT_ALLOWED_METHODS =
            listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
        private val DEFAULT_ALLOWED_HEADERS =
            listOf(
                "Authorization",
                "Content-Type",
                "X-Request-Id",
                "X-Active-Organisation-Context",
                "Idempotency-Key",
            )
        private val DEFAULT_EXPOSED_HEADERS =
            listOf("Idempotency-Key", "Idempotency-Replayed")
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

/**
 * Controls whether the API documentation surface (Scalar UI and the OpenAPI document) is
 * reachable without authentication.
 *
 * springdoc/Scalar themselves stay enabled unconditionally; gating unauthenticated access here
 * instead, at the always-registered `SecurityConfiguration.securityFilterChain` bean, keeps the
 * toggle live at container restart under this repository's AOT-frozen `bootBuildImage` builds
 * (see `docs/superpowers/specs/2026-09-06-production-readiness-coolify-deployment-design.md`).
 */
@ConfigurationProperties(prefix = "finaxis.security.api-docs")
@Validated
data class ApiDocsProperties(
    val publicAccessEnabled: Boolean = true,
)

/**
 * Which direct peers may tell the application the client's address (#185).
 *
 * `trusted-proxies` lists the reverse proxies in front of the application, each an IPv4 or IPv6
 * address or a CIDR range. Empty by default: then `X-Forwarded-For` is ignored and the peer is
 * the client. A deployment behind a proxy must list it, or every audit row records the proxy's
 * address. An entry that is not an address or a range, or a range of every address (`/0`), fails
 * startup (see [ClientIpResolver] and [IpRange]).
 */
@ConfigurationProperties(prefix = "finaxis.security.client-ip")
data class ClientIpProperties(
    val trustedProxies: List<String> = emptyList(),
) {
    /** The parsed entries; parsing them at binding time is what refuses a bad entry at startup. */
    val trustedRanges: List<IpRange> = trustedProxies.map(IpRange::parse)
}
