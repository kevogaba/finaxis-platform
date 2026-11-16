package com.finaxis.platform.lifecycle.adapter.inbound.startup

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.application.RefusedTimeZones
import com.finaxis.platform.lifecycle.application.StoredTimeZoneReport
import com.finaxis.platform.lifecycle.application.StoredTimeZoneStore
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.dao.DataAccessResourceFailureException
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The startup warning's three outcomes (#331): silent while every stored zone is accepted, one
 * `WARN` with the counts and ids when one is not, and one message-free `WARN` that does not stop
 * the boot when the database cannot answer. The PostgreSQL side is in
 * `StoredTimeZoneReportIntegrationTests`.
 */
class StoredTimeZoneStartupWarningTests {
    @Test
    fun `logs nothing while every stored zone is accepted`() {
        val warnings = captureWarnings { warningOver(FakeStore()).afterSingletonsInstantiated() }

        assertTrue(warnings.isEmpty(), "expected no WARN, got $warnings")
    }

    @Test
    fun `logs one warning naming the counts and ids of the refused zones`() {
        val organisationId = uuidV7()
        val branchId = uuidV7()
        val store =
            FakeStore(
                organisations = RefusedTimeZones(1, listOf(organisationId)),
                branches = RefusedTimeZones(BRANCH_COUNT, listOf(branchId)),
            )

        val warnings = captureWarnings { warningOver(store).afterSingletonsInstantiated() }

        val message = warnings.single().formattedMessage
        assertTrue(message.startsWith("1 organisation(s) [$organisationId] and 12 branch(es)"))
        assertTrue(message.contains(branchId.toString()), message)
        assertTrue(message.contains("Time zones stored before the rule"), message)
        assertEquals(StoredTimeZoneReport.SAMPLE_SIZE, store.sampleSizes.single())
    }

    @Test
    fun `a database that cannot answer is logged without its message and the boot goes on`() {
        val store = FakeStore(failure = DataAccessResourceFailureException("host db-1 refused"))

        val warnings = captureWarnings { warningOver(store).afterSingletonsInstantiated() }

        val message = warnings.single().formattedMessage
        assertTrue(message.contains("DataAccessResourceFailureException"), message)
        assertFalse(message.contains("db-1"), "the exception message stays out: $message")
    }

    private fun warningOver(store: StoredTimeZoneStore) =
        StoredTimeZoneStartupWarning(StoredTimeZoneReport(store))

    private fun captureWarnings(action: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(StoredTimeZoneStartupWarning::class.java) as Logger
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger.addAppender(appender)
        try {
            action()
        } finally {
            logger.detachAppender(appender)
        }
        return appender.list.filter { it.level == Level.WARN }
    }

    private class FakeStore(
        private val organisations: RefusedTimeZones = RefusedTimeZones(0, emptyList()),
        private val branches: RefusedTimeZones = RefusedTimeZones(0, emptyList()),
        private val failure: RuntimeException? = null,
    ) : StoredTimeZoneStore {
        val sampleSizes = mutableSetOf<Int>()

        override fun organisationsOutside(
            accepted: Set<String>,
            sampleSize: Int,
        ): RefusedTimeZones {
            failure?.let { throw it }
            check("Africa/Nairobi" in accepted && "+03:00" !in accepted)
            sampleSizes += sampleSize
            return organisations
        }

        override fun branchesOutside(
            accepted: Set<String>,
            sampleSize: Int,
        ): RefusedTimeZones {
            sampleSizes += sampleSize
            return branches
        }
    }

    private companion object {
        const val BRANCH_COUNT = 12
    }
}
