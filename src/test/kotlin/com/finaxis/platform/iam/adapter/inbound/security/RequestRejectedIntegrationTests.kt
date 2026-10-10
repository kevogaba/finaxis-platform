package com.finaxis.platform.iam.adapter.inbound.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.web.api.ApiJsonCodec
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.net.URI
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The firewall's `request_rejected` problem through the real filter chain: a refused path is
 * never reflected (the problem's `instance` is fixed, not the request URI), the response carries
 * the browser-hardening headers `HeaderWriterFilter` never got to write, and a value the firewall
 * refuses only after the controller ran (the access log reading `Referer` last) leaves the
 * committed response as it was, one JSON document, never the original body with a problem
 * appended.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class RequestRejectedIntegrationTests
    @Autowired
    constructor(
        private val mockMvc: MockMvc,
        private val apiJsonCodec: ApiJsonCodec,
    ) {
        private val rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        private val appender = ListAppender<ILoggingEvent>()

        @BeforeEach
        fun attachAppender() {
            appender.start()
            rootLogger.addAppender(appender)
        }

        @AfterEach
        fun detachAppender() {
            rootLogger.detachAppender(appender)
            appender.stop()
        }

        @Test
        fun `a refused path is answered with a problem that never reflects the path`() {
            listOf(
                "/api/v1/auth/me;reflected=$MARKER",
                "/api//v1/auth/$MARKER",
                "/actuator//$MARKER",
            ).forEach { path ->
                val response =
                    mockMvc
                        // A URI, not a template: a template string would collapse the `//`.
                        .get(URI.create(path))
                        .andExpect {
                            status { isBadRequest() }
                            content {
                                contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                            }
                            jsonPath("$.code") { value("request_rejected") }
                            jsonPath("$.instance") { value(ApiRequestRejectedHandler.INSTANCE) }
                            header { string("X-Content-Type-Options", "nosniff") }
                            header {
                                string(
                                    "Content-Security-Policy",
                                    ApiRequestRejectedHandler.CONTENT_SECURITY_POLICY,
                                )
                            }
                            header { string("X-Frame-Options", "DENY") }
                        }.andReturn()
                        .response
                assertNothingReflected(response)
            }
        }

        @Test
        fun `a value refused after the controller ran never appends a problem to its body`() {
            val response =
                mockMvc
                    .get("/actuator/health") {
                        header("Referer", "a${LATE_REFUSED}b-$MARKER")
                    }.andReturn()
                    .response

            // The health body was flushed, so the response was committed before the access log
            // read Referer: it is left exactly as written. Before the guard the problem was
            // appended to it, a second JSON document that a strict parse counts.
            assertEquals(1, jsonDocuments(response.contentAsString), response.contentAsString)
            val health = apiJsonCodec.mapper.readTree(response.contentAsString)
            assertTrue(health.has("status"), response.contentAsString)
            assertFalse(health.has("code"), response.contentAsString)
            assertTrue(response.status != HttpStatus.BAD_REQUEST.value())
            assertNothingReflected(response)
            // The rejection did happen: the handler's message-free line names this request.
            val requestId = requireNotNull(response.getHeader("X-Request-Id"))
            val refused = appender.list.filter { it.loggerName == HANDLER_LOGGER }
            assertTrue(refused.single().formattedMessage.contains(requestId))
            assertFalse(appender.list.any { MARKER in it.formattedMessage })
        }

        private fun assertNothingReflected(response: MockHttpServletResponse) {
            val echoed = response.headerNames.flatMap { response.getHeaders(it) }
            assertFalse(echoed.any { MARKER in it })
            assertFalse(MARKER in response.contentAsString, response.contentAsString)
            assertFalse(";" in response.contentAsString, response.contentAsString)
            assertFalse("//" in response.contentAsString, response.contentAsString)
            UUID.fromString(requireNotNull(response.getHeader("X-Request-Id")))
        }

        private fun jsonDocuments(content: String): Int =
            apiJsonCodec.mapper.createParser(content).use { parser ->
                var documents = 0
                while (parser.nextToken() != null) {
                    parser.skipChildren()
                    documents++
                }
                documents
            }

        private companion object {
            const val MARKER = "reflected-segment"
            val HANDLER_LOGGER: String = ApiRequestRejectedHandler::class.java.name

            // A C1 control: Tomcat accepts the byte as ISO-8859-1 obs-text, and the firewall
            // refuses it only when a header is read, here by the access log after the chain ran.
            const val LATE_REFUSED = '\u0085'
        }
    }
