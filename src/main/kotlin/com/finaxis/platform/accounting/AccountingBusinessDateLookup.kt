package com.finaxis.platform.accounting

import java.time.LocalDate
import java.util.UUID

/**
 * Accounting-owned read port for the controlled tenant business date, implemented by the lifecycle
 * module which owns the business date and its close-of-business state machine.
 *
 * Read-only by construction: accounting can never advance, reopen or close a business date. This is
 * the first cross-module business-date port in the repository - nothing outside
 * `BusinessDateService` previously read it.
 */
interface AccountingBusinessDateLookup {
    /**
     * Returns the organisation's current business date and posting eligibility, or null when the
     * organisation has no initialized business date.
     *
     * Non-locking, and its answer may be stale by the time the caller acts on it. Never use it to
     * decide whether a posting may commit once that posting has claimed its source reference: that
     * decision is [currentBusinessDateForPosting]'s, and it answers from a row the posting
     * transaction holds. This one is for read-side queries and for the pre-claim read the
     * idempotency fingerprint is computed from, which must not lock.
     */
    fun currentBusinessDate(organisationId: UUID): AccountingBusinessDate?

    /**
     * The same value, read under a shared lock on the `business_date` row, for the one caller that
     * decides whether a posting may still commit into the day: `PostingPeriodResolver`, after the
     * idempotency claim.
     *
     * A locking read, because nothing weaker closes the window. The posting reads the business date
     * *before* its claim - the resolved dates are fingerprinted, and that read cannot lock, because
     * a replay is answered from the claim and a replay deliberately takes no locks at all. A
     * close-of-business committed between that read and the journal is therefore invisible to any
     * plain re-read: the posting path runs at `SERIALIZABLE`, where both reads come from one
     * snapshot and so always agree. Measured on the pinned PostgreSQL, a current-dated journal then
     * commits into a day whose close-of-business had already started.
     *
     * What this read guarantees is *not* that the caller sees the new status. At `SERIALIZABLE` a
     * concurrent committed `startCob` makes this read fail rather than answer, the transaction
     * aborts, and the retry re-reads at a fresh snapshot and is refused with
     * `accounting.business_date_not_open`. The application layer does not get to know that the
     * mechanism is a PostgreSQL row lock, only that this read either answers from a row this
     * transaction holds or fails.
     *
     * Requires an active transaction; the lock is held to its end.
     */
    fun currentBusinessDateForPosting(organisationId: UUID): AccountingBusinessDate?
}

/**
 * A tenant's current business date as accounting sees it. [postingAllowed] is the lifecycle
 * module's judgement, so accounting never has to interpret lifecycle's business-date status
 * vocabulary.
 */
data class AccountingBusinessDate(
    val organisationId: UUID,
    val businessDate: LocalDate,
    val postingAllowed: Boolean,
)
