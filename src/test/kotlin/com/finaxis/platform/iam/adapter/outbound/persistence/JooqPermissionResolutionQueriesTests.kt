package com.finaxis.platform.iam.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.iam.domain.PermissionEffect
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.ROLE
import com.finaxis.platform.jooq.tables.references.ROLE_PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.jooq.tables.references.USER_ROLE_ASSIGNMENT
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.annotation.Transactional
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals

@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Transactional
class JooqPermissionResolutionQueriesTests(
    private val dsl: DSLContext,
    private val queries: JooqPermissionResolutionQueries,
) {
    @Test
    fun `rolePermissionCodes excludes a branch-scoped grant when that branch is not selected`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val branchId = insertBranch(organisationId)
        val otherBranchId = insertBranch(organisationId)
        val permissionId = insertPermission("branch.report.view")
        val roleId = insertRole(organisationId)
        insertRolePermission(organisationId, roleId, permissionId)
        insertUserRoleAssignment(organisationId, userId, roleId, branchId)

        assertEquals(
            setOf("branch.report.view"),
            queries.rolePermissionCodes(membershipId, branchId),
        )
        assertEquals(emptySet(), queries.rolePermissionCodes(membershipId, otherBranchId))
        assertEquals(emptySet(), queries.rolePermissionCodes(membershipId, null))
    }

    @Test
    fun `rolePermissionCodes always includes tenant-scoped grants regardless of selected branch`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val branchId = insertBranch(organisationId)
        val permissionId = insertPermission("tenant.report.view")
        val roleId = insertRole(organisationId)
        insertRolePermission(organisationId, roleId, permissionId)
        insertUserRoleAssignment(organisationId, userId, roleId, branchId = null)

        assertEquals(
            setOf("tenant.report.view"),
            queries.rolePermissionCodes(membershipId, branchId),
        )
        assertEquals(
            setOf("tenant.report.view"),
            queries.rolePermissionCodes(membershipId, null),
        )
    }

    @Test
    fun `directPermissionEffects returns allow and deny overrides for the membership`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val allowedPermissionId = insertPermission("reports.export")
        val deniedPermissionId = insertPermission("reports.delete")
        insertMembershipPermission(
            organisationId,
            membershipId,
            allowedPermissionId,
            PermissionEffect.ALLOW,
        )
        insertMembershipPermission(
            organisationId,
            membershipId,
            deniedPermissionId,
            PermissionEffect.DENY,
        )

        val effects = queries.directPermissionEffects(membershipId).associateBy { it.code }

        assertEquals(PermissionEffect.ALLOW, effects.getValue("reports.export").effect)
        assertEquals(PermissionEffect.DENY, effects.getValue("reports.delete").effect)
    }

    @Test
    fun `directPermissionEffects is scoped to the requested membership only`() {
        val organisationId = insertOrganisation()
        val userId = insertUser()
        val membershipId = insertMembership(organisationId, userId)
        val otherUserId = insertUser()
        val otherMembershipId = insertMembership(organisationId, otherUserId)
        val permissionId = insertPermission("reports.export")
        insertMembershipPermission(
            organisationId,
            otherMembershipId,
            permissionId,
            PermissionEffect.ALLOW,
        )

        val effects = queries.directPermissionEffects(membershipId)

        assertEquals(emptyList(), effects)
    }

    private fun insertOrganisation(): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.ID, id)
            .set(ORGANISATION.TENANT_CODE, "tenant-$id")
            .set(ORGANISATION.DISPLAY_NAME, "Test Organisation")
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now)
            .set(ORGANISATION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertUser(): UUID {
        val id = UUID.randomUUID()
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
        val id = UUID.randomUUID()
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
        val id = UUID.randomUUID()
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

    private fun insertRole(organisationId: UUID): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE)
            .set(ROLE.ID, id)
            .set(ROLE.ORGANISATION_ID, organisationId)
            .set(ROLE.ROLE_CODE, "role-$id")
            .set(ROLE.ROLE_NAME, "Test Role")
            .set(ROLE.STATUS, "ACTIVE")
            .set(ROLE.CREATED_AT, now)
            .set(ROLE.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertRolePermission(
        organisationId: UUID,
        roleId: UUID,
        permissionId: UUID,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(ROLE_PERMISSION)
            .set(ROLE_PERMISSION.ID, UUID.randomUUID())
            .set(ROLE_PERMISSION.ORGANISATION_ID, organisationId)
            .set(ROLE_PERMISSION.ROLE_ID, roleId)
            .set(ROLE_PERMISSION.PERMISSION_ID, permissionId)
            .set(ROLE_PERMISSION.GRANTED_AT, now)
            .set(ROLE_PERMISSION.CREATED_AT, now)
            .set(ROLE_PERMISSION.UPDATED_AT, now)
            .execute()
    }

    private fun insertUserRoleAssignment(
        organisationId: UUID,
        userId: UUID,
        roleId: UUID,
        branchId: UUID?,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(USER_ROLE_ASSIGNMENT)
            .set(USER_ROLE_ASSIGNMENT.ID, UUID.randomUUID())
            .set(USER_ROLE_ASSIGNMENT.ORGANISATION_ID, organisationId)
            .set(USER_ROLE_ASSIGNMENT.USER_ID, userId)
            .set(USER_ROLE_ASSIGNMENT.ROLE_ID, roleId)
            .set(USER_ROLE_ASSIGNMENT.BRANCH_ID, branchId)
            .set(USER_ROLE_ASSIGNMENT.SCOPE_TYPE, if (branchId != null) "BRANCH" else "TENANT")
            .set(USER_ROLE_ASSIGNMENT.STATUS, "ACTIVE")
            .set(USER_ROLE_ASSIGNMENT.ASSIGNED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.CREATED_AT, now)
            .set(USER_ROLE_ASSIGNMENT.UPDATED_AT, now)
            .execute()
    }

    private fun insertPermission(code: String): UUID {
        val id = UUID.randomUUID()
        val now = OffsetDateTime.now()
        dsl
            .insertInto(PERMISSION)
            .set(PERMISSION.ID, id)
            .set(PERMISSION.PERMISSION_CODE, code)
            .set(PERMISSION.PERMISSION_NAME, code)
            .set(PERMISSION.MODULE_CODE, "TEST")
            .set(PERMISSION.RISK_LEVEL, "LOW")
            .set(PERMISSION.STATUS, "ACTIVE")
            .set(PERMISSION.CREATED_AT, now)
            .set(PERMISSION.UPDATED_AT, now)
            .execute()
        return id
    }

    private fun insertMembershipPermission(
        organisationId: UUID,
        membershipId: UUID,
        permissionId: UUID,
        effect: PermissionEffect,
    ) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.ID, UUID.randomUUID())
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
            .set(MEMBERSHIP_PERMISSION.EFFECT, effect.name)
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .execute()
    }
}
