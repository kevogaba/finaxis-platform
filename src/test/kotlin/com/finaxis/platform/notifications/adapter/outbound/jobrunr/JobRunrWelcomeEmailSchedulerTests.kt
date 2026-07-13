package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.notifications.application.WelcomeEmailCommand
import org.jobrunr.scheduling.JobRequestScheduler
import org.mockito.Mockito.mock
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals

class JobRunrWelcomeEmailSchedulerTests {
    @Test
    fun `identical commands enqueue with the same deterministic job id`() {
        val jobRequestScheduler = mock(JobRequestScheduler::class.java)
        val scheduler = JobRunrWelcomeEmailScheduler(jobRequestScheduler)
        val command = welcomeEmailCommand()

        scheduler.scheduleWelcomeEmail(command)
        scheduler.scheduleWelcomeEmail(command)

        val invocations =
            org.mockito.Mockito
                .mockingDetails(jobRequestScheduler)
                .invocations
                .filter { it.method.name == "enqueue" && it.arguments.size == 2 }
        assertEquals(2, invocations.size)
        assertEquals(invocations[0].arguments[0], invocations[1].arguments[0])
    }

    private fun welcomeEmailCommand(): WelcomeEmailCommand =
        WelcomeEmailCommand(
            membershipId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            organisationId = UUID.randomUUID(),
            transition = "ACTIVATE",
            occurredAt = Instant.parse("2026-07-13T10:15:30Z"),
        )
}
