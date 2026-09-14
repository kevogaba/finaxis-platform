package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.PostgresRabbitTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/**
 * Discriminating control for [MailHealthIndicatorDisabledIntegrationTests]: with the mail health
 * indicator explicitly re-enabled and the same unreachable SMTP host, `/actuator/health` must
 * report `DOWN`. Without this, an `UP` result in the disabled test would be unfalsifiable evidence
 * that `management.health.mail.enabled=false` actually took effect.
 */
@Import(PostgresRabbitTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.email.enabled=false",
        "management.health.mail.enabled=true",
        "spring.mail.host=localhost",
        "spring.mail.port=65534",
    ],
)
@AutoConfigureMockMvc
class MailHealthIndicatorEnabledIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health reports DOWN when the mail indicator is re-enabled against an unreachable host`() {
        mockMvc.get("/actuator/health").andExpect {
            jsonPath("$.status") { value("DOWN") }
        }
    }
}
