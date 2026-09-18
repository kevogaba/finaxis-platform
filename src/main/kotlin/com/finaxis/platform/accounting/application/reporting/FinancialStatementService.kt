package com.finaxis.platform.accounting.application.reporting

import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.RequiredSnapshotIsolation
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The balance sheet and the income statement, derived from the chart's classes and the journal.
 *
 * ## No account numbers, and no second classification
 *
 * Every account reaches its section through [StatementSection.of] and its own `account_class`.
 * There is no mapping table, no configured range, and no constant naming an account — which is
 * #51's explicit acceptance criterion, and also the only design that survives a tenant numbering
 * its chart however it likes. The chart's parent hierarchy supplies the nesting *within* a section;
 * the class supplies the section.
 *
 * ## No second ledger, and no report builder
 *
 * Both statements are composed from the same two reads #49 uses — [LedgerBalanceSnapshot] for
 * balances at a date and [LedgerReportingQueries] for movement over a window — so a statement
 * cannot disagree with the trial balance that shares them. #51 forbids inventing a reporting ledger
 * or a generic report framework, and nothing here is either: these are two functions over reads
 * that already existed.
 *
 * ## Only postable accounts carry lines
 *
 * A `HEADER` account can receive no journal line, so it has no balance of its own. Giving it a line
 * would be how a roll-up comes to double-count, and this is the place that mistake is easiest to
 * make: a reader summing a section would add a parent's subtotal to the children it already
 * counted. The hierarchy is carried on each line as [FinancialStatementLine.parentAccountId] and
 * [FinancialStatementLine.depth] so a presentation layer can nest without any total being restated.
 *
 * ## Periods, open and closed
 *
 * Neither statement consults a fiscal period's status, and that is deliberate. A report of an
 * `OPEN` period is a report of the ledger as it stands, and it changes when someone posts — which
 * is what "mid-period" means and is not a defect. A report of a `CLOSED` period is stable unless
 * break-glass authority reopens it for a correction, and then the report changes because the ledger
 * did. What makes a statement trustworthy is that it is derived from the journal at the moment it
 * is asked for, not that it was taken at a blessed time; a caller needing a fixed copy takes the
 * period's dates and keeps the result.
 */
@Service
class FinancialStatementService(
    private val balanceSnapshot: LedgerBalanceSnapshot,
    private val ledger: LedgerReportingQueries,
    private val chart: ChartReportingQueries,
    private val periods: FiscalPeriodStateStore,
    private val tenants: AccountingTenantLookup,
    private val permissions: AccountingPermissionGuard,
    private val snapshots: SnapshotIsolationGuard,
) {
    /**
     * The statement of financial position at a date, proven balanced before it is returned.
     *
     * Refuses rather than reports when the equation fails, for the reason a trial balance does:
     * `INV-4` makes `assets = liabilities + equity + current earnings` true of any sound ledger, so
     * a failure here means something upstream is wrong and wants investigating rather than
     * formatting.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun balanceSheet(query: BalanceSheetQuery): BalanceSheet {
        requireReportPermission(query.actorId, query.organisationId)
        // The same reason the trial balance runs at REPEATABLE_READ, and the failure is worse
        // here. A balance sheet is a checkpoint-plus-delta composition over five sections; at
        // READ COMMITTED each statement sees its own snapshot, so a journal committing mid-report
        // with a posting date at or before the checkpoint lands in neither half. Because the
        // omitted journal is itself balanced, the equation still holds - so `proven` passes and
        // the statement is wrong while asserting it is sound.
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "A balance sheet",
        )
        requireBranchInOrganisation(query.organisationId, query.branchId)
        val accounts = postableAccounts(query.organisationId)
        val balances =
            balanceSnapshot.signedBalancesAsOf(
                organisationId = query.organisationId,
                branchId = query.branchId,
                currencyCode = functionalCurrencyOf(query.organisationId),
                asOfDate = query.asOfDate,
            )
        val bySection = sections(accounts, balances)
        val assets = section(StatementSection.ASSETS, bySection)
        val liabilities = section(StatementSection.LIABILITIES, bySection)
        val equity = section(StatementSection.EQUITY, bySection)
        val income = section(StatementSection.INCOME, bySection)
        val expenses = section(StatementSection.EXPENSES, bySection)
        val currentEarnings = income.total.subtract(expenses.total)
        return proven(
            BalanceSheet(
                organisationId = query.organisationId,
                branchId = query.branchId,
                asOfDate = query.asOfDate,
                assets = assets,
                liabilities = liabilities,
                equity = equity,
                currentEarnings = currentEarnings,
                totalAssets = assets.total,
                totalLiabilitiesAndEquity =
                    liabilities.total.add(equity.total).add(currentEarnings),
            ),
        )
    }

    /**
     * The statement of performance over a window, named as a fiscal period or as dates.
     *
     * Its totals are the same income and expense movements a trial balance of the same scope
     * reports, because both are [LedgerReportingQueries.movementsByAccount] over the same window —
     * not two computations that ought to agree, but one used twice.
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    fun incomeStatement(query: IncomeStatementQuery): IncomeStatement {
        requireReportPermission(query.actorId, query.organisationId)
        // One snapshot, so the period resolution and the movement aggregate cannot straddle a
        // commit. An income statement reads fewer relations than a balance sheet, but it is the
        // comparative figure a balance sheet's retained earnings has to agree with, and two
        // statements taken from different snapshots disagree by whatever committed between them.
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "An income statement",
        )
        requireBranchInOrganisation(query.organisationId, query.branchId)
        val scope = resolveWindow(query)
        val accounts = postableAccounts(query.organisationId)
        val movements =
            ledger
                .movementsByAccount(
                    query.organisationId,
                    query.branchId,
                    scope.fromDate,
                    scope.toDate,
                ).associate { it.accountId to it.debit.subtract(it.credit) }
        val bySection = sections(accounts, movements)
        val income = section(StatementSection.INCOME, bySection)
        val expenses = section(StatementSection.EXPENSES, bySection)
        return IncomeStatement(
            organisationId = query.organisationId,
            branchId = query.branchId,
            fiscalPeriodId = scope.fiscalPeriodId,
            fromDate = scope.fromDate,
            toDate = scope.toDate,
            income = income,
            expenses = expenses,
            netIncome = income.total.subtract(expenses.total),
        )
    }

    /**
     * Groups accounts into sections, dropping the ones with nothing to report.
     *
     * A zero line contributes to no total and to no reader's understanding, and dropping it cannot
     * change a section's total — which is the only kind of line that can safely be dropped.
     */
    private fun sections(
        accounts: Map<UUID, ReportingAccountNode>,
        amounts: Map<UUID, BigDecimal>,
    ): Map<StatementSection, List<FinancialStatementLine>> =
        amounts
            .mapNotNull { (accountId, signed) ->
                val node = accounts[accountId] ?: return@mapNotNull null
                if (signed.signum() == 0) return@mapNotNull null
                FinancialStatementLine(
                    accountId = accountId,
                    accountCode = node.account.accountCode,
                    accountName = node.account.accountName,
                    accountClass = node.account.accountClass,
                    parentAccountId = node.parentAccountId,
                    depth = node.depth,
                    signedAmount = signed,
                    amount = presented(node.account.accountClass, signed),
                )
            }.sortedBy { it.accountCode }
            .groupBy { StatementSection.of(it.accountClass) }

    private fun section(
        section: StatementSection,
        bySection: Map<StatementSection, List<FinancialStatementLine>>,
    ): FinancialStatementSectionView {
        val lines = bySection[section].orEmpty()
        return FinancialStatementSectionView(
            section = section,
            lines = lines,
            total = lines.fold(BigDecimal.ZERO) { sum, line -> sum.add(line.amount) },
        )
    }

    /**
     * The ledger's signed amount as its own section presents it: positive in the normal direction.
     *
     * Assets and expenses are debit-normal and keep their sign; liabilities, equity and income are
     * credit-normal and are negated. A contra account is **not** special-cased here: a contra asset
     * carries a credit balance and therefore presents as a negative asset, which is exactly how a
     * statement shows accumulated depreciation, and flipping it by the contra flag would show it as
     * a positive asset that then had to be subtracted somewhere else.
     */
    private fun presented(
        accountClass: AccountClass,
        signed: BigDecimal,
    ): BigDecimal =
        when (accountClass) {
            AccountClass.ASSET, AccountClass.EXPENSE -> signed
            AccountClass.LIABILITY, AccountClass.EQUITY, AccountClass.INCOME -> signed.negate()
        }

    /** Only accounts that can hold a balance; a `HEADER` holds none and would double-count. */
    private fun postableAccounts(organisationId: UUID): Map<UUID, ReportingAccountNode> =
        chart
            .hierarchy(organisationId, null)
            .filter { it.account.accountUsage == AccountUsage.POSTABLE }
            .associateBy { it.account.accountId }

    private fun proven(statement: BalanceSheet): BalanceSheet {
        if (!statement.balanced) {
            throw ConflictException(
                code = PostingErrorCodes.BALANCE_SHEET_UNBALANCED,
                safeDetail =
                    "The balance sheet for this scope does not balance; the ledger needs " +
                        "investigation before the statement can be trusted.",
            )
        }
        return statement
    }

    /**
     * Resolves the window from either a fiscal period or an explicit range, never both.
     *
     * Split in two the way a trial balance's is, so each form is one function rather than one
     * branch: they are different questions, and a request carrying both would silently prefer one.
     */
    private fun resolveWindow(query: IncomeStatementQuery): ReportWindow =
        query.fiscalPeriodId?.let { periodWindow(query, it) } ?: dateWindow(query)

    /** The window a period defines, taken from the period itself and never from the caller. */
    private fun periodWindow(
        query: IncomeStatementQuery,
        fiscalPeriodId: UUID,
    ): ReportWindow {
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
        return ReportWindow(period.startDate, period.endDate, fiscalPeriodId)
    }

    /** The window an explicit range defines, once both ends are present and sane. */
    private fun dateWindow(query: IncomeStatementQuery): ReportWindow {
        val fromDate = query.fromDate
        val toDate = query.toDate
        if (fromDate == null || toDate == null) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_SCOPE_INVALID,
                safeDetail = "Name either a fiscal period or both ends of a date range.",
            )
        }
        requireWindow(fromDate, toDate)
        return ReportWindow(fromDate, toDate, null)
    }

    private fun requireWindow(
        fromDate: LocalDate,
        toDate: LocalDate,
    ) {
        if (toDate.isBefore(fromDate)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_RANGE_INVALID,
                safeDetail = "The reporting range must end on or after it starts.",
            )
        }
        if (fromDate.plusDays(MAXIMUM_WINDOW_DAYS.toLong()).isBefore(toDate)) {
            throw InvalidOperationException(
                code = PostingErrorCodes.REPORT_RANGE_TOO_WIDE,
                safeDetail = "A reporting range covers at most $MAXIMUM_WINDOW_DAYS days.",
            )
        }
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

    private fun functionalCurrencyOf(organisationId: UUID): String =
        tenants.functionalCurrencyOf(organisationId)
            ?: throw InvalidOperationException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                safeDetail = "The organisation has no functional currency.",
            )

    private fun requireReportPermission(
        actorId: UUID,
        organisationId: UUID,
    ) = permissions.requireTenantPermission(
        actorId,
        organisationId,
        AccountingPermissions.ACCOUNTING_REPORT_VIEW,
    )

    private companion object {
        /** The same cap a trial balance applies, for the same reason. */
        const val MAXIMUM_WINDOW_DAYS = 366
    }
}

/** A resolved reporting window, whichever way the caller named it. */
private data class ReportWindow(
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val fiscalPeriodId: UUID?,
)

/** A statement of financial position at one date, optionally within one branch. */
data class BalanceSheetQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val asOfDate: LocalDate,
    val branchId: UUID? = null,
)

/**
 * A statement of performance over a window.
 *
 * The window is **either** [fiscalPeriodId] **or** both of [fromDate] and [toDate], for the reason
 * a trial balance holds the same rule: they are different questions, and a request carrying both
 * would silently prefer one.
 */
data class IncomeStatementQuery(
    val organisationId: UUID,
    val actorId: UUID,
    val branchId: UUID? = null,
    val fiscalPeriodId: UUID? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
)
