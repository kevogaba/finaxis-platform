package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.posting.ReversePostingCommand
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Everything [JournalReversalIntegrationTests] and its sibling need to stand a tenant up and post
 * into it: an active organisation with an open period and two accounts, a checker who holds
 * `journal.reverse`, the two posting helpers, and the row readers the assertions compare against.
 *
 * Extracted from the test class rather than duplicated into the second one, so the two suites
 * prove different things about one fixture instead of drifting apart.
 *
 * [postings] is the production [PostingTransactionBoundary], not a `TransactionTemplate` a suite
 * built for itself. The engine refuses any transaction below `SERIALIZABLE`, and a template a
 * suite raised on its own would be a second, unverified answer to the question of what isolation a
 * posting runs at - green here while production entered through the boundary, or the reverse.
 */
internal class JournalReversalFixture(
    private val dsl: DSLContext,
    private val engine: PostingEngine,
    private val tenants: TenantAdminOrganisationFixture,
    private val schema: JournalSchemaFixture,
    private val postings: PostingTransactionBoundary,
) {
    data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val periodId: UUID,
        val debitAccountId: UUID,
        val creditAccountId: UUID,
        val checker: UUID,
    )

    fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, MAKER)
        val checker = newChecker("$label-${UUID.randomUUID()}")
        tenants.grantTenantAdmin(organisationId, checker)
        grantDirectly(organisationId, checker, AccountingPermissions.JOURNAL_REVERSE)
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
        val periodId = openPeriodCovering(organisationId, businessDate)
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            businessDate = businessDate,
            periodId = periodId,
            debitAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
            creditAccountId = schema.insertAccount(organisationId, "2010", "LIABILITY"),
            checker = checker,
        )
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

    /** Posts the journal a scenario is about to reverse, in a transaction the boundary owns. */
    fun postOriginal(
        tenant: Tenant,
        reference: String = "dep-${uuidV7()}",
        debit: String = "500.00",
    ): PostingReceipt =
        inContext(tenant, MAKER) {
            postings.execute("Posting a reversal scenario's original") {
                postExplicit(tenant, reference, debit)
            }
        }

    fun postExplicit(
        tenant: Tenant,
        reference: String,
        debit: String,
        corrects: UUID? = null,
    ): PostingReceipt =
        engine.post(
            LedgerPostingRequest(
                context = AccountingContext(tenant.organisationId, tenant.branchId, MAKER),
                source =
                    AccountingSourceReference("savings", "SAVINGS_DEPOSIT", uuidV7(), reference),
                eventCode = "SAVINGS_DEPOSIT",
                entryType = JournalEntryType.STANDARD,
                correctsPostingRequestId = corrects,
            ),
        ) {
            ResolvedLegs(
                listOf(
                    PostingLeg(tenant.debitAccountId, PostingSide.DEBIT, kes(debit), "Cash"),
                    PostingLeg(
                        tenant.creditAccountId,
                        PostingSide.CREDIT,
                        kes(debit),
                        subledgerReference = "SAV-0001",
                    ),
                ),
                null,
            )
        }

    fun command(
        tenant: Tenant,
        journalEntryId: UUID,
        reason: String,
        dates: PostingDateRequest = PostingDateRequest(),
    ) = ReversePostingCommand(
        context =
            AccountingContext(
                tenant.organisationId,
                tenant.branchId,
                RequestContexts.actor()!!.userId,
            ),
        originalJournalEntryId = journalEntryId,
        reason = reason,
        dates = dates,
    )

    fun kes(amount: String) = MonetaryAmount(BigDecimal(amount), "KES")

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

    /** The same, with no branch selected: a head-office session. */
    fun <T> atTenantLevel(
        tenant: Tenant,
        actorId: UUID,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                actor = ActorContext(actorId, null, null, null),
            ),
        ) { withRequestContext(block) }

    fun rowsOf(
        tenant: Tenant,
        journalEntryId: UUID,
    ): List<String> =
        dsl
            .selectFrom(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
            .and(JOURNAL_ENTRY.ID.eq(journalEntryId))
            .fetch { it.toString() } +
            dsl
                .selectFrom(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(journalEntryId))
                .orderBy(JOURNAL_LINE.LINE_NUMBER)
                .fetch { it.toString() }

    /**
     * The `objid` of the advisory lock [PostgresJournalReversalLock] takes for this journal.
     *
     * Derived exactly as production derives it - `AdvisoryLockNamespace.objectId` over the same
     * `"$organisationId:$journalEntryId"` string. Deriving it any other way, in particular with
     * SQL's `hashtextextended`, yields a different key and silently loses the mutual exclusion the
     * scenarios using it are trying to observe.
     */
    fun reversalLockKey(
        tenant: Tenant,
        journalEntryId: UUID,
    ) = AdvisoryLockNamespace.objectId("${tenant.organisationId}:$journalEntryId")

    /**
     * Takes the journal-reversal advisory lock for [objectId] on the calling transaction, so a
     * test can hold the key a reverser needs and watch the reverser park on it.
     */
    fun takeReversalLock(objectId: Int) {
        dsl.execute(
            "select pg_advisory_xact_lock(?, ?)",
            AdvisoryLockNamespace.ACCOUNTING_JOURNAL_REVERSAL,
            objectId,
        )
    }

    fun reversalsOf(
        tenant: Tenant,
        journalEntryId: UUID,
    ) = dsl.fetchCount(
        JOURNAL_ENTRY,
        JOURNAL_ENTRY.ORGANISATION_ID
            .eq(tenant.organisationId)
            .and(JOURNAL_ENTRY.REVERSES_JOURNAL_ENTRY_ID.eq(journalEntryId)),
    )

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

    fun newChecker(label: String): UUID {
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, "journal.checker.$label")
            .set(USER_ACCOUNT.EMAIL, "journal.checker.$label@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Journal Checker")
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
        /** The `V3` bootstrap administrator, who posts every original here. */
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        const val LATCH_TIMEOUT_SECONDS = 10L
        const val FUTURE_TIMEOUT_SECONDS = 60L
    }
}
