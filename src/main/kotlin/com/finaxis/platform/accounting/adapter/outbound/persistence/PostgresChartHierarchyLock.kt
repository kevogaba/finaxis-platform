package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.ChartHierarchyLock
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tenant-scoped advisory lock over chart-of-accounts structure changes.
 *
 * Uses the **two-`int4`** form, `pg_advisory_xact_lock(classid, objid)`, for the reason
 * [AdvisoryLockNamespace] records: PostgreSQL keeps the single-`bigint` and `int4`-pair key spaces
 * structurally separate, so this domain cannot collide with the two legacy single-key sites by
 * construction rather than by hash luck.
 *
 * `xact`, not `session`: the lock is released when the caller's transaction ends, so a failure path
 * cannot leak it. That is also why [requireActiveTransaction] is asserted first — outside a
 * transaction the lock would be taken and released within the statement, and the caller would
 * believe it held exclusivity it never had.
 *
 * Blocking rather than `try`. A chart edit that cannot serialise should wait its turn, not fail:
 * these are rare administrative operations, and the alternative is a spurious conflict error for a
 * request that would have succeeded a moment later. The bounded-wait form belongs where a caller
 * has something better to do than wait, which is not the case here.
 */
@Component
class PostgresChartHierarchyLock(
    private val dsl: DSLContext,
) : ChartHierarchyLock {
    override fun lockChartOfAccounts(organisationId: UUID) {
        requireActiveTransaction("A chart-of-accounts hierarchy lock")
        dsl.execute(
            "select pg_advisory_xact_lock(?, ?)",
            AdvisoryLockNamespace.ACCOUNTING_CHART_HIERARCHY,
            AdvisoryLockNamespace.objectId(organisationId.toString()),
        )
    }
}
