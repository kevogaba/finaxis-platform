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

    /**
     * Returns the organisation's functional currency - `organisation.base_currency_code` - or null
     * when the organisation does not exist.
     *
     * The column, not the `base_currency` tenant setting: the column is set at provisioning,
     * carries the ISO 4217 check, and can only be amended while the organisation is still a draft,
     * so it is fixed before a journal can exist. The setting is a tenant preference that lifecycle
     * refuses to change once accounting reports a posted journal.
     */
    fun functionalCurrencyOf(organisationId: UUID): String?
}
