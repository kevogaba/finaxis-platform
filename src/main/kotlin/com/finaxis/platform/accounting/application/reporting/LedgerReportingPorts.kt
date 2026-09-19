package com.finaxis.platform.accounting.application.reporting

import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountUsage
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/** One account's debit and credit movement over a date range, as the aggregate returns it. */
data class AccountMovement(
    val accountId: UUID,
    val debit: BigDecimal,
    val credit: BigDecimal,
)

/**
 * Read port over the journal for the reporting models.
 *
 * Every method is tenant-scoped by parameter, takes a bounded date range, and is served by an index
 * the schema document names with its justifying query (`INV-15`). There is deliberately no method
 * returning raw journal lines without a range: an unbounded ledger read is the one thing the seven
 * query patterns exist to prevent.
 */
interface LedgerReportingQueries {
    /**
     * Movement per account over `(fromDate, toDate]`-inclusive-of-both-ends, optionally within one
     * branch. A null [fromDate] means *from inception*, which the opening-balance path needs when a
     * tenant has no projected checkpoint at all.
     *
     * **Two query shapes, because two indexes answer two different questions.** With a branch named
     * it is one grouped aggregate over `idx_journal_line_branch_account_date`, whose key is
     * `(organisation_id, branch_id, posting_date, gl_account_id)` — a single index-only range scan
     * of exactly that branch's rows in the window. Without one it is a `LATERAL` per account over
     * `idx_journal_line_account_date`, whose key leads with the account, so each account is a tight
     * range scan of its own rows in the window and nothing else is read.
     *
     * Using either index for the other question is what makes a trial balance slow. The
     * account-leading index asked for a branch-wide report has to scan every account's whole window
     * and discard the other branches; the branch-leading index asked for a tenant-wide one has no
     * leading value to range on and degrades to reading the tenant's history.
     *
     * Returns only accounts that moved, so a report over a quiet month costs what the month did
     * rather than what the chart holds.
     */
    fun movementsByAccount(
        organisationId: UUID,
        branchId: UUID?,
        fromDate: LocalDate?,
        toDate: LocalDate,
    ): List<AccountMovement>

    /**
     * One keyset page of an account's movements, **oldest first**, at most [pageSize] rows.
     *
     * Ascending rather than the pagination contract's `(posting_date DESC, id DESC)`, and the
     * exception is deliberate: a running balance only means anything read forward from an opening
     * balance, so a ledger page arriving newest-first could not carry one. The same index serves
     * both directions, and descending stays the contract for the statement and search paths that
     * have no running balance to carry.
     */
    fun movementsForAccount(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        fromDate: LocalDate,
        toDate: LocalDate,
        after: LedgerCursor?,
        pageSize: Int,
    ): List<LedgerMovement>

    /** One journal with its lines, by the gapless entry number, or null. */
    fun journalByEntryNumber(
        organisationId: UUID,
        entryNumber: Long,
    ): JournalDetail?

    /** The same journal by identity, for a drill-down that already holds one. */
    fun journalById(
        organisationId: UUID,
        journalEntryId: UUID,
    ): JournalDetail?
}

/**
 * The projection side of an as-of balance, read for a whole tenant in one statement.
 *
 * The bulk counterpart of [com.finaxis.platform.accounting.application.balances.DailyBalanceStore],
 * which reads and writes one series at a time for the build and its repair. Both read
 * `gl_account_daily_balance`; they are separate ports because they are separate access patterns,
 * and giving the build's port a tenant-wide sweep would be handing the repair path a query it must
 * never run.
 */
fun interface BalanceCheckpointQueries {
    /**
     * The latest date at or before [asOfDate] that the projection is complete for, and every
     * account's closing balance at it.
     *
     * **One checkpoint date for the whole scope, not one per account.** A projected series carries
     * its balance forward — the row is written only on a day that moved — so "the latest row on
     * or before `D`" is that series' closing balance at `D` for *any* `D`, and picking the same `D`
     * for every account makes the journal delta that follows a single bounded window rather than a
     * different window per account.
     *
     * The date is chosen strictly **before** the earliest posting date any journal recorded since
     * the projection's watermark has touched, never at the latest projected posting date, for the
     * reason [com.finaxis.platform.accounting.application.balances.DailyBalanceReader] sets out at
     * length: a posting backdated onto an already-projected day is otherwise invisible to the
     * checkpoint and to the delta alike. It may therefore precede every projected row, in which
     * case the balances are empty and the caller's delta is the whole answer — correct, and the
     * read the projection was built to replace.
     */
    fun checkpoint(
        organisationId: UUID,
        branchId: UUID?,
        currencyCode: String,
        asOfDate: LocalDate,
    ): BalanceCheckpoint
}

/**
 * A trusted point in the projection: the date, and each account's closing balance at it.
 *
 * [closingByAccount] holds only accounts the projection has a row for at [checkpointDate], so an
 * absent account means a zero checkpoint rather than an unknown one.
 */
data class BalanceCheckpoint(
    val checkpointDate: LocalDate,
    val closingByAccount: Map<UUID, BigDecimal>,
)

/**
 * Read port over the chart of accounts for the reporting models.
 *
 * Separate from [LedgerReportingQueries] for the same reason the projection's ports are separate:
 * one reads the append-only ledger and the other reads mutable configuration, and a single port
 * over both makes it easy to lose track of which of the two a number came from.
 */
interface ChartReportingQueries {
    /**
     * Every account of the tenant, by identity, with the naming a report needs.
     *
     * Bounded by the chart rather than by a page, and that is the bound `INV-15` asks for here: a
     * tenant holds 500 to 2,000 accounts by design, a number fixed by configuration rather than by
     * history. A trial balance needs all of them at once to name the accounts that moved, so paging
     * would only move the join into the caller.
     */
    fun accountsById(organisationId: UUID): Map<UUID, ReportingAccount>

    /** One account by identity, or null when the tenant has no such account. */
    fun find(
        organisationId: UUID,
        accountId: UUID,
    ): ReportingAccount?

    /**
     * The chart beneath [rootAccountId], or the whole chart when it is null, as a flat list in code
     * order with each node's depth.
     *
     * **One recursive descent, not one query per node.** `ChartHierarchyPolicy` bounds the chart's
     * depth, so a `WITH RECURSIVE` walk is a handful of index lookups in one statement — which is
     * what issue #49's *"hierarchy rollup must not issue one query per account node"* requires. The
     * depth guard is in the SQL as well as in the policy, so a corrupted parent chain terminates
     * rather than spinning.
     */
    fun hierarchy(
        organisationId: UUID,
        rootAccountId: UUID?,
    ): List<ReportingAccountNode>
}

/** An account as a report names it. */
data class ReportingAccount(
    val accountId: UUID,
    val accountCode: String,
    val accountName: String,
    val accountClass: AccountClass,
    val accountUsage: AccountUsage,
    val isContraAccount: Boolean,
)

/** An account with its place in the chart, as the recursive descent returns it. */
data class ReportingAccountNode(
    val account: ReportingAccount,
    val parentAccountId: UUID?,
    val depth: Int,
)
