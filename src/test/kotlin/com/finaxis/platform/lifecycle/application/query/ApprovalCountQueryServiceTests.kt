package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.BranchVisibility
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.MakerCheckerSwitches
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.anyOrNull
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** The authority, exclusion and omission rules of the pending-approval counts (#169). */
class ApprovalCountQueryServiceTests {
    private val organisationId = uuidV7()
    private val actorId = uuidV7()
    private val caller = TenantCaller(actorId, organisationId)
    private val guard = mock(PermissionGuard::class.java)
    private val store = mock(PendingApprovalCountStore::class.java)
    private val switches = MakerCheckerSwitches()
    private val service = ApprovalCountQueryService(store, guard, switches.policy)

    @Test
    fun `a holder of both codes gets both counts excluding its own items`() {
        holdsBranchApproveTenantWide()
        whenever(store.pendingMemberships(organisationId, actorId)).thenReturn(3)
        whenever(store.pendingBranches(organisationId, actorId)).thenReturn(2)

        assertEquals(TenantPendingCounts(3, 2), service.tenantPendingCounts(caller))
    }

    @Test
    fun `a key is omitted for a resource the caller may not approve`() {
        lacks("branch.approve")
        whenever(store.pendingMemberships(organisationId, actorId)).thenReturn(4)

        val counts = service.tenantPendingCounts(caller)

        assertEquals(4L, counts.memberships)
        assertNull(counts.branches)
        verify(store, never()).pendingBranches(any(), anyOrNull())
    }

    @Test
    fun `a branch scoped grant counts as no branch approval`() {
        whenever(guard.mutationBranchVisibility(actorId, organisationId, "branch.approve"))
            .thenReturn(BranchVisibility.Branches(setOf(uuidV7())))
        whenever(store.pendingMemberships(organisationId, actorId)).thenReturn(1)

        val counts = service.tenantPendingCounts(caller)

        assertEquals(1L, counts.memberships)
        assertNull(counts.branches)
    }

    @Test
    fun `a caller who may approve nothing is refused by name and nothing is read`() {
        lacksTenantPermission("user.approve")
        lacks("branch.approve")

        val refusal =
            assertFailsWith<MissingPermissionException> {
                service.tenantPendingCounts(caller)
            }

        assertEquals("user.approve", refusal.permissionCode)
        verify(store, never()).pendingMemberships(any(), anyOrNull())
        verify(store, never()).pendingBranches(any(), anyOrNull())
    }

    @Test
    fun `a tenant that switched the rule off counts the caller's own items`() {
        holdsBranchApproveTenantWide()
        switches.switchOffUserInvites(organisationId)
        switches.switchOffBranchCreation(organisationId)
        whenever(store.pendingMemberships(organisationId, null)).thenReturn(5)
        whenever(store.pendingBranches(organisationId, null)).thenReturn(6)

        assertEquals(TenantPendingCounts(5, 6), service.tenantPendingCounts(caller))
    }

    @Test
    fun `the platform count needs tenant approve and always excludes the caller's own`() {
        val platformCaller = PlatformCaller(actorId, uuidV7())
        whenever(store.pendingTenants(actorId)).thenReturn(7)

        assertEquals(PlatformPendingCounts(7), service.platformPendingCounts(platformCaller))

        whenever(guard.requirePlatformPermission(actorId, "tenant.approve"))
            .thenThrow(MissingPermissionException("tenant.approve"))
        assertFailsWith<MissingPermissionException> {
            service.platformPendingCounts(platformCaller)
        }
        verify(store).pendingTenants(eq(actorId))
    }

    private fun holdsBranchApproveTenantWide() {
        whenever(guard.mutationBranchVisibility(actorId, organisationId, "branch.approve"))
            .thenReturn(BranchVisibility.AllBranches)
    }

    private fun lacks(code: String) {
        whenever(guard.mutationBranchVisibility(actorId, organisationId, code))
            .thenThrow(MissingPermissionException(code))
    }

    private fun lacksTenantPermission(code: String) {
        whenever(guard.requireTenantPermission(actorId, organisationId, code))
            .thenThrow(MissingPermissionException(code))
    }
}
