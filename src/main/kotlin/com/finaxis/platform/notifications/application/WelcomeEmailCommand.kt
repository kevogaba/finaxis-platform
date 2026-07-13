package com.finaxis.platform.notifications.application

import java.time.Instant
import java.util.UUID

/**
 * The information required to schedule a welcome email after a membership activation.
 */
data class WelcomeEmailCommand(
    val membershipId: UUID,
    val userId: UUID,
    val organisationId: UUID,
    val transition: String,
    val occurredAt: Instant,
)
