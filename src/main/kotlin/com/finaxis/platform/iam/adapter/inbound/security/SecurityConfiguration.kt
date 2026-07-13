package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.web.ratelimit.RateLimitFilter
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.UserStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import org.springframework.stereotype.Service
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Spring Security MVC configuration for Keycloak JWT authentication.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfiguration(
    private val activeOrganisationFilter: ActiveOrganisationContextFilter,
    private val rateLimitFilter: RateLimitFilter,
) {
    /**
     * Builds the servlet security filter chain for JWT authentication and method security.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { csrf -> csrf.disable() }
            .sessionManagement { sessions ->
                sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            }.authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(
                        "/actuator/health",
                        "/docs/**",
                        "/v3/api-docs/**",
                        "/swagger-ui/**",
                    ).permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { resourceServer -> resourceServer.jwt { } }
            .addFilterAfter(activeOrganisationFilter, BearerTokenAuthenticationFilter::class.java)
            .addFilterAfter(rateLimitFilter, ActiveOrganisationContextFilter::class.java)
        return http.build()
    }
}

/**
 * Loads the application principal for a verified Keycloak subject and active tenant context.
 */
@Service
class AppPrincipalLoader(
    private val principalLookup: AppPrincipalLookup,
    private val resolver: EffectivePermissionResolver,
) {
    /**
     * Loads the application principal for a matching Keycloak subject and tenant context.
     */
    fun load(
        keycloakSubject: String,
        context: ActiveOrganisationContext,
    ): AppPrincipal? =
        principalLookup
            .findPrincipalUserByKeycloakSubject(keycloakSubject)
            ?.takeIf { it.id == context.userId }
            ?.let { user ->
                principalLookup
                    .findPrincipalMembershipById(context.membershipId)
                    ?.takeIf {
                        it.userId == user.id && it.organisationId == context.organisationId
                    }?.takeIf {
                        user.status == UserStatus.ACTIVE && it.status == MembershipStatus.ACTIVE
                    }?.let { membership ->
                        AppPrincipal(
                            userId = user.id,
                            keycloakSubject = user.keycloakSubject,
                            organisationId = membership.organisationId,
                            membershipId = membership.id,
                            branchId = context.branchId,
                            email = user.email,
                            fullName = user.fullName,
                            permissions =
                                resolver.effectivePermissions(membership.id, context.branchId),
                        )
                    }
            }
}

/**
 * Servlet filter that upgrades Keycloak JWT authentication to an application principal when
 * context exists.
 */
@Service
class ActiveOrganisationContextFilter(
    private val contextResolver: ActiveOrganisationContextResolver,
    private val principalLoader: AppPrincipalLoader,
) : OncePerRequestFilter() {
    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        val jwtResolved = resolveJwtAuthentication(request, response)
        if (jwtResolved) {
            installRequestContext(request) { filterChain.doFilter(request, response) }
        }
    }

    private fun resolveJwtAuthentication(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean =
        (SecurityContextHolder.getContext().authentication as? JwtAuthenticationToken)
            ?.let { authentication -> resolveActiveContext(authentication, request, response) }
            ?: true

    private fun resolveActiveContext(
        authentication: JwtAuthenticationToken,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean {
        val resolution = contextResolver.resolve(request)
        return resolution.failureMessage?.let { forbidden(response, it) }
            ?: resolution.context?.let { context ->
                authentication.token.subject?.let { subject ->
                    principalLoader.load(subject, context)?.let { principal ->
                        SecurityContextHolder.getContext().authentication =
                            AppPrincipalAuthenticationToken(principal)
                        true
                    } ?: forbidden(response, "Invalid active organisation context")
                } ?: forbidden(response, "JWT subject is required")
            }
            ?: true
    }

    private fun installRequestContext(
        request: HttpServletRequest,
        action: () -> Unit,
    ) {
        val principal =
            (SecurityContextHolder.getContext().authentication as? AppPrincipalAuthenticationToken)
                ?.principal
        if (principal == null) {
            RequestContexts.clear()
            try {
                action()
            } finally {
                RequestContexts.clear()
            }
            return
        }
        RequestContexts.with(requestContext(principal, request), action)
    }

    private fun requestContext(
        principal: AppPrincipal,
        request: HttpServletRequest,
    ): RequestContext =
        RequestContext(
            tenant = TenantContext(principal.organisationId),
            branch = principal.branchId?.let(::BranchContext),
            actor =
                ActorContext(
                    principal.userId,
                    principal.keycloakSubject,
                    principal.fullName,
                    principal.email,
                ),
            correlation =
                CorrelationContext(
                    request.getHeader(REQUEST_ID_HEADER),
                    request.getHeader(CORRELATION_ID_HEADER)
                        ?: request.getHeader(REQUEST_ID_HEADER),
                ),
        )

    private fun forbidden(
        response: HttpServletResponse,
        message: String,
    ): Boolean {
        response.sendError(HttpServletResponse.SC_FORBIDDEN, message)
        return false
    }

    private companion object {
        const val REQUEST_ID_HEADER = "X-Request-Id"
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
    }
}
