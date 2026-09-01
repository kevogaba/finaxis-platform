package com.finaxis.platform.lifecycle.adapter.outbound.accounting

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.lifecycle.application.BusinessDateStore
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Lifecycle implementation of the accounting business-date port.
 *
 * Translates the lifecycle business-date status vocabulary into the single boolean accounting
 * needs, so accounting never interprets close-of-business states. Only an OPEN business date
 * permits posting: CLOSING, CLOSED and ADVANCING all deny it.
 */
@Component
class LifecycleAccountingBusinessDateAdapter(
    private val businessDateStore: BusinessDateStore,
) : AccountingBusinessDateLookup {
    override fun currentBusinessDate(organisationId: UUID): AccountingBusinessDate? =
        businessDateStore.current(organisationId)?.let { snapshot ->
            AccountingBusinessDate(
                organisationId = organisationId,
                businessDate = snapshot.currentBusinessDate,
                postingAllowed = snapshot.status == OPEN_STATUS,
            )
        }

    private companion object {
        /**
         * Matches the OPEN constant owned by the lifecycle business-date service. Duplicated
         * because that constant is private; `LifecycleAccountingAdapterIntegrationTests` drives
         * the real service so the duplication cannot drift undetected.
         */
        const val OPEN_STATUS = "OPEN"
    }
}
