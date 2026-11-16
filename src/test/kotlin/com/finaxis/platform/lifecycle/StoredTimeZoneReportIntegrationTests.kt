package com.finaxis.platform.lifecycle

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.lifecycle.adapter.inbound.startup.StoredTimeZoneStartupWarning
import com.finaxis.platform.lifecycle.application.StoredTimeZoneReport
import com.finaxis.platform.lifecycle.application.StoredTimeZoneStore
import com.finaxis.platform.lifecycle.domain.TimeZoneRule
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The stored time-zone report and its startup warning (#331), on PostgreSQL: the store compares
 * against `TimeZoneRule`'s own list (so `Etc/GMT-3` passes and PostgreSQL-only spellings do not),
 * the counts are exact, the sample is bounded and in id order, and the warning names ids but
 * never the stored text.
 *
 * Other suites share the container, so the counts are compared before and after seeding rather
 * than against zero, and each test removes its own organisations.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class StoredTimeZoneReportIntegrationTests(
    private val jdbcTemplate: JdbcTemplate,
    private val store: StoredTimeZoneStore,
    private val report: StoredTimeZoneReport,
    private val warning: StoredTimeZoneStartupWarning,
) {
    private val organisations = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededOrganisations() {
        organisations.forEach { organisationId ->
            jdbcTemplate.update("DELETE FROM branch WHERE organisation_id = ?", organisationId)
            jdbcTemplate.update("DELETE FROM organisation WHERE id = ?", organisationId)
        }
        organisations.clear()
    }

    @Test
    fun `counts exactly the organisations and branches whose zone the rule refuses`() {
        val before = report.refused()
        val halfHour = seedOrganisation("+05:30")
        val etc = seedOrganisation("Etc/GMT-3")
        val misspelt = seedBranch(etc, "africa/nairobi")
        seedBranch(etc, "Africa/Nairobi")
        seedBranch(halfHour, "UTC")

        val after = report.refused()

        assertEquals(before.organisations.count + 1, after.organisations.count)
        assertEquals(before.branches.count + 1, after.branches.count)
        val rule = TimeZoneRule.acceptedIds()
        val refusedOrganisations = store.organisationsOutside(rule, Int.MAX_VALUE).sampleIds
        val refusedBranches = store.branchesOutside(rule, Int.MAX_VALUE).sampleIds
        assertTrue(halfHour in refusedOrganisations)
        assertFalse(etc in refusedOrganisations, "Etc/GMT-3 is an IANA region id")
        val seededBranches = branchesOf(etc, halfHour).toSet()
        assertEquals(listOf(misspelt), refusedBranches.filter { it in seededBranches })
    }

    @Test
    fun `the sample is bounded and in id order`() {
        repeat(StoredTimeZoneReport.SAMPLE_SIZE + 2) { seedOrganisation("+05:45") }

        val sample = report.refused().organisations.sampleIds

        assertEquals(StoredTimeZoneReport.SAMPLE_SIZE, sample.size)
        // PostgreSQL orders uuids by their unsigned bytes, as their text sorts; UUID.compareTo
        // compares signed halves and would disagree.
        assertEquals(sample.sortedBy { it.toString() }, sample)
    }

    @Test
    fun `the startup warning names the ids and never the stored text`() {
        // Ids that sort before any random one, so the sample, which is taken in id order, holds
        // them whatever other suites left behind.
        val organisationId = seedOrganisation("+05:30", LOWEST_ORGANISATION_ID)
        val branchId = seedBranch(organisationId, "Mars/Olympus", LOWEST_BRANCH_ID)

        val warnings = captureWarnings { warning.afterSingletonsInstantiated() }

        val message = warnings.single().formattedMessage
        assertTrue(message.contains(organisationId.toString()), message)
        assertTrue(message.contains(branchId.toString()), message)
        assertTrue(message.contains("docs/operations/tenant-settings.md"), message)
        assertFalse(message.contains("+05:30"), "no stored text: $message")
        assertFalse(message.contains("Mars/Olympus"), "no stored text: $message")
    }

    private fun branchesOf(vararg organisationIds: UUID): List<UUID> =
        organisationIds.flatMap { organisationId ->
            jdbcTemplate
                .queryForList(
                    "SELECT id FROM branch WHERE organisation_id = ?",
                    UUID::class.java,
                    organisationId,
                ).filterNotNull()
        }

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

    private fun seedOrganisation(
        timezone: String,
        id: UUID = UUID.randomUUID(),
    ): UUID {
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'Zone Report', 'KE', 'KES', ?, 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "ZR-" + id.toString().take(CODE_SUFFIX),
            timezone,
        )
        organisations += id
        return id
    }

    private fun seedBranch(
        organisationId: UUID,
        timezone: String,
        id: UUID = UUID.randomUUID(),
    ): UUID {
        jdbcTemplate.update(
            """
            INSERT INTO branch (
                id, organisation_id, branch_code, branch_name, branch_type, status, timezone,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'Zone Branch', 'OPERATIONS', 'ACTIVE', ?, NOW(), NOW())
            """.trimIndent(),
            id,
            organisationId,
            "ZB-" + id.toString().take(CODE_SUFFIX),
            timezone,
        )
        return id
    }

    private companion object {
        const val CODE_SUFFIX = 8
        val LOWEST_ORGANISATION_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000331")
        val LOWEST_BRANCH_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000332")
    }
}
