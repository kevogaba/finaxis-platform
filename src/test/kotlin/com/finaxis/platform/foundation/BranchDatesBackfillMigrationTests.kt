package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.sql.Date
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * `V18`'s best-effort backfill of `branch.opened_on` and `branch.closed_on`, proved on PostgreSQL.
 *
 * Flyway has long finished, against a database that holds no pre-release branch, by the time a
 * test runs, so the migration's own run is a no-op here. These tests therefore seed the shapes a
 * database upgraded from before the release holds - branches in `ACTIVE` and `CLOSED` with `NULL`
 * dates and the transition-log rows that happened to them - and execute the migration file itself
 * (read from the classpath, not restated, so a change to the file is a change to the test)
 * against them. The file is idempotent by design, which is what makes re-running it legitimate.
 *
 * Every test works in its own organisation and removes it afterwards, so the shared container
 * stays free of leftovers other suites would count. The migration itself is not scoped: running
 * it is a table-wide `UPDATE` on the shared container. That is benign - a branch the application
 * created is already stamped, so only these tests' own rows qualify - and the suites run serially.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchDatesBackfillMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val organisations = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededOrganisations() {
        organisations.forEach { organisationId ->
            jdbcTemplate.update(
                "DELETE FROM branch_transition_log WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update("DELETE FROM branch WHERE organisation_id = ?", organisationId)
            jdbcTemplate.update("DELETE FROM organisation WHERE id = ?", organisationId)
        }
        organisations.clear()
    }

    @Test
    fun `an active branch is opened on the day of its activation in the organisation timezone`() {
        // 22:30 UTC on the 10th is already 01:30 on the 11th in Nairobi (UTC+3): the UTC date
        // and the organisation's date differ, which is the case that proves the zone is used.
        val organisationId = seedOrganisation("Africa/Nairobi")
        val branchId = seedBranch(organisationId, "ACTIVE")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T22:30:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-03-11"), openedOn(branchId))
        assertNull(closedOn(branchId))
    }

    @Test
    fun `a zone behind UTC moves the date back rather than forward`() {
        val organisationId = seedOrganisation("America/New_York")
        val branchId = seedBranch(organisationId, "ACTIVE")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T02:00:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-03-09"), openedOn(branchId))
    }

    @Test
    fun `a closed branch gets both dates`() {
        val organisationId = seedOrganisation("Africa/Nairobi")
        val branchId = seedBranch(organisationId, "CLOSED")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T10:00:00Z")
        log(organisationId, branchId, "CLOSE", "ACTIVE", "CLOSED", "2026-04-02T21:30:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-03-10"), openedOn(branchId))
        assertEquals(LocalDate.parse("2026-04-03"), closedOn(branchId))
    }

    @Test
    fun `a branch closed from suspension gets its closing date from CLOSE_SUSPENDED`() {
        val organisationId = seedOrganisation("UTC")
        val branchId = seedBranch(organisationId, "CLOSED")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-01-05T08:00:00Z")
        log(organisationId, branchId, "SUSPEND", "ACTIVE", "SUSPENDED", "2026-02-01T08:00:00Z")
        val closedAt = "2026-02-20T08:00:00Z"
        log(organisationId, branchId, "CLOSE_SUSPENDED", "SUSPENDED", "CLOSED", closedAt)

        runMigration()

        assertEquals(LocalDate.parse("2026-01-05"), openedOn(branchId))
        assertEquals(LocalDate.parse("2026-02-20"), closedOn(branchId))
    }

    @Test
    fun `a reactivation does not move the first opening date`() {
        val organisationId = seedOrganisation("UTC")
        val branchId = seedBranch(organisationId, "ACTIVE")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-01-05T08:00:00Z")
        log(organisationId, branchId, "SUSPEND", "ACTIVE", "SUSPENDED", "2026-02-01T08:00:00Z")
        log(organisationId, branchId, "REACTIVATE", "SUSPENDED", "ACTIVE", "2026-03-01T08:00:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-01-05"), openedOn(branchId))
        assertNull(closedOn(branchId))
    }

    @Test
    fun `dates that are already stored are never overwritten`() {
        val organisationId = seedOrganisation("Africa/Nairobi")
        val branchId =
            seedBranch(
                organisationId,
                "CLOSED",
                openedOn = "2025-12-01",
                closedOn = "2026-01-15",
            )
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T10:00:00Z")
        log(organisationId, branchId, "CLOSE", "ACTIVE", "CLOSED", "2026-04-02T10:00:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2025-12-01"), openedOn(branchId))
        assertEquals(LocalDate.parse("2026-01-15"), closedOn(branchId))
    }

    @Test
    fun `a branch with no matching transition-log row keeps NULL dates`() {
        val organisationId = seedOrganisation("Africa/Nairobi")
        val unlogged = seedBranch(organisationId, "ACTIVE")
        val draftOnly = seedBranch(organisationId, "CLOSED")
        log(organisationId, draftOnly, "CREATE_DRAFT", "NONE", "DRAFT", "2026-03-01T09:00:00Z")

        runMigration()

        assertNull(openedOn(unlogged))
        assertNull(closedOn(unlogged))
        assertNull(openedOn(draftOnly))
        assertNull(closedOn(draftOnly))
    }

    @Test
    fun `a closing date before the opening date is clamped up to it`() {
        // Clock skew or a zone edge: the log says the branch closed the day before it opened.
        // chk_branch_dates would refuse that pair and abort the whole upgrade, so it is lifted to
        // the opening date.
        val organisationId = seedOrganisation("UTC")
        val derived = seedBranch(organisationId, "CLOSED")
        seedDraftSubmitActivate(organisationId, derived, activatedAt = "2026-05-10T10:00:00Z")
        log(organisationId, derived, "CLOSE", "ACTIVE", "CLOSED", "2026-05-09T10:00:00Z")
        val storedOpening = seedBranch(organisationId, "CLOSED", openedOn = "2026-06-01")
        log(organisationId, storedOpening, "CLOSE", "ACTIVE", "CLOSED", "2026-05-20T10:00:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-05-10"), openedOn(derived))
        assertEquals(LocalDate.parse("2026-05-10"), closedOn(derived))
        assertEquals(LocalDate.parse("2026-06-01"), openedOn(storedOpening))
        assertEquals(LocalDate.parse("2026-06-01"), closedOn(storedOpening))
    }

    @Test
    fun `an opening date after an already stored closing date is clamped down to it`() {
        val organisationId = seedOrganisation("UTC")
        val branchId = seedBranch(organisationId, "CLOSED", closedOn = "2026-01-15")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T10:00:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-01-15"), openedOn(branchId))
        assertEquals(LocalDate.parse("2026-01-15"), closedOn(branchId))
    }

    @Test
    fun `an invalid organisation timezone falls back to UTC instead of aborting`() {
        val organisationId = seedOrganisation("Not/AZone")
        val branchId = seedBranch(organisationId, "ACTIVE")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T22:30:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-03-10"), openedOn(branchId))
    }

    @Test
    fun `the branch's own timezone is not what selects the date`() {
        val organisationId = seedOrganisation("Africa/Nairobi")
        val branchId = seedBranch(organisationId, "ACTIVE", branchTimezone = "America/New_York")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T22:30:00Z")

        runMigration()

        assertEquals(LocalDate.parse("2026-03-11"), openedOn(branchId))
    }

    @Test
    fun `running the migration again changes nothing, and only the two date columns ever change`() {
        val organisationId = seedOrganisation("Africa/Nairobi")
        val branchId = seedBranch(organisationId, "CLOSED")
        seedDraftSubmitActivate(organisationId, branchId, activatedAt = "2026-03-10T10:00:00Z")
        log(organisationId, branchId, "CLOSE", "ACTIVE", "CLOSED", "2026-04-02T10:00:00Z")
        val before = untouchedColumns(branchId)

        runMigration()
        val afterFirst = datesAndUntouchedColumns(branchId)
        runMigration()
        val afterSecond = datesAndUntouchedColumns(branchId)

        assertEquals(before, untouchedColumns(branchId), "no column but the two dates may change")
        assertEquals(afterFirst, afterSecond, "a second run must be a no-op")
        assertEquals(LocalDate.parse("2026-04-02"), closedOn(branchId))
    }

    private fun runMigration() {
        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )
    }

    private fun seedOrganisation(timezone: String): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'Backfill Test', 'KE', 'KES', ?, 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "BF-" + id.toString().take(TENANT_CODE_SUFFIX),
            timezone,
        )
        organisations += id
        return id
    }

    private fun seedBranch(
        organisationId: UUID,
        status: String,
        openedOn: String? = null,
        closedOn: String? = null,
        branchTimezone: String = "Africa/Nairobi",
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO branch (
                id, organisation_id, branch_code, branch_name, branch_type, status, timezone,
                opened_on, closed_on, created_at, updated_at
            ) VALUES (?, ?, ?, 'Backfill Branch', 'OPERATIONS', ?, ?, ?, ?, NOW(), NOW())
            """.trimIndent(),
            id,
            organisationId,
            "B-" + id.toString().take(TENANT_CODE_SUFFIX),
            status,
            branchTimezone,
            openedOn?.let { Date.valueOf(it) },
            closedOn?.let { Date.valueOf(it) },
        )
        return id
    }

    private fun seedDraftSubmitActivate(
        organisationId: UUID,
        branchId: UUID,
        activatedAt: String,
    ) {
        val activation = Instant.parse(activatedAt)
        log(organisationId, branchId, "CREATE_DRAFT", "NONE", "DRAFT", activation - DAY)
        log(organisationId, branchId, "SUBMIT", "DRAFT", "PENDING_APPROVAL", activation - HOUR)
        log(organisationId, branchId, "ACTIVATE", "PENDING_APPROVAL", "ACTIVE", activation)
    }

    private fun log(
        organisationId: UUID,
        branchId: UUID,
        transition: String,
        from: String,
        to: String,
        at: String,
    ) = log(organisationId, branchId, transition, from, to, Instant.parse(at))

    private fun log(
        organisationId: UUID,
        branchId: UUID,
        transition: String,
        from: String,
        to: String,
        at: Instant,
    ) {
        val createdAt = OffsetDateTime.ofInstant(at, ZoneOffset.UTC)
        jdbcTemplate.update(
            """
            INSERT INTO branch_transition_log (
                organisation_id, branch_id, entity_id, transition_name, status_from, status_to,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            organisationId,
            branchId,
            branchId,
            transition,
            from,
            to,
            createdAt,
            createdAt,
        )
    }

    private fun openedOn(branchId: UUID): LocalDate? = date("opened_on", branchId)

    private fun closedOn(branchId: UUID): LocalDate? = date("closed_on", branchId)

    private fun date(
        column: String,
        branchId: UUID,
    ): LocalDate? =
        jdbcTemplate.queryForObject(
            "SELECT $column FROM branch WHERE id = ?",
            { rs, _ -> rs.getObject(column, LocalDate::class.java) },
            branchId,
        )

    /** Everything on the branch row except the two dates, as one comparable string. */
    private fun untouchedColumns(branchId: UUID): String? =
        jdbcTemplate.queryForObject(
            """
            SELECT concat_ws('|', status, timezone, address_jsonb, status_reason, created_at,
                             updated_at, updated_by, row_version)
            FROM branch WHERE id = ?
            """.trimIndent(),
            String::class.java,
            branchId,
        )

    private fun datesAndUntouchedColumns(branchId: UUID): String? =
        jdbcTemplate.queryForObject(
            """
            SELECT concat_ws('|', opened_on, closed_on, status, timezone, address_jsonb,
                             status_reason, created_at, updated_at, updated_by, row_version)
            FROM branch WHERE id = ?
            """.trimIndent(),
            String::class.java,
            branchId,
        )

    private companion object {
        const val MIGRATION = "db/migration/V18__branch_opened_closed_on_backfill.sql"
        const val TENANT_CODE_SUFFIX = 12
        val DAY: Duration = Duration.ofDays(1)
        val HOUR: Duration = Duration.ofHours(1)
    }
}
