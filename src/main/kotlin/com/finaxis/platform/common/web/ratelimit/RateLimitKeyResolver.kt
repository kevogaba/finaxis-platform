package com.finaxis.platform.common.web.ratelimit

import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.util.AntPathMatcher

/**
 * Resolves stable distributed rate-limit keys from authenticated or anonymous request identity.
 */
open class RateLimitKeyResolver {
    private val pathMatcher = AntPathMatcher()

    /**
     * Resolves the request identity and associated token-bucket policy.
     */
    open fun resolve(
        request: HttpServletRequest,
        properties: RateLimitProperties,
    ): RateLimitResolution {
        val path = request.servletPath.ifBlank { request.requestURI }
        val policyId =
            resolvePolicyId(request, properties)
                ?: return NoRateLimitPolicy(request.method, path)
        val policy = properties.policies.getValue(policyId)
        val authentication = SecurityContextHolder.getContext().authentication
        val authenticatedKey = authentication?.authenticatedRateLimitKey()
        return if (authenticatedKey == null) {
            RateLimitIdentity(
                key = "rate-limit:${policyId.externalId}:anon:${request.remoteAddr}",
                policyId = policyId,
                policy = policy,
                authenticated = false,
            )
        } else {
            RateLimitIdentity(
                key = "rate-limit:${policyId.externalId}:$authenticatedKey",
                policyId = policyId,
                policy = policy,
                authenticated = true,
            )
        }
    }

    private fun resolvePolicyId(
        request: HttpServletRequest,
        properties: RateLimitProperties,
    ): RateLimitPolicyId? {
        val path = request.servletPath.ifBlank { request.requestURI }
        return properties.paths.rules
            .firstOrNull { rule ->
                rule.method.equals(request.method, ignoreCase = true) &&
                    pathMatcher.match(rule.path, path)
            }?.policy
    }

    private fun Authentication.authenticatedRateLimitKey(): String? {
        if (!isAuthenticated) {
            return null
        }
        val principal = principal
        return when (principal) {
            is RateLimitPrincipal -> {
                "auth:${principal.rateLimitTenantId ?: GLOBAL_TENANT}:${principal.rateLimitUserId}"
            }

            is Jwt -> {
                principal.subject?.let { subject -> "auth:global:$subject" }
            }

            else -> {
                (this as? JwtAuthenticationToken)
                    ?.token
                    ?.subject
                    ?.let { subject -> "auth:global:$subject" }
            }
        }
    }

    private companion object {
        private const val GLOBAL_TENANT = "global"
    }
}
