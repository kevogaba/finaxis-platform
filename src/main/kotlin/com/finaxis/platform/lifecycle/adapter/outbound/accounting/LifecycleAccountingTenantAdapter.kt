package com.finaxis.platform.lifecycle.adapter.outbound.accounting

import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.lifecycle.application.FoundationLifecycleReader
import com.finaxis.platform.lifecycle.application.OrganisationBootstrapStore
import com.finaxis.platform.lifecycle.domain.BranchLifecycleState
import com.finaxis.platform.lifecycle.domain.OrganisationLifecycleState
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Lifecycle implementation of the accounting tenant port.
 *
 * Collapses organisation and branch lifecycle states to the booleans accounting needs, so a new
 * lifecycle state cannot silently change accounting behaviour: anything other than ACTIVE denies
 * posting, and a new state is denied until someone decides otherwise here.
 */
@Component
class LifecycleAccountingTenantAdapter(
    private val lifecycleReader: FoundationLifecycleReader,
    private val bootstrapStore: OrganisationBootstrapStore,
) : AccountingTenantLookup {
    override fun isOrganisationPostable(organisationId: UUID): Boolean =
        lifecycleReader.findOrganisation(organisationId)?.state == OrganisationLifecycleState.ACTIVE

    override fun isBranchPostable(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean =
        lifecycleReader.findBranch(organisationId, branchId)?.state == BranchLifecycleState.ACTIVE

    /**
     * Existence within the tenant, whatever the branch's state.
     *
     * `findBranch` is already organisation-scoped, so a non-null answer *is* membership. No state
     * is consulted on purpose: unlike posting, a historical proof of a closed branch is legitimate.
     */
    override fun branchBelongsTo(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean = lifecycleReader.findBranch(organisationId, branchId) != null

    override fun functionalCurrencyOf(organisationId: UUID): String? =
        bootstrapStore.baseCurrencyCode(organisationId)

    /**
     * The locking read accounting decides a journal's currency from, delegated unchanged.
     *
     * Deliberately a second port method rather than a flag on the first. The two have different
     * preconditions - this one requires an active transaction and leaves a lock behind - and a
     * boolean parameter would let a read-side caller acquire that lock by accident.
     */
    override fun functionalCurrencyForPosting(organisationId: UUID): String? =
        bootstrapStore.lockBaseCurrencyCode(organisationId)
}
