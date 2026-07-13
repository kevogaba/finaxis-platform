package com.finaxis.platform.notifications.application

import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailScheduler
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Coordinates notification use cases derived from externalized lifecycle transition events.
 */
@Service
class NotificationService(
    private val welcomeEmailScheduler: WelcomeEmailScheduler,
) {
    /**
     * Maps a membership activation event to a welcome email command and schedules it.
     *
     * @param event the externalized membership activation event
     */
    fun handleMembershipActivated(event: ExternalizedTransitionEvent) {
        welcomeEmailScheduler.scheduleWelcomeEmail(
            WelcomeEmailCommand(
                membershipId = event.requiredMetadataUuid(MEMBERSHIP_ID),
                userId = event.requiredMetadataUuid(USER_ID),
                organisationId = event.requiredMetadataUuid(ORGANISATION_ID),
                transition = event.transition,
                occurredAt = event.occurredAt,
            ),
        )
    }

    private companion object {
        const val MEMBERSHIP_ID = "membershipId"
        const val USER_ID = "userId"
        const val ORGANISATION_ID = "organisationId"
    }
}

private fun ExternalizedTransitionEvent.requiredMetadataUuid(field: String): UUID =
    UUID.fromString(
        requireNotNull(metadata[field]?.toString()?.takeIf(String::isNotBlank)) {
            "Membership activation event is missing required metadata: $field"
        },
    )
