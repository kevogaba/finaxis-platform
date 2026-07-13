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

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
class SecurityHeadersIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `public responses include the baseline security headers`() {
        mockMvc
            .get("/actuator/health")
            .andExpect {
                header { string("X-Content-Type-Options", "nosniff") }
                header { string("X-Frame-Options", "DENY") }
                header { string("Referrer-Policy", "strict-origin-when-cross-origin") }
            }
    }

    @Test
    fun `disabled cors does not allow cross origin preflight requests`() {
        mockMvc
            .options("/api/v1/auth/me") {
                header(HttpHeaders.ORIGIN, "https://app.finaxis.example")
                header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
            }.andExpect {
                header { doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN) }
            }
    }
}
