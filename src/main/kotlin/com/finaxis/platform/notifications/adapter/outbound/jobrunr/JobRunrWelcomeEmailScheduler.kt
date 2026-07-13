package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.notifications.application.WelcomeEmailCommand
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailScheduler
import org.jobrunr.scheduling.JobRequestScheduler
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * JobRunr-backed implementation that schedules idempotent welcome-email background jobs.
 */
@Component
class JobRunrWelcomeEmailScheduler(
    private val jobRequestScheduler: JobRequestScheduler,
) : WelcomeEmailScheduler {
    /**
     * Enqueues the welcome email job under an identifier stable across RabbitMQ redeliveries.
     *
     * @param command the membership activation details for the welcome email
     */
    override fun scheduleWelcomeEmail(command: WelcomeEmailCommand) {
        jobRequestScheduler.enqueue(
            deterministicJobId(command),
            SendWelcomeEmailJobRequest(
                membershipId = command.membershipId,
                userId = command.userId,
                organisationId = command.organisationId,
            ),
        )
    }

    private fun deterministicJobId(command: WelcomeEmailCommand): UUID =
        UUID.nameUUIDFromBytes(
            "${command.membershipId}:${command.transition}:${command.occurredAt}".toByteArray(),
        )
}
