package com.finaxis.platform.lifecycle.application

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.InvalidTransitionException
import com.finaxis.platform.common.transitions.TransitionGuardException
import com.finaxis.platform.common.transitions.TransitionNotAllowedException
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode.CONFLICT
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode.DATABASE_ERROR
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode.IDENTITY_PROVIDER_FAILED
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode.INVALID_STATE
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode.NOT_FOUND
import com.finaxis.platform.lifecycle.application.InitialAdministratorBootstrapFailureCode.UNEXPECTED
import com.finaxis.platform.lifecycle.application.port.outbound.IdentityProvisioningException
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyNoInteractions
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Unit tests for the closed bootstrap failure-code set and the recorder that stores it. */
class InitialAdministratorBootstrapFailureCodeTests {
    private val leaky = "insert into user_account (email) values ('jane.doe@acme.test') failed"
    private val store = mock<InitialAdministratorBootstrapStore>()
    private val recorder =
        InitialAdministratorBootstrapFailureRecorder(
            InitialAdministratorBootstrapFailureStatusWriter(store),
        )
    private val appender = ListAppender<ILoggingEvent>()
    private val recorderLogger =
        LoggerFactory.getLogger(InitialAdministratorBootstrapFailureRecorder::class.java) as Logger

    @BeforeEach
    fun attachAppender() {
        appender.start()
        recorderLogger.addAppender(appender)
    }

    @AfterEach
    fun detachAppender() {
        recorderLogger.detachAppender(appender)
    }

    private fun codeOf(failure: Throwable) = InitialAdministratorBootstrapFailureCode.from(failure)

    @Test
    fun `every failure the bootstrap flow raises maps to exactly one code by type`() {
        assertEquals(IDENTITY_PROVIDER_FAILED, codeOf(IdentityProvisioningException(leaky)))
        assertEquals(CONFLICT, codeOf(ConflictException(safeDetail = leaky)))
        assertEquals(NOT_FOUND, codeOf(ResourceNotFoundException(safeDetail = leaky)))
        assertEquals(INVALID_STATE, codeOf(IllegalStateException(leaky)))
        assertEquals(INVALID_STATE, codeOf(IllegalArgumentException(leaky)))
        assertEquals(INVALID_STATE, codeOf(InvalidOperationException(safeDetail = leaky)))
        assertEquals(INVALID_STATE, codeOf(InvalidTransitionException(leaky)))
        assertEquals(INVALID_STATE, codeOf(TransitionNotAllowedException(leaky)))
        assertEquals(INVALID_STATE, codeOf(TransitionGuardException(leaky)))
        assertEquals(DATABASE_ERROR, codeOf(DataIntegrityViolationException(leaky)))
    }

    @Test
    fun `an unknown exception maps to UNEXPECTED whatever its message says`() {
        assertEquals(UNEXPECTED, codeOf(RuntimeException(leaky)))
        assertEquals(UNEXPECTED, codeOf(Exception(leaky)))
        assertEquals(UNEXPECTED, codeOf(StackOverflowError()))
    }

    @Test
    fun `a stored value is read back as its code and anything else as UNEXPECTED`() {
        assertNull(InitialAdministratorBootstrapFailureCode.fromStored(null))
        InitialAdministratorBootstrapFailureCode.entries.forEach {
            assertSame(it, InitialAdministratorBootstrapFailureCode.fromStored(it.name))
        }
        assertEquals(UNEXPECTED, InitialAdministratorBootstrapFailureCode.fromStored(leaky))
    }

    @Test
    fun `the recorder stores only the code and logs the classes and frames, no message`() {
        val organisationId = uuidV7()
        val failure =
            IllegalStateException("outer $leaky", IdentityProvisioningException("cause $leaky"))

        recorder.recordFailure(organisationId, failure)

        verify(store).updateStatus(
            organisationId = organisationId,
            status = InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = INVALID_STATE,
            incrementAttempts = false,
        )
        val event = appender.list.single()
        assertEquals(Level.ERROR, event.level)
        assertTrue(event.formattedMessage.contains(organisationId.toString()))
        assertTrue(event.formattedMessage.contains("INVALID_STATE"))
        assertNull(event.throwableProxy)
        val logged = event.formattedMessage
        assertTrue(logged.contains("exceptionClass=${IllegalStateException::class.java.name}"))
        val root = IdentityProvisioningException::class.java.name
        assertTrue(logged.contains("rootCauseClass=$root"))
        assertTrue(logged.contains("Caused by: $root"))
        assertTrue(logged.contains("\tat ${javaClass.name}."))
        assertFalse(logged.contains("jane.doe@acme.test"))
        assertFalse(logged.contains("insert into"))
    }

    @Test
    fun `a failure whose stack cannot be rendered is still recorded as failed`() {
        val organisationId = uuidV7()
        val hostile =
            object : IllegalStateException(leaky) {
                override val cause: Throwable get() = error("hostile getCause")

                override fun getStackTrace(): Array<StackTraceElement> = error("hostile stack")
            }

        recorder.recordFailure(organisationId, hostile)

        verify(store).updateStatus(
            organisationId = organisationId,
            status = InitialAdministratorBootstrapStatus.FAILED,
            lastFailureCode = INVALID_STATE,
            incrementAttempts = false,
        )
        val logged = appender.list.single().formattedMessage
        assertTrue(logged.contains("rootCauseClass=<unavailable>"))
        assertTrue(logged.contains("<stack unavailable: java.lang.IllegalStateException>"))
        assertFalse(logged.contains("jane.doe@acme.test"))
    }

    @Test
    fun `inside a transaction nothing is logged or stored until it rolls back`() {
        val organisationId = uuidV7()
        TransactionSynchronizationManager.initSynchronization()
        try {
            recorder.recordFailure(organisationId, IllegalStateException(leaky))
            assertTrue(appender.list.isEmpty())

            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_COMMITTED)
            }
            assertTrue(appender.list.isEmpty())
            verifyNoInteractions(store)

            TransactionSynchronizationManager.getSynchronizations().forEach {
                it.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK)
            }
            assertEquals(Level.ERROR, appender.list.single().level)
            verify(store).updateStatus(
                organisationId = organisationId,
                status = InitialAdministratorBootstrapStatus.FAILED,
                lastFailureCode = INVALID_STATE,
                incrementAttempts = false,
            )
        } finally {
            TransactionSynchronizationManager.clearSynchronization()
        }
    }
}
