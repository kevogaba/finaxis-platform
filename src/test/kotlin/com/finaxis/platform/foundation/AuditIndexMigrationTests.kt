package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.zaxxer.hikari.HikariDataSource
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.TestConstructor
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `V25`-`V28`, the audit search indexes (#183), are built `CONCURRENTLY` by non-transactional
 * migrations, proved on PostgreSQL with the application's own Flyway configuration. A
 * concurrent build fails inside a transaction block, so these migrations succeeding at all
 * proves the `.sql.conf` files are honoured; and it never finishes under Flyway's transactional
 * advisory lock, so it proves `transactional-lock: false` too.
 *
 * The invalid-index guard is proved on a scratch database migrated to `V24`, so the shared
 * schema is never touched: a valid index of the same name is kept, an INVALID one (what a failed
 * concurrent build leaves) stops the migration with the operator step in the message.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Timeout(300) // a concurrent build under a transactional lock would hang, not fail
class AuditIndexMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    private val flyway: Flyway,
    private val dataSource: DataSource,
) {
    @Test
    fun `the four audit indexes are valid and their migrations succeeded`() {
        INDEXES.forEach { (_, name) ->
            assertEquals(
                true,
                jdbcTemplate.queryForObject(
                    "SELECT indisvalid FROM pg_index WHERE indexrelid = ?::regclass",
                    Boolean::class.java,
                    name,
                ),
                name,
            )
        }
        assertEquals(
            INDEXES.keys.map(Int::toString).toSet(),
            jdbcTemplate
                .queryForList(
                    "SELECT version FROM flyway_schema_history " +
                        "WHERE version IN ('25', '26', '27', '28') AND success",
                    String::class.java,
                ).toSet(),
        )
    }

    @Test
    fun `a valid index of the same name is kept as it is`() {
        withDatabaseAtV24 { scratch, scratchFlyway ->
            scratch.execute(createIndexSql(BRANCH_INDEX))
            val before = indexOid(scratch, BRANCH_INDEX)

            scratchFlyway.migrate()

            assertEquals(before, indexOid(scratch, BRANCH_INDEX))
            INDEXES.values.forEach { assertTrue(isValid(scratch, it), it) }
        }
    }

    @Test
    fun `an INVALID index left by a failed concurrent build stops the migration`() {
        withDatabaseAtV24 { scratch, scratchFlyway ->
            scratch.execute(createIndexSql(BRANCH_INDEX))
            scratch.update(
                "UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass",
                BRANCH_INDEX,
            )

            val failure = assertFailsWith<FlywayException> { scratchFlyway.migrate() }

            val message = generateSequence<Throwable>(failure) { it.cause }.joinToString { "$it" }
            assertTrue(message.contains("$BRANCH_INDEX exists but is INVALID"), message)
            assertTrue(!isValid(scratch, BRANCH_INDEX), "the guard must not repair silently")
            // What the operator step's `flyway repair` is for (docs/operations).
            assertEquals(
                1,
                scratch.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '25' " +
                        "AND NOT success",
                    Int::class.java,
                ),
            )
        }
    }

    /** Creates a scratch database migrated to V24 with the application's Flyway settings. */
    private fun withDatabaseAtV24(block: (JdbcTemplate, Flyway) -> Unit) {
        val hikari = dataSource.unwrap(HikariDataSource::class.java)
        val name = "audit_index_${uuidV7().toString().replace("-", "")}"
        jdbcTemplate.execute("CREATE DATABASE $name")
        val url = hikari.jdbcUrl.replaceAfterLast('/', name)
        val scratchSource = DriverManagerDataSource(url, hikari.username, hikari.password)
        try {
            val configured =
                Flyway
                    .configure()
                    .configuration(flyway.configuration)
                    // Stated again: whether the copy above carries plugin settings is not
                    // something this test should depend on.
                    .configuration(mapOf("flyway.postgresql.transactional.lock" to "false"))
                    .dataSource(scratchSource)
            configured.target("24").load().migrate()
            block(JdbcTemplate(scratchSource), configured.target("latest").load())
        } finally {
            jdbcTemplate.execute("DROP DATABASE $name WITH (FORCE)")
        }
    }

    private fun createIndexSql(name: String) =
        "CREATE INDEX $name ON audit_event (organisation_id, branch_id, event_time DESC) " +
            "WHERE branch_id IS NOT NULL"

    private fun indexOid(
        scratch: JdbcTemplate,
        name: String,
    ): Long = scratch.queryForObject("SELECT ?::regclass::oid", Long::class.java, name)!!

    private fun isValid(
        scratch: JdbcTemplate,
        name: String,
    ): Boolean =
        scratch.queryForObject(
            "SELECT indisvalid FROM pg_index WHERE indexrelid = ?::regclass",
            Boolean::class.java,
            name,
        ) == true

    private companion object {
        const val BRANCH_INDEX = "idx_audit_event_organisation_branch_time"
        val INDEXES =
            mapOf(
                25 to BRANCH_INDEX,
                26 to "idx_audit_event_organisation_subject_time",
                27 to "idx_audit_event_organisation_outcome_time",
                28 to "idx_audit_event_organisation_severity_time",
            )
    }
}
