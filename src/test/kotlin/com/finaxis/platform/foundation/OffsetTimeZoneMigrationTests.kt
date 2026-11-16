package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals

/**
 * `V37`'s rewrite of raw whole-hour offsets to their IANA equivalents (#331), proved on
 * PostgreSQL.
 *
 * Flyway ran long before these tests, against a database holding no pre-#276 zone, so they seed
 * the shapes an upgraded database can hold (every `ZoneId.of` spelling of an offset on an
 * organisation, and anything at all on a branch) and execute the migration file itself, read from
 * the classpath, against them. The file is idempotent by design, which is what makes re-running it
 * legitimate. Each test removes its organisations afterwards.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class OffsetTimeZoneMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
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
    fun `every ZoneId spelling of a whole-hour offset becomes the IANA id of that offset`() {
        val expected =
            mapOf(
                "+3" to "Etc/GMT-3",
                "+03" to "Etc/GMT-3",
                "+0300" to "Etc/GMT-3",
                "+03:00" to "Etc/GMT-3",
                "+03:00:00" to "Etc/GMT-3",
                "+030000" to "Etc/GMT-3",
                "UTC+3" to "Etc/GMT-3",
                "GMT+03:00" to "Etc/GMT-3",
                "UT+03" to "Etc/GMT-3",
                "-05:00" to "Etc/GMT+5",
                "GMT-5" to "Etc/GMT+5",
                "+14:00" to "Etc/GMT-14",
                "-12:00" to "Etc/GMT+12",
                "+00:00" to "UTC",
                "-0" to "UTC",
                "UTC+0" to "UTC",
                "Z" to "UTC",
                "UT" to "UTC",
            )
        val seeded = expected.keys.associateWith { seedOrganisation(it) }

        runMigration()

        expected.forEach { (stored, zone) ->
            assertEquals(zone, organisationZone(seeded.getValue(stored)), stored)
            // The same fixed offset, so every local date the application computes is unchanged.
            assertEquals(
                ZoneId.of(stored).rules.getOffset(Instant.EPOCH),
                ZoneId.of(zone).rules.getOffset(Instant.EPOCH),
                stored,
            )
        }
    }

    @Test
    fun `a branch zone is rewritten by the same rule and an IANA id is never touched`() {
        val organisationId = seedOrganisation("Africa/Nairobi")
        val offset = seedBranch(organisationId, "+03:00")
        val prefixed = seedBranch(organisationId, "UTC-4")
        val region = seedBranch(organisationId, "Europe/London")

        runMigration()

        assertEquals("Africa/Nairobi", organisationZone(organisationId))
        assertEquals("Etc/GMT-3", branchZone(offset))
        assertEquals("Etc/GMT+4", branchZone(prefixed))
        assertEquals("Europe/London", branchZone(region))
    }

    @Test
    fun `what has no exact IANA equivalent is left for the operator`() {
        val untouched =
            listOf(
                // Minutes and seconds: no Etc id names them.
                "+05:30",
                "+0545",
                "-03:30:00",
                // Outside the Etc range, though ZoneId.of reads them.
                "+15:00",
                "-13",
                // Not an offset ZoneId.of reads, nor an id: a branch create before #276 kept it.
                "+3:00",
                "+100",
                "Mars/Olympus",
                "africa/nairobi",
            )
        val organisationId = seedOrganisation("Africa/Nairobi")
        val seeded = untouched.associateWith { seedBranch(organisationId, it) }

        runMigration()

        untouched.forEach { stored -> assertEquals(stored, branchZone(seeded.getValue(stored))) }
    }

    @Test
    fun `running it again changes nothing, and only the timezone column ever changes`() {
        val organisationId = seedOrganisation("+03:00")
        val branchId = seedBranch(organisationId, "GMT+3")
        val organisationBefore = organisationRowWithoutZone(organisationId)
        val branchBefore = branchRowWithoutZone(branchId)

        runMigration()
        runMigration()

        assertEquals("Etc/GMT-3", organisationZone(organisationId))
        assertEquals("Etc/GMT-3", branchZone(branchId))
        assertEquals(organisationBefore, organisationRowWithoutZone(organisationId))
        assertEquals(branchBefore, branchRowWithoutZone(branchId))
    }

    @Test
    fun `every zone the migration writes is one PostgreSQL reads with the right sign`() {
        val organisationId = seedOrganisation("+03:00")

        runMigration()

        // 12:00 UTC is 15:00 in UTC+3. A raw '+03:00' would answer 09:00 (the POSIX sign).
        val localHour =
            jdbcTemplate.queryForObject(
                "SELECT EXTRACT(HOUR FROM TIMESTAMPTZ '2026-01-01 12:00:00+00' AT TIME ZONE " +
                    "timezone)::INT FROM organisation WHERE id = ?",
                Int::class.java,
                organisationId,
            )
        assertEquals(LOCAL_HOUR_IN_UTC_PLUS_THREE, localHour)
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM pg_proc WHERE proname = 'v37_iana_equivalent'",
                Int::class.java,
            ),
            "the migration's session-local function is dropped",
        )
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
            ) VALUES (?, ?, 'Zone Test', 'KE', 'KES', ?, 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "TZ-" + id.toString().take(CODE_SUFFIX),
            timezone,
        )
        organisations += id
        return id
    }

    private fun seedBranch(
        organisationId: UUID,
        timezone: String,
    ): UUID {
        val id = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO branch (
                id, organisation_id, branch_code, branch_name, branch_type, status, timezone,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'Zone Branch', 'OPERATIONS', 'ACTIVE', ?, NOW(), NOW())
            """.trimIndent(),
            id,
            organisationId,
            "B-" + id.toString().take(CODE_SUFFIX),
            timezone,
        )
        return id
    }

    private fun organisationZone(id: UUID): String? = zone("organisation", id)

    private fun branchZone(id: UUID): String? = zone("branch", id)

    private fun zone(
        table: String,
        id: UUID,
    ): String? =
        jdbcTemplate.queryForObject(
            "SELECT timezone FROM $table WHERE id = ?",
            String::class.java,
            id,
        )

    private fun organisationRowWithoutZone(id: UUID): String? = rowWithoutZone("organisation", id)

    private fun branchRowWithoutZone(id: UUID): String? = rowWithoutZone("branch", id)

    /** Every column of the row except `timezone`, as one comparable string. */
    private fun rowWithoutZone(
        table: String,
        id: UUID,
    ): String? =
        jdbcTemplate.queryForObject(
            "SELECT (to_jsonb(t) - 'timezone')::TEXT FROM $table t WHERE id = ?",
            String::class.java,
            id,
        )

    private companion object {
        const val MIGRATION = "db/migration/V37__normalise_offset_time_zones.sql"
        const val CODE_SUFFIX = 8
        const val LOCAL_HOUR_IN_UTC_PLUS_THREE = 15
    }
}
