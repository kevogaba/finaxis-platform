package com.finaxis.platform.accounting.application

import java.time.LocalDate
import java.util.UUID

/** Identity of the fiscal period a posting or a state change targets. */
data class FiscalPeriodKey(
    val organisationId: UUID,
    val fiscalPeriodId: UUID,
)

/**
 * Fiscal-period lifecycle status.
 *
 * These four are the set `docs/database/accounting-erd.md` adopts and
 * `chk_accounting_fiscal_period_status` enforces, so the enum and the column cannot drift.
 *
 * `SOFT_CLOSED` is deliberately absent: the distinction it would draw — postings blocked for
 * ordinary users but open to a privileged few — is already expressed by [CLOSED] plus
 * `journal.post_prior_period`, so it would add a state without adding a capability.
 */
enum class FiscalPeriodStatus {
    /** Provisioned but not yet open for posting. */
    FUTURE,

    /** Open for posting. */
    OPEN,

    /** Closed; postings are rejected until an explicit, audited reopen. */
    CLOSED,

    /**
     * Locked; postings are rejected and the period can never be reopened.
     *
     * Distinct from [CLOSED] on purpose, and the distinction is load-bearing rather than
     * decorative: `docs/architecture/accounting-foundation.md` states that a closed period may be
     * reopened and a locked one may not, and rejects Fineract's closure-date model precisely
     * because it cannot express the difference. Without this state a store adapter has no way to
     * represent a locked row, and issue #39's reopen flow has nothing to refuse against — it would
     * happily reopen permanently finalised books.
     */
    LOCKED,
}

/** A fiscal period as read under a row lock: identity, bounds and status. */
data class FiscalPeriodSnapshot(
    val key: FiscalPeriodKey,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: FiscalPeriodStatus,
)

/**
 * Fiscal-period state port.
 *
 * Both locking methods must read the period row **after** taking its lock, so the status they
 * return is the latest committed value rather than the caller's snapshot. Under READ COMMITTED
 * that second read takes a fresh snapshot, which is what makes the posting-versus-close race
 * decidable — see `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md`.
 *
 * Returning a freshly read snapshot rather than a boolean puts the correct value nearest to hand.
 * It does not make the mistake impossible: [findCovering] also returns a status, so a caller can
 * still decide from the unlocked read. Deciding from the snapshot these methods return is a
 * convention, and reviewers of a new caller must check it.
 *
 * The production adapter is
 * [com.finaxis.platform.accounting.adapter.outbound.persistence.JooqFiscalPeriodStateStore].
 */
interface FiscalPeriodStateStore {
    /** Finds the period covering [postingDate] without locking; its status may be stale. */
    fun findCovering(
        organisationId: UUID,
        postingDate: LocalDate,
    ): FiscalPeriodSnapshot?

    /** Takes a shared row lock and returns the period as read under that lock. */
    fun lockForPosting(key: FiscalPeriodKey): FiscalPeriodSnapshot?

    /** Takes an exclusive row lock and returns the period as read under that lock. */
    fun lockForStateChange(key: FiscalPeriodKey): FiscalPeriodSnapshot?

    /**
     * Sets the status while the caller holds the exclusive lock; false when the row is gone.
     *
     * [actorId] populates `updated_by` so the period row itself says who last moved it. The
     * authoritative record of a transition, and the one issue #39's reopen actor-identity check
     * reads, is `fiscal_period_transition_log.created_by`.
     */
    fun updateStatus(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
        actorId: UUID,
    ): Boolean
}
