package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.web.api.ApiJsonCodec
import com.finaxis.platform.common.web.api.ApiProblemFactory
import com.finaxis.platform.common.web.api.ApiProblemWriter
import com.finaxis.platform.iam.application.context.ActiveOrganisationContext
import com.finaxis.platform.iam.application.context.AppPrincipal
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.web.firewall.RequestRejectedException
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * #252 at the filter: `ActiveOrganisationContextFilter` carries a client `X-Correlation-Id` only
 * in the shape `X-Request-Id` must take, both in the metadata made ambient while the principal
 * loads (which first-login audit rows record) and in the principal's own request context; and the
 * firewall's rejection is written as a problem that names nothing from the request, not even a
 * refused path, and never appended to a body the controller already wrote.
 */
class CorrelationIdFilterTests {
    @AfterTest
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    @Test
    fun `a good correlation id is carried while the principal loads and afterwards`() {
        val (loading, installed) = contexts(correlationHeader = "saga.step-0042")

        assertEquals("saga.step-0042", loading.correlation?.correlationId)
        assertEquals("saga.step-0042", installed.correlation?.correlationId)
        assertEquals("client-request", loading.correlation?.requestId)
        assertEquals("client-request", installed.correlation?.requestId)
    }

    @Test
    fun `a rejected correlation id is replaced by the request id before and after loading`() {
        listOf("saga-1", "has spaces in it", "a".repeat(500), "sága-ünicode").forEach { hostile ->
            val (loading, installed) = contexts(correlationHeader = hostile)

            // The first-login audit rows written while the principal loads take their correlation
            // id from the ambient metadata, so it must never be the raw header either.
            assertEquals("client-request", loading.correlation?.correlationId)
            assertEquals("client-request", installed.correlation?.correlationId)
            assertEquals("client-request", installed.correlation?.requestId)
        }
    }

    @Test
    fun `request rejected handler writes a problem with a generated request id`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        request.addHeader("X-Request-Id", "forged-id\r\nX-Injected: yes")
        val response = MockHttpServletResponse()

        ApiRequestRejectedHandler(problemWriter()).handle(
            request,
            response,
            RequestRejectedException("forged-id header detail"),
        )

        assertEquals(400, response.status)
        assertTrue(requireNotNull(response.contentType).startsWith("application/problem+json"))
        val requestId = requireNotNull(response.getHeader("X-Request-Id"))
        assertEquals(UUID_V7, UUID.fromString(requestId).version())
        assertTrue(response.contentAsString.contains("\"code\":\"request_rejected\""))
        assertTrue(response.contentAsString.contains("\"request_id\":\"$requestId\""))
        assertFalse(response.contentAsString.contains("forged"))
    }

    @Test
    fun `request rejected handler never echoes a refused path and writes the hardening headers`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me;evil=reflected-segment")
        val response = MockHttpServletResponse()

        ApiRequestRejectedHandler(problemWriter()).handle(
            request,
            response,
            RequestRejectedException("The request was rejected because the URL contained ;"),
        )

        assertEquals(400, response.status)
        assertTrue(response.contentAsString.contains("\"instance\":\"about:blank\""))
        assertFalse(response.contentAsString.contains("reflected"))
        assertFalse(response.contentAsString.contains(";"))
        assertEquals("nosniff", response.getHeader("X-Content-Type-Options"))
        assertEquals("DENY", response.getHeader("X-Frame-Options"))
        assertEquals(
            ApiRequestRejectedHandler.CONTENT_SECURITY_POLICY,
            response.getHeader("Content-Security-Policy"),
        )
        assertTrue(requireNotNull(response.getHeader("Cache-Control")).contains("no-store"))
        assertEquals(null, response.getHeader("Strict-Transport-Security"))
    }

    @Test
    fun `request rejected handler writes HSTS on a secure request only when it is enabled`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        request.isSecure = true
        val enabled = MockHttpServletResponse()
        val disabled = MockHttpServletResponse()

        ApiRequestRejectedHandler(problemWriter(), hstsEnabled = true)
            .handle(request, enabled, RequestRejectedException("rejected"))
        ApiRequestRejectedHandler(problemWriter(), hstsEnabled = false)
            .handle(request, disabled, RequestRejectedException("rejected"))

        assertTrue(enabled.getHeader("Strict-Transport-Security") != null)
        assertEquals(null, disabled.getHeader("Strict-Transport-Security"))
    }

    @Test
    fun `a late rejection discards the buffered body so only the problem is sent`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        response.status = 200
        response.contentType = "application/json"
        response.outputStream.write("{\"status\":\"UP\"}".toByteArray())

        ApiRequestRejectedHandler(problemWriter())
            .handle(request, response, RequestRejectedException("late header"))

        assertEquals(400, response.status)
        val body = response.contentAsString
        assertTrue(body.startsWith("{\"type\":\"urn:finaxis:problem:request_rejected\""), body)
        assertFalse(body.contains("UP"), body)
    }

    @Test
    fun `a rejection after the response was committed leaves it untouched`() {
        val request = MockHttpServletRequest("GET", "/actuator/health")
        val response = MockHttpServletResponse()
        response.status = 200
        response.outputStream.write("{\"status\":\"UP\"}".toByteArray())
        response.flushBuffer()

        ApiRequestRejectedHandler(problemWriter())
            .handle(request, response, RequestRejectedException("late header"))

        assertEquals(200, response.status)
        assertEquals("{\"status\":\"UP\"}", response.contentAsString)
        assertEquals(null, response.getHeader("X-Request-Id"))
    }

    @Test
    fun `a rejection answers an allowed origin with the configured cors headers`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me;evil=reflected-segment")
        request.addHeader("Origin", ALLOWED_ORIGIN)
        val response = MockHttpServletResponse()

        ApiRequestRejectedHandler(problemWriter(), corsConfigurationSource = corsSource())
            .handle(request, response, RequestRejectedException("rejected"))

        assertEquals(400, response.status)
        assertTrue(response.contentAsString.contains("\"code\":\"request_rejected\""))
        assertEquals(ALLOWED_ORIGIN, response.getHeader("Access-Control-Allow-Origin"))
        assertEquals("true", response.getHeader("Access-Control-Allow-Credentials"))
        assertTrue(response.getHeaders("Vary").contains("Origin"))
        assertFalse(response.contentAsString.contains("reflected"))
    }

    @Test
    fun `a rejection gives an origin outside the list no cors headers and keeps the problem`() {
        listOf("https://evil.example", "not a valid origin").forEach { origin ->
            val request = MockHttpServletRequest("GET", "/api//v1/auth/me")
            request.addHeader("Origin", origin)
            val response = MockHttpServletResponse()

            ApiRequestRejectedHandler(problemWriter(), corsConfigurationSource = corsSource())
                .handle(request, response, RequestRejectedException("rejected"))

            // Never the processor's own 403 "Invalid CORS request": the rejection's answer is
            // the same problem with or without an Origin, only the CORS headers differ.
            assertEquals(400, response.status)
            assertTrue(response.contentAsString.contains("\"code\":\"request_rejected\""))
            assertFalse(response.contentAsString.contains("Invalid CORS"))
            assertTrue(
                response.headerNames.none { it.startsWith("Access-Control-") },
                response.headerNames.toString(),
            )
            assertTrue(response.getHeaders("Vary").contains("Origin"))
        }
    }

    @Test
    fun `a rejection writes no cors headers when no cors mapping is registered`() {
        val request = MockHttpServletRequest("GET", "/api//v1/auth/me")
        request.addHeader("Origin", ALLOWED_ORIGIN)
        val response = MockHttpServletResponse()

        ApiRequestRejectedHandler(
            problemWriter(),
            corsConfigurationSource = UrlBasedCorsConfigurationSource(),
        ).handle(request, response, RequestRejectedException("rejected"))

        assertEquals(400, response.status)
        assertEquals(null, response.getHeader("Access-Control-Allow-Origin"))
    }

    private fun corsSource(): CorsConfigurationSource =
        UrlBasedCorsConfigurationSource().also { source ->
            source.registerCorsConfiguration(
                "/**",
                CorsConfiguration().apply {
                    allowedOrigins = listOf(ALLOWED_ORIGIN)
                    allowedMethods = listOf("GET", "POST")
                    allowCredentials = true
                },
            )
        }

    /** The context ambient while the principal loads, then the one installed for the chain. */
    private fun contexts(correlationHeader: String): Pair<RequestContext, RequestContext> {
        val contextResolver = mock(ActiveOrganisationContextResolver::class.java)
        val loader = mock(AppPrincipalLoader::class.java)
        val filter =
            ActiveOrganisationContextFilter(
                contextResolver,
                loader,
                problemWriter(),
                ClientIpResolver(ClientIpProperties()),
            )
        val context = ActiveOrganisationContext(uuidV7(), uuidV7(), uuidV7())
        val request = MockHttpServletRequest()
        request.addHeader("X-Request-Id", "client-request")
        request.addHeader("X-Correlation-Id", correlationHeader)
        var loading: RequestContext? = null
        var installed: RequestContext? = null
        SecurityContextHolder.getContext().authentication =
            JwtAuthenticationToken(
                Jwt
                    .withTokenValue("token")
                    .header("alg", "none")
                    .subject("subject")
                    .build(),
            )
        `when`(contextResolver.resolve(request))
            .thenReturn(ActiveOrganisationContextResolution(context))
        `when`(loader.load("subject", context)).thenAnswer {
            loading = RequestContexts.current()
            principal()
        }

        filter.doFilter(request, MockHttpServletResponse()) { _, _ ->
            installed = RequestContexts.current()
        }

        return requireNotNull(loading) to requireNotNull(installed)
    }

    private fun problemWriter(): ApiProblemWriter =
        ApiProblemWriter(ApiProblemFactory(), ApiJsonCodec())

    private fun principal(): AppPrincipal =
        AppPrincipal(
            userId = uuidV7(),
            keycloakSubject = "subject",
            organisationId = uuidV7(),
            membershipId = uuidV7(),
            email = "user@example.com",
            fullName = "Example User",
            permissions = emptySet(),
        )

    private companion object {
        const val UUID_V7 = 7
        const val ALLOWED_ORIGIN = "https://app.finaxis.example"
    }
}
