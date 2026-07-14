package com.finaxis.platform.notifications.application

import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.ExternalizedTransitionEvent
import com.finaxis.platform.common.transitions.TransitionActor
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailScheduler
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class NotificationServiceTests {
    @Test
    fun `membership activation maps to a welcome email command`() {
        val scheduler = CapturingWelcomeEmailScheduler()
        val membershipId = uuidV7()
        val userId = uuidV7()
        val organisationId = uuidV7()
        val occurredAt = Instant.parse("2026-07-13T10:15:30Z")

        NotificationService(scheduler).handleMembershipActivated(
            membershipActivatedEvent(membershipId, userId, organisationId, occurredAt),
        )

        assertEquals(
            WelcomeEmailCommand(membershipId, userId, organisationId, "ACTIVATE", occurredAt),
            scheduler.command,
        )
    }

    private fun membershipActivatedEvent(
        membershipId: UUID,
        userId: UUID,
        organisationId: UUID,
        occurredAt: Instant,
    ): ExternalizedTransitionEvent =
        ExternalizedTransitionEvent(
            target = "finaxis.lifecycle.membership.activated",
            aggregateType = "MEMBERSHIP",
            aggregateId = membershipId.toString(),
            transition = "ACTIVATE",
            fromState = "INVITED",
            toState = "ACTIVE",
            actor = TransitionActor("USER", userId.toString(), "Example User"),
            occurredAt = occurredAt,
            metadata =
                mapOf(
                    "membershipId" to membershipId.toString(),
                    "userId" to userId.toString(),
                    "organisationId" to organisationId.toString(),
                ),
        )

    private class CapturingWelcomeEmailScheduler : WelcomeEmailScheduler {
        var command: WelcomeEmailCommand? = null

        override fun scheduleWelcomeEmail(command: WelcomeEmailCommand) {
            this.command = command
        }
    }
}
