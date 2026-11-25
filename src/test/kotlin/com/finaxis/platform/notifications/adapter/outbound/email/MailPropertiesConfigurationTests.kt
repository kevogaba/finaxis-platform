package com.finaxis.platform.notifications.adapter.outbound.email

import org.junit.jupiter.api.Test
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.env.StandardEnvironment
import org.springframework.core.io.FileSystemResource
import kotlin.test.assertEquals

/**
 * SMTP is Spring Boot's `spring.mail.*` and nothing else: `application.yaml` names the server and
 * credentials once (`FINAXIS_SMTP_*`), and the `production` profile adds only authentication and
 * STARTTLS on top of them. `finaxis.email.*` keeps what Boot has no property for.
 */
class MailPropertiesConfigurationTests {
    @Test
    fun `production keeps the base SMTP server and adds authentication and STARTTLS`() {
        val environment = environment("application.yaml", "application-production.yaml")

        assertEquals("localhost", environment.getProperty("spring.mail.host"))
        assertEquals("1025", environment.getProperty("spring.mail.port"))
        assertEquals("true", environment.getProperty("spring.mail.properties.mail.smtp.auth"))
        assertEquals(
            "true",
            environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable"),
        )
        assertEquals("false", environment.getProperty("finaxis.email.enabled"))
        assertEquals(
            "no-reply@finaxis.local",
            environment.getProperty("finaxis.email.from-address"),
        )
    }

    @Test
    fun `without a profile SMTP neither authenticates nor upgrades by default`() {
        val environment = environment("application.yaml")

        assertEquals("false", environment.getProperty("spring.mail.properties.mail.smtp.auth"))
        assertEquals(
            "false",
            environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable"),
        )
    }

    /** The files in increasing precedence, as Spring Boot layers a profile over the base. */
    private fun environment(vararg files: String): StandardEnvironment =
        StandardEnvironment().apply {
            files.forEach { file ->
                propertySources.addFirst(
                    YamlPropertySourceLoader()
                        .load(file, FileSystemResource("src/main/resources/$file"))
                        .first(),
                )
            }
        }
}
