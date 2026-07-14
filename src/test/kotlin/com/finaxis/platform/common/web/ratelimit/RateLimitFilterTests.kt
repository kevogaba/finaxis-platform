package com.finaxis.platform.common.web.ratelimit

import com.finaxis.platform.common.id.uuidV7
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
            anonymous =
                RateLimitPolicy(
                    capacity = 1,
                    refillTokens = 1,
                    refillPeriod = Duration.ofMinutes(1),
                ),
            authenticated =
                RateLimitPolicy(
                    capacity = 2,
                    refillTokens = 2,
                    refillPeriod = Duration.ofMinutes(1),
                ),
        )
    private val limiter = InMemoryRateLimiterService(properties)
    private val filter =
        RateLimitFilter(
            properties = properties,
            keyResolver = RateLimitKeyResolver(),
            rateLimiter = limiter,
            clock = Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC),
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

        assertEquals(200, performRequest("/api/v1/auth/me").status)
        assertEquals(200, performRequest("/api/v1/auth/me").status)
        assertEquals(429, performRequest("/api/v1/auth/me").status)
    }

    @Test
    fun `excluded paths are not rate limited`() {
        assertEquals(200, performRequest("/actuator/health").status)
        assertEquals(200, performRequest("/actuator/health").status)
    }

    private fun performRequest(path: String): MockHttpServletResponse {
        val request = MockHttpServletRequest("GET", path)
        request.remoteAddr = "203.0.113.10"
        val response = MockHttpServletResponse()
        filter.doFilter(request, response, MockFilterChain())
        return response
    }
}
