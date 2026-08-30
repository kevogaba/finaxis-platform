package com.finaxis.platform.notifications.application.port.outbound.email

/** Categorizes a Finaxis application email for template selection and metric tagging. */
enum class EmailCategory {
    WELCOME,
    ORGANISATION_INVITE,
}

/**
 * The recipient and business context needed to render and deliver one Finaxis application
 * email. Never carries a template's rendered body, a token, or any secret.
 */
data class EmailMessage(
    val category: EmailCategory,
    val recipientEmail: String,
    val recipientDisplayName: String,
    val organisationDisplayName: String,
)

/** Confirms an email was accepted for delivery by the underlying transport. */
data class EmailDeliveryReceipt(
    val messageId: String?,
)

/** Base type for email rendering/delivery failures, owned by the notifications module. */
sealed class EmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

/** A failure a JobRunr retry may resolve (transient connectivity, transient SMTP failure). */
class RetryableEmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : EmailDeliveryException(message, cause)

/** A failure no retry will fix (bad address, template error, bad auth configuration). */
class PermanentEmailDeliveryException(
    message: String,
    cause: Throwable? = null,
) : EmailDeliveryException(message, cause)

/**
 * Outbound port for rendering and delivering Finaxis application emails, owned by the
 * notifications module. Implementations must never be called synchronously inside a
 * tenant/user provisioning transaction — callers invoke this only from durable JobRunr
 * background jobs.
 */
fun interface EmailGateway {
    /**
     * @throws RetryableEmailDeliveryException for failures a retry may resolve
     * @throws PermanentEmailDeliveryException for failures no retry will fix
     */
    fun send(message: EmailMessage): EmailDeliveryReceipt
}
