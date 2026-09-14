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
 * Regression for the boot-blocker described in
 * `docs/superpowers/specs/2026-09-06-production-readiness-coolify-deployment-design.md`:
 * `spring-boot-starter-mail`'s health indicator dials the configured SMTP host regardless of
 * `finaxis.email.enabled`, and Coolify polls this exact path to decide whether a deploy succeeded.
 * `management.health.mail.enabled=false` (the unconditional `application.yaml` default) must keep
 * `/actuator/health` reporting `UP` even with email disabled and no SMTP host reachable.
 */
@Import(PostgresRabbitTestConfiguration::class)
@SpringBootTest(
    properties = [
        "finaxis.email.enabled=false",
        "spring.mail.host=localhost",
        // An arbitrary high, almost certainly unbound port: connecting refuses immediately rather
        // than hanging out to spring.mail.properties.mail.smtp.connectiontimeout.
        "spring.mail.port=65534",
    ],
)
@AutoConfigureMockMvc
class MailHealthIndicatorDisabledIntegrationTests {
    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun `health reports UP with email disabled and no reachable SMTP host`() {
        mockMvc.get("/actuator/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
        }
    }
}
