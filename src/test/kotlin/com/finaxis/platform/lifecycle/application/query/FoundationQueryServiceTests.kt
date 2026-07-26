package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.apiPageOf
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.junit.jupiter.api.Test
import java.util.UUID
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
    fun `searchTenants validates page parameters`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> {
            service.searchTenants(TenantFilter(page = -1), caller)
        }
        assertFailsWith<IllegalArgumentException> {
            service.searchTenants(TenantFilter(size = 0), caller)
        }
        assertFailsWith<IllegalArgumentException> {
            service.searchTenants(TenantFilter(size = 101), caller)
        }
    }

    @Test
    fun `searchTenants validates sort fields`() {
        val caller = PlatformCaller(actorId, UUID.randomUUID())
        assertFailsWith<IllegalArgumentException> {
            service.searchTenants(TenantFilter(sortBy = "invalid"), caller)
        }
        assertFailsWith<IllegalArgumentException> {
            service.searchTenants(TenantFilter(sortBy = "displayName", sortDir = "UP"), caller)
        }
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

    override fun findTenantById(id: UUID): TenantDetail? = null

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
