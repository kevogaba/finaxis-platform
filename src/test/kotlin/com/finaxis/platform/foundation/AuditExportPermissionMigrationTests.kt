package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `V34`'s `audit.export` permission and its grants, on PostgreSQL (#167, ADR 0039).
 *
 * Flyway has finished by the time a test runs, so the migration's own run granted nothing to the
 * tenants these tests create. They seed the shape an upgraded installation holds (a seeded
 * `TENANT_ADMIN` and `TENANT_AUDITOR` that predate the code) and execute the migration file itself
 * against it, read from the classpath so a change to the file is a change to the test. The file
 * is idempotent by design, which is what makes re-running it legitimate.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AuditExportPermissionMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val organisations = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededOrganisations() {
        organisations.forEach { organisationId ->
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
    fun `the code exists once, as a high risk tenant mutation that requires only the audit view`() {
        runMigration()

        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT id::text AS id, module_code, risk_level, status, kind, grant_scope,
                       system_permission
                FROM permission WHERE permission_code = 'audit.export'
                """.trimIndent(),
            )
        assertEquals(1, rows.size)
        val row = rows.single()
        assertEquals(PERMISSION_ID, row["id"])
        assertEquals("audit", row["module_code"])
        assertEquals("HIGH", row["risk_level"])
        assertEquals("ACTIVE", row["status"])
        assertEquals("MUTATION", row["kind"])
        assertEquals("TENANT", row["grant_scope"])
        assertEquals(true, row["system_permission"])
        assertEquals(setOf("audit.view"), requiredViews())
    }

    @Test
    fun `every tenant administrator, the local one and the platform admin hold it`() {
        val organisationId = seedOrganisation()
        val legacyAdmin = seedRole(organisationId, "TENANT_ADMIN", system = true)

        runMigration()

        assertTrue("audit.export" in codesOf(legacyAdmin))
        assertTrue("audit.export" in codesOf(LOCAL_ADMIN_ROLE))
        assertTrue("audit.export" in codesOf(PLATFORM_SUPER_ADMIN))
    }

    @Test
    fun `the auditor and every other role stay without it`() {
        val organisationId = seedOrganisation()
        val auditor = seedRole(organisationId, "TENANT_AUDITOR", system = true, "audit.view")
        val seededOther = seedRole(organisationId, "IAM_ADMIN", system = true)
        val custom = seedRole(organisationId, "CUSTOM_VIEWER", system = false, "audit.view")

        runMigration()

        assertEquals(setOf("audit.view"), codesOf(auditor))
        assertEquals(emptySet(), codesOf(seededOther))
        assertEquals(setOf("audit.view"), codesOf(custom))
        assertTrue("audit.export" !in codesOf(PLATFORM_SUPPORT))
        assertEquals(
            0L,
            count(
                """
                SELECT COUNT(*) FROM membership_permission mp
                JOIN permission p ON p.id = mp.permission_id
                WHERE p.permission_code = 'audit.export' AND mp.organisation_id = '$organisationId'
                """.trimIndent(),
            ),
            "no direct override is created in the seeded organisation",
        )
    }

    @Test
    fun `re-running the migration changes nothing`() {
        runMigration()
        val afterFirst = snapshot()

        runMigration()
        runMigration()

        assertEquals(afterFirst, snapshot())
    }

    private data class Snapshot(
        val permissions: Long,
        val rolePermissions: Long,
        val requirements: Long,
    )

    private fun snapshot() =
        Snapshot(
            count("SELECT COUNT(*) FROM permission WHERE permission_code = 'audit.export'"),
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
                WHERE m.permission_code = 'audit.export'
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
            ) VALUES (?, ?, 'V34 Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "V34-" + id.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        organisations += id
        return id
    }

    private fun seedRole(
        organisationId: UUID,
        roleCode: String,
        system: Boolean,
        vararg permissionCodes: String,
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
        permissionCodes.forEach { code ->
            jdbcTemplate.update(
                """
                INSERT INTO role_permission (
                    organisation_id, role_id, permission_id, granted_at, created_at, updated_at
                )
                SELECT ?, ?, id, NOW(), NOW(), NOW() FROM permission WHERE permission_code = ?
                """.trimIndent(),
                organisationId,
                roleId,
                code,
            )
        }
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
        const val MIGRATION = "db/migration/V34__audit_export_permission.sql"
        const val PERMISSION_ID = "40000000-0000-0000-0000-000000000068"
        const val PLATFORM_SUPER_ADMIN = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT = "50000000-0000-0000-0000-000000000002"
        const val LOCAL_ADMIN_ROLE = "77777777-7777-7777-7777-777777777777"
        const val TENANT_CODE_SUFFIX = 12
    }
}
