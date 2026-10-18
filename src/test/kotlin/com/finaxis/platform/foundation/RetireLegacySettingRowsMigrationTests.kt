package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * `V31` (#272) closes the current `base_currency` and `business-date.timezone` setting rows and
 * leaves every other row alone. Each case runs in a transaction that is rolled back.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RetireLegacySettingRowsMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    @Test
    fun `no current legacy row survives the migration on the bootstrap database`() {
        assertEquals(0, currentLegacyRows())
    }

    @Test
    fun `current legacy rows are closed and other rows are untouched`() {
        inRolledBackTransaction {
            val org = seedOrganisation()
            val currency = insertSetting(org, "base_currency", "\"KES\"", "2026-01-01T00:00:00Z")
            val zone =
                insertSetting(org, "business-date.timezone", "\"UTC\"", "2026-01-01T00:00:00Z")
            val other = insertSetting(org, "maker-checker.user-invites", "true", FUTURE)

            runMigration()

            assertNotNull(effectiveTo(currency))
            assertNotNull(effectiveTo(zone))
            assertNull(effectiveTo(other))
        }
    }

    @Test
    fun `a saved false of a maker-checker switch is closed and a true or another key is not`() {
        inRolledBackTransaction {
            val org = seedOrganisation()
            val invitesFalse =
                insertSetting(org, INVITES, "\"false\"", "2026-01-01T00:00:00Z")
            val branchesFalse =
                insertSetting(org, BRANCHES, "\"false\"", "2026-01-01T00:00:00Z")
            val orgTrue = seedOrganisation()
            val invitesTrue = insertSetting(orgTrue, INVITES, "\"true\"", "2026-01-01T00:00:00Z")
            val timezone = insertSetting(orgTrue, "default_timezone", "\"false\"", FUTURE)

            runMigration()

            assertNotNull(effectiveTo(invitesFalse))
            assertNotNull(effectiveTo(branchesFalse))
            assertNull(effectiveTo(invitesTrue), "a true is already required and is kept")
            assertNull(effectiveTo(timezone), "only the two switches are normalised")
        }
    }

    @Test
    fun `a future dated legacy row is closed after it starts and a re-run is a no-op`() {
        inRolledBackTransaction {
            val org = seedOrganisation()
            val future = insertSetting(org, "base_currency", "\"KES\"", FUTURE)

            runMigration()

            val closed = effectiveTo(future)
            assertNotNull(closed)
            assertEquals(
                true,
                jdbcTemplate.queryForObject(
                    "SELECT effective_to > effective_from FROM organisation_setting WHERE id = ?",
                    Boolean::class.java,
                    future,
                ),
            )

            runMigration()

            assertEquals(closed, effectiveTo(future))
        }
    }

    private fun currentLegacyRows(): Int =
        requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM organisation_setting WHERE effective_to IS NULL " +
                    "AND setting_key IN ('base_currency', 'business-date.timezone')",
                Int::class.java,
            ),
        )

    private fun effectiveTo(id: UUID): Any? =
        jdbcTemplate.queryForObject(
            "SELECT effective_to FROM organisation_setting WHERE id = ?",
            { rs, _ -> rs.getTimestamp(1) },
            id,
        )

    private fun runMigration() {
        val sql =
            ClassPathResource("db/migration/V31__retire_legacy_setting_rows.sql")
                .inputStream
                .use { String(it.readAllBytes()) }
        jdbcTemplate.execute(sql)
    }

    private fun seedOrganisation(): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, created_at, updated_at
            ) VALUES (?, ?, 'Legacy Settings Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            id,
            "LS-" + id,
        )
        return id
    }

    private fun insertSetting(
        organisationId: UUID,
        key: String,
        jsonValue: String,
        effectiveFrom: String,
    ): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation_setting (
                id, organisation_id, setting_key, setting_value, value_type, effective_from,
                created_at, updated_at
            ) VALUES (?, ?, ?, ?::jsonb, 'STRING', ?::timestamptz, NOW(), NOW())
            """.trimIndent(),
            id,
            organisationId,
            key,
            jsonValue,
            effectiveFrom,
        )
        return id
    }

    private fun <T> inRolledBackTransaction(block: () -> T): T =
        requireNotNull(
            transactionTemplate.execute { transaction ->
                transaction.setRollbackOnly()
                block()
            },
        )

    private companion object {
        const val FUTURE = "2999-01-01T00:00:00Z"
        const val INVITES = "require_maker_checker_for_user_invites"
        const val BRANCHES = "require_maker_checker_for_branch_creation"
    }
}
