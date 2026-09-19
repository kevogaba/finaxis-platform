package com.finaxis.platform.accounting.application.balances

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * The identity of one projected series: an account, a branch and a currency.
 *
 * A *key* in the sense the projection's unique constraint means it, minus the date. Every balance
 * this table answers is a balance of one key, and a rebuild recomputes one key's rows from a date
 * forward, so the type exists to make "one key" impossible to lose track of.
 *
 * [branchId] is nullable and the null is **head office**, not a wildcard — the same meaning
 * `journal_line.branch_id` carries. This is the opposite of what a null branch means to
 * [com.finaxis.platform.accounting.application.reconciliation.LedgerBalanceQuery], where it means
 * every branch; the two are reconciled in [DailyBalanceReader], deliberately in one place.
 */
data class BalanceKey(
    val organisationId: UUID,
    val accountId: UUID,
    val branchId: UUID?,
    val currencyCode: String,
)

/** One projected day of one key, as the store reads and writes it. */
data class DailyBalanceRow(
    val key: BalanceKey,
    val postingDate: LocalDate,
    val openingSignedFunctional: BigDecimal,
    val debitFunctional: BigDecimal,
    val creditFunctional: BigDecimal,
    val lineCount: Int,
) {
    /** `opening + debit - credit`, the value the database stores as a generated column. */
    val closingSignedFunctional: BigDecimal
        get() = openingSignedFunctional.add(debitFunctional).subtract(creditFunctional)
}

/**
 * One day's movement for one key, as the rebuild query returns it.
 *
 * Carries no opening balance: the aggregate over `journal_line` knows what moved on a day and
 * nothing about what came before it. Chaining movements into [DailyBalanceRow]s is the service's
 * job, because that is where the previous closing balance is known.
 */
data class DailyMovement(
    val key: BalanceKey,
    val postingDate: LocalDate,
    val debitFunctional: BigDecimal,
    val creditFunctional: BigDecimal,
    val lineCount: Int,
)

/** A key the build must recompute, and the earliest posting date it must recompute it from. */
data class AffectedSeries(
    val key: BalanceKey,
    val fromDate: LocalDate,
)

/** One row on which the projection and the journal disagree, with both sides shown. */
data class ProjectionDrift(
    val key: BalanceKey,
    val postingDate: LocalDate,
    val projectedDebit: BigDecimal?,
    val projectedCredit: BigDecimal?,
    val projectedLineCount: Int?,
    val journalDebit: BigDecimal?,
    val journalCredit: BigDecimal?,
    val journalLineCount: Int?,
) {
    /** What kind of disagreement this is, for the message an operator reads. */
    val kind: DriftKind
        get() =
            when {
                projectedDebit == null -> DriftKind.MISSING_FROM_PROJECTION
                journalDebit == null -> DriftKind.ABSENT_FROM_JOURNAL
                else -> DriftKind.AMOUNTS_DISAGREE
            }
}

/**
 * The three ways a projection row and the journal can fail to agree.
 *
 * Named separately because the proof query is a `FULL OUTER JOIN` precisely so that the first two
 * are visible: an inner join would see neither a build that never ran nor a row the journal has no
 * lines for, and those are opposite failures with opposite repairs.
 */
enum class DriftKind {
    /** The journal has lines for this key and day; the projection has no row. A missed build. */
    MISSING_FROM_PROJECTION,

    /** The projection has a row; the journal has none. A phantom that would inflate a balance. */
    ABSENT_FROM_JOURNAL,

    /** Both exist and their debits, credits or line counts differ. */
    AMOUNTS_DISAGREE,
}

/**
 * One projected row whose opening balance does not continue its series.
 *
 * Separate from [ProjectionDrift] because it is a different question asked of different data. Drift
 * compares the projection against the journal a day at a time; this compares the projection against
 * *itself*, and needs no journal access at all.
 *
 * It exists because the day-at-a-time comparison cannot see this class of error. A carried-forward
 * opening makes every row depend on the one before it, so a single wrong opening — or a first row
 * that starts from something other than zero — shifts every later closing balance by a constant
 * while leaving each day's debit, credit and line count exactly right. The drift proof would pass
 * over a projection whose every balance is wrong, and [DailyBalanceReader] would take one of those
 * closing balances as its checkpoint.
 */
data class OpeningChainBreak(
    val key: BalanceKey,
    val postingDate: LocalDate,
    val recordedOpening: BigDecimal,
    val expectedOpening: BigDecimal,
)

/**
 * The **journal** side of the projection: everything the rebuild reads from `journal_line`.
 *
 * Separate from [DailyBalanceStore] because the two halves are the distinction this whole design
 * rests on — one is the append-only system of record and the other is a derived structure that can
 * be thrown away and rebuilt from it. A single port over both invites a method that quietly reads
 * the projection where it meant to read the journal, which is how a rebuild comes to verify itself.
 *
 * Every read is tenant-scoped and bounded, as `INV-15` requires, and every aggregate is served by
 * `idx_journal_line_account_date`.
 */
interface LedgerMovementSource {
    /**
     * The keys [organisationId] moved on the business dates in `[fromBusinessDate,
     * toBusinessDate]`, with the earliest posting date each was moved on.
     *
     * Enumerates through `journal_entry.business_date`, which is the only column that sees a
     * backdated correction: a journal recorded on one of these business dates into an earlier
     * posting date carries that earlier date on its lines and this business date on its header.
     *
     * **A range rather than one day, in one indexed scan.** The build has to cover every business
     * date it has not settled, not just the one it was called for: an enqueue lost between the
     * advance committing and the callback running leaves a day nobody will otherwise revisit, and
     * once the watermark passes it the delta query can no longer reach it. A range closes that
     * with the same `(organisation_id, business_date, id)` index prefix a single day used, and
     * replaces the per-day loop that repeated one query per day of catch-up.
     *
     * [functionalCurrency] is supplied rather than grouped on. It is not in the key or the
     * `INCLUDE` list of `idx_journal_line_account_date`, so grouping by it would cost a heap fetch
     * per line; and it cannot differ within a tenant, because the functional currency is frozen
     * the moment that tenant posts its first journal. The drift proof does group by it, which is
     * where a future multi-currency change would surface.
     */
    fun seriesAffectedBetween(
        organisationId: UUID,
        fromBusinessDate: LocalDate,
        toBusinessDate: LocalDate,
        functionalCurrency: String,
    ): List<AffectedSeries>

    /**
     * The tenant's earliest `journal_entry.business_date`, or null when it has never posted.
     *
     * The floor of a cold-start build. A projection created over a journal that already holds
     * history has no watermark to catch up from, and starting at the current business date would
     * leave every earlier day unprojected while the watermark claimed otherwise.
     */
    fun earliestBusinessDate(organisationId: UUID): LocalDate?

    /**
     * `SUM(signed_functional_amount)` for one key over every line dated strictly before [before].
     *
     * The opening balance a series starts from when the projection holds no earlier row for it —
     * which is every series on a cold start, and any series whose rebuild reaches behind the
     * oldest row it has. Assuming zero there is the more obvious choice and is **wrong**: it
     * produces a chain that is internally consistent and understated by exactly the history it
     * ignored, and neither proof would catch it, because both compare a day's debit, credit and
     * line totals and an opening offset leaves all three untouched.
     *
     * Unlike [signedMovement] the branch is part of the key rather than a filter, so a null
     * [BalanceKey.branchId] means **head office** and not every branch.
     */
    fun signedBalanceBefore(
        key: BalanceKey,
        before: LocalDate,
    ): BigDecimal

    /**
     * The keys [accountId] has actually moved in the journal, optionally within one branch.
     *
     * The journal's answer to the question [DailyBalanceStore.seriesOf] answers from the
     * projection, and the one a repair must ask when the projection holds nothing for an account:
     * a null [branchId] there is a *wildcard* meaning every branch, which cannot be turned into a
     * [BalanceKey] whose null branch means head office. Synthesising one key from the wildcard
     * rebuilds head office alone and reports success, leaving every branch series missing.
     *
     * Bounded by the account's branches, which configuration bounds to a few dozen.
     */
    fun journalSeriesOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        functionalCurrency: String,
    ): List<BalanceKey>

    /** The day-by-day movement of one key from [fromDate] onwards, from `journal_line`. */
    fun movementsFrom(
        key: BalanceKey,
        fromDate: LocalDate,
    ): List<DailyMovement>

    /**
     * The day-by-day movement of **every branch series** of one account from [fromDate] onwards,
     * in one grouped scan.
     *
     * [movementsFrom] filtered to a branch is the obvious way to rebuild an account that moved in
     * several branches on a day, and it is quadratic in the wrong place: `branch_id` is an
     * `INCLUDE` payload of `idx_journal_line_account_date` and not a key column, so each branch's
     * call rescans the account's whole date tail and discards the other branches. An account
     * present in forty branches pays forty scans of the same range to read it once.
     *
     * Grouping by `(branch_id, posting_date)` in a single scan reads that range once and lets the
     * caller partition the result, which is also the shape `AccountingQueryPlanTests` budgets.
     */
    fun movementsForAccountFrom(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        functionalCurrency: String,
    ): List<DailyMovement>

    /**
     * `SUM(signed_functional_amount)` over `journal_line` for the account, after [afterDate]
     * exclusive and up to [toDate] inclusive.
     *
     * The **delta** of a checkpoint-plus-delta read. A null [branchId] applies no branch predicate,
     * as the port it serves requires.
     */
    fun signedMovement(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        afterDate: LocalDate,
        toDate: LocalDate,
    ): BigDecimal

    /**
     * The earliest posting date touched by any journal recorded **on or after**
     * [afterBusinessDate], or null when none has been; every journal the tenant has, when it is
     * null.
     *
     * *On or after*, and the inclusive bound is deliberate. The watermark's own business date is
     * not safely closed: a backdated posting takes no lock on `business_date`, so one that read
     * `B` before the advance can commit after the build for `B` has already queried it. Excluding
     * `B` would leave that journal in neither the checkpoint nor the delta. Including it can only
     * retreat the checkpoint one business day further than strictly necessary, which costs a
     * slightly wider delta and never a wrong answer.
     *
     * This is what makes a checkpoint-plus-delta read exact rather than merely fresh, and it exists
     * because a posting's business date and its posting date are different facts. The projection is
     * complete for the journals recorded up to the watermark and for no others, so a journal
     * recorded since — a **backdated** one especially — may carry a posting date earlier than any
     * checkpoint the projection offers. A reader that bounded its delta by the latest projected
     * posting date would place its checkpoint after that posting and never see it: the balance
     * would silently omit a line the journal holds, and a control-account reconciliation would
     * report the difference as a break in the sub-ledger.
     *
     * The answer minus one day is therefore the latest date a checkpoint may safely be taken at.
     * Index-only over `idx_journal_entry_business_date`, whose `INCLUDE (posting_date)` exists for
     * this aggregate, and bounded by the journals recorded since the last build rather than by the
     * tenant's history.
     */
    fun earliestPostingDateRecordedAfter(
        organisationId: UUID,
        afterBusinessDate: LocalDate?,
    ): LocalDate?
}

/**
 * Write and read port over `gl_account_daily_balance` itself.
 *
 * Every method is tenant-scoped and every read is bounded, as `INV-15` requires of an accounting
 * query. There is deliberately no *"all rows for this tenant"* method: an unbounded projection scan
 * is how a rebuild quietly becomes a full-table operation.
 */
interface DailyBalanceStore {
    /** The key's last projected row strictly before [before], or null when it has none. */
    fun lastRowBefore(
        key: BalanceKey,
        before: LocalDate,
    ): DailyBalanceRow?

    /** The key's latest projected row on or before [asOfDate], or null when it has none. */
    fun latestRowAsOf(
        key: BalanceKey,
        asOfDate: LocalDate,
    ): DailyBalanceRow?

    /**
     * Every key of [organisationId] that has ever been projected for [accountId], optionally in
     * one branch.
     *
     * Bounded by the chart and the branch list rather than by the journal: a tenant holds 500 to
     * 2,000 accounts and at most a few dozen branches, so this is small by construction. It is what
     * lets a tenant-wide as-of balance be a sum over the key space rather than a scan of the table.
     */
    fun seriesOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
    ): List<BalanceKey>

    /**
     * The latest business date this tenant's build has settled, or null when nothing is projected.
     *
     * The **watermark**: the projection holds exactly the journals recorded on or before it. It is
     * read from the rows themselves rather than from a counter, so it cannot disagree with what is
     * actually stored — a build that failed halfway leaves a watermark that reflects the rows it
     * managed to write, and the as-of read is conservative in the right direction as a result.
     *
     * A per-series watermark would be tighter and is deliberately not used: a series the build did
     * not touch on a business date is one that had no journals on it, so the tenant-wide maximum is
     * sound for every series, and one aggregate is cheaper than one per key.
     *
     * One backward scan of `idx_gl_account_daily_balance_watermark`, stopping at the first row.
     */
    fun projectionWatermark(organisationId: UUID): LocalDate?

    /** Removes the key's rows from [fromDate] onwards, so the rebuild can replace them. */
    fun deleteFrom(
        key: BalanceKey,
        fromDate: LocalDate,
    ): Int

    /** Inserts recomputed rows in date order, stamped with when and for which day. */
    fun insertAll(
        rows: List<DailyBalanceRow>,
        builtAt: Instant,
        builtForBusinessDate: LocalDate,
    )

    /**
     * Rows of one account whose opening balance does not equal the previous row's closing balance,
     * with the first row of each series required to open at zero.
     *
     * **A sound projection returns an empty list.** The window runs over each series' rows up to
     * [toDate] rather than from [fromDate], because the predecessor of the first row *in a range*
     * lies outside it: bounding below would compare that row against nothing and report every
     * healthy series as broken. Only rows on or after [fromDate] are returned.
     */
    fun openingChainBreaks(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<OpeningChainBreak>

    /**
     * Rows on which the projection and the journal disagree, for one account over one date range.
     *
     * Bounded by account and by range for the same reason the header-versus-lines proof is bounded
     * by period: it has to be runnable on a 1.4-billion-row table without being a full scan.
     * **A sound projection returns an empty list.**
     */
    fun drift(
        organisationId: UUID,
        accountId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
    ): List<ProjectionDrift>
}

/**
 * Serialises daily-balance builds for one tenant.
 *
 * Coarse on purpose, and right here for the reason the chart-hierarchy lock is coarse: this is a
 * background administrative path and not the posting path, so serialising a tenant's rebuilds costs
 * nothing measurable. Without it a retry and a scheduled build can interleave on one key, and since
 * a rebuild deletes a key's tail before reinserting it, the interleaving window is one in which the
 * key has no rows at all.
 *
 * Released when the caller's transaction ends, so a failed build frees it with no unlock to forget.
 */
fun interface DailyBalanceProjectionLock {
    /** Blocks until this tenant's other builds have finished, for the current transaction. */
    fun lockForBuild(organisationId: UUID)
}
