package com.finaxis.platform.lifecycle

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapJobRequest
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapService
import org.awaitility.Awaitility.await
import org.jobrunr.jobs.states.FailedState
import org.jobrunr.scheduling.JobRequestScheduler
import org.jobrunr.storage.StorageProvider
import org.junit.jupiter.api.Test
import org.mockito.Mockito.doThrow
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs a failing bootstrap job through JobRunr's real failure path (background server on, real
 * handler, real `jobrunr_jobs` storage) and proves that neither the stored failed state nor any
 * log line JobRunr writes holds the message of the exception the service raised: the email
 * address and the SQL text. The service is replaced by a mock that raises the leaky exception,
 * so what is under test is the handler's sanitising and what JobRunr then logs and stores.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest(
    properties = [
        "jobrunr.background-job-server.enabled=true",
        "jobrunr.background-job-server.poll-interval-in-seconds=5",
    ],
)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BootstrapJobFailureLeakIntegrationTests(
    private val scheduler: JobRequestScheduler,
    private val storageProvider: StorageProvider,
) {
    @MockitoBean
    private lateinit var bootstrapService: InitialAdministratorBootstrapService

    @Test
    fun `a failed bootstrap job stores and logs the closed code and no exception text`() {
        val organisationId = uuidV7()
        doThrow(
            DataIntegrityViolationException(
                "insert into user_account (email) values ('$EMAIL')",
                IllegalStateException("Key (email)=($EMAIL) already exists"),
            ),
        ).`when`(bootstrapService).bootstrap(organisationId)
        val appender = ListAppender<ILoggingEvent>().also { it.start() }
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as Logger
        root.addAppender(appender)
        val jobId = uuidV7()

        try {
            scheduler.enqueue(jobId, InitialAdministratorBootstrapJobRequest(organisationId))
            // JobRunr saves the failed state first and logs "processing failed" after it, so wait
            // for both before the capture is detached.
            await().atMost(Duration.ofSeconds(90)).pollInterval(Duration.ofSeconds(1)).until {
                storageProvider.getJobById(jobId).jobStates.any { it is FailedState } &&
                    appender.list.any { it.formattedMessage.contains("processing failed") }
            }
            root.detachAppender(appender)

            val failed =
                assertNotNull(
                    storageProvider
                        .getJobById(jobId)
                        .jobStates
                        .filterIsInstance<FailedState>()
                        .first(),
                )
            assertEquals("DATABASE_ERROR", failed.exceptionMessage)
            assertNull(failed.exceptionCauseMessage)
            assertFalse(failed.mustNotRetry())
            listOf(failed.message, failed.exceptionMessage, failed.stackTrace).forEach {
                assertFalse(it.contains(EMAIL))
                assertFalse(it.contains("insert into"))
            }
            val rendered = appender.list.map { render(it) }
            assertTrue(rendered.any { it.contains("DATABASE_ERROR") }, "JobRunr logged the failure")
            rendered.forEach {
                assertFalse(it.contains(EMAIL), it)
                assertFalse(it.contains("insert into"), it)
            }
        } finally {
            root.detachAppender(appender)
            // The context is cached for later test classes: stop JobRunr retrying the job.
            runCatching { storageProvider.deletePermanently(jobId) }
        }
    }

    private fun render(event: ILoggingEvent): String =
        event.formattedMessage + (event.throwableProxy?.let { proxyText(it) } ?: "")

    private fun proxyText(proxy: ch.qos.logback.classic.spi.IThrowableProxy): String =
        proxy.message.orEmpty() + (proxy.cause?.let { proxyText(it) } ?: "")

    private companion object {
        const val EMAIL = "jane.doe@acme.test"
    }
}
