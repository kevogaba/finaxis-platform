package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.balances.DailyBalanceProjectionLock
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tenant-scoped advisory lock over daily-balance builds.
 *
 * The two-`int4` form and the `xact` scope, for the reasons [PostgresChartHierarchyLock] records:
 * the key space is structurally separate from the legacy single-`bigint` sites, and a transaction
 * lock cannot be leaked by a failure path.
 *
 * **Blocking, and with no bound.** Every other accounting lock that waits is bounded, because every
 * other one has an operator or a posting on the other end of it and an unbounded wait would be a
 * request that never returns. This one is taken only by a background rebuild, whose caller is
 * JobRunr: waiting is exactly what it should do, and failing fast would turn an ordinary overlap
 * into a retry that re-does the same work later. A build blocked behind a stuck build is a
 * monitoring question (#53), not a latency one.
 */
@Component
class PostgresDailyBalanceProjectionLock(
    private val dsl: DSLContext,
) : DailyBalanceProjectionLock {
    override fun lockForBuild(organisationId: UUID) {
        requireActiveTransaction("A daily-balance projection lock")
        dsl.execute(
            "select pg_advisory_xact_lock(?, ?)",
            AdvisoryLockNamespace.ACCOUNTING_DAILY_BALANCE_PROJECTION,
            AdvisoryLockNamespace.objectId(organisationId.toString()),
        )
    }
}
