package com.finaxis.platform.common.web.ratelimit

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.iam.application.context.AppPrincipal
import org.junit.jupiter.api.Test
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
                mapOf(
                    "platform-read" to RateLimitPolicy(1, 1, Duration.ofMinutes(1)),
                    "tenant-read" to RateLimitPolicy(2, 2, Duration.ofMinutes(1)),
                ),
            paths =
                RateLimitPathProperties(
                    rules =
                        listOf(
                            RateLimitPathRule("GET", "/api/v1/auth/me", "platform-read"),
                            RateLimitPathRule("GET", "/api/v1/**", "tenant-read"),
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
            )

        assertEquals("tenant-read", identity.policyId)
        assertEquals(
            "rate-limit:tenant-read:auth:${principal.organisationId}:${principal.userId}",
            identity.key,
        )
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
}
