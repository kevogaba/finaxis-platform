package com.finaxis.platform.foundation

import com.finaxis.platform.PostgresTestConfiguration
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.TestConstructor
import java.util.UUID
import kotlin.test.assertFailsWith

/**
 * Database-level regression tests for foundation schema constraints that application-service
 * pre-checks must not be the only line of defence for.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FoundationSchemaConstraintTests(
    private val jdbcTemplate: JdbcTemplate,
) {
    @Test
    fun `unique tenant code is rejected`() {
        assertFailsWith<DataIntegrityViolationException> {
            insertOrganisation("FINAXIS-LOCAL")
        }
    }

    @Test
    fun `duplicate branch code within a tenant is rejected`() {
        assertFailsWith<DataIntegrityViolationException> {
            insertBranch(BOOTSTRAP_ORGANISATION_ID, "HQ")
        }
    }

    @Test
    fun `duplicate branch code across tenants is allowed`() {
        val organisationId = insertOrganisation("B1-BRANCH-CODE-OTHER")

        insertBranch(organisationId, "HQ")
    }

    @Test
    fun `duplicate membership for the same user and tenant is rejected`() {
        assertFailsWith<DataIntegrityViolationException> {
            insertMembership(BOOTSTRAP_ORGANISATION_ID, BOOTSTRAP_USER_ID)
        }
    }

    @Test
    fun `branch assignment referencing another tenants branch is rejected`() {
        val otherOrganisationId = insertOrganisation("B1-CROSS-TENANT-BRANCH")
        val otherBranchId = insertBranch(otherOrganisationId, "OTHER-HQ")

        assertFailsWith<DataIntegrityViolationException> {
            insertBranchAssignment(
                organisationId = BOOTSTRAP_ORGANISATION_ID,
                userId = BOOTSTRAP_USER_ID,
                branchId = otherBranchId,
            )
        }
    }

    @Test
    fun `second active role assignment for the same user role and scope is rejected`() {
        assertFailsWith<DataIntegrityViolationException> {
            insertTenantRoleAssignment(status = "ACTIVE")
        }
    }

    @Test
    fun `inactive duplicate role assignment is allowed`() {
        insertTenantRoleAssignment(status = "REVOKED")
    }

    @Test
    fun `client supplied duplicate guid is rejected`() {
        val duplicateGuid = UUID.fromString("12121212-1212-1212-1212-121212121212")
        insertOrganisation("B1-GUID-ONE", duplicateGuid)

        assertFailsWith<DataIntegrityViolationException> {
            insertOrganisation("B1-GUID-TWO", duplicateGuid)
        }
    }

    private fun insertOrganisation(
        tenantCode: String,
        guid: UUID? = null,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO organisation (
                ${guidColumn(guid)}tenant_code, display_name, country_code, base_currency_code,
                timezone, status, created_at, updated_at
            ) VALUES (
                ${guidPlaceholder(guid)}?, ?, 'KE', 'KES', 'Africa/Nairobi', 'ACTIVE', NOW(), NOW()
            )
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            *organisationArguments(tenantCode, guid),
        ) ?: error("organisation insert returned no id")

    private fun insertBranch(
        organisationId: UUID,
        branchCode: String,
    ): UUID =
        jdbcTemplate.queryForObject(
            """
            INSERT INTO branch (
                organisation_id, branch_code, branch_name, branch_type, status, timezone,
                opened_on, created_at, updated_at
            ) VALUES (?, ?, ?, 'OPERATIONS', 'ACTIVE', 'Africa/Nairobi', CURRENT_DATE, NOW(), NOW())
            RETURNING id
            """.trimIndent(),
            UUID::class.java,
            organisationId,
            branchCode,
            "$branchCode Branch",
        ) ?: error("branch insert returned no id")

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_organisation_membership (
                organisation_id, user_id, membership_status, membership_type, joined_at,
                created_at, updated_at
            ) VALUES (?, ?, 'ACTIVE', 'ADMIN', NOW(), NOW(), NOW())
            """.trimIndent(),
            organisationId,
            userId,
        )
    }

    private fun insertBranchAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO user_branch_assignment (
                organisation_id, user_id, branch_id, assignment_type, status, assigned_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'VIEW', 'ACTIVE', NOW(), NOW(), NOW())
            """.trimIndent(),
            organisationId,
            userId,
            branchId,
        )
    }

    private fun insertTenantRoleAssignment(status: String) {
        jdbcTemplate.update(
            """
            INSERT INTO user_role_assignment (
                organisation_id, user_id, role_id, scope_type, status, assigned_at,
                created_at, updated_at
            ) VALUES (?, ?, ?, 'TENANT', ?, NOW(), NOW(), NOW())
            """.trimIndent(),
            BOOTSTRAP_ORGANISATION_ID,
            BOOTSTRAP_USER_ID,
            BOOTSTRAP_ROLE_ID,
            status,
        )
    }

    private fun guidColumn(guid: UUID?): String = if (guid == null) "" else "guid, "

    private fun guidPlaceholder(guid: UUID?): String = if (guid == null) "" else "?, "

    private fun organisationArguments(
        tenantCode: String,
        guid: UUID?,
    ): Array<Any> =
        if (guid == null) {
            arrayOf(tenantCode, "$tenantCode Organisation")
        } else {
            arrayOf(guid, tenantCode, "$tenantCode Organisation")
        }

    private companion object {
        val BOOTSTRAP_USER_ID: UUID =
            UUID.fromString("11111111-1111-1111-1111-111111111111")
        val BOOTSTRAP_ORGANISATION_ID: UUID =
            UUID.fromString("22222222-2222-2222-2222-222222222222")
        val BOOTSTRAP_ROLE_ID: UUID =
            UUID.fromString("77777777-7777-7777-7777-777777777777")
    }
}
