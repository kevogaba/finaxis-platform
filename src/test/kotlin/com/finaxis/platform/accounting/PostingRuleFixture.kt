package com.finaxis.platform.accounting

import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleVersionCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleDryRunCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.application.rules.PostingRuleVersionTransitionCommand
import com.finaxis.platform.accounting.domain.AccountResolution
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingRuleVersion
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
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
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION_TRANSITION_LOG
import com.finaxis.platform.jooq.tables.references.USER_ACCOUNT
import com.finaxis.platform.jooq.tables.references.USER_ORGANISATION_MEMBERSHIP
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * Everything the posting-rule suites need to stand a tenant up and drive a version through its
 * lifecycle: an active organisation with open periods, three accounts, a rule, a checker who may
 * approve, and the maker-checker moves themselves.
 *
 * Extracted from [PostingRuleLifecycleIntegrationTests] rather than duplicated into
 * [PostingRuleWindowIntegrationTests], so the two suites prove different things about one fixture
 * instead of drifting apart - the same reason `JournalReversalFixture` exists.
 *
 * Every call that reaches an application service does so inside [withRequestContext], because the
 * permission guard resolves a request-scoped bean and there is no controller layer driving these
 * services yet.
 */
internal class PostingRuleFixture(
    private val dsl: DSLContext,
    private val rules: PostingRuleService,
    private val tenants: TenantAdminOrganisationFixture,
    private val schema: JournalSchemaFixture,
) {
    /** An active organisation with a rule, three accounts, open periods and a checker. */
    data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val cashAccountId: UUID,
        val liabilityAccountId: UUID,
        val feeAccountId: UUID,
        val ruleId: UUID,
        val checker: UUID,
    )

    /** Provisions the tenant and its first rule, `SAVINGS-DEPOSIT`, with no versions yet. */
    fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, MAKER)
        val checker = newChecker("$label-${UUID.randomUUID()}")
        tenants.grantTenantAdmin(organisationId, checker)
        grantDirectly(organisationId, checker, AccountingPermissions.POSTING_RULE_APPROVE)
        grantDirectly(organisationId, MAKER, AccountingPermissions.POSTING_RULE_APPROVE)
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
        openPeriodsCovering(organisationId, businessDate)
        val partial =
            Tenant(
                organisationId = organisationId,
                branchId = branchId,
                businessDate = businessDate,
                cashAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
                liabilityAccountId = schema.insertAccount(organisationId, "2010", "LIABILITY"),
                feeAccountId = schema.insertAccount(organisationId, "4010", "INCOME"),
                ruleId = UUID(0, 0),
                checker = checker,
            )
        return partial.copy(ruleId = createRule(partial, "SAVINGS-DEPOSIT"))
    }

    /** Creates a rule and returns its identifier. */
    fun createRule(
        tenant: Tenant,
        code: String,
        productClass: String? = null,
        currencyCode: String? = null,
        actor: UUID = MAKER,
    ): UUID =
        withRequestContext {
            rules
                .createRule(
                    CreatePostingRuleCommand(
                        organisationId = tenant.organisationId,
                        actorId = actor,
                        code = code,
                        name = "Rule $code",
                        selector =
                            PostingRuleSelector("SAVINGS_DEPOSIT", productClass, currencyCode),
                    ),
                ).id
        }

    /** The legs of a version: two without [feeShare], three with a percentage split when given. */
    fun legs(
        tenant: Tenant,
        feeShare: String? = null,
    ): List<PostingRuleLeg> =
        buildList {
            add(leg(1, PostingSide.DEBIT, tenant.cashAccountId))
            if (feeShare != null) {
                add(leg(2, PostingSide.CREDIT, tenant.feeAccountId, percentage = feeShare))
                add(leg(3, PostingSide.CREDIT, tenant.liabilityAccountId, residual = true))
            } else {
                add(leg(2, PostingSide.CREDIT, tenant.liabilityAccountId))
            }
        }

    /** Creates the next `DRAFT` version of [rule], effective from [from]. */
    fun draftVersion(
        tenant: Tenant,
        from: LocalDate,
        feeShare: String? = null,
        rule: UUID = tenant.ruleId,
    ) = withRequestContext {
        rules.createVersion(
            CreatePostingRuleVersionCommand(
                organisationId = tenant.organisationId,
                actorId = MAKER,
                ruleId = rule,
                effectiveFrom = from,
                legs = legs(tenant, feeShare),
            ),
        )
    }

    /** Submits as the maker and approves as the tenant's checker, so the version activates. */
    fun activate(
        tenant: Tenant,
        version: PostingRuleVersion,
    ): PostingRuleVersion {
        withRequestContext { rules.submit(transition(tenant, version.id, MAKER)) }
        return withRequestContext { rules.approve(transition(tenant, version.id, tenant.checker)) }
    }

    /** Reads one version back through the service, permission-gated as any caller would. */
    fun version(
        tenant: Tenant,
        versionId: UUID,
    ) = withRequestContext { rules.getVersion(tenant.organisationId, versionId, MAKER) }

    fun transition(
        tenant: Tenant,
        versionId: UUID,
        actor: UUID,
        reason: String? = null,
    ) = PostingRuleVersionTransitionCommand(tenant.organisationId, actor, versionId, reason)

    /** Retires a version, closing its window on [effectiveTo]. Both arguments are mandatory. */
    fun retire(
        tenant: Tenant,
        versionId: UUID,
        actor: UUID,
        reason: String? = "Product withdrawn",
        effectiveTo: LocalDate? = tenant.businessDate,
    ) = rules.retire(
        PostingRuleVersionTransitionCommand(
            organisationId = tenant.organisationId,
            actorId = actor,
            versionId = versionId,
            reason = reason,
            effectiveTo = effectiveTo,
        ),
    )

    /**
     * A tenant member who may approve, and who has approved nothing yet.
     *
     * Retirement refuses the actor who approved the version exactly as approval refuses the actor
     * who submitted it, so closing an activated version always needs a third person.
     */
    fun approver(
        tenant: Tenant,
        label: String,
    ): UUID {
        val actor = newChecker("$label-${UUID.randomUUID()}")
        tenants.grantTenantAdmin(tenant.organisationId, actor)
        grantDirectly(tenant.organisationId, actor, AccountingPermissions.POSTING_RULE_APPROVE)
        return actor
    }

    fun intent(
        principal: String,
        productClass: String? = null,
        positionReference: String? = null,
    ) = PostingIntent.Facts(
        "SAVINGS_DEPOSIT",
        listOf(
            FinancialFact(
                "PRINCIPAL",
                MonetaryAmount(BigDecimal(principal), "KES"),
                positionReference,
            ),
        ),
        productClass,
    )

    fun context(
        tenant: Tenant,
        actor: UUID,
    ) = AccountingContext(tenant.organisationId, tenant.branchId, actor)

    fun dryRun(
        tenant: Tenant,
        principal: String,
        postingDate: LocalDate,
        actor: UUID = MAKER,
    ) = rules.dryRun(
        PostingRuleDryRunCommand(context(tenant, actor), intent(principal), postingDate),
    )

    /** Runs [block] with the tenant, branch and actor bound, as the engine needs. */
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

    fun transitionNames(
        tenant: Tenant,
        versionId: UUID,
    ) = dsl
        .select(POSTING_RULE_VERSION_TRANSITION_LOG.TRANSITION_NAME)
        .from(POSTING_RULE_VERSION_TRANSITION_LOG)
        .where(POSTING_RULE_VERSION_TRANSITION_LOG.ORGANISATION_ID.eq(tenant.organisationId))
        .and(POSTING_RULE_VERSION_TRANSITION_LOG.ENTITY_ID.eq(versionId))
        .orderBy(POSTING_RULE_VERSION_TRANSITION_LOG.ID)
        .fetch(POSTING_RULE_VERSION_TRANSITION_LOG.TRANSITION_NAME)
        .filterNotNull()

    fun auditActions(tenant: Tenant) =
        dsl
            .select(AUDIT_EVENT.ACTION)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant.organisationId))
            .and(AUDIT_EVENT.ACTION.like("posting_rule.%"))
            .fetch(AUDIT_EVENT.ACTION)
            .filterNotNull()
            .sorted()

    private fun leg(
        number: Int,
        side: PostingSide,
        account: UUID,
        percentage: String = "100",
        residual: Boolean = false,
    ) = PostingRuleLeg(
        number,
        side,
        AccountResolution.FIXED_ACCOUNT,
        account,
        "PRINCIPAL",
        BigDecimal(percentage),
        residual,
        null,
    )

    /** Two fiscal years of open monthly periods around the business date. */
    private fun openPeriodsCovering(
        organisationId: UUID,
        date: LocalDate,
    ) {
        val now = OffsetDateTime.now()
        listOf(date.year - 2, date.year - 1, date.year).forEach { year ->
            val yearId =
                dsl
                    .insertInto(ACCOUNTING_FISCAL_YEAR)
                    .set(ACCOUNTING_FISCAL_YEAR.ORGANISATION_ID, organisationId)
                    .set(ACCOUNTING_FISCAL_YEAR.YEAR_CODE, "FY$year")
                    .set(ACCOUNTING_FISCAL_YEAR.YEAR_NAME, "Financial year $year")
                    .set(ACCOUNTING_FISCAL_YEAR.START_DATE, LocalDate.of(year, 1, 1))
                    .set(ACCOUNTING_FISCAL_YEAR.END_DATE, LocalDate.of(year, 12, 31))
                    .set(ACCOUNTING_FISCAL_YEAR.CREATED_AT, now)
                    .set(ACCOUNTING_FISCAL_YEAR.UPDATED_AT, now)
                    .returning(ACCOUNTING_FISCAL_YEAR.ID)
                    .fetchOne()!!
                    .id!!
            (1..12).forEach { month ->
                val first = LocalDate.of(year, month, 1)
                dsl
                    .insertInto(ACCOUNTING_FISCAL_PERIOD)
                    .set(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID, organisationId)
                    .set(ACCOUNTING_FISCAL_PERIOD.FISCAL_YEAR_ID, yearId)
                    .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NUMBER, month)
                    .set(ACCOUNTING_FISCAL_PERIOD.PERIOD_NAME, "Period $month")
                    .set(ACCOUNTING_FISCAL_PERIOD.START_DATE, first)
                    .set(
                        ACCOUNTING_FISCAL_PERIOD.END_DATE,
                        first.withDayOfMonth(first.lengthOfMonth()),
                    ).set(ACCOUNTING_FISCAL_PERIOD.STATUS, "OPEN")
                    .set(ACCOUNTING_FISCAL_PERIOD.CREATED_AT, now)
                    .set(ACCOUNTING_FISCAL_PERIOD.UPDATED_AT, now)
                    .execute()
            }
        }
    }

    private fun newChecker(label: String): UUID {
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, "rule.checker.$label")
            .set(USER_ACCOUNT.EMAIL, "rule.checker.$label@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Rule Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    private fun grantDirectly(
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
        /** The `V3` bootstrap administrator, and the maker of every version here. */
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")

        /** A user with no membership in the tenant, for the permission-gate assertions. */
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
