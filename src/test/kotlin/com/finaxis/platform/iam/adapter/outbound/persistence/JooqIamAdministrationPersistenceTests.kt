package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.iam.application.port.outbound.IamAdministrationPersistence
import com.finaxis.platform.iam.application.role.RoleScopeType
import com.finaxis.platform.iam.domain.OrganisationStatus
import com.finaxis.platform.iam.domain.RoleStatus
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_BRANCH_ASSIGNMENT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies role-administration jOOQ operations against the Flyway-managed PostgreSQL schema. */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqIamAdministrationPersistenceTests(
    private val dsl: DSLContext,
    private val persistence: IamAdministrationPersistence,
) {
    /** Creates a tenant role with the expected active state and organisation boundary. */
    @Test
    fun `creates and reads an organisation scoped tenant role`() {
        val organisationId = insertOrganisation()
        val actorId = uuidV7()

        val roleId =
            persistence.createRole(
                organisationId,
                "OPS",
                "Operations",
                "Handles ops",
                actorId,
            )

        val role = requireNotNull(persistence.findRole(organisationId, roleId))
        assertEquals("OPS", role.roleCode)
        assertFalse(role.systemRole)
        assertEquals(RoleStatus.ACTIVE, role.status)
        assertNull(persistence.findRole(uuidV7(), roleId))
    }

    /** Resolves the lifecycle status for a seeded organisation. */
    @Test
    fun `returns organisation status`() {
        val organisationId = insertOrganisation()

        assertEquals(OrganisationStatus.ACTIVE, persistence.organisationStatus(organisationId))
    }

    /** Uses the unique role-permission mapping for idempotent grants and scoped removal. */
    @Test
    fun `grants and removes a role permission idempotently`() {
        val organisationId = insertOrganisation()
        val roleId =
            persistence.createRole(
                organisationId,
                "OPS",
                "Operations",
                null,
                uuidV7(),
            )
        val permissionId = insertPermission("test.permission.${uuidV7()}")

        assertTrue(
            persistence.grantPermission(organisationId, roleId, permissionId, uuidV7()),
        )
        assertFalse(
            persistence.grantPermission(organisationId, roleId, permissionId, uuidV7()),
        )
        assertEquals(
            1,
            dsl.fetchCount(
                ROLE_PERMISSION,
                ROLE_PERMISSION.ORGANISATION_ID
                    .eq(organisationId)
                    .and(ROLE_PERMISSION.ROLE_ID.eq(roleId))
                    .and(ROLE_PERMISSION.PERMISSION_ID.eq(permissionId)),
            ),
        )
        assertTrue(
            persistence.removePermission(organisationId, roleId, permissionId, uuidV7()),
        )
        assertFalse(
            persistence.removePermission(organisationId, roleId, permissionId, uuidV7()),
        )
    }

    /** Creates idempotent tenant and branch role assignments under the same organisation. */
    @Test
    fun `assigns tenant and branch scoped roles idempotently`() {
        val fixture = createRoleAssignmentFixture()
        val repeatedTenantAssignment = assignTenantRole(fixture)
        val branchAssignment = assignBranchRole(fixture)

        assertEquals(fixture.tenantAssignmentId, repeatedTenantAssignment)
        assertNotNull(
            persistence.activeRoleAssignment(
                fixture.organisationId,
                fixture.userId,
                fixture.branchRoleId,
                RoleScopeType.BRANCH,
                fixture.branchId,
            ),
        )
        assertEquals(2, activeRoleAssignmentCount(fixture.organisationId, fixture.userId))
        assertTrue(
            persistence.revokeRole(
                fixture.organisationId,
                fixture.userId,
                fixture.branchRoleId,
                RoleScopeType.BRANCH,
                fixture.branchId,
                fixture.userId,
            ),
        )
        assertFalse(
            persistence.revokeRole(
                fixture.organisationId,
                fixture.userId,
                fixture.branchRoleId,
                RoleScopeType.BRANCH,
                fixture.branchId,
                fixture.userId,
            ),
        )
        assertEquals("ACTIVE", assignmentStatus(fixture.tenantAssignmentId))
        assertEquals("REVOKED", assignmentStatus(branchAssignment))
    }

    /** Prevents a different organisation from reading or mutating the role. */
    @Test
    fun `enforces organisation boundaries for role reads and mutations`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val roleId =
            persistence.createRole(
                organisationId,
                "OPS",
                "Operations",
                null,
                uuidV7(),
            )
        val snapshot = requireNotNull(persistence.findRole(organisationId, roleId))

        assertNull(persistence.findRole(otherOrganisationId, roleId))
        assertThrows<OptimisticLockingFailureException> {
            persistence.setRoleStatus(
                otherOrganisationId,
                roleId,
                RoleStatus.DISABLED,
                snapshot.rowVersion,
                uuidV7(),
            )
        }
        assertEquals(RoleStatus.ACTIVE, persistence.findRole(organisationId, roleId)?.status)
    }

    /** Resolves affected membership identifiers from active assignments in the organisation. */
    @Test
    fun `returns membership ids with active role assignments`() {
        val organisationId = insertOrganisation()
        val otherOrganisationId = insertOrganisation()
        val userId = insertUser()
        val otherUserId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        insertMembership(otherOrganisationId, otherUserId)
        val roleId = persistence.createRole(organisationId, "OPS", "Operations", null, userId)
        val otherRoleId =
            persistence.createRole(
                otherOrganisationId,
                "OPS",
                "Operations",
                null,
                otherUserId,
            )

        persistence.assignRole(organisationId, userId, roleId, RoleScopeType.TENANT, null, userId)
        persistence.assignRole(
            otherOrganisationId,
            otherUserId,
            otherRoleId,
            RoleScopeType.TENANT,
            null,
            otherUserId,
        )

        assertEquals(
            listOf(membershipId),
            persistence.membershipIdsWithRole(organisationId, roleId),
        )
    }

    private fun activeRoleAssignmentCount(
        organisationId: UUID,
        userId: UUID,
    ): Int =
        dsl.fetchCount(
            USER_ROLE_ASSIGNMENT,
            USER_ROLE_ASSIGNMENT.ORGANISATION_ID
                .eq(organisationId)
                .and(USER_ROLE_ASSIGNMENT.USER_ID.eq(userId))
                .and(USER_ROLE_ASSIGNMENT.STATUS.eq("ACTIVE")),
        )

    private fun createRoleAssignmentFixture(): RoleAssignmentFixture {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        insertMembership(organisationId, userId)
        val branchId = insertBranch(organisationId)
        insertBranchAssignment(organisationId, userId, branchId)
        val tenantRoleId = persistence.createRole(organisationId, "OPS", "Operations", null, userId)
        val branchRoleId =
            persistence.createRole(organisationId, "APPROVER", "Approver", null, userId)
        val tenantAssignmentId =
            persistence.assignRole(
                organisationId,
                userId,
                tenantRoleId,
                RoleScopeType.TENANT,
                null,
                userId,
            )
        return RoleAssignmentFixture(
            organisationId,
            userId,
            branchId,
            tenantRoleId,
            branchRoleId,
            tenantAssignmentId,
        )
    }

    private fun assignTenantRole(fixture: RoleAssignmentFixture): UUID =
        persistence.assignRole(
            fixture.organisationId,
            fixture.userId,
            fixture.tenantRoleId,
            RoleScopeType.TENANT,
            null,
            fixture.userId,
        )

    private fun assignBranchRole(fixture: RoleAssignmentFixture): UUID =
        persistence.assignRole(
            fixture.organisationId,
            fixture.userId,
            fixture.branchRoleId,
            RoleScopeType.BRANCH,
            fixture.branchId,
            fixture.userId,
        )

    private fun assignmentStatus(assignmentId: UUID): String? =
        dsl
            .select(USER_ROLE_ASSIGNMENT.STATUS)
            .from(USER_ROLE_ASSIGNMENT)
            .where(USER_ROLE_ASSIGNMENT.ID.eq(assignmentId))
            .fetchOne(USER_ROLE_ASSIGNMENT.STATUS)

    private fun insertOrganisation(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, OrganisationLifecycleState.ACTIVE.name)
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.ID, id)
            .set(USER_ACCOUNT.USERNAME, "user-$id")
            .set(USER_ACCOUNT.EMAIL, "user-$id@example.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Test User")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembership(
        organisationId: UUID,
        userId: UUID,
    ): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ORGANISATION_MEMBERSHIP)
            .set(USER_ORGANISATION_MEMBERSHIP.ID, id)
            .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
            .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
            .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
            .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
            .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranch(organisationId: UUID): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ID, id)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, "branch-$id")
            .set(BRANCH.BRANCH_NAME, "Test Branch")
            .set(BRANCH.BRANCH_TYPE, "MAIN")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now)
            .set(BRANCH.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertBranchAssignment(
        organisationId: UUID,
        userId: UUID,
        branchId: UUID,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_BRANCH_ASSIGNMENT)
            .set(USER_BRANCH_ASSIGNMENT.ID, uuidV7())
            .set(USER_BRANCH_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_BRANCH_ASSIGNMENT.USER_ID, userId)
            .set(USER_BRANCH_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNMENT_TYPE, "VIEW")
            .set(USER_BRANCH_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_BRANCH_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.CREATED_AT, now)
            .set(USER_BRANCH_ASSIGNMENT.UPDATED_AT, now)
            .execute()
    }

    private fun insertPermission(permissionCode: String): UUID {
        val id = uuidV7()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(PERMISSION)
            .set(PERMISSION.ID, id)
            .set(PERMISSION.PERMISSION_CODE, permissionCode)
            .set(PERMISSION.PERMISSION_NAME, permissionCode)
            .set(PERMISSION.MODULE_CODE, "tenant")
            .set(PERMISSION.RISK_LEVEL, "CRITICAL")
            .set(PERMISSION.SYSTEM_PERMISSION, true)
            .set(PERMISSION.STATUS, "ACTIVE")
            .set(PERMISSION.CREATED_AT, now)
            .set(PERMISSION.UPDATED_AT, now)
            .execute()
        return id
    }

    private data class RoleAssignmentFixture(
        val organisationId: UUID,
        val userId: UUID,
        val branchId: UUID,
        val tenantRoleId: UUID,
        val branchRoleId: UUID,
        val tenantAssignmentId: UUID,
    )
}
