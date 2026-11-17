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
 * `V39`'s `user.assign_self` permission and its grants, on PostgreSQL (#342, ADR 0044).
 *
 * Flyway has finished by the time a test runs, so the migration's own run granted nothing to the
 * tenants these tests create. They seed the shape an upgraded installation holds (a seeded
 * `TENANT_ADMIN`, `IAM_ADMIN` and `BRANCH_MANAGER` that predate the code) and execute the
 * migration file itself against it, read from the classpath so a change to the file is a change
 * to the test. The file is idempotent by design, which is what makes re-running it legitimate.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SelfAssignPermissionMigrationTests(
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
    fun `the code exists once, as a high risk tenant mutation that requires only the user view`() {
        runMigration()

        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT id::text AS id, module_code, risk_level, status, kind, grant_scope,
                       system_permission
                FROM permission WHERE permission_code = 'user.assign_self'
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
        assertEquals(setOf("user.view"), requiredViews())
    }

    @Test
    fun `every tenant administrator, the local one and the platform admin hold it`() {
        val organisationId = seedOrganisation()
        val legacyAdmin = seedRole(organisationId, "TENANT_ADMIN", system = true)

        runMigration()

        assertTrue("user.assign_self" in codesOf(legacyAdmin))
        assertTrue("user.assign_self" in codesOf(LOCAL_ADMIN_ROLE))
        assertTrue("user.assign_self" in codesOf(PLATFORM_SUPER_ADMIN))
    }

    @Test
    fun `the role managers and every other role stay without it`() {
        val organisationId = seedOrganisation()
        val iamAdmin =
            seedRole(organisationId, "IAM_ADMIN", system = true, "user.assign_role", "user.view")
        val branchManager =
            seedRole(organisationId, "BRANCH_MANAGER", system = true, "user.assign_branch")
        val custom = seedRole(organisationId, "CUSTOM_ASSIGNER", system = false, "user.view")
        // A custom role that merely borrows an administrator's code, and a local-admin outside
        // FINAXIS-LOCAL, are not the seeded administrators: V39 pins both by system_role and
        // organisation.
        val lookalikeAdmin = seedRole(seedOrganisation(), "TENANT_ADMIN", system = false)
        val foreignLocalAdmin = seedRole(organisationId, "local-admin", system = true)

        runMigration()

        assertEquals(setOf("user.assign_role", "user.view"), codesOf(iamAdmin))
        assertEquals(setOf("user.assign_branch"), codesOf(branchManager))
        assertEquals(setOf("user.view"), codesOf(custom))
        assertEquals(emptySet(), codesOf(lookalikeAdmin))
        assertEquals(emptySet(), codesOf(foreignLocalAdmin))
        assertTrue("user.assign_self" !in codesOf(PLATFORM_SUPPORT))
        assertEquals(
            0L,
            count(
                """
                SELECT COUNT(*) FROM membership_permission mp
                JOIN permission p ON p.id = mp.permission_id
                WHERE p.permission_code = 'user.assign_self'
                  AND mp.organisation_id = '$organisationId'
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
            count("SELECT COUNT(*) FROM permission WHERE permission_code = 'user.assign_self'"),
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
                WHERE m.permission_code = 'user.assign_self'
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
            ) VALUES (?, ?, 'V39 Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "V39-" + id.toString().takeLast(TENANT_CODE_SUFFIX),
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
        const val MIGRATION = "db/migration/V39__user_assign_self_permission.sql"
        const val PERMISSION_ID = "40000000-0000-0000-0000-000000000069"
        const val PLATFORM_SUPER_ADMIN = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT = "50000000-0000-0000-0000-000000000002"
        const val LOCAL_ADMIN_ROLE = "77777777-7777-7777-7777-777777777777"
        const val TENANT_CODE_SUFFIX = 12
    }
}
