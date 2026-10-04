package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.lifecycle.FoundationCaller
import java.time.Instant
import java.util.UUID

/** Read port used by lifecycle web adapters for IAM-owned membership and assignment projections. */
interface LifecycleIamReadService {
    /** Searches membership summaries in an organisation. */
    fun searchMemberships(
        organisationId: UUID,
        filter: LifecycleMembershipFilter,
        caller: FoundationCaller,
    ): ApiPage<LifecycleMembershipSummary>

    /** Retrieves a membership detail in an organisation. */
    fun getMembership(
        organisationId: UUID,
        membershipId: UUID,
        caller: FoundationCaller,
    ): LifecycleMembershipDetail

    /**
     * Retrieves a membership detail without a permission check, for the response of a mutation
     * the caller was already authorised to make on that membership. Still bound to
     * [organisationId].
     */
    fun getMembershipAfterAuthorizedMutation(
        organisationId: UUID,
        membershipId: UUID,
    ): LifecycleMembershipDetail

    /**
     * Searches branch-assignment summaries in an organisation, limited to the branches the caller
     * holds `branch_assignment.view` on (ADR 0030, decision 5).
     */
    fun searchBranchAssignments(
        organisationId: UUID,
        filter: LifecycleBranchAssignmentFilter,
        caller: FoundationCaller,
    ): ApiPage<LifecycleBranchAssignmentSummary>

    /**
     * Retrieves a branch-assignment detail in an organisation, authorised at the assignment's
     * branch: 404 for an unknown id to a tenant-wide holder, 403 to a branch-scoped one.
     */
    fun getBranchAssignment(
        organisationId: UUID,
        assignmentId: UUID,
        caller: FoundationCaller,
    ): LifecycleBranchAssignmentDetail
}

/** Filter parameters for membership queries exposed to lifecycle adapters. */
data class LifecycleMembershipFilter(
    val q: String? = null,
    val membershipStatus: String? = null,
    val membershipType: String? = null,
    val page: Int = 0,
    val size: Int = 25,
)

/** Filter parameters for branch-assignment queries exposed to lifecycle adapters. */
data class LifecycleBranchAssignmentFilter(
    val branchId: UUID? = null,
    val assignmentType: String? = null,
    val status: String? = null,
    val page: Int = 0,
    val size: Int = 25,
    /**
     * The caller's selected branch: the default for an unfiltered list when the caller may view it
     * (every tenant caller alike); never a gate.
     */
    val pinnedBranchId: UUID? = null,
)

/** Membership summary projection exposed to lifecycle adapters. */
data class LifecycleMembershipSummary(
    val id: UUID,
    val userId: UUID,
    val membershipStatus: String,
    val membershipType: String,
    val primaryBranchId: UUID?,
)

/** Membership detail projection exposed to lifecycle adapters. */
data class LifecycleMembershipDetail(
    val id: UUID,
    val organisationId: UUID,
    val userId: UUID,
    val username: String,
    val email: String,
    val displayName: String,
    val userStatus: String,
    val membershipStatus: String,
    val membershipType: String,
    val primaryBranchId: UUID?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** Branch-assignment summary projection exposed to lifecycle adapters. */
data class LifecycleBranchAssignmentSummary(
    val id: UUID,
    val userId: UUID,
    val branchId: UUID,
    val assignmentType: String,
    val status: String,
)

/** Branch-assignment detail projection exposed to lifecycle adapters. */
data class LifecycleBranchAssignmentDetail(
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
