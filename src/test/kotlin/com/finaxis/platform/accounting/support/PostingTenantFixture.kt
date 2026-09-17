package com.finaxis.platform.accounting.support

import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.CorrelationContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * A tenant that can actually be posted into, and the smallest way to post into it.
 *
 * Everything a database-backed posting suite needs before it can assert anything at all: an ACTIVE
 * organisation with a business date and a head office, an OPEN fiscal period covering that date,
 * two postable accounts, the ambient request context the engine reconciles a caller's claim
 * against, and a balanced two-line posting through the real [PostingEngine].
 *
 * It exists because that preamble is identical in every posting suite and was being copied between
 * them. A copy is not merely longer: it drifts. A period built from a hard-coded date rather than
 * the tenant's real business date, or a source reference whose entity id is fresh on every call,
 * turns a suite green for reasons that have nothing to do with what it claims to prove - and the
 * copy that drifts is always the one nobody was reading.
 *
 * [JournalSchemaFixture] is the sibling this does not replace: that one writes journals *around*
 * the engine, to hold the database to its own constraints. This one only ever writes through the
 * production path.
 */
internal class PostingTenantFixture(
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
    private val engine: PostingEngine,
    private val actorId: UUID,
) {
    private val organisations = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)

    /** A provisioned tenant, with everything a posting reads resolved up front. */
    data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val periodId: UUID,
        val debitAccountId: UUID,
        val creditAccountId: UUID,
    )

    /**
     * An ACTIVE organisation as provisioning leaves it - business date, head office, sequences -
     * plus what accounting needs on top: an OPEN period covering the business date and two postable
     * accounts. The period is built from the *real* business date, because the engine reads that
     * date and resolves the period from it.
     */
    fun provisionTenant(label: String): Tenant {
        val organisationId = organisations.createActiveOrganisation(label, actorId)
        val businessDate =
            requireNotNull(
                dsl
                    .select(BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                    .from(BUSINESS_DATE)
                    .where(BUSINESS_DATE.ORGANISATION_ID.eq(organisationId))
                    .fetchOne(BUSINESS_DATE.CURRENT_BUSINESS_DATE),
            ) { "provisioning must have created the business date" }
        val branchId =
            requireNotNull(
                dsl
                    .select(BRANCH.ID)
                    .from(BRANCH)
                    .where(BRANCH.ORGANISATION_ID.eq(organisationId))
                    .and(BRANCH.BRANCH_CODE.eq("HEAD_OFFICE"))
                    .fetchOne(BRANCH.ID),
            ) { "provisioning must have created the head office" }
        val periodId = openPeriodCovering(organisationId, businessDate)
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            businessDate = businessDate,
            periodId = periodId,
            debitAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
            creditAccountId = schema.insertAccount(organisationId, "2010", "LIABILITY"),
        )
    }

    /** One OPEN period covering [date], inside a fiscal year covering its calendar year. */
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

    /** The caller's claimed context: the tenant, its head office and the fixture's actor. */
    fun context(tenant: Tenant) =
        AccountingContext(tenant.organisationId, tenant.branchId, actorId, correlationId = null)

    /**
     * A source whose entity id is a function of the reference: a real retry carries the same
     * business entity, so the fingerprint - which covers the whole source triple - must too.
     */
    fun source(reference: String) =
        AccountingSourceReference(
            "savings",
            "SAVINGS_DEPOSIT",
            UUID.nameUUIDFromBytes(reference.toByteArray()),
            reference,
        )

    /** A balanced two-line set, with the credit carrying a sub-ledger reference. */
    fun legs(
        tenant: Tenant,
        debit: String = "500.00",
        credit: String = debit,
        currency: String = "KES",
        debitAccount: UUID = tenant.debitAccountId,
    ) = listOf(
        PostingLeg(debitAccount, PostingSide.DEBIT, MonetaryAmount(BigDecimal(debit), currency)),
        PostingLeg(
            tenant.creditAccountId,
            PostingSide.CREDIT,
            MonetaryAmount(BigDecimal(credit), currency),
            subledgerReference = "SAV-0001",
        ),
    )

    /**
     * Posts explicit legs through the engine, as accounting's own callers do.
     *
     * [engine] defaults to the production bean this fixture was built with, and is a parameter only
     * so that a suite proving what the engine does when a *port* misbehaves can hand in the same
     * engine with that one port swapped.
     */
    @Suppress("LongParameterList")
    fun post(
        tenant: Tenant,
        reference: String = "dep-${uuidV7()}",
        debit: String = "500.00",
        credit: String = debit,
        currency: String = "KES",
        debitAccount: UUID = tenant.debitAccountId,
        dates: PostingDateRequest = PostingDateRequest(),
        engine: PostingEngine = this.engine,
    ): PostingReceipt =
        engine.post(
            LedgerPostingRequest(
                context = context(tenant),
                source = source(reference),
                eventCode = "SAVINGS_DEPOSIT",
                entryType = JournalEntryType.STANDARD,
                narrative = "Counter deposit",
                dates = dates,
                // A real product-module caller carries this from PostingIntent.Facts; supplied by
                // hand here because this helper calls the engine directly, so a "conflicting reuse"
                // test that varies only the amount stays distinguishable (ADR 0023).
                financialFacts =
                    listOf(
                        FinancialFact("AMOUNT", MonetaryAmount(BigDecimal(debit), currency)),
                    ),
            ),
        ) { ResolvedLegs(legs(tenant, debit, credit, currency, debitAccount), null) }

    /**
     * Moves the tenant's business date to the last day of its month, so that backdating by a few
     * days stays inside the fiscal period [provisionTenant] opened.
     *
     * Without it a backdated scenario is date-dependent: provisioning sets the business date to the
     * tenant's today, and on the first of a month `minusDays(1)` falls into the previous month,
     * where no period exists and the posting is refused with `fiscal_period_not_found` for reasons
     * that have nothing to do with what the test is about. Written straight to the row because
     * `BusinessDateService.advance` refuses a date that is not after the current one.
     */
    fun moveBusinessDateToMonthEnd(tenant: Tenant): LocalDate {
        val monthEnd = tenant.businessDate.withDayOfMonth(tenant.businessDate.lengthOfMonth())
        dsl
            .update(BUSINESS_DATE)
            .set(BUSINESS_DATE.CURRENT_BUSINESS_DATE, monthEnd)
            .set(BUSINESS_DATE.ROW_VERSION, BUSINESS_DATE.ROW_VERSION.plus(1))
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(tenant.organisationId))
            .execute()
        return monthEnd
    }

    /** Installs the ambient request context the engine reconciles the caller's claim against. */
    fun <T> inContext(
        tenant: Tenant,
        requestId: String? = null,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(actorId, null, null, null),
                correlation =
                    requestId?.let {
                        CorrelationContext(
                            requestId = it,
                            correlationId = it,
                        )
                    },
            ),
        ) {
            withRequestContext(block)
        }
}
