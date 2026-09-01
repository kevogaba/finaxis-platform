package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationSeedDataTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `platform reference data contains the exact permission catalogue`() {
        val actual =
            jdbcTemplate
                .queryForList("SELECT permission_code FROM permission", String::class.java)
                .filterNotNull()

        // Sorting happens in Kotlin, not SQL: PostgreSQL's default collation ignores punctuation,
        // so `ORDER BY permission_code` interleaves `branch_assignment.view` with the `branch.*`
        // codes differently from Kotlin's natural ordering. The catalogue's content is what
        // matters here, not the database's collation.
        assertEquals(expectedPermissionCodes.sorted(), actual.sorted())
    }

    @Test
    fun `permission catalogue has no duplicate codes and drops the legacy logistics permission`() {
        val codes =
            jdbcTemplate
                .queryForList("SELECT permission_code FROM permission", String::class.java)
                .filterNotNull()

        assertEquals(codes.size, codes.toSet().size, "permission_code must be unique")
        assertEquals(EXPECTED_CATALOGUE_SIZE, codes.size)
        assertTrue(
            "logistics.shipment.approve" !in codes,
            "the logistics leftover must not survive the greenfield reset",
        )
    }

    @Test
    fun `platform super admin holds the entire permission catalogue`() {
        val missing =
            jdbcTemplate.queryForList(
                """
                SELECT p.permission_code
                FROM permission p
                WHERE NOT EXISTS (
                    SELECT 1 FROM role_permission rp
                    WHERE rp.permission_id = p.id
                      AND rp.role_id = '50000000-0000-0000-0000-000000000001'
                )
                ORDER BY p.permission_code
                """.trimIndent(),
                String::class.java,
            )
        assertEquals(emptyList(), missing)
    }

    @Test
    fun `platform support holds only its two operational read permissions`() {
        val actual =
            jdbcTemplate.queryForList(
                """
                SELECT p.permission_code
                FROM role_permission rp
                JOIN permission p ON p.id = rp.permission_id
                WHERE rp.role_id = '50000000-0000-0000-0000-000000000002'
                ORDER BY p.permission_code
                """.trimIndent(),
                String::class.java,
            )
        assertEquals(listOf("audit.view", "business_date.view"), actual)
    }

    @Test
    fun `bootstrap administrator satisfies every login pre-check`() {
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT o.status AS org_status,
                       u.status AS user_status,
                       m.membership_status,
                       (SELECT COUNT(*) FROM user_branch_assignment a
                         WHERE a.user_id = u.id AND a.status = 'ACTIVE') AS branch_count,
                       (SELECT COUNT(*) FROM user_role_assignment r
                         WHERE r.user_id = u.id AND r.status = 'ACTIVE') AS role_count,
                       (SELECT COUNT(*) FROM keycloak_identity_link k
                         WHERE k.user_id = u.id AND k.unlinked_at IS NULL) AS identity_count,
                       (SELECT COUNT(*) FROM role_permission rp
                         WHERE rp.role_id = '77777777-7777-7777-7777-777777777777') AS perm_count
                FROM user_account u
                JOIN user_organisation_membership m ON m.user_id = u.id
                JOIN organisation o ON o.id = m.organisation_id
                WHERE u.id = '11111111-1111-1111-1111-111111111111'
                  AND o.id = '22222222-2222-2222-2222-222222222222'
                """.trimIndent(),
            )

        assertEquals("ACTIVE", row["org_status"])
        assertEquals("ACTIVE", row["user_status"])
        assertEquals("ACTIVE", row["membership_status"])
        assertTrue((row["branch_count"] as Long) >= 1)
        assertTrue((row["role_count"] as Long) >= 1)
        assertEquals(1L, row["identity_count"])
        assertTrue((row["perm_count"] as Long) > 0)
    }

    @Test
    fun `bootstrap administrator can invite and approve tenant users`() {
        val actual =
            jdbcTemplate
                .queryForList(
                    """
                    SELECT p.permission_code
                    FROM role_permission rp
                    JOIN permission p ON p.id = rp.permission_id
                    WHERE rp.role_id = '77777777-7777-7777-7777-777777777777'
                    """.trimIndent(),
                    String::class.java,
                ).filterNotNull()

        assertEquals(expectedLocalAdminPermissionCodes.sorted(), actual.sorted())
    }

    @Test
    fun `a distinct bootstrap checker exists to approve the administrator's first invitation`() {
        val row =
            jdbcTemplate.queryForMap(
                """
                SELECT u.status AS user_status,
                       m.membership_status,
                       r.status AS role_status,
                       r.role_id,
                       (SELECT COUNT(*) FROM keycloak_identity_link k
                         WHERE k.user_id = u.id AND k.unlinked_at IS NULL) AS identity_count
                FROM user_account u
                JOIN user_organisation_membership m ON m.user_id = u.id
                JOIN user_role_assignment r ON r.user_id = u.id AND r.status = 'ACTIVE'
                  AND r.organisation_id = '$BOOTSTRAP_ORGANISATION_ID'
                WHERE u.id = '$CHECKER_USER_ID'
                  AND m.organisation_id = '$BOOTSTRAP_ORGANISATION_ID'
                """.trimIndent(),
            )

        assertEquals("ACTIVE", row["user_status"])
        assertEquals("ACTIVE", row["membership_status"])
        assertEquals("ACTIVE", row["role_status"])
        assertEquals(LOCAL_ADMIN_ROLE_ID, row["role_id"].toString())
        assertEquals(1L, row["identity_count"])
        assertNotEquals(BOOTSTRAP_ADMINISTRATOR_ID, CHECKER_USER_ID)
    }

    private companion object {
        const val BOOTSTRAP_ORGANISATION_ID = "22222222-2222-2222-2222-222222222222"
        const val BOOTSTRAP_ADMINISTRATOR_ID = "11111111-1111-1111-1111-111111111111"
        const val LOCAL_ADMIN_ROLE_ID = "77777777-7777-7777-7777-777777777777"
        const val CHECKER_USER_ID = "dddddddd-dddd-dddd-dddd-dddddddddd01"
        const val EXPECTED_CATALOGUE_SIZE = 80

        val expectedLocalAdminPermissionCodes =
            listOf(
                "accounting_report.view",
                "auth.select_branch",
                "auth.select_organisation",
                "branch.reactivate",
                "branch.view",
                "branch_assignment.view",
                "fiscal_period.open",
                "fiscal_period.view",
                "gl_account.approve",
                "gl_account.create",
                "gl_account.deactivate",
                "gl_account.submit",
                "gl_account.update",
                "gl_account.view",
                "iam.profile.read",
                "membership.reactivate",
                "membership.revoke",
                "membership.suspend",
                "membership.view",
                "permission.view",
                "posting_rule.view",
                "role.activate",
                "role.deactivate",
                "role.remove_permission",
                "role.view",
                "role_assignment.view",
                "settings.view",
                "user.approve",
                "user.assign_branch",
                "user.invite",
                "user.revoke_branch",
                "user.revoke_role",
                "user.view",
            )

        val expectedPermissionCodes =
            listOf(
                "accounting_report.export",
                "accounting_report.view",
                "audit.view",
                "auth.select_branch",
                "auth.select_organisation",
                "branch.activate",
                "branch.approve",
                "branch.close",
                "branch.create",
                "branch.reactivate",
                "branch.suspend",
                "branch.view",
                "branch_assignment.view",
                "business_date.advance",
                "business_date.reopen",
                "business_date.view",
                "cob.complete",
                "cob.start",
                "fiscal_period.close",
                "fiscal_period.open",
                "fiscal_period.reopen",
                "fiscal_period.view",
                "gl_account.approve",
                "gl_account.create",
                "gl_account.deactivate",
                "gl_account.submit",
                "gl_account.update",
                "gl_account.view",
                "iam.profile.read",
                "journal.approve",
                "journal.create_manual",
                "journal.post_prior_period",
                "journal.reverse",
                "journal.submit",
                "journal.view",
                "membership.reactivate",
                "membership.revoke",
                "membership.suspend",
                "membership.view",
                "permission.view",
                "posting_rule.approve",
                "posting_rule.create",
                "posting_rule.submit",
                "posting_rule.update",
                "posting_rule.view",
                "reconciliation.resolve",
                "reconciliation.run",
                "reconciliation.view",
                "role.activate",
                "role.assign_permission",
                "role.create",
                "role.deactivate",
                "role.remove_permission",
                "role.update",
                "role.view",
                "role_assignment.view",
                "settings.update",
                "settings.view",
                "tenant.activate",
                "tenant.approve",
                "tenant.bootstrap_retry",
                "tenant.create",
                "tenant.deprovision",
                "tenant.reactivate",
                "tenant.reject",
                "tenant.submit_for_approval",
                "tenant.suspend",
                "tenant.update_draft",
                "tenant.view",
                "tenant_setting.manage_platform",
                "user.activate",
                "user.approve",
                "user.assign_branch",
                "user.assign_role",
                "user.deactivate",
                "user.invite",
                "user.revoke_branch",
                "user.revoke_role",
                "user.suspend",
                "user.view",
            )
    }
}
