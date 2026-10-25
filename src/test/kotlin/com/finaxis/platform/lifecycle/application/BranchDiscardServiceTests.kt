package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.BranchLifecycleTransition
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Rules of discarding a draft branch (#222, ADR 0036), against stubbed ports: the permission and
 * where it is asked, the order of the checks, the head office and the organisation state, and that
 * the FSM's own row is the only audit of the discard.
 */
class BranchDiscardServiceTests {
    private val organisationId = uuidV7()
    private val branchId = uuidV7()
    private val actor = uuidV7()
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
            allControlsOn(),
        )

    private fun givenDraft(
        organisationState: OrganisationLifecycleState? = OrganisationLifecycleState.ACTIVE,
        headOffice: Boolean = false,
    ) {
        whenever(store.branchState(organisationId, branchId)).thenReturn(BranchLifecycleState.DRAFT)
        whenever(store.organisationState(organisationId)).thenReturn(organisationState)
        whenever(store.isHeadOffice(organisationId, branchId)).thenReturn(headOffice)
    }

    private fun command() =
        DiscardBranchCommand(organisationId, branchId, Reason.required(REASON), actor)

    private fun assertNotTransitioned() {
        verify(lifecycle, never()).transition(any<BranchTransitionCommand>())
    }

    @Test
    fun `a draft is discarded through the FSM with the reason and nothing else is audited`() {
        givenDraft()

        service.discard(command())

        verify(guard).requireBranchPermission(actor, organisationId, branchId, "branch.create")
        val captor = argumentCaptor<BranchTransitionCommand>()
        verify(lifecycle).transition(captor.capture())
        assertEquals(organisationId, captor.firstValue.organisationId)
        assertEquals(branchId, captor.firstValue.branchId)
        assertEquals(BranchLifecycleTransition.DISCARD, captor.firstValue.transition)
        assertEquals(REASON, captor.firstValue.command.reason)
        // ADR 0031: the FSM row is the record of the transition, and it carries the reason.
        verify(audit, never()).record(any())
        verify(audit, never()).recordIndependently(any())
    }

    @Test
    fun `a missing permission is refused before any lookup`() {
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requireBranchPermission(actor, organisationId, branchId, "branch.create")

        assertFailsWith<ForbiddenOperationException> { service.discard(command()) }

        verify(store, never()).branchState(any(), any())
        verify(store, never()).organisationState(any())
        verify(store, never()).isHeadOffice(any(), any())
        verify(store, never()).lockOrganisation(any())
        assertNotTransitioned()
    }

    @Test
    fun `a missing or foreign branch is not found after the permission`() {
        // The store is organisation-scoped: another tenant's branch reads as absent.
        whenever(store.branchState(organisationId, branchId)).thenReturn(null)

        assertFailsWith<ResourceNotFoundException> { service.discard(command()) }

        verify(guard).requireBranchPermission(actor, organisationId, branchId, "branch.create")
        verify(store, never()).isHeadOffice(any(), any())
        assertNotTransitioned()
    }

    @Test
    fun `an organisation that is not active or provisioning refuses the discard`() {
        OrganisationLifecycleState.entries
            .filter {
                it !in
                    setOf(
                        OrganisationLifecycleState.ACTIVE,
                        OrganisationLifecycleState.PROVISIONING,
                    )
            }.forEach { state ->
                givenDraft(organisationState = state)
                assertFailsWith<ConflictException>("$state") { service.discard(command()) }
            }
        assertNotTransitioned()
    }

    @ParameterizedTest
    @EnumSource(
        value = OrganisationLifecycleState::class,
        names = ["ACTIVE", "PROVISIONING"],
    )
    fun `an active or provisioning organisation lets the discard through`(
        state: OrganisationLifecycleState,
    ) {
        givenDraft(organisationState = state)

        service.discard(command())

        verify(lifecycle).transition(any<BranchTransitionCommand>())
    }

    @Test
    fun `a missing organisation is not found`() {
        givenDraft(organisationState = null)

        assertFailsWith<ResourceNotFoundException> { service.discard(command()) }
        assertNotTransitioned()
    }

    @Test
    fun `the organisation row is locked before its state is read`() {
        givenDraft()

        service.discard(command())

        inOrder(store).apply {
            verify(store).lockOrganisation(organisationId)
            verify(store).organisationState(organisationId)
        }
    }

    @Test
    fun `the head office draft is refused with its own code and stays a draft`() {
        givenDraft(headOffice = true)

        val error = assertFailsWith<ConflictException> { service.discard(command()) }

        assertEquals(LifecycleErrorCodes.HEAD_OFFICE_BRANCH_PROTECTED, error.code)
        assertEquals(LifecycleErrorCodes.HEAD_OFFICE_BRANCH_PROTECTED_DETAIL, error.message)
        assertNotTransitioned()
    }

    @Test
    fun `the organisation state is checked before the head office`() {
        givenDraft(organisationState = OrganisationLifecycleState.SUSPENDED, headOffice = true)

        val error = assertFailsWith<ConflictException> { service.discard(command()) }

        assertEquals("conflict", error.code)
        verify(store, never()).isHeadOffice(any(), any())
    }

    @Test
    fun `a branch that is not a draft is the FSM conflict and leaves no audit row`() {
        givenDraft()
        doThrow(ConflictException())
            .whenever(lifecycle)
            .transition(any<BranchTransitionCommand>())

        assertFailsWith<ConflictException> { service.discard(command()) }

        verify(audit, never()).record(any())
    }

    private companion object {
        const val REASON = "Opened against the wrong organisation."
    }
}
