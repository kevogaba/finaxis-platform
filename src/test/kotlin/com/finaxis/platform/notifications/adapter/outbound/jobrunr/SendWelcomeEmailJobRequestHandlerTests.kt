package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.jobs.JobStepGuard
import com.finaxis.platform.common.persistence.SystemActor
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipient
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailDeliveryReceipt
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.EmailMessage
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.jobrunr.JobRunrException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SendWelcomeEmailJobRequestHandlerTests {
    private val userId = uuidV7()
    private val organisationId = uuidV7()
    private val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
    private val audits = CapturingAudits()
    private val auditService = AuditService(audits, clock)
    private val recipientDirectory =
        WelcomeEmailRecipientDirectory { _, _ ->
            WelcomeEmailRecipient("member@example.test", "Ada Lovelace", "Acme Bank")
        }

    private fun handler(
        gateway: EmailGateway,
        stepGuard: JobStepGuard = InMemoryJobStepGuard(),
    ) = SendWelcomeEmailJobRequestHandler(recipientDirectory, gateway, stepGuard, auditService)

    private fun request() = SendWelcomeEmailJobRequest(uuidV7(), userId, organisationId)

    @Test
    fun `sends the welcome email and audits success`() {
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }

        handler(gateway).run(request())

        assertEquals(1, sentMessages.size)
        assertEquals("member@example.test", sentMessages.single().recipientEmail)
        val audit = audits.items.single()
        assertEquals("user.welcome_email", audit.action)
        assertEquals(AuditOutcome.SUCCESS, audit.outcome)
        assertEquals(SystemActor.ID.toString(), audit.actorId)
    }

    @Test
    fun `permanent failure audits failure and throws a do-not-retry JobRunrException`() {
        val gateway = EmailGateway { throw PermanentEmailDeliveryException("bad address") }

        val exception = assertFailsWith<JobRunrException> { handler(gateway).run(request()) }

        assertEquals(true, exception.isProblematicAndDoNotRetry())
        assertEquals(AuditOutcome.FAILURE, audits.items.single().outcome)
    }

    @Test
    fun `retryable failure audits failure and rethrows the original exception`() {
        val gateway = EmailGateway { throw RetryableEmailDeliveryException("timeout") }

        assertFailsWith<RetryableEmailDeliveryException> { handler(gateway).run(request()) }

        assertEquals(AuditOutcome.FAILURE, audits.items.single().outcome)
    }

    @Test
    fun `missing recipient audits failure and throws a do-not-retry JobRunrException`() {
        val missingRecipientDirectory = WelcomeEmailRecipientDirectory { _, _ -> null }
        val gateway = EmailGateway { EmailDeliveryReceipt("msg-1") }
        val handler =
            SendWelcomeEmailJobRequestHandler(
                missingRecipientDirectory,
                gateway,
                InMemoryJobStepGuard(),
                auditService,
            )

        val exception = assertFailsWith<JobRunrException> { handler.run(request()) }

        assertEquals(true, exception.isProblematicAndDoNotRetry())
        assertEquals(AuditOutcome.FAILURE, audits.items.single().outcome)
    }

    @Test
    fun `retrying an already-completed step does not send a second email`() {
        val sentMessages = mutableListOf<EmailMessage>()
        val gateway =
            EmailGateway { message ->
                sentMessages.add(message)
                EmailDeliveryReceipt("msg-1")
            }
        val stepGuard = InMemoryJobStepGuard()

        handler(gateway, stepGuard).run(request())
        handler(gateway, stepGuard).run(request())

        assertEquals(1, sentMessages.size)
    }
}

private class InMemoryJobStepGuard : JobStepGuard {
    private val completedSteps = mutableSetOf<String>()

    override fun runOnce(
        step: String,
        action: () -> Unit,
    ) {
        if (completedSteps.add(step)) action()
    }
}

private class CapturingAudits : AuditEventRepository {
    val items = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        items.add(event)
    }
}
