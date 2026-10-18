package com.finaxis.platform.notifications

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.notifications.adapter.outbound.jobrunr.SendWelcomeEmailJobRequest
import com.finaxis.platform.notifications.adapter.outbound.jobrunr.SendWelcomeEmailJobRequestHandler
import com.finaxis.platform.notifications.application.port.outbound.WelcomeEmailRecipientDirectory
import com.finaxis.platform.notifications.application.port.outbound.email.EmailGateway
import com.finaxis.platform.notifications.application.port.outbound.email.PermanentEmailDeliveryException
import com.finaxis.platform.notifications.application.port.outbound.email.RetryableEmailDeliveryException
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.storage.StorageProvider
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.times
import org.mockito.kotlin.any
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.time.Duration
import java.util.Collections
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs a failing welcome-email job through JobRunr's real failure path (background server on,
 * real handler and recipient directory, real `jobrunr_jobs` storage) and proves that neither the
 * stored failed state, nor the stored job row, nor any log line JobRunr writes holds the message
 * or cause text of the exception the email gateway raised. Only the gateway is replaced, by a mock
 * that throws an exception whose messages carry [SENTINEL] (standing in for a recipient address
 * quoted by SMTP).
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class WelcomeEmailJobFailureLeakIntegrationTests(
    private val scheduler: JobRequestScheduler,
    private val storageProvider: StorageProvider,
    private val dsl: DSLContext,
) {
    @MockitoBean
    private lateinit var emailGateway: EmailGateway

    // A spy, so the other tests still resolve the real recipient.
    @MockitoSpyBean
    private lateinit var recipientDirectory: WelcomeEmailRecipientDirectory

    @Test
    fun `a retryable welcome email failure stores and logs no exception text`() {
        whenever(emailGateway.send(any())).thenThrow(
            RetryableEmailDeliveryException(
                "smtp timeout for $SENTINEL",
                IllegalStateException("connect $SENTINEL"),
            ),
        )

        val captured = runWelcomeEmailJob()

        assertFalse(captured.failed.mustNotRetry(), "a transient failure must stay retryable")
        assertEquals("RetryableEmailDeliveryException", captured.failed.exceptionMessage)
        verify(emailGateway, atLeastOnce()).send(any())
        assertNoSentinel(captured)
        assertNull(captured.failed.exceptionCauseMessage)
        assertHandlerWarning(captured, RETRYABLE_CLASS)
    }

    @Test
    fun `a permanent welcome email failure stores and logs no exception text`() {
        whenever(emailGateway.send(any())).thenThrow(
            PermanentEmailDeliveryException(
                "bad address $SENTINEL",
                IllegalStateException("smtp rejected $SENTINEL"),
            ),
        )

        val captured = runWelcomeEmailJob()

        assertTrue(captured.failed.mustNotRetry(), "a permanent failure must not be retried")
        assertEquals("PermanentEmailDeliveryException", captured.failed.exceptionMessage)
        verify(emailGateway, times(1)).send(any())
        assertNoSentinel(captured)
        assertNull(captured.failed.exceptionCauseMessage)
        assertHandlerWarning(captured, PERMANENT_CLASS)
    }

    @Test
    fun `a failing recipient lookup stores and logs no exception text and is retried`() {
        doThrow(
            DataAccessResourceFailureException(
                "could not read $SENTINEL",
                IllegalStateException("connection for $SENTINEL"),
            ),
        ).whenever(recipientDirectory).findRecipient(any(), any())

        val captured = runWelcomeEmailJob()

        assertFalse(captured.failed.mustNotRetry(), "a transient failure must stay retryable")
        assertEquals("DataAccessResourceFailureException", captured.failed.exceptionMessage)
        assertNoSentinel(captured)
        assertNull(captured.failed.exceptionCauseMessage)
        assertHandlerWarning(captured, LOOKUP_CLASS)
    }

    private fun runWelcomeEmailJob(): CapturedFailure {
        val jobId = uuidV7()
        val appender = ListAppender<ILoggingEvent>()
        // JobRunr threads append while this test reads: a synchronised list keeps reads safe.
        appender.list = Collections.synchronizedList(mutableListOf<ILoggingEvent>())
        appender.start()
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        try {
            scheduler.enqueue(
                jobId,
                SendWelcomeEmailJobRequest(
                    membershipId = uuidV7(),
                    userId = LOCAL_USER_ID,
                    organisationId = LOCAL_ORGANISATION_ID,
                ),
            )
            // JobRunr saves the failed state first and logs "processing failed" after it, so wait
            // for both before the capture is detached.
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).until {
                storageProvider.getJobById(jobId).jobStates.any { it is FailedState } &&
                    appender.snapshot().any { it.formattedMessage.contains("processing failed") }
            }
            root.detachAppender(appender)

            val failed =
                storageProvider
                    .getJobById(jobId)
                    .jobStates
                    .filterIsInstance<FailedState>()
                    .first()
            val jobRow =
                dsl
                    .resultQuery(
                        "select jobasjson from jobrunr_jobs where id = {0}",
                        jobId.toString(),
                    ).fetchSingle()
                    .get(0, String::class.java)
            return CapturedFailure(
                failed = failed,
                jobRow = jobRow,
                logLines = appender.snapshot().map { render(it) },
                handlerWarnings =
                    appender.snapshot().filter {
                        it.level == Level.WARN && it.loggerName == HANDLER_LOGGER
                    },
            )
        } finally {
            root.detachAppender(appender)
            // The context is cached for later test classes: stop JobRunr retrying the job.
            runCatching { storageProvider.deletePermanently(jobId) }
        }
    }

    private fun ListAppender<ILoggingEvent>.snapshot(): List<ILoggingEvent> =
        synchronized(list) { list.toList() }

    /**
     * The handler logs one message-free `WARN` before it throws: it names the exception class and
     * the root-cause class, and carries no throwable (so no message can be rendered from it).
     */
    private fun assertHandlerWarning(
        captured: CapturedFailure,
        exceptionClass: String,
    ) {
        assertTrue(captured.handlerWarnings.isNotEmpty(), "the handler logs a WARN")
        captured.handlerWarnings.forEach { warn ->
            assertTrue(
                warn.formattedMessage.contains("rootCauseClass=java.lang.IllegalStateException"),
            )
            assertNull(warn.throwableProxy)
        }
        assertTrue(
            captured.handlerWarnings.any {
                it.formattedMessage.contains("exceptionClass=$exceptionClass")
            },
        )
    }

    private fun assertNoSentinel(captured: CapturedFailure) {
        val stored =
            listOf(
                captured.failed.message,
                captured.failed.exceptionMessage,
                captured.failed.stackTrace,
                captured.jobRow,
            )
        (stored + captured.logLines).forEach { assertFalse(it.contains(SENTINEL), it) }
        assertTrue(captured.logLines.any { it.contains("processing failed") })
    }

    private fun render(event: ILoggingEvent): String =
        event.formattedMessage + (event.throwableProxy?.let { proxyText(it) } ?: "")

    private fun proxyText(proxy: ch.qos.logback.classic.spi.IThrowableProxy): String =
        proxy.message.orEmpty() + (proxy.cause?.let { proxyText(it) } ?: "")

    private class CapturedFailure(
        val failed: FailedState,
        val jobRow: String,
        val logLines: List<String>,
        val handlerWarnings: List<ILoggingEvent>,
    )

    private companion object {
        const val SENTINEL = "leak-victim@example.test"
        val HANDLER_LOGGER: String = SendWelcomeEmailJobRequestHandler::class.java.name
        val RETRYABLE_CLASS: String = RetryableEmailDeliveryException::class.java.name
        val PERMANENT_CLASS: String = PermanentEmailDeliveryException::class.java.name
        val LOOKUP_CLASS: String = DataAccessResourceFailureException::class.java.name

        // Seeded by V3__bootstrap_tenant_and_administrator.sql: the local administrator and the
        // organisation whose welcome-email recipient the directory resolves.
        val LOCAL_USER_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val LOCAL_ORGANISATION_ID: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    }
}
