package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresRabbitTestConfiguration
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.startsWith
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.BadJwtException
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * A request whose bearer token the decoder rejects must get the same problem document and
 * `X-Request-Id` as every other error, not Spring's empty `401` body.
 */
@Import(
    PostgresRabbitTestConfiguration::class,
    InvalidBearerTokenIntegrationTests.RejectingJwt::class,
)
@SpringBootTest
@AutoConfigureMockMvc
class InvalidBearerTokenIntegrationTests {
    @TestConfiguration(proxyBeanMethods = false)
    class RejectingJwt {
        @Bean
        @Primary
        fun rejectingJwtDecoder(): JwtDecoder = JwtDecoder { throw BadJwtException("garbage") }
    }

    @Autowired
    private lateinit var mockMvc: MockMvc

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
                jsonPath("$.code") { value("authentication_required") }
                jsonPath("$.status") { value(401) }
                jsonPath("$.request_id") { value("garbage-jwt-request") }
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
}
