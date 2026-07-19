package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertFailsWith

class IamQueryServiceTests {
    private val queries = FakeIamAdministrationQueries()
    private val permissionGuard = FakePermissionGuard()
    private val service = IamQueryService(queries, queries, queries, queries, permissionGuard)

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()

    @Test
    fun `searchUsers rejects TenantCaller mismatch`() {
        val caller = TenantCaller(actorId, tenantId)
        assertFailsWith<ResourceNotFoundException> {
            service.searchUsers(UUID.randomUUID(), UserInTenantFilter(), caller)
        }
    }

    @Test
    fun `searchUsers routes to requireTenantPermission for TenantCaller`() {
        val caller = TenantCaller(actorId, tenantId)
        permissionGuard.deny(tenantId, "user.view")

        assertFailsWith<SecurityException> {
            service.searchUsers(tenantId, UserInTenantFilter(), caller)
        }
    }

    @Test
    fun `searchRoles validates sorting parameters`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> {
            service.searchRoles(tenantId, RoleFilter(sortBy = "invalid"), caller)
        }
        assertFailsWith<IllegalArgumentException> {
            service.searchRoles(tenantId, RoleFilter(sortBy = "roleCode", sortDir = "UP"), caller)
        }
    }
}

private class FakeIamAdministrationQueries :
    IamUserQueries,
    IamRoleQueries,
    IamAssignmentQueries,
    IamPermissionQueries {
    override fun searchUsers(
        organisationId: UUID,
        filter: UserInTenantFilter,
    ): ApiPage<UserInTenantSummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findMembershipById(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipDetail? = null

    override fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
    ): ApiPage<BranchAssignmentSummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findBranchAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): BranchAssignmentDetail? = null

    override fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
    ): ApiPage<RoleSummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findRoleById(
        organisationId: UUID,
        id: UUID,
    ): RoleDetail? = null

    override fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
    ): ApiPage<RoleAssignmentSummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findRoleAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): RoleAssignmentDetail? = null

    override fun searchPermissions(filter: PermissionFilter): ApiPage<PermissionSummary> =
        apiPageOf(emptyList(), 0, 25, 0)

    override fun findPermissionById(id: UUID): PermissionDetail? = null

    override fun listRolePermissions(
        organisationId: UUID,
        roleId: UUID,
        filter: RolePermissionFilter,
    ): ApiPage<RolePermissionSummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findRolePermissionById(
        organisationId: UUID,
        id: UUID,
    ): RolePermissionDetail? = null
}

private class FakePermissionGuard : PermissionGuard {
    private val denied = mutableSetOf<Pair<UUID, String>>()

    fun deny(
        orgId: UUID,
        permission: String,
    ) {
        denied.add(orgId to permission)
    }

    override fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (denied.contains(organisationId to permissionCode)) {
            throw SecurityException("Missing permission: $permissionCode")
        }
    }

    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    ) {
        val platformOrgId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        requirePermission(actorId, platformOrgId, permissionCode)
    }
}
