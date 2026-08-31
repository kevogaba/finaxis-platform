package com.finaxis.platform.lifecycle.adapter.outbound.accounting

import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.lifecycle.application.FoundationLifecycleReader
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
) : AccountingTenantLookup {
    override fun isOrganisationPostable(organisationId: UUID): Boolean =
        lifecycleReader.findOrganisation(organisationId)?.state == OrganisationLifecycleState.ACTIVE

    override fun isBranchPostable(
        organisationId: UUID,
        branchId: UUID,
    ): Boolean =
        lifecycleReader.findBranch(organisationId, branchId)?.state == BranchLifecycleState.ACTIVE
}
