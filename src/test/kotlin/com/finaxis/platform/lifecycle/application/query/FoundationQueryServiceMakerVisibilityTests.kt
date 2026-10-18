package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.ActorRef
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Who is shown as the maker of a branch or a tenant (#156). A tenant caller sees a branch's maker
 * only when they belong to the tenant, and never a tenant's requester or submitter, who are
 * platform users; a platform caller sees every maker.
 */
class FoundationQueryServiceMakerVisibilityTests {
    private val store = FakeFoundationQueryStore()
    private val service = FoundationQueryService(store, FakePermissionGuard())

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()
    private val branchId = UUID.randomUUID()
    private val member = ActorRef(UUID.randomUUID(), "Mary Member", member = true)
    private val operator = ActorRef(UUID.randomUUID(), "Platform Operator", member = false)

    private val tenantCaller = TenantCaller(actorId, tenantId)
    private val platformCaller =
        PlatformCaller(actorId, UUID.fromString("00000000-0000-0000-0000-000000000000"))

    private fun stock(
        creator: ActorRef?,
        submitter: ActorRef?,
    ) {
        store.branchSummaries = listOf(branchSummary(creator, submitter))
        store.branches[branchId] = branchDetail(creator, submitter)
        store.tenantSummaries = listOf(tenantSummary(creator, submitter))
        store.tenants[tenantId] =
            tenantDetail(tenantId).copy(requestedBy = creator, submittedBy = submitter)
    }

    /** `[creator, submitter]` of a branch as the list and the detail show them to [caller]. */
    private fun branchMakers(caller: FoundationCaller): List<List<ActorRef?>> =
        listOf(
            service.searchBranches(tenantId, BranchFilter(), caller).items.single().let {
                listOf(it.createdBy, it.submittedBy)
            },
            service
                .getBranch(
                    tenantId,
                    branchId,
                    caller,
                ).let { listOf(it.createdBy, it.submittedBy) },
        )

    /** `[requester, submitter]` of the tenant as the list and the detail show them to [caller]. */
    private fun tenantMakers(caller: FoundationCaller): List<List<ActorRef?>> =
        listOf(
            service.searchTenants(TenantFilter(), caller).items.single().let {
                listOf(it.requestedBy, it.submittedBy)
            },
            service.getTenant(tenantId, caller).let { listOf(it.requestedBy, it.submittedBy) },
        )

    @Test
    fun `a tenant caller sees branch makers who are members and not the operator`() {
        stock(creator = member, submitter = operator)

        assertEquals(List(2) { listOf(member, null) }, branchMakers(tenantCaller))
    }

    @Test
    fun `a platform caller sees every branch maker`() {
        stock(creator = operator, submitter = member)

        assertEquals(List(2) { listOf(operator, member) }, branchMakers(platformCaller))
    }

    @Test
    fun `a tenant caller never sees the platform users who requested and submitted the tenant`() {
        stock(creator = operator, submitter = operator)

        assertEquals(List(2) { listOf(null, null) }, tenantMakers(tenantCaller))
    }

    @Test
    fun `a platform caller sees the tenant requester and submitter`() {
        stock(creator = operator, submitter = ActorRef(UUID.randomUUID(), "Second Op", false))

        val (list, detail) = tenantMakers(platformCaller)

        assertEquals(operator, list[0])
        assertEquals("Second Op", list[1]?.displayName)
        assertEquals(list, detail)
    }

    private fun branchSummary(
        creator: ActorRef?,
        submitter: ActorRef?,
    ) = BranchSummary(
        id = branchId,
        organisationId = tenantId,
        branchCode = "B-1",
        branchName = "Branch",
        branchType = "BRANCH",
        status = "ACTIVE",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        parentBranchId = null,
        timezone = "Africa/Nairobi",
        openedOn = null,
        closedOn = null,
        activeUserCount = 1,
        createdBy = creator,
        submittedBy = submitter,
    )

    private fun branchDetail(
        creator: ActorRef?,
        submitter: ActorRef?,
    ) = BranchDetail(
        id = branchId,
        organisationId = tenantId,
        branchCode = "B-1",
        branchName = "Branch",
        branchType = "BRANCH",
        parentBranchId = null,
        status = "ACTIVE",
        timezone = "Africa/Nairobi",
        addressJson = "{}",
        openedOn = null,
        closedOn = null,
        statusReason = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        createdBy = creator,
        submittedBy = submitter,
    )

    private fun tenantSummary(
        requester: ActorRef?,
        submitter: ActorRef?,
    ) = TenantSummary(
        id = tenantId,
        tenantCode = "acme",
        displayName = "Acme",
        countryCode = "KE",
        status = "ACTIVE",
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        baseCurrencyCode = "KES",
        timezone = "Africa/Nairobi",
        bootstrapStatus = null,
        activeBranchCount = 1,
        activeUserCount = 1,
        requestedBy = requester,
        submittedBy = submitter,
    )
}
