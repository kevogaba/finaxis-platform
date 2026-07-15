package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.SystemActor
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SendWelcomeEmailJobRequestHandlerTests {
    private val logger =
        LoggerFactory.getLogger(
            SendWelcomeEmailJobRequestHandler::class.java,
        ) as Logger
    private val appender = ListAppender<ILoggingEvent>()
    private val audits = CapturingAudits()

    @BeforeTest
    fun attachAppender() {
        appender.start()
        logger.addAppender(appender)
    }

    @AfterTest
    fun detachAppender() {
        logger.detachAppender(appender)
        appender.stop()
    }

    @Test
    fun `handler logs the welcome email stub without error and audits the dispatch`() {
        val userId = uuidV7()
        val organisationId = uuidV7()
        val clock = Clock.fixed(Instant.parse("2026-07-14T12:00:00Z"), ZoneOffset.UTC)
        val auditService = AuditService(audits, clock)

        SendWelcomeEmailJobRequestHandler(auditService).run(
            SendWelcomeEmailJobRequest(
                membershipId = uuidV7(),
                userId = userId,
                organisationId = organisationId,
            ),
        )

        val message = appender.list.single().formattedMessage
        assertTrue(message.contains("would send welcome email"))
        assertTrue(message.contains(organisationId.toString()))
        assertTrue(message.contains(userId.toString()))
        val dispatchAudit = audits.items.single()
        assertEquals("user.welcome_email", dispatchAudit.action)
        assertEquals(AuditOutcome.SUCCESS, dispatchAudit.outcome)
        assertEquals(SystemActor.ID.toString(), dispatchAudit.actorId)
    }
}

private class CapturingAudits : AuditEventRepository {
    val items = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        items.add(event)
    }
}
