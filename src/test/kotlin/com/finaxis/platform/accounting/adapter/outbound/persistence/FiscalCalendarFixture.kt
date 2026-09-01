package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import org.jooq.DSLContext
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Test-side setup for the fiscal-calendar concurrency scenarios.
 *
 * This is **only** setup and observation. Every locking and status-changing call in those
 * scenarios goes through the production [JooqFiscalPeriodStateStore] bean, so what they exercise
 * is the real adapter against `accounting_fiscal_period` — not a stand-in. Issue #35 had to bind
 * those proofs to a row in `organisation_setting` because the table did not exist; `V6` created
 * it, `FiscalPeriodStandIn` was deleted, and the compiler is what enforced the repointing.
 *
 * One stand-in remains and is named as such: `recordJournal` writes a `gl_account` row rather than
 * a journal, because `journal_entry` arrives with issue #40. It is a probe for *"did a durable
 * write inside the posting transaction survive?"*, which is all the concurrency scenarios ask of
 * it, and it at least writes to a real accounting table.
 */
class FiscalCalendarFixture(
    private val dsl: DSLContext,
) {
    /**
     * Creates a fiscal year and one period inside it, and returns the period's key.
     *
     * [yearOffset] shifts both by whole years. It exists because the exclusion constraints forbid
     * two years — or two periods — of one tenant covering the same date, so a scenario that needs
     * a *second*, genuinely unrelated calendar row has to put it in another year. With the
     * stand-in that was free; against the real table it is the constraint doing its job.
     *
     * Offset zero covers [PERIOD_DAY], which is the posting date every scenario resolves.
     */
    fun createPeriod(
        organisationId: UUID,
        status: FiscalPeriodStatus,
        yearOffset: Long = 0,
    ): FiscalPeriodKey {
        val now = OffsetDateTime.now()
        val year = PERIOD_DAY.year + yearOffset
        val yearId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_YEAR)
                .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY$year")
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year $year")
                .set(ACCOUNTING_FISCAL_YEAR.START_DATE, YEAR_START.plusYears(yearOffset))
                .set(ACCOUNTING_FISCAL_YEAR.END_DATE, YEAR_END.plusYears(yearOffset))
                .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now)
                .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now)
                .returning(ACCOUNTING_FISCAL_YEAR.ID)
                .fetchOne()!!
                .id!!

        val periodId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_PERIOD)
                .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
                .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, PERIOD_DAY.monthValue)
                .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period ${PERIOD_DAY.monthValue}")
                .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, PERIOD_START.plusYears(yearOffset))
                .set(ACCOUNTING_FISCAL_PERIOD.END_DATE, PERIOD_END.plusYears(yearOffset))
                .set(ACCOUNTING_FISCAL_PERIOD.STATUS, status.name)
                .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now)
                .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now)
                .returning(ACCOUNTING_FISCAL_PERIOD.ID)
                .fetchOne()!!
                .id!!

        return FiscalPeriodKey(organisationId, periodId)
    }

    /** Records the durable effect a posting would leave. See the class KDoc on what stands in. */
    fun recordJournal(key: FiscalPeriodKey) {
        val now = OffsetDateTime.now()
        dsl
            .insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ORGANISATION_ID, key.organisationId)
            // Deliberately short enough for chk_gl_account_code and AccountCode's 32-character
            // bound: the full UUID would be 49 characters, which the constraint rejects and the
            // value object would throw on when the row was read back.
            .set(
                GL_ACCOUNT.ACCOUNT_CODE,
                "$JOURNAL_CODE_PREFIX${UUID.randomUUID().toString().take(PROBE_SUFFIX_LENGTH)}",
            ).set(GL_ACCOUNT.ACCOUNT_NAME, "Posting probe")
            .set(GL_ACCOUNT.ACCOUNT_CLASS, "ASSET")
            .set(GL_ACCOUNT.ACCOUNT_USAGE, "POSTABLE")
            // normal_balance is a generated column - writing it raises SQLSTATE 428C9.
            .set(GL_ACCOUNT.STATUS, "ACTIVE")
            .set(GL_ACCOUNT.CREATED_AT, now)
            .set(GL_ACCOUNT.UPDATED_AT, now)
            .execute()
    }

    /** Counts the durable effects recorded for one organisation. */
    fun journalCount(organisationId: UUID): Int =
        dsl.fetchCount(
            GL_ACCOUNT,
            GL_ACCOUNT.ORGANISATION_ID
                .eq(organisationId)
                .and(GL_ACCOUNT.ACCOUNT_CODE.like("$JOURNAL_CODE_PREFIX%")),
        )

    /**
     * Changes the status with a plain `UPDATE` that takes no explicit lock.
     *
     * Exists so one scenario can prove that `FOR SHARE` blocks even a caller that forgot to take
     * the exclusive lock — the reason the shared lock is `FOR SHARE` rather than `FOR KEY SHARE`.
     */
    fun updateStatusWithoutLocking(
        key: FiscalPeriodKey,
        newStatus: FiscalPeriodStatus,
    ): Int =
        dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, newStatus.name)
            .where(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
            .and(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(key.organisationId))
            .execute()

    companion object {
        /** The posting date every scenario uses, and the period below covers it. */
        val PERIOD_DAY: LocalDate = LocalDate.of(2026, 8, 15)
        val PERIOD_START: LocalDate = LocalDate.of(2026, 8, 1)
        val PERIOD_END: LocalDate = LocalDate.of(2026, 8, 31)
        val YEAR_START: LocalDate = LocalDate.of(2026, 1, 1)
        val YEAR_END: LocalDate = LocalDate.of(2026, 12, 31)
        const val JOURNAL_CODE_PREFIX = "PROBE-"

        /** Keeps the generated code inside `chk_gl_account_code`'s 32-character bound. */
        const val PROBE_SUFFIX_LENGTH = 20
    }
}
