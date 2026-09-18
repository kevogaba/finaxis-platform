package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.balances.DailyBalanceProjectionService
import com.finaxis.platform.accounting.application.reporting.AccountLedgerQuery
import com.finaxis.platform.accounting.application.reporting.AccountRollupQuery
import com.finaxis.platform.accounting.application.reporting.JournalLookupQuery
import com.finaxis.platform.accounting.application.reporting.LedgerReportingService
import com.finaxis.platform.accounting.application.reporting.TrialBalanceQuery
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the accounting read models answer, proved against PostgreSQL through the service.
 *
 * Journals are inserted directly rather than posted through the engine, for the reason the
 * projection's tests give: these are about what a *report* makes of a set of journal rows, and they
 * need posting and business dates the engine would not let them choose freely.
 *
 * Every assertion here is about a number a reader would act on. The trial balance proving itself
 * balanced is not a convenience — the service refuses to return an unbalanced one, so a test that
 * gets a result at all has already asserted `INV-4` for that scope.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LedgerReportingIntegrationTests(
    private val dsl: DSLContext,
    private val reports: LedgerReportingService,
    private val projection: DailyBalanceProjectionService,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a trial balance balances and carries an opening balance into the closing column`() {
        val tenant = fixture.createTenant("report-trial-balance")
        val actor = reader(tenant.organisationId, "tb")
        postJournal(tenant, OPENING_DAY, "100.000000")
        projection.settleDay(tenant.organisationId, OPENING_DAY)
        postJournal(tenant, REPORT_DAY, "40.000000")

        val balance =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )

        assertTrue(
            balance.balanced,
            "the service refuses an unbalanced report, so this is a tautology worth keeping",
        )
        val debit = balance.lines.single { it.accountId == tenant.debitAccountId }
        assertEquals(
            BigDecimal("100.000000"),
            debit.openingSigned,
            "the opening comes from the projected checkpoint",
        )
        assertEquals(BigDecimal("40.000000"), debit.debitMovement)
        assertEquals(BigDecimal("140.000000"), debit.closingSigned)
        assertEquals(BigDecimal("140.000000"), debit.closingDebit)
        assertEquals(BigDecimal.ZERO, debit.closingCredit)
    }

    @Test
    fun `a trial balance includes an account that carries a balance but did not move`() {
        val tenant = fixture.createTenant("report-dormant-with-balance")
        val actor = reader(tenant.organisationId, "dormant")
        postJournal(tenant, OPENING_DAY, "100.000000")
        projection.settleDay(tenant.organisationId, OPENING_DAY)

        // Nothing moved on the report day at all. Both accounts still carry their opening balances,
        // and a report that listed only what moved would return two empty columns - which balance,
        // and say nothing true about the ledger.
        val balance =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )

        assertEquals(2, balance.lines.size)
        assertEquals(BigDecimal("100.000000"), balance.totalDebit)
        assertEquals(BigDecimal("100.000000"), balance.totalCredit)
    }

    @Test
    fun `a trial balance scoped to a branch counts that branch and no other`() {
        val tenant = fixture.createTenant("report-branch-scope")
        val actor = reader(tenant.organisationId, "branch")
        val other = fixture.insertBranch(tenant.organisationId, "BR2")
        postJournal(tenant, REPORT_DAY, "100.000000")
        postJournal(tenant, REPORT_DAY, "30.000000", branchId = other)

        val scoped =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    branchId = tenant.branchId,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )
        val whole =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )

        assertEquals(BigDecimal("100.000000"), scoped.totalDebit)
        assertEquals(BigDecimal("130.000000"), whole.totalDebit)
    }

    @Test
    fun `a trial balance named by fiscal period takes the period's own dates`() {
        val tenant = fixture.createTenant("report-period-scope")
        val actor = reader(tenant.organisationId, "period")
        postJournal(tenant, REPORT_DAY, "100.000000")

        val balance =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    fiscalPeriodId = tenant.fiscalPeriodId,
                ),
            )

        assertEquals(tenant.fiscalPeriodId, balance.fiscalPeriodId)
        assertEquals(REPORT_DAY.withDayOfMonth(1), balance.fromDate)
        assertEquals(BigDecimal("100.000000"), balance.totalDebit)
    }

    @Test
    fun `a reversal nets out of the trial balance rather than being hidden from it`() {
        val tenant = fixture.createTenant("report-reversal")
        val actor = reader(tenant.organisationId, "reversal")
        val original = postJournal(tenant, REPORT_DAY, "100.000000")
        reverse(tenant, original, REPORT_DAY, "100.000000")

        val balance =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )

        val debit = balance.lines.singleOrNull { it.accountId == tenant.debitAccountId }
        assertEquals(
            BigDecimal("100.000000"),
            debit?.debitMovement,
            "both journals are in the movement columns - nothing is filtered out",
        )
        assertEquals(BigDecimal("100.000000"), debit?.creditMovement)
        assertEquals(BigDecimal.ZERO.setScale(SCALE), debit?.closingSigned?.setScale(SCALE))
    }

    @Test
    fun `a ledger page carries the running balance and the next page resumes where it ended`() {
        val tenant = fixture.createTenant("report-ledger-paging")
        val actor = reader(tenant.organisationId, "ledger")
        postJournal(tenant, OPENING_DAY, "100.000000")
        projection.settleDay(tenant.organisationId, OPENING_DAY)
        repeat(LEDGER_JOURNALS) { postJournal(tenant, REPORT_DAY, "10.000000") }

        val first =
            reports.accountLedger(
                AccountLedgerQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    accountId = tenant.debitAccountId,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                    pageSize = 2,
                ),
            )

        assertEquals(BigDecimal("100.000000"), first.openingSigned)
        assertEquals(2, first.movements.size)
        assertEquals(BigDecimal("110.000000"), first.movements.first().runningBalanceSigned)
        assertEquals(BigDecimal("120.000000"), first.closingSigned)
        val cursor = assertNotNull(first.nextCursor)

        val second =
            reports.accountLedger(
                AccountLedgerQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    accountId = tenant.debitAccountId,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                    cursor = cursor,
                    carriedBalance = first.closingSigned,
                    pageSize = 2,
                ),
            )

        assertEquals(1, second.movements.size, "three journals, two on the first page")
        assertEquals(BigDecimal("130.000000"), second.closingSigned)
        assertNull(second.nextCursor, "the last page says so, so a caller looping terminates")
        assertTrue(
            first.movements.none { movement ->
                movement.lineId in second.movements.map { it.lineId }
            },
            "the keyset never repeats a row across pages",
        )
    }

    @Test
    fun `a journal drill-down shows both halves of the reversal linkage`() {
        val tenant = fixture.createTenant("report-drill-down")
        val actor = reader(tenant.organisationId, "drill", AccountingPermissions.JOURNAL_VIEW)
        val original = postJournal(tenant, REPORT_DAY, "100.000000")
        val reversal = reverse(tenant, original, REPORT_DAY, "100.000000")

        val originalDetail =
            reports.journalById(tenant.organisationId, original, actor)
        val reversalDetail =
            reports.journalByEntryNumber(
                JournalLookupQuery(tenant.organisationId, actor, entryNumberOf(reversal)),
            )

        assertNull(originalDetail.reversesJournalEntryId)
        assertEquals(
            reversal,
            originalDetail.reversedByJournalEntryId,
            "the backward link is derived",
        )
        assertEquals(original, reversalDetail.reversesJournalEntryId)
        assertNull(reversalDetail.reversedByJournalEntryId)
        assertEquals(2, originalDetail.lines.size)
        assertEquals("savings", originalDetail.sourceModule)
    }

    @Test
    fun `a hierarchy rollup totals a subtree in one descent without double counting`() {
        val tenant = fixture.createTenant("report-rollup")
        val actor = reader(tenant.organisationId, "rollup")
        val header = insertHeader(tenant.organisationId, "1000")
        reparent(tenant.debitAccountId, header)
        val sibling = fixture.insertAccount(tenant.organisationId, "1020", "ASSET")
        reparent(sibling, header)
        postJournal(tenant, REPORT_DAY, "100.000000")
        postJournal(tenant, REPORT_DAY, "25.000000", debitAccountId = sibling)

        val nodes =
            reports.rollup(
                AccountRollupQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    rootAccountId = header,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )

        val root = nodes.single { it.accountId == header }
        assertEquals(BigDecimal.ZERO, root.ownSigned, "a HEADER takes no lines of its own")
        assertEquals(BigDecimal("125.000000"), root.subtreeSigned)
        assertEquals(
            BigDecimal("100.000000"),
            nodes.single { it.accountId == tenant.debitAccountId }.subtreeSigned,
        )
        assertEquals(1, root.depth)
    }

    @Test
    fun `a report never sees another tenant's journals`() {
        val mine = fixture.createTenant("report-isolation-mine")
        val theirs = fixture.createTenant("report-isolation-theirs")
        val actor = reader(mine.organisationId, "isolation")
        postJournal(theirs, REPORT_DAY, "999.000000")

        val balance =
            reports.trialBalance(
                TrialBalanceQuery(
                    organisationId = mine.organisationId,
                    actorId = actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )

        assertTrue(balance.lines.isEmpty())
        assertEquals(BigDecimal.ZERO, balance.totalDebit)
    }

    @Test
    fun `a report refuses an impossible window rather than answering one`() {
        val tenant = fixture.createTenant("report-window-refusals")
        val actor = reader(tenant.organisationId, "window")

        assertThrowsInvalid("a range that ends before it starts") {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY.minusDays(1),
                ),
            )
        }
        assertThrowsInvalid("a range wider than a year and a day") {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY.plusYears(2),
                ),
            )
        }
        assertThrowsInvalid("neither a period nor a range") {
            reports.trialBalance(TrialBalanceQuery(tenant.organisationId, actor))
        }
        assertThrowsInvalid("both a period and a range") {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    actor,
                    fiscalPeriodId = tenant.fiscalPeriodId,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )
        }
    }

    @Test
    fun `a report refuses a branch or a page it cannot honour`() {
        val tenant = fixture.createTenant("report-bound-refusals")
        val actor = reader(tenant.organisationId, "bounds")

        assertThrowsInvalid("a branch of another organisation") {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    actor,
                    branchId = fixture.createTenant("report-other-branch").branchId,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )
        }
        assertThrowsInvalid("a page size past the platform ceiling") {
            reports.accountLedger(
                AccountLedgerQuery(
                    organisationId = tenant.organisationId,
                    actorId = actor,
                    accountId = tenant.debitAccountId,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                    pageSize = OVERSIZED_PAGE,
                ),
            )
        }
    }

    @Test
    fun `a report refuses an actor without the reporting permission`() {
        val tenant = fixture.createTenant("report-permission")
        val stranger = reader(tenant.organisationId, "stranger", permissionCode = null)

        assertThrowsForbidden {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    stranger,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )
        }
        assertThrowsForbidden {
            reports.rollup(
                AccountRollupQuery(
                    tenant.organisationId,
                    stranger,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY,
                ),
            )
        }
        assertThrowsForbidden {
            reports.journalByEntryNumber(JournalLookupQuery(tenant.organisationId, stranger, 1L))
        }
    }

    @Test
    fun `a journal that does not exist is a not-found rather than an empty answer`() {
        val tenant = fixture.createTenant("report-missing-journal")
        val actor = reader(tenant.organisationId, "missing", AccountingPermissions.JOURNAL_VIEW)

        assertThrowsNotFound {
            reports.journalByEntryNumber(
                JournalLookupQuery(tenant.organisationId, actor, MISSING_ENTRY_NUMBER),
            )
        }
    }

    private fun postJournal(
        tenant: JournalSchemaFixture.Tenant,
        postingDate: LocalDate,
        amount: String,
        branchId: UUID? = tenant.branchId,
        debitAccountId: UUID = tenant.debitAccountId,
        creditAccountId: UUID = tenant.creditAccountId,
        businessDate: LocalDate = postingDate,
    ): UUID {
        val journalId =
            fixture.insertJournalEntry(
                tenant,
                totalDebit = BigDecimal(amount),
                totalCredit = BigDecimal(amount),
                branchId = branchId,
                businessDate = businessDate,
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
        return journalId
    }

    /** The mirror image of a journal, linked to it as `JournalReversalService` links one. */
    private fun reverse(
        tenant: JournalSchemaFixture.Tenant,
        originalId: UUID,
        postingDate: LocalDate,
        amount: String,
    ): UUID {
        val journalId =
            fixture.insertJournalEntry(
                tenant,
                entryType = "REVERSAL",
                reversesJournalEntryId = originalId,
                totalDebit = BigDecimal(amount),
                totalCredit = BigDecimal(amount),
                businessDate = postingDate,
                postingDate = postingDate,
            )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 1,
            glAccountId = tenant.creditAccountId,
            direction = "DEBIT",
            amount = BigDecimal(amount),
            postingDate = postingDate,
        )
        fixture.insertJournalLine(
            tenant,
            journalId,
            lineNumber = 2,
            glAccountId = tenant.debitAccountId,
            direction = "CREDIT",
            amount = BigDecimal(amount),
            postingDate = postingDate,
        )
        return journalId
    }

    private fun entryNumberOf(journalEntryId: UUID): Long =
        dsl
            .select(JOURNAL_ENTRY.ENTRY_NUMBER)
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ID.eq(journalEntryId))
            .fetchOne(0, Long::class.java)!!

    private fun insertHeader(
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
                .set(USER_ACCOUNT.USERNAME, "reporting.$suffix")
                .set(USER_ACCOUNT.EMAIL, "reporting.$suffix@finaxis.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Reporting $label")
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
        listOfNotNull(permissionCode, permissionCode?.let { AccountingPermissions.JOURNAL_VIEW })
            .distinct()
            .forEach { code -> grant(membershipId, organisationId, code, now) }
        return userId
    }

    private fun grant(
        membershipId: UUID,
        organisationId: UUID,
        permissionCode: String,
        now: OffsetDateTime,
    ) {
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

    private fun assertThrowsInvalid(
        what: String,
        call: () -> Unit,
    ) {
        val failure = runCatching(call).exceptionOrNull()
        assertTrue(
            failure is InvalidOperationException,
            "expected $what to be refused, got ${failure ?: "a result"}",
        )
    }

    private fun assertThrowsForbidden(call: () -> Unit) {
        val failure = runCatching(call).exceptionOrNull()
        assertTrue(
            failure is ForbiddenOperationException,
            "expected a permission refusal, got ${failure ?: "a result"}",
        )
    }

    private fun assertThrowsNotFound(call: () -> Unit) {
        val failure = runCatching(call).exceptionOrNull()
        assertTrue(
            failure is ResourceNotFoundException,
            "expected a not-found, got ${failure ?: "a result"}",
        )
    }

    private companion object {
        val OPENING_DAY: LocalDate = JournalSchemaFixture.PERIOD_DAY
        val REPORT_DAY: LocalDate = JournalSchemaFixture.PERIOD_DAY.plusDays(1)
        const val LEDGER_JOURNALS = 3

        const val OVERSIZED_PAGE = 5_000
        const val MISSING_ENTRY_NUMBER = 999_999L
        const val SCALE = 6
    }
}
