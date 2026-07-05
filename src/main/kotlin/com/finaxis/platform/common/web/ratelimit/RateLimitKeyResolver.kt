package com.finaxis.platform.common.web.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

/**
 * Resolves stable distributed rate-limit keys from authenticated or anonymous request identity.
 */
open class RateLimitKeyResolver {
    /**
     * Resolves the request identity and associated token-bucket policy.
     */
    open fun resolve(
        request: HttpServletRequest,
        properties: RateLimitProperties,
    ): RateLimitIdentity {
        val authentication = SecurityContextHolder.getContext().authentication
        val authenticatedKey = authentication?.authenticatedRateLimitKey()
        return if (authenticatedKey == null) {
            RateLimitIdentity(
                key = "rate-limit:anon:${request.remoteAddr}",
                policy = properties.anonymous,
                authenticated = false,
            )
        } else {
            RateLimitIdentity(
                key = authenticatedKey,
                policy = properties.authenticated,
                authenticated = true,
            )
        }
    }

    private fun Authentication.authenticatedRateLimitKey(): String? {
        if (!isAuthenticated) {
            return null
        }
        val principal = principal
        return when (principal) {
            is RateLimitPrincipal -> {
                "rate-limit:auth:${principal.rateLimitTenantId ?: GLOBAL_TENANT}:${principal.rateLimitUserId}"
            }

            is Jwt -> {
                principal.subject?.let { subject -> "rate-limit:auth:global:$subject" }
            }

            else -> {
                (this as? JwtAuthenticationToken)
                    ?.token
                    ?.subject
                    ?.let { subject -> "rate-limit:auth:global:$subject" }
            }
        }
    }

    private companion object {
        private const val GLOBAL_TENANT = "global"
    }
}
