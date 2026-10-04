package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `V23`'s backfill, rule by rule, on PostgreSQL. Each test rebuilds the shape a tenant had BEFORE
 * the release (the legacy `TENANT_ADMIN` with its ten inert `tenant.*` codes and none of the
 * accounting maker and checker codes; a thin `local-admin`; an unusable `PLATFORM_SUPPORT`), runs
 * the file, and asserts the result. Everything runs in a rolled-back transaction, as the other
 * permission-migration tests do.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class SeededAdminRolesMigrationTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    private val seeder = PermissionMigrationSeeder(jdbcTemplate, "V23")

    @Test
    fun `an existing tenant administrator gains every tenant code and loses the inert ones`() {
        val organisationId = seeder.organisation()
        val admin =
            seeder.systemRole(
                organisationId,
                "TENANT_ADMIN",
                // The legacy shape: inert platform codes, a view, and the deprecated V21 leftover.
                "tenant.create",
                "tenant.approve",
                "tenant.bootstrap_retry",
                "tenant.view",
                "audit.view",
                "branch.activate",
            )

        runMigration()

        val held = codesOfRole(admin)
        // The ACTIVE tenant-scope codes, plus the DEPRECATED row V21 leaves on legacy roles.
        assertEquals(activeCodesOfScope("TENANT") + "branch.activate", held)
        assertTrue(held.containsAll(BREAK_GLASS + CHECKER_CODES), "accounting codes were missing")
        assertEquals(emptySet(), held.intersect(activeAndInactiveCodesOfScope("PLATFORM")))
    }

    @Test
    fun `the bootstrap local admin gains every active tenant code`() {
        // Rebuild the V3/V4/V5 shape: 33 codes, no audit.view.
        jdbcTemplate.update(
            """
            DELETE FROM role_permission WHERE role_id = '$LOCAL_ADMIN'
              AND permission_id NOT IN (
                  SELECT id FROM permission
                  WHERE permission_code IN ('iam.profile.read', 'auth.select_organisation')
              )
            """.trimIndent(),
        )

        runMigration()

        assertEquals(activeCodesOfScope("TENANT"), codesOfRole(UUID.fromString(LOCAL_ADMIN)))
    }

    @Test
    fun `a local admin look-alike in another tenant is not the bootstrap role`() {
        val organisationId = seeder.organisation()
        val lookAlike = seeder.systemRole(organisationId, "local-admin", "tenant.view")

        runMigration()

        assertEquals(setOf("tenant.view"), codesOfRole(lookAlike))
    }

    @Test
    fun `the platform super admin is topped up with every active code and no more`() {
        val newActive = insertPermission("mig.new_active", "VIEW", "TENANT", "ACTIVE")
        val newPlatform = insertPermission("mig.new_platform", "VIEW", "PLATFORM", "ACTIVE")
        val newDisabled = insertPermission("mig.new_disabled", "VIEW", "TENANT", "DISABLED")
        jdbcTemplate.update(
            """
            DELETE FROM role_permission WHERE role_id = '$PLATFORM_SUPER_ADMIN'
              AND permission_id IN (SELECT id FROM permission
                                    WHERE permission_code IN ('journal.view', 'tenant.approve'))
            """.trimIndent(),
        )

        runMigration()

        val held = codesOfRole(UUID.fromString(PLATFORM_SUPER_ADMIN))
        assertTrue(held.containsAll(activeCodes()), "every ACTIVE code")
        assertTrue(listOf(newActive, newPlatform).all { it in held }, "$held")
        assertTrue(newDisabled !in held, "an inactive code is not granted")
    }

    @Test
    fun `a new active tenant code, and only that, reaches the tenant administrators`() {
        val organisationId = seeder.organisation()
        val admin = seeder.systemRole(organisationId, "TENANT_ADMIN")
        val active = insertPermission("mig.tenant_active", "VIEW", "TENANT", "ACTIVE")
        val platform = insertPermission("mig.tenant_platform", "VIEW", "PLATFORM", "ACTIVE")
        val disabled = insertPermission("mig.tenant_disabled", "VIEW", "TENANT", "DISABLED")

        runMigration()

        val held = codesOfRole(admin)
        assertTrue(active in held)
        assertTrue(platform !in held, "a platform-scope code is inert in a tenant")
        assertTrue(disabled !in held, "a DISABLED code confers nothing")
        assertTrue(active in codesOfRole(UUID.fromString(LOCAL_ADMIN)))
    }

    @Test
    fun `platform support gains the platform selection, profile and reads, no accounting`() {
        jdbcTemplate.update(
            """
            DELETE FROM role_permission WHERE role_id = '$PLATFORM_SUPPORT'
              AND permission_id NOT IN (
                  SELECT id FROM permission
                  WHERE permission_code IN ('audit.view', 'business_date.view')
              )
            """.trimIndent(),
        )

        runMigration()

        assertEquals(
            setOf(
                "audit.view",
                "business_date.view",
                "auth.select_organisation",
                "iam.profile.read",
                "tenant.view",
                "branch.view",
                "user.view",
                "membership.view",
                "branch_assignment.view",
                "role.view",
                "role_assignment.view",
                "permission.view",
            ),
            codesOfRole(UUID.fromString(PLATFORM_SUPPORT)),
        )
    }

    @Test
    fun `the other seeded tenant roles gain their additions and only those`() {
        val organisationId = seeder.organisation()
        val iam = seeder.systemRole(organisationId, "IAM_ADMIN", "user.view")
        val manager = seeder.systemRole(organisationId, "BRANCH_MANAGER", "branch.create")
        val operator = seeder.systemRole(organisationId, "BRANCH_OPERATOR", "business_date.view")
        val auditor = seeder.systemRole(organisationId, "TENANT_AUDITOR", "audit.view")
        val maker = seeder.systemRole(organisationId, "ACCOUNTING_OPERATOR", "journal.view")
        val checker = seeder.systemRole(organisationId, "ACCOUNTING_APPROVER", "journal.view")

        runMigration()

        assertEquals(setOf("user.view", "branch.view"), codesOfRole(iam))
        assertEquals(
            setOf("branch.create", "user.revoke_branch", "user.view"),
            codesOfRole(manager),
        )
        assertEquals(setOf("business_date.view", "branch.view"), codesOfRole(operator))
        assertEquals(setOf("audit.view"), codesOfRole(auditor), "the auditor is unchanged")
        assertEquals(setOf("journal.view"), codesOfRole(maker))
        assertEquals(setOf("journal.view"), codesOfRole(checker))
    }

    @Test
    fun `a tenant-customised role is never touched, whatever it is called`() {
        val organisationId = seeder.organisation()
        // Created through the API a role is never a system role, so these are what a tenant makes.
        val customAdmin = seeder.role(organisationId, "TENANT_ADMIN", "tenant.view")
        val otherOrganisation = seeder.organisation()
        val customIam = seeder.role(otherOrganisation, "IAM_ADMIN", "user.view")
        val customLocal = seeder.role(otherOrganisation, "local-admin")
        val seededButUnknown = seeder.systemRole(otherOrganisation, "SOME_OTHER_SYSTEM_ROLE")
        val custom = seeder.role(otherOrganisation, "MY_ADMIN", "journal.view", "tenant.create")

        runMigration()

        assertEquals(setOf("tenant.view"), codesOfRole(customAdmin))
        assertEquals(setOf("user.view"), codesOfRole(customIam))
        assertEquals(emptySet(), codesOfRole(customLocal))
        assertEquals(emptySet(), codesOfRole(seededButUnknown))
        assertEquals(setOf("journal.view", "tenant.create"), codesOfRole(custom))
    }

    @Test
    fun `a member's direct deny override survives the widening`() {
        val organisationId = seeder.organisation()
        val admin = seeder.systemRole(organisationId, "TENANT_ADMIN")
        val member = seeder.member(organisationId, "denied")
        seeder.assign(organisationId, member, admin)
        seeder.override(organisationId, member.membershipId, "journal.post_prior_period", "DENY")

        runMigration()

        assertEquals(
            listOf("DENY"),
            jdbcTemplate.queryForList(
                "SELECT effect FROM membership_permission WHERE membership_id = ?",
                String::class.java,
                member.membershipId,
            ),
        )
    }

    @Test
    fun `re-running the migration writes nothing`() {
        val organisationId = seeder.organisation()
        seeder.systemRole(organisationId, "TENANT_ADMIN", "tenant.create", "tenant.view")
        seeder.systemRole(organisationId, "BRANCH_MANAGER", "branch.create")
        runMigration()
        val afterFirst = snapshot()

        runMigration()

        assertEquals(afterFirst, snapshot())
    }

    @Test
    fun `the migrated administrators already satisfy every post-condition`() {
        // The Flyway run left the bootstrap tenant complete, so a re-run is a no-op there too.
        val before = codesOfRole(UUID.fromString(LOCAL_ADMIN))

        runMigration()

        assertEquals(before, codesOfRole(UUID.fromString(LOCAL_ADMIN)))
        assertEquals(activeCodesOfScope("TENANT"), before)
    }

    @Test
    fun `each post-condition fires on a deliberately broken shape`() {
        val broken =
            listOf(
                "DELETE FROM role_permission WHERE role_id = '$LOCAL_ADMIN' AND permission_id = " +
                    "(SELECT id FROM permission WHERE permission_code = 'audit.view');" to
                    "does not hold every ACTIVE tenant-scope permission",
                "INSERT INTO role_permission (organisation_id, role_id, permission_id, " +
                    "granted_at, created_at, updated_at) SELECT '$LOCAL_ORGANISATION', " +
                    "'$LOCAL_ADMIN', id, NOW(), NOW(), NOW() FROM permission " +
                    "WHERE permission_code = 'tenant.create';" to
                    "still holds an inert platform-only permission",
                "DELETE FROM role_permission WHERE role_id = '$PLATFORM_SUPER_ADMIN' AND " +
                    "permission_id = (SELECT id FROM permission " +
                    "WHERE permission_code = 'journal.view');" to
                    "PLATFORM_SUPER_ADMIN does not hold every ACTIVE permission",
            )

        broken.forEach { (doctoring, expected) ->
            jdbcTemplate.execute("SAVEPOINT broken")
            val failure = assertFailsWith<DataAccessException>(expected) { runMigration(doctoring) }
            jdbcTemplate.execute("ROLLBACK TO SAVEPOINT broken")

            assertTrue(expected in failure.message.orEmpty(), "got: ${failure.message}")
        }
    }

    private fun snapshot(): List<Map<String, Any?>> =
        jdbcTemplate.queryForList(
            "SELECT id, role_id, permission_id, row_version, updated_at FROM role_permission " +
                "ORDER BY id",
        )

    private fun insertPermission(
        code: String,
        kind: String,
        grantScope: String,
        status: String,
    ): String {
        jdbcTemplate.update(
            """
            INSERT INTO permission (
                permission_code, permission_name, module_code, risk_level, status, kind,
                grant_scope, created_at, updated_at
            ) VALUES (?, 'Migration test', 'iam', 'LOW', ?, ?, ?, NOW(), NOW())
            """.trimIndent(),
            code,
            status,
            kind,
            grantScope,
        )
        return code
    }

    private fun activeCodes(): Set<String> = codes("status = 'ACTIVE'")

    private fun activeCodesOfScope(scope: String): Set<String> =
        codes("status = 'ACTIVE' AND grant_scope = '$scope'")

    private fun activeAndInactiveCodesOfScope(scope: String): Set<String> =
        codes("grant_scope = '$scope'")

    private fun codes(where: String): Set<String> =
        jdbcTemplate
            .queryForList("SELECT permission_code FROM permission WHERE $where", String::class.java)
            .filterNotNull()
            .toSet()

    private fun codesOfRole(roleId: UUID): Set<String> =
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

    /** Runs the migration, with [doctoring] SQL spliced in before its post-conditions if given. */
    private fun runMigration(doctoring: String? = null) {
        val text =
            ClassPathResource(MIGRATION).inputStream.use { it.readAllBytes().decodeToString() }
        val sql =
            if (doctoring == null) {
                text
            } else {
                assertTrue(POST_CONDITIONS_MARKER in text, "the doctoring marker moved")
                text.replace(POST_CONDITIONS_MARKER, "$doctoring\n$POST_CONDITIONS_MARKER")
            }
        jdbcTemplate.execute(sql)
    }

    private companion object {
        const val MIGRATION =
            "db/migration/V23__seeded_admin_roles_hold_every_permission_of_their_scope.sql"
        const val POST_CONDITIONS_MARKER = "-- Post-conditions: what this file is responsible for."
        const val LOCAL_ORGANISATION = "22222222-2222-2222-2222-222222222222"
        const val LOCAL_ADMIN = "77777777-7777-7777-7777-777777777777"
        const val PLATFORM_SUPER_ADMIN = "50000000-0000-0000-0000-000000000001"
        const val PLATFORM_SUPPORT = "50000000-0000-0000-0000-000000000002"
        val BREAK_GLASS = setOf("fiscal_period.reopen", "journal.post_prior_period")
        val CHECKER_CODES =
            setOf("journal.create_manual", "journal.submit", "journal.approve", "journal.reverse")
    }
}
