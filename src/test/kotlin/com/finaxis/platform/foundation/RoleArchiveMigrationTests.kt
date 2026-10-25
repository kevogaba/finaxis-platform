package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * `V33`'s `role.archive` permission, its grants and the `role_transition_log` table, on PostgreSQL.
 *
 * Flyway has finished by the time a test runs, so the migration's own run granted nothing to the
 * tenants these tests create. They seed the shape an upgraded installation holds (a seeded
 * `TENANT_ADMIN` that predates the code) and execute the migration file itself against it, read
 * from the classpath so a change to the file is a change to the test. The file is idempotent by
 * design, which is what makes re-running it legitimate.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class RoleArchiveMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val organisations = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededOrganisations() {
        organisations.forEach { organisationId ->
            jdbcTemplate.update(
                "DELETE FROM role_transition_log WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update(
                "DELETE FROM role_permission WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update("DELETE FROM role WHERE organisation_id = ?", organisationId)
            jdbcTemplate.update("DELETE FROM organisation WHERE id = ?", organisationId)
        }
        organisations.clear()
    }

    @Test
    fun `the code exists once, as a tenant mutation that requires only the role view`() {
        runMigration()

        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT id::text AS id, module_code, risk_level, status, kind, grant_scope,
                       system_permission
                FROM permission WHERE permission_code = 'role.archive'
                """.trimIndent(),
            )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(PERMISSION_ID, row["id"])
        assertEquals("iam", row["module_code"])
        assertEquals("HIGH", row["risk_level"])
        assertEquals("ACTIVE", row["status"])
        assertEquals("MUTATION", row["kind"])
        assertEquals("TENANT", row["grant_scope"])
        assertEquals(true, row["system_permission"])
        assertEquals(setOf("role.view"), requiredViews())
    }

    @Test
    fun `every tenant administrator, the local one and the platform admin hold it`() {
        val organisationId = seedOrganisation()
        val legacyAdmin = seedRole(organisationId, "TENANT_ADMIN", system = true)

        runMigration()

        assertTrue("role.archive" in codesOf(legacyAdmin))
        assertTrue("role.archive" in codesOf(LOCAL_ADMIN_ROLE))
        assertTrue("role.archive" in codesOf(PLATFORM_SUPER_ADMIN))
    }

    @Test
    fun `no other role is granted it`() {
        val organisationId = seedOrganisation()
        val seededOther = seedRole(organisationId, "IAM_ADMIN", system = true)
        val custom = seedRole(organisationId, "CUSTOM_MAKER", system = false)

        runMigration()

        assertEquals(emptySet(), codesOf(seededOther))
        assertEquals(emptySet(), codesOf(custom))
        assertTrue("role.archive" !in codesOf(PLATFORM_SUPPORT))
    }

    @Test
    fun `re-running the migration changes nothing`() {
        runMigration()
        val afterFirst = snapshot()

        runMigration()
        runMigration()

        assertEquals(afterFirst, snapshot())
    }

    @Test
    fun `the transition log row must name a role of its own organisation`() {
        val organisationId = seedOrganisation()
        val roleId = seedRole(organisationId, "LOGGED", system = false)
        val otherOrganisation = seedOrganisation()

        insertLog(organisationId, roleId)

        assertFailsWith<DataIntegrityViolationException> { insertLog(otherOrganisation, roleId) }
        assertFailsWith<DataIntegrityViolationException> { insertLog(organisationId, uuidV7()) }
        val row =
            jdbcTemplate.queryForMap(
                "SELECT id, guid FROM role_transition_log WHERE entity_id = ?",
                roleId,
            )
        assertNotNull(row["id"], "id defaults to uuidv7()")
        assertNotNull(row["guid"], "guid defaults to uuidv7()")
    }

    private fun insertLog(
        organisationId: UUID,
        entityId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO role_transition_log (
                organisation_id, entity_id, transition_name, status_from, status_to, created_at,
                updated_at
            ) VALUES (?, ?, 'ARCHIVE', 'ACTIVE', 'ARCHIVED', NOW(), NOW())
            """.trimIndent(),
            organisationId,
            entityId,
        )
    }

    private data class Snapshot(
        val permissions: Long,
        val rolePermissions: Long,
        val requirements: Long,
    )

    private fun snapshot() =
        Snapshot(
            count("SELECT COUNT(*) FROM permission WHERE permission_code = 'role.archive'"),
            count("SELECT COUNT(*) FROM role_permission"),
            count("SELECT COUNT(*) FROM permission_view_requirement"),
        )

    private fun count(sql: String): Long = jdbcTemplate.queryForObject(sql, Long::class.java)!!

    private fun runMigration() {
        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )
    }

    private fun requiredViews(): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT v.permission_code FROM permission_view_requirement r
                JOIN permission m ON m.id = r.permission_id
                JOIN permission v ON v.id = r.required_view_permission_id
                WHERE m.permission_code = 'role.archive'
                """.trimIndent(),
                String::class.java,
            ).filterNotNull()
            .toSet()

    private fun seedOrganisation(): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'V33 Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "V33-" + id.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        organisations += id
        return id
    }

    private fun seedRole(
        organisationId: UUID,
        roleCode: String,
        system: Boolean,
    ): UUID {
        val roleId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO role (
                id, organisation_id, role_code, role_name, system_role, status, created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            roleId,
            organisationId,
            roleCode,
            roleCode,
            system,
        )
        return roleId
    }

    private fun codesOf(roleId: Any): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id WHERE rp.role_id = ?::uuid
                """.trimIndent(),
                String::class.java,
                roleId.toString(),
            ).filterNotNull()
            .toSet()

    private companion object {
        const val MIGRATION = "db/migration/V33__role_archive.sql"
        const val PERMISSION_ID = "40000000-0000-0000-0000-000000000067"
        const val PLATFORM_SUPER_ADMIN = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT = "50000000-0000-0000-0000-000000000002"
        const val LOCAL_ADMIN_ROLE = "77777777-7777-7777-7777-777777777777"
        const val TENANT_CODE_SUFFIX = 12
    }
}
