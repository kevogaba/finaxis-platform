package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.common.web.ratelimit.RateLimitFilter
import com.finaxis.platform.iam.application.authorization.EffectivePermissionResolver
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken
import com.finaxis.platform.iam.application.port.outbound.AppPrincipalLookup
import com.finaxis.platform.iam.domain.MembershipStatus
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.allowsLogin
import com.finaxis.platform.lifecycle.UserFirstLoginActivation
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.security.access.AccessDeniedException
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.AuthenticationException
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.AuthenticationEntryPoint
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.access.AccessDeniedHandler
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
    private val apiDocsProperties: ApiDocsProperties,
    private val problemWriter: ApiProblemWriter,
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
                    ?.let { configuredContentSecurityPolicy ->
                        // A configured CSP this strict (production's default is `default-src
                        // 'none'`) blocks Scalar outright: its HTML loads a same-origin JS bundle
                        // fine under 'self', but also carries an inline
                        // `<script>Scalar.createApiReference(...)</script>` initializer with no
                        // nonce hook (see ScalarHtmlRenderer in com.scalar.maven:scalar-core) and
                        // fetches the OpenAPI document itself, which default-src 'none' also
                        // blocks. Exposing the docs publicly therefore trades the operator's
                        // configured CSP for one scoped to what Scalar needs, everywhere - not
                        // just on `/scalar/**` - which is the honest cost of "docs are public" and
                        // is why this is documented in production-hardening.md rather than done
                        // silently.
                        val contentSecurityPolicy =
                            if (apiDocsProperties.publicAccessEnabled) {
                                DOCS_CONTENT_SECURITY_POLICY
                            } else {
                                configuredContentSecurityPolicy
                            }
                        headers.contentSecurityPolicy { csp ->
                            csp.policyDirectives(contentSecurityPolicy)
                        }
                    }
            }.sessionManagement { sessions ->
                sessions.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
            }.authorizeHttpRequests { requests ->
                requests
                    .requestMatchers(*publicPaths().toTypedArray())
                    .permitAll()
                    .anyRequest()
                    .authenticated()
            }.oauth2ResourceServer { resourceServer -> resourceServer.jwt { } }
            .exceptionHandling { exceptions ->
                exceptions
                    .authenticationEntryPoint(ApiAuthenticationEntryPoint(problemWriter))
                    .accessDeniedHandler(ApiAccessDeniedHandler(problemWriter))
            }.addFilterAfter(activeOrganisationFilter, BearerTokenAuthenticationFilter::class.java)
            .addFilterAfter(rateLimitFilter, ActiveOrganisationContextFilter::class.java)
        return http.build()
    }

    /**
     * Paths reachable without authentication: `/actuator/health` always (Coolify's deploy health
     * check), plus the Scalar UI and OpenAPI document only while
     * [ApiDocsProperties.publicAccessEnabled] is true. When false, those three paths simply fall
     * through to `anyRequest().authenticated()` instead of being permitted.
     */
    private fun publicPaths(): List<String> =
        buildList {
            add("/actuator/health")
            if (apiDocsProperties.publicAccessEnabled) {
                add("/scalar/**")
                add("/v3/api-docs/**")
                add("/swagger-ui/**")
            }
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
                        exposedHeaders = corsProperties.exposedHeaders
                        allowCredentials = corsProperties.allowCredentials
                    },
                )
            }
        }

    private companion object {
        // 'unsafe-inline' on script-src and style-src is required for Scalar's own HTML - not a
        // choice this codebase gets to avoid, see the comment where this constant is used.
        const val DOCS_CONTENT_SECURITY_POLICY =
            "default-src 'none'; script-src 'self' 'unsafe-inline'; " +
                "style-src 'self' 'unsafe-inline'; connect-src 'self'; " +
                "img-src 'self' data:; font-src 'self' data:"
    }
}

/** Writes unauthenticated Spring Security failures through the public API problem contract. */
class ApiAuthenticationEntryPoint(
    private val problemWriter: ApiProblemWriter,
) : AuthenticationEntryPoint {
    override fun commence(
        request: HttpServletRequest,
        response: HttpServletResponse,
        authException: AuthenticationException,
    ) {
        problemWriter.write(
            request,
            response,
            HttpStatus.UNAUTHORIZED,
            "authentication_required",
            "Authentication is required.",
        )
    }
}

/** Writes unauthorized Spring Security failures through the public API problem contract. */
class ApiAccessDeniedHandler(
    private val problemWriter: ApiProblemWriter,
) : AccessDeniedHandler {
    override fun handle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        accessDeniedException: AccessDeniedException,
    ) {
        problemWriter.write(
            request,
            response,
            HttpStatus.FORBIDDEN,
            "access_denied",
            "Access is denied.",
        )
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
                        user.status.allowsLogin() &&
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
}

/**
 * Servlet filter that upgrades Keycloak JWT authentication to an application principal when
 * context exists.
 */
@Service
class ActiveOrganisationContextFilter(
    private val contextResolver: ActiveOrganisationContextResolver,
    private val principalLoader: AppPrincipalLoader,
    private val problemWriter: ApiProblemWriter,
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
        return resolution.failureMessage?.let { forbidden(request, response) }
            ?: resolution.context?.let { context ->
                authentication.token.subject?.let { subject ->
                    principalLoader.load(subject, context)?.let { principal ->
                        SecurityContextHolder.getContext().authentication =
                            AppPrincipalAuthenticationToken(principal)
                        true
                    } ?: forbidden(request, response)
                } ?: forbidden(request, response)
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
    ): RequestContext {
        // The platform's request id is whatever ApiProblemFactory resolves - the client header
        // when one was sent, otherwise a generated id cached on the request attribute. Resolving
        // it here (rather than reading the raw header) is what makes the id recorded on a posting
        // the same one returned in the response header, the access log, and any problem document,
        // including for the majority of callers that send no X-Request-Id at all. This filter runs
        // inside the security chain and therefore before HttpAccessLogFilter, so this call is
        // usually the one that generates the id; the attribute cache stops a second one appearing.
        val requestId = ApiProblemFactory.requestId(request)
        return RequestContext(
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
                    requestId,
                    request.getHeader(CORRELATION_ID_HEADER) ?: requestId,
                ),
            userAgent = request.getHeader(USER_AGENT_HEADER),
        )
    }

    private fun forbidden(
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): Boolean {
        problemWriter.writeForbiddenTenantContext(request, response)
        return false
    }

    private companion object {
        const val CORRELATION_ID_HEADER = "X-Correlation-Id"
        const val USER_AGENT_HEADER = "User-Agent"
    }
}
