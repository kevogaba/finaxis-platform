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
 * Both locking methods take the lock and return the locked row's columns in **one** statement, so
 * there is no window between the lock and the read for a status to change in, at any isolation
 * level — see `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md` as amended by
 * `docs/adr/0025-serializable-posting-and-the-covering-period-lock.md`.
 *
 * That one statement carries the caller's whole predicate, the posting date included, which is why
 * [lockCoveringForPosting] takes a date rather than a key. An earlier shape looked up the covering
 * period unlocked and then locked it by key, and the port could only ask reviewers to decide from
 * the second answer rather than the first. Folding the date predicate into the locking statement
 * removes the first answer instead of documenting it.
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

    /**
     * Finds the period covering [postingDate] without locking; its status may be stale.
     *
     * Never use this to decide postability; it exists for read-side queries. Postability is
     * [lockCoveringForPosting]'s question, and it answers it from a row this transaction holds.
     */
    fun findCovering(
        organisationId: UUID,
        postingDate: LocalDate,
    ): FiscalPeriodSnapshot?

    /**
     * Locks and reads, in one statement, the period whose inclusive bounds contain [postingDate].
     *
     * One statement is the whole contract. A lock taken in one statement and the status read in
     * another is decidable only at `READ COMMITTED`, where the second statement takes a fresh
     * snapshot. Here the lock and the projection are the same statement, so PostgreSQL either
     * re-reads the locked row (`EvalPlanQual`, at `READ COMMITTED`) or refuses to serialize the
     * access (`40001`, above it). Never a stale status, at any isolation level.
     *
     * At most one row can match: `ex_accounting_fiscal_period_no_overlap` makes two periods of one
     * tenant covering a date unrepresentable.
     */
    fun lockCoveringForPosting(
        organisationId: UUID,
        postingDate: LocalDate,
    ): FiscalPeriodSnapshot?

    /**
     * Takes an exclusive row lock and returns the columns of the tuple it locked, in one statement.
     */
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
