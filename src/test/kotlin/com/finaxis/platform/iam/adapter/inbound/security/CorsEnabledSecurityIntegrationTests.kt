package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.options
import org.springframework.test.web.servlet.post

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
}
