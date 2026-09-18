package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.balances.AffectedSeries
import com.finaxis.platform.accounting.application.balances.BalanceKey
import com.finaxis.platform.accounting.application.balances.DailyBalanceRow
import com.finaxis.platform.accounting.application.balances.DailyBalanceStore
import com.finaxis.platform.accounting.application.balances.DailyMovement
import com.finaxis.platform.accounting.application.balances.LedgerMovementSource
import com.finaxis.platform.accounting.application.balances.OpeningChainBreak
import com.finaxis.platform.accounting.application.balances.ProjectionDrift
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_DAILY_BALANCE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.Record
import org.jooq.Table
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * The projection's persistence, and the `journal_line` aggregates it is rebuilt from.
 *
 * Both halves live here on purpose. The rebuild query is not an incidental detail of how the
 * projection is stored — it *is* the projection's definition (`INV-13`), and keeping the read of
 * the source beside the write of the derived rows makes a change to one that forgets the other
 * visible in one file.
 *
 * Every aggregate is bounded by tenant, account and a date, and is served by
 * `idx_journal_line_account_date`, whose `INCLUDE` list carries `direction`, `functional_amount`
 * and `branch_id` — so the grouping never leaves the index. `functional_currency_code` is
 * deliberately not grouped on anywhere except the drift proof: it is in neither the index key nor
 * its `INCLUDE` list, so a `GROUP BY` over it would force a heap fetch for every line in range.
 */
@Component
class JooqDailyBalanceStore(
    private val dsl: DSLContext,
) : DailyBalanceStore,
    LedgerMovementSource {
    override fun seriesAffectedBetween(
        organisationId: UUID,
        fromBusinessDate: LocalDate,
        toBusinessDate: LocalDate,
        functionalCurrency: String,
    ): List<AffectedSeries> =
        dsl
            .select(
                JOURNAL_LINE.GL_ACCOUNT_ID,
                JOURNAL_LINE.BRANCH_ID,
                DSL.min(JOURNAL_LINE.POSTING_DATE).`as`(EARLIEST),
            ).from(JOURNAL_LINE)
            .join(JOURNAL_ENTRY)
            .on(JOURNAL_LINE.ORGANISATION_ID.eq(JOURNAL_ENTRY.ORGANISATION_ID))
            .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(JOURNAL_ENTRY.ID))
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_ENTRY.BUSINESS_DATE.between(fromBusinessDate, toBusinessDate))
            .groupBy(JOURNAL_LINE.GL_ACCOUNT_ID, JOURNAL_LINE.BRANCH_ID)
            .fetch { record ->
                AffectedSeries(
                    key =
                        BalanceKey(
                            organisationId = organisationId,
                            accountId = record[JOURNAL_LINE.GL_ACCOUNT_ID]!!,
                            branchId = record[JOURNAL_LINE.BRANCH_ID],
                            currencyCode = functionalCurrency,
                        ),
                    fromDate = record.get(EARLIEST, LocalDate::class.java)!!,
                )
            }

    override fun lastRowBefore(
        key: BalanceKey,
        before: LocalDate,
    ): DailyBalanceRow? = latestRow(key, GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE.lt(before))

    override fun latestRowAsOf(
        key: BalanceKey,
        asOfDate: LocalDate,
    ): DailyBalanceRow? = latestRow(key, GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE.le(asOfDate))

    override fun seriesOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
    ): List<BalanceKey> =
        dsl
            .selectDistinct(
                GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID,
                GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE,
            ).from(GL_ACCOUNT_DAILY_BALANCE)
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(accountId))
            .and(
                branchId
                    ?.let { GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID.eq(it) }
                    ?: DSL.noCondition(),
            ).fetch { record ->
                BalanceKey(
                    organisationId = organisationId,
                    accountId = accountId,
                    branchId = record[GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID],
                    currencyCode = record[GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE]!!.trim(),
                )
            }

    override fun movementsFrom(
        key: BalanceKey,
        fromDate: LocalDate,
    ): List<DailyMovement> =
        dsl
            .select(
                JOURNAL_LINE.POSTING_DATE,
                debitSum(),
                creditSum(),
                DSL.count().`as`(LINES),
            ).from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(key.organisationId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(key.accountId))
            .and(JOURNAL_LINE.POSTING_DATE.ge(fromDate))
            .and(branchMatches(JOURNAL_LINE.BRANCH_ID, key.branchId))
            .groupBy(JOURNAL_LINE.POSTING_DATE)
            .orderBy(JOURNAL_LINE.POSTING_DATE)
            .fetch { record ->
                DailyMovement(
                    key = key,
                    postingDate = record[JOURNAL_LINE.POSTING_DATE]!!,
                    debitFunctional = record.get(DEBIT, BigDecimal::class.java) ?: BigDecimal.ZERO,
                    creditFunctional =
                        record.get(CREDIT, BigDecimal::class.java) ?: BigDecimal.ZERO,
                    lineCount = record.get(LINES, Int::class.java) ?: 0,
                )
            }

    override fun projectionWatermark(organisationId: UUID): LocalDate? =
        dsl
            .select(DSL.max(GL_ACCOUNT_DAILY_BALANCE.BUILT_FOR_BUSINESS_DATE))
            .from(GL_ACCOUNT_DAILY_BALANCE)
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId))
            .fetchOne(0, LocalDate::class.java)

    override fun signedMovement(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        afterDate: LocalDate,
        toDate: LocalDate,
    ): BigDecimal =
        dsl
            .select(
                DSL.coalesce(DSL.sum(JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT), BigDecimal.ZERO),
            ).from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
            .and(JOURNAL_LINE.POSTING_DATE.le(toDate))
            .and(JOURNAL_LINE.POSTING_DATE.gt(afterDate))
            .and(
                branchId
                    ?.let { JOURNAL_LINE.BRANCH_ID.eq(it) }
                    ?: DSL.noCondition(),
            ).fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO

    /**
     * `MIN(posting_date)` over the journals recorded after the watermark.
     *
     * The header table and not `journal_line`, because `business_date` lives only on the header -
     * a line denormalises the columns its read-path indexes are keyed on and nothing else. With a
     * null watermark the predicate is dropped and the answer is the tenant's first posting date,
     * which is the right answer for a tenant whose projection has never been built.
     */
    override fun earliestPostingDateRecordedAfter(
        organisationId: UUID,
        afterBusinessDate: LocalDate?,
    ): LocalDate? =
        dsl
            .select(DSL.min(JOURNAL_ENTRY.POSTING_DATE))
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
            .and(
                afterBusinessDate
                    ?.let { JOURNAL_ENTRY.BUSINESS_DATE.ge(it) }
                    ?: DSL.noCondition(),
            ).fetchOne(0, LocalDate::class.java)

    override fun journalSeriesOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        functionalCurrency: String,
    ): List<BalanceKey> =
        dsl
            .selectDistinct(JOURNAL_LINE.BRANCH_ID)
            .from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
            .and(
                branchId
                    ?.let { JOURNAL_LINE.BRANCH_ID.eq(it) }
                    ?: DSL.noCondition(),
            ).fetch { record ->
                BalanceKey(
                    organisationId = organisationId,
                    accountId = accountId,
                    branchId = record[JOURNAL_LINE.BRANCH_ID],
                    currencyCode = functionalCurrency,
                )
            }

    override fun movementsForAccountFrom(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        functionalCurrency: String,
    ): List<DailyMovement> =
        dsl
            .select(
                JOURNAL_LINE.BRANCH_ID,
                JOURNAL_LINE.POSTING_DATE,
                debitSum(),
                creditSum(),
                DSL.count().`as`(LINES),
            ).from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
            .and(JOURNAL_LINE.POSTING_DATE.ge(fromDate))
            .groupBy(JOURNAL_LINE.BRANCH_ID, JOURNAL_LINE.POSTING_DATE)
            .orderBy(JOURNAL_LINE.POSTING_DATE)
            .fetch { record ->
                DailyMovement(
                    key =
                        BalanceKey(
                            organisationId = organisationId,
                            accountId = accountId,
                            branchId = record[JOURNAL_LINE.BRANCH_ID],
                            currencyCode = functionalCurrency,
                        ),
                    postingDate = record[JOURNAL_LINE.POSTING_DATE]!!,
                    debitFunctional = record.get(DEBIT, BigDecimal::class.java) ?: BigDecimal.ZERO,
                    creditFunctional =
                        record.get(CREDIT, BigDecimal::class.java) ?: BigDecimal.ZERO,
                    lineCount = record.get(LINES, Int::class.java) ?: 0,
                )
            }

    override fun earliestBusinessDate(organisationId: UUID): LocalDate? =
        dsl
            .select(DSL.min(JOURNAL_ENTRY.BUSINESS_DATE))
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
            .fetchOne(0, LocalDate::class.java)

    override fun signedBalanceBefore(
        key: BalanceKey,
        before: LocalDate,
    ): BigDecimal =
        dsl
            .select(
                DSL.coalesce(DSL.sum(JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT), BigDecimal.ZERO),
            ).from(JOURNAL_LINE)
            .where(JOURNAL_LINE.ORGANISATION_ID.eq(key.organisationId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(key.accountId))
            .and(JOURNAL_LINE.POSTING_DATE.lt(before))
            .and(branchMatches(JOURNAL_LINE.BRANCH_ID, key.branchId))
            .fetchOne(0, BigDecimal::class.java) ?: BigDecimal.ZERO

    override fun deleteFrom(
        key: BalanceKey,
        fromDate: LocalDate,
    ): Int =
        dsl
            .deleteFrom(GL_ACCOUNT_DAILY_BALANCE)
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(key.organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(key.accountId))
            .and(branchMatches(GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID, key.branchId))
            .and(GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE.eq(key.currencyCode))
            .and(GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE.ge(fromDate))
            .execute()

    override fun insertAll(
        rows: List<DailyBalanceRow>,
        builtAt: Instant,
        builtForBusinessDate: LocalDate,
    ) {
        if (rows.isEmpty()) {
            return
        }
        val stamped = OffsetDateTime.ofInstant(builtAt, ZoneOffset.UTC)
        // One prepared statement executed once per row, rather than a multi-row VALUES list built
        // by calling `values(...)` in a loop and discarding what it returns. jOOQ's builder happens
        // to be mutable, so the discarding form works today; a batch says what it means and does
        // not depend on that.
        dsl
            .batch(
                dsl
                    .insertInto(
                        GL_ACCOUNT_DAILY_BALANCE,
                        GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID,
                        GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID,
                        GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID,
                        GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE,
                        GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE,
                        GL_ACCOUNT_DAILY_BALANCE.OPENING_SIGNED_FUNCTIONAL,
                        GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL,
                        GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL,
                        GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT,
                        GL_ACCOUNT_DAILY_BALANCE.BUILT_AT,
                        GL_ACCOUNT_DAILY_BALANCE.BUILT_FOR_BUSINESS_DATE,
                    ).values(
                        null as UUID?,
                        null as UUID?,
                        null as UUID?,
                        null as String?,
                        null as LocalDate?,
                        null as BigDecimal?,
                        null as BigDecimal?,
                        null as BigDecimal?,
                        null as Int?,
                        null as OffsetDateTime?,
                        null as LocalDate?,
                    ),
            ).let { template ->
                rows.fold(template) { batch, row ->
                    batch.bind(
                        row.key.organisationId,
                        row.key.accountId,
                        row.key.branchId,
                        row.key.currencyCode,
                        row.postingDate,
                        row.openingSignedFunctional,
                        row.debitFunctional,
                        row.creditFunctional,
                        row.lineCount,
                        stamped,
                        builtForBusinessDate,
                    )
                }
            }.execute()
    }

    /**
     * The proof, as a `FULL OUTER JOIN` between the stored rows and the rebuild aggregate.
     *
     * The join has to be full rather than inner because the two failure directions are opposite: a
     * day the journal has and the projection does not is a build that never ran, and a day the
     * projection has and the journal does not is a phantom that would be added into a balance. An
     * inner join sees neither, and would report a sound projection for both.
     *
     * This is the one place `functional_currency_code` **is** grouped on. A proof runs per period
     * rather than per rollover, so it can afford the heap fetch, and it is where a tenant that
     * somehow held two functional currencies would show up as a mismatch rather than as a silently
     * merged sum.
     */
    override fun openingChainBreaks(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<OpeningChainBreak> =
        dsl
            .resultQuery(
                """
                WITH chained AS (
                    SELECT branch_id,
                           currency_code,
                           posting_date,
                           opening_signed_functional,
                           COALESCE(
                               LAG(closing_signed_functional) OVER (
                                   PARTITION BY branch_id, currency_code
                                   ORDER BY posting_date
                               ),
                               -- Scaled like the column it stands in for: a bare 0 comes back at
                               -- scale 0, and BigDecimal.equals is scale-sensitive, so a caller
                               -- comparing a genuine zero opening against this would disagree.
                               0::NUMERIC(23, 6)
                           ) AS expected_opening
                    FROM gl_account_daily_balance
                    WHERE organisation_id = ?
                      AND gl_account_id = ?
                      AND posting_date <= ?
                )
                SELECT branch_id, currency_code, posting_date,
                       opening_signed_functional, expected_opening
                FROM chained
                WHERE posting_date >= ?
                  AND opening_signed_functional IS DISTINCT FROM expected_opening
                ORDER BY branch_id, currency_code, posting_date
                """.trimIndent(),
                organisationId,
                accountId,
                toDate,
                fromDate,
            ).fetch { record ->
                OpeningChainBreak(
                    key =
                        BalanceKey(
                            organisationId = organisationId,
                            accountId = accountId,
                            branchId = record.get("branch_id", UUID::class.java),
                            currencyCode = record.get("currency_code", String::class.java)!!,
                        ),
                    postingDate = record.get("posting_date", LocalDate::class.java)!!,
                    recordedOpening =
                        record.get("opening_signed_functional", BigDecimal::class.java)!!,
                    expectedOpening = record.get("expected_opening", BigDecimal::class.java)!!,
                )
            }

    override fun drift(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<ProjectionDrift> {
        val stored = storedSide(organisationId, accountId, fromDate, toDate)
        val rebuilt = rebuiltSide(organisationId, accountId, fromDate, toDate)
        val sides = DriftSides(stored, rebuilt)
        return dsl
            .select(sides.selected)
            .from(stored)
            .fullOuterJoin(rebuilt)
            // Plain equality on every condition, and a null-free branch key to make that possible.
            // PostgreSQL rejects a FULL JOIN whose conditions are not hash- or merge-joinable, and
            // `IS NOT DISTINCT FROM` is neither - so the obvious way to write this proof does not
            // execute at all. Coalescing the nullable branch to a sentinel keeps head office
            // matching head office without it.
            .on(sides.storedBranchKey.eq(sides.journalBranchKey))
            .and(sides.storedCurrency.eq(sides.journalCurrency))
            .and(sides.storedDate.eq(sides.journalDate))
            .where(sides.disagreement)
            .fetch { sides.toDrift(organisationId, accountId, it) }
    }

    /**
     * The two sides of the proof's join, with their fields resolved once.
     *
     * A holder rather than a dozen locals inside [drift], because the same twelve fields are needed
     * three times over - in the projection, in the join predicate and in the mapper - and repeating
     * `stored.field(...)` at each site is where a mismatched pair would hide.
     */
    private class DriftSides(
        stored: Table<*>,
        rebuilt: Table<*>,
    ) {
        val storedBranch = stored.field(GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID)!!
        val storedBranchKey = stored.field(BRANCH_KEY, UUID::class.java)!!
        val storedCurrency = stored.field(GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE)!!
        val storedDate = stored.field(GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE)!!
        val storedDebit = stored.field(GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL)!!
        val storedCredit = stored.field(GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL)!!
        val storedLines = stored.field(GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT)!!
        val journalBranch = rebuilt.field(JOURNAL_LINE.BRANCH_ID)!!
        val journalBranchKey = rebuilt.field(BRANCH_KEY, UUID::class.java)!!
        val journalCurrency = rebuilt.field(JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE)!!
        val journalDate = rebuilt.field(JOURNAL_LINE.POSTING_DATE)!!
        val journalDebit = rebuilt.field(DEBIT, BigDecimal::class.java)!!
        val journalCredit = rebuilt.field(CREDIT, BigDecimal::class.java)!!
        val journalLines = rebuilt.field(LINES, Int::class.java)!!

        /** Every field the proof returns, in the order the mapper reads them. */
        val selected =
            listOf(
                storedBranch,
                storedCurrency,
                storedDate,
                storedDebit,
                storedCredit,
                storedLines,
                journalBranch,
                journalCurrency,
                journalDate,
                journalDebit,
                journalCredit,
                journalLines,
            )

        /**
         * The three ways the two sides can disagree.
         *
         * The first two disjuncts are what a plain inner join would lose: a day the journal has and
         * the projection does not is a build that never ran, and a day the projection has and the
         * journal does not is a phantom that would be added into a balance.
         */
        val disagreement: Condition =
            storedDate
                .isNull
                .or(journalDate.isNull)
                .or(storedDebit.ne(journalDebit))
                .or(storedCredit.ne(journalCredit))
                .or(storedLines.ne(journalLines))

        /** One disagreeing row, taking its identity from whichever side produced it. */
        fun toDrift(
            organisationId: UUID,
            accountId: UUID,
            record: Record,
        ) = ProjectionDrift(
            key =
                BalanceKey(
                    organisationId = organisationId,
                    accountId = accountId,
                    branchId = record[storedBranch] ?: record[journalBranch],
                    currencyCode = (record[storedCurrency] ?: record[journalCurrency])!!.trim(),
                ),
            postingDate = (record[storedDate] ?: record[journalDate])!!,
            projectedDebit = record[storedDebit],
            projectedCredit = record[storedCredit],
            projectedLineCount = record[storedLines],
            journalDebit = record[journalDebit],
            journalCredit = record[journalCredit],
            journalLineCount = record[journalLines],
        )
    }

    /**
     * The journal aggregate the projection is supposed to equal, as a derived table.
     *
     * This is the one place `functional_currency_code` **is** grouped on. A proof runs per period
     * rather than per rollover, so it can afford the heap fetch the grouping costs, and it is where
     * a tenant that somehow held two functional currencies would surface as a mismatch rather than
     * as a silently merged sum.
     */
    private fun rebuiltSide(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
    ) = dsl
        .select(
            JOURNAL_LINE.BRANCH_ID,
            DSL.coalesce(JOURNAL_LINE.BRANCH_ID, HEAD_OFFICE_KEY).`as`(BRANCH_KEY),
            JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE,
            JOURNAL_LINE.POSTING_DATE,
            debitSum(),
            creditSum(),
            DSL.count().`as`(LINES),
        ).from(JOURNAL_LINE)
        .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
        .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(accountId))
        .and(JOURNAL_LINE.POSTING_DATE.between(fromDate, toDate))
        .groupBy(
            JOURNAL_LINE.BRANCH_ID,
            JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE,
            JOURNAL_LINE.POSTING_DATE,
        ).asTable(DSL.name("journal"))

    /** The projected rows over the same bounded range, as a derived table. */
    private fun storedSide(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
    ) = dsl
        .select(
            GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID,
            DSL.coalesce(GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID, HEAD_OFFICE_KEY).`as`(BRANCH_KEY),
            GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE,
            GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE,
            GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL,
            GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL,
            GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT,
        ).from(GL_ACCOUNT_DAILY_BALANCE)
        .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId))
        .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(accountId))
        .and(GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE.between(fromDate, toDate))
        .asTable(DSL.name("projected"))

    /**
     * `branch_id = ?` for a named branch, `branch_id IS NULL` for head office.
     *
     * **Never `IS NOT DISTINCT FROM`**, which expresses the same intent in one arm and cannot
     * qualify a btree index. PostgreSQL parses it as a `DistinctExpr` rather than an indexable
     * operator clause, so the branch drops out of the `Index Cond` and is re-checked as a heap
     * filter; every key column after it then stops being an equality-bound prefix, and the
     * `ORDER BY posting_date DESC LIMIT 1` probe that should stop at the first row degrades into a
     * scan and sort of the account's whole cross-branch, cross-currency history. Measured against a
     * 328k-row projection: 4 shared buffers for this form, 820 and a top-N heapsort for the other,
     * with 14,680 rows discarded by the filter. The cost grows with retention — the exact cost
     * `gl_account_daily_balance` exists to remove.
     *
     * The two arms are disjoint and total, because `NULLS NOT DISTINCT` on
     * `uq_gl_account_daily_balance_key` makes a null branch one key rather than many.
     *
     * One honest limit: the head-office arm *is* index-qualifiable, but `IS NULL` is not an
     * equality clause for pathkey purposes, so that arm still sorts the series rather than reading
     * backwards through it. It is bounded by one series' history instead of the account's whole
     * cross-branch history, which is the property the checkpoint depends on.
     */
    private fun branchMatches(
        field: Field<UUID?>,
        branchId: UUID?,
    ): Condition = branchId?.let { field.eq(it) } ?: field.isNull

    private fun latestRow(
        key: BalanceKey,
        dateBound: org.jooq.Condition,
    ): DailyBalanceRow? =
        dsl
            .select(
                GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE,
                GL_ACCOUNT_DAILY_BALANCE.OPENING_SIGNED_FUNCTIONAL,
                GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL,
                GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL,
                GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT,
            ).from(GL_ACCOUNT_DAILY_BALANCE)
            .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(key.organisationId))
            .and(GL_ACCOUNT_DAILY_BALANCE.GL_ACCOUNT_ID.eq(key.accountId))
            .and(branchMatches(GL_ACCOUNT_DAILY_BALANCE.BRANCH_ID, key.branchId))
            .and(GL_ACCOUNT_DAILY_BALANCE.CURRENCY_CODE.eq(key.currencyCode))
            .and(dateBound)
            .orderBy(GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE.desc())
            .limit(1)
            .fetchOne { record: Record ->
                DailyBalanceRow(
                    key = key,
                    postingDate = record[GL_ACCOUNT_DAILY_BALANCE.POSTING_DATE]!!,
                    openingSignedFunctional =
                        record[GL_ACCOUNT_DAILY_BALANCE.OPENING_SIGNED_FUNCTIONAL]!!,
                    debitFunctional = record[GL_ACCOUNT_DAILY_BALANCE.DEBIT_FUNCTIONAL]!!,
                    creditFunctional = record[GL_ACCOUNT_DAILY_BALANCE.CREDIT_FUNCTIONAL]!!,
                    lineCount = record[GL_ACCOUNT_DAILY_BALANCE.LINE_COUNT]!!,
                )
            }

    private companion object {
        const val EARLIEST = "earliest"
        const val BRANCH_KEY = "branch_key"

        /**
         * The stand-in for a null `branch_id` in the proof's join key.
         *
         * The nil UUID, and safe as a sentinel because it cannot collide with a real branch: every
         * identifier in this schema is a `uuidv7`, whose leading 48 bits are a millisecond
         * timestamp and so are never all zero.
         */
        val HEAD_OFFICE_KEY: UUID = UUID(0L, 0L)
        const val DEBIT = "debit_functional"
        const val CREDIT = "credit_functional"
        const val LINES = "line_count"

        fun debitSum() =
            DSL
                .coalesce(
                    DSL.sum(JOURNAL_LINE.FUNCTIONAL_AMOUNT).filterWhere(
                        JOURNAL_LINE.DIRECTION.eq("DEBIT"),
                    ),
                    BigDecimal.ZERO,
                ).`as`(DEBIT)

        fun creditSum() =
            DSL
                .coalesce(
                    DSL.sum(JOURNAL_LINE.FUNCTIONAL_AMOUNT).filterWhere(
                        JOURNAL_LINE.DIRECTION.eq("CREDIT"),
                    ),
                    BigDecimal.ZERO,
                ).`as`(CREDIT)
    }
}
