package com.finaxis.platform.iam.application.query

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Target-aware reads of branch assignments and role assignments (ADR 0030, decision 5). */
class IamQueryServiceTargetAwareReadTests {
    private val queries = FakeIamAdministrationQueries()
    private val permissionGuard = FakePermissionGuard()
    private val service = IamQueryService(queries, queries, queries, queries, permissionGuard)

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val itemId = UUID.randomUUID()

    @Test
    fun `branch assignment list of a tenant wide holder defaults to the pinned branch`() {
        val pinned = UUID.randomUUID()
        val caller = TenantCaller(actorId, tenantId, pinned)

        service.searchBranchAssignments(
            tenantId,
            BranchAssignmentFilter(pinnedBranchId = pinned),
            caller,
        )

        assertEquals(pinned, queries.lastBranchAssignmentFilter?.branchId)
        assertEquals(null, queries.lastBranchAssignmentRestriction)
    }

    @Test
    fun `branch assignment list shows a branch scoped holder every branch it may view`() {
        val branchA = UUID.randomUUID()
        val branchB = UUID.randomUUID()
        val pinnedElsewhere = UUID.randomUUID()
        permissionGuard.visibleOnly(tenantId, "branch_assignment.view", branchA, branchB)

        service.searchBranchAssignments(
            tenantId,
            BranchAssignmentFilter(pinnedBranchId = pinnedElsewhere),
            TenantCaller(actorId, tenantId, pinnedElsewhere),
        )

        assertEquals(null, queries.lastBranchAssignmentFilter?.branchId)
        assertEquals(setOf(branchA, branchB), queries.lastBranchAssignmentRestriction)
    }

    @Test
    fun `branch assignment list defaults to the pinned branch whenever the caller may view it`() {
        val pinned = UUID.randomUUID()
        permissionGuard.visibleOnly(tenantId, "branch_assignment.view", pinned, UUID.randomUUID())

        service.searchBranchAssignments(
            tenantId,
            BranchAssignmentFilter(pinnedBranchId = pinned),
            TenantCaller(actorId, tenantId, pinned),
        )

        assertEquals(pinned, queries.lastBranchAssignmentFilter?.branchId)
    }

    @Test
    fun `branch assignment list refuses an explicit branch outside the visible set`() {
        val visible = UUID.randomUUID()
        permissionGuard.visibleOnly(tenantId, "branch_assignment.view", visible)
        val caller = TenantCaller(actorId, tenantId)

        assertFailsWith<ForbiddenOperationException> {
            service.searchBranchAssignments(
                tenantId,
                BranchAssignmentFilter(branchId = UUID.randomUUID()),
                caller,
            )
        }
        service.searchBranchAssignments(
            tenantId,
            BranchAssignmentFilter(branchId = visible),
            caller,
        )
        assertEquals(visible, queries.lastBranchAssignmentFilter?.branchId)
    }

    @Test
    fun `branch assignment list is forbidden without any grant`() {
        permissionGuard.deny(tenantId, "branch_assignment.view")

        assertFailsWith<ForbiddenOperationException> {
            service.searchBranchAssignments(
                tenantId,
                BranchAssignmentFilter(),
                TenantCaller(actorId, tenantId),
            )
        }
    }

    @Test
    fun `branch assignment by id is read at the branch of the assignment`() {
        val visible = UUID.randomUUID()
        val caller = TenantCaller(actorId, tenantId)
        permissionGuard.visibleOnly(tenantId, "branch_assignment.view", visible)

        queries.branchAssignmentBranchId = visible
        assertEquals(visible, service.getBranchAssignment(tenantId, itemId, caller).branchId)

        queries.branchAssignmentBranchId = UUID.randomUUID()
        assertFailsWith<ForbiddenOperationException> {
            service.getBranchAssignment(tenantId, itemId, caller)
        }
        // An unknown id is refused exactly like another branch's assignment: no oracle.
        queries.shouldReturnNull = true
        assertFailsWith<ForbiddenOperationException> {
            service.getBranchAssignment(tenantId, itemId, caller)
        }
    }

    @Test
    fun `branch assignment by id is forbidden without any grant`() {
        permissionGuard.deny(tenantId, "branch_assignment.view")

        assertFailsWith<ForbiddenOperationException> {
            service.getBranchAssignment(tenantId, itemId, TenantCaller(actorId, tenantId))
        }
    }

    @Test
    fun `role assignment list is restricted to branch scope rows for a branch scoped holder`() {
        val branchA = UUID.randomUUID()
        permissionGuard.visibleOnly(tenantId, "role_assignment.view", branchA)
        val caller = TenantCaller(actorId, tenantId)

        service.searchRoleAssignments(tenantId, RoleAssignmentFilter(), caller)
        assertEquals(setOf(branchA), queries.lastRoleAssignmentRestriction)

        assertFailsWith<ForbiddenOperationException> {
            service.searchRoleAssignments(
                tenantId,
                RoleAssignmentFilter(branchId = UUID.randomUUID()),
                caller,
            )
        }
        // A scope_type filter is matched like every enum-like filter: the restricted query ANDs it
        // with BRANCH scope, so TENANT (or any other value) is an empty page, never a 403.
        service.searchRoleAssignments(tenantId, RoleAssignmentFilter(scopeType = "TENANT"), caller)
        assertEquals("TENANT", queries.lastRoleAssignmentFilter?.scopeType)
        assertEquals(setOf(branchA), queries.lastRoleAssignmentRestriction)
    }

    @Test
    fun `role assignment list is unrestricted and forbidden as the grant dictates`() {
        val caller = TenantCaller(actorId, tenantId)
        service.searchRoleAssignments(tenantId, RoleAssignmentFilter(scopeType = "TENANT"), caller)
        assertEquals(null, queries.lastRoleAssignmentRestriction)

        permissionGuard.deny(tenantId, "role_assignment.view")
        assertFailsWith<ForbiddenOperationException> {
            service.searchRoleAssignments(tenantId, RoleAssignmentFilter(), caller)
        }
    }

    @Test
    fun `role assignment by id needs the tenant wide view for a tenant scope row`() {
        val branchA = UUID.randomUUID()
        val caller = TenantCaller(actorId, tenantId)
        permissionGuard.visibleOnly(tenantId, "role_assignment.view", branchA)

        queries.roleAssignmentScopeType = "BRANCH"
        queries.roleAssignmentBranchId = branchA
        assertEquals(branchA, service.getRoleAssignment(tenantId, itemId, caller).branchId)

        queries.roleAssignmentBranchId = UUID.randomUUID()
        assertFailsWith<ForbiddenOperationException> {
            service.getRoleAssignment(tenantId, itemId, caller)
        }

        queries.roleAssignmentScopeType = "TENANT"
        queries.roleAssignmentBranchId = null
        assertFailsWith<ForbiddenOperationException> {
            service.getRoleAssignment(tenantId, itemId, caller)
        }

        queries.shouldReturnNull = true
        assertFailsWith<ForbiddenOperationException> {
            service.getRoleAssignment(tenantId, itemId, caller)
        }
    }

    @Test
    fun `role assignment by id answers not found to a tenant wide holder`() {
        queries.shouldReturnNull = true

        assertFailsWith<ResourceNotFoundException> {
            service.getRoleAssignment(tenantId, itemId, TenantCaller(actorId, tenantId))
        }
    }
}
