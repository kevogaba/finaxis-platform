package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import org.jooq.DSLContext
import org.jooq.Record5
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * The `accounting_fiscal_period` adapter behind [FiscalPeriodStateStore].
 *
 * Issue #35 shipped the port, the locking protocol and its proofs before the table existed, with
 * the proofs bound to a stand-in row. This binds them to the real table. The protocol has since
 * been reduced to one statement per lock: [lockCoveringForPosting] and [lockForStateChange] each
 * take their lock and project the locked tuple's columns in a single `SELECT … FOR SHARE` /
 * `… FOR UPDATE`, rather than locking through a boolean primitive and re-reading afterwards.
 *
 * That collapse is not a tidy-up. A lock in one statement and a read in the next is decidable only
 * at `READ COMMITTED`, where the second statement takes a fresh snapshot; above it the lock
 * statement raises `40001` and the second statement never runs. One statement leaves one mechanism
 * to reason about at every isolation level — `EvalPlanQual` at `READ COMMITTED`, `40001` above it —
 * which matters now that the posting path runs at `SERIALIZABLE` and the close path does not.
 *
 * Every statement carries the tenant predicate. The snapshot's organisation is read from the row
 * rather than echoed back from the caller's key, so a key naming another tenant's period can never
 * come back labelled with the caller's organisation — which is how a cross-tenant write slips past
 * a downstream tenant check.
 *
 * See `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md` as amended by
 * `docs/adr/0025-serializable-posting-and-the-covering-period-lock.md`.
 */
@Component
class JooqFiscalPeriodStateStore(
    private val dsl: DSLContext,
    private val clock: Clock,
) : FiscalPeriodStateStore {
    /**
     * Finds the period whose inclusive bounds contain [postingDate], without locking.
     *
     * At most one row can match: `ex_accounting_fiscal_period_no_overlap` makes two periods of one
     * tenant covering the same date unrepresentable, which is what lets this return a single
     * snapshot instead of a collection the caller would have to disambiguate.
     */
    override fun findCovering(
        organisationId: UUID,
        postingDate: LocalDate,
    ): FiscalPeriodSnapshot? =
        selectPeriod()
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.START_DATE.le(postingDate))
            .and(ACCOUNTING_FISCAL_PERIOD.END_DATE.ge(postingDate))
            .fetchOne()
            ?.let(::toSnapshot)

    override fun findById(key: FiscalPeriodKey): FiscalPeriodSnapshot? =
        selectPeriod()
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(key.organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
            .fetchOne()
            ?.let(::toSnapshot)

    /**
     * `FOR SHARE`, with the tenant predicate *and* the date-range predicate in the same statement
     * as the lock.
     *
     * The date predicate belongs here rather than in a lookup the caller makes first. With it
     * inside the locking statement, a row that comes back is a row this transaction holds and whose
     * bounds satisfied the predicate at lock time: at `READ COMMITTED` a concurrent edit to those
     * bounds makes `EvalPlanQual` re-evaluate the quals and the statement return nothing at all,
     * and above `READ COMMITTED` it raises `40001`. Neither outcome is a stale row labelled as
     * covering the date.
     *
     * At most one row can match: `ex_accounting_fiscal_period_no_overlap` makes two periods of one
     * tenant covering the same date unrepresentable, which is what lets this return a single
     * snapshot rather than a collection the caller would have to disambiguate.
     */
    override fun lockCoveringForPosting(
        organisationId: UUID,
        postingDate: LocalDate,
    ): FiscalPeriodSnapshot? {
        requireActiveTransaction("Locking the fiscal period covering a posting date")
        return selectPeriod()
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.START_DATE.le(postingDate))
            .and(ACCOUNTING_FISCAL_PERIOD.END_DATE.ge(postingDate))
            .forShare()
            .fetchOne()
            ?.let(::toSnapshot)
    }

    /**
     * `FOR UPDATE`, with the tenant predicate in the same statement as the lock.
     *
     * A close or a reopen waits here for every in-flight posting holding [lockCoveringForPosting]'s
     * shared lock on the same row, and the status it reads back is the locked tuple's own. The
     * close path deliberately stays at `READ COMMITTED`, so one adapter method now serves a
     * serializable reader and a read-committed writer; the statement is correct at both, which is
     * the reason the collapse was worth making rather than raising the writer too.
     */
    override fun lockForStateChange(key: FiscalPeriodKey): FiscalPeriodSnapshot? {
        requireActiveTransaction("Locking a fiscal period for a state change")
        return selectPeriod()
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(key.organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
            .forUpdate()
            .fetchOne()
            ?.let(::toSnapshot)
    }

    /**
     * Writes the new status under the exclusive lock the caller already holds.
     *
     * [actorId] populates `updated_by` and [reason] populates `status_reason`, so the row says who
     * last moved it and why without a join. Both are mirrors: the authoritative record of who
     * performed a transition, and the one the reopen actor-identity check reads, is
     * `fiscal_period_transition_log.created_by`.
     *
     * A false return means the row no longer exists for that tenant. It cannot mean a concurrent
     * change: the caller holds `FOR UPDATE`, so nothing else can move the row until it commits.
     */
    override fun updateStatus(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
        actorId: UUID,
        reason: String?,
    ): Boolean {
        // The contract above says the caller holds FOR UPDATE. Outside a transaction that lock was
        // released the instant it was taken, so the write would silently bypass the protocol rather
        // than fail. Assert the premise instead of trusting it.
        requireActiveTransaction("Updating a fiscal-period status")
        return dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, newStatus.name)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS_REASON, reason)
            .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, OffsetDateTime.now(clock))
            .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_BY, actorId)
            .set(
                ACCOUNTING_FISCAL_PERIOD.ROW_VERSION,
                ACCOUNTING_FISCAL_PERIOD.ROW_VERSION.plus(1),
            ).where(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
            .and(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(key.organisationId))
            .execute() == 1
    }

    private fun selectPeriod() =
        dsl
            .select(
                ACCOUNTING_FISCAL_PERIOD.ID,
                ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID,
                ACCOUNTING_FISCAL_PERIOD.START_DATE,
                ACCOUNTING_FISCAL_PERIOD.END_DATE,
                ACCOUNTING_FISCAL_PERIOD.STATUS,
            ).from(ACCOUNTING_FISCAL_PERIOD)

    private fun toSnapshot(row: Record5<UUID?, UUID?, LocalDate?, LocalDate?, String?>) =
        FiscalPeriodSnapshot(
            key = FiscalPeriodKey(row.value2()!!, row.value1()!!),
            startDate = row.value3()!!,
            endDate = row.value4()!!,
            status = FiscalPeriodStatus.valueOf(row.value5()!!),
        )
}
