package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.domain.FiscalPeriodTransition
import java.time.LocalDate
import java.util.UUID

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
    /**
     * Reads one period by key without locking.
     *
     * For a caller that wants to *see* a period rather than change it. A state change must go
     * through [lockForStateChange] instead: deciding from this snapshot is deciding from a read
     * another transaction can invalidate before the write lands.
     */
    fun findById(key: FiscalPeriodKey): FiscalPeriodSnapshot?

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
     * [actorId] populates `updated_by` and [reason] populates `status_reason`, so the row itself
     * says who last moved it and why. Both are mirrors for convenience: the authoritative record
     * of a transition, and the one the reopen actor-identity check reads, is
     * `fiscal_period_transition_log`.
     */
    fun updateStatus(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
        actorId: UUID,
        reason: String?,
    ): Boolean
}

/**
 * Who performed a given transition on a period last.
 *
 * Exists so the reopen control can require a different actor from the one who closed, which is how
 * `INV-10`'s *"a checker whose identity is persisted and who is not the maker"* is satisfied
 * without a `fiscal_period.submit` code the catalogue does not have. The answer lives in
 * `fiscal_period_transition_log`, so the accounting transition-log writer implements this.
 */
interface FiscalPeriodMakerResolver {
    /** The actor of the most recent [transition] on [key], or null when it never happened. */
    fun lastActorFor(
        key: FiscalPeriodKey,
        transition: FiscalPeriodTransition,
    ): UUID?
}
