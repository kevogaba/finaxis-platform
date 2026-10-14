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
                DeprovisionedAssignment(
                    branchAssignment,
                    "USER_BRANCH_ASSIGNMENT",
                    branchId,
                    userId,
                ),
                DeprovisionedAssignment(tenantRoleAssignment, "USER_ROLE_ASSIGNMENT", null, userId),
            ),
        )

        revoker.revoke(
            DeactivateUserCommand(organisationId, userId, actorId, Reason.required("left")),
        )

        val rows = repository.saved.associateBy { it.resourceId }
        assertEquals(branchId.toString(), rows.getValue(branchAssignment.toString()).branchId)
        assertNull(rows.getValue(tenantRoleAssignment.toString()).branchId)
    }

    @Test
    fun `a revoked assignment is about the deactivated user, not the administrator`() {
        val organisationId = uuidV7()
        val userId = uuidV7()
        val actorId = uuidV7()
        whenever(reader.findOrganisationIdsForActiveUserAccess(userId))
            .thenReturn(setOf(organisationId))
        whenever(writer.revokeActiveAssignments(organisationId, userId)).thenReturn(
            listOf(
                DeprovisionedAssignment(uuidV7(), "USER_BRANCH_ASSIGNMENT", uuidV7(), userId),
                DeprovisionedAssignment(uuidV7(), "USER_ROLE_ASSIGNMENT", null, userId),
            ),
        )

        revoker.revoke(
            DeactivateUserCommand(organisationId, userId, actorId, Reason.required("left")),
        )

        assertEquals(
            listOf(userId.toString(), userId.toString()),
            repository.saved.map { it.subjectUserId },
        )
        assertEquals(actorId.toString(), repository.saved.first().actorId)
    }
}

private class CapturingAuditRepository : AuditEventRepository {
    val saved = mutableListOf<AuditEvent>()

    override fun save(event: AuditEvent) {
        saved += event
    }
}
