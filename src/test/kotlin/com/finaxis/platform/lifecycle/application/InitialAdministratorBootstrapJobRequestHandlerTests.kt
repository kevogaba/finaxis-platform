package com.finaxis.platform.lifecycle.application

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.common.id.uuidV7
import org.jobrunr.jobs.states.FailedState
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InitialAdministratorBootstrapJobRequestHandlerTests {
    private val bootstrapService = mock(InitialAdministratorBootstrapService::class.java)
    private val handler = InitialAdministratorBootstrapJobRequestHandler(bootstrapService)

    @Test
    fun `handler delegates running to bootstrap service`() {
        val organisationId = uuidV7()
        val request = InitialAdministratorBootstrapJobRequest(organisationId)

        handler.run(request)

        verify(bootstrapService).bootstrap(organisationId)
    }

    @Test
    fun `a failure reaches JobRunr as the closed code with no message and no cause`() {
        val organisationId = uuidV7()
        val original =
            DataIntegrityViolationException(
                "insert into user_account (email) values ('$EMAIL')",
                IllegalStateException("Key (email)=($EMAIL) already exists"),
            )
        doThrow(original).`when`(bootstrapService).bootstrap(organisationId)

        val thrown =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(InitialAdministratorBootstrapJobRequest(organisationId))
            }

        assertEquals("DATABASE_ERROR", thrown.message)
        assertNull(thrown.cause)
        assertEquals(0, thrown.suppressed.size)
        val text = StringWriter().also { thrown.printStackTrace(PrintWriter(it)) }.toString()
        assertFalse(text.contains(EMAIL))
        assertFalse(text.contains("insert into"))
    }

    @Test
    fun `a failure no recorder wrote is logged with classes and frames but no message`() {
        val organisationId = uuidV7()
        doThrow(
            DataIntegrityViolationException(
                "insert into user_account (email) values ('$EMAIL')",
                IllegalStateException("Key (email)=($EMAIL) already exists"),
            ),
        ).`when`(bootstrapService).bootstrap(organisationId)
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val logger = LoggerFactory.getLogger(SanitisedJobFailureException::class.java) as Logger
        logger.addAppender(appender)
        try {
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(InitialAdministratorBootstrapJobRequest(organisationId))
            }
        } finally {
            logger.detachAppender(appender)
        }

        val event = appender.list.single()
        assertNull(event.throwableProxy)
        val logged = event.formattedMessage
        assertTrue(logged.contains(organisationId.toString()))
        assertTrue(
            logged.contains("exceptionClass=${DataIntegrityViolationException::class.java.name}"),
        )
        assertTrue(logged.contains("rootCauseClass=${IllegalStateException::class.java.name}"))
        assertTrue(logged.contains("\tat "))
        assertFalse(logged.contains(EMAIL))
        assertFalse(logged.contains("insert into"))
    }

    @Test
    fun `an interruption in the original chain is preserved without its message`() {
        val organisationId = uuidV7()
        doThrow(IllegalStateException("stopped for $EMAIL", InterruptedException("secret $EMAIL")))
            .`when`(bootstrapService)
            .bootstrap(organisationId)

        val thrown =
            assertFailsWith<SanitisedJobFailureException> {
                handler.run(InitialAdministratorBootstrapJobRequest(organisationId))
            }

        val cause = assertNotNull(thrown.cause)
        assertTrue(cause is InterruptedException)
        assertNull(cause.message)
        assertNull(cause.cause)
    }

    @Test
    fun `JobRunr retries the sanitised failure exactly as it retried the original`() {
        val original = IllegalStateException("provider said $EMAIL")
        val sanitised = SanitisedJobFailureException.forFailure(original)

        val before = FailedState("Job processing failed", original)
        val after = FailedState("Job processing failed", sanitised)

        assertEquals(false, before.mustNotRetry())
        assertEquals(before.mustNotRetry(), after.mustNotRetry())
        assertEquals("INVALID_STATE", after.exceptionMessage)
        assertNull(after.exceptionCauseMessage)
        assertFalse(after.stackTrace.contains(EMAIL))
    }

    private companion object {
        const val EMAIL = "jane.doe@acme.test"
    }
}
