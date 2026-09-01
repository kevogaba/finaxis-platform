package com.finaxis.platform.accounting.application

import java.util.UUID

/**
 * Serialises chart-of-accounts structure changes within one tenant.
 *
 * Declared here and implemented in `adapter/outbound/persistence`, like every other port this
 * package owns: the application layer states that hierarchy mutations must not interleave, and does
 * not get to know that the mechanism is a PostgreSQL advisory lock.
 *
 * Why a lock is needed at all is a property of the validation, not of the storage. Re-parenting is
 * checked by reading the chart and then writing to it, and at READ COMMITTED those are two
 * snapshots. Two moves that each pass validation against a pre-write read can both commit — move A
 * under B while moving B under A — and the schema rejects only the one-hop case, so the multi-hop
 * cycle [com.finaxis.platform.accounting.domain.ChartHierarchyPolicy] exists to prevent is exactly
 * what survives.
 */
fun interface ChartHierarchyLock {
    /**
     * Blocks until no other transaction is changing [organisationId]'s chart structure.
     *
     * Held for the remainder of the caller's transaction and released at commit or rollback, so
     * there is no unlock to forget. Tenant-scoped, never global: one tenant's administrator must
     * not be able to stall another's.
     */
    fun lockChartOfAccounts(organisationId: UUID)
}
