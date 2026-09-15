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
     * Returns whether [branchId] is a branch of [organisationId] at all, whatever its state.
     *
     * Existence, deliberately not postability. Reconciliation is historical: a branch closed last
     * year is a legitimate subject of a proof of a date on which it was open, so reusing
     * [isBranchPostable] there would refuse a question the ledger can answer. A scope naming a
     * branch that does not exist is a different thing entirely and is refused before either side
     * of a proof is read, rather than surfacing as a foreign-key violation on the evidence insert.
     */
    fun branchBelongsTo(
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
     *
     * Non-locking, and its answer may be stale by the time the caller acts on it. Never use it to
     * decide the currency a posting is denominated in once that posting holds the tenant-currency
     * lock: that decision is [functionalCurrencyForPosting]'s, and it answers from a row the
     * posting transaction holds. This one is for read-side queries and for the pre-claim read the
     * idempotency fingerprint is computed from, which must not lock.
     */
    fun functionalCurrencyOf(organisationId: UUID): String?

    /**
     * The same value, read under a shared lock on the organisation row, for the one caller that
     * decides a journal's currency: `PostingEngine.postNew`, after the tenant-currency lock.
     *
     * A locking read, because nothing weaker closes the window. The posting reads the currency
     * *before* its idempotency claim - the fingerprint is computed from it, and that read cannot
     * lock, because a replay is answered from the claim and a replay deliberately takes no locks
     * at all. A `base_currency` change committing between that read and the lock is therefore
     * invisible to any plain re-read: the posting path runs at `SERIALIZABLE`, where both reads
     * come from one snapshot and so always agree. Measured on the pinned PostgreSQL, the tenant's
     * first journal then commits in the superseded currency.
     *
     * Rejected alternatives, both measured rather than reasoned about. The tenant-currency
     * advisory lock does not close it: it does not participate in MVCC, so there is no re-read to
     * correct the second value and no serialization failure to abort on. Hoisting that lock above
     * the pre-claim read does not close it either - the snapshot was already fixed by work the
     * transaction had done before the lock, and waiting on a lock is precisely what guarantees a
     * stale snapshot. Making [functionalCurrencyOf] itself locking would close it and cost far
     * more than it is worth: it would hold the organisation row for every read-side caller, and
     * for the whole of every replay, which is the one path that must take no locks.
     *
     * What this read guarantees is *not* that the caller sees the new value. At `SERIALIZABLE` a
     * concurrent committed change makes this read fail rather than answer, the transaction aborts,
     * and the retry re-reads at a fresh snapshot and fingerprints against the currency that won.
     * The application layer does not get to know that the mechanism is a PostgreSQL row lock, only
     * that this read either answers from a row this transaction holds or fails.
     *
     * Requires an active transaction; the lock is held to its end.
     */
    fun functionalCurrencyForPosting(organisationId: UUID): String?
}
