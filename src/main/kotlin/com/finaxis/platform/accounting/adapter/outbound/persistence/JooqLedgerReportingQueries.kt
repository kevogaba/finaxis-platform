package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.reporting.AccountMovement
import com.finaxis.platform.accounting.application.reporting.JournalDetail
import com.finaxis.platform.accounting.application.reporting.JournalDetailLine
import com.finaxis.platform.accounting.application.reporting.LedgerCursor
import com.finaxis.platform.accounting.application.reporting.LedgerMovement
import com.finaxis.platform.accounting.application.reporting.LedgerReportingQueries
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The journal-side adapter behind [LedgerReportingQueries].
 *
 * Read-only by construction: nothing in this class writes, and `journal_line` could not be updated
 * if it tried (`INV-1`). It reads three tables — `journal_entry`, `journal_line` and
 * `posting_request` — and every statement carries the tenant predicate in its own `WHERE`, never
 * only through a join, so a corrupted foreign key cannot walk into another tenant's ledger.
 *
 * ## Which index answers which question
 *
 * The two aggregate shapes in [movementsByAccount] exist because two indexes lead with different
 * columns, and asking either one the other's question is what makes a trial balance slow:
 *
 * | Question                                     | Index                                    |
 * |----------------------------------------------|------------------------------------------|
 * | One branch, a date range, grouped by account | `idx_journal_line_branch_account_date`   |
 * | One account, a date range                    | `idx_journal_line_account_date`          |
 *
 * A tenant-wide report is therefore a `LATERAL` driven by the chart — 500 to 2,000 tight range
 * scans, each reading only its own account's rows in the window — rather than one scan over a
 * date-range predicate that no index leads with. Both are index-only: the columns the aggregate
 * needs ride in the indexes' `INCLUDE` lists.
 */
@Component
class JooqLedgerReportingQueries(
    private val dsl: DSLContext,
) : LedgerReportingQueries {
    override fun movementsByAccount(
        organisationId: UUID,
        branchId: UUID?,
        fromDate: LocalDate?,
        toDate: LocalDate,
    ): List<AccountMovement> =
        if (branchId == null) {
            tenantWideMovements(organisationId, fromDate, toDate)
        } else {
            branchMovements(organisationId, branchId, fromDate, toDate)
        }

    /**
     * One grouped aggregate over `idx_journal_line_branch_account_date`.
     *
     * The index key is `(organisation_id, branch_id, posting_date, gl_account_id)`, which is this
     * predicate in its own order, so the whole report is a single range scan of exactly the rows in
     * the window. `direction` and `functional_amount` are in the index's `INCLUDE` list, so the
     * heap is never touched.
     */
    private fun branchMovements(
        organisationId: UUID,
        branchId: UUID,
        fromDate: LocalDate?,
        toDate: LocalDate,
    ): List<AccountMovement> =
        dsl
            .select(JOURNAL_LINE.GL_ACCOUNT_ID, debitSum(), creditSum())
            .from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.BRANCH_ID.eq(branchId))
            .and(window(fromDate, toDate))
            .groupBy(JOURNAL_LINE.GL_ACCOUNT_ID)
            .fetch { movement(it.value1(), it.value2(), it.value3()) }
            .filterNotNull()

    /**
     * One `LATERAL` per account over `idx_journal_line_account_date`.
     *
     * Driven by the chart, which is configuration and so bounded by design rather than by history,
     * and each correlated aggregate is a range scan over `(organisation_id, gl_account_id,
     * posting_date)` — the rows in the window for that account and nothing else. Accounts that
     * did not move are dropped here rather than returned as zeroes, so the result is the size of
     * the activity and not of the chart.
     *
     * The alternative — one aggregate with a bare `posting_date BETWEEN` predicate — has no
     * leading index column to range on for a tenant-wide scope, and degrades to reading the
     * tenant's whole ledger to answer a question about one month of it.
     */
    private fun tenantWideMovements(
        organisationId: UUID,
        fromDate: LocalDate?,
        toDate: LocalDate,
    ): List<AccountMovement> {
        val movement =
            DSL
                .select(debitSum(), creditSum())
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
                .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(GL_ACCOUNT.ID))
                .and(window(fromDate, toDate))
                .asTable(MOVEMENT)
        return dsl
            .select(
                GL_ACCOUNT.ID,
                movement.field(DEBIT, BigDecimal::class.java),
                movement.field(CREDIT, BigDecimal::class.java),
            ).from(GL_ACCOUNT)
            .crossJoin(DSL.lateral(movement))
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .fetch { movement(it.value1(), it.value2(), it.value3()) }
            .filterNotNull()
    }

    @Suppress("LongParameterList")
    override fun movementsForAccount(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
        after: LedgerCursor?,
        pageSize: Int,
    ): List<LedgerMovement> =
        dsl
            .select(
                JOURNAL_LINE.ID,
                JOURNAL_LINE.JOURNAL_ENTRY_ID,
                JOURNAL_ENTRY.ENTRY_NUMBER,
                JOURNAL_ENTRY.ENTRY_TYPE,
                JOURNAL_LINE.POSTING_DATE,
                JOURNAL_LINE.BRANCH_ID,
                JOURNAL_LINE.DIRECTION,
                JOURNAL_LINE.FUNCTIONAL_AMOUNT,
                JOURNAL_LINE.NARRATIVE,
                JOURNAL_LINE.SOURCE_MODULE,
                JOURNAL_LINE.SUBLEDGER_REFERENCE,
            ).from(JOURNAL_LINE)
            .join(JOURNAL_ENTRY)
            .on(JOURNAL_ENTRY.ORGANISATION_ID.eq(JOURNAL_LINE.ORGANISATION_ID))
            .and(JOURNAL_ENTRY.ID.eq(JOURNAL_LINE.JOURNAL_ENTRY_ID))
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
            .and(window(fromDate, toDate))
            .and(branchId?.let { JOURNAL_LINE.BRANCH_ID.eq(it) } ?: DSL.noCondition())
            .and(keysetAfter(after))
            .orderBy(JOURNAL_LINE.POSTING_DATE.asc(), JOURNAL_LINE.ID.asc())
            .limit(pageSize)
            .fetch(::toMovement)

    override fun journalByEntryNumber(
        organisationId: UUID,
        entryNumber: Long,
    ): JournalDetail? = journal(organisationId, JOURNAL_ENTRY.ENTRY_NUMBER.eq(entryNumber))

    override fun journalById(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalDetail? = journal(organisationId, JOURNAL_ENTRY.ID.eq(journalEntryId))

    /**
     * The header, its source and both halves of its reversal linkage, in one statement.
     *
     * The forward link is the `reverses_journal_entry_id` column; the backward one is a correlated
     * scalar over the same table, because a journal does not carry a pointer to the reversal that
     * may one day be written against it. `uq_journal_entry_reversal_once` makes that subquery
     * return at most one row, so it is a scalar by construction rather than by a `LIMIT` the
     * reader has to trust.
     */
    private fun journal(
        organisationId: UUID,
        identity: Condition,
    ): JournalDetail? {
        val reversal = JOURNAL_ENTRY.`as`(REVERSAL)
        val reversedBy =
            DSL
                .field(
                    DSL
                        .select(reversal.ID)
                        .from(reversal)
                        .where(reversal.ORGANISATION_ID.eq(organisationId))
                        .and(reversal.REVERSES_JOURNAL_ENTRY_ID.eq(JOURNAL_ENTRY.ID)),
                ).`as`(REVERSED_BY)
        val header =
            dsl
                .select(
                    JOURNAL_ENTRY.ID,
                    JOURNAL_ENTRY.ENTRY_NUMBER,
                    JOURNAL_ENTRY.ENTRY_TYPE,
                    JOURNAL_ENTRY.POSTING_DATE,
                    JOURNAL_ENTRY.BUSINESS_DATE,
                    JOURNAL_ENTRY.BRANCH_ID,
                    JOURNAL_ENTRY.TOTAL_DEBIT_FUNCTIONAL,
                    JOURNAL_ENTRY.TOTAL_CREDIT_FUNCTIONAL,
                    JOURNAL_ENTRY.REVERSES_JOURNAL_ENTRY_ID,
                    reversedBy,
                    POSTING_REQUEST.SOURCE_MODULE,
                    POSTING_REQUEST.SOURCE_ENTITY_TYPE,
                    POSTING_REQUEST.SOURCE_ENTITY_ID,
                    POSTING_REQUEST.SOURCE_REFERENCE,
                ).from(JOURNAL_ENTRY)
                .join(POSTING_REQUEST)
                .on(POSTING_REQUEST.ORGANISATION_ID.eq(JOURNAL_ENTRY.ORGANISATION_ID))
                .and(POSTING_REQUEST.ID.eq(JOURNAL_ENTRY.POSTING_REQUEST_ID))
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
                .and(identity)
                .fetchOne() ?: return null
        val journalEntryId = header.get(JOURNAL_ENTRY.ID)!!
        return JournalDetail(
            journalEntryId = journalEntryId,
            entryNumber = header.get(JOURNAL_ENTRY.ENTRY_NUMBER)!!,
            entryType = header.get(JOURNAL_ENTRY.ENTRY_TYPE)!!,
            postingDate = header.get(JOURNAL_ENTRY.POSTING_DATE)!!,
            businessDate = header.get(JOURNAL_ENTRY.BUSINESS_DATE)!!,
            branchId = header.get(JOURNAL_ENTRY.BRANCH_ID),
            totalDebit = header.get(JOURNAL_ENTRY.TOTAL_DEBIT_FUNCTIONAL)!!,
            totalCredit = header.get(JOURNAL_ENTRY.TOTAL_CREDIT_FUNCTIONAL)!!,
            reversesJournalEntryId = header.get(JOURNAL_ENTRY.REVERSES_JOURNAL_ENTRY_ID),
            reversedByJournalEntryId = header.get(reversedBy),
            sourceModule = header.get(POSTING_REQUEST.SOURCE_MODULE)!!,
            sourceEntityType = header.get(POSTING_REQUEST.SOURCE_ENTITY_TYPE)!!,
            sourceEntityId = header.get(POSTING_REQUEST.SOURCE_ENTITY_ID)!!,
            sourceReference = header.get(POSTING_REQUEST.SOURCE_REFERENCE)!!,
            lines = linesOf(organisationId, journalEntryId),
        )
    }

    /**
     * A journal's lines in line order, joined to their account codes.
     *
     * Bounded without a page and deliberately so: `chk_journal_entry_line_count` and the
     * append guard hold a journal to the line count its header declares, so "one journal's lines"
     * is a bounded read in a way that "an account's lines" never is.
     */
    private fun linesOf(
        organisationId: UUID,
        journalEntryId: UUID,
    ): List<JournalDetailLine> =
        dsl
            .select(
                JOURNAL_LINE.ID,
                JOURNAL_LINE.LINE_NUMBER,
                JOURNAL_LINE.GL_ACCOUNT_ID,
                GL_ACCOUNT.ACCOUNT_CODE,
                JOURNAL_LINE.BRANCH_ID,
                JOURNAL_LINE.DIRECTION,
                JOURNAL_LINE.FUNCTIONAL_AMOUNT,
                JOURNAL_LINE.NARRATIVE,
                JOURNAL_LINE.SOURCE_MODULE,
                JOURNAL_LINE.SUBLEDGER_REFERENCE,
            ).from(JOURNAL_LINE)
            .join(GL_ACCOUNT)
            .on(GL_ACCOUNT.ORGANISATION_ID.eq(JOURNAL_LINE.ORGANISATION_ID))
            .and(GL_ACCOUNT.ID.eq(JOURNAL_LINE.GL_ACCOUNT_ID))
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalEntryId))
            .orderBy(JOURNAL_LINE.LINE_NUMBER.asc())
            .fetch { record ->
                val debit = record.get(JOURNAL_LINE.DIRECTION) == DEBIT_DIRECTION
                val amount = record.get(JOURNAL_LINE.FUNCTIONAL_AMOUNT)!!
                JournalDetailLine(
                    lineId = record.get(JOURNAL_LINE.ID)!!,
                    lineNumber = record.get(JOURNAL_LINE.LINE_NUMBER)!!,
                    accountId = record.get(JOURNAL_LINE.GL_ACCOUNT_ID)!!,
                    accountCode = record.get(GL_ACCOUNT.ACCOUNT_CODE)!!,
                    branchId = record.get(JOURNAL_LINE.BRANCH_ID),
                    debit = if (debit) amount else BigDecimal.ZERO,
                    credit = if (debit) BigDecimal.ZERO else amount,
                    narrative = record.get(JOURNAL_LINE.NARRATIVE),
                    sourceModule = record.get(JOURNAL_LINE.SOURCE_MODULE)!!,
                    subledgerReference = record.get(JOURNAL_LINE.SUBLEDGER_REFERENCE),
                )
            }

    private fun toMovement(record: Record): LedgerMovement {
        val debit = record.get(JOURNAL_LINE.DIRECTION) == DEBIT_DIRECTION
        val amount = record.get(JOURNAL_LINE.FUNCTIONAL_AMOUNT)!!
        return LedgerMovement(
            lineId = record.get(JOURNAL_LINE.ID)!!,
            journalEntryId = record.get(JOURNAL_LINE.JOURNAL_ENTRY_ID)!!,
            entryNumber = record.get(JOURNAL_ENTRY.ENTRY_NUMBER)!!,
            entryType = record.get(JOURNAL_ENTRY.ENTRY_TYPE)!!,
            postingDate = record.get(JOURNAL_LINE.POSTING_DATE)!!,
            branchId = record.get(JOURNAL_LINE.BRANCH_ID),
            debit = if (debit) amount else BigDecimal.ZERO,
            credit = if (debit) BigDecimal.ZERO else amount,
            narrative = record.get(JOURNAL_LINE.NARRATIVE),
            sourceModule = record.get(JOURNAL_LINE.SOURCE_MODULE)!!,
            subledgerReference = record.get(JOURNAL_LINE.SUBLEDGER_REFERENCE),
        )
    }

    /**
     * `(posting_date, id) > (cursor.postingDate, cursor.lineId)`, as a row-value comparison.
     *
     * A row value rather than the expanded `date > d OR (date = d AND id > i)`, because the
     * expanded form is what silently drops or repeats rows on a day carrying many postings when a
     * reader gets one of the branches wrong, and PostgreSQL turns the row value into the same index
     * range either way.
     */
    private fun keysetAfter(after: LedgerCursor?): Condition =
        after?.let {
            DSL
                .row(JOURNAL_LINE.POSTING_DATE, JOURNAL_LINE.ID)
                .gt(DSL.row(it.postingDate, it.lineId))
        } ?: DSL.noCondition()

    private fun window(
        fromDate: LocalDate?,
        toDate: LocalDate,
    ): Condition =
        JOURNAL_LINE.POSTING_DATE
            .le(toDate)
            .and(fromDate?.let { JOURNAL_LINE.POSTING_DATE.ge(it) } ?: DSL.noCondition())

    /**
     * `SUM(functional_amount) FILTER (WHERE direction = 'DEBIT')`, coalesced to zero.
     *
     * A filtered aggregate rather than a `CASE`, so the two sides are one pass over the same rows,
     * and over the two columns the read-path indexes already carry in their `INCLUDE` lists.
     * Summing `signed_functional_amount` instead would be shorter and would cost a heap fetch per
     * row, because that generated column is in no index.
     */
    private fun debitSum() = sideSum(DEBIT_DIRECTION).`as`(DEBIT)

    private fun creditSum() = sideSum(CREDIT_DIRECTION).`as`(CREDIT)

    private fun sideSum(direction: String) =
        DSL.coalesce(
            DSL.sum(JOURNAL_LINE.FUNCTIONAL_AMOUNT).filterWhere(
                JOURNAL_LINE.DIRECTION.eq(direction),
            ),
            BigDecimal.ZERO,
        )

    /**
     * An account's movement, or null when it did not move.
     *
     * The `LATERAL` shape returns a row for every account in the chart, zero on both sides for the
     * ones with no lines in the window, and a zero-movement line is not a fact a trial balance
     * needs. `chk_journal_line_amount` makes every line's amount strictly positive, so zero on both
     * sides means *no rows* rather than rows that happened to cancel.
     */
    private fun movement(
        accountId: UUID?,
        debit: BigDecimal?,
        credit: BigDecimal?,
    ): AccountMovement? {
        val debits = debit ?: BigDecimal.ZERO
        val credits = credit ?: BigDecimal.ZERO
        return if (accountId == null || (debits.signum() == 0 && credits.signum() == 0)) {
            null
        } else {
            AccountMovement(accountId = accountId, debit = debits, credit = credits)
        }
    }

    private companion object {
        const val DEBIT_DIRECTION = "DEBIT"
        const val CREDIT_DIRECTION = "CREDIT"
        const val DEBIT = "debit"
        const val CREDIT = "credit"
        const val MOVEMENT = "movement"
        const val REVERSAL = "reversal"
        const val REVERSED_BY = "reversed_by"
    }
}
