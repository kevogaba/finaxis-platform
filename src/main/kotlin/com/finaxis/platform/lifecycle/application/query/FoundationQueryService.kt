package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.lifecycle.FoundationCaller
import com.finaxis.platform.lifecycle.PermissionGuard
import com.finaxis.platform.lifecycle.PlatformCaller
import com.finaxis.platform.lifecycle.TenantCaller
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * Application service for executing lifecycle read-side queries. Enforces tenant scope boundaries,
 * evaluates permissions, and canonicalizes sorting and pagination parameters.
 */
@Service
class FoundationQueryService(
    private val store: FoundationQueryStore,
    private val permissionGuard: PermissionGuard,
) {
    /** Retrieves detailed tenant metadata by id, validating caller context and permissions. */
    fun getTenant(
        id: UUID,
        caller: FoundationCaller,
    ): TenantDetail {
        when (caller) {
            is TenantCaller -> {
                if (caller.activeOrganisationId != id) {
                    throw ResourceNotFoundException(safeDetail = "Tenant not found: $id")
                }
                permissionGuard.requireTenantPermission(caller.actorId, id, "tenant.view")
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(caller.actorId, "tenant.view")
            }
        }
        return store.findTenantById(id)
            ?: throw ResourceNotFoundException(safeDetail = "Tenant not found: $id")
    }

    /** Searches tenant summaries, restricting results to caller's tenant scope if restricted. */
    fun searchTenants(
        filter: TenantFilter,
        caller: FoundationCaller,
    ): ApiPage<TenantSummary> {
        validatePage(filter.page, filter.size)
        validateSort(filter.sortBy, filter.sortDir, allowedTenantSorts)
        val scopedFilter =
            when (caller) {
                is TenantCaller -> {
                    permissionGuard.requireTenantPermission(
                        caller.actorId,
                        caller.activeOrganisationId,
                        "tenant.view",
                    )
                    filter.copy(q = null, status = null, country = null)
                }

                is PlatformCaller -> {
                    permissionGuard.requirePlatformPermission(caller.actorId, "tenant.view")
                    filter
                }
            }
        val orgId = if (caller is TenantCaller) caller.activeOrganisationId else null
        return store.searchTenants(scopedFilter, orgId)
    }

    /** Retrieves detailed branch metadata by id, validating caller context and permissions. */
    fun getBranch(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): BranchDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "branch.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(caller.actorId, "branch.view")
            }
        }
        return store.findBranchById(organisationId, id)
            ?: throw ResourceNotFoundException(safeDetail = "Branch not found: $id")
    }

    /** Searches branches within an organisation, validating caller context and permissions. */
    fun searchBranches(
        organisationId: UUID,
        filter: BranchFilter,
        caller: FoundationCaller,
    ): ApiPage<BranchSummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        validateSort(filter.sortBy, filter.sortDir, allowedBranchSorts)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "branch.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(caller.actorId, "branch.view")
            }
        }
        return store.searchBranches(organisationId, filter)
    }

    /** Retrieves detailed business date history metadata by id, validating caller context. */
    fun getBusinessDateHistory(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): BusinessDateHistoryDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "business_date.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(caller.actorId, "business_date.view")
            }
        }
        return store.findBusinessDateHistoryById(organisationId, id)
            ?: throw ResourceNotFoundException(
                safeDetail = "Business date history record not found: $id",
            )
    }

    /** Lists business date history events for an organisation, validating caller context. */
    fun listBusinessDateHistory(
        organisationId: UUID,
        filter: BusinessDateHistoryFilter,
        caller: FoundationCaller,
    ): ApiPage<BusinessDateHistorySummary> {
        verifyTenantScope(organisationId, caller)
        validatePage(filter.page, filter.size)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireTenantPermission(
                    caller.actorId,
                    organisationId,
                    "business_date.view",
                )
            }

            is PlatformCaller -> {
                permissionGuard.requirePlatformPermission(caller.actorId, "business_date.view")
            }
        }
        return store.listBusinessDateHistory(organisationId, filter)
    }

    private fun verifyTenantScope(
        organisationId: UUID,
        caller: FoundationCaller,
    ) {
        if (caller is TenantCaller && caller.activeOrganisationId != organisationId) {
            throw ResourceNotFoundException(safeDetail = "Organisation not found: $organisationId")
        }
    }

    private fun validatePage(
        page: Int,
        size: Int,
    ) {
        require(page >= 0) { "Page number must not be negative" }
        require(size in MINIMUM_PAGE_SIZE..MAXIMUM_PAGE_SIZE) {
            "Page size must be between $MINIMUM_PAGE_SIZE and $MAXIMUM_PAGE_SIZE"
        }
    }

    private fun validateSort(
        sortBy: String?,
        sortDir: String?,
        allowedFields: Set<String>,
    ) {
        if (sortBy != null) {
            require(sortBy in allowedFields) { "Sorting by field '$sortBy' is not allowed" }
        }
        if (sortDir != null) {
            val dir = sortDir.uppercase()
            require(dir == "ASC" || dir == "DESC") { "Sort direction must be ASC or DESC" }
        }
    }

    private companion object {
        const val MINIMUM_PAGE_SIZE = 1
        const val MAXIMUM_PAGE_SIZE = 100
        val allowedTenantSorts = setOf("tenantCode", "displayName", "countryCode", "createdAt")
        val allowedBranchSorts =
            setOf("branchCode", "branchName", "branchType", "status", "createdAt")
    }
}
