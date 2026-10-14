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
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.test.context.TestConstructor
import java.nio.charset.StandardCharsets
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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
 *
 * `V29` and `V30` (#186) are the same convention applied to the subject user: `V29` adds the
 * nullable `audit_event.subject_user_id` column and `V30` builds its partial index. The column is
 * proved on the shared schema; `V30`'s guard is proved on a scratch database migrated to `V29`,
 * and the no-backfill cut-over (a row written before `V29` keeps a `NULL`) on one migrated to
 * `V28`.
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
        withDatabaseAt("24") { scratch, scratchFlyway ->
            scratch.execute(createIndexSql(BRANCH_INDEX))
            val before = indexOid(scratch, BRANCH_INDEX)

            scratchFlyway.migrate()

            assertEquals(before, indexOid(scratch, BRANCH_INDEX))
            INDEXES.values.forEach { assertTrue(isValid(scratch, it), it) }
        }
    }

    @Test
    fun `an INVALID index left by a failed concurrent build stops the migration`() {
        withDatabaseAt("24") { scratch, scratchFlyway ->
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

    @Test
    fun `audit_event carries a nullable uuid subject_user_id with no default and no foreign key`() {
        assertEquals(
            listOf(mapOf("data_type" to "uuid", "is_nullable" to "YES", "column_default" to null)),
            jdbcTemplate.queryForList(
                """
                SELECT data_type, is_nullable, column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'audit_event'
                  AND column_name = 'subject_user_id'
                """.trimIndent(),
            ),
        )
        // Audit rows outlive users, so the column is not a foreign key (V29's header comment).
        val foreignKeys =
            jdbcTemplate.queryForList(
                "SELECT pg_get_constraintdef(oid) FROM pg_constraint " +
                    "WHERE conrelid = 'audit_event'::regclass AND contype = 'f'",
                String::class.java,
            )
        assertTrue(
            foreignKeys.none { it?.contains("subject_user_id") == true },
            foreignKeys.toString(),
        )
        assertTrue(succeeded(jdbcTemplate, "29"))
    }

    @Test
    fun `the subject user index is valid, partial and its migration succeeded`() {
        assertTrue(isValid(jdbcTemplate, SUBJECT_USER_INDEX), SUBJECT_USER_INDEX)
        val definition =
            jdbcTemplate.queryForObject(
                "SELECT pg_get_indexdef(indexrelid) FROM pg_index WHERE indexrelid = ?::regclass",
                String::class.java,
                SUBJECT_USER_INDEX,
            )!!
        assertTrue(
            definition.contains("(organisation_id, subject_user_id, event_time DESC)"),
            definition,
        )
        assertTrue(definition.contains("WHERE (subject_user_id IS NOT NULL)"), definition)
        assertTrue(succeeded(jdbcTemplate, "30"))
    }

    @Test
    fun `a valid subject user index of the same name is kept as it is`() {
        withDatabaseAt("29") { scratch, scratchFlyway ->
            scratch.execute(createSubjectUserIndexSql())
            val before = indexOid(scratch, SUBJECT_USER_INDEX)

            scratchFlyway.migrate()

            assertEquals(before, indexOid(scratch, SUBJECT_USER_INDEX))
            assertTrue(isValid(scratch, SUBJECT_USER_INDEX), SUBJECT_USER_INDEX)
        }
    }

    @Test
    fun `an INVALID subject user index stops the migration and names the operator step`() {
        withDatabaseAt("29") { scratch, scratchFlyway ->
            scratch.execute(createSubjectUserIndexSql())
            scratch.update(
                "UPDATE pg_index SET indisvalid = false WHERE indexrelid = ?::regclass",
                SUBJECT_USER_INDEX,
            )

            val failure = assertFailsWith<FlywayException> { scratchFlyway.migrate() }

            val message = generateSequence<Throwable>(failure) { it.cause }.joinToString { "$it" }
            assertTrue(message.contains("$SUBJECT_USER_INDEX exists but is INVALID"), message)
            assertTrue(!isValid(scratch, SUBJECT_USER_INDEX), "the guard must not repair silently")
            assertEquals(
                1,
                scratch.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '30' " +
                        "AND NOT success",
                    Int::class.java,
                ),
            )
        }
    }

    @Test
    fun `a row written before V29 keeps a NULL subject_user_id`() {
        withDatabaseAt("28") { scratch, scratchFlyway ->
            val id =
                scratch.queryForObject(
                    """
                    INSERT INTO audit_event (
                        organisation_id, event_time, actor_type, event_type, entity_type,
                        action, outcome, severity
                    )
                    VALUES ('$PLATFORM_ORGANISATION_ID', now(), 'SYSTEM', 'TEST', 'TEST',
                            'TEST', 'SUCCESS', 'INFO')
                    RETURNING id
                    """.trimIndent(),
                    UUID::class.java,
                )

            scratchFlyway.migrate()

            assertNull(
                scratch.queryForObject(
                    "SELECT subject_user_id FROM audit_event WHERE id = ?",
                    UUID::class.java,
                    id,
                ),
            )
        }
    }

    @Test
    fun `V30 is non-transactional by its own sql conf, as V25-V28 are`() {
        val conf =
            ClassPathResource("db/migration/V30__audit_event_subject_user_index.sql.conf")
                .getContentAsString(StandardCharsets.UTF_8)
        assertTrue(conf.lines().contains("executeInTransaction=false"), conf)
    }

    @Test
    fun `V29 gives up on a lock held on audit_event instead of queueing audit writes behind it`() {
        withDatabaseAt("28") { scratch, scratchFlyway ->
            // An open transaction that has touched audit_event, as a request writing an audit row
            // would be. ACCESS EXCLUSIVE (ADD COLUMN) conflicts with it.
            val holder = scratch.dataSource!!.connection
            val executor = Executors.newSingleThreadExecutor()
            try {
                holder.autoCommit = false
                holder.createStatement().use {
                    it.execute(
                        "LOCK TABLE audit_event IN ACCESS SHARE MODE",
                    )
                }

                val started = System.nanoTime()
                val migration = executor.submit { scratchFlyway.migrate() }
                val failure =
                    runCatching { migration.get(LOCK_WAIT_CEILING.toSeconds(), TimeUnit.SECONDS) }
                        .exceptionOrNull()
                val elapsed = Duration.ofNanos(System.nanoTime() - started)

                assertTrue(
                    failure is ExecutionException,
                    "V29 must fail within $LOCK_WAIT_CEILING; got $failure after $elapsed",
                )
                val sqlStates =
                    causeChain(failure).filterIsInstance<SQLException>().map { it.sqlState }
                assertTrue("55P03" in sqlStates, "expected lock_timeout (55P03): $sqlStates")
                assertTrue(elapsed < LOCK_WAIT_CEILING, "elapsed $elapsed")

                holder.rollback()
                assertFalse(
                    subjectUserColumnExists(scratch),
                    "the rolled-back ALTER must not survive",
                )
                // Flyway records nothing for a rolled-back transactional migration, so the
                // operator retries the deploy and does not run `flyway repair` (the operator page).
                assertEquals(emptyList(), historyRows(scratch))
            } finally {
                runCatching { holder.close() }
                executor.shutdownNow()
                executor.awaitTermination(LOCK_WAIT_CEILING.toSeconds(), TimeUnit.SECONDS)
            }

            scratchFlyway.migrate()

            assertTrue(subjectUserColumnExists(scratch))
            assertEquals(
                1,
                scratch.queryForObject(
                    "SELECT count(*) FROM flyway_schema_history WHERE version = '29' AND success",
                    Int::class.java,
                ),
            )
        }
    }

    private fun subjectUserColumnExists(jdbc: JdbcTemplate): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM information_schema.columns " +
                "WHERE table_name = 'audit_event' AND column_name = 'subject_user_id'",
            Int::class.java,
        ) == 1

    private fun historyRows(jdbc: JdbcTemplate): List<Map<String, Any?>> =
        jdbc.queryForList(
            "SELECT version, success, script FROM flyway_schema_history " +
                "WHERE version IN ('29', '30') ORDER BY installed_rank",
        )

    private fun causeChain(failure: Throwable?): List<Throwable> =
        generateSequence(failure) { it.cause }.toList()

    /** Creates a scratch database migrated to [version] with the application's Flyway settings. */
    private fun withDatabaseAt(
        version: String,
        block: (JdbcTemplate, Flyway) -> Unit,
    ) {
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
            configured.target(version).load().migrate()
            block(JdbcTemplate(scratchSource), configured.target("latest").load())
        } finally {
            jdbcTemplate.execute("DROP DATABASE $name WITH (FORCE)")
        }
    }

    private fun createIndexSql(name: String) =
        "CREATE INDEX $name ON audit_event (organisation_id, branch_id, event_time DESC) " +
            "WHERE branch_id IS NOT NULL"

    private fun createSubjectUserIndexSql() =
        "CREATE INDEX $SUBJECT_USER_INDEX ON audit_event " +
            "(organisation_id, subject_user_id, event_time DESC) WHERE subject_user_id IS NOT NULL"

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

    private fun succeeded(
        jdbc: JdbcTemplate,
        version: String,
    ): Boolean =
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE version = ? AND success",
            Int::class.java,
            version,
        ) == 1

    private companion object {
        const val BRANCH_INDEX = "idx_audit_event_organisation_branch_time"
        const val SUBJECT_USER_INDEX = "idx_audit_event_organisation_subject_user_time"
        const val PLATFORM_ORGANISATION_ID = "00000000-0000-0000-0000-000000000000"
        val LOCK_WAIT_CEILING: Duration = Duration.ofSeconds(12)
        val INDEXES =
            mapOf(
                25 to BRANCH_INDEX,
                26 to "idx_audit_event_organisation_subject_time",
                27 to "idx_audit_event_organisation_outcome_time",
                28 to "idx_audit_event_organisation_severity_time",
            )
    }
}
