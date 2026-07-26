package com.finaxis.platform.iam.adapter.outbound.query

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.BranchAssignmentSummary
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.MembershipSummary
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentFilter
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentSummary
import com.finaxis.platform.lifecycle.application.query.LifecycleIamReadService
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipFilter
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipSummary
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * IAM implementation of the lifecycle read port for membership and branch-assignment projections.
 */
@Service
class IamLifecycleReadAdapter(
    private val iamQueryService: IamQueryService,
) : LifecycleIamReadService {
    override fun searchMemberships(
        organisationId: UUID,
        filter: LifecycleMembershipFilter,
        caller: FoundationCaller,
    ): ApiPage<LifecycleMembershipSummary> =
        iamQueryService
            .searchMemberships(
                organisationId,
                MembershipFilter(
                    q = filter.q,
                    membershipStatus = filter.membershipStatus,
                    membershipType = filter.membershipType,
                    page = filter.page,
                    size = filter.size,
                ),
                caller,
            ).let { page -> ApiPage(page.items.map { it.toLifecycle() }, page.page) }

    override fun getMembership(
        organisationId: UUID,
        membershipId: UUID,
        caller: FoundationCaller,
    ): LifecycleMembershipDetail =
        iamQueryService.getMembership(organisationId, membershipId, caller).let {
            LifecycleMembershipDetail(
                it.id,
                it.organisationId,
                it.userId,
                it.username,
                it.email,
                it.displayName,
                it.userStatus,
                it.membershipStatus,
                it.membershipType,
                it.primaryBranchId,
                it.createdAt,
                it.updatedAt,
            )
        }

    override fun searchBranchAssignments(
        organisationId: UUID,
        filter: LifecycleBranchAssignmentFilter,
        caller: FoundationCaller,
    ): ApiPage<LifecycleBranchAssignmentSummary> =
        iamQueryService
            .searchBranchAssignments(
                organisationId,
                BranchAssignmentFilter(
                    filter.branchId,
                    filter.assignmentType,
                    filter.status,
                    filter.page,
                    filter.size,
                ),
                caller,
            ).let { page -> ApiPage(page.items.map { it.toLifecycle() }, page.page) }

    override fun getBranchAssignment(
        organisationId: UUID,
        assignmentId: UUID,
        caller: FoundationCaller,
    ): LifecycleBranchAssignmentDetail =
        iamQueryService.getBranchAssignment(organisationId, assignmentId, caller).let {
            LifecycleBranchAssignmentDetail(
                it.id,
                it.organisationId,
                it.userId,
                it.branchId,
                it.assignmentType,
                it.status,
                it.assignedAt,
                it.assignedBy,
                it.revokedAt,
                it.revokedBy,
                it.createdAt,
                it.updatedAt,
            )
        }

    private fun MembershipSummary.toLifecycle() =
        LifecycleMembershipSummary(id, userId, membershipStatus, membershipType, primaryBranchId)

    private fun BranchAssignmentSummary.toLifecycle() =
        LifecycleBranchAssignmentSummary(id, userId, branchId, assignmentType, status)
}
