package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import jakarta.mail.Session
import jakarta.mail.internet.MimeMessage
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.mockito.kotlin.whenever
import org.springframework.beans.factory.ObjectProvider
import org.springframework.mail.javamail.JavaMailSender
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Proves `EmailConfiguration.emailGateway`'s runtime branch, not just its wiring: the same
 * unconditional bean method must behave like [SpringMailEmailGateway] when enabled and like
 * [DisabledEmailGateway] when disabled, always wrapped by [MeteredEmailGateway].
 */
class EmailConfigurationTests {
    private val configuration = EmailConfiguration()
    private val registry = SimpleMeterRegistry()

    @Test
    fun `enabled properties deliver through spring mail and record a metered success`() {
        val mailSender = mock<JavaMailSender>()
        val renderer = mock<EmailTemplateRenderer>()
        val mailSenderProvider = mock<ObjectProvider<JavaMailSender>>()
        val rendererProvider = mock<ObjectProvider<EmailTemplateRenderer>>()
        whenever(mailSenderProvider.getObject()).thenReturn(mailSender)
        whenever(rendererProvider.getObject()).thenReturn(renderer)
        whenever(renderer.render(any(), any())).thenReturn("rendered body")
        whenever(mailSender.createMimeMessage())
            .thenReturn(MimeMessage(Session.getInstance(Properties())))

        val gateway =
            configuration.emailGateway(
                EmailProperties(enabled = true),
                mailSenderProvider,
                rendererProvider,
                registry,
            )
        val receipt = gateway.send(sampleWelcomeMessage())

        assertNotNull(receipt)
        verify(mailSenderProvider).getObject()
        verify(rendererProvider).getObject()
        val counter =
            registry
                .find("finaxis.email.delivery.attempts.total")
                .tags("outcome", "success")
                .counter()
        assertEquals(1.0, counter?.count())
    }

    @Test
    fun `disabled properties never touch the mail beans and record a metered permanent failure`() {
        val mailSenderProvider = mock<ObjectProvider<JavaMailSender>>()
        val rendererProvider = mock<ObjectProvider<EmailTemplateRenderer>>()

        val gateway =
            configuration.emailGateway(
                EmailProperties(enabled = false),
                mailSenderProvider,
                rendererProvider,
                registry,
            )

        val failure =
            assertFailsWith<PermanentEmailDeliveryException> {
                gateway.send(sampleWelcomeMessage())
            }

        assertEquals("Email delivery is disabled.", failure.message)
        // The whole point of ObjectProvider indirection: neither bean is required to exist when
        // email is disabled, so the disabled branch must never resolve them.
        verifyNoInteractions(mailSenderProvider, rendererProvider)
        val counter =
            registry
                .find("finaxis.email.delivery.attempts.total")
                .tags("outcome", "permanent_failure")
                .counter()
        assertEquals(1.0, counter?.count())
    }
}
