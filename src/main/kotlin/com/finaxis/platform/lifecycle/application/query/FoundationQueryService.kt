package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.application.GatedRead
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.api.ApiPage
import com.finaxis.platform.common.web.api.requireValidPage
import com.finaxis.platform.common.web.api.requireValidSort
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
    /**
     * Retrieves detailed tenant metadata by id, validating caller context and permissions. The
     * initial-administrator bootstrap status is part of the detail, read with the tenant row, so
     * a web adapter never reaches the bootstrap store itself. It is also the read-back of every
     * platform tenant mutation, at `tenant.view` in the platform organisation.
     */
    @GatedRead
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
    @GatedRead
    fun searchTenants(
        filter: TenantFilter,
        caller: FoundationCaller,
    ): ApiPage<TenantSummary> {
        requireValidPage(filter.page, filter.size)
        requireValidSort(filter.sortBy, filter.sortDir, allowedTenantSorts)
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

    /**
     * Retrieves detailed branch metadata by id, validating caller context and permissions. A
     * tenant caller needs `branch.view` tenant-wide or on that branch (ADR 0030, decision 5), so a
     * branch-scoped holder asking for another branch or an unknown id gets 403, while a
     * tenant-wide holder gets 404 for an unknown id: there is no existence oracle.
     */
    @GatedRead
    fun getBranch(
        organisationId: UUID,
        id: UUID,
        caller: FoundationCaller,
    ): BranchDetail {
        verifyTenantScope(organisationId, caller)
        when (caller) {
            is TenantCaller -> {
                permissionGuard.requireBranchPermission(
                    caller.actorId,
                    organisationId,
                    id,
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

    /**
     * Searches branches within an organisation, validating caller context and permissions. A
     * tenant caller sees the branches it holds `branch.view` on (every branch for a tenant-wide
     * grant), restricted in the store query so pages and totals are exact; a caller holding it
     * nowhere gets 403.
     */
    @GatedRead
    fun searchBranches(
        organisationId: UUID,
        filter: BranchFilter,
        caller: FoundationCaller,
    ): ApiPage<BranchSummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
        requireValidSort(filter.sortBy, filter.sortDir, allowedBranchSorts)
        val visibleBranchIds =
            when (caller) {
                is TenantCaller -> {
                    permissionGuard
                        .branchVisibility(caller.actorId, organisationId, "branch.view")
                        .requireListRestriction()
                }

                is PlatformCaller -> {
                    permissionGuard.requirePlatformPermission(caller.actorId, "branch.view")
                    null
                }
            }
        return store.searchBranches(organisationId, filter, visibleBranchIds)
    }

    /** Retrieves detailed business date history metadata by id, validating caller context. */
    @GatedRead
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
    @GatedRead
    fun listBusinessDateHistory(
        organisationId: UUID,
        filter: BusinessDateHistoryFilter,
        caller: FoundationCaller,
    ): ApiPage<BusinessDateHistorySummary> {
        verifyTenantScope(organisationId, caller)
        requireValidPage(filter.page, filter.size)
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

    private companion object {
        val allowedTenantSorts = setOf("tenantCode", "displayName", "countryCode", "createdAt")
        val allowedBranchSorts =
            setOf("branchCode", "branchName", "branchType", "status", "createdAt")
    }
}
