package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IamQueryServiceTests {
    private val queries = FakeIamAdministrationQueries()
    private val permissionGuard = FakePermissionGuard()
    private val service = IamQueryService(queries, queries, queries, queries, permissionGuard)

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()

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
    fun `searchUsers routes to requirePlatformPermission for PlatformCaller`() {
        val caller =
            PlatformCaller(actorId, UUID.fromString("00000000-0000-0000-0000-000000000000"))
        permissionGuard.deny(UUID.fromString("00000000-0000-0000-0000-000000000000"), "user.view")

        assertFailsWith<SecurityException> {
            service.searchUsers(tenantId, UserInTenantFilter(), caller)
        }
    }

    @Test
    fun `searchUsers happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.searchUsers(tenantId, UserInTenantFilter(), caller)
        assertEquals(1, result.items.size)
        assertEquals("testuser", result.items.first().username)
    }

    @Test
    fun `searchUsers validates pagination`() {
        val caller = TenantCaller(actorId, tenantId)
        assertFailsWith<IllegalArgumentException> {
            service.searchUsers(tenantId, UserInTenantFilter(page = -1), caller)
        }
        assertFailsWith<IllegalArgumentException> {
            service.searchUsers(tenantId, UserInTenantFilter(size = 0), caller)
        }
        assertFailsWith<IllegalArgumentException> {
            service.searchUsers(tenantId, UserInTenantFilter(size = 101), caller)
        }
    }

    @Test
    fun `getMembership happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.getMembership(tenantId, itemId, caller)
        assertEquals("testuser", result.username)
    }

    @Test
    fun `getMembership throws when not found`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true
        assertFailsWith<ResourceNotFoundException> {
            service.getMembership(tenantId, itemId, caller)
        }
    }

    @Test
    fun `searchBranchAssignments happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.searchBranchAssignments(tenantId, BranchAssignmentFilter(), caller)
        assertEquals(1, result.items.size)
        assertEquals("ACTIVE", result.items.first().status)
    }

    @Test
    fun `getBranchAssignment happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.getBranchAssignment(tenantId, itemId, caller)
        assertEquals("ACTIVE", result.status)
    }

    @Test
    fun `getBranchAssignment throws when not found`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true
        assertFailsWith<ResourceNotFoundException> {
            service.getBranchAssignment(tenantId, itemId, caller)
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

    @Test
    fun `searchRoles happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result =
            service.searchRoles(
                tenantId,
                RoleFilter(sortBy = "roleCode", sortDir = "ASC"),
                caller,
            )
        assertEquals(1, result.items.size)
        assertEquals("ROLE_ADMIN", result.items.first().roleCode)
    }

    @Test
    fun `getRole happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.getRole(tenantId, itemId, caller)
        assertEquals("ROLE_ADMIN", result.roleCode)
    }

    @Test
    fun `getRole throws when not found`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true
        assertFailsWith<ResourceNotFoundException> {
            service.getRole(tenantId, itemId, caller)
        }
    }

    @Test
    fun `searchRoleAssignments happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.searchRoleAssignments(tenantId, RoleAssignmentFilter(), caller)
        assertEquals(1, result.items.size)
        assertEquals("ACTIVE", result.items.first().status)
    }

    @Test
    fun `getRoleAssignment happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.getRoleAssignment(tenantId, itemId, caller)
        assertEquals("ACTIVE", result.status)
    }

    @Test
    fun `getRoleAssignment throws when not found`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true
        assertFailsWith<ResourceNotFoundException> {
            service.getRoleAssignment(tenantId, itemId, caller)
        }
    }

    @Test
    fun `searchPermissions happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result =
            service.searchPermissions(
                tenantId,
                PermissionFilter(sortBy = "permissionCode", sortDir = "DESC"),
                caller,
            )
        assertEquals(1, result.items.size)
        assertEquals("user.view", result.items.first().permissionCode)
    }

    @Test
    fun `getPermission happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.getPermission(tenantId, itemId, caller)
        assertEquals("user.view", result.permissionCode)
    }

    @Test
    fun `getPermission throws when not found`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true
        assertFailsWith<ResourceNotFoundException> {
            service.getPermission(tenantId, itemId, caller)
        }
    }

    @Test
    fun `listRolePermissions happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.listRolePermissions(tenantId, itemId, RolePermissionFilter(), caller)
        assertEquals(1, result.items.size)
        assertEquals("user.view", result.items.first().permissionCode)
    }

    @Test
    fun `getRolePermission happy path`() {
        val caller = TenantCaller(actorId, tenantId)
        val result = service.getRolePermission(tenantId, itemId, caller)
        assertEquals("user.view", result.permissionCode)
    }

    @Test
    fun `getRolePermission throws when not found`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true
        assertFailsWith<ResourceNotFoundException> {
            service.getRolePermission(tenantId, itemId, caller)
        }
    }

    @Test
    fun `searchUsers happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.searchUsers(tenantId, UserInTenantFilter(), caller)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `getMembership happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.getMembership(tenantId, itemId, caller)
        assertEquals("testuser", result.username)
    }

    @Test
    fun `searchBranchAssignments happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.searchBranchAssignments(tenantId, BranchAssignmentFilter(), caller)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `getBranchAssignment happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.getBranchAssignment(tenantId, itemId, caller)
        assertEquals("ACTIVE", result.status)
    }

    @Test
    fun `searchRoles happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result =
            service.searchRoles(
                tenantId,
                RoleFilter(sortBy = "roleCode", sortDir = "ASC"),
                caller,
            )
        assertEquals(1, result.items.size)
    }

    @Test
    fun `getRole happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.getRole(tenantId, itemId, caller)
        assertEquals("ROLE_ADMIN", result.roleCode)
    }

    @Test
    fun `searchRoleAssignments happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.searchRoleAssignments(tenantId, RoleAssignmentFilter(), caller)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `getRoleAssignment happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.getRoleAssignment(tenantId, itemId, caller)
        assertEquals("ACTIVE", result.status)
    }

    @Test
    fun `searchPermissions happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result =
            service.searchPermissions(
                tenantId,
                PermissionFilter(sortBy = "permissionCode", sortDir = "DESC"),
                caller,
            )
        assertEquals(1, result.items.size)
    }

    @Test
    fun `getPermission happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.getPermission(tenantId, itemId, caller)
        assertEquals("user.view", result.permissionCode)
    }

    @Test
    fun `listRolePermissions happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.listRolePermissions(tenantId, itemId, RolePermissionFilter(), caller)
        assertEquals(1, result.items.size)
    }

    @Test
    fun `getRolePermission happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val result = service.getRolePermission(tenantId, itemId, caller)
        assertEquals("user.view", result.permissionCode)
    }

    @Test
    fun `verify full data model coverage part 1`() {
        val id = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = Instant.now()

        val u1 = UserInTenantSummary(id, "u", "e", "d", "a", "a")
        assertNotNull(u1.toString())
        assertEquals(u1, u1.copy())
        assertEquals(u1.hashCode(), u1.copy().hashCode())
        assertTrue(u1 == u1)

        val m1 = MembershipDetail(id, orgId, userId, "u", "e", "d", "a", "a", "t", null, now, now)
        assertNotNull(m1.toString())
        assertEquals(m1, m1.copy())
        assertEquals(m1.hashCode(), m1.copy().hashCode())

        val b1 = BranchAssignmentSummary(id, userId, id, "t", "a")
        assertNotNull(b1.toString())
        assertEquals(b1, b1.copy())
        assertEquals(b1.hashCode(), b1.copy().hashCode())

        val b2 =
            BranchAssignmentDetail(id, orgId, userId, id, "t", "a", now, null, null, null, now, now)
        assertNotNull(b2.toString())
        assertEquals(b2, b2.copy())
        assertEquals(b2.hashCode(), b2.copy().hashCode())

        val r1 = RoleSummary(id, "c", "n", true, "a")
        assertNotNull(r1.toString())
        assertEquals(r1, r1.copy())
        assertEquals(r1.hashCode(), r1.copy().hashCode())

        val r2 = RoleDetail(id, orgId, "c", "n", "d", true, "a", now, now)
        assertNotNull(r2.toString())
        assertEquals(r2, r2.copy())
        assertEquals(r2.hashCode(), r2.copy().hashCode())
    }

    @Test
    fun `verify full data model coverage part 2`() {
        val id = UUID.randomUUID()
        val orgId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val now = Instant.now()

        val ra1 = RoleAssignmentSummary(id, userId, id, null, "s", "a")
        assertNotNull(ra1.toString())
        assertEquals(ra1, ra1.copy())
        assertEquals(ra1.hashCode(), ra1.copy().hashCode())

        val ra2 =
            RoleAssignmentDetail(
                id,
                orgId,
                userId,
                id,
                null,
                "s",
                "a",
                now,
                null,
                null,
                null,
                now,
                now,
            )
        assertNotNull(ra2.toString())
        assertEquals(ra2, ra2.copy())
        assertEquals(ra2.hashCode(), ra2.copy().hashCode())

        val p1 = PermissionSummary(id, "c", "n", "m", "r", "a")
        assertNotNull(p1.toString())
        assertEquals(p1, p1.copy())
        assertEquals(p1.hashCode(), p1.copy().hashCode())

        val p2 = PermissionDetail(id, "c", "n", "m", "d", "r", "a", now, now)
        assertNotNull(p2.toString())
        assertEquals(p2, p2.copy())
        assertEquals(p2.hashCode(), p2.copy().hashCode())

        val rp1 = RolePermissionSummary(id, id, id, "c", now)
        assertNotNull(rp1.toString())
        assertEquals(rp1, rp1.copy())
        assertEquals(rp1.hashCode(), rp1.copy().hashCode())

        val rp2 = RolePermissionDetail(id, orgId, id, id, "c", now, null, now, now)
        assertNotNull(rp2.toString())
        assertEquals(rp2, rp2.copy())
        assertEquals(rp2.hashCode(), rp2.copy().hashCode())
    }
}

private class FakeIamAdministrationQueries :
    IamUserQueries,
    IamRoleQueries,
    IamAssignmentQueries,
    IamPermissionQueries {
    var shouldReturnNull = false

    override fun searchUsers(
        organisationId: UUID,
        filter: UserInTenantFilter,
    ): ApiPage<UserInTenantSummary> =
        apiPageOf(
            listOf(
                UserInTenantSummary(
                    UUID.randomUUID(),
                    "testuser",
                    "email@test.com",
                    "Test User",
                    "ACTIVE",
                    "ACTIVE",
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun findMembershipById(
        organisationId: UUID,
        membershipId: UUID,
    ): MembershipDetail? {
        if (shouldReturnNull) return null
        return MembershipDetail(
            id = membershipId,
            organisationId = organisationId,
            userId = UUID.randomUUID(),
            username = "testuser",
            email = "email@test.com",
            displayName = "Test User",
            userStatus = "ACTIVE",
            membershipStatus = "ACTIVE",
            membershipType = "STAFF",
            primaryBranchId = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    override fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
    ): ApiPage<BranchAssignmentSummary> =
        apiPageOf(
            listOf(
                BranchAssignmentSummary(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "VIEW",
                    "ACTIVE",
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun findBranchAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): BranchAssignmentDetail? {
        if (shouldReturnNull) return null
        return BranchAssignmentDetail(
            id = id,
            organisationId = organisationId,
            userId = UUID.randomUUID(),
            branchId = UUID.randomUUID(),
            assignmentType = "VIEW",
            status = "ACTIVE",
            assignedAt = Instant.now(),
            assignedBy = null,
            revokedAt = null,
            revokedBy = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    override fun searchRoles(
        organisationId: UUID,
        filter: RoleFilter,
    ): ApiPage<RoleSummary> =
        apiPageOf(
            listOf(
                RoleSummary(
                    UUID.randomUUID(),
                    "ROLE_ADMIN",
                    "Admin Role",
                    false,
                    "ACTIVE",
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun findRoleById(
        organisationId: UUID,
        id: UUID,
    ): RoleDetail? {
        if (shouldReturnNull) return null
        return RoleDetail(
            id = id,
            organisationId = organisationId,
            roleCode = "ROLE_ADMIN",
            roleName = "Admin Role",
            description = "Admin Description",
            systemRole = false,
            status = "ACTIVE",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    override fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
    ): ApiPage<RoleAssignmentSummary> =
        apiPageOf(
            listOf(
                RoleAssignmentSummary(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    "TENANT",
                    "ACTIVE",
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun findRoleAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): RoleAssignmentDetail? {
        if (shouldReturnNull) return null
        return RoleAssignmentDetail(
            id = id,
            organisationId = organisationId,
            userId = UUID.randomUUID(),
            roleId = UUID.randomUUID(),
            branchId = null,
            scopeType = "TENANT",
            status = "ACTIVE",
            assignedAt = Instant.now(),
            assignedBy = null,
            revokedAt = null,
            revokedBy = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    override fun searchPermissions(filter: PermissionFilter): ApiPage<PermissionSummary> =
        apiPageOf(
            listOf(
                PermissionSummary(
                    UUID.randomUUID(),
                    "user.view",
                    "View Users",
                    "iam",
                    "LOW",
                    "ACTIVE",
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun findPermissionById(id: UUID): PermissionDetail? {
        if (shouldReturnNull) return null
        return PermissionDetail(
            id = id,
            permissionCode = "user.view",
            permissionName = "View Users",
            moduleCode = "iam",
            description = "View users permissions",
            riskLevel = "LOW",
            status = "ACTIVE",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    override fun listRolePermissions(
        organisationId: UUID,
        roleId: UUID,
        filter: RolePermissionFilter,
    ): ApiPage<RolePermissionSummary> =
        apiPageOf(
            listOf(
                RolePermissionSummary(
                    UUID.randomUUID(),
                    roleId,
                    UUID.randomUUID(),
                    "user.view",
                    Instant.now(),
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun findRolePermissionById(
        organisationId: UUID,
        id: UUID,
    ): RolePermissionDetail? {
        if (shouldReturnNull) return null
        return RolePermissionDetail(
            id = id,
            organisationId = organisationId,
            roleId = UUID.randomUUID(),
            permissionId = UUID.randomUUID(),
            permissionCode = "user.view",
            grantedAt = Instant.now(),
            grantedBy = null,
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }
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
