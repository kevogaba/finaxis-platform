package com.finaxis.platform.lifecycle.adapter.inbound.web.dto

import com.finaxis.platform.lifecycle.application.BranchAssignmentType
import jakarta.validation.constraints.NotNull
import java.time.Instant
import java.util.UUID

/** Request payload for assigning a user to an operating branch. */
data class AssignBranchRequest(
    @field:NotNull
    val userId: UUID,
    @field:NotNull
    val branchId: UUID,
    @field:NotNull
    val assignmentType: BranchAssignmentType,
)

/** Detailed response for a user branch assignment. */
data class BranchAssignmentDetailResponse(
    val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: String,
    val status: String,
    val assignedAt: Instant,
    val assignedBy: UUID?,
    val revokedAt: Instant?,
    val revokedBy: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Summary response for a user branch assignment. */
data class BranchAssignmentSummaryResponse(
    val id: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: String,
    val status: String,
)
