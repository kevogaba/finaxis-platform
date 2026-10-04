package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.whenever
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/** Rules of the branch update use case, against stubbed ports (issue #165). */
class BranchUpdateServiceTests {
    private val organisationId = uuidV7()
    private val branchId = uuidV7()
    private val actorId = uuidV7()
    private val store = mock(BranchLifecycleStore::class.java)
    private val audit = mock(AuditService::class.java)
    private val guard = mock(PermissionGuard::class.java)
    private val service =
        BranchProvisioningService(
            mock(FoundationLifecycleService::class.java),
            store,
            mock(BranchAssignmentStore::class.java),
            audit,
            mock(TransitionEventPublisher::class.java),
            guard,
        )

    private fun givenBranch(state: BranchLifecycleState = BranchLifecycleState.DRAFT) {
        whenever(store.branchState(organisationId, branchId)).thenReturn(state)
        whenever(store.updateBranch(any())).thenReturn(true)
        whenever(audit.record(any())).thenReturn(mock(AuditEvent::class.java))
    }

    private fun command(
        name: String? = "Riverside",
        changesParent: Boolean = false,
        parent: UUID? = null,
        timezone: String? = null,
        address: Map<String, String>? = null,
    ) = UpdateBranchCommand(
        organisationId,
        branchId,
        actorId,
        branchName = name,
        changesParent = changesParent,
        parentBranchId = parent,
        timezone = timezone,
        address = address,
    )

    @ParameterizedTest
    @EnumSource(value = BranchLifecycleState::class, names = ["DRAFT", "ACTIVE"])
    fun `updates a draft or active branch and audits it`(state: BranchLifecycleState) {
        givenBranch(state)

        service.update(command())

        verify(store).updateBranch(command())
        verify(guard).requireBranchPermission(actorId, organisationId, branchId, "branch.update")
    }

    @ParameterizedTest
    @EnumSource(
        value = BranchLifecycleState::class,
        names = ["PENDING_APPROVAL", "SUSPENDED", "CLOSED", "ARCHIVED"],
    )
    fun `refuses to update a branch in any other state`(state: BranchLifecycleState) {
        givenBranch(state)

        assertFailsWith<ConflictException> { service.update(command()) }

        verify(store, never()).updateBranch(any())
        verify(audit, never()).record(any())
    }

    @Test
    fun `checks the permission before it looks the branch up`() {
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requireBranchPermission(actorId, organisationId, branchId, "branch.update")

        assertFailsWith<ForbiddenOperationException> { service.update(command()) }

        verify(store, never()).branchState(any(), any())
        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `a branch create grant alone does not authorise an update`() {
        // The guard refuses everything but branch.create, as it would for a maker-only role: the
        // service must ask for branch.update, so the refusal reaches the caller (#203).
        givenBranch()
        doThrow(ForbiddenOperationException())
            .whenever(guard)
            .requireBranchPermission(actorId, organisationId, branchId, "branch.update")

        assertFailsWith<ForbiddenOperationException> { service.update(command()) }

        verify(guard, never())
            .requireBranchPermission(actorId, organisationId, branchId, "branch.create")
        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `reports a missing or foreign branch as not found after the permission check`() {
        whenever(store.branchState(organisationId, branchId)).thenReturn(null)

        assertFailsWith<ResourceNotFoundException> { service.update(command()) }

        verify(guard).requireBranchPermission(actorId, organisationId, branchId, "branch.update")
        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `refuses a command that changes nothing`() {
        givenBranch()

        assertFailsWith<InvalidOperationException> { service.update(command(name = null)) }

        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `refuses a blank name and a timezone that is not a zone id`() {
        givenBranch()

        assertFailsWith<InvalidOperationException> { service.update(command(name = "  ")) }
        assertFailsWith<InvalidOperationException> {
            service.update(command(name = null, timezone = "Mars/Olympus"))
        }
        assertFailsWith<InvalidOperationException> {
            service.update(command(name = null, timezone = "Africa/Nairobi'; DROP TABLE branch;--"))
        }

        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `refuses to make a branch its own parent`() {
        givenBranch()
        whenever(store.parentBelongsToOrganisation(organisationId, branchId)).thenReturn(true)

        assertFailsWith<InvalidOperationException> {
            service.update(command(name = null, changesParent = true, parent = branchId))
        }

        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `refuses a parent outside the organisation as not found`() {
        givenBranch()
        val foreign = uuidV7()
        whenever(store.parentBelongsToOrganisation(organisationId, foreign)).thenReturn(false)

        assertFailsWith<ResourceNotFoundException> {
            service.update(command(name = null, changesParent = true, parent = foreign))
        }

        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `refuses a parent that is a descendant of the branch`() {
        givenBranch()
        val child = uuidV7()
        val grandchild = uuidV7()
        whenever(store.parentBelongsToOrganisation(organisationId, grandchild)).thenReturn(true)
        whenever(store.parentBranchId(organisationId, grandchild)).thenReturn(child)
        whenever(store.parentBranchId(organisationId, child)).thenReturn(branchId)

        assertFailsWith<InvalidOperationException> {
            service.update(command(name = null, changesParent = true, parent = grandchild))
        }

        verify(store, never()).updateBranch(any())
    }

    @Test
    fun `moves a branch under an unrelated parent holding the hierarchy lock`() {
        givenBranch()
        val parent = uuidV7()
        val root = uuidV7()
        whenever(store.parentBelongsToOrganisation(organisationId, parent)).thenReturn(true)
        whenever(store.parentBranchId(organisationId, parent)).thenReturn(root)
        whenever(store.parentBranchId(organisationId, root)).thenReturn(null)
        whenever(store.claimOpenParent(organisationId, parent)).thenReturn(true)

        service.update(command(name = null, changesParent = true, parent = parent))

        verify(store).lockBranchHierarchy(organisationId)
        verify(store).updateBranch(command(name = null, changesParent = true, parent = parent))
    }

    @Test
    fun `terminates when the stored hierarchy above the new parent already loops`() {
        givenBranch()
        val a = uuidV7()
        val b = uuidV7()
        whenever(store.parentBelongsToOrganisation(organisationId, a)).thenReturn(true)
        whenever(store.parentBranchId(organisationId, a)).thenReturn(b)
        whenever(store.parentBranchId(organisationId, b)).thenReturn(a)
        whenever(store.claimOpenParent(organisationId, a)).thenReturn(true)

        service.update(command(name = null, changesParent = true, parent = a))

        verify(store).updateBranch(command(name = null, changesParent = true, parent = a))
    }

    @Test
    fun `refuses a closed or archived parent and moves nothing`() {
        givenBranch()
        val closed = uuidV7()
        whenever(store.parentBelongsToOrganisation(organisationId, closed)).thenReturn(true)
        whenever(store.parentBranchId(organisationId, closed)).thenReturn(null)
        whenever(store.claimOpenParent(organisationId, closed)).thenReturn(false)

        val refusal =
            assertFailsWith<ConflictException> {
                service.update(command(name = null, changesParent = true, parent = closed))
            }

        assertEquals("A closed or archived branch cannot be a parent.", refusal.safeDetail)
        verify(store, never()).updateBranch(any())
        verify(audit, never()).record(any())
    }

    @Test
    fun `claims the new parent before it writes the move`() {
        givenBranch()
        val parent = uuidV7()
        whenever(store.parentBelongsToOrganisation(organisationId, parent)).thenReturn(true)
        whenever(store.parentBranchId(organisationId, parent)).thenReturn(null)
        whenever(store.claimOpenParent(organisationId, parent)).thenReturn(true)

        service.update(command(name = null, changesParent = true, parent = parent))

        val order = org.mockito.Mockito.inOrder(store)
        order.verify(store).claimOpenParent(organisationId, parent)
        order.verify(store).updateBranch(any())
    }

    @Test
    fun `clears the parent without validating one`() {
        givenBranch()

        service.update(command(name = null, changesParent = true, parent = null))

        verify(store, never()).parentBelongsToOrganisation(any(), any())
        verify(store, never()).claimOpenParent(any(), any())
        verify(store).updateBranch(command(name = null, changesParent = true, parent = null))
    }

    @Test
    fun `reports a status change that raced the update as a conflict and audits nothing`() {
        givenBranch()
        whenever(store.updateBranch(any())).thenReturn(false)

        assertFailsWith<ConflictException> { service.update(command()) }

        verify(audit, never()).record(any())
    }

    @Test
    fun `audits the names of the changed fields and never the address`() {
        givenBranch()
        val captor = argumentCaptor<AuditCommand>()

        service.update(
            command(
                name = "Riverside",
                timezone = "Africa/Nairobi",
                address = mapOf("line1" to "12 Secret Lane"),
            ),
        )

        verify(audit).record(captor.capture())
        val recorded = captor.firstValue
        assertEquals("branch.update", recorded.action)
        assertEquals("BRANCH", recorded.resourceType)
        assertEquals(branchId.toString(), recorded.resourceId)
        assertEquals(organisationId.toString(), recorded.tenantId)
        assertEquals(actorId.toString(), recorded.actorId)
        assertEquals(AuditOutcome.SUCCESS, recorded.outcome)
        assertEquals("branch_name,timezone,address", recorded.metadata["changedFields"])
        assertFalse(recorded.toString().contains("Secret"), "address content must not be audited")
        assertFalse(recorded.toString().contains("Riverside"), "values must not be audited")
    }
}
