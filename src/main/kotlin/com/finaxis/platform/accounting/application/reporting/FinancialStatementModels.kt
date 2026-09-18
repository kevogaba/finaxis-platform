package com.finaxis.platform.accounting.application.reporting

import com.finaxis.platform.accounting.domain.AccountClass
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Which financial statement an account's class reports on, and under which heading.
 *
 * **The whole of #51's classification, and it is derived rather than configured.** `gl_account`
 * already carries `account_class`, so a tenant's own chart maps onto statement sections without a
 * single account number in code — which is that issue's explicit acceptance criterion, and the
 * reason there is no mapping table here to drift out of date.
 *
 * A reporting hierarchy that grouped differently from the classes would be a second classification
 * of the same accounts, and the two would eventually disagree. The chart's parent hierarchy
 * supplies the *nesting within* a section; the class supplies the section.
 */
enum class StatementSection(
    val statement: FinancialStatementKind,
) {
    /** Resources the tenant controls. */
    ASSETS(FinancialStatementKind.BALANCE_SHEET),

    /** Obligations the tenant owes. */
    LIABILITIES(FinancialStatementKind.BALANCE_SHEET),

    /** Residual interest, including earnings not yet closed into it. */
    EQUITY(FinancialStatementKind.BALANCE_SHEET),

    /** Inflows that increase equity other than contributions. */
    INCOME(FinancialStatementKind.INCOME_STATEMENT),

    /** Outflows that decrease equity other than distributions. */
    EXPENSES(FinancialStatementKind.INCOME_STATEMENT),

    ;

    /** Maps a chart's classes onto sections, which is the whole of #51's classification. */
    companion object {
        /** The section an account's class reports under. Total, so no account is unclassified. */
        fun of(accountClass: AccountClass): StatementSection =
            when (accountClass) {
                AccountClass.ASSET -> ASSETS
                AccountClass.LIABILITY -> LIABILITIES
                AccountClass.EQUITY -> EQUITY
                AccountClass.INCOME -> INCOME
                AccountClass.EXPENSE -> EXPENSES
            }
    }
}

/** The two statements this issue produces. */
enum class FinancialStatementKind {
    /** Position at a date. */
    BALANCE_SHEET,

    /** Performance over a period. */
    INCOME_STATEMENT,
}

/**
 * One account's line on a statement.
 *
 * Carries **both** signs, for the reason [TrialBalanceLine] does: [signedAmount] is the ledger's
 * own convention and is what arithmetic is done in; [amount] is the same number as a statement
 * presents it, positive for a section's normal direction. A reader adding up [amount] gets the
 * section total; a reader doing ledger arithmetic uses [signedAmount]. Deriving either from the
 * other at the call site is where a sign convention gets lost.
 *
 * [accountId] is the drill-down handle: it is what
 * [LedgerReportingService.accountLedger] takes, so a statement line reaches the immutable journal
 * lines behind it without the statement carrying any lineage of its own.
 *
 * [depth] and [parentAccountId] place the line in the tenant's own hierarchy. Only **postable**
 * accounts appear: a header account holds no lines, so giving it a line of its own is how a roll-up
 * comes to double-count.
 */
data class FinancialStatementLine(
    val accountId: UUID,
    val accountCode: String,
    val accountName: String,
    val accountClass: AccountClass,
    val parentAccountId: UUID?,
    val depth: Int,
    val signedAmount: BigDecimal,
    val amount: BigDecimal,
)

/**
 * One section of a statement: its lines in code order, and their total.
 *
 * [total] is in presentation terms — positive for the section's normal direction — so a reader adds
 * sections rather than remembering which of them invert.
 */
data class FinancialStatementSectionView(
    val section: StatementSection,
    val lines: List<FinancialStatementLine>,
    val total: BigDecimal,
)

/**
 * A statement of financial position at a date.
 *
 * ## Why current earnings appear in equity
 *
 * Because they have to, for the statement to balance at all, and the reason is worth stating.
 *
 * Every journal balances, so the signed sum over **every** account is zero:
 * `assets + liabilities + equity + income + expenses = 0`. Rearranged into presentation terms, with
 * each section positive in its normal direction, that is:
 *
 * ```
 * assets = liabilities + equity + (income - expenses)
 * ```
 *
 * The parenthetical is [currentEarnings]. Income and expense accounts accumulate until a year-end
 * close moves them into retained earnings, and this platform has no year-end close yet — so between
 * the tenant's first posting and that close, the earnings sit in the income and expense accounts
 * and a balance sheet that ignored them would be out by exactly the tenant's profit to date.
 *
 * Presenting them is not a workaround for the missing close. It is what a mid-period balance sheet
 * does in any ledger: the close is an event that moves a number between equity accounts, and the
 * statement is correct on both sides of it. When #54 or a later issue implements the close, this
 * arithmetic is unchanged — [currentEarnings] simply becomes the earnings since the last close
 * rather than since inception.
 *
 * [balanced] is checked before the statement is returned. `INV-4` makes it true of any sound
 * ledger, so a failure is evidence rather than a formatting problem.
 */
data class BalanceSheet(
    val organisationId: UUID,
    val branchId: UUID?,
    val asOfDate: LocalDate,
    val assets: FinancialStatementSectionView,
    val liabilities: FinancialStatementSectionView,
    val equity: FinancialStatementSectionView,
    val currentEarnings: BigDecimal,
    val totalAssets: BigDecimal,
    val totalLiabilitiesAndEquity: BigDecimal,
) {
    /** True when `assets = liabilities + equity + current earnings`. */
    val balanced: Boolean
        get() = totalAssets.compareTo(totalLiabilitiesAndEquity) == 0
}

/**
 * A statement of performance over a period.
 *
 * [netIncome] is income less expenses, which is the same number a [BalanceSheet] as of
 * [toDate] carries as its current earnings when the period runs from the tenant's inception — and
 * that equality is what ties the two statements together rather than leaving them as two
 * independent reports of the same ledger.
 *
 * [fiscalPeriodId] is set only when the caller named a period rather than dates, so a consumer can
 * tell a statutory period statement from an arbitrary window that shares its boundaries.
 */
data class IncomeStatement(
    val organisationId: UUID,
    val branchId: UUID?,
    val fiscalPeriodId: UUID?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val income: FinancialStatementSectionView,
    val expenses: FinancialStatementSectionView,
    val netIncome: BigDecimal,
)
