package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.manual.AmendManualJournalCommand
import com.finaxis.platform.accounting.application.manual.CreateManualJournalCommand
import com.finaxis.platform.accounting.application.manual.ManualJournalDraftContent
import com.finaxis.platform.accounting.application.manual.ManualJournalService
import com.finaxis.platform.accounting.application.manual.ManualJournalTransitionCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.posting.ReversePostingCommand
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.ManualJournalLine
import com.finaxis.platform.accounting.domain.ManualJournalStatus
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MANUAL_JOURNAL_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Everything [ManualJournalIntegrationTests] needs to stand a tenant up and draft into it: an
 * active organisation with an open period, three accounts with manual posting allowed, a checker
 * holding `journal.approve`, and the readers the assertions compare against.
 *
 * Extracted from the test class so the suite can keep growing as controls are added, rather than
 * the class being capped by its own fixture.
 */
internal class ManualJournalFixture(
    private val dsl: DSLContext,
    private val manual: ManualJournalService,
    private val tenants: TenantAdminOrganisationFixture,
    private val schema: JournalSchemaFixture,
) {
    data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val periodId: UUID,
        val cashAccountId: UUID,
        val expenseAccountId: UUID,
        val checker: UUID,
    )

    fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, MAKER)
        val checker = newChecker("$label-${UUID.randomUUID()}")
        tenants.grantTenantAdmin(organisationId, checker)
        grantDirectly(organisationId, checker, AccountingPermissions.JOURNAL_APPROVE)
        grantDirectly(organisationId, MAKER, AccountingPermissions.JOURNAL_CREATE_MANUAL)
        grantDirectly(organisationId, MAKER, AccountingPermissions.JOURNAL_SUBMIT)
        // Granted up front, before any authorization read caches the maker's permission set:
        // the self-approval test needs the maker to *hold* approve and still be refused, and the
        // reversal test needs the maker - not the checker who posted - to reverse.
        grantDirectly(organisationId, MAKER, AccountingPermissions.JOURNAL_APPROVE)
        grantDirectly(organisationId, MAKER, AccountingPermissions.JOURNAL_REVERSE)
        grantDirectly(organisationId, checker, AccountingPermissions.JOURNAL_SUBMIT)
        grantDirectly(organisationId, checker, AccountingPermissions.JOURNAL_CREATE_MANUAL)
        val businessDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            )
        val branchId =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_CODE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            )
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            businessDate = businessDate,
            periodId = openPeriodCovering(organisationId, businessDate),
            cashAccountId = manualAccount(organisationId, "1010", "ASSET"),
            expenseAccountId = manualAccount(organisationId, "5010", "EXPENSE"),
            checker = checker,
        )
    }

    fun manualAccount(
        organisationId: UUID,
        code: String,
        accountClass: String,
    ): UUID {
        val id = schema.insertAccount(organisationId, code, accountClass)
        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.MANUAL_POSTING_ALLOWED, true)
            .where(GL_ACCOUNT.ID.eq(id))
            .execute()
        return id
    }

    fun openPeriodCovering(
        organisationId: UUID,
        date: LocalDate,
    ): UUID {
        val now = OffsetDateTime.now()
        val yearId =
            dsl
                .insertInto(ACCOUNTING_FISCAL_YEAR)
                .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY${date.year}")
                .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year ${date.year}")
                .set(ACCOUNTING_FISCAL_YEAR.START_DATE, date.withDayOfYear(1))
                .set(ACCOUNTING_FISCAL_YEAR.END_DATE, date.withDayOfYear(date.lengthOfYear()))
                .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now)
                .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now)
                .returning(ACCOUNTING_FISCAL_YEAR.ID)
                .fetchOne()!!
                .id!!
        return dsl
            .insertInto(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
            .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
            .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, date.monthValue)
            .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period ${date.monthValue}")
            .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, date.withDayOfMonth(1))
            .set(ACCOUNTING_FISCAL_PERIOD.END_DATE, date.withDayOfMonth(date.lengthOfMonth()))
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, "OPEN")
            .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now)
            .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now)
            .returning(ACCOUNTING_FISCAL_PERIOD.ID)
            .fetchOne()!!
            .id!!
    }

    fun content(
        tenant: Tenant,
        debit: String,
        credit: String = debit,
        title: String = "Adjustment",
        debitAccount: UUID = tenant.expenseAccountId,
        creditAccount: UUID = tenant.cashAccountId,
    ) = ManualJournalDraftContent(
        title = title,
        narrative = "Correct a mis-posting",
        branchId = tenant.branchId,
        transactionDate = null,
        valueDate = null,
        postingDate = null,
        lines =
            listOf(
                ManualJournalLine(
                    1,
                    debitAccount,
                    PostingSide.DEBIT,
                    BigDecimal(debit),
                    "KES",
                    "Expense",
                ),
                ManualJournalLine(
                    2,
                    creditAccount,
                    PostingSide.CREDIT,
                    BigDecimal(credit),
                    "KES",
                    null,
                ),
            ),
    )

    fun create(
        tenant: Tenant,
        actor: UUID,
        debit: String,
    ) = inContext(tenant, actor) {
        manual.create(CreateManualJournalCommand(context(tenant, actor), content(tenant, debit)))
    }

    fun submit(
        tenant: Tenant,
        actor: UUID,
        draftId: UUID,
    ) = inContext(tenant, actor) {
        manual.submit(ManualJournalTransitionCommand(context(tenant, actor), draftId))
    }

    fun context(
        tenant: Tenant,
        actor: UUID,
    ) = AccountingContext(tenant.organisationId, tenant.branchId, actor)

    fun <T> inContext(
        tenant: Tenant,
        actorId: UUID,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(actorId, null, null, null),
            ),
        ) { withRequestContext(block) }

    fun journalCount(tenant: Tenant) =
        dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

    fun setPeriodStatus(
        tenant: Tenant,
        status: String,
    ) {
        dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, status)
            .where(ACCOUNTING_FISCAL_PERIOD.ID.eq(tenant.periodId))
            .execute()
    }

    fun transitionNames(
        tenant: Tenant,
        journalId: UUID,
    ) = dsl
        .select(MANUAL_JOURNAL_TRANSITION_LOG.TRANSITION_NAME)
        .from(MANUAL_JOURNAL_TRANSITION_LOG)
        .where(MANUAL_JOURNAL_TRANSITION_LOG.ORGANISATION_ID.eq(tenant.organisationId))
        .and(MANUAL_JOURNAL_TRANSITION_LOG.ENTITY_ID.eq(journalId))
        .orderBy(MANUAL_JOURNAL_TRANSITION_LOG.ID)
        .fetch(MANUAL_JOURNAL_TRANSITION_LOG.TRANSITION_NAME)
        .filterNotNull()

    fun auditActions(tenant: Tenant) =
        dsl
            .select(AUDIT_EVENT.ACTION)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant.organisationId))
            .and(AUDIT_EVENT.ACTION.like("journal.%"))
            .fetch(AUDIT_EVENT.ACTION)
            .filterNotNull()
            .sorted()

    fun newChecker(label: String): UUID {
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, "manual.checker.$label")
            .set(USER_ACCOUNT.EMAIL, "manual.checker.$label@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Manual Journal Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    fun grantDirectly(
        organisationId: UUID,
        actorId: UUID,
        permissionCode: String,
    ) {
        val membershipId =
            dsl
                .select(USER_ORGANISATION_MEMBERSHIP.ID)
                .from(USER_ORGANISATION_MEMBERSHIP)
                .where(USER_ORGANISATION_MEMBERSHIP.ORGANISATION_ID.eq(organisationId))
                .and(USER_ORGANISATION_MEMBERSHIP.USER_ID.eq(actorId))
                .fetchOne(USER_ORGANISATION_MEMBERSHIP.ID)
                ?: error("no membership for $actorId in $organisationId")
        val permissionId =
            dsl
                .select(PERMISSION.ID)
                .from(PERMISSION)
                .where(PERMISSION.PERMISSION_CODE.eq(permissionCode))
                .fetchOne(PERMISSION.ID)
                ?: error("permission $permissionCode is not seeded")
        val now = OffsetDateTime.now()
        dsl
            .insertInto(MEMBERSHIP_PERMISSION)
            .set(MEMBERSHIP_PERMISSION.ORGANISATION_ID, organisationId)
            .set(MEMBERSHIP_PERMISSION.MEMBERSHIP_ID, membershipId)
            .set(MEMBERSHIP_PERMISSION.PERMISSION_ID, permissionId)
            .set(MEMBERSHIP_PERMISSION.EFFECT, "ALLOW")
            .set(MEMBERSHIP_PERMISSION.GRANTED_AT, now)
            .set(MEMBERSHIP_PERMISSION.CREATED_AT, now)
            .set(MEMBERSHIP_PERMISSION.UPDATED_AT, now)
            .onConflictDoNothing()
            .execute()
    }

    companion object {
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
