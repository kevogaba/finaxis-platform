package com.finaxis.platform.accounting

import java.util.UUID

/**
 * Accounting-owned read port for the tenant and branch facts accounting validates before accepting
 * a posting, implemented by the lifecycle module which owns organisation and branch lifecycle
 * state.
 *
 * Deliberately boolean-only: accounting never sees lifecycle state enums, so a new lifecycle state
 * cannot silently change accounting behaviour.
 */
interface AccountingTenantLookup {
    /** Returns whether the organisation is in a state that permits financial activity. */
    fun isOrganisationPostable(organisationId: UUID): Boolean

    /** Returns whether [branchId] exists within [organisationId] and permits financial activity. */
    fun isBranchPostable(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean
}
