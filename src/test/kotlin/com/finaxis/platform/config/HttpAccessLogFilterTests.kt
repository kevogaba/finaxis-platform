package com.finaxis.platform.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletResponse
import org.junit.jupiter.api.assertThrows
import org.slf4j.LoggerFactory
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import java.io.IOException
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class HttpAccessLogFilterTests {
    private val logger = LoggerFactory.getLogger("com.finaxis.platform.http.access") as Logger
    private val appender = ListAppender<ILoggingEvent>()

    @BeforeTest
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `filter emits access log through slf4j with request fields`() {
        val request =
            MockHttpServletRequest("GET", "/api/v1/auth/me").apply {
                queryString = "include=branches"
                remoteAddr = "203.0.113.10"
                addHeader("User-Agent", "finaxis-test")
                addHeader("X-Request-Id", "request-1")
            }
        val response = MockHttpServletResponse()
        val filterChain =
            FilterChain { _, servletResponse ->
                val httpResponse = servletResponse as HttpServletResponse
                httpResponse.contentType = "application/json"
                httpResponse.writer.write("""{"ok":true}""")
            }

        HttpAccessLogFilter().doFilter(request, response, filterChain)

        val event = appender.list.single()
        assertEquals(Level.INFO, event.level)
        assertTrue(event.formattedMessage.contains("http_access requestId="))
        assertTrue(event.formattedMessage.contains("method=GET"))
        assertTrue(event.formattedMessage.contains("""requestId="request-1""""))
        assertTrue(event.formattedMessage.contains("""path="/api/v1/auth/me""""))
        assertTrue(event.formattedMessage.contains("""query="include=branches""""))
        assertTrue(event.formattedMessage.contains("status=200"))
        assertTrue(event.formattedMessage.contains("responseBytes=11"))
        assertTrue(event.formattedMessage.contains("""remoteAddress="203.0.113.10""""))
        assertEquals("GET", event.mdcPropertyMap["http.method"])
        assertEquals("/api/v1/auth/me", event.mdcPropertyMap["http.path"])
        assertEquals("200", event.mdcPropertyMap["http.status_code"])
        assertEquals("11", event.mdcPropertyMap["http.response_bytes"])
        assertEquals("request-1", event.mdcPropertyMap["requestId"])
        assertEquals("request-1", response.getHeader("X-Request-Id"))
        assertNotNull(event.mdcPropertyMap["http.duration_ms"])
    }

    @Test
    fun `filter redacts the query string of the global search route`() {
        val message =
            logged(
                MockHttpServletRequest("GET", "/api/v1/tenant/search").apply {
                    queryString = "q=someone@example.com"
                },
            )

        assertTrue(message.contains("""query="[redacted]""""), message)
        assertFalse(message.contains("someone@example.com"), message)
        assertFalse(message.contains("q="), message)
        assertTrue(message.contains("""path="/api/v1/tenant/search""""))
    }

    @Test
    fun `filter redacts the search query string for path variants too`() {
        listOf(
            "/api/v1/tenant/search/",
            "/API/V1/TENANT/SEARCH",
            "/api/v1/tenant/search;jsessionid=abc",
            "/api/v1/tenant/%73earch",
            "/api/v1/%74enant/search",
            "/%61pi/v1/tenant/search",
            "/api/v1/tenant/%53EARCH",
        ).forEach { uri ->
            val message =
                logged(
                    MockHttpServletRequest("GET", uri).apply {
                        queryString = "q=someone@example.com"
                    },
                )

            assertFalse(message.contains("someone@example.com"), "$uri: $message")
            assertTrue(message.contains("""query="[redacted]""""), "$uri: $message")
        }
    }

    @Test
    fun `filter honours the context path when redacting the search query string`() {
        val message =
            logged(
                MockHttpServletRequest("GET", "/app/api/v1/tenant/search").apply {
                    contextPath = "/app"
                    queryString = "q=someone@example.com"
                },
            )

        assertFalse(message.contains("someone@example.com"), message)
        assertTrue(message.contains("""query="[redacted]""""), message)
    }

    @Test
    fun `a path that cannot be decoded is redacted and never throws`() {
        listOf("/api/v1/tenant/se%zzrch", "/api/v1/tenant/search%", "/api/v1/tenant/se%2").forEach {
            val message =
                logged(MockHttpServletRequest("GET", it).apply { queryString = "q=someone@x.com" })

            assertFalse(message.contains("someone@x.com"), "$it: $message")
            assertTrue(message.contains("""query="[redacted]""""), "$it: $message")
        }
    }

    @Test
    fun `a double encoded search path is not the search route and is logged as before`() {
        val message =
            logged(
                MockHttpServletRequest("GET", "/api/v1/tenant/%2573earch").apply {
                    queryString = "q=visible"
                },
            )

        assertTrue(message.contains("""query="q=visible""""), message)
    }

    @Test
    fun `filter keeps logging the query string of other routes and a bare search path`() {
        val other =
            logged(
                MockHttpServletRequest("GET", "/api/v1/tenant/searchable").apply {
                    queryString = "q=visible"
                },
            )
        assertTrue(other.contains("""query="q=visible""""), other)

        val noQuery = logged(MockHttpServletRequest("GET", "/api/v1/tenant/search"))
        assertTrue(noQuery.contains("query=-"), noQuery)
    }

    private fun logged(request: MockHttpServletRequest): String {
        appender.list.clear()
        HttpAccessLogFilter().doFilter(request, MockHttpServletResponse()) { _, _ -> }
        return appender.list.single().formattedMessage
    }

    @Test
    fun `filter logs failed requests as server errors and rethrows`() {
        val request = MockHttpServletRequest("POST", "/api/v1/auth/select-branch")
        val response = MockHttpServletResponse()
        val filterChain =
            FilterChain { _, _ ->
                throw IOException("failed")
            }

        assertThrows<IOException> {
            HttpAccessLogFilter().doFilter(request, response, filterChain)
        }

        val event = appender.list.single()
        assertEquals(Level.WARN, event.level)
        assertTrue(event.formattedMessage.contains("status=500"))
        assertEquals("500", event.mdcPropertyMap["http.status_code"])
    }

    @Test
    fun `filter never logs, puts in MDC or echoes a rejected client request id`() {
        val hostile = "forged\r\nhttp_access requestId=\"spoofed\""
        val request =
            MockHttpServletRequest("GET", "/api/v1/auth/me").apply {
                addHeader("X-Request-Id", hostile)
            }
        val response = MockHttpServletResponse()

        HttpAccessLogFilter().doFilter(request, response, FilterChain { _, _ -> })

        val event = appender.list.single()
        val generated = requireNotNull(response.getHeader("X-Request-Id"))
        assertEquals(UUID_V7, UUID.fromString(generated).version())
        assertEquals(generated, event.mdcPropertyMap["requestId"])
        assertTrue(event.formattedMessage.contains("""requestId="$generated""""))
        assertFalse(event.formattedMessage.contains("forged"))
        assertFalse(event.mdcPropertyMap.values.any { it.contains("forged") })
    }

    @Test
    fun `filter reuses a request id established before access logging`() {
        val request =
            MockHttpServletRequest("GET", "/api/v1/auth/me").apply {
                setAttribute("com.finaxis.platform.common.web.api.request-id", "problem-request-1")
            }
        val response = MockHttpServletResponse()

        HttpAccessLogFilter().doFilter(request, response, FilterChain { _, _ -> })

        assertEquals("problem-request-1", response.getHeader("X-Request-Id"))
        assertEquals("problem-request-1", appender.list.single().mdcPropertyMap["requestId"])
    }

    private companion object {
        const val UUID_V7 = 7
    }
}
