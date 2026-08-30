package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import freemarker.template.Configuration
import freemarker.template.TemplateExceptionHandler
import java.io.StringWriter
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EmailTemplateRendererTests {
    private val freemarkerConfig =
        Configuration(Configuration.VERSION_2_3_32).apply {
            setDirectoryForTemplateLoading(java.io.File("src/main/resources/templates"))
            templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
        }
    private val renderer = EmailTemplateRenderer(freemarkerConfig)

    @Test
    fun `renders the welcome html template with the supplied model`() {
        val html =
            renderer.render(
                "email/welcome.ftlh",
                mapOf(
                    "recipientDisplayName" to "Ada Lovelace",
                    "organisationDisplayName" to "Acme Bank",
                    "appUrl" to "https://app.finaxis.test",
                ),
            )

        assertTrue(html.contains("Ada Lovelace"))
        assertTrue(html.contains("Acme Bank"))
        assertTrue(html.contains("https://app.finaxis.test"))
    }

    @Test
    fun `renders the organisation-invite plain-text template`() {
        val text =
            renderer.render(
                "email/organisation-invite.txt.ftl",
                mapOf(
                    "recipientDisplayName" to "Grace Hopper",
                    "organisationDisplayName" to "Acme Bank",
                    "appUrl" to "https://app.finaxis.test",
                ),
            )

        assertTrue(text.contains("Grace Hopper"))
        assertTrue(text.contains("invited to join Acme Bank"))
    }

    @Test
    fun `missing template raises a permanent delivery exception`() {
        assertFailsWith<PermanentEmailDeliveryException> {
            renderer.render("email/does-not-exist.ftlh", emptyMap())
        }
    }
}
