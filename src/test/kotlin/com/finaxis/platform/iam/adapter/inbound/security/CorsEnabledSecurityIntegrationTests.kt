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

@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.security.cors.enabled=true",
        "finaxis.security.cors.allowed-origins=https://app.finaxis.example",
        "finaxis.security.cors.allowed-methods=GET,POST,OPTIONS",
        "finaxis.security.cors.allowed-headers=Authorization,Content-Type",
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
}
