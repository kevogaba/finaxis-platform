package com.finaxis.platform.common.web.api

import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.mock.web.MockHttpServletRequest
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class ClientRequestIdsTests {
    @ParameterizedTest
    @ValueSource(
        strings = [
            "abcd1234",
            "request-1",
            "019f7d42-8db9-7ef7-9f5e-53211981bb54",
            "trace.span_01-AZaz09",
            "0123456789012345678901234567890123456789012345678901234567890123",
        ],
    )
    fun `an id of 8 to 64 safe characters is accepted unchanged`(candidate: String) {
        assertEquals(candidate, ClientRequestIds.acceptable(candidate))
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "",
            "        ",
            "1234567",
            "01234567890123456789012345678901234567890123456789012345678901234",
            "request-1\r\nX-Injected: yes",
            "request-1\nforged log line",
            "has spaces in it",
            "réquest-ïd-ünicode",
            "request\u0000null-byte",
            "request/with/slashes",
            "request:colon:id",
            "\"quoted-request\"",
            "request-id\t",
        ],
    )
    fun `anything else is rejected`(candidate: String) {
        assertNull(ClientRequestIds.acceptable(candidate))
    }

    @Test
    fun `a missing id and a 500-character id are rejected`() {
        assertNull(ClientRequestIds.acceptable(null))
        assertNull(ClientRequestIds.acceptable("a".repeat(500)))
    }

    @Test
    fun `the resolver keeps an acceptable client id and caches it`() {
        val request = MockHttpServletRequest().apply { addHeader("X-Request-Id", "client-req-1") }

        assertEquals("client-req-1", ApiProblemFactory.requestId(request))
        assertEquals("client-req-1", request.getAttribute(ApiProblemFactory.REQUEST_ID_ATTRIBUTE))
    }

    @ParameterizedTest
    @ValueSource(strings = ["bad\r\nid-value", "short", "has spaces in it", "ünïcödé-request"])
    fun `the resolver replaces a rejected client id with one generated v7 id`(hostile: String) {
        val request = MockHttpServletRequest().apply { addHeader("X-Request-Id", hostile) }

        val resolved = ApiProblemFactory.requestId(request)

        assertNotEquals(hostile, resolved)
        assertEquals(UUID_V7, UUID.fromString(resolved).version())
        assertEquals(resolved, request.getAttribute(ApiProblemFactory.REQUEST_ID_ATTRIBUTE))
        assertEquals(resolved, ApiProblemFactory.requestId(request))
    }

    @Test
    fun `the resolver replaces a 500-character client id`() {
        val request =
            MockHttpServletRequest().apply { addHeader("X-Request-Id", "a".repeat(500)) }

        val resolved = ApiProblemFactory.requestId(request)

        assertEquals(UUID_V7, UUID.fromString(resolved).version())
    }

    private companion object {
        const val UUID_V7 = 7
    }
}
