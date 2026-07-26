package com.finaxis.platform.iam.application.context

import com.finaxis.platform.common.web.idempotency.IdempotencyActorPrincipal
import com.finaxis.platform.common.web.ratelimit.RateLimitPrincipal
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import java.io.Serializable
import java.util.UUID

/**
 * Application principal exposed to controllers and method security after tenant context resolution.
 */
data class AppPrincipal(
    val userId: UUID,
    val keycloakSubject: String,
    val organisationId: UUID,
    val membershipId: UUID,
    val branchId: UUID? = null,
    val email: String?,
    val fullName: String?,
    val permissions: Set<String>,
) : RateLimitPrincipal,
    IdempotencyActorPrincipal,
    Serializable {
    override val idempotencySubject: String
        get() = keycloakSubject
    override val rateLimitUserId: String
        get() = userId.toString()

    override val rateLimitTenantId: String
        get() = organisationId.toString()

    /**
     * Java serialization metadata for Redis-backed Spring Security session storage.
     */
    companion object {
        private const val serialVersionUID = 1L
    }
}

/**
 * Spring Security authentication token backed by an application principal.
 */
class AppPrincipalAuthenticationToken(
    private val appPrincipal: AppPrincipal,
) : AbstractAuthenticationToken(appPrincipal.permissions.map(::SimpleGrantedAuthority)) {
    init {
        isAuthenticated = true
    }

    override fun getCredentials(): Any = ""

    override fun getPrincipal(): AppPrincipal = appPrincipal

    /**
     * Java serialization metadata for Redis-backed Spring Security session storage.
     */
    companion object {
        private const val serialVersionUID = 1L
    }
}
