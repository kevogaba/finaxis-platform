package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.reporting.BalanceSheetQuery
import com.finaxis.platform.accounting.application.reporting.FinancialStatementService
import com.finaxis.platform.accounting.application.reporting.IncomeStatementQuery
import com.finaxis.platform.accounting.application.reporting.LedgerReportingService
import com.finaxis.platform.accounting.application.reporting.StatementSection
import com.finaxis.platform.accounting.application.reporting.TrialBalanceQuery
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
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
import kotlin.test.assertTrue

/**
 * What the balance sheet and the income statement say, proved against PostgreSQL.
 *
 * The assertions that matter are the two ties: the balance sheet balances, and the income statement
 * agrees with the trial balance over the same window. Both are properties of a sound ledger rather
 * than of this code, which is what makes them worth asserting — a statement that satisfies them is
 * one whose classification, sign handling and roll-up are all right at once.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FinancialStatementIntegrationTests(
    private val dsl: DSLContext,
    private val statements: FinancialStatementService,
    private val reports: LedgerReportingService,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a balance sheet balances, with unclosed earnings carried in equity`() {
        val tenant = fixture.createTenant("statement-balance-sheet")
        val actor = reader(tenant.organisationId, "bs")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val deposits = account(tenant.organisationId, "2100", "LIABILITY")
        val fees = account(tenant.organisationId, "4100", "INCOME")
        val wages = account(tenant.organisationId, "5100", "EXPENSE")
        // A member deposits 1,000: cash up, deposits owed up.
        post(tenant, DAY, cash, deposits, "1000.000000")
        // A fee of 30 is charged: cash up, fee income up.
        post(tenant, DAY, cash, fees, "30.000000")
        // Wages of 12 are paid: expense up, cash down.
        post(tenant, DAY, wages, cash, "12.000000")

        val sheet =
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY),
            )

        assertTrue(sheet.balanced, "the service refuses an unbalanced sheet, so this is the proof")
        assertEquals(
            BigDecimal("1018.000000"),
            sheet.totalAssets,
            "1,000 deposited plus a 30 fee less 12 of wages",
        )
        assertEquals(BigDecimal("1000.000000"), sheet.liabilities.total)
        assertEquals(
            BigDecimal("18.000000"),
            sheet.currentEarnings,
            "30 of income less 12 of expense, unclosed and therefore shown in equity",
        )
        assertEquals(sheet.totalAssets, sheet.totalLiabilitiesAndEquity)
    }

    @Test
    fun `an income statement reconciles to the trial balance's income and expense movements`() {
        val tenant = fixture.createTenant("statement-income")
        val actor = reader(tenant.organisationId, "is")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val fees = account(tenant.organisationId, "4100", "INCOME")
        val wages = account(tenant.organisationId, "5100", "EXPENSE")
        post(tenant, DAY, cash, fees, "250.000000")
        post(tenant, DAY, wages, cash, "90.000000")

        val statement =
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = DAY,
                    toDate = DAY,
                ),
            )
        val trial =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    fromDate = DAY,
                    toDate = DAY,
                ),
            )

        // The trial balance's movement columns, restated in the income statement's presentation
        // signs. These are the same read, so a disagreement would mean the statement's
        // classification or sign handling is wrong rather than its arithmetic.
        val trialIncome =
            trial.lines
                .filter { it.accountClass == AccountClass.INCOME }
                .fold(BigDecimal.ZERO) { sum, line ->
                    sum.add(line.creditMovement).subtract(line.debitMovement)
                }
        val trialExpense =
            trial.lines
                .filter { it.accountClass == AccountClass.EXPENSE }
                .fold(BigDecimal.ZERO) { sum, line ->
                    sum.add(line.debitMovement).subtract(line.creditMovement)
                }

        assertEquals(trialIncome, statement.income.total)
        assertEquals(trialExpense, statement.expenses.total)
        assertEquals(BigDecimal("160.000000"), statement.netIncome, "250 earned less 90 spent")
    }

    @Test
    fun `net income for a window equals the balance sheet's earnings at its end`() {
        val tenant = fixture.createTenant("statement-tie")
        val actor = reader(tenant.organisationId, "tie")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val fees = account(tenant.organisationId, "4100", "INCOME")
        val wages = account(tenant.organisationId, "5100", "EXPENSE")
        post(tenant, DAY, cash, fees, "400.000000")
        post(tenant, DAY.plusDays(1), wages, cash, "175.000000")

        val statement =
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = DAY,
                    toDate = DAY.plusDays(1),
                ),
            )
        val sheet =
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY.plusDays(1)),
            )

        // The window runs from the tenant's first posting, so the period's net income and the
        // earnings the sheet carries are the same number. This is the tie between the two
        // statements; without it they would be two independent reports of one ledger.
        assertEquals(statement.netIncome, sheet.currentEarnings)
    }

    @Test
    fun `a reversal moves through both statements without being hidden from either`() {
        val tenant = fixture.createTenant("statement-reversal")
        val actor = reader(tenant.organisationId, "rev")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        val fees = account(tenant.organisationId, "4100", "INCOME")
        post(tenant, DAY, cash, fees, "500.000000")
        // The mirror image, which is what a reversal writes: the same accounts, opposite sides.
        post(tenant, DAY, fees, cash, "500.000000")

        val sheet =
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY),
            )
        val statement =
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = DAY,
                    toDate = DAY,
                ),
            )

        assertTrue(sheet.balanced)
        assertEquals(
            BigDecimal.ZERO.setScale(SCALE),
            statement.netIncome.setScale(SCALE),
            "the two halves net by arithmetic rather than by either being filtered out",
        )
        assertTrue(
            sheet.assets.lines.none { it.accountId == cash },
            "an account whose postings cancel carries no balance and so prints no line",
        )
    }

    @Test
    fun `only postable accounts carry lines, so a roll-up cannot double count`() {
        val tenant = fixture.createTenant("statement-header")
        val actor = reader(tenant.organisationId, "header")
        val header = header(tenant.organisationId, "1000")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        reparent(cash, header)
        post(tenant, DAY, cash, tenant.creditAccountId, "700.000000")

        val sheet =
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY),
            )

        assertTrue(
            sheet.assets.lines.none { it.accountId == header },
            "a HEADER holds no journal line, so it holds no balance and prints none",
        )
        val line = sheet.assets.lines.single { it.accountId == cash }
        assertEquals(header, line.parentAccountId, "the nesting is carried without a subtotal line")
        assertEquals(2, line.depth)
        assertEquals(BigDecimal("700.000000"), sheet.assets.total)
    }

    @Test
    fun `a statement never sees another tenant's ledger`() {
        val mine = fixture.createTenant("statement-isolation-mine")
        val theirs = fixture.createTenant("statement-isolation-theirs")
        val actor = reader(mine.organisationId, "isolation")
        post(theirs, DAY, theirs.debitAccountId, theirs.creditAccountId, "999.000000")

        val sheet =
            statements.balanceSheet(
                BalanceSheetQuery(mine.organisationId, actor, asOfDate = DAY),
            )

        assertEquals(BigDecimal.ZERO, sheet.totalAssets)
        assertTrue(sheet.assets.lines.isEmpty())
    }

    @Test
    fun `a branch-scoped sheet balances on that branch's own postings`() {
        val tenant = fixture.createTenant("statement-branch")
        val actor = reader(tenant.organisationId, "branch")
        val other = fixture.insertBranch(tenant.organisationId, "BR2")
        val cash = account(tenant.organisationId, "1100", "ASSET")
        post(tenant, DAY, cash, tenant.creditAccountId, "300.000000")
        post(tenant, DAY, cash, tenant.creditAccountId, "80.000000", branchId = other)

        val scoped =
            statements.balanceSheet(
                BalanceSheetQuery(
                    tenant.organisationId,
                    actor,
                    asOfDate = DAY,
                    branchId = tenant.branchId,
                ),
            )
        val whole =
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, actor, asOfDate = DAY),
            )

        assertTrue(
            scoped.balanced,
            "a branch's own postings balance, because a journal cannot span",
        )
        assertEquals(BigDecimal("300.000000"), scoped.totalAssets)
        assertEquals(BigDecimal("380.000000"), whole.totalAssets)
    }

    @Test
    fun `statements refuse an impossible scope and an actor without the permission`() {
        val tenant = fixture.createTenant("statement-refusals")
        val actor = reader(tenant.organisationId, "refusals")
        val stranger = reader(tenant.organisationId, "stranger", permissionCode = null)

        assertThrows<InvalidOperationException>("a range that ends before it starts") {
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = DAY,
                    toDate = DAY.minusDays(1),
                ),
            )
        }
        assertThrows<InvalidOperationException>("neither a period nor a range") {
            statements.incomeStatement(IncomeStatementQuery(tenant.organisationId, actor))
        }
        assertThrows<InvalidOperationException>("both a period and a range") {
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    actor,
                    fiscalPeriodId = tenant.fiscalPeriodId,
                    fromDate = DAY,
                    toDate = DAY,
                ),
            )
        }
        assertThrows<ForbiddenOperationException>("a balance sheet without the permission") {
            statements.balanceSheet(
                BalanceSheetQuery(tenant.organisationId, stranger, asOfDate = DAY),
            )
        }
        assertThrows<ForbiddenOperationException>("an income statement without the permission") {
            statements.incomeStatement(
                IncomeStatementQuery(
                    tenant.organisationId,
                    stranger,
                    fromDate = DAY,
                    toDate = DAY,
                ),
            )
        }
    }

    @Test
    fun `every account class reaches a section, so nothing is unclassified`() {
        AccountClass.entries.forEach { accountClass ->
            val section = StatementSection.of(accountClass)
            assertTrue(
                section in StatementSection.entries,
                "$accountClass must classify onto a statement without an account number",
            )
        }
    }

    private inline fun <reified T : Throwable> assertThrows(
        what: String,
        call: () -> Unit,
    ) {
        val failure = runCatching(call).exceptionOrNull()
        val expected = T::class.simpleName
        assertTrue(
            failure is T,
            "expected $what to be refused with $expected, got ${failure ?: "a result"}",
        )
    }

    private fun post(
        tenant: JournalSchemaFixture.Tenant,
        postingDate: LocalDate,
        debitAccountId: UUID,
        creditAccountId: UUID,
        amount: String,
        branchId: UUID? = tenant.branchId,
    ) {
        val journalId =
            fixture.insertJournalEntry(
                tenant,
                totalDebit = BigDecimal(amount),
                totalCredit = BigDecimal(amount),
                branchId = branchId,
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
            branchId = branchId,
        )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 2,
            glAccountId = creditAccountId,
            direction = "CREDIT",
            amount = BigDecimal(amount),
            postingDate = postingDate,
            branchId = branchId,
        )
    }

    private fun account(
        organisationId: UUID,
        code: String,
        accountClass: String,
    ) = fixture.insertAccount(organisationId, code, accountClass)

    private fun header(
        organisationId: UUID,
        code: String,
    ): UUID =
        dsl
            .insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ORGANISATION_ID, organisationId)
            .set(GL_ACCOUNT.ACCOUNT_CODE, code)
            .set(GL_ACCOUNT.ACCOUNT_NAME, "Header $code")
            .set(GL_ACCOUNT.ACCOUNT_CLASS, "ASSET")
            .set(GL_ACCOUNT.ACCOUNT_USAGE, "HEADER")
            .set(GL_ACCOUNT.STATUS, "ACTIVE")
            .set(GL_ACCOUNT.CREATED_AT, OffsetDateTime.now())
            .set(GL_ACCOUNT.UPDATED_AT, OffsetDateTime.now())
            .returning(GL_ACCOUNT.ID)
            .fetchOne()!!
            .id!!

    private fun reparent(
        accountId: UUID,
        parentId: UUID,
    ) {
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.PARENT_ACCOUNT_ID, parentId)
            .where(GL_ACCOUNT.ID.eq(accountId))
            .execute()
    }

    /** An active member of the tenant, holding [permissionCode] when one is named. */
    private fun reader(
        organisationId: UUID,
        label: String,
        permissionCode: String? = AccountingPermissions.ACCOUNTING_REPORT_VIEW,
    ): UUID {
        val now = OffsetDateTime.now()
        val suffix = "$label.${UUID.randomUUID()}"
        val userId =
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.USERNAME, "statements.$suffix")
                .set(USER_ACCOUNT.EMAIL, "statements.$suffix@finaxis.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Statements $label")
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
        if (permissionCode != null) {
            val permissionId =
                dsl
                    .select(PERMISSION.ID)
                    .from(PERMISSION)
                    .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                    .fetchOne(PERMISSION.ID)
                    ?: error("permission $permissionCode is not seeded")
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
        }
        return userId
    }

    private companion object {
        val DAY: LocalDate = JournalSchemaFixture.PERIOD_DAY
        const val SCALE = 6
    }
}
