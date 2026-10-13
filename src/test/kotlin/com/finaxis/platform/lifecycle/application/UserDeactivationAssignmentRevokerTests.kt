package com.finaxis.platform.lifecycle.application

import com.finaxis.platform.common.audit.AuditEvent
import com.finaxis.platform.common.audit.AuditEventRepository
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.transitions.TransitionEventPublisher
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull

class UserDeactivationAssignmentRevokerTests {
    private val reader = mock<FoundationLifecycleReader>()
    private val writer = mock<FoundationLifecycleWriter>()
    private val publisher = mock<TransitionEventPublisher>()
    private val repository = CapturingAuditRepository()
    private val clock = Clock.fixed(Instant.parse("2026-07-06T08:00:00Z"), ZoneOffset.UTC)
    private val revoker =
        UserDeactivationAssignmentRevoker(
            reader,
            writer,
            AuditService(repository, clock),
            publisher,
            clock,
        )

    @Test
    fun `a revoked branch assignment is audited against its branch, a tenant role against none`() {
        val organisationId = uuidV7()
        val userId = uuidV7()
        val actorId = uuidV7()
        val branchId = uuidV7()
        val branchAssignment = uuidV7()
        val tenantRoleAssignment = uuidV7()
        whenever(reader.findOrganisationIdsForActiveUserAccess(userId))
            .thenReturn(setOf(organisationId))
        whenever(writer.revokeActiveAssignments(organisationId, userId)).thenReturn(
            listOf(
                DeprovisionedAssignment(branchAssignment, "USER_BRANCH_ASSIGNMENT", branchId),
                DeprovisionedAssignment(tenantRoleAssignment, "USER_ROLE_ASSIGNMENT", null),
            ),
        )

        revoker.revoke(
            DeactivateUserCommand(organisationId, userId, actorId, Reason.required("left")),
        )

        val rows = repository.saved.associateBy { it.resourceId }
        assertEquals(branchId.toString(), rows.getValue(branchAssignment.toString()).branchId)
        assertNull(rows.getValue(tenantRoleAssignment.toString()).branchId)
    }
}

private class CapturingAuditRepository : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved += event
    }
}
