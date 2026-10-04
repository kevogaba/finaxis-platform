package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `V22`'s own schema and its re-run behaviour, proved on PostgreSQL. What the migration put in the
 * catalogue is asserted by `PermissionCatalogueMetadataTests`.
 *
 * Every test runs in a rolled-back transaction, DDL included, so nothing leaks into the shared
 * container.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class PermissionCatalogueMetadataMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val savepoint =
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_NESTED
        }

    @Test
    fun `kind and grant scope are mandatory and bounded`() {
        assertEquals(
            listOf("NO", "NO"),
            jdbcTemplate.queryForList(
                """
                SELECT is_nullable FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'permission'
                  AND column_name IN ('kind', 'grant_scope')
                ORDER BY column_name DESC
                """.trimIndent(),
                String::class.java,
            ),
        )
        rejected { insertPermission("t.bogus_kind", "WRONG", "TENANT") }
        rejected { insertPermission("t.bogus_scope", "VIEW", "WRONG") }
        rejected { insertPermission("t.no_kind", null, "TENANT") }
        rejected { insertPermission("t.no_scope", "VIEW", null) }
    }

    @Test
    fun `a requirement has a generated id and guid, links two catalogue rows and is unique`() {
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT id, guid FROM permission_view_requirement
                WHERE permission_id =
                    (SELECT id FROM permission WHERE permission_code = 'branch.suspend')
                """.trimIndent(),
            )
        assertNotNull(row["id"])
        assertNotNull(row["guid"])

        rejected("a pairing is unique") {
            requirement("branch.suspend", "branch.view")
        }
        rejected("a code cannot require itself") {
            requirement("branch.view", "branch.view")
        }
        rejected("the view must exist") {
            jdbcTemplate.update(
                """
                INSERT INTO permission_view_requirement (
                    permission_id, required_view_permission_id, created_at, updated_at
                ) SELECT id, uuidv7(), NOW(), NOW() FROM permission
                  WHERE permission_code = 'branch.suspend'
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `a second view for the same mutation is allowed`() {
        assertEquals(
            setOf("membership.view", "user.view"),
            jdbcTemplate
                .queryForList(
                    """
                    SELECT v.permission_code FROM permission_view_requirement r
                    JOIN permission m ON m.id = r.permission_id
                    JOIN permission v ON v.id = r.required_view_permission_id
                    WHERE m.permission_code = 'user.invite'
                    """.trimIndent(),
                    String::class.java,
                ).toSet(),
        )
    }

    @Test
    fun `re-running the migration changes nothing`() {
        val before = snapshot()

        runMigration()

        assertEquals(before, snapshot())
    }

    @Test
    fun `re-running the migration restores a pairing that was removed out of band`() {
        val before = jdbcTemplate.queryForObject(COUNT_REQUIREMENTS, Long::class.java)
        jdbcTemplate.update(
            """
            DELETE FROM permission_view_requirement
            WHERE permission_id = (SELECT id FROM permission WHERE permission_code = 'cob.start')
            """.trimIndent(),
        )

        runMigration()

        assertEquals(before, jdbcTemplate.queryForObject(COUNT_REQUIREMENTS, Long::class.java))
    }

    @Test
    fun `re-running the migration restores a check constraint that is missing`() {
        jdbcTemplate.execute("ALTER TABLE permission DROP CONSTRAINT chk_permission_kind")
        jdbcTemplate.execute("ALTER TABLE permission DROP CONSTRAINT chk_permission_grant_scope")

        runMigration()

        assertEquals(
            2L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM pg_constraint
                WHERE conrelid = 'permission'::regclass
                  AND conname IN ('chk_permission_kind', 'chk_permission_grant_scope')
                """.trimIndent(),
                Long::class.java,
            ),
        )
    }

    @Test
    fun `the migration refuses a permission it cannot classify and names it`() {
        jdbcTemplate.execute("ALTER TABLE permission ALTER COLUMN kind DROP NOT NULL")
        jdbcTemplate.execute("ALTER TABLE permission ALTER COLUMN grant_scope DROP NOT NULL")
        jdbcTemplate.update(
            """
            INSERT INTO permission (
                permission_code, permission_name, module_code, risk_level, status, created_at,
                updated_at
            ) VALUES ('hotfix.unclassified', 'Hotfix', 'iam', 'LOW', 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
        )

        val failure = assertFailsWith<DataAccessException> { runMigration() }

        assertTrue(
            failure.mostSpecificCause.message
                .orEmpty()
                .contains("hotfix.unclassified"),
            failure.mostSpecificCause.message,
        )
    }

    /** Runs [statement] in a savepoint, so a rejected statement does not abort the test. */
    private fun rejected(
        message: String? = null,
        statement: () -> Unit,
    ) {
        assertFailsWith<DataIntegrityViolationException>(message) {
            savepoint.executeWithoutResult { statement() }
        }
    }

    private fun snapshot(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            """
            SELECT permission_code, kind, grant_scope, row_version FROM permission
            ORDER BY permission_code
            """.trimIndent(),
        ) +
            jdbcTemplate.queryForList(
                """
                SELECT m.permission_code AS mutation, v.permission_code AS view, r.id, r.guid,
                       r.row_version
                FROM permission_view_requirement r
                JOIN permission m ON m.id = r.permission_id
                JOIN permission v ON v.id = r.required_view_permission_id
                ORDER BY m.permission_code, v.permission_code
                """.trimIndent(),
            )

    private fun insertPermission(
        code: String,
        kind: String?,
        grantScope: String?,
    ) = jdbcTemplate.update(
        """
        INSERT INTO permission (
            permission_code, permission_name, module_code, risk_level, status, kind, grant_scope,
            created_at, updated_at
        ) VALUES (?, 'Test', 'iam', 'LOW', 'ACTIVE', ?, ?, NOW(), NOW())
        """.trimIndent(),
        code,
        kind,
        grantScope,
    )

    private fun requirement(
        mutation: String,
        view: String,
    ) = jdbcTemplate.update(
        """
        INSERT INTO permission_view_requirement (
            permission_id, required_view_permission_id, created_at, updated_at
        )
        SELECT m.id, v.id, NOW(), NOW() FROM permission m, permission v
        WHERE m.permission_code = ? AND v.permission_code = ?
        """.trimIndent(),
        mutation,
        view,
    )

    private fun runMigration() {
        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )
    }

    private companion object {
        const val MIGRATION = "db/migration/V22__permission_catalogue_metadata.sql"
        const val COUNT_REQUIREMENTS = "SELECT COUNT(*) FROM permission_view_requirement"
    }
}
