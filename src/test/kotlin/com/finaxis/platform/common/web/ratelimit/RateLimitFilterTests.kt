package com.finaxis.platform.common.web.ratelimit

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.iam.application.context.AppPrincipal
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource
import org.springframework.mock.web.MockFilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RateLimitFilterTests {
    private val properties =
        RateLimitProperties(
            policies =
                testPolicies(
                    platformRead = RateLimitPolicy(1, 1, Duration.ofMinutes(1)),
                    tenantRead = RateLimitPolicy(2, 2, Duration.ofMinutes(1)),
                ),
            paths =
                RateLimitPathProperties(
                    rules =
                        listOf(
                            RateLimitPathRule(
                                "GET",
                                "/api/v1/auth/me",
                                RateLimitPolicyId.PLATFORM_READ,
                            ),
                            RateLimitPathRule(
                                "GET",
                                "/api/v1/**",
                                RateLimitPolicyId.TENANT_READ,
                            ),
                        ),
                ),
        )
    private val limiter = InMemoryRateLimiterService(properties)
    private val filter =
        RateLimitFilter(
            properties = properties,
            keyResolver = RateLimitKeyResolver(),
            rateLimiter = limiter,
            clock = Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC),
            problemWriter = ApiProblemWriter(ApiProblemFactory(), ApiJsonCodec()),
        )

    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `anonymous requests use anonymous limit and return rate headers`() {
        val first = performRequest("/api/v1/auth/me")
        val second = performRequest("/api/v1/auth/me")

        assertEquals(200, first.status)
        assertEquals(429, second.status)
        assertEquals("1", second.getHeader("RateLimit-Limit"))
        assertEquals("0", second.getHeader("RateLimit-Remaining"))
        assertTrue(!second.getHeader("Retry-After").isNullOrBlank())
        assertProblem(second, "rate_limit_exceeded", "request-123")
    }

    @Test
    fun `authenticated requests use authenticated principal key`() {
        SecurityContextHolder.getContext().authentication =
            com.finaxis.platform.iam.application.context.AppPrincipalAuthenticationToken(
                AppPrincipal(
                    userId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                    keycloakSubject = "subject",
                    organisationId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    membershipId = uuidV7(),
                    branchId = null,
                    email = "user@example.com",
                    fullName = "User",
                    permissions = emptySet(),
                ),
            )

        assertEquals(200, performRequest("/api/v1/records").status)
        assertEquals(200, performRequest("/api/v1/records").status)
        assertEquals(429, performRequest("/api/v1/records").status)
    }

    @Test
    fun `excluded paths are not rate limited`() {
        assertEquals(200, performRequest("/actuator/health").status)
        assertEquals(200, performRequest("/actuator/health").status)
    }

    @Test
    fun `unavailable limiter returns shared problem when fail closed`() {
        val unavailable =
            RateLimitFilter(
                properties = properties.copy(failOpen = false),
                keyResolver = RateLimitKeyResolver(),
                rateLimiter =
                    RateLimiterService { _, _ ->
                        throw RateLimitUnavailableException(IllegalStateException("redis"))
                    },
                clock = Clock.systemUTC(),
                problemWriter = ApiProblemWriter(ApiProblemFactory(), ApiJsonCodec()),
            )
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        request.addHeader("X-Request-Id", "request-123")
        val response = MockHttpServletResponse()

        unavailable.doFilter(request, response, MockFilterChain())

        assertProblem(response, "rate_limiter_unavailable", "request-123")
    }

    @Test
    fun `distributed key includes named policy tenant and authenticated identity`() {
        val principal =
            AppPrincipal(
                userId = UUID.fromString("11111111-1111-1111-1111-111111111111"),
                keycloakSubject = "subject",
                organisationId = UUID.fromString("22222222-2222-2222-2222-222222222222"),
                membershipId = uuidV7(),
                branchId = null,
                email = "user@example.com",
                fullName = "User",
                permissions = emptySet(),
            )
        SecurityContextHolder.getContext().authentication =
            com.finaxis.platform.iam.application.context
                .AppPrincipalAuthenticationToken(principal)

        val identity =
            RateLimitKeyResolver().resolve(
                MockHttpServletRequest("GET", "/api/v1/records"),
                properties,
            ) as RateLimitIdentity

        assertEquals(RateLimitPolicyId.TENANT_READ, identity.policyId)
        assertEquals(
            "rate-limit:tenant-read:auth:${principal.organisationId}:${principal.userId}",
            identity.key,
        )
    }

    @ParameterizedTest
    @MethodSource("policyRoutes")
    fun `ordered allowlist selects every named policy`(
        method: String,
        path: String,
        expected: RateLimitPolicyId,
    ) {
        val resolution =
            RateLimitKeyResolver().resolve(
                MockHttpServletRequest(method, path),
                RateLimitProperties(),
            )

        val identity = resolution as RateLimitIdentity
        assertEquals(expected, identity.policyId)
    }

    @Test
    fun `specific auth selection rule wins before platform command catch all`() {
        val identity =
            RateLimitKeyResolver().resolve(
                MockHttpServletRequest("POST", "/api/v1/auth/select-organisation"),
                RateLimitProperties(),
            ) as RateLimitIdentity

        assertEquals(RateLimitPolicyId.AUTH_SELECTION, identity.policyId)
    }

    @Test
    fun `unmatched business request fails closed with safe no policy problem`() {
        val request = MockHttpServletRequest("TRACE", "/api/v1/records")
        request.addHeader("X-Request-Id", "request-123")
        val response = MockHttpServletResponse()

        filter.doFilter(request, response, MockFilterChain())

        assertProblem(response, "rate_limit_policy_unavailable", "request-123")
    }

    @Test
    fun `options request bypasses business rate limiting`() {
        val response = MockHttpServletResponse()

        filter.doFilter(
            MockHttpServletRequest("OPTIONS", "/api/v1/records"),
            response,
            MockFilterChain(),
        )

        assertEquals(200, response.status)
    }

    private fun performRequest(path: String): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", path)
        request.addHeader("X-Request-Id", "request-123")
        request.remoteAddr = "203.0.113.10"
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }

    private fun assertProblem(
        response: MockHttpServletResponse,
        code: String,
        requestId: String,
    ) {
        assertEquals(429, response.status)
        assertTrue(requireNotNull(response.contentType).startsWith("application/problem+json"))
        assertEquals(requestId, response.getHeader("X-Request-Id"))
        assertTrue(response.contentAsString.contains("\"code\":\"$code\""))
        assertTrue(response.contentAsString.contains("\"request_id\":\"$requestId\""))
    }

    companion object {
        @JvmStatic
        fun policyRoutes() =
            listOf(
                Arguments.of(
                    "POST",
                    "/api/v1/auth/select-branch",
                    RateLimitPolicyId.AUTH_SELECTION,
                ),
                Arguments.of("GET", "/api/v1/auth/me", RateLimitPolicyId.PLATFORM_READ),
                Arguments.of("POST", "/api/v1/auth/refresh", RateLimitPolicyId.PLATFORM_COMMAND),
                Arguments.of("GET", "/api/v1/records", RateLimitPolicyId.TENANT_READ),
                Arguments.of("POST", "/api/v1/records", RateLimitPolicyId.TENANT_COMMAND),
                Arguments.of("PUT", "/api/v1/records/1", RateLimitPolicyId.TENANT_COMMAND),
                Arguments.of("PATCH", "/api/v1/records/1", RateLimitPolicyId.TENANT_COMMAND),
                Arguments.of("DELETE", "/api/v1/records/1", RateLimitPolicyId.TENANT_COMMAND),
            )
    }
}

private fun testPolicies(
    platformRead: RateLimitPolicy = RateLimitPolicy(),
    tenantRead: RateLimitPolicy = RateLimitPolicy(),
): Map<RateLimitPolicyId, RateLimitPolicy> =
    mapOf(
        RateLimitPolicyId.AUTH_SELECTION to RateLimitPolicy(),
        RateLimitPolicyId.PLATFORM_READ to platformRead,
        RateLimitPolicyId.PLATFORM_COMMAND to RateLimitPolicy(),
        RateLimitPolicyId.TENANT_READ to tenantRead,
        RateLimitPolicyId.TENANT_COMMAND to RateLimitPolicy(),
    )
