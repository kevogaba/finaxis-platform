package com.finaxis.platform.iam.adapter.inbound.security

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresRabbitTestConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidationException
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The real filter chain's `401` challenges and request-id handling: a missing token gets a bare
 * RFC 6750 `Bearer` challenge, a rejected token a fixed `error_description` that never repeats
 * the decoder's message, and a client `X-Request-Id` outside the accepted shape is replaced by a
 * generated id that is the only one echoed, logged or put in the MDC.
 */
@Import(
    PostgresRabbitTestConfiguration::class,
    BearerChallengeAndRequestIdIntegrationTests.RejectingJwt::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class BearerChallengeAndRequestIdIntegrationTests {
    @TestConfiguration(proxyBeanMethods = false)
    class RejectingJwt {
        @Bean
        @Primary
        fun rejectingJwtDecoder(): JwtDecoder =
            JwtDecoder { token ->
                if (token == EXPIRED_TOKEN) {
                    throw JwtValidationException(
                        EXPIRED_MESSAGE,
                        listOf(OAuth2Error("invalid_token", EXPIRED_MESSAGE, null)),
                    )
                }
                throw BadJwtException(GARBAGE_MESSAGE)
            }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

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
    fun `a missing token gets a bare bearer challenge with no error code`() {
        mockMvc
            .get("/api/v1/auth/me") {
                header("X-Request-Id", GOOD_ID)
            }.andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith("application/problem+json") }
                header {
                    string(
                        "WWW-Authenticate",
                        "Bearer resource_metadata=" +
                            "\"http://localhost/.well-known/oauth-protected-resource\"",
                    )
                }
                header { string("X-Request-Id", GOOD_ID) }
                jsonPath("$.code") { value("authentication_required") }
                jsonPath("$.request_id") { value(GOOD_ID) }
            }
    }

    @Test
    fun `a garbage bearer token gets a problem body and a request id`() {
        mockMvc
            .get("/api/v1/auth/me") {
                header("Authorization", "Bearer not-a-jwt")
                header("X-Request-Id", "garbage-jwt-request")
            }.andExpect {
                status { isUnauthorized() }
                content { contentTypeCompatibleWith("application/problem+json") }
                header { string("X-Request-Id", "garbage-jwt-request") }
                header { string("WWW-Authenticate", startsWith("Bearer ")) }
                header { string("WWW-Authenticate", containsString("error=\"invalid_token\"")) }
                header { string("WWW-Authenticate", containsString(FIXED_DESCRIPTION)) }
                header { string("WWW-Authenticate", not(containsString(GARBAGE_MESSAGE))) }
                header { string("WWW-Authenticate", containsString("resource_metadata=")) }
                jsonPath("$.code") { value("authentication_required") }
                jsonPath("$.status") { value(401) }
                jsonPath("$.request_id") { value("garbage-jwt-request") }
            }
    }

    @Test
    fun `an expired token keeps invalid_token but never repeats the expiry message`() {
        mockMvc
            .get("/api/v1/auth/me") {
                header("Authorization", "Bearer $EXPIRED_TOKEN")
            }.andExpect {
                status { isUnauthorized() }
                header { string("WWW-Authenticate", containsString("error=\"invalid_token\"")) }
                header { string("WWW-Authenticate", containsString(FIXED_DESCRIPTION)) }
                header { string("WWW-Authenticate", not(containsString("expired at"))) }
                header { string("WWW-Authenticate", not(containsString("2026"))) }
                content { string(not(containsString("expired at"))) }
                jsonPath("$.code") { value("authentication_required") }
            }
    }

    @Test
    fun `a garbage bearer token without a client request id still gets a generated one`() {
        mockMvc
            .get("/api/v1/auth/me") {
                header("Authorization", "Bearer not-a-jwt")
            }.andExpect {
                status { isUnauthorized() }
                header { exists("X-Request-Id") }
                jsonPath("$.code") { value("authentication_required") }
            }
    }

    @Test
    fun `a good client request id is preserved through the whole chain and the access log`() {
        mockMvc
            .get("/actuator/health") {
                header("X-Request-Id", GOOD_ID)
            }.andExpect {
                status { isOk() }
                header { string("X-Request-Id", GOOD_ID) }
            }

        val access = accessLogEvents().single { it.mdcPropertyMap["requestId"] == GOOD_ID }
        assertTrue(access.formattedMessage.contains("requestId=\"$GOOD_ID\""))
    }

    @ParameterizedTest
    @MethodSource("hostileIds")
    fun `a hostile request id on a refused request is replaced and never echoed or logged`(
        hostile: String,
    ) {
        mockMvc
            .get("/api/v1/auth/me") {
                header("Authorization", "Bearer not-a-jwt")
                header("X-Request-Id", hostile)
            }.andExpect {
                status { isUnauthorized() }
            }.generatedRequestId(hostile)

        assertNotLogged(hostile)
    }

    @ParameterizedTest
    @MethodSource("hostileIds")
    fun `a hostile request id on a served request is replaced in the header and access log`(
        hostile: String,
    ) {
        val generated =
            mockMvc
                .get("/actuator/health") {
                    header("X-Request-Id", hostile)
                }.andExpect {
                    status { isOk() }
                }.generatedRequestId(hostile)

        val access = accessLogEvents().single { it.mdcPropertyMap["requestId"] == generated }
        assertTrue(access.formattedMessage.contains("requestId=\"$generated\""))
        assertNotLogged(hostile)
    }

    @ParameterizedTest
    @MethodSource("controlCharacterIds")
    fun `a request id carrying CR or LF is refused by the firewall and never echoed or logged`(
        hostile: String,
    ) {
        listOf("/api/v1/auth/me", "/actuator/health").forEach { path ->
            val response =
                mockMvc
                    .get(path) {
                        header("Authorization", "Bearer not-a-jwt")
                        header("X-Request-Id", hostile)
                    }.andExpect {
                        status { isBadRequest() }
                    }.andReturn()
                    .response
            val echoed = response.headerNames.flatMap { response.getHeaders(it) }
            assertFalse(echoed.any { "forged" in it })
            assertFalse("forged" in response.contentAsString)
        }
        assertNotLogged(hostile)
        assertFalse(appender.list.any { "forged" in it.formattedMessage })
    }

    private fun ResultActionsDsl.generatedRequestId(hostile: String): String {
        val response = andReturn().response
        val echoed = requireNotNull(response.getHeader("X-Request-Id"))
        assertNotEquals(hostile, echoed)
        assertEquals(UUID_V7, UUID.fromString(echoed).version())
        assertFalse(response.contentAsString.contains(hostile))
        if (response.contentType?.startsWith("application/problem+json") == true) {
            assertTrue(response.contentAsString.contains("\"request_id\":\"$echoed\""))
        }
        return echoed
    }

    private fun assertNotLogged(hostile: String) {
        val events = appender.list.toList()
        assertFalse(events.any { it.formattedMessage.contains(hostile) })
        assertFalse(events.any { event -> event.mdcPropertyMap.values.any { hostile in it } })
    }

    private fun accessLogEvents(): List<ILoggingEvent> =
        appender.list.filter { it.loggerName == "com.finaxis.platform.http.access" }

    private companion object {
        const val GOOD_ID = "client-request.ID_0042"
        const val EXPIRED_TOKEN = "expired-token"
        const val EXPIRED_MESSAGE = "Jwt expired at 2026-10-08T10:15:30Z"
        const val GARBAGE_MESSAGE = "garbage decoder detail"
        const val FIXED_DESCRIPTION = ApiBearerTokenEntryPoint.INVALID_TOKEN_DESCRIPTION
        const val UUID_V7 = 7

        // Over the wire the HTTP parser ends or refuses a header line at CR or LF; inside the
        // container Spring Security's StrictHttpFirewall refuses such a value with a 400 before
        // any application code reads the header.
        @JvmStatic
        fun controlCharacterIds(): List<String> =
            listOf(
                "forged-id\r\nX-Injected: yes",
                "forged-id\nhttp_access requestId=\"spoofed\"",
                "forged-id\rcarriage-return",
            )

        @JvmStatic
        fun hostileIds(): List<String> =
            listOf(
                "has spaces in it",
                "a".repeat(500),
                "réquest-ïd-ünicode",
                "x1y2z3w",
            )
    }
}
