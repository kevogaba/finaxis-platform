package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.FiscalPeriodKey
import com.finaxis.platform.accounting.application.FiscalPeriodSnapshot
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.FiscalPeriodStatus
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
 * the proofs bound to a stand-in row. This binds them to the real table, and nothing about the
 * protocol changes: the lookup is unlocked, the lock is taken through the same [PostgresRowLock],
 * and both locking methods re-read **after** the lock so the status they return is the latest
 * committed value rather than the caller's snapshot.
 *
 * Every statement carries the tenant predicate. The snapshot's organisation is read from the row
 * rather than echoed back from the caller's key, so a key naming another tenant's period can never
 * come back labelled with the caller's organisation — which is how a cross-tenant write slips past
 * a downstream tenant check.
 *
 * See `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md`.
 */
@Component
class JooqFiscalPeriodStateStore(
    private val dsl: DSLContext,
    private val rowLock: PostgresRowLock,
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

    override fun lockForPosting(key: FiscalPeriodKey): FiscalPeriodSnapshot? =
        readUnderLock(key) { id, organisationId ->
            rowLock.lockForShare(
                ACCOUNTING_FISCAL_PERIOD,
                ACCOUNTING_FISCAL_PERIOD.ID,
                ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID,
                id,
                organisationId,
            )
        }

    override fun lockForStateChange(key: FiscalPeriodKey): FiscalPeriodSnapshot? =
        readUnderLock(key) { id, organisationId ->
            rowLock.lockForUpdate(
                ACCOUNTING_FISCAL_PERIOD,
                ACCOUNTING_FISCAL_PERIOD.ID,
                ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID,
                id,
                organisationId,
            )
        }

    /**
     * Writes the new status under the exclusive lock the caller already holds.
     *
     * [actorId] populates `updated_by`, so the row itself says who last moved it. That is a mirror
     * for convenience: the authoritative record of who performed a transition, and the one the
     * reopen actor-identity check reads, is `fiscal_period_transition_log.created_by`.
     *
     * A false return means the row no longer exists for that tenant. It cannot mean a concurrent
     * change: the caller holds `FOR UPDATE`, so nothing else can move the row until it commits.
     */
    override fun updateStatus(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
        actorId: UUID,
    ): Boolean {
        // The contract above says the caller holds FOR UPDATE. Outside a transaction that lock was
        // released the instant it was taken, so the write would silently bypass the protocol rather
        // than fail. Assert the premise instead of trusting it.
        requireActiveTransaction("Updating a fiscal-period status")
        return dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, newStatus.name)
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

    /**
     * Takes the lock, then re-reads. The re-read is the point: under READ COMMITTED the second
     * statement takes a fresh snapshot, so a status change that committed between an earlier
     * unlocked lookup and this call is observed rather than missed.
     */
    private fun readUnderLock(
        key: FiscalPeriodKey,
        lock: (UUID, UUID) -> Boolean,
    ): FiscalPeriodSnapshot? {
        if (!lock(key.fiscalPeriodId, key.organisationId)) {
            return null
        }
        return selectPeriod()
            .where(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
            .and(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(key.organisationId))
            .fetchOne()
            ?.let(::toSnapshot)
    }

    private fun toSnapshot(row: Record5<UUID?, UUID?, LocalDate?, LocalDate?, String?>) =
        FiscalPeriodSnapshot(
            key = FiscalPeriodKey(row.value2()!!, row.value1()!!),
            startDate = row.value3()!!,
            endDate = row.value4()!!,
            status = FiscalPeriodStatus.valueOf(row.value5()!!),
        )
}
