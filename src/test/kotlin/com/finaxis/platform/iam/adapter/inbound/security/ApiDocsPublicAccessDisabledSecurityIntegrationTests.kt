package com.finaxis.platform.iam.adapter.inbound.security

import com.finaxis.platform.PostgresRabbitTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@Import(PostgresRabbitTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.security.api-docs.public-access-enabled=false",
    ],
)
@AutoConfigureMockMvc
class ApiDocsPublicAccessDisabledSecurityIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `scalar and the openapi document require authentication when public access is disabled`() {
        mockMvc.get("/scalar").andExpect { status { isUnauthorized() } }
        mockMvc.get("/v3/api-docs").andExpect { status { isUnauthorized() } }
        mockMvc.get("/swagger-ui/index.html").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `actuator health stays public regardless of the api-docs toggle`() {
        mockMvc.get("/actuator/health").andExpect { status { isOk() } }
    }
}
