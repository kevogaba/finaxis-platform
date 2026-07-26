package com.finaxis.platform.lifecycle.application.query

import com.finaxis.platform.common.web.api.ApiPage
import java.time.Instant
import java.util.UUID

/** Filter parameters for tenant queries. */
data class TenantFilter(
    val q: String? = null,
    val status: String? = null,
    val country: String? = null,
    val createdFrom: Instant? = null,
    val createdTo: Instant? = null,
    val page: Int = 0,
    val size: Int = 25,
    val sortBy: String? = null,
    val sortDir: String? = null,
)

/** Filter parameters for branch queries. */
data class BranchFilter(
    val q: String? = null,
    val status: String? = null,
    val type: String? = null,
    val page: Int = 0,
    val size: Int = 25,
    val sortBy: String? = null,
    val sortDir: String? = null,
)

/** Filter parameters for business date history queries. */
data class BusinessDateHistoryFilter(
    val page: Int = 0,
    val size: Int = 25,
)

/** Outbound port for query storage operations against tenant lifecycle aggregates. */
interface FoundationQueryStore {
    /** Searches tenants matching the filter constraints, optionally scoped to a single tenant. */
    fun searchTenants(
        filter: TenantFilter,
        organisationId: UUID? = null,
    ): ApiPage<TenantSummary>

    /** Finds detailed tenant metadata by id. */
    fun findTenantById(id: UUID): TenantDetail?

    /** Searches branches within an organisation. */
    fun searchBranches(
        organisationId: UUID,
        filter: BranchFilter,
    ): ApiPage<BranchSummary>

    /** Finds detailed branch metadata by id. */
    fun findBranchById(
        organisationId: UUID,
        id: UUID,
    ): BranchDetail?

    /** Lists business date history events for an organisation. */
    fun listBusinessDateHistory(
        organisationId: UUID,
        filter: BusinessDateHistoryFilter,
    ): ApiPage<BusinessDateHistorySummary>

    /** Finds detailed business date history metadata by id. */
    fun findBusinessDateHistoryById(
        organisationId: UUID,
        id: UUID,
    ): BusinessDateHistoryDetail?
}
