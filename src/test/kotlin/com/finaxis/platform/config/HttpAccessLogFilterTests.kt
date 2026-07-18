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
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
