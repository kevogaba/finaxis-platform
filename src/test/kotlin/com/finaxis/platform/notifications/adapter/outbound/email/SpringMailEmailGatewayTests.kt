package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import freemarker.template.Configuration
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSenderImpl
import java.io.File
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

class SpringMailEmailGatewayTests {
    private val freemarkerConfig =
        Configuration(Configuration.VERSION_2_3_32).apply {
            setDirectoryForTemplateLoading(File("src/main/resources/templates"))
        }
    private val renderer = EmailTemplateRenderer(freemarkerConfig)
    private val properties = EmailProperties()
    private val message =
        EmailMessage(
            category = EmailCategory.WELCOME,
            recipientEmail = "member@example.test",
            recipientDisplayName = "Ada Lovelace",
            organisationDisplayName = "Acme Bank",
        )

    @Test
    fun `successful send returns a receipt with a message id`() {
        val mailSender = FakeJavaMailSender()
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        val receipt = gateway.send(message)

        assertNotNull(receipt.messageId)
    }

    @Test
    fun `authentication failure is permanent`() {
        val mailSender =
            FakeJavaMailSender(failure = MailAuthenticationException("bad credentials"))
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(message) }
    }

    @Test
    fun `malformed message failure is permanent`() {
        val mailSender = FakeJavaMailSender(failure = MailParseException("bad address"))
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(message) }
    }

    @Test
    fun `unclassified send failure is retryable`() {
        val mailSender =
            FakeJavaMailSender(
                failure = MailSendException(mapOf<Any, Exception>(Any() to Exception("timeout"))),
            )
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)

        assertFailsWith<RetryableEmailDeliveryException> { gateway.send(message) }
    }

    @Test
    fun `malformed recipient address during message preparation is permanent`() {
        val mailSender = FakeJavaMailSender()
        val gateway = SpringMailEmailGateway(mailSender, renderer, properties)
        val malformedMessage = message.copy(recipientEmail = "not-an-email-@@@")

        assertFailsWith<PermanentEmailDeliveryException> { gateway.send(malformedMessage) }
    }

    private class FakeJavaMailSender(
        private val failure: RuntimeException? = null,
    ) : JavaMailSenderImpl() {
        override fun createMimeMessage(): MimeMessage =
            MimeMessage(jakarta.mail.Session.getDefaultInstance(java.util.Properties()))

        override fun send(mimeMessage: MimeMessage) {
            failure?.let { throw it }
            // Mirrors JavaMailSenderImpl.doSend(), which calls saveChanges() before handing the
            // message to Transport — that lazily generates the Message-ID header this gateway
            // reads back into the delivery receipt. Overriding send() bypasses that real
            // implementation, so the fake must emulate this one observable side effect.
            mimeMessage.saveChanges()
        }
    }
}
