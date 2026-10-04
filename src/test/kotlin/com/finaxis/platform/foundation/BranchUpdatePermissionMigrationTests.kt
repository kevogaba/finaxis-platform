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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `V19`'s `branch.update` permission and its grant-copy rule, proved on PostgreSQL.
 *
 * Flyway has finished, against a database that holds no custom role, by the time a test runs, so
 * the migration's own run copied nothing here. These tests therefore seed the shape an upgraded
 * installation holds - roles that already hold `branch.create` and have no `branch.update` - and
 * execute the migration file itself (read from the classpath, not restated, so a change to the
 * file is a change to the test) against them. The file is idempotent by design, which is what
 * makes re-running it legitimate.
 *
 * Every test works in its own organisation and removes it afterwards. The migration is not scoped:
 * it is a table-wide grant copy on the shared container, which is benign because it only ever adds
 * `branch.update` beside a `branch.create` that is already there, and the suites run serially.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BranchUpdatePermissionMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
    transactionManager: PlatformTransactionManager,
) {
    private val transactionTemplate = TransactionTemplate(transactionManager)

    private val organisations = mutableListOf<UUID>()

    @AfterEach
    fun removeSeededOrganisations() {
        organisations.forEach { organisationId ->
            jdbcTemplate.update(
                "DELETE FROM membership_permission WHERE organisation_id = ?",
                organisationId,
            )
            jdbcTemplate.update(
                "DELETE FROM user_organisation_membership WHERE organisation_id = ?",
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
        jdbcTemplate.update("DELETE FROM user_account WHERE username LIKE 'v19-%'")
    }

    @Test
    fun `the code exists exactly once, in the branch module, at a deterministic identifier`() {
        runMigration()

        val rows =
            jdbcTemplate.queryForList(
                """
                SELECT id::text AS id, module_code, risk_level, status, system_permission
                FROM permission WHERE permission_code = 'branch.update'
                """.trimIndent(),
            )

        assertEquals(1, rows.size)
        assertEquals(PERMISSION_ID, rows.single()["id"])
        assertEquals(moduleOf("branch.create"), rows.single()["module_code"])
        assertEquals("HIGH", rows.single()["risk_level"])
        assertEquals("ACTIVE", rows.single()["status"])
        assertEquals(true, rows.single()["system_permission"])
    }

    @Test
    fun `a role that held branch create before the migration holds branch update after it`() {
        val organisationId = seedOrganisation()
        val maker = seedRole(organisationId, "V19_MAKER", "branch.create", "branch.view")
        assertEquals(setOf("branch.create", "branch.view"), codesOf(maker))

        runMigration()

        assertEquals(setOf("branch.create", "branch.view", "branch.update"), codesOf(maker))
    }

    @Test
    fun `a role without branch create gets nothing`() {
        val organisationId = seedOrganisation()
        val reader = seedRole(organisationId, "V19_READER", "branch.view", "branch.approve")
        val empty = seedRole(organisationId, "V19_EMPTY")

        runMigration()

        assertEquals(setOf("branch.view", "branch.approve"), codesOf(reader))
        assertEquals(emptySet(), codesOf(empty))
    }

    @Test
    fun `platform super admin holds branch update, as it holds branch create`() {
        runMigration()

        assertEquals(
            1L,
            jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id
                WHERE rp.role_id = '$PLATFORM_SUPER_ADMIN' AND p.permission_code = 'branch.update'
                """.trimIndent(),
                Long::class.java,
            ),
        )
    }

    @Test
    fun `platform support, which holds no branch create, gets nothing`() {
        runMigration()

        assertEquals(
            setOf("audit.view", "business_date.view"),
            jdbcTemplate
                .queryForList(
                    """
                    SELECT p.permission_code FROM role_permission rp
                    JOIN permission p ON p.id = rp.permission_id
                    WHERE rp.role_id = '$PLATFORM_SUPPORT'
                    """.trimIndent(),
                    String::class.java,
                ).toSet(),
        )
    }

    @Test
    fun `a direct membership override on branch create is copied with its effect`() {
        val organisationId = seedOrganisation()
        val allowed = seedMembership(organisationId, "v19-allow", "branch.create", "ALLOW")
        val denied = seedMembership(organisationId, "v19-deny", "branch.create", "DENY")
        val unrelated = seedMembership(organisationId, "v19-other", "branch.view", "ALLOW")

        runMigration()

        assertEquals(
            mapOf("branch.create" to "ALLOW", "branch.update" to "ALLOW"),
            overrides(allowed),
        )
        assertEquals(
            mapOf("branch.create" to "DENY", "branch.update" to "DENY"),
            overrides(denied),
        )
        assertEquals(mapOf("branch.view" to "ALLOW"), overrides(unrelated))
    }

    @Test
    fun `a branch create that is not active yields a branch update that is not active`() {
        // Runtime resolution only honours ACTIVE permissions. If branch.create had been
        // DEPRECATED or DISABLED, an ACTIVE branch.update would silently re-open PATCH to every
        // role the grant copy reaches, so the new code inherits the status it mirrors.
        //
        // The flip, the delete and the migration all run in one transaction that is always rolled
        // back, so nothing is visible to another connection and nothing needs restoring: the
        // shared catalogue is never touched. JdbcTemplate joins the transaction, so the migration
        // script executes on the same connection.
        var seen: String? = null
        transactionTemplate.execute { transaction ->
            deleteBranchUpdate()
            setBranchCreateStatus("DEPRECATED")

            runMigration()

            seen = statusOf("branch.update")
            transaction.setRollbackOnly()
        }

        assertEquals("DEPRECATED", seen)
        assertEquals("ACTIVE", statusOf("branch.update"), "the rollback must leave Flyway's row")
        assertEquals("ACTIVE", statusOf("branch.create"))
    }

    @Test
    fun `re-running the migration changes nothing`() {
        val organisationId = seedOrganisation()
        val maker = seedRole(organisationId, "V19_MAKER", "branch.create")
        seedMembership(organisationId, "v19-again", "branch.create", "ALLOW")

        runMigration()
        val afterFirst = snapshot()
        runMigration()
        runMigration()

        assertEquals(afterFirst, snapshot(), "a second run must be a no-op")
        assertEquals(setOf("branch.create", "branch.update"), codesOf(maker))
        assertTrue(afterFirst.permissions == 1L, "the code must still exist exactly once")
    }

    private data class Snapshot(
        val permissions: Long,
        val rolePermissions: Long,
        val membershipPermissions: Long,
    )

    private fun snapshot() =
        Snapshot(
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM permission WHERE permission_code = 'branch.update'",
                Long::class.java,
            )!!,
            jdbcTemplate.queryForObject("SELECT COUNT(*) FROM role_permission", Long::class.java)!!,
            jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM membership_permission",
                Long::class.java,
            )!!,
        )

    private fun runMigration() {
        jdbcTemplate.execute(
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() },
        )
    }

    private fun statusOf(code: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM permission WHERE permission_code = ?",
            String::class.java,
            code,
        )

    private fun setBranchCreateStatus(status: String) {
        jdbcTemplate.update(
            "UPDATE permission SET status = ? WHERE permission_code = 'branch.create'",
            status,
        )
    }

    private fun deleteBranchUpdate() {
        val id = "(SELECT id FROM permission WHERE permission_code = 'branch.update')"
        jdbcTemplate.update("DELETE FROM role_permission WHERE permission_id = $id")
        jdbcTemplate.update("DELETE FROM membership_permission WHERE permission_id = $id")
        jdbcTemplate.update("DELETE FROM permission WHERE permission_code = 'branch.update'")
    }

    private fun moduleOf(code: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT module_code FROM permission WHERE permission_code = ?",
            String::class.java,
            code,
        )

    private fun seedOrganisation(): UUID {
        val id = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO organisation (
                id, tenant_code, display_name, country_code, base_currency_code, timezone,
                status, activated_at, created_at, updated_at
            ) VALUES (?, ?, 'V19 Test', 'KE', 'KES', 'UTC', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            id,
            "V19-" + id.toString().takeLast(TENANT_CODE_SUFFIX),
        )
        organisations += id
        return id
    }

    private fun seedRole(
        organisationId: UUID,
        roleCode: String,
        vararg permissionCodes: String,
    ): UUID {
        val roleId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO role (
                id, organisation_id, role_code, role_name, system_role, status, created_at,
                updated_at
            ) VALUES (?, ?, ?, ?, FALSE, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            roleId,
            organisationId,
            roleCode,
            roleCode,
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

    private fun seedMembership(
        organisationId: UUID,
        username: String,
        permissionCode: String,
        effect: String,
    ): UUID {
        val userId = uuidV7()
        val membershipId = uuidV7()
        jdbcTemplate.update(
            """
            INSERT INTO user_account (id, username, email, display_name, status, created_at,
                                      updated_at)
            VALUES (?, ?, ?, ?, 'ACTIVE', NOW(), NOW())
            """.trimIndent(),
            userId,
            username,
            "$username@example.test",
            username,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership (
                id, organisation_id, user_id, membership_status, membership_type, joined_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'ACTIVE', 'STAFF', NOW(), NOW(), NOW())
            """.trimIndent(),
            membershipId,
            organisationId,
            userId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO membership_permission (
                organisation_id, membership_id, permission_id, effect, granted_at, created_at,
                updated_at
            )
            SELECT ?, ?, id, ?, NOW(), NOW(), NOW() FROM permission WHERE permission_code = ?
            """.trimIndent(),
            organisationId,
            membershipId,
            effect,
            permissionCode,
        )
        return membershipId
    }

    private fun codesOf(roleId: UUID): Set<String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id WHERE rp.role_id = ?
                """.trimIndent(),
                String::class.java,
                roleId,
            ).filterNotNull()
            .toSet()

    private fun overrides(membershipId: UUID): Map<String, String> =
        jdbcTemplate
            .queryForList(
                """
                SELECT p.permission_code, mp.effect FROM membership_permission mp
                JOIN permission p ON p.id = mp.permission_id WHERE mp.membership_id = ?
                """.trimIndent(),
                membershipId,
            ).associate { (it["permission_code"] as String) to (it["effect"] as String) }

    private companion object {
        const val MIGRATION = "db/migration/V19__branch_update_permission.sql"
        const val PERMISSION_ID = "40000000-0000-0000-0000-000000000064"
        const val PLATFORM_SUPER_ADMIN = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT = "50000000-0000-0000-0000-000000000002"
        const val TENANT_CODE_SUFFIX = 12
    }
}
