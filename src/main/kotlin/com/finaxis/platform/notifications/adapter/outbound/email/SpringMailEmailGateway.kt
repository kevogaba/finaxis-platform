package com.finaxis.platform.notifications.adapter.outbound.email

import com.finaxis.platform.notifications.application.port.outbound.email.EmailCategory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import jakarta.mail.MessagingException
import jakarta.mail.SendFailedException
import jakarta.mail.internet.AddressException
import jakarta.mail.internet.MimeMessage
import org.springframework.mail.MailAuthenticationException
import org.springframework.mail.MailException
import org.springframework.mail.MailParseException
import org.springframework.mail.MailPreparationException
import org.springframework.mail.MailSendException
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import java.io.UnsupportedEncodingException

/** Sends multipart (HTML + plain-text) Finaxis application emails over SMTP via Spring Mail. */
class SpringMailEmailGateway(
    private val mailSender: JavaMailSender,
    private val renderer: EmailTemplateRenderer,
    private val properties: EmailProperties,
) : EmailGateway {
    override fun send(message: EmailMessage): EmailDeliveryReceipt {
        val spec = TEMPLATES.getValue(message.category)
        val model =
            mapOf(
                "recipientDisplayName" to message.recipientDisplayName,
                "organisationDisplayName" to message.organisationDisplayName,
                "appUrl" to properties.appBaseUrl,
            )
        val html = renderer.render(spec.htmlTemplate, model)
        val text = renderer.render(spec.textTemplate, model)
        val mimeMessage = mailSender.createMimeMessage()
        prepareMessage(mimeMessage, spec, message, text, html)
        return try {
            mailSender.send(mimeMessage)
            EmailDeliveryReceipt(mimeMessage.messageID)
        } catch (ex: MailException) {
            throw ex.toDeliveryException()
        }
    }

    /**
     * Populates [mimeMessage] via [MimeMessageHelper], whose setters throw raw
     * `jakarta.mail.MessagingException`/`UnsupportedEncodingException` directly — never a Spring
     * `MailException` subtype — since this utility sits below the `JavaMailSender` abstraction.
     */
    private fun prepareMessage(
        mimeMessage: MimeMessage,
        spec: TemplateSpec,
        message: EmailMessage,
        text: String,
        html: String,
    ) {
        try {
            val helper = MimeMessageHelper(mimeMessage, true, "UTF-8")
            helper.setFrom(properties.fromAddress, properties.fromDisplayName)
            helper.setTo(message.recipientEmail)
            helper.setSubject(spec.subject(message.organisationDisplayName))
            helper.setText(text, html)
        } catch (ex: MessagingException) {
            throw PermanentEmailDeliveryException("Failed to prepare email message", ex)
        } catch (ex: UnsupportedEncodingException) {
            throw PermanentEmailDeliveryException("Failed to prepare email message", ex)
        }
    }

    /**
     * Classifies a Spring Mail send failure into the permanent/retryable distinction JobRunr
     * retry policy depends on, without adding another throw statement to [send].
     */
    private fun MailException.toDeliveryException(): EmailDeliveryException =
        when (this) {
            is MailAuthenticationException -> {
                PermanentEmailDeliveryException("SMTP authentication failed", this)
            }

            is MailParseException -> {
                PermanentEmailDeliveryException("Malformed recipient or message content", this)
            }

            is MailPreparationException -> {
                PermanentEmailDeliveryException("Failed to prepare email message", this)
            }

            is MailSendException -> {
                if (isPermanentAddressFailure()) {
                    PermanentEmailDeliveryException("SMTP rejected recipient permanently", this)
                } else {
                    RetryableEmailDeliveryException("SMTP send failed transiently", this)
                }
            }

            else -> {
                RetryableEmailDeliveryException("Unclassified transient mail failure", this)
            }
        }

    private fun MailSendException.isPermanentAddressFailure(): Boolean =
        failedMessages.values.any { failure -> failure.hasPermanentAddressCause() }

    private fun Throwable.hasPermanentAddressCause(): Boolean =
        generateSequence(this) { it.cause }
            .any {
                it is AddressException ||
                    (it is SendFailedException && it.invalidAddresses?.isNotEmpty() == true)
            }

    private companion object {
        val TEMPLATES =
            mapOf(
                EmailCategory.WELCOME to
                    TemplateSpec(
                        subject = { organisationName -> "Welcome to $organisationName on Finaxis" },
                        htmlTemplate = "email/welcome.ftlh",
                        textTemplate = "email/welcome.txt.ftl",
                    ),
                EmailCategory.ORGANISATION_INVITE to
                    TemplateSpec(
                        subject = { organisationName ->
                            "You've been invited to join $organisationName on Finaxis"
                        },
                        htmlTemplate = "email/organisation-invite.ftlh",
                        textTemplate = "email/organisation-invite.txt.ftl",
                    ),
            )
    }
}

private data class TemplateSpec(
    val subject: (String) -> String,
    val htmlTemplate: String,
    val textTemplate: String,
)
