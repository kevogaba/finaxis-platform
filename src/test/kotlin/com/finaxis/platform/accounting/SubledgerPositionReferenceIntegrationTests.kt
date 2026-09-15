package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleVersionCommand
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
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
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

/**
 * The sub-ledger position reference, end to end through the public posting path (#91).
 *
 * `V9` built `idx_journal_line_subledger` for the `Q6` reconciliation drill-down - *"which general
 * ledger lines moved this member's savings account"* - and nothing could populate it: neither
 * `FinancialFact` nor `PostingRulePolicy.allocate` carried a position, so every real product
 * posting left `journal_line.subledger_reference` null and a control-account break could be seen
 * but never traced. These tests prove the whole path: a reference asserted on a fact reaches every
 * general-ledger line the rule derives from that fact, the drill-down query then answers, and a
 * reference the column could not store is refused before the ledger is touched.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class SubledgerPositionReferenceIntegrationTests(
    private val rules: PostingRuleService,
    private val postingService: PostingService,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `a fact's position reference reaches every line the rule derives from it`() {
        val tenant = provisionTenant("position-reference")

        val receipt = post(tenant, "dep-position-1", positionReference = "SAV-0007")

        // All three legs - the debit, the fee share and the residual - come from the one PRINCIPAL
        // fact, so all three carry its position. Tracing a control-account break to the positions
        // that caused it is exactly what this makes possible.
        assertEquals(
            listOf("SAV-0007", "SAV-0007", "SAV-0007"),
            dsl
                .select(JOURNAL_LINE.SUBLEDGER_REFERENCE)
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(receipt))
                .fetch(JOURNAL_LINE.SUBLEDGER_REFERENCE),
        )
    }

    @Test
    fun `the Q6 drill-down finds one position's lines and not another's`() {
        val tenant = provisionTenant("position-drilldown")
        post(tenant, "dep-a", positionReference = "SAV-0001")
        post(tenant, "dep-b", positionReference = "SAV-0002")

        assertEquals(3, linesForPosition(tenant, "SAV-0001"))
        assertEquals(3, linesForPosition(tenant, "SAV-0002"))
        assertEquals(0, linesForPosition(tenant, "SAV-0003"))
    }

    @Test
    fun `a reference the journal line could not store is refused before the ledger is touched`() {
        val tenant = provisionTenant("position-invalid")
        val before = journalCount(tenant)

        val blank =
            assertFailsWith<InvalidOperationException> {
                post(tenant, "dep-blank", positionReference = " ")
            }
        assertEquals(PostingRulePolicy.SUBLEDGER_REFERENCE_INVALID, blank.code)

        val overLong = "S".repeat(PostingRulePolicy.SUBLEDGER_REFERENCE_MAX_LENGTH + 1)
        val tooLong =
            assertFailsWith<InvalidOperationException> {
                post(tenant, "dep-long", positionReference = overLong)
            }
        assertEquals(PostingRulePolicy.SUBLEDGER_REFERENCE_INVALID, tooLong.code)

        assertEquals(before, journalCount(tenant), "a refused reference wrote no journal")
    }

    @Test
    fun `a posting that moves no single position leaves the partial index alone`() {
        val tenant = provisionTenant("position-absent")

        val receipt = post(tenant, "dep-none", positionReference = null)

        assertEquals(
            listOf(null, null, null),
            dsl
                .select(JOURNAL_LINE.SUBLEDGER_REFERENCE)
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .and(JOURNAL_LINE.JOURNAL_ENTRY_ID.eq(receipt))
                .fetch(JOURNAL_LINE.SUBLEDGER_REFERENCE),
        )
    }

    // ---- helpers ----------------------------------------------------------------------------

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

    /** Posts one deposit through the public facts path and returns the journal it produced. */
    private fun post(
        tenant: Tenant,
        reference: String,
        positionReference: String?,
    ): UUID =
        inContext(tenant) {
            transactions
                .execute {
                    postingService.post(
                        PostFinancialFactsCommand(
                            context =
                                AccountingContext(
                                    tenant.organisationId,
                                    tenant.branchId,
                                    MAKER,
                                ),
                            source =
                                AccountingSourceReference(
                                    "savings",
                                    "SAVINGS_DEPOSIT",
                                    uuidV7(),
                                    reference,
                                ),
                            intent =
                                PostingIntent.Facts(
                                    "SAVINGS_DEPOSIT",
                                    listOf(
                                        FinancialFact(
                                            "PRINCIPAL",
                                            MonetaryAmount(BigDecimal("1000.00"), "KES"),
                                            positionReference,
                                        ),
                                    ),
                                ),
                        ),
                    )
                }.journalEntryId
        }

    private fun linesForPosition(
        tenant: Tenant,
        position: String,
    ) = dsl.fetchCount(
        JOURNAL_LINE,
        JOURNAL_LINE.ORGANISATION_ID
            .eq(tenant.organisationId)
            .and(JOURNAL_LINE.SOURCE_MODULE.eq("savings"))
            .and(JOURNAL_LINE.SUBLEDGER_REFERENCE.eq(position)),
    )

    private fun journalCount(tenant: Tenant) =
        dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

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
        openPeriodCovering(organisationId, businessDate)
        val tenant =
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
        return activateRule(tenant)
    }

    /** One three-leg rule, approved and in force from the business date, so a posting resolves. */
    private fun activateRule(tenant: Tenant): Tenant {
        val ruleId = createRule(tenant)
        val versionId = createVersion(tenant, ruleId)
        withRequestContext { rules.submit(transition(tenant, versionId, MAKER)) }
        withRequestContext { rules.approve(transition(tenant, versionId, tenant.checker)) }
        return tenant.copy(ruleId = ruleId)
    }

    private fun createRule(tenant: Tenant): UUID =
        withRequestContext {
            rules
                .createRule(
                    CreatePostingRuleCommand(
                        organisationId = tenant.organisationId,
                        actorId = MAKER,
                        code = "SAVINGS-DEPOSIT",
                        name = "Savings deposit",
                        selector = PostingRuleSelector("SAVINGS_DEPOSIT", null, null),
                    ),
                ).id
        }

    /** Debit cash in full; credit a 2.5% fee and the residual, so one fact yields three legs. */
    private fun createVersion(
        tenant: Tenant,
        ruleId: UUID,
    ): UUID =
        withRequestContext {
            rules
                .createVersion(
                    CreatePostingRuleVersionCommand(
                        organisationId = tenant.organisationId,
                        actorId = MAKER,
                        ruleId = ruleId,
                        effectiveFrom = tenant.businessDate,
                        legs =
                            listOf(
                                leg(1, PostingSide.DEBIT, tenant.cashAccountId),
                                leg(2, PostingSide.CREDIT, tenant.feeAccountId, "2.5"),
                                leg(
                                    3,
                                    PostingSide.CREDIT,
                                    tenant.liabilityAccountId,
                                    residual = true,
                                ),
                            ),
                    ),
                ).id
        }

    private fun transition(
        tenant: Tenant,
        versionId: UUID,
        actor: UUID,
    ) = PostingRuleVersionTransitionCommand(tenant.organisationId, actor, versionId, null)

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

    private fun openPeriodCovering(
        organisationId: UUID,
        date: LocalDate,
    ) {
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
        dsl
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
            .execute()
    }

    private fun <T> inContext(
        tenant: Tenant,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(MAKER, null, null, null),
            ),
        ) { withRequestContext(block) }

    private fun newChecker(label: String): UUID {
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, "position.checker.$label")
            .set(USER_ACCOUNT.EMAIL, "position.checker.$label@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "Position Checker")
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
    }
}
