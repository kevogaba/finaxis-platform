package com.finaxis.platform.lifecycle.application.query

import java.util.UUID

/**
 * What a tenant caller may approve, as counts (#169). A key is `null` when the caller lacks that
 * resource's approve authority, so a caller learns nothing about resources it cannot act on; it is
 * not a zero.
 *
 * @property memberships pending membership invitations the caller could approve, or `null`.
 * @property branches pending branches the caller could approve, or `null`.
 */
data class TenantPendingCounts(
    val memberships: Long?,
    val branches: Long?,
)

/**
 * What a platform caller may approve as the tenant checker: pending tenants, across tenants.
 *
 * @property tenants pending tenants the caller did not request or submit itself.
 */
data class PlatformPendingCounts(
    val tenants: Long,
)

/**
 * Outbound port for the pending-approval counts. Each method is one `COUNT(*)` over one table,
 * tenant-filtered except [pendingTenants], which counts tenant rows themselves. A non-null
 * `excludingMaker` leaves out the items that actor made, because the maker-checker rule would
 * refuse their approval (ADR 0028).
 */
interface PendingApprovalCountStore {
    /** Memberships of [organisationId] in `PENDING_APPROVAL` not invited by [excludingMaker]. */
    fun pendingMemberships(
        organisationId: UUID,
        excludingMaker: UUID?,
    ): Long

    /** Branches of [organisationId] in `PENDING_APPROVAL` not created by [excludingMaker]. */
    fun pendingBranches(
        organisationId: UUID,
        excludingMaker: UUID?,
    ): Long

    /**
     * Tenants in `PENDING_APPROVAL` (never the reserved platform organisation) whose requester and
     * latest submitter are not [excludingMaker].
     */
    fun pendingTenants(excludingMaker: UUID?): Long
}
