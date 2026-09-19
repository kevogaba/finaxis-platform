package com.finaxis.platform.accounting.application.reporting

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.RequiredSnapshotIsolation
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.reconciliation.LedgerBalanceQuery
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.web.pagination.PaginationProperties
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The accounting read models: trial balance, general-ledger account ledger, journal drill-down and
 * chart roll-up.
 *
 * ## Derived, never a second ledger
 *
 * Nothing here writes. Every number is computed from `journal_line`, or from the daily-balance
 * projection which is itself rebuildable from `journal_line`, so there is no read model that can
 * disagree with the journal and still be believed (`INV-13`).
 *
 * Reversals need no special handling anywhere in this class, and that is the point rather than an
 * omission. A reversal is an ordinary journal of opposite lines (`INV-6`), so it nets out of every
 * sum here by construction — which is exactly what issue #49's *"read models must never hide
 * reversals"* asks for. A read model that filtered reversed journals out would be reporting a
 * history the ledger does not have.
 *
 * ## An opening balance is a checkpoint plus a bounded delta
 *
 * Opening is the closing balance of the day before the range starts. It is read as the projection's
 * latest checkpoint on or before that day plus the journal's movement after it — the shape issue
 * #47 built the projection for, and the reason a trial balance does not scan an account's lines
 * from inception. Closing is opening plus the range's movement, computed and never stored twice.
 *
 * ## Every read is bounded
 *
 * A date range is mandatory, its width is capped, and a page size is capped by
 * `finaxis.pagination.max-page-size`. `INV-15` is not a style rule here: an unbounded ledger read
 * at the design envelope means scanning toward a billion rows, and the request that does it is the
 * one that takes the database down.
 */
@Service
@Suppress("TooManyFunctions")
class LedgerReportingService(
    private val ledger: LedgerReportingQueries,
    private val chart: ChartReportingQueries,
    private val balanceSnapshot: LedgerBalanceSnapshot,
    private val balances: LedgerBalanceQuery,
    private val periods: FiscalPeriodStateStore,
    private val tenants: AccountingTenantLookup,
    private val permissions: AccountingPermissionGuard,
    private val pagination: PaginationProperties,
    private val snapshots: SnapshotIsolationGuard,
) {
    /**
     * A trial balance for the scope, proven balanced before it is returned.
     *
     * **It refuses rather than reports when the columns disagree.** A trial balance is the
     * instrument a ledger is checked with, so one that quietly presents unequal totals is worse
     * than an error: the assurance is the entire reason the reader opened it. `INV-4` makes the
     * equality true of any sound ledger, so a failure here is evidence that something upstream is
     * wrong and wants investigating, not formatting.
     *
     * Every account with a balance appears, not only those that moved. An account carrying an
     * opening balance and no movement in the range still belongs to the totals, and leaving it out
     * is the most direct way to produce a trial balance that cannot balance.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun trialBalance(query: TrialBalanceQuery): TrialBalance {
        permissions.requireTenantPermission(
            query.actorId,
            query.organisationId,
            AccountingPermissions.ACCOUNTING_REPORT_VIEW,
        )
        // Four to six statements across two stores make one report, and under READ COMMITTED each
        // could see a different snapshot. A journal committing mid-report between the checkpoint
        // and the delta lands in neither, and because a whole balanced journal goes missing the
        // columns still agree - so the balanced check cannot catch it and the report is wrong and
        // says it is sound. One snapshot for the whole report is the only thing that closes this.
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "A trial balance",
        )
        val scope = resolveScope(query)
        requireBranchInOrganisation(query.organisationId, query.branchId)
        val currency = functionalCurrencyOf(query.organisationId)
        val accounts = chart.accountsById(query.organisationId)
        val openings =
            openingBalances(query.organisationId, query.branchId, currency, scope.fromDate)
        val movements =
            ledger
                .movementsByAccount(
                    query.organisationId,
                    query.branchId,
                    scope.fromDate,
                    scope.toDate,
                ).associateBy { it.accountId }
        val lines =
            (openings.keys + movements.keys)
                .mapNotNull { accountId ->
                    val account = accounts[accountId] ?: return@mapNotNull null
                    val opening = openings[accountId] ?: BigDecimal.ZERO
                    val movement = movements[accountId]
                    val debit = movement?.debit ?: BigDecimal.ZERO
                    val credit = movement?.credit ?: BigDecimal.ZERO
                    TrialBalanceLine(
                        accountId = accountId,
                        accountCode = account.accountCode,
                        accountName = account.accountName,
                        accountClass = account.accountClass,
                        openingSigned = opening,
                        debitMovement = debit,
                        creditMovement = credit,
                        closingSigned = opening.add(debit).subtract(credit),
                    )
                }.filterNot { it.isDormant }
                .sortedBy { it.accountCode }
        return provenBalanced(
            TrialBalance(
                organisationId = query.organisationId,
                branchId = query.branchId,
                fiscalPeriodId = scope.fiscalPeriodId,
                fromDate = scope.fromDate,
                toDate = scope.toDate,
                lines = lines,
                totalDebit =
                    lines.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.closingDebit) },
                totalCredit =
                    lines.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.closingCredit) },
            ),
        )
    }

    /**
     * One keyset page of an account's ledger, with a running balance carried from the opening.
     *
     * The running balance is accumulated over the page in one pass rather than asked for per row,
     * which is the shape that would turn a statement into an N+1 and is why the page carries
     * [AccountLedgerPage.closingSigned] for the caller to hand back as
     * [AccountLedgerQuery.carriedBalance] on the next page.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun accountLedger(query: AccountLedgerQuery): AccountLedgerPage {
        permissions.requireTenantPermission(
            query.actorId,
            query.organisationId,
            AccountingPermissions.ACCOUNTING_REPORT_VIEW,
        )
        requireRange(query.fromDate, query.toDate)
        requireBranchInOrganisation(query.organisationId, query.branchId)
        requireAccount(query.organisationId, query.accountId)
        val pageSize = requirePageSize(query.pageSize)
        requirePaginationState(query)
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "An account ledger page",
        )
        // Only on the first page. A later page already knows where it starts, and recomputing the
        // as-of opening only to discard it pays for a checkpoint read and, on a tenant with
        // nothing projected, a scan from inception - once per page of the walk.
        val opening =
            query.carriedBalance
                ?: balances.signedBalanceAsOf(
                    organisationId = query.organisationId,
                    accountId = query.accountId,
                    branchId = query.branchId,
                    asOfDate = query.fromDate.minusDays(1),
                )
        // One row more than the page, so "is there a next page" is answered by the same read
        // rather than by a count over the remainder of the range.
        val fetched =
            ledger.movementsForAccount(
                organisationId = query.organisationId,
                accountId = query.accountId,
                branchId = query.branchId,
                fromDate = query.fromDate,
                toDate = query.toDate,
                after = query.cursor,
                pageSize = pageSize + 1,
            )
        val hasMore = fetched.size > pageSize
        var running = opening
        val movements =
            fetched.take(pageSize).map { movement ->
                running = running.add(movement.debit).subtract(movement.credit)
                movement.copy(runningBalanceSigned = running)
            }
        return AccountLedgerPage(
            accountId = query.accountId,
            branchId = query.branchId,
            fromDate = query.fromDate,
            toDate = query.toDate,
            openingSigned = opening,
            movements = movements,
            closingSigned = running,
            nextCursor =
                movements.lastOrNull().takeIf { hasMore }?.let {
                    LedgerCursor(it.postingDate, it.lineId)
                },
        )
    }

    /** One journal and its lines, by the gapless entry number an accountant quotes. */
    @Transactional(readOnly = true)
    fun journalByEntryNumber(query: JournalLookupQuery): JournalDetail {
        requireJournalView(query.actorId, query.organisationId)
        return ledger.journalByEntryNumber(query.organisationId, query.entryNumber)
            ?: throw ResourceNotFoundException(
                code = PostingErrorCodes.JOURNAL_NOT_FOUND,
                safeDetail = "No journal with that entry number exists for this organisation.",
            )
    }

    /** The same journal by identity, for a drill-down that already holds one. */
    @Transactional(readOnly = true)
    fun journalById(
        organisationId: UUID,
        journalEntryId: UUID,
        actorId: UUID,
    ): JournalDetail {
        requireJournalView(actorId, organisationId)
        return ledger.journalById(organisationId, journalEntryId)
            ?: throw ResourceNotFoundException(
                code = PostingErrorCodes.JOURNAL_NOT_FOUND,
                safeDetail = "No such journal exists for this organisation.",
            )
    }

    /**
     * The chart beneath a root, each node carrying its own movement and its subtree's.
     *
     * One recursive descent for the structure and one aggregate for the movement, joined in memory
     * — never a query per node. The subtree total is accumulated by walking the nodes
     * deepest-first, which is what [ChartReportingQueries.hierarchy] returns the depth for.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun rollup(query: AccountRollupQuery): List<AccountRollupNode> {
        permissions.requireTenantPermission(
            query.actorId,
            query.organisationId,
            AccountingPermissions.ACCOUNTING_REPORT_VIEW,
        )
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "A chart roll-up",
        )
        val scope = resolveScope(query.asTrialBalanceScope())
        requireBranchInOrganisation(query.organisationId, query.branchId)
        query.rootAccountId?.let { requireAccount(query.organisationId, it) }
        val nodes = chart.hierarchy(query.organisationId, query.rootAccountId)
        val own =
            ledger
                .movementsByAccount(
                    query.organisationId,
                    query.branchId,
                    scope.fromDate,
                    scope.toDate,
                ).associateBy({ it.accountId }, { it.debit.subtract(it.credit) })
        return rolledUp(nodes, own)
    }

    /**
     * Accumulates each node's subtree total from the leaves up, in one pass over the nodes.
     *
     * Deepest-first, so every child has already been folded into its parent by the time the parent
     * is reached. A node whose parent is outside the requested subtree contributes to nothing,
     * which is what makes a roll-up rooted below the top of the chart total correctly.
     */
    private fun rolledUp(
        nodes: List<ReportingAccountNode>,
        own: Map<UUID, BigDecimal>,
    ): List<AccountRollupNode> {
        val subtree =
            nodes.associateTo(mutableMapOf()) { node ->
                node.account.accountId to (own[node.account.accountId] ?: BigDecimal.ZERO)
            }
        nodes.sortedByDescending { it.depth }.forEach { node ->
            val parent = node.parentAccountId ?: return@forEach
            val carried = subtree[node.account.accountId] ?: return@forEach
            subtree.computeIfPresent(parent) { _, total -> total.add(carried) }
        }
        return nodes.map { node ->
            AccountRollupNode(
                accountId = node.account.accountId,
                accountCode = node.account.accountCode,
                accountName = node.account.accountName,
                accountClass = node.account.accountClass,
                parentAccountId = node.parentAccountId,
                depth = node.depth,
                ownSigned = own[node.account.accountId] ?: BigDecimal.ZERO,
                subtreeSigned = subtree[node.account.accountId] ?: BigDecimal.ZERO,
            )
        }
    }

    /**
     * Every account's balance at the close of the day before [fromDate], in two statements.
     *
     * The checkpoint supplies what the projection already knows; the delta is the journal's
     * movement over `(checkpoint, fromDate - 1]`, which is bounded by how far the projection lags
     * — a day or two in a healthy deployment. With no checkpoint at all the delta runs from
     * inception, which is correct and is the read the projection was built to replace.
     */
    private fun openingBalances(
        organisationId: UUID,
        branchId: UUID?,
        currencyCode: String,
        fromDate: LocalDate,
    ): Map<UUID, BigDecimal> =
        balanceSnapshot.signedBalancesAsOf(
            organisationId = organisationId,
            branchId = branchId,
            currencyCode = currencyCode,
            asOfDate = fromDate.minusDays(1),
        )

    private fun provenBalanced(balance: TrialBalance): TrialBalance {
        if (!balance.balanced) {
            throw ConflictException(
                code = PostingErrorCodes.TRIAL_BALANCE_UNBALANCED,
                safeDetail =
                    "The trial balance for this scope does not balance; the ledger needs " +
                        "investigation before the report can be trusted.",
            )
        }
        return balance
    }

    /**
     * Resolves a report's window from either a fiscal period or an explicit date range.
     *
     * Exactly one of the two, because they are different questions: a period is the tenant's own
     * calendar boundary and a range is an arbitrary window, and a request carrying both would
     * silently prefer one. Naming a period is the safer form, since a statutory report is a report
     * of a period rather than of dates that happen to resemble one.
     */
    private fun resolveScope(query: TrialBalanceQuery): ReportScope =
        query.fiscalPeriodId?.let { periodScope(query, it) } ?: dateScope(query)

    /** The window a period defines, taken from the period itself and never from the caller. */
    private fun periodScope(
        query: TrialBalanceQuery,
        fiscalPeriodId: UUID,
    ): ReportScope {
        if (query.fromDate != null || query.toDate != null) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_SCOPE_INVALID,
                safeDetail = "Name either a fiscal period or a date range, not both.",
            )
        }
        val period =
            periods.findById(FiscalPeriodKey(query.organisationId, fiscalPeriodId))
                ?: throw ResourceNotFoundException(
                    code = PostingErrorCodes.PERIOD_NOT_FOUND,
                    safeDetail = "No such fiscal period exists for this organisation.",
                )
        // A period's own bounds are not self-evidently narrow. The calendar permits a fiscal year
        // and its periods to be any length, so naming one must not become the way to ask for a
        // report the explicit date path would refuse - which is how a bounded-query rule turns
        // into a bounded-query rule with one unguarded door.
        requireRange(period.startDate, period.endDate)
        return ReportScope(period.startDate, period.endDate, fiscalPeriodId)
    }

    /** The window an explicit range defines, once both ends are present and sane. */
    private fun dateScope(query: TrialBalanceQuery): ReportScope {
        val fromDate = query.fromDate
        val toDate = query.toDate
        if (fromDate == null || toDate == null) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_SCOPE_INVALID,
                safeDetail = "Name either a fiscal period or both ends of a date range.",
            )
        }
        requireRange(fromDate, toDate)
        return ReportScope(fromDate, toDate, null)
    }

    /**
     * Refuses a page whose cursor and carried balance do not agree about where it starts.
     *
     * They are one pagination state. A cursor with no carried balance skips the rows before it and
     * then opens from the range's own opening balance, so every running balance on the page is
     * short by exactly what the earlier pages moved; a carried balance with no cursor applies a
     * balance from elsewhere to the first page. Neither is detectable in the result, which is why
     * this refuses instead of choosing one.
     */
    private fun requirePaginationState(query: AccountLedgerQuery) {
        if ((query.cursor == null) != (query.carriedBalance == null)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_CURSOR_INVALID,
                safeDetail =
                    "A ledger page takes a cursor and the balance the previous page closed at, " +
                        "or neither.",
            )
        }
    }

    private fun requireRange(
        fromDate: LocalDate,
        toDate: LocalDate,
    ) {
        if (toDate.isBefore(fromDate)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_RANGE_INVALID,
                safeDetail = "The reporting range must end on or after it starts.",
            )
        }
        // Inclusive of both ends, so the widest legal range is `from + (MAXIMUM_RANGE_DAYS - 1)`.
        // Comparing against `from + MAXIMUM_RANGE_DAYS` admitted one day more than the constant
        // and the message both promise.
        if (fromDate.plusDays(MAXIMUM_RANGE_DAYS.toLong() - 1).isBefore(toDate)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_RANGE_TOO_WIDE,
                safeDetail = "A reporting range covers at most $MAXIMUM_RANGE_DAYS days.",
            )
        }
    }

    private fun requirePageSize(pageSize: Int?): Int {
        val resolved = pageSize ?: pagination.defaultPageSize
        if (resolved < 1 || resolved > pagination.maxPageSize) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_PAGE_SIZE_INVALID,
                safeDetail = "The page size must be between 1 and ${pagination.maxPageSize}.",
            )
        }
        return resolved
    }

    private fun requireBranchInOrganisation(
        organisationId: UUID,
        branchId: UUID?,
    ) {
        if (branchId != null && !tenants.branchBelongsTo(organisationId, branchId)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.BRANCH_NOT_IN_ORGANISATION,
                safeDetail = "The branch does not belong to this organisation.",
            )
        }
    }

    private fun requireAccount(
        organisationId: UUID,
        accountId: UUID,
    ) {
        if (chart.find(organisationId, accountId) == null) {
            throw ResourceNotFoundException(
                code = PostingErrorCodes.GL_ACCOUNT_NOT_FOUND,
                safeDetail = "No such account exists for this organisation.",
            )
        }
    }

    private fun functionalCurrencyOf(organisationId: UUID): String =
        tenants.functionalCurrencyOf(organisationId)
            ?: throw InvalidOperationException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                safeDetail = "The organisation has no functional currency.",
            )

    private fun requireJournalView(
        actorId: UUID,
        organisationId: UUID,
    ) = permissions.requireTenantPermission(
        actorId,
        organisationId,
        AccountingPermissions.JOURNAL_VIEW,
    )

    private companion object {
        /**
         * The capped reporting window, in days.
         *
         * A year plus a day, which is what a full fiscal year and a comparative annual report need,
         * and is the point past which a request stops being a report and starts being an export.
         */
        const val MAXIMUM_RANGE_DAYS = 366
    }
}

/** A resolved reporting window, whichever way the caller named it. */
private data class ReportScope(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val fiscalPeriodId: UUID?,
)

/**
 * A trial balance over one tenant and one window, optionally within one branch.
 *
 * The window is **either** [fiscalPeriodId] **or** both of [fromDate] and [toDate]; naming both
 * forms is refused rather than resolved, so a caller never gets a period report over dates it did
 * not choose.
 */
data class TrialBalanceQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val branchId: UUID? = null,
    val fiscalPeriodId: UUID? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
)

/**
 * One page of an account's ledger.
 *
 * [carriedBalance] is the running balance the previous page ended at — [AccountLedgerPage
 * .closingSigned] — supplied by a caller walking forward. Absent on the first page, where the
 * opening balance is the start.
 */
data class AccountLedgerQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val accountId: UUID,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val branchId: UUID? = null,
    val cursor: LedgerCursor? = null,
    val carriedBalance: BigDecimal? = null,
    val pageSize: Int? = null,
)

/** One journal by its gapless entry number. */
data class JournalLookupQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val entryNumber: Long,
)

/** The chart beneath a root, with movement over a window named the same two ways. */
data class AccountRollupQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val rootAccountId: UUID? = null,
    val branchId: UUID? = null,
    val fiscalPeriodId: UUID? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
) {
    internal fun asTrialBalanceScope() =
        TrialBalanceQuery(
            organisationId = organisationId,
            actorId = actorId,
            branchId = branchId,
            fiscalPeriodId = fiscalPeriodId,
            fromDate = fromDate,
            toDate = toDate,
        )
}
