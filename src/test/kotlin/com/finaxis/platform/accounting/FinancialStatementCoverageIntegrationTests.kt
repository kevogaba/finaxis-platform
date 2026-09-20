package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.reporting.BalanceSheetQuery
import com.finaxis.platform.accounting.application.reporting.FinancialStatementService
import com.finaxis.platform.accounting.application.reporting.IncomeStatementQuery
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Statement coverage named explicitly as missing from [FinancialStatementIntegrationTests]: a
 * genuine `EQUITY` account, [FinancialStatementService]'s `proven()` refusal actually throwing
 * instead of only ever being reached by a sheet it already let through, and the income
 * statement's fiscal-period path actually resolving a window rather than being reached only by
 * the "both a period and a range" rejection test.
 *
 * Split into its own class rather than added to [FinancialStatementIntegrationTests], for the same
 * reason `LedgerReportingBoundsIntegrationTests` stands beside `LedgerReportingIntegrationTests`
 * rather than inside it: that class was already close to Detekt's `LargeClass` ceiling, and these
 * three tests would have pushed it over.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FinancialStatementCoverageIntegrationTests(
    private val dsl: DSLContext,
    private val statements: FinancialStatementService,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `equity carries its credit-normal balance and its own term in the total`() {
        val tenant = fixture.createTenant("statement-equity")
        val actor = reader(tenant.organisationId, "equity")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val capital = account(tenant.organisationId, "3100", "EQUITY")
        // An owner's capital contribution: cash up, equity up - a credit to a credit-normal
        // account, the case no test in the main statement suite exercised.
        post(tenant, DAY, cash, capital, "600.000000")

        val sheet =
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY),
            )

        val line = sheet.equity.lines.single { it.accountId == capital }
        assertEquals(
            BigDecimal("-600.000000"),
            line.signedAmount,
            "the ledger's own convention: a credit balance is negative",
        )
        assertEquals(
            BigDecimal("600.000000"),
            line.amount,
            "credit-normal, so the section negates the signed balance back to positive",
        )
        assertEquals(BigDecimal("600.000000"), sheet.equity.total)
        assertEquals(
            BigDecimal("600.000000"),
            sheet.totalLiabilitiesAndEquity,
            "liabilities and current earnings are both zero, so equity alone carries the total",
        )
        assertEquals(sheet.totalAssets, sheet.totalLiabilitiesAndEquity)
    }

    @Test
    fun `a balance sheet refuses to return once a corrupted ledger no longer balances`() {
        val tenant = fixture.createTenant("statement-corrupted")
        val actor = reader(tenant.organisationId, "corrupted")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val deposits = account(tenant.organisationId, "2100", "LIABILITY")
        val journalId = post(tenant, DAY, cash, deposits, "100.000000")

        // Corrupt one line the way a bug would, bypassing the posting engine entirely: a number
        // that no longer matches the other side of its own journal. `proven()` throws whenever
        // `!statement.balanced`, so every sheet the service successfully returns is balanced by
        // construction - the only way to see it actually catch anything is to give it a ledger
        // that no longer is.
        dsl
            .update(JOURNAL_LINE)
            .set(JOURNAL_LINE.FUNCTIONAL_AMOUNT, BigDecimal("999.000000"))
            .where(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalId))
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(cash))
            .execute()

        val failure =
            assertFailsWith<ConflictException> {
                statements.balanceSheet(
                    BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY),
                )
            }
        assertEquals(PostingErrorCodes.BALANCE_SHEET_UNBALANCED, failure.code)
    }

    @Test
    fun `an income statement named by fiscal period takes the period's own dates`() {
        val tenant = fixture.createTenant("statement-period-scope")
        val actor = reader(tenant.organisationId, "period")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val fees = account(tenant.organisationId, "4100", "INCOME")
        post(tenant, DAY, cash, fees, "220.000000")

        // The period path (`periodWindow`) is otherwise reached only by the "both a period and a
        // range" rejection test, which never lets it resolve an actual window - mirroring the
        // trial balance's own period-scope test, this names a real fiscal period and checks what
        // it resolves to rather than only that naming both forms together is refused.
        val statement =
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    actor,
                    fiscalPeriodId = tenant.fiscalPeriodId,
                ),
            )

        assertEquals(tenant.fiscalPeriodId, statement.fiscalPeriodId)
        assertEquals(DAY.withDayOfMonth(1), statement.fromDate)
        assertEquals(DAY.withDayOfMonth(DAY.lengthOfMonth()), statement.toDate)
        assertEquals(BigDecimal("220.000000"), statement.netIncome, "the period's only posting")
    }

    /** Writes one balanced two-line journal directly through the fixture and returns its id. */
    private fun post(
        tenant: JournalSchemaFixture.Tenant,
        postingDate: LocalDate,
        debitAccountId: UUID,
        creditAccountId: UUID,
        amount: String,
    ): UUID {
        val journalId =
            fixture.insertJournalEntry(
                tenant,
                totalDebit = BigDecimal(amount),
                totalCredit = BigDecimal(amount),
                businessDate = postingDate,
                postingDate = postingDate,
            )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 1,
            glAccountId = debitAccountId,
            direction = "DEBIT",
            amount = BigDecimal(amount),
            postingDate = postingDate,
        )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 2,
            glAccountId = creditAccountId,
            direction = "CREDIT",
            amount = BigDecimal(amount),
            postingDate = postingDate,
        )
        return journalId
    }

    private fun account(
        organisationId: UUID,
        code: String,
        accountClass: String,
    ) = fixture.insertAccount(organisationId, code, accountClass)

    /** An active member of the tenant, holding `accounting.report.view`. */
    private fun reader(
        organisationId: UUID,
        label: String,
    ): UUID {
        val now = OffsetDateTime.now()
        val suffix = "$label.${UUID.randomUUID()}"
        val userId =
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.USERNAME, "statements.coverage.$suffix")
                .set(USER_ACCOUNT.EMAIL, "statements.coverage.$suffix@finaxis.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Statements Coverage $label")
                .set(USER_ACCOUNT.STATUS, "ACTIVE")
                .set(USER_ACCOUNT.CREATED_AT, now)
                .set(USER_ACCOUNT.UPDATED_AT, now)
                .returning(USER_ACCOUNT.ID)
                .fetchOne()!!
                .id!!
        val membershipId =
            dsl
                .insertInto(USER_ORGANISATION_MEMBERSHIP)
                .set(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID, organisationId)
                .set(USER_ORGANISATION_MEMBERSHIP.USER_ID, userId)
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_STATUS, "ACTIVE")
                .set(USER_ORGANISATION_MEMBERSHIP.MEMBERSHIP_TYPE, "STAFF")
                .set(USER_ORGANISATION_MEMBERSHIP.JOINED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.CREATED_AT, now)
                .set(USER_ORGANISATION_MEMBERSHIP.UPDATED_AT, now)
                .returning(USER_ORGANISATION_MEMBERSHIP.ID)
                .fetchOne()!!
                .id!!
        val permissionId =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(AccountingPermissions.ACCOUNTING_REPORT_VIEW))
                .fetchOne(PERMISSION.ID)
                ?: error("permission ${AccountingPermissions.ACCOUNTING_REPORT_VIEW} is not seeded")
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
            .set(MEMBERSHIP_PERMISSION.EFFECT, "ALLOW")
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .execute()
        return userId
    }

    private companion object {
        val DAY: LocalDate = JournalSchemaFixture.PERIOD_DAY
    }
}
