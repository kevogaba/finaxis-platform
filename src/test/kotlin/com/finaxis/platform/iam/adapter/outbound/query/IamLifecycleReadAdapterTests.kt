package com.finaxis.platform.iam.adapter.outbound.query

import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.ApiPageMetadata
import com.finaxis.platform.iam.application.query.BranchAssignmentDetail
import com.finaxis.platform.iam.application.query.BranchAssignmentFilter
import com.finaxis.platform.iam.application.query.BranchAssignmentSummary
import com.finaxis.platform.iam.application.query.IamQueryService
import com.finaxis.platform.iam.application.query.MembershipDetail
import com.finaxis.platform.iam.application.query.MembershipFilter
import com.finaxis.platform.iam.application.query.MembershipSummary
import com.finaxis.platform.lifecycle.TenantCaller
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentFilter
import com.finaxis.platform.lifecycle.application.query.LifecycleBranchAssignmentSummary
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipDetail
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipFilter
import com.finaxis.platform.lifecycle.application.query.LifecycleMembershipSummary
import org.junit.jupiter.api.Test
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertSame

class IamLifecycleReadAdapterTests {
    private val iamQueryService = mock<IamQueryService>()
    private val adapter = IamLifecycleReadAdapter(iamQueryService)

    private val organisationId = UUID.fromString("10000000-0000-0000-0000-000000000001")
    private val actorId = UUID.fromString("20000000-0000-0000-0000-000000000002")
    private val activeBranchId = UUID.fromString("30000000-0000-0000-0000-000000000003")
    private val caller = TenantCaller(actorId, organisationId, activeBranchId)

    @Test
    fun `searchMemberships delegates with mapped filter and maps response page`() {
        val primaryBranchId = UUID.fromString("40000000-0000-0000-0000-000000000004")
        val membershipSummary = membershipSummary(primaryBranchId)
        val lifecycleFilter =
            LifecycleMembershipFilter(
                q = "anita@example.test",
                membershipStatus = "PENDING_APPROVAL",
                membershipType = "OPERATIONS_LEAD",
                page = 3,
                size = 17,
                sortBy = "membershipType",
                sortDir = "ASC",
                userId = USER_ID,
            )
        val iamFilter =
            MembershipFilter(
                q = "anita@example.test",
                membershipStatus = "PENDING_APPROVAL",
                membershipType = "OPERATIONS_LEAD",
                page = 3,
                size = 17,
                sortBy = "membershipType",
                sortDir = "ASC",
                userId = USER_ID,
            )
        val pageMetadata =
            ApiPageMetadata(
                number = 3,
                size = 17,
                totalItems = 49,
                totalPages = 4,
                hasNext = false,
                hasPrevious = true,
            )
        whenever(
            iamQueryService.searchMemberships(eq(organisationId), eq(iamFilter), eq(caller)),
        ).thenReturn(ApiPage(listOf(membershipSummary), pageMetadata))

        val result = adapter.searchMemberships(organisationId, lifecycleFilter, caller)

        verify(iamQueryService).searchMemberships(eq(organisationId), eq(iamFilter), eq(caller))
        assertEquals(
            listOf(lifecycleMembershipSummary(membershipSummary)),
            result.items,
        )
        assertSame(pageMetadata, result.page)
    }

    @Test
    fun `getMembership delegates to iam query service and maps detail`() {
        val membershipId = UUID.fromString("70000000-0000-0000-0000-000000000007")
        val primaryBranchId = UUID.fromString("80000000-0000-0000-0000-000000000008")
        val createdAt = Instant.parse("2026-03-04T05:06:07Z")
        val updatedAt = Instant.parse("2026-04-05T06:07:08Z")
        val membershipDetail =
            MembershipDetail(
                id = membershipId,
                organisationId = organisationId,
                userId = UUID.fromString("90000000-0000-0000-0000-000000000009"),
                username = "anita.ops",
                email = "anita.ops@example.test",
                displayName = "Anita Operations",
                userStatus = "INVITED",
                membershipStatus = "AWAITING_REVIEW",
                membershipType = "BRANCH_MANAGER",
                primaryBranchId = primaryBranchId,
                createdAt = createdAt,
                updatedAt = updatedAt,
            )
        whenever(
            iamQueryService.getMembership(eq(organisationId), eq(membershipId), eq(caller)),
        ).thenReturn(membershipDetail)

        val result = adapter.getMembership(organisationId, membershipId, caller)

        verify(iamQueryService).getMembership(eq(organisationId), eq(membershipId), eq(caller))
        assertEquals(
            LifecycleMembershipDetail(
                id = membershipId,
                organisationId = organisationId,
                userId = membershipDetail.userId,
                username = "anita.ops",
                email = "anita.ops@example.test",
                displayName = "Anita Operations",
                userStatus = "INVITED",
                membershipStatus = "AWAITING_REVIEW",
                membershipType = "BRANCH_MANAGER",
                primaryBranchId = primaryBranchId,
                createdAt = createdAt,
                updatedAt = updatedAt,
            ),
            result,
        )
    }

    @Test
    fun `searchBranchAssignments delegates with mapped filter and maps response page`() {
        val branchId = UUID.fromString("a0000000-0000-0000-0000-00000000000a")
        val assignmentSummary = assignmentSummary(branchId)
        val lifecycleFilter =
            LifecycleBranchAssignmentFilter(
                branchId = branchId,
                assignmentType = "CASH_VAULT_REVIEWER",
                status = "REVOKED",
                page = 2,
                size = 11,
                sortBy = "assignedAt",
                sortDir = "DESC",
                userId = USER_ID,
            )
        val iamFilter =
            BranchAssignmentFilter(
                branchId = branchId,
                assignmentType = "CASH_VAULT_REVIEWER",
                status = "REVOKED",
                page = 2,
                size = 11,
                sortBy = "assignedAt",
                sortDir = "DESC",
                userId = USER_ID,
            )
        val pageMetadata =
            ApiPageMetadata(
                number = 2,
                size = 11,
                totalItems = 32,
                totalPages = 3,
                hasNext = false,
                hasPrevious = true,
            )
        whenever(
            iamQueryService.searchBranchAssignments(eq(organisationId), eq(iamFilter), eq(caller)),
        ).thenReturn(ApiPage(listOf(assignmentSummary), pageMetadata))

        val result = adapter.searchBranchAssignments(organisationId, lifecycleFilter, caller)

        verify(iamQueryService)
            .searchBranchAssignments(eq(organisationId), eq(iamFilter), eq(caller))
        assertEquals(
            listOf(lifecycleAssignmentSummary(assignmentSummary)),
            result.items,
        )
        assertSame(pageMetadata, result.page)
    }

    @Test
    fun `getBranchAssignment delegates to iam query service and maps detail`() {
        val assignmentId = UUID.fromString("d0000000-0000-0000-0000-00000000000d")
        val branchId = UUID.fromString("e0000000-0000-0000-0000-00000000000e")
        val assignedBy = UUID.fromString("f0000000-0000-0000-0000-00000000000f")
        val revokedBy = UUID.fromString("11000000-0000-0000-0000-000000000011")
        val assignedAt = Instant.parse("2026-05-06T07:08:09Z")
        val revokedAt = Instant.parse("2026-06-07T08:09:10Z")
        val createdAt = Instant.parse("2026-07-08T09:10:11Z")
        val updatedAt = Instant.parse("2026-08-09T10:11:12Z")
        val assignmentDetail =
            BranchAssignmentDetail(
                id = assignmentId,
                organisationId = organisationId,
                userId = UUID.fromString("12000000-0000-0000-0000-000000000012"),
                branchId = branchId,
                assignmentType = "HEAD_OFFICE_SIGNATORY",
                status = "SUSPENDED",
                assignedAt = assignedAt,
                assignedBy = assignedBy,
                revokedAt = revokedAt,
                revokedBy = revokedBy,
                createdAt = createdAt,
                updatedAt = updatedAt,
                username = "anita",
                displayName = "Anita Wanjiru",
                branchName = "Westlands",
            )
        whenever(
            iamQueryService.getBranchAssignment(eq(organisationId), eq(assignmentId), eq(caller)),
        ).thenReturn(assignmentDetail)

        val result = adapter.getBranchAssignment(organisationId, assignmentId, caller)

        verify(iamQueryService)
            .getBranchAssignment(eq(organisationId), eq(assignmentId), eq(caller))
        assertEquals(
            LifecycleBranchAssignmentDetail(
                id = assignmentId,
                organisationId = organisationId,
                userId = assignmentDetail.userId,
                branchId = branchId,
                assignmentType = "HEAD_OFFICE_SIGNATORY",
                status = "SUSPENDED",
                assignedAt = assignedAt,
                assignedBy = assignedBy,
                revokedAt = revokedAt,
                revokedBy = revokedBy,
                createdAt = createdAt,
                updatedAt = updatedAt,
                username = "anita",
                displayName = "Anita Wanjiru",
                branchName = "Westlands",
            ),
            result,
        )
    }

    private fun membershipSummary(primaryBranchId: UUID) =
        MembershipSummary(
            id = UUID.fromString("50000000-0000-0000-0000-000000000005"),
            userId = UUID.fromString("60000000-0000-0000-0000-000000000006"),
            membershipStatus = "PENDING_APPROVAL",
            membershipType = "OPERATIONS_LEAD",
            primaryBranchId = primaryBranchId,
            username = "anita",
            email = "anita@example.test",
            displayName = "Anita Wanjiru",
            primaryBranchName = "Westlands",
            createdAt = SUMMARY_CREATED_AT,
        )

    /** The lifecycle projection the adapter must map [source] to, field by field. */
    private fun lifecycleMembershipSummary(source: MembershipSummary) =
        LifecycleMembershipSummary(
            id = source.id,
            userId = source.userId,
            membershipStatus = "PENDING_APPROVAL",
            membershipType = "OPERATIONS_LEAD",
            primaryBranchId = source.primaryBranchId,
            username = "anita",
            email = "anita@example.test",
            displayName = "Anita Wanjiru",
            primaryBranchName = "Westlands",
            createdAt = SUMMARY_CREATED_AT,
        )

    private fun assignmentSummary(branchId: UUID) =
        BranchAssignmentSummary(
            id = UUID.fromString("b0000000-0000-0000-0000-00000000000b"),
            userId = UUID.fromString("c0000000-0000-0000-0000-00000000000c"),
            branchId = branchId,
            assignmentType = "CASH_VAULT_REVIEWER",
            status = "REVOKED",
            username = "anita",
            displayName = "Anita Wanjiru",
            branchName = "Westlands",
            assignedAt = SUMMARY_CREATED_AT,
        )

    /** The lifecycle projection the adapter must map [source] to, field by field. */
    private fun lifecycleAssignmentSummary(source: BranchAssignmentSummary) =
        LifecycleBranchAssignmentSummary(
            id = source.id,
            userId = source.userId,
            branchId = source.branchId,
            assignmentType = "CASH_VAULT_REVIEWER",
            status = "REVOKED",
            username = "anita",
            displayName = "Anita Wanjiru",
            branchName = "Westlands",
            assignedAt = SUMMARY_CREATED_AT,
        )

    private companion object {
        val USER_ID: UUID = UUID.fromString("13000000-0000-0000-0000-000000000013")
        val SUMMARY_CREATED_AT: Instant = Instant.parse("2026-03-04T05:06:07Z")
    }
}
