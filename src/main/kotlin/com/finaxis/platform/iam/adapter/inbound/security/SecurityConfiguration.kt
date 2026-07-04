package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
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
    ): AppPrincipal? {
        val user =
            principalLookup
                .findPrincipalUserByKeycloakSubject(keycloakSubject)
                ?.takeIf { it.id == context.userId }
                ?: return null
        val membership =
            principalLookup
                .findPrincipalMembershipById(context.membershipId)
                ?.takeIf { it.userId == user.id && it.organisationId == context.organisationId }
                ?: return null

        return AppPrincipal(
            userId = user.id,
            keycloakSubject = user.keycloakSubject,
            organisationId = membership.organisationId,
            membershipId = membership.id,
            branchId = context.branchId,
            email = user.email,
            fullName = user.fullName,
            permissions = resolver.effectivePermissions(membership.id),
        )
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
        val authentication = SecurityContextHolder.getContext().authentication

        if (authentication is JwtAuthenticationToken) {
            val resolution = contextResolver.resolve(request)
            if (resolution.failureMessage != null) {
                response.sendError(HttpServletResponse.SC_FORBIDDEN, resolution.failureMessage)
                return
            }

            val context = resolution.context
            if (context != null) {
                val subject = authentication.token.subject
                if (subject == null) {
                    response.sendError(HttpServletResponse.SC_FORBIDDEN, "JWT subject is required")
                    return
                }
                val principal = principalLoader.load(subject, context)
                if (principal == null) {
                    response.sendError(
                        HttpServletResponse.SC_FORBIDDEN,
                        "Invalid active organisation context",
                    )
                    return
                }
                SecurityContextHolder.getContext().authentication =
                    AppPrincipalAuthenticationToken(principal)
            }
        }

        filterChain.doFilter(request, response)
    }
}
