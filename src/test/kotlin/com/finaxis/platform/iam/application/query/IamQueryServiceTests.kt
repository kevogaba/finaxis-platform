package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.BranchVisibility
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
        assertRejected(null) {
            service.searchUsers(tenantId, UserInTenantFilter(page = -1), caller)
        }
        assertRejected(null) {
            service.searchUsers(tenantId, UserInTenantFilter(size = 0), caller)
        }
        assertRejected(null) {
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
    fun `getUserInTenant returns tenant scoped user detail`() {
        val caller = TenantCaller(actorId, tenantId)

        val result = service.getUserInTenant(tenantId, itemId, caller)

        assertEquals(itemId, result.id)
        assertEquals("testuser", result.username)
    }

    @Test
    fun `getUserInTenant returns a safe not found result`() {
        val caller = TenantCaller(actorId, tenantId)
        queries.shouldReturnNull = true

        assertFailsWith<ResourceNotFoundException> {
            service.getUserInTenant(tenantId, itemId, caller)
        }
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
    fun `searchMemberships validates permission and pagination then delegates`() {
        val caller = TenantCaller(actorId, tenantId)
        permissionGuard.deny(tenantId, "membership.view")

        assertFailsWith<SecurityException> {
            service.searchMemberships(tenantId, MembershipFilter(), caller)
        }

        val permitted = FakePermissionGuard()
        val permittedService = IamQueryService(queries, queries, queries, queries, permitted)
        assertRejected(null) {
            permittedService.searchMemberships(tenantId, MembershipFilter(page = -1), caller)
        }
        assertRejected(null) {
            permittedService.searchMemberships(tenantId, MembershipFilter(size = 101), caller)
        }

        val result = permittedService.searchMemberships(tenantId, MembershipFilter(), caller)
        assertEquals(1, result.items.size)
        assertEquals("ACTIVE", result.items.single().membershipStatus)
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
    fun `searchRoles validates paging and sorting parameters`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        assertRejected("sort_by") {
            service.searchRoles(tenantId, RoleFilter(sortBy = "invalid"), caller)
        }
        assertRejected("sort_dir") {
            service.searchRoles(tenantId, RoleFilter(sortBy = "roleCode", sortDir = "UP"), caller)
        }
        assertRejected(null) { service.searchRoles(tenantId, RoleFilter(page = -1), caller) }
        assertRejected(null) { service.searchRoles(tenantId, RoleFilter(size = 101), caller) }
        assertEquals(
            1,
            service
                .searchRoles(tenantId, RoleFilter(sortBy = "roleName", sortDir = "desc"), caller)
                .items.size,
        )
    }

    @Test
    fun `searchPermissions validates paging and sorting parameters`() {
        val caller = TenantCaller(actorId, tenantId)
        assertRejected("sort_by") {
            service.searchPermissions(tenantId, PermissionFilter(sortBy = "bogus"), caller)
        }
        assertRejected("sort_dir") {
            service.searchPermissions(
                tenantId,
                PermissionFilter(sortBy = "riskLevel", sortDir = "sideways"),
                caller,
            )
        }
        assertRejected(null) {
            service.searchPermissions(tenantId, PermissionFilter(page = -1), caller)
        }
        assertRejected(null) {
            service.searchPermissions(tenantId, PermissionFilter(size = 0), caller)
        }
    }

    @Test
    fun `branch assignment role assignment and role permission lists validate paging`() {
        val caller = TenantCaller(actorId, tenantId)
        assertRejected(null) {
            service.searchBranchAssignments(tenantId, BranchAssignmentFilter(page = -1), caller)
        }
        assertRejected(null) {
            service.searchBranchAssignments(tenantId, BranchAssignmentFilter(size = 101), caller)
        }
        assertRejected(null) {
            service.searchRoleAssignments(tenantId, RoleAssignmentFilter(page = -1), caller)
        }
        assertRejected(null) {
            service.searchRoleAssignments(tenantId, RoleAssignmentFilter(size = 0), caller)
        }
        assertRejected(null) {
            service.listRolePermissions(tenantId, itemId, RolePermissionFilter(size = 101), caller)
        }
        assertRejected(null) {
            service.listRolePermissions(tenantId, itemId, RolePermissionFilter(page = -1), caller)
        }
    }

    private fun assertRejected(
        parameter: String?,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<InvalidPageRequestException>(block = block)
        assertEquals(parameter, failure.parameter)
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
    fun `getUserInTenant happy path with PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())

        val result = service.getUserInTenant(tenantId, itemId, caller)

        assertEquals(itemId, result.id)
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

        val u2 = UserInTenantDetail(id, "u", "e", "d", "a", "a")
        assertNotNull(u2.toString())
        assertEquals(u2, u2.copy())
        assertEquals(u2.hashCode(), u2.copy().hashCode())

        val m1 = MembershipDetail(id, orgId, userId, "u", "e", "d", "a", "a", "t", null, now, now)
        assertNotNull(m1.toString())
        assertEquals(m1, m1.copy())
        assertEquals(m1.hashCode(), m1.copy().hashCode())

        val ms1 = MembershipSummary(id, userId, "a", "t", null)
        assertNotNull(ms1.toString())
        assertEquals(ms1, ms1.copy())
        assertEquals(ms1.hashCode(), ms1.copy().hashCode())

        val b1 = BranchAssignmentSummary(id, userId, id, "t", "a")
        assertNotNull(b1.toString())
        assertEquals(b1, b1.copy())
        assertEquals(b1.hashCode(), b1.copy().hashCode())

        val b2 =
            BranchAssignmentDetail(id, orgId, userId, id, "t", "a", now, null, null, null, now, now)
        assertNotNull(b2.toString())
        assertEquals(b2, b2.copy())
        assertEquals(b2.hashCode(), b2.copy().hashCode())

        val r1 = RoleSummary(id, "c", "n", true, "a", listOf("branch.view"))
        assertNotNull(r1.toString())
        assertEquals(r1, r1.copy())
        assertEquals(r1.hashCode(), r1.copy().hashCode())

        val r2 = RoleDetail(id, orgId, "c", "n", "d", true, "a", listOf("branch.view"), now, now)
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

        val p1 = PermissionSummary(id, "c", "n", "m", "r", "a", "VIEW", "TENANT", emptyList())
        assertNotNull(p1.toString())
        assertEquals(p1, p1.copy())
        assertEquals(p1.hashCode(), p1.copy().hashCode())

        val p2 =
            PermissionDetail(
                id,
                "c",
                "n",
                "m",
                "d",
                "r",
                "a",
                "VIEW",
                "TENANT",
                emptyList(),
                now,
                now,
            )
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

internal class FakeIamAdministrationQueries :
    IamUserQueries,
    IamRoleQueries,
    IamAssignmentQueries,
    IamPermissionQueries {
    var shouldReturnNull = false
    var branchAssignmentBranchId: UUID = UUID.randomUUID()
    var roleAssignmentScopeType = "TENANT"
    var roleAssignmentBranchId: UUID? = null
    var lastBranchAssignmentFilter: BranchAssignmentFilter? = null
    var lastBranchAssignmentRestriction: Set<UUID>? = null
    var lastRoleAssignmentFilter: RoleAssignmentFilter? = null
    var lastRoleAssignmentRestriction: Set<UUID>? = null

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

    override fun findUserInTenant(
        organisationId: UUID,
        userId: UUID,
    ): UserInTenantDetail? {
        if (shouldReturnNull) return null
        return UserInTenantDetail(
            id = userId,
            username = "testuser",
            email = "email@test.com",
            displayName = "Test User",
            userStatus = "ACTIVE",
            membershipStatus = "ACTIVE",
        )
    }

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

    override fun searchMemberships(
        organisationId: UUID,
        filter: MembershipFilter,
    ): ApiPage<MembershipSummary> =
        apiPageOf(
            listOf(
                MembershipSummary(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "ACTIVE",
                    "STAFF",
                    null,
                ),
            ),
            filter.page,
            filter.size,
            1L,
        )

    override fun searchBranchAssignments(
        organisationId: UUID,
        filter: BranchAssignmentFilter,
        restrictToBranchIds: Set<UUID>?,
    ): ApiPage<BranchAssignmentSummary> {
        lastBranchAssignmentFilter = filter
        lastBranchAssignmentRestriction = restrictToBranchIds
        return apiPageOf(
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
    }

    override fun findBranchAssignmentById(
        organisationId: UUID,
        id: UUID,
    ): BranchAssignmentDetail? {
        if (shouldReturnNull) return null
        return BranchAssignmentDetail(
            id = id,
            organisationId = organisationId,
            userId = UUID.randomUUID(),
            branchId = branchAssignmentBranchId,
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
                    emptyList(),
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
            missingViewPermissions = emptyList(),
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    override fun searchRoleAssignments(
        organisationId: UUID,
        filter: RoleAssignmentFilter,
        restrictToBranchIds: Set<UUID>?,
    ): ApiPage<RoleAssignmentSummary> {
        lastRoleAssignmentFilter = filter
        lastRoleAssignmentRestriction = restrictToBranchIds
        return apiPageOf(
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
    }

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
            branchId = roleAssignmentBranchId,
            scopeType = roleAssignmentScopeType,
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
                    "VIEW",
                    "TENANT",
                    emptyList(),
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
            kind = "VIEW",
            grantScope = "TENANT",
            requiredViewPermissions = emptyList(),
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

internal class FakePermissionGuard : PermissionGuard {
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
        val visibility = visibilities[organisationId to permissionCode]
        if (visibility != null && !visibility.canSee(branchId)) {
            throw SecurityException("Missing permission: $permissionCode")
        }
        if (visibility == null) {
            requirePermission(actorId, organisationId, permissionCode)
        }
    }

    override fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    ) {
        val platformOrgId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        requirePermission(actorId, platformOrgId, permissionCode)
    }

    private val visibilities = mutableMapOf<Pair<UUID, String>, BranchVisibility>()

    fun visibleOnly(
        organisationId: UUID,
        permissionCode: String,
        vararg branchIds: UUID,
    ) {
        visibilities[organisationId to permissionCode] =
            BranchVisibility.Branches(branchIds.toSet())
    }

    override fun branchVisibility(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ): BranchVisibility =
        visibilities[organisationId to permissionCode]
            ?: if (denied.contains(organisationId to permissionCode)) {
                BranchVisibility.Branches(emptySet())
            } else {
                BranchVisibility.AllBranches
            }
}
