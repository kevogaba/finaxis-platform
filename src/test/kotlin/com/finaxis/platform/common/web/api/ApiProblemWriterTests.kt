package com.finaxis.platform.common.web.api

import org.junit.jupiter.api.Test
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ApiProblemWriterTests {
    @Test
    fun `writer returns a correlated RFC problem response`() {
        val request = MockHttpServletRequest("GET", "/api/v1/auth/me")
        val response = MockHttpServletResponse()

        ApiProblemWriter(
            ApiProblemFactory(),
            ApiJsonCodec(),
        ).writeForbiddenTenantContext(
            request,
            response,
        )

        assertEquals(403, response.status)
        assertTrue(
            requireNotNull(
                response.contentType,
            ).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE),
        )
        assertEquals(
            response.getHeader("X-Request-Id"),
            request.getAttribute(ApiProblemFactory.REQUEST_ID_ATTRIBUTE),
        )
        assertTrue(response.contentAsString.contains("invalid_active_tenant_context"))
        assertTrue(
            response.contentAsString.contains("Active tenant context is invalid or unavailable."),
        )
    }
}
