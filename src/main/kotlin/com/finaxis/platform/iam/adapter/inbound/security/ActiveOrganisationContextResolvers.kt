package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.ActiveOrganisationContextService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Service

/**
 * Result of resolving the active tenant context from an HTTP request.
 */
data class ActiveOrganisationContextResolution(
    val context: ActiveOrganisationContext? = null,
    val failureMessage: String? = null,
)

/**
 * Inbound security adapter contract for resolving active tenant context.
 */
interface ActiveOrganisationContextResolver {
    fun resolve(request: HttpServletRequest): ActiveOrganisationContextResolution
}

/**
 * Resolves tenant context from the explicit headless-client header.
 */
@Service
class HeaderActiveOrganisationContextResolver(
    private val contextService: ActiveOrganisationContextService,
) : ActiveOrganisationContextResolver {
    override fun resolve(request: HttpServletRequest): ActiveOrganisationContextResolution {
        val token = request.getHeader(ActiveOrganisationContextService.HEADER)
        if (token.isNullOrBlank()) {
            return ActiveOrganisationContextResolution()
        }

        return contextService.verify(token)?.let(::ActiveOrganisationContextResolution)
            ?: ActiveOrganisationContextResolution(failureMessage = "Invalid active organisation context header")
    }
}

/**
 * Resolves tenant context from the Redis-backed browser HTTP session.
 */
@Service
class SessionActiveOrganisationContextResolver : ActiveOrganisationContextResolver {
    override fun resolve(request: HttpServletRequest): ActiveOrganisationContextResolution {
        val context = request.getSession(false)?.getAttribute(ATTRIBUTE) as? ActiveOrganisationContext
        return ActiveOrganisationContextResolution(context)
    }

    companion object {
        const val ATTRIBUTE = "iam.activeOrganisationContext"
    }
}

/**
 * Resolves request context with strict transport precedence: header first,
 * session second, and invalid headers fail closed.
 */
@Primary
@Service
class CompositeActiveOrganisationContextResolver(
    private val headerResolver: HeaderActiveOrganisationContextResolver,
    private val sessionResolver: SessionActiveOrganisationContextResolver,
) : ActiveOrganisationContextResolver {
    override fun resolve(request: HttpServletRequest): ActiveOrganisationContextResolution {
        val headerResolution = headerResolver.resolve(request)
        if (headerResolution.failureMessage != null || headerResolution.context != null) {
            return headerResolution
        }
        return sessionResolver.resolve(request)
    }
}
