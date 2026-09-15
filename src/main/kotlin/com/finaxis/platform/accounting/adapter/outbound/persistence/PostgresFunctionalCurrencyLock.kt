package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.FunctionalCurrencyLock
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import org.jooq.DSLContext
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Tenant-scoped advisory lock serialising a tenant's first posting against a currency change.
 *
 * Uses the **two-`int4`** form for the reason [AdvisoryLockNamespace] records: PostgreSQL keeps the
 * single-`bigint` and `int4`-pair key spaces structurally separate, so this domain cannot collide
 * with the two legacy single-key sites by construction rather than by hash luck.
 *
 * The mode asymmetry is the point. `pg_advisory_xact_lock_shared` does not conflict with itself, so
 * concurrent postings in one tenant pass straight through while nothing exclusive is pending;
 * `pg_advisory_xact_lock` conflicts with both, so a `base_currency` change waits for postings in
 * flight and they wait for it. Note the qualifier: PostgreSQL conflicts a request against the
 * *waiting* queue as well as the granted locks, so once an exclusive request is queued a new shared
 * request queues behind it - verified against `postgres:18.4`. The exclusive wait is therefore
 * bounded by its caller, because an unbounded one would stall every posting in the tenant.
 *
 * Note that `docs/adr/0022-...` calls `pg_advisory_xact_lock` "exclusive-only" - true of that
 * function, but the family has a shared form, and that is what makes an advisory lock viable here
 * instead of a tenant-wide serialisation.
 *
 * `xact`, not `session`: both modes are released when the caller's transaction ends, so a failure
 * path cannot leak either. That is also why an active transaction is asserted first - outside one
 * the lock would be taken and released within the statement, and the caller would believe it held
 * exclusivity it never had.
 *
 * Blocking rather than `try`. A currency change that cannot serialise should wait its turn rather
 * than fail: it is a rare administrative operation, and a posting only ever waits behind one.
 */
@Component
class PostgresFunctionalCurrencyLock(
    private val dsl: DSLContext,
) : FunctionalCurrencyLock {
    override fun lockForPosting(organisationId: UUID) {
        requireActiveTransaction("A functional-currency posting lock")
        dsl.execute(
            "select pg_advisory_xact_lock_shared(?, ?)",
            AdvisoryLockNamespace.ACCOUNTING_TENANT_FUNCTIONAL_CURRENCY,
            AdvisoryLockNamespace.objectId(organisationId.toString()),
        )
    }

    override fun lockForCurrencyChange(organisationId: UUID) {
        requireActiveTransaction("A functional-currency change lock")
        dsl.execute(
            "select pg_advisory_xact_lock(?, ?)",
            AdvisoryLockNamespace.ACCOUNTING_TENANT_FUNCTIONAL_CURRENCY,
            AdvisoryLockNamespace.objectId(organisationId.toString()),
        )
    }
}
