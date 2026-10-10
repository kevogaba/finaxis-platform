package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post
import java.net.URI
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.security.cors.enabled=true",
        "finaxis.security.cors.allowed-origins=https://app.finaxis.example",
        "finaxis.security.cors.allowed-methods=GET,POST,OPTIONS",
        "finaxis.security.cors.allow-credentials=true",
    ],
)
@AutoConfigureMockMvc
class CorsEnabledSecurityIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `enabled cors accepts configured origin preflight requests`() {
        mockMvc
            .options("/api/v1/auth/me") {
                header(HttpHeaders.ORIGIN, "https://app.finaxis.example")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            }.andExpect {
                status { isOk() }
                header {
                    string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://app.finaxis.example",
                    )
                }
                header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
            }
    }

    @Test
    fun `mutation preflight allows idempotency key and exposes replay headers`() {
        mockMvc
            .options("/api/v1/auth/select-organisation") {
                header(HttpHeaders.ORIGIN, "https://app.finaxis.example")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "Idempotency-Key,Content-Type")
            }.andExpect {
                status { isOk() }
                header {
                    string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://app.finaxis.example",
                    )
                }
                header {
                    string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS,
                        "Idempotency-Key, Content-Type",
                    )
                }
            }
    }

    @Test
    fun `idempotency filter errors retain browser cors response headers`() {
        mockMvc
            .post("/api/v1/auth/select-organisation") {
                header(HttpHeaders.ORIGIN, "https://app.finaxis.example")
                header("Idempotency-Key", "invalid")
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isBadRequest() }
                header {
                    string(
                        HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                        "https://app.finaxis.example",
                    )
                }
                header {
                    string(
                        HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                        "Idempotency-Key, Idempotency-Replayed",
                    )
                }
            }
    }

    @Test
    fun `a firewall rejection answers an allowed origin with its cors headers`() {
        // URIs, not templates: a template string would collapse the `//`.
        listOf("/api/v1/auth/me;evil=reflected", "/api//v1/auth/me").forEach { path ->
            val response =
                mockMvc
                    .get(URI.create(path)) {
                        header(HttpHeaders.ORIGIN, "https://app.finaxis.example")
                    }.andExpect {
                        status { isBadRequest() }
                        jsonPath("$.code") { value("request_rejected") }
                        header {
                            string(
                                HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN,
                                "https://app.finaxis.example",
                            )
                        }
                        header { string(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true") }
                        header {
                            string(
                                HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS,
                                "Idempotency-Key, Idempotency-Replayed",
                            )
                        }
                    }.andReturn()
                    .response
            assertTrue(response.getHeaders(HttpHeaders.VARY).contains(HttpHeaders.ORIGIN))
            assertFalse("reflected" in response.contentAsString, response.contentAsString)
        }
    }

    @Test
    fun `a firewall rejection gives an origin outside the list no cors headers`() {
        val response =
            mockMvc
                .get(URI.create("/api/v1/auth/me;evil=reflected")) {
                    header(HttpHeaders.ORIGIN, "https://evil.example")
                }.andExpect {
                    status { isBadRequest() }
                    jsonPath("$.code") { value("request_rejected") }
                    header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
                    header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS) }
                }.andReturn()
                .response
        assertTrue(response.headerNames.none { it.startsWith("Access-Control-") })
    }
}
