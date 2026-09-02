package com.finaxis.platform.accounting.schema

import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.dao.DataIntegrityViolationException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * jOOQ fixture for the issue #40 schema tests.
 *
 * Inserts through the generated tables rather than through the posting engine: the engine refuses
 * an unbalanced or cross-tenant row before the database sees it, and every assertion in the schema
 * suite is about what the database is the last line against. jOOQ rather than `JdbcTemplate`, as
 * `FiscalCalendarFixture` already does, so a renamed column fails at compile time rather than at
 * run time.
 *
 * Defaults describe one valid two-line journal - a 100.00 debit and a 100.00 credit in KES - so a
 * test that wants to prove one rejection overrides one argument and leaves the rest valid.
 */
class JournalSchemaFixture(
    private val dsl: DSLContext,
) {
    /** A tenant with one open period covering [PERIOD_DAY], a branch and two postable accounts. */
    data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val fiscalPeriodId: UUID,
        val debitAccountId: UUID,
        val creditAccountId: UUID,
    )

    /** Creates a committed organisation and returns its generated identifier. */
    fun createOrganisation(label: String): UUID =
        dsl
            .insertInto(ORGANISATION)
            .set(ORGANISATION.TENANT_CODE, "$label-${UUID.randomUUID()}")
            .set(ORGANISATION.DISPLAY_NAME, label)
            .set(ORGANISATION.COUNTRY_CODE, "KE")
            .set(ORGANISATION.BASE_CURRENCY_CODE, "KES")
            .set(ORGANISATION.TIMEZONE, "Africa/Nairobi")
            .set(ORGANISATION.STATUS, "ACTIVE")
            .set(ORGANISATION.CREATED_AT, now())
            .set(ORGANISATION.UPDATED_AT, now())
            .returning(ORGANISATION.ID)
            .fetchOne()!!
            .id!!

    /** Creates a committed tenant with the rows a journal needs. */
    fun createTenant(label: String): Tenant {
        val organisationId = createOrganisation(label)
        val yearId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_YEAR)
                .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY${PERIOD_DAY.year}")
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year ${PERIOD_DAY.year}")
                .set(ACCOUNTING_FISCAL_YEAR.START_DATE, PERIOD_DAY.withDayOfYear(1))
                .set(ACCOUNTING_FISCAL_YEAR.END_DATE, PERIOD_DAY.withMonth(12).withDayOfMonth(31))
                .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now())
                .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now())
                .returning(ACCOUNTING_FISCAL_YEAR.ID)
                .fetchOne()!!
                .id!!
        val periodId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_PERIOD)
                .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
                .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, PERIOD_DAY.monthValue)
                .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period ${PERIOD_DAY.monthValue}")
                .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, PERIOD_DAY.withDayOfMonth(1))
                .set(
                    ACCOUNTING_FISCAL_PERIOD.END_DATE,
                    PERIOD_DAY.withDayOfMonth(PERIOD_DAY.lengthOfMonth()),
                ).set(ACCOUNTING_FISCAL_PERIOD.STATUS, "OPEN")
                .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now())
                .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now())
                .returning(ACCOUNTING_FISCAL_PERIOD.ID)
                .fetchOne()!!
                .id!!
        return Tenant(
            organisationId = organisationId,
            branchId = insertBranch(organisationId, "HQ"),
            fiscalPeriodId = periodId,
            debitAccountId = insertAccount(organisationId, "1010", "ASSET"),
            creditAccountId = insertAccount(organisationId, "2010", "LIABILITY"),
        )
    }

    /** Inserts an ACTIVE branch and returns its generated identifier. */
    fun insertBranch(
        organisationId: UUID,
        code: String,
    ): UUID =
        dsl
            .insertInto(BRANCH)
            .set(BRANCH.ORGANISATION_ID, organisationId)
            .set(BRANCH.BRANCH_CODE, code)
            .set(BRANCH.BRANCH_NAME, "Branch $code")
            .set(BRANCH.BRANCH_TYPE, "HEAD_OFFICE")
            .set(BRANCH.STATUS, "ACTIVE")
            .set(BRANCH.TIMEZONE, "Africa/Nairobi")
            .set(BRANCH.CREATED_AT, now())
            .set(BRANCH.UPDATED_AT, now())
            .returning(BRANCH.ID)
            .fetchOne()!!
            .id!!

    /** Inserts an ACTIVE, POSTABLE account and returns its generated identifier. */
    fun insertAccount(
        organisationId: UUID,
        code: String,
        accountClass: String,
    ): UUID =
        dsl
            .insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ORGANISATION_ID, organisationId)
            .set(GL_ACCOUNT.ACCOUNT_CODE, code)
            .set(GL_ACCOUNT.ACCOUNT_NAME, "Account $code")
            .set(GL_ACCOUNT.ACCOUNT_CLASS, accountClass)
            .set(GL_ACCOUNT.ACCOUNT_USAGE, "POSTABLE")
            .set(GL_ACCOUNT.STATUS, "ACTIVE")
            .set(GL_ACCOUNT.CREATED_AT, now())
            .set(GL_ACCOUNT.UPDATED_AT, now())
            .returning(GL_ACCOUNT.ID)
            .fetchOne()!!
            .id!!

    /** Inserts an ACTIVE, POSTABLE control account for [kind] and returns its identifier. */
    fun insertControlAccount(
        organisationId: UUID,
        code: String,
        accountClass: String,
        kind: ControlSubledgerKind,
    ): UUID =
        dsl
            .insertInto(GL_ACCOUNT)
            .set(GL_ACCOUNT.ORGANISATION_ID, organisationId)
            .set(GL_ACCOUNT.ACCOUNT_CODE, code)
            .set(GL_ACCOUNT.ACCOUNT_NAME, "Control $code")
            .set(GL_ACCOUNT.ACCOUNT_CLASS, accountClass)
            .set(GL_ACCOUNT.ACCOUNT_USAGE, "POSTABLE")
            .set(GL_ACCOUNT.STATUS, "ACTIVE")
            .set(GL_ACCOUNT.IS_CONTROL_ACCOUNT, true)
            .set(GL_ACCOUNT.CONTROL_SUBLEDGER_KIND, kind.name)
            .set(GL_ACCOUNT.CREATED_AT, now())
            .set(GL_ACCOUNT.UPDATED_AT, now())
            .returning(GL_ACCOUNT.ID)
            .fetchOne()!!
            .id!!

    /** Inserts a posting request and returns its generated identifier. */
    @Suppress("LongParameterList")
    fun insertPostingRequest(
        organisationId: UUID,
        sourceReference: String = "ref-${UUID.randomUUID()}",
        sourceModule: String = "savings",
        status: String = "POSTED",
        postedAt: Boolean = status == "POSTED",
        branchId: UUID? = null,
        correctsPostingRequestId: UUID? = null,
        postingDate: LocalDate = PERIOD_DAY,
        fingerprint: String = VALID_FINGERPRINT,
        eventCode: String = "SAVINGS_DEPOSIT",
        sourceEntityType: String = "SAVINGS_DEPOSIT",
        id: UUID? = null,
    ): UUID =
        dsl
            .insertInto(POSTING_REQUEST)
            .apply { if (id != null) set(POSTING_REQUEST.ID, id) }
            .set(POSTING_REQUEST.ORGANISATION_ID, organisationId)
            .set(POSTING_REQUEST.BRANCH_ID, branchId)
            .set(POSTING_REQUEST.SOURCE_MODULE, sourceModule)
            .set(POSTING_REQUEST.SOURCE_ENTITY_TYPE, sourceEntityType)
            .set(POSTING_REQUEST.SOURCE_ENTITY_ID, UUID.randomUUID())
            .set(POSTING_REQUEST.SOURCE_REFERENCE, sourceReference)
            .set(POSTING_REQUEST.EVENT_CODE, eventCode)
            .set(POSTING_REQUEST.REQUEST_FINGERPRINT, fingerprint)
            .set(POSTING_REQUEST.CORRECTS_POSTING_REQUEST_ID, correctsPostingRequestId)
            .set(POSTING_REQUEST.BUSINESS_DATE, PERIOD_DAY)
            .set(POSTING_REQUEST.TRANSACTION_DATE, PERIOD_DAY)
            .set(POSTING_REQUEST.VALUE_DATE, PERIOD_DAY)
            .set(POSTING_REQUEST.POSTING_DATE, postingDate)
            .set(POSTING_REQUEST.CURRENCY_CODE, "KES")
            .set(POSTING_REQUEST.STATUS, status)
            .set(POSTING_REQUEST.POSTED_AT, if (postedAt) now() else null)
            .set(POSTING_REQUEST.CREATED_AT, now())
            .set(POSTING_REQUEST.UPDATED_AT, now())
            .returning(POSTING_REQUEST.ID)
            .fetchOne()!!
            .id!!

    /** Inserts a journal header and returns its generated identifier. */
    @Suppress("LongParameterList")
    fun insertJournalEntry(
        tenant: Tenant,
        postingRequestId: UUID = insertPostingRequest(tenant.organisationId),
        entryNumber: Long = nextEntryNumber(tenant.organisationId),
        entryType: String = "STANDARD",
        reversesJournalEntryId: UUID? = null,
        totalDebit: BigDecimal = HUNDRED,
        totalCredit: BigDecimal = HUNDRED,
        lineCount: Int = 2,
        fiscalPeriodId: UUID = tenant.fiscalPeriodId,
        branchId: UUID? = tenant.branchId,
        id: UUID? = null,
    ): UUID =
        dsl
            .insertInto(JOURNAL_ENTRY)
            .apply { if (id != null) set(JOURNAL_ENTRY.ID, id) }
            .set(JOURNAL_ENTRY.ORGANISATION_ID, tenant.organisationId)
            .set(JOURNAL_ENTRY.BRANCH_ID, branchId)
            .set(JOURNAL_ENTRY.POSTING_REQUEST_ID, postingRequestId)
            .set(JOURNAL_ENTRY.FISCAL_PERIOD_ID, fiscalPeriodId)
            .set(JOURNAL_ENTRY.ENTRY_NUMBER, entryNumber)
            .set(JOURNAL_ENTRY.ENTRY_TYPE, entryType)
            .set(JOURNAL_ENTRY.REVERSES_JOURNAL_ENTRY_ID, reversesJournalEntryId)
            .set(JOURNAL_ENTRY.BUSINESS_DATE, PERIOD_DAY)
            .set(JOURNAL_ENTRY.TRANSACTION_DATE, PERIOD_DAY)
            .set(JOURNAL_ENTRY.VALUE_DATE, PERIOD_DAY)
            .set(JOURNAL_ENTRY.POSTING_DATE, PERIOD_DAY)
            .set(JOURNAL_ENTRY.CURRENCY_CODE, "KES")
            .set(JOURNAL_ENTRY.FUNCTIONAL_CURRENCY_CODE, "KES")
            .set(JOURNAL_ENTRY.TOTAL_DEBIT_FUNCTIONAL, totalDebit)
            .set(JOURNAL_ENTRY.TOTAL_CREDIT_FUNCTIONAL, totalCredit)
            .set(JOURNAL_ENTRY.LINE_COUNT, lineCount)
            .set(JOURNAL_ENTRY.POSTED_AT, now())
            .set(JOURNAL_ENTRY.CREATED_AT, now())
            .returning(JOURNAL_ENTRY.ID)
            .fetchOne()!!
            .id!!

    /** Inserts one journal line and returns its generated identifier. */
    @Suppress("LongParameterList")
    fun insertJournalLine(
        tenant: Tenant,
        journalEntryId: UUID,
        lineNumber: Int = 1,
        glAccountId: UUID = tenant.debitAccountId,
        direction: String = "DEBIT",
        amount: BigDecimal = HUNDRED,
        functionalAmount: BigDecimal = amount,
        exchangeRate: BigDecimal = BigDecimal.ONE,
        fiscalPeriodId: UUID = tenant.fiscalPeriodId,
        subledgerReference: String? = null,
    ): UUID =
        dsl
            .insertInto(JOURNAL_LINE)
            .set(JOURNAL_LINE.ORGANISATION_ID, tenant.organisationId)
            .set(JOURNAL_LINE.JOURNAL_ENTRY_ID, journalEntryId)
            .set(JOURNAL_LINE.LINE_NUMBER, lineNumber)
            .set(JOURNAL_LINE.GL_ACCOUNT_ID, glAccountId)
            .set(JOURNAL_LINE.BRANCH_ID, tenant.branchId)
            .set(JOURNAL_LINE.FISCAL_PERIOD_ID, fiscalPeriodId)
            .set(JOURNAL_LINE.POSTING_DATE, PERIOD_DAY)
            .set(JOURNAL_LINE.DIRECTION, direction)
            .set(JOURNAL_LINE.CURRENCY_CODE, "KES")
            .set(JOURNAL_LINE.AMOUNT, amount)
            .set(JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE, "KES")
            .set(JOURNAL_LINE.FUNCTIONAL_AMOUNT, functionalAmount)
            .set(JOURNAL_LINE.EXCHANGE_RATE, exchangeRate)
            .set(JOURNAL_LINE.SOURCE_MODULE, "savings")
            .set(JOURNAL_LINE.SUBLEDGER_REFERENCE, subledgerReference)
            .set(JOURNAL_LINE.CREATED_AT, now())
            .returning(JOURNAL_LINE.ID)
            .fetchOne()!!
            .id!!

    /** A balanced two-line journal, the positive control every rejection test stands beside. */
    fun insertBalancedJournal(tenant: Tenant): UUID {
        val journalId = insertJournalEntry(tenant)
        insertJournalLine(tenant, journalId, lineNumber = 1, direction = "DEBIT")
        insertJournalLine(
            tenant,
            journalId,
            lineNumber = 2,
            glAccountId = tenant.creditAccountId,
            direction = "CREDIT",
        )
        return journalId
    }

    private fun nextEntryNumber(organisationId: UUID): Long =
        (
            dsl
                .select(DSL.max(JOURNAL_ENTRY.ENTRY_NUMBER))
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
                .fetchOne(0, Long::class.java) ?: 0L
        ) + 1

    private fun now(): OffsetDateTime = OffsetDateTime.now()

    companion object {
        /** The posting date every scenario uses; the fixture period covers it. */
        val PERIOD_DAY: LocalDate = LocalDate.of(2026, 8, 15)
        val HUNDRED: BigDecimal = BigDecimal("100.000000")

        /** Sixty-four lower-case hex characters, as `chk_posting_request_fingerprint` demands. */
        const val VALID_FINGERPRINT =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

        /**
         * Asserts that [block] is rejected by the named database constraint.
         *
         * The constraint name is asserted, not merely the exception type. Without it a test passes
         * when a *different* constraint fires — a typo in the fixture, or a unique key catching a
         * row the check under test would have let through — which is how a schema test comes to
         * report success for the wrong reason.
         */
        fun assertViolates(
            constraint: String,
            block: () -> Unit,
        ) {
            val failure =
                try {
                    block()
                    fail("expected $constraint to reject this row, but the insert succeeded")
                } catch (ex: DataIntegrityViolationException) {
                    ex
                }

            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }
                    .any { it.message?.contains(constraint) == true },
                "expected $constraint to be the violated constraint, but the failure was: " +
                    failure.mostSpecificCause.message,
            )
        }
    }
}
