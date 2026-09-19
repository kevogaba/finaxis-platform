package com.finaxis.platform.accounting

import java.time.LocalDate
import java.util.UUID

/**
 * Accounting-owned port the module that owns the business date calls once a tenant's day has
 * closed, so accounting can settle what that day changed.
 *
 * Today it does one thing: build the daily-balance projection for the journals the tenant recorded
 * on that business date. It is named for the occasion rather than for the projection because the
 * occasion is what lifecycle knows about — *"this tenant's day rolled over"* — and a port named
 * `DailyBalanceProjectionTrigger` would make the caller responsible for knowing which derived
 * structures accounting keeps, which is exactly the coupling this port exists to avoid.
 *
 * **Called on the advance, not on close-of-business completing.** A backdated posting into a
 * still-open prior period is legal while the business date is `CLOSED` and takes no lock on it, so
 * the set of journals recorded on a business date is still open at `completeCob` and closes only
 * once the tenant's date has moved past it. See
 * `docs/adr/0027-derived-balance-projection-and-its-build-trigger.md`.
 *
 * **Called after that transaction commits, never inside it.** `advance` holds the `business_date`
 * row lock that every current-dated posting queues behind, and a rebuild inside it would stall the
 * tenant's whole posting path for as long as the rebuild took. The caller enqueues durable
 * background work inside its transaction — so it exists exactly when the advance committed —
 * and that work calls this.
 *
 * **Accounting declares this port and lifecycle implements the trigger, not the other way round.**
 * `AccountingBoundaryRuleTests` forbids `org.springframework.amqp`, `io.namastack.outbox` and
 * `org.jobrunr` anywhere under `com.finaxis.platform.accounting..`, so accounting can host neither
 * a broker listener nor a background job; lifecycle already allows `accounting` and `common::jobs`
 * and hosts both. The rule is kept rather than narrowed.
 */
interface AccountingDayRollover {
    /**
     * Settles the derived balances for the journals [organisationId] recorded on [businessDate].
     *
     * Idempotent: running it twice for the same business date produces the same rows. Safe to
     * retry, which is what makes it usable from a background job at all.
     *
     * Enumerates by *recording* date rather than by posting date, so a backdated correction
     * recorded on [businessDate] is settled together with that day's ordinary postings, and
     * rebuilds each affected key forward from the earliest posting date the day touched.
     */
    fun settleDay(
        organisationId: UUID,
        businessDate: LocalDate,
    )
}
