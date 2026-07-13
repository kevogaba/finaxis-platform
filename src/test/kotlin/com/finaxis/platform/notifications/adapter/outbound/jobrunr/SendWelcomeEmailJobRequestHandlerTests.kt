package com.finaxis.platform.notifications.adapter.outbound.jobrunr

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertTrue

class SendWelcomeEmailJobRequestHandlerTests {
    private val logger =
        LoggerFactory.getLogger(
            SendWelcomeEmailJobRequestHandler::class.java,
        ) as Logger
    private val appender = ListAppender<ILoggingEvent>()

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
    fun `handler logs the welcome email stub without error`() {
        val userId = UUID.randomUUID()
        val organisationId = UUID.randomUUID()

        SendWelcomeEmailJobRequestHandler().run(
            SendWelcomeEmailJobRequest(
                membershipId = UUID.randomUUID(),
                userId = userId,
                organisationId = organisationId,
            ),
        )

        val message = appender.list.single().formattedMessage
        assertTrue(message.contains("would send welcome email"))
        assertTrue(message.contains(organisationId.toString()))
        assertTrue(message.contains(userId.toString()))
    }
}
