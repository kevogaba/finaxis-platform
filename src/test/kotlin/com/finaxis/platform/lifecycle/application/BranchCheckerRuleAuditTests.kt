package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.MissingPermissionException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The branch maker-checker refusals of [BranchProvisioningService.activate] (#251, mirroring the
 * tenant rule of #221/#245): the creator, on the platform route the latest submitter, and anyone
 * who amended the branch are refused with their unchanged 403, and each refusal is first recorded
 * as one `DENIED`, `HIGH` `branch.activate` audit row through [AuditService.recordIndependently]
 * (a new transaction, so the 403's rollback cannot take it), never through [AuditService.record].
 * A refusal that comes earlier (permission, 404, the platform window) writes no such row, and an
 * accepted checker writes none.
 */
class BranchCheckerRuleAuditTests {
    private val lifecycleService = mock(FoundationLifecycleService::class.java)
    private val lifecycleStore = mock(BranchLifecycleStore::class.java)
    private val assignmentStore = mock(BranchAssignmentStore::class.java)
    private val auditService = mock(AuditService::class.java)
    private val eventPublisher = mock(TransitionEventPublisher::class.java)
    private val permissionGuard = mock(PermissionGuard::class.java)
    private val branches =
        BranchProvisioningService(
            lifecycleService,
            lifecycleStore,
            assignmentStore,
            auditService,
            eventPublisher,
            permissionGuard,
        )
    private val organisationId = uuidV7()
    private val branchId = uuidV7()
    private val creator = uuidV7()
    private val submitter = uuidV7()

    @BeforeTest
    fun pendingBranch() {
        whenever(lifecycleStore.branchState(organisationId, branchId))
            .thenReturn(BranchLifecycleState.PENDING_APPROVAL)
        whenever(lifecycleStore.organisationState(organisationId))
            .thenReturn(OrganisationLifecycleState.ACTIVE)
        whenever(lifecycleStore.createdBy(organisationId, branchId)).thenReturn(creator)
        whenever(lifecycleStore.submittedBy(organisationId, branchId)).thenReturn(submitter)
    }

    @Test
    fun `the creator refused on either route is audited as denied before the plain 403`() {
        ActingScope.entries.forEach { scope ->
            val refusal =
                assertFailsWith<ForbiddenOperationException> {
                    branches.activate(command(creator, scope))
                }
            assertEquals("forbidden", refusal.code)
        }

        val rows = deniedRows(2)
        rows.forEach { assertDenied(it, creator, "forbidden") }
        verifyNothingWritten()
    }

    @Test
    fun `the platform submitter is refused and audited but a tenant submitter is not`() {
        val refusal =
            assertFailsWith<ForbiddenOperationException> {
                branches.activate(command(submitter, ActingScope.PLATFORM))
            }
        assertEquals("forbidden", refusal.code)
        assertDenied(deniedRows(1).single(), submitter, "forbidden")

        branches.activate(command(submitter, ActingScope.TENANT))
        // Still the one row: the accepted tenant submitter wrote no DENIED row.
        deniedRows(1)
    }

    @Test
    fun `an amender refused on either route is audited with the modifier code`() {
        val amender = uuidV7()
        whenever(lifecycleStore.hasAmended(organisationId, branchId, amender)).thenReturn(true)

        ActingScope.entries.forEach { scope ->
            val refusal =
                assertFailsWith<ForbiddenOperationException> {
                    branches.activate(command(amender, scope))
                }
            assertEquals(LifecycleErrorCodes.APPROVER_IS_BRANCH_MODIFIER, refusal.code)
            assertEquals(LifecycleErrorCodes.APPROVER_IS_BRANCH_MODIFIER_DETAIL, refusal.safeDetail)
        }

        deniedRows(2).forEach {
            assertDenied(it, amender, LifecycleErrorCodes.APPROVER_IS_BRANCH_MODIFIER)
        }
        verifyNothingWritten()
    }

    @Test
    fun `a creator who also amended is audited once with the plain code`() {
        whenever(lifecycleStore.hasAmended(organisationId, branchId, creator)).thenReturn(true)

        assertFailsWith<ForbiddenOperationException> {
            branches.activate(command(creator, ActingScope.TENANT))
        }

        assertDenied(deniedRows(1).single(), creator, "forbidden")
    }

    @Test
    fun `a checker who neither made nor amended the branch writes no denied row`() {
        branches.activate(command(uuidV7(), ActingScope.TENANT))
        branches.activate(command(uuidV7(), ActingScope.PLATFORM))

        verify(auditService, never()).recordIndependently(any())
    }

    @Test
    fun `refusals that precede the maker-checker rule write no denied row`() {
        doThrow(MissingPermissionException("branch.approve"))
            .whenever(permissionGuard)
            .requireBranchPermission(creator, organisationId, branchId, "branch.approve")
        assertFailsWith<MissingPermissionException> {
            branches.activate(command(creator, ActingScope.TENANT))
        }

        whenever(lifecycleStore.hasActiveBranchBeyondBootstrap(organisationId)).thenReturn(true)
        assertFailsWith<ConflictException> {
            branches.activate(command(creator, ActingScope.PLATFORM))
        }

        verify(auditService, never()).recordIndependently(any())
        verify(auditService, never()).record(any())
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

    /** Captures exactly [count] independent audit rows; anything else fails the verify. */
    private fun deniedRows(count: Int): List<AuditCommand> {
        val captor = argumentCaptor<AuditCommand>()
        verify(auditService, times(count)).recordIndependently(captor.capture())
        return captor.allValues
    }

    private fun assertDenied(
        row: AuditCommand,
        actor: UUID,
        reason: String,
    ) {
        assertEquals("USER", row.actorType)
        assertEquals(actor.toString(), row.actorId)
        assertEquals(organisationId.toString(), row.tenantId)
        assertEquals("branch.activate", row.action)
        assertEquals("BRANCH", row.resourceType)
        assertEquals(branchId.toString(), row.resourceId)
        // The target branch, explicitly: left null, the adapter would fall back to the caller's
        // pinned branch (or none on the platform route).
        assertEquals(branchId.toString(), row.branchId)
        assertEquals(AuditOutcome.DENIED, row.outcome)
        assertEquals(AuditSeverity.HIGH, row.severity)
        assertEquals(reason, row.reason)
    }

    /** A refusal transitions nothing and writes no row inside the doomed transaction. */
    private fun verifyNothingWritten() {
        verify(lifecycleService, never()).transition(any<BranchTransitionCommand>())
        verify(auditService, never()).record(any())
    }
}
