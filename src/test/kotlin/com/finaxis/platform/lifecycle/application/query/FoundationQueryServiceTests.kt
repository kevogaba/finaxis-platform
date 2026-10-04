package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.InvalidPageRequestException
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class FoundationQueryServiceTests {
    private val store = FakeFoundationQueryStore()
    private val permissionGuard = FakePermissionGuard()
    private val service = FoundationQueryService(store, permissionGuard)

    private val tenantId = UUID.randomUUID()
    private val actorId = UUID.randomUUID()

    @Test
    fun `getTenant rejects TenantCaller mismatch`() {
        val caller = TenantCaller(actorId, tenantId)
        assertFailsWith<ResourceNotFoundException> {
            service.getTenant(UUID.randomUUID(), caller)
        }
    }

    @Test
    fun `getTenant routes to requireTenantPermission for TenantCaller`() {
        val caller = TenantCaller(actorId, tenantId)
        permissionGuard.deny(tenantId, "tenant.view")

        assertFailsWith<SecurityException> {
            service.getTenant(tenantId, caller)
        }
    }

    @Test
    fun `getTenant routes to requirePlatformPermission for PlatformCaller`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val platformOrgId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        permissionGuard.deny(platformOrgId, "tenant.view")

        assertFailsWith<SecurityException> {
            service.getTenant(tenantId, caller)
        }
    }

    @Test
    fun `the post-mutation tenant read needs no permission and still reports a missing tenant`() {
        permissionGuard.deny(UUID.fromString("00000000-0000-0000-0000-000000000000"), "tenant.view")
        permissionGuard.deny(tenantId, "tenant.view")
        store.tenants[tenantId] = tenantDetail(tenantId)

        // A checker holding only tenant.reject reads back its own committed return.
        assertEquals(tenantId, service.getTenantAfterAuthorizedMutation(tenantId).id)
        assertFailsWith<ResourceNotFoundException> {
            service.getTenantAfterAuthorizedMutation(UUID.randomUUID())
        }
    }

    @Test
    fun `searchTenants validates page parameters`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        assertRejected(null) { service.searchTenants(TenantFilter(page = -1), caller) }
        assertRejected(null) { service.searchTenants(TenantFilter(size = 0), caller) }
        assertRejected(null) { service.searchTenants(TenantFilter(size = 101), caller) }
    }

    @Test
    fun `searchTenants validates sort fields`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        assertRejected("sort_by") {
            service.searchTenants(TenantFilter(sortBy = "invalid"), caller)
        }
        assertRejected("sort_dir") {
            service.searchTenants(TenantFilter(sortBy = "displayName", sortDir = "UP"), caller)
        }
    }

    @Test
    fun `searchTenants accepts valid paging and sorting unchanged`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        val filter = TenantFilter(page = 0, size = 100, sortBy = "displayName", sortDir = "desc")
        assertEquals(0, service.searchTenants(filter, caller).items.size)
    }

    @Test
    fun `searchBranches validates page and sort parameters`() {
        val caller = TenantCaller(actorId, tenantId)
        assertRejected(null) { service.searchBranches(tenantId, BranchFilter(page = -1), caller) }
        assertRejected(null) { service.searchBranches(tenantId, BranchFilter(size = 0), caller) }
        assertRejected(null) {
            service.searchBranches(tenantId, BranchFilter(size = 101), caller)
        }
        assertRejected("sort_by") {
            service.searchBranches(tenantId, BranchFilter(sortBy = "bogus"), caller)
        }
        assertRejected("sort_dir") {
            service.searchBranches(
                tenantId,
                BranchFilter(sortBy = "branchCode", sortDir = "sideways"),
                caller,
            )
        }
        assertEquals(
            0,
            service
                .searchBranches(
                    tenantId,
                    BranchFilter(size = 1, sortBy = "branchCode", sortDir = "asc"),
                    caller,
                ).items.size,
        )
    }

    @Test
    fun `listBusinessDateHistory validates page parameters`() {
        val caller = TenantCaller(actorId, tenantId)
        assertRejected(null) {
            service.listBusinessDateHistory(tenantId, BusinessDateHistoryFilter(page = -1), caller)
        }
        assertRejected(null) {
            service.listBusinessDateHistory(tenantId, BusinessDateHistoryFilter(size = 101), caller)
        }
    }

    private fun assertRejected(
        parameter: String?,
        block: () -> Unit,
    ) {
        val failure = assertFailsWith<InvalidPageRequestException>(block = block)
        assertEquals(parameter, failure.parameter)
    }

    @Test
    fun `getBranch enforces tenant scope for TenantCaller`() {
        val caller = TenantCaller(actorId, tenantId)
        assertFailsWith<ResourceNotFoundException> {
            service.getBranch(UUID.randomUUID(), UUID.randomUUID(), caller)
        }
    }
}

private class FakeFoundationQueryStore : FoundationQueryStore {
    override fun searchTenants(
        filter: TenantFilter,
        organisationId: UUID?,
    ): ApiPage<TenantSummary> = apiPageOf(emptyList(), 0, 25, 0)

    val tenants = mutableMapOf<UUID, TenantDetail>()

    override fun findTenantById(id: UUID): TenantDetail? = tenants[id]

    override fun searchBranches(
        organisationId: UUID,
        filter: BranchFilter,
    ): ApiPage<BranchSummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findBranchById(
        organisationId: UUID,
        id: UUID,
    ): BranchDetail? = null

    override fun listBusinessDateHistory(
        organisationId: UUID,
        filter: BusinessDateHistoryFilter,
    ): ApiPage<BusinessDateHistorySummary> = apiPageOf(emptyList(), 0, 25, 0)

    override fun findBusinessDateHistoryById(
        organisationId: UUID,
        id: UUID,
    ): BusinessDateHistoryDetail? = null
}

private fun tenantDetail(id: UUID) =
    TenantDetail(
        id = id,
        tenantCode = "acme-test",
        displayName = "Acme Test",
        countryCode = "KE",
        baseCurrencyCode = "KES",
        timezone = "Africa/Nairobi",
        status = "DRAFT",
        statusReason = "Registration number has a typo.",
        createdAt = Instant.parse("2026-07-18T10:00:00Z"),
        updatedAt = Instant.parse("2026-07-18T10:00:00Z"),
    )

private class FakePermissionGuard : PermissionGuard {
    private val denied = mutableSetOf<Pair<UUID, String>>()

    fun deny(
        orgId: UUID,
        permission: String,
    ) {
        denied.add(orgId to permission)
    }

    override fun requirePermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        if (denied.contains(organisationId to permissionCode)) {
            throw SecurityException("Missing permission: $permissionCode")
        }
    }

    override fun requireTenantPermission(
        actorId: UUID,
        organisationId: UUID,
        permissionCode: String,
    ) {
        requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requireBranchPermission(
        actorId: UUID,
        organisationId: UUID,
        branchId: UUID,
        permissionCode: String,
    ) {
        requirePermission(actorId, organisationId, permissionCode)
    }

    override fun requirePlatformPermission(
        actorId: UUID,
        permissionCode: String,
    ) {
        val platformOrgId = UUID.fromString("00000000-0000-0000-0000-000000000000")
        requirePermission(actorId, platformOrgId, permissionCode)
    }
}
