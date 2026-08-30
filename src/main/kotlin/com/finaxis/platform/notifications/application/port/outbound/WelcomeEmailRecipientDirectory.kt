package com.finaxis.platform.notifications.application.port.outbound

import java.util.UUID

/** Recipient and organisation context resolved at welcome-email send time. */
data class WelcomeEmailRecipient(
    val email: String,
    val displayName: String,
    val organisationDisplayName: String,
)

/** Internal, notifications-only lookup resolving welcome-email context at send time. */
fun interface WelcomeEmailRecipientDirectory {
    /** Resolves the welcome-email recipient and organisation display name, or `null` if absent. */
    fun findRecipient(
        userId: UUID,
        organisationId: UUID,
    ): WelcomeEmailRecipient?
}
