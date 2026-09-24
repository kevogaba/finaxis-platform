package com.finaxis.platform.accounting.support

import com.finaxis.platform.accounting.application.reporting.BalanceSheet
import com.finaxis.platform.accounting.application.reporting.FinancialStatementService
import com.finaxis.platform.accounting.application.reporting.LedgerBalanceSnapshot
import com.finaxis.platform.accounting.application.reporting.LedgerReportingService
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import org.jooq.DSLContext
import org.jooq.impl.DSL
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals

/**
 * Ties a [BalanceSheet]'s totals to a sum taken straight off `journal_line` and `gl_account`,
 * entirely bypassing [LedgerBalanceSnapshot] and [LedgerReportingService].
 *
 * `balanced` alone is refused-by-construction: [FinancialStatementService.balanceSheet] never
 * returns a sheet for which it is false, so any sheet a test can hold has already satisfied it.
 * Recomputing the same totals through [LedgerReportingService.trialBalance] would not close that
 * gap either - `trialBalance` opens its own totals with the very same
 * [LedgerBalanceSnapshot.signedBalancesAsOf] a balance sheet calls, and that primitive's own KDoc
 * names its checkpoint-plus-delta arithmetic as the subtlest piece of the whole reporting stack; a
 * defect there would make both reads wrong identically, so a cross-check against
 * [LedgerReportingService] would not close that tautology. This instead sums `journal_line`
 * directly, grouped by `gl_account.account_class`, with no checkpoint, no projection and no shared
 * service anywhere in the path - a second computation of the same fact rather than the same one
 * asked twice.
 *
 * Extracted from `FinancialStatementIntegrationTests` rather than kept as a private helper there,
 * for the same reason `JournalSchemaFixture` and its siblings are their own files: a cross-cutting
 * piece of test infrastructure kept inline is what pushes a test class past Detekt's `LargeClass`
 * ceiling.
 */
class RawLedgerBalanceFixture(
    private val dsl: DSLContext,
) {
    /** Asserts [sheet]'s two totals against the raw sum, in the same presentation sign. */
    fun assertTiesToRawLedger(sheet: BalanceSheet) {
        fun classTotal(accountClass: AccountClass) =
            rawStatementSignedTotal(
                sheet.organisationId,
                sheet.branchId,
                sheet.asOfDate,
                accountClass,
            )
        val currentEarnings =
            classTotal(AccountClass.INCOME).subtract(classTotal(AccountClass.EXPENSE))
        val liabilitiesAndEquity =
            classTotal(AccountClass.LIABILITY)
                .add(classTotal(AccountClass.EQUITY))
                .add(currentEarnings)
        assertEquals(
            classTotal(AccountClass.ASSET).setScale(SCALE),
            sheet.totalAssets.setScale(SCALE),
            "assets tie to a sum taken directly off journal_line, independently of the service",
        )
        assertEquals(
            liabilitiesAndEquity.setScale(SCALE),
            sheet.totalLiabilitiesAndEquity.setScale(SCALE),
            "liabilities, equity and current earnings tie to the same raw sum",
        )
    }

    /**
     * One account class's balance at the close of [asOfDate], summed directly from `journal_line`
     * joined to `gl_account`, restated in the statement's own presentation sign.
     *
     * Deliberately not [LedgerBalanceSnapshot] or [LedgerReportingService]: this is the second,
     * independent read [assertTiesToRawLedger] needs, so it must not go anywhere near the
     * checkpoint, the daily-balance projection or the movement query the service under test
     * itself uses.
     */
    private fun rawStatementSignedTotal(
        organisationId: UUID,
        branchId: UUID?,
        asOfDate: LocalDate,
        accountClass: AccountClass,
    ): BigDecimal {
        val debitTotal =
            DSL.coalesce(
                DSL
                    .sum(
                        JOURNAL_LINE.FUNCTIONAL_AMOUNT,
                    ).filterWhere(JOURNAL_LINE.DIRECTION.eq("DEBIT")),
                BigDecimal.ZERO,
            )
        val creditTotal =
            DSL.coalesce(
                DSL
                    .sum(
                        JOURNAL_LINE.FUNCTIONAL_AMOUNT,
                    ).filterWhere(JOURNAL_LINE.DIRECTION.eq("CREDIT")),
                BigDecimal.ZERO,
            )
        val signed =
            dsl
                .select(debitTotal, creditTotal)
                .from(JOURNAL_LINE)
                .join(GL_ACCOUNT)
                .on(GL_ACCOUNT.ORGANISATION_ID.eq(JOURNAL_LINE.ORGANISATION_ID))
                .and(GL_ACCOUNT.ID.eq(JOURNAL_LINE.GL_ACCOUNT_ID))
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(organisationId))
                .and(GL_ACCOUNT.ACCOUNT_CLASS.eq(accountClass.name))
                .and(JOURNAL_LINE.POSTING_DATE.le(asOfDate))
                .and(branchId?.let { JOURNAL_LINE.BRANCH_ID.eq(it) } ?: DSL.noCondition())
                .fetchOne { record ->
                    val debit = record.value1() ?: BigDecimal.ZERO
                    val credit = record.value2() ?: BigDecimal.ZERO
                    debit.subtract(credit)
                } ?: BigDecimal.ZERO
        return when (accountClass) {
            AccountClass.ASSET, AccountClass.EXPENSE -> signed
            AccountClass.LIABILITY, AccountClass.EQUITY, AccountClass.INCOME -> signed.negate()
        }
    }

    private companion object {
        /** The scale every `functional_amount` carries, so a bare zero cannot mismatch it. */
        const val SCALE = 6
    }
}
