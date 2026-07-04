package com.finaxis.platform.iam.application.context

import java.util.UUID
import org.springframework.security.authentication.AbstractAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority

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
)

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
}
