package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.reporting.AccountLedgerQuery
import com.finaxis.platform.accounting.application.reporting.LedgerCursor
import com.finaxis.platform.accounting.application.reporting.LedgerReportingService
import com.finaxis.platform.accounting.application.reporting.TrialBalanceQuery
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
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
import kotlin.test.assertTrue

/**
 * The bounds a report refuses to exceed, separated from what a report computes.
 *
 * `INV-15` is a different obligation from `INV-4`, and the tests that prove it read differently:
 * nothing here asserts a number, only that a request which would make a query unbounded — or a
 * page whose running balance would be a fiction — is refused before any of it runs.
 *
 * Split out of `LedgerReportingIntegrationTests` because that class had grown past what Detekt
 * admits, and because a reader looking for "what does this refuse" should not have to read past
 * every arithmetic assertion to find out.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class LedgerReportingBoundsIntegrationTests(
    private val dsl: DSLContext,
    private val reports: LedgerReportingService,
) {
    private val fixture = JournalSchemaFixture(dsl)

    @Test
    fun `a reporting window is capped at its stated width, counting both ends`() {
        val tenant = fixture.createTenant("report-width-cap")
        val actor = reader(tenant.organisationId, "width")

        // 366 days inclusive is the widest legal range, so its last legal day is from + 365.
        reports.trialBalance(
            TrialBalanceQuery(
                tenant.organisationId,
                actor,
                fromDate = REPORT_DAY,
                toDate = REPORT_DAY.plusDays(MAXIMUM_INCLUSIVE_SPAN),
            ),
        )
        assertThrowsInvalid("one day past the cap, which the old comparison admitted") {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    actor,
                    fromDate = REPORT_DAY,
                    toDate = REPORT_DAY.plusDays(MAXIMUM_INCLUSIVE_SPAN + 1),
                ),
            )
        }
    }

    @Test
    fun `naming a fiscal period does not escape the width cap a date range obeys`() {
        val tenant = fixture.createTenant("report-period-cap")
        val actor = reader(tenant.organisationId, "period")

        // The calendar permits a period to be any length. If naming one skipped the cap, the
        // bounded-query rule would hold for every caller except the one who knows a period id.
        dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.END_DATE, REPORT_DAY.plusYears(3))
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(tenant.organisationId))
            .and(ACCOUNTING_FISCAL_PERIOD.ID.eq(tenant.fiscalPeriodId))
            .execute()

        assertThrowsInvalid("a fiscal period wider than the reporting cap") {
            reports.trialBalance(
                TrialBalanceQuery(
                    tenant.organisationId,
                    actor,
                    fiscalPeriodId = tenant.fiscalPeriodId,
                ),
            )
        }
    }

    @Test
    fun `a ledger page refuses a cursor and a carried balance that disagree`() {
        val tenant = fixture.createTenant("report-pagination-state")
        val actor = reader(tenant.organisationId, "pagination")

        // The cursor is synthetic on purpose: the refusal must happen before any row is read, so
        // whether it points at a real line is irrelevant. A cursor without the balance the
        // previous page closed at skips those rows and then restarts the running balance from the
        // range's opening, and the page that comes back looks right.
        assertThrowsInvalid("a cursor with no carried balance") {
            reports.accountLedger(
                ledgerQuery(
                    tenant,
                    actor,
                    cursor = LedgerCursor(REPORT_DAY, UUID.randomUUID()),
                ),
            )
        }
        assertThrowsInvalid("a carried balance with no cursor") {
            reports.accountLedger(
                ledgerQuery(tenant, actor, carriedBalance = BigDecimal("999.000000")),
            )
        }
    }

    private fun ledgerQuery(
        tenant: JournalSchemaFixture.Tenant,
        actor: UUID,
        cursor: LedgerCursor? = null,
        carriedBalance: BigDecimal? = null,
    ) = AccountLedgerQuery(
        organisationId = tenant.organisationId,
        actorId = actor,
        accountId = tenant.debitAccountId,
        fromDate = REPORT_DAY,
        toDate = REPORT_DAY,
        cursor = cursor,
        carriedBalance = carriedBalance,
        pageSize = 1,
    )

    private fun reader(
        organisationId: UUID,
        label: String,
    ): UUID {
        val now = OffsetDateTime.now()
        val suffix = "$label.${UUID.randomUUID()}"
        val userId =
            dsl
                .insertInto(USER_ACCOUNT)
                .set(USER_ACCOUNT.USERNAME, "bounds.$suffix")
                .set(USER_ACCOUNT.EMAIL, "bounds.$suffix@finaxis.test")
                .set(USER_ACCOUNT.DISPLAY_NAME, "Bounds $label")
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
        grant(membershipId, organisationId, AccountingPermissions.ACCOUNTING_REPORT_VIEW, now)
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

    private companion object {
        val REPORT_DAY: LocalDate = JournalSchemaFixture.PERIOD_DAY.plusDays(1)

        /**
         * The widest legal span as an offset from the first day: `MAXIMUM_RANGE_DAYS - 1`.
         *
         * Named rather than written as 365, because the point of the test that uses it is that a
         * range counts both of its ends and the cap is inclusive.
         */
        const val MAXIMUM_INCLUSIVE_SPAN = 365L
    }
}
