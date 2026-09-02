package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.rules.AmendPostingRuleVersionCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleVersionCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleDryRunCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.application.rules.PostingRuleVersionTransitionCommand
import com.finaxis.platform.accounting.domain.AccountResolution
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
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
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION_TRANSITION_LOG
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
import org.springframework.transaction.support.TransactionTemplate
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
 * Posting-rule lifecycle, maker-checker and deterministic resolution against PostgreSQL (#45),
 * ending with the first product-style posting the platform can make: a `PostingIntent.Facts`
 * resolved by an approved rule into a journal that records the exact version it used.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRuleLifecycleIntegrationTests(
    private val rules: PostingRuleService,
    private val postingService: PostingService,
    private val journals: JournalReadStore,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `a submitted version is approved by another actor, activated, and leaves history`() {
        val tenant = provisionTenant("rule-lifecycle")
        val version = draftVersion(tenant, from = tenant.businessDate.minusMonths(1))

        withRequestContext { rules.submit(transition(tenant, version.id, MAKER)) }
        val selfApproval =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { rules.approve(transition(tenant, version.id, MAKER)) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_SELF_APPROVAL, selfApproval.code)

        val approved =
            withRequestContext { rules.approve(transition(tenant, version.id, tenant.checker)) }

        assertEquals(PostingRuleVersionStatus.ACTIVE, approved.status)
        assertEquals(listOf("SUBMIT", "APPROVE"), transitionNames(tenant, version.id))
        assertEquals(
            listOf("posting_rule.approve", "posting_rule.create", "posting_rule.create_version"),
            auditActions(tenant),
        )
    }

    @Test
    fun `an approved version is immutable and a successor supersedes it without overlap`() {
        val tenant = provisionTenant("rule-supersede")
        val first = activate(tenant, draftVersion(tenant, from = tenant.businessDate.minusYears(1)))

        val frozen =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    rules.amendDraft(
                        AmendPostingRuleVersionCommand(
                            tenant.organisationId,
                            MAKER,
                            first.id,
                            tenant.businessDate,
                            legs(tenant),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_VERSION_NOT_EDITABLE, frozen.code)

        val second =
            activate(
                tenant,
                draftVersion(tenant, from = tenant.businessDate.minusMonths(1), feeShare = "10"),
            )

        val history =
            withRequestContext { rules.getVersion(tenant.organisationId, first.id, MAKER) }
        assertEquals(PostingRuleVersionStatus.SUPERSEDED, history.status)
        assertEquals(tenant.businessDate.minusMonths(1).minusDays(1), history.effectiveTo)
        assertEquals(PostingRuleVersionStatus.ACTIVE, second.status)
        assertNull(second.effectiveTo)

        // A successor that would start before the current head began is refused: closing the head
        // would leave the dates it governed with no version.
        val tooEarly =
            assertFailsWith<ConflictException> {
                activate(tenant, draftVersion(tenant, from = tenant.businessDate.minusYears(2)))
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_WINDOW_INVALID, tooEarly.code)
    }

    @Test
    fun `rejection returns a version to draft with a reason, and one-sided legs cannot submit`() {
        val tenant = provisionTenant("rule-reject")
        val version = draftVersion(tenant, from = tenant.businessDate)
        withRequestContext { rules.submit(transition(tenant, version.id, MAKER)) }

        assertFailsWith<InvalidOperationException> {
            withRequestContext { rules.reject(transition(tenant, version.id, tenant.checker)) }
        }
        val rejected =
            withRequestContext {
                rules.reject(
                    transition(tenant, version.id, tenant.checker, reason = "Wrong fee account"),
                )
            }
        assertEquals(PostingRuleVersionStatus.DRAFT, rejected.status)
        assertEquals("Wrong fee account", rejected.statusReason)

        withRequestContext {
            rules.amendDraft(
                AmendPostingRuleVersionCommand(
                    tenant.organisationId,
                    MAKER,
                    version.id,
                    tenant.businessDate,
                    legs(tenant).filter { it.side == PostingSide.DEBIT },
                ),
            )
        }
        val oneSided =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { rules.submit(transition(tenant, version.id, MAKER)) }
            }
        assertEquals("accounting.posting_rule_legs_one_sided", oneSided.code)
    }

    @Test
    fun `the same intent always resolves the same version, and the posting records it`() {
        val tenant = provisionTenant("rule-resolve")
        val version =
            activate(
                tenant,
                draftVersion(tenant, from = tenant.businessDate.minusMonths(1), feeShare = "2.5"),
            )

        val dryRun =
            withRequestContext {
                dryRun(tenant, "1000.00", tenant.businessDate)
            }
        assertEquals(version.id, dryRun.postingRuleVersionId)
        assertEquals(
            listOf("1000.00", "25.00", "975.00"),
            dryRun.legs.map { it.amount.amount.toPlainString() },
        )
        assertEquals(0, journalCount(tenant), "a dry run persists nothing")

        val receipt =
            inContext(tenant, MAKER) {
                transactions.execute {
                    postingService.post(
                        PostFinancialFactsCommand(
                            context = context(tenant, MAKER),
                            source =
                                AccountingSourceReference(
                                    "savings",
                                    "SAVINGS_DEPOSIT",
                                    uuidV7(),
                                    "dep-rule-1",
                                ),
                            intent = intent("1000.00"),
                        ),
                    )
                }!!
            }
        val again =
            withRequestContext {
                dryRun(tenant, "1000.00", tenant.businessDate)
            }

        assertEquals(dryRun, again, "same inputs, same version, same legs")
        assertEquals(
            version.id,
            dsl
                .select(POSTING_REQUEST.POSTING_RULE_VERSION_ID)
                .from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ID.eq(receipt.postingRequestId))
                .fetchOne(POSTING_REQUEST.POSTING_RULE_VERSION_ID),
            "the exact rule version is durable on the request",
        )
        val lines = journals.findJournalLines(tenant.organisationId, receipt.journalEntryId)
        assertEquals(3, lines.size)
        assertEquals(
            BigDecimal("1000.000000"),
            lines.filter { it.side == PostingSide.CREDIT }.sumOf { it.functionalAmount },
        )
    }

    @Test
    fun `a posting date selects the version in force on that date, not today's`() {
        val tenant = provisionTenant("rule-effective")
        val old = activate(tenant, draftVersion(tenant, from = tenant.businessDate.minusMonths(3)))
        val new =
            activate(tenant, draftVersion(tenant, from = tenant.businessDate, feeShare = "10"))

        val today = withRequestContext { dryRun(tenant, "100.00", tenant.businessDate) }
        val lastMonth =
            withRequestContext {
                dryRun(tenant, "100.00", tenant.businessDate.minusMonths(1))
            }

        assertEquals(new.id, today.postingRuleVersionId)
        assertEquals(old.id, lastMonth.postingRuleVersionId)
        assertEquals(3, today.legs.size)
        assertEquals(2, lastMonth.legs.size)
    }

    @Test
    fun `no-match and ambiguous configurations are explicit failures`() {
        val tenant = provisionTenant("rule-ambiguous")
        val none =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    dryRun(tenant, "1.00", tenant.businessDate)
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_FOUND, none.code)

        // One rule pinned to the product class, another to the currency: both match a KES posting
        // for that product at the same specificity.
        activate(
            tenant,
            draftVersion(
                tenant,
                from = tenant.businessDate,
                rule = createRule(tenant, "BY-PRODUCT", productClass = "SAVINGS:REGULAR"),
            ),
        )
        activate(
            tenant,
            draftVersion(
                tenant,
                from = tenant.businessDate,
                rule = createRule(tenant, "BY-CCY", currencyCode = "KES"),
            ),
        )

        val ambiguous =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    rules.dryRun(
                        PostingRuleDryRunCommand(
                            context(tenant, MAKER),
                            intent("1.00", productClass = "SAVINGS:REGULAR"),
                            tenant.businessDate,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_AMBIGUOUS, ambiguous.code)

        // Without the product class only the currency rule matches, deterministically.
        val resolved =
            withRequestContext {
                dryRun(tenant, "1.00", tenant.businessDate)
            }
        assertNotNull(resolved.postingRuleVersionId)
    }

    @Test
    fun `every operation is permission gated and a duplicate selector is a conflict`() {
        val tenant = provisionTenant("rule-permissions")

        assertFailsWith<ForbiddenOperationException> { createRule(tenant, "X", actor = STRANGER) }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                dryRun(tenant, "1.00", tenant.businessDate, actor = STRANGER)
            }
        }
        val duplicate = assertFailsWith<ApplicationException> { createRule(tenant, "AGAIN") }
        assertEquals(PostingErrorCodes.POSTING_RULE_DUPLICATE, duplicate.code)
    }

    @Test
    fun `retirement closes the window, needs a different actor and a reason, and is audited`() {
        val tenant = provisionTenant("rule-retire")
        val version = activate(tenant, draftVersion(tenant, from = tenant.businessDate))

        val noReason =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { retire(tenant, version.id, tenant.checker, reason = null) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_REASON_REQUIRED, noReason.code)

        // The actor who approved the version cannot also retire it.
        val selfRetire =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { retire(tenant, version.id, tenant.checker) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_SELF_APPROVAL, selfRetire.code)

        val secondChecker = newChecker("rule-retire-second-${UUID.randomUUID()}")
        tenants.grantTenantAdmin(tenant.organisationId, secondChecker)
        grantDirectly(
            tenant.organisationId,
            secondChecker,
            AccountingPermissions.POSTING_RULE_APPROVE,
        )
        val retired = withRequestContext { retire(tenant, version.id, secondChecker) }
        assertEquals(PostingRuleVersionStatus.RETIRED, retired.status)
        assertEquals(tenant.businessDate, retired.effectiveTo)
        assertTrue(
            "posting_rule.retire" in auditActions(tenant),
            "retirement can stop a product posting, so it is audited in its own right",
        )

        // The day after the cutoff no version governs the event any more.
        val unresolved =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { dryRun(tenant, "10.00", tenant.businessDate.plusDays(1)) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_FOUND, unresolved.code)
    }

    @Test
    fun `a fact supplied twice is refused rather than silently losing an amount`() {
        val tenant = provisionTenant("rule-duplicate-fact")
        activate(tenant, draftVersion(tenant, from = tenant.businessDate))

        val duplicated =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    rules.dryRun(
                        PostingRuleDryRunCommand(
                            context(tenant, MAKER),
                            PostingIntent.Facts(
                                "SAVINGS_DEPOSIT",
                                listOf(
                                    FinancialFact(
                                        "PRINCIPAL",
                                        MonetaryAmount(BigDecimal("100.00"), "KES"),
                                    ),
                                    FinancialFact(
                                        "PRINCIPAL",
                                        MonetaryAmount(BigDecimal("250.00"), "KES"),
                                    ),
                                ),
                            ),
                            tenant.businessDate,
                        ),
                    )
                }
            }
        assertEquals(PostingRulePolicy.FACT_DUPLICATED, duplicated.code)
    }

    // ---- helpers ------------------------------------------------------------------------------

    private data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val businessDate: LocalDate,
        val cashAccountId: UUID,
        val liabilityAccountId: UUID,
        val feeAccountId: UUID,
        val ruleId: UUID,
        val checker: UUID,
    )

    private fun provisionTenant(label: String): Tenant {
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

    private fun createRule(
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

    private fun legs(
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

    private fun draftVersion(
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

    private fun activate(
        tenant: Tenant,
        version: com.finaxis.platform.accounting.domain.PostingRuleVersion,
    ): com.finaxis.platform.accounting.domain.PostingRuleVersion {
        withRequestContext { rules.submit(transition(tenant, version.id, MAKER)) }
        return withRequestContext { rules.approve(transition(tenant, version.id, tenant.checker)) }
    }

    private fun transition(
        tenant: Tenant,
        versionId: UUID,
        actor: UUID,
        reason: String? = null,
    ) = PostingRuleVersionTransitionCommand(tenant.organisationId, actor, versionId, reason)

    private fun intent(
        principal: String,
        productClass: String? = null,
    ) = PostingIntent.Facts(
        "SAVINGS_DEPOSIT",
        listOf(FinancialFact("PRINCIPAL", MonetaryAmount(BigDecimal(principal), "KES"))),
        productClass,
    )

    private fun context(
        tenant: Tenant,
        actor: UUID,
    ) = AccountingContext(tenant.organisationId, tenant.branchId, actor)

    private fun retire(
        tenant: Tenant,
        versionId: UUID,
        actor: UUID,
        reason: String? = "Product withdrawn",
    ) = rules.retire(
        PostingRuleVersionTransitionCommand(
            organisationId = tenant.organisationId,
            actorId = actor,
            versionId = versionId,
            reason = reason,
            effectiveTo = tenant.businessDate,
        ),
    )

    private fun dryRun(
        tenant: Tenant,
        principal: String,
        postingDate: LocalDate,
        actor: UUID = MAKER,
    ) = rules.dryRun(
        PostingRuleDryRunCommand(context(tenant, actor), intent(principal), postingDate),
    )

    private fun <T> inContext(
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

    private fun journalCount(tenant: Tenant) =
        dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

    private fun transitionNames(
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

    private fun auditActions(tenant: Tenant) =
        dsl
            .select(AUDIT_EVENT.ACTION)
            .from(AUDIT_EVENT)
            .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant.organisationId))
            .and(AUDIT_EVENT.ACTION.like("posting_rule.%"))
            .fetch(AUDIT_EVENT.ACTION)
            .filterNotNull()
            .sorted()

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

    private companion object {
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
    }
}
