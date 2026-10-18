package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.mockito.Mockito.atLeast
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The tenant's switch for branch maker-checker (`require_maker_checker_for_branch_creation`).
 * With it off nobody is refused for having made or amended the branch, and an approval that
 * relied on that is audited as one `branch.self_approve` row, HIGH, beside the FSM's own row; an
 * approval that did not rely on it writes no such row. The switch is per tenant and independent
 * of the membership switch. With it on (the default) the refusals are unchanged
 * ([BranchCheckerRuleAuditTests]).
 */
class BranchMakerCheckerSwitchTests {
    private val lifecycleService = mock(FoundationLifecycleService::class.java)
    private val lifecycleStore = mock(BranchLifecycleStore::class.java)
    private val auditService = mock(AuditService::class.java)
    private val switches = MakerCheckerSwitches()
    private val branches =
        BranchProvisioningService(
            lifecycleService,
            lifecycleStore,
            mock(BranchAssignmentStore::class.java),
            auditService,
            mock(TransitionEventPublisher::class.java),
            mock(PermissionGuard::class.java),
            switches.policy,
        )
    private val organisationId = uuidV7()
    private val branchId = uuidV7()
    private val creator = uuidV7()
    private val submitter = uuidV7()
    private val amender = uuidV7()

    @BeforeTest
    fun pendingBranch() {
        whenever(lifecycleStore.branchState(organisationId, branchId))
            .thenReturn(BranchLifecycleState.PENDING_APPROVAL)
        whenever(lifecycleStore.organisationState(organisationId))
            .thenReturn(OrganisationLifecycleState.ACTIVE)
        whenever(lifecycleStore.createdBy(organisationId, branchId)).thenReturn(creator)
        whenever(lifecycleStore.submittedBy(organisationId, branchId)).thenReturn(submitter)
        whenever(lifecycleStore.hasAmended(organisationId, branchId, amender)).thenReturn(true)
    }

    @Test
    fun `with the switch off the creator approves and the approval is audited HIGH`() {
        switches.switchOffBranchCreation(organisationId)

        branches.activate(command(creator, ActingScope.TENANT))

        verify(lifecycleService).transition(any<BranchTransitionCommand>())
        val row = selfApprovals(1).single()
        assertEquals(creator.toString(), row.actorId)
        assertEquals("branch.self_approve", row.action)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(AuditOutcome.SUCCESS, row.outcome)
        assertEquals(branchId.toString(), row.branchId)
        assertEquals(organisationId.toString(), row.tenantId)
        verify(auditService, never()).recordIndependently(any())
    }

    @Test
    fun `with the switch off an amender approves and is audited`() {
        switches.switchOffBranchCreation(organisationId)

        branches.activate(command(amender, ActingScope.TENANT))

        assertEquals(listOf(amender.toString()), selfApprovals(1).map { it.actorId })
        verify(auditService, never()).recordIndependently(any())
    }

    @Test
    fun `the platform checker is held to the rule whatever the tenant switched`() {
        switches.switchOffBranchCreation(organisationId)

        listOf(creator, submitter).forEach { actor ->
            val refusal =
                assertFailsWith<ForbiddenOperationException> {
                    branches.activate(command(actor, ActingScope.PLATFORM))
                }
            assertEquals(LifecycleErrorCodes.APPROVER_IS_BRANCH_MAKER, refusal.code)
        }
        selfApprovals(0)
        verify(auditService, times(2)).recordIndependently(any())
    }

    @Test
    fun `with the switch off a checker who made and amended nothing writes no self approval`() {
        switches.switchOffBranchCreation(organisationId)

        branches.activate(command(uuidV7(), ActingScope.TENANT))

        verify(lifecycleService).transition(any<BranchTransitionCommand>())
        selfApprovals(0)
        verify(auditService, never()).recordIndependently(any())
    }

    @Test
    fun `the switch is per tenant`() {
        val other = uuidV7()
        switches.switchOffBranchCreation(other)

        assertFailsWith<ForbiddenOperationException> {
            branches.activate(command(creator, ActingScope.TENANT))
        }
        verify(auditService, times(1)).recordIndependently(any())
    }

    @Test
    fun `the membership switch does not loosen branch approval`() {
        switches.switchOffUserInvites(organisationId)

        val refusal =
            assertFailsWith<ForbiddenOperationException> {
                branches.activate(command(creator, ActingScope.TENANT))
            }

        assertEquals(LifecycleErrorCodes.APPROVER_IS_BRANCH_MAKER, refusal.code)
    }

    private fun command(
        actor: UUID,
        scope: ActingScope,
    ) = ActivateBranchCommand(
        organisationId = organisationId,
        branchId = branchId,
        actorId = actor,
        requestId = uuidV7(),
        scope = scope,
    )

    /** The `branch.self_approve` rows written so far, whatever other rows a route adds. */
    private fun selfApprovals(count: Int): List<AuditCommand> {
        val captor = argumentCaptor<AuditCommand>()
        verify(auditService, atLeast(0)).record(captor.capture())
        val rows = captor.allValues.filter { it.action == "branch.self_approve" }
        assertEquals(count, rows.size)
        return rows
    }
}
