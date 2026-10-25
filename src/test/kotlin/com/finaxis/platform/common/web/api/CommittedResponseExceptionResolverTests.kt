package com.finaxis.platform.common.web.api

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.context.request.async.AsyncRequestNotUsableException
import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A failure after the response started aborts the transfer and writes no problem document. */
class CommittedResponseExceptionResolverTests {
    private val resolver = CommittedResponseExceptionResolver()
    private val logger =
        LoggerFactory.getLogger(CommittedResponseExceptionResolver::class.java) as Logger
    private val appender = ListAppender<ILoggingEvent>().also { it.start() }
    private val previousLevel = logger.level

    init {
        logger.level = Level.DEBUG
        logger.addAppender(appender)
    }

    @AfterEach
    fun detach() {
        logger.detachAppender(appender)
        logger.level = previousLevel
    }

    private fun committed() = MockHttpServletResponse().also { it.setCommitted(true) }

    private fun request() = MockHttpServletRequest("GET", "/api/v1/tenant/audit-events/export")

    @Test
    fun `a response that has not started is left to the other resolvers`() {
        val response = MockHttpServletResponse()

        val result = resolver.resolveException(request(), response, null, IllegalStateException())

        assertNull(result)
        assertTrue(appender.list.isEmpty())
        assertEquals("", response.contentAsString)
    }

    @Test
    fun `a failure after the response started is logged by class and aborts the transfer`() {
        val response = committed()
        val secret = IllegalStateException("db-secret-message", RuntimeException("cause-secret"))

        val aborted =
            assertFailsWith<ResponseAbortedException> {
                resolver.resolveException(request(), response, null, secret)
            }

        assertEquals("", response.contentAsString, "nothing is written to a committed response")
        assertTrue(aborted.cause == null, "no cause is carried to the container")
        assertTrue(!aborted.message.orEmpty().contains("secret"), aborted.message)
        assertTrue(aborted.message.orEmpty().contains("IllegalStateException"))
        val line = appender.list.single { it.level == Level.WARN }
        assertTrue(line.formattedMessage.contains("exceptionClass=java.lang.IllegalStateException"))
        assertTrue(line.formattedMessage.contains("rootCauseClass=java.lang.RuntimeException"))
        assertTrue(!line.formattedMessage.contains("secret"), line.formattedMessage)
        assertNull(line.throwableProxy, "the throwable never reaches the logger")
        assertTrue(appender.list.none { it.level == Level.ERROR })
    }

    @Test
    fun `a client disconnect aborts the transfer too, and is only logged at debug`() {
        val disconnects =
            listOf(
                org.apache.catalina.connector
                    .ClientAbortException(IOException("Broken pipe")),
                AsyncRequestNotUsableException("ServletOutputStream failed", IOException("x")),
                IOException("Connection reset by peer"),
                EOFException(),
                IOException(
                    "outer",
                    org.apache.catalina.connector
                        .ClientAbortException(IOException()),
                ),
            )

        disconnects.forEach { failure ->
            val response = committed()

            assertFailsWith<ResponseAbortedException>(failure.toString()) {
                resolver.resolveException(request(), response, null, failure)
            }
            assertEquals("", response.contentAsString)
        }

        assertTrue(appender.list.none { it.level.isGreaterOrEqual(Level.WARN) }, "no WARN or ERROR")
        assertEquals(disconnects.size, appender.list.count { it.level == Level.DEBUG })
        assertTrue(appender.list.none { it.formattedMessage.contains("Broken pipe") })
    }

    @Test
    fun `a lost database connection is never a client abort, it aborts and warns`() {
        // PgJDBC: DataAccessException -> PSQLException -> EOFException, or a reset socket.
        val lost =
            listOf(
                DataAccessResourceFailureException("secret", SQLException("io", EOFException())),
                IllegalStateException("wrapped", EOFException()),
                DataAccessResourceFailureException(
                    "secret",
                    SQLException("io", SocketException("Connection reset")),
                ),
                RuntimeException("wrapped", SocketException("Broken pipe")),
            )

        lost.forEach { failure ->
            assertFailsWith<ResponseAbortedException>(failure.toString()) {
                resolver.resolveException(request(), committed(), null, failure)
            }
        }

        assertEquals(lost.size, appender.list.count { it.level == Level.WARN })
        assertTrue(appender.list.none { it.level == Level.DEBUG })
        assertTrue(appender.list.none { it.formattedMessage.contains("secret") })
    }

    @Test
    fun `the container's error-page dispatches that follow are quiet`() {
        listOf(
            jakarta.servlet.DispatcherType.ERROR,
            jakarta.servlet.DispatcherType.INCLUDE,
            jakarta.servlet.DispatcherType.FORWARD,
        ).forEach { type ->
            val result =
                resolver.resolveException(
                    request().also { it.dispatcherType = type },
                    committed(),
                    null,
                    IllegalStateException("secret"),
                )

            assertNotNull(result, type.name)
            assertTrue(result.isEmpty)
        }
        assertTrue(appender.list.none { it.level.isGreaterOrEqual(Level.WARN) })
    }

    @Test
    fun `it runs ahead of every other exception resolver`() {
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, resolver.order)
    }
}
