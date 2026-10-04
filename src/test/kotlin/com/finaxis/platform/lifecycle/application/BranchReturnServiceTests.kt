package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.context.PlatformOrganisation
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rules of returning or withdrawing a pending branch (ADR 0029, 3b), against stubbed ports: who
 * is a maker, which permission each intent needs and where, the order of the checks, the platform
 * checker window, and the audit rows each intent leaves.
 */
class BranchReturnServiceTests {
    private val organisationId = uuidV7()
    private val branchId = uuidV7()
    private val creator = uuidV7()
    private val submitter = uuidV7()
    private val checker = uuidV7()
    private val store = mock(BranchLifecycleStore::class.java)
    private val lifecycle = mock(FoundationLifecycleService::class.java)
    private val audit = mock(AuditService::class.java)
    private val guard = mock(PermissionGuard::class.java)
    private val service =
        BranchProvisioningService(
            lifecycle,
            store,
            mock(BranchAssignmentStore::class.java),
            audit,
            mock(TransitionEventPublisher::class.java),
            guard,
        )

    private fun givenPendingBranch(
        organisationState: OrganisationLifecycleState? = OrganisationLifecycleState.ACTIVE,
        checkerWindowOpen: Boolean = true,
    ) {
        whenever(store.branchState(organisationId, branchId))
            .thenReturn(BranchLifecycleState.PENDING_APPROVAL)
        whenever(store.createdBy(organisationId, branchId)).thenReturn(creator)
        whenever(store.submittedBy(organisationId, branchId)).thenReturn(submitter)
        whenever(store.organisationState(organisationId)).thenReturn(organisationState)
        whenever(store.hasActiveBranchBeyondBootstrap(organisationId))
            .thenReturn(!checkerWindowOpen)
        whenever(audit.record(any())).thenReturn(mock(AuditEvent::class.java))
    }

    private fun command(
        actor: UUID,
        scope: ActingScope = ActingScope.TENANT,
        organisation: UUID = organisationId,
    ) = ReturnBranchCommand(organisation, branchId, Reason.required(REASON), actor, scope)

    private fun auditRows(): List<AuditCommand> {
        val captor = argumentCaptor<AuditCommand>()
        verify(audit, org.mockito.Mockito.atLeast(0)).record(captor.capture())
        return captor.allValues
    }

    private fun assertTransitioned() {
        val captor = argumentCaptor<BranchTransitionCommand>()
        verify(lifecycle).transition(captor.capture())
        assertEquals(organisationId, captor.firstValue.organisationId)
        assertEquals(branchId, captor.firstValue.branchId)
        assertEquals(BranchLifecycleTransition.RETURN_FOR_CHANGES, captor.firstValue.transition)
        assertEquals(REASON, captor.firstValue.command.reason)
    }

    private fun assertNotTransitioned() {
        verify(lifecycle, never()).transition(any<BranchTransitionCommand>())
        assertTrue(auditRows().isEmpty(), "a refused return must not leave an audit row")
    }

    @Test
    fun `a checker who is neither maker returns the branch with branch approve`() {
        givenPendingBranch()

        service.returnForChanges(command(checker))

        verify(guard).requireBranchPermission(checker, organisationId, branchId, "branch.approve")
        verify(guard, never())
            .requireBranchPermission(checker, organisationId, branchId, "branch.create")
        verify(guard, never())
            .requireBranchPermission(checker, organisationId, branchId, "branch.activate")
        assertTransitioned()
        assertTrue(auditRows().isEmpty(), "the FSM row is the only audit of a tenant return")
    }

    @Test
    fun `the creator withdraws with branch create and a withdraw audit row`() {
        givenPendingBranch()

        service.returnForChanges(command(creator))

        verify(guard).requireBranchPermission(creator, organisationId, branchId, "branch.create")
        verify(guard, never())
            .requireBranchPermission(creator, organisationId, branchId, "branch.approve")
        assertTransitioned()
        val row = auditRows().single()
        assertEquals("branch.withdraw", row.action)
        assertEquals("BRANCH", row.resourceType)
        assertEquals(branchId.toString(), row.resourceId)
        assertEquals(organisationId.toString(), row.tenantId)
        assertEquals(creator.toString(), row.actorId)
        assertEquals(REASON, row.reason)
        assertNull(row.metadata["checkerScope"])
    }

    @Test
    fun `the latest submitter withdraws with branch create`() {
        givenPendingBranch()

        service.returnForChanges(command(submitter))

        verify(guard).requireBranchPermission(submitter, organisationId, branchId, "branch.create")
        assertTransitioned()
        assertEquals("branch.withdraw", auditRows().single().action)
    }

    @Test
    fun `a creator who also holds branch activate is still a maker and needs branch create`() {
        givenPendingBranch()
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requireBranchPermission(creator, organisationId, branchId, "branch.create")

        assertFailsWith<ForbiddenOperationException> { service.returnForChanges(command(creator)) }

        verify(guard, never())
            .requireBranchPermission(creator, organisationId, branchId, "branch.approve")
        assertNotTransitioned()
    }

    @Test
    fun `a maker who lost branch create cannot withdraw but a checker can still return`() {
        givenPendingBranch()
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requireBranchPermission(submitter, organisationId, branchId, "branch.create")

        assertFailsWith<ForbiddenOperationException> {
            service.returnForChanges(command(submitter))
        }
        service.returnForChanges(command(checker))

        assertTransitioned()
    }

    @Test
    fun `a missing permission is refused before any existence signal`() {
        whenever(store.branchState(organisationId, branchId)).thenReturn(null)
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requireBranchPermission(checker, organisationId, branchId, "branch.approve")

        assertFailsWith<ForbiddenOperationException> { service.returnForChanges(command(checker)) }

        verify(store, never()).branchState(any(), any())
        verify(store, never()).organisationState(any())
        assertNotTransitioned()
    }

    @Test
    fun `a missing or foreign branch is classified as a return and then not found`() {
        // The store is organisation-scoped: another tenant's branch reads as absent.
        whenever(store.branchState(organisationId, branchId)).thenReturn(null)
        whenever(store.createdBy(organisationId, branchId)).thenReturn(null)
        whenever(store.submittedBy(organisationId, branchId)).thenReturn(null)

        assertFailsWith<ResourceNotFoundException> {
            service.returnForChanges(command(creator))
        }

        verify(guard).requireBranchPermission(creator, organisationId, branchId, "branch.approve")
        assertNotTransitioned()
    }

    @Test
    fun `an organisation that is not active or provisioning freezes its pending branches`() {
        OrganisationLifecycleState.entries
            .filter {
                it !in
                    setOf(
                        OrganisationLifecycleState.ACTIVE,
                        OrganisationLifecycleState.PROVISIONING,
                    )
            }.forEach { state ->
                givenPendingBranch(organisationState = state)
                listOf(checker, creator).forEach { actor ->
                    assertFailsWith<ConflictException>("$state for $actor") {
                        service.returnForChanges(command(actor))
                    }
                }
            }
        verify(lifecycle, never()).transition(any<BranchTransitionCommand>())
    }

    @ParameterizedTest
    @EnumSource(
        value = OrganisationLifecycleState::class,
        names = ["ACTIVE", "PROVISIONING"],
    )
    fun `an active or provisioning organisation lets both intents through`(
        state: OrganisationLifecycleState,
    ) {
        givenPendingBranch(organisationState = state)

        service.returnForChanges(command(checker))
        service.returnForChanges(command(creator))

        verify(lifecycle, org.mockito.Mockito.times(2)).transition(any<BranchTransitionCommand>())
    }

    @Test
    fun `a missing organisation is not found`() {
        givenPendingBranch(organisationState = null)

        assertFailsWith<ResourceNotFoundException> { service.returnForChanges(command(checker)) }
        assertNotTransitioned()
    }

    @Test
    fun `a branch that is not pending is the FSM conflict and leaves no audit row`() {
        givenPendingBranch()
        doThrow(ConflictException())
            .whenever(lifecycle)
            .transition(any<BranchTransitionCommand>())

        assertFailsWith<ConflictException> { service.returnForChanges(command(creator)) }
        assertFailsWith<ConflictException> { service.returnForChanges(command(checker)) }

        assertTrue(auditRows().isEmpty(), "no withdraw or checker row for a refused transition")
    }

    @Test
    fun `the organisation state is checked before the branch state`() {
        givenPendingBranch(organisationState = OrganisationLifecycleState.SUSPENDED)

        assertFailsWith<ConflictException> { service.returnForChanges(command(checker)) }

        verify(lifecycle, never()).transition(any<BranchTransitionCommand>())
    }

    @Test
    fun `a platform checker returns with the platform permission and is audited as the checker`() {
        givenPendingBranch()

        service.returnForChanges(command(checker, ActingScope.PLATFORM))

        verify(guard).requirePlatformPermission(checker, "branch.approve")
        verify(guard, never()).requireBranchPermission(any(), any(), any(), any())
        verify(guard, never()).requirePlatformPermission(checker, "branch.activate")
        verify(guard, never()).requireTenantPermission(any(), any(), any())
        assertTransitioned()
        val row = auditRows().single()
        assertEquals("branch.return_for_changes_as_platform_checker", row.action)
        assertEquals(checker.toString(), row.actorId)
        assertEquals(organisationId.toString(), row.tenantId)
        assertEquals(branchId.toString(), row.resourceId)
        assertEquals(REASON, row.reason)
        assertEquals("PLATFORM", row.metadata["checkerScope"])
    }

    @Test
    fun `a platform checker return is refused once the tenant has an active branch of its own`() {
        givenPendingBranch(checkerWindowOpen = false)

        val error =
            assertFailsWith<ConflictException> {
                service.returnForChanges(command(checker, ActingScope.PLATFORM))
            }

        assertEquals(LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED, error.code)
        assertNotTransitioned()
        // The tenant's own checker is not bounded by the window.
        service.returnForChanges(command(checker))
        assertTransitioned()
    }

    @Test
    fun `a platform withdrawal needs branch create and is not bounded by the window`() {
        givenPendingBranch(checkerWindowOpen = false)

        service.returnForChanges(command(creator, ActingScope.PLATFORM))
        service.returnForChanges(command(submitter, ActingScope.PLATFORM))

        verify(guard).requirePlatformPermission(creator, "branch.create")
        verify(guard).requirePlatformPermission(submitter, "branch.create")
        verify(guard, never()).requirePlatformPermission(any(), eq("branch.approve"))
        verify(store, never()).hasActiveBranchBeyondBootstrap(any())
        val rows = auditRows()
        assertEquals(listOf("branch.withdraw", "branch.withdraw"), rows.map { it.action })
        rows.forEach { assertNull(it.metadata["checkerScope"], "a withdrawal carries no marker") }
    }

    @Test
    fun `the platform organisation is never a tenant and is refused after the permission`() {
        whenever(store.branchState(PlatformOrganisation.ID, branchId))
            .thenReturn(BranchLifecycleState.PENDING_APPROVAL)

        assertFailsWith<ResourceNotFoundException> {
            service.returnForChanges(
                command(checker, ActingScope.PLATFORM, PlatformOrganisation.ID),
            )
        }

        verify(guard).requirePlatformPermission(checker, "branch.approve")
        assertNotTransitioned()
    }

    @Test
    fun `a platform caller without the permission is refused before the 404`() {
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requirePlatformPermission(checker, "branch.approve")

        assertFailsWith<ForbiddenOperationException> {
            service.returnForChanges(
                command(checker, ActingScope.PLATFORM, PlatformOrganisation.ID),
            )
        }
        assertNotTransitioned()
    }

    @Test
    fun `the branch 404 precedes the platform window which precedes the organisation state`() {
        // Absent branch with a closed window: 404, not the window conflict.
        whenever(store.branchState(organisationId, branchId)).thenReturn(null)
        whenever(store.hasActiveBranchBeyondBootstrap(organisationId)).thenReturn(true)
        assertFailsWith<ResourceNotFoundException> {
            service.returnForChanges(command(checker, ActingScope.PLATFORM))
        }

        // Closed window with a frozen tenant: the window conflict.
        givenPendingBranch(
            organisationState = OrganisationLifecycleState.SUSPENDED,
            checkerWindowOpen = false,
        )
        val error =
            assertFailsWith<ConflictException> {
                service.returnForChanges(command(checker, ActingScope.PLATFORM))
            }
        assertEquals(LifecycleErrorCodes.PLATFORM_CHECKER_CLOSED, error.code)
    }

    private companion object {
        const val REASON = "Branch code has a typo."
    }
}
