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
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.UserStatus
import com.finaxis.platform.lifecycle.UserFirstLoginActivation
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
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy
import org.springframework.stereotype.Service
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
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
    private val corsProperties: CorsProperties,
    private val securityHeadersProperties: SecurityHeadersProperties,
) {
    /**
     * Builds the servlet security filter chain for JWT authentication and method security.
     */
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            // See docs/security/production-hardening.md: JWTs authenticate every request; the
            // Redis session carries active-organisation context only, never authentication.
            .csrf { csrf -> csrf.disable() }
            .cors { cors -> cors.configurationSource(corsConfigurationSource()) }
            .headers { headers ->
                headers
                    .contentTypeOptions { }
                    .frameOptions { frameOptions -> frameOptions.deny() }
                    .referrerPolicy { policy ->
                        policy.policy(ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)
                    }.httpStrictTransportSecurity { hsts ->
                        if (!securityHeadersProperties.hstsEnabled) {
                            hsts.disable()
                        }
                    }
                securityHeadersProperties.contentSecurityPolicy
                    .takeIf(String::isNotBlank)
                    ?.let { contentSecurityPolicy ->
                        headers.contentSecurityPolicy { csp ->
                            csp.policyDirectives(contentSecurityPolicy)
                        }
                    }
            }.sessionManagement { sessions ->
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

    /**
     * Provides no registered CORS mapping until a deployment explicitly enables one.
     */
    @Bean
    fun corsConfigurationSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().also { source ->
            if (corsProperties.enabled) {
                source.registerCorsConfiguration(
                    "/**",
                    CorsConfiguration().apply {
                        allowedOrigins = corsProperties.allowedOrigins
                        allowedMethods = corsProperties.allowedMethods
                        allowedHeaders = corsProperties.allowedHeaders
                        allowCredentials = corsProperties.allowCredentials
                    },
                )
            }
        }
}

/**
 * Loads the application principal for a verified Keycloak subject and active tenant context.
 */
@Service
class AppPrincipalLoader(
    private val principalLookup: AppPrincipalLookup,
    private val resolver: EffectivePermissionResolver,
    private val userFirstLoginActivation: UserFirstLoginActivation,
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
                        user.status in LOGIN_ALLOWED_USER_STATUSES &&
                            it.status == MembershipStatus.ACTIVE
                    }?.takeIf {
                        principalLookup.organisationStatus(it.organisationId) ==
                            OrganisationStatus.ACTIVE
                    }?.takeIf { membership ->
                        context.branchId?.let { branchId ->
                            principalLookup.hasActiveAssignedBranch(
                                membership.id,
                                membership.organisationId,
                                branchId,
                            )
                        } ?: true
                    }?.let { membership ->
                        userFirstLoginActivation.activateOnFirstLogin(
                            user.id,
                            membership.organisationId,
                        )
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

    private companion object {
        val LOGIN_ALLOWED_USER_STATUSES = setOf(UserStatus.ACTIVE, UserStatus.INVITED)
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
