package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.PostingRuleFixture.Companion.MAKER
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.rules.PostingRuleDryRunMode
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * What `PostingRuleService.dryRun` now answers, and that it answers it the way a posting would
 * (issue #95).
 *
 * The defect these suites exist for: the dry run resolved legs and stopped there, so the two cases
 * the issue names - facts denominated in a currency the tenant does not post in, and a rule naming
 * an account deactivated since its version was approved - **dry-ran clean and posted red**. Both
 * tests below therefore assert the same thing twice: the code the dry run raises, and the code a
 * real posting of the same intent raises. Before the change the first assertion failed, because
 * the dry run returned legs; after it, the pair is what stops the two paths diverging again.
 *
 * What the dry run still does not judge is deliberate and stated in `dryRun`'s own documentation:
 * tenant postability, fiscal-period status and posting-date admissibility belong to the engine,
 * because each of them would refuse a preview an administrator is entitled to take.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRuleDryRunIntegrationTests(
    rules: PostingRuleService,
    private val postingService: PostingService,
    dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val transactions = TransactionTemplate(transactionManager)
    private val fx =
        PostingRuleFixture(
            dsl,
            rules,
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl),
            JournalSchemaFixture(dsl),
        )

    @Test
    fun `facts in a currency the tenant does not post in are refused, not resolved`() {
        val tenant = fx.provisionTenant("dry-run-currency")
        fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))

        // The selector's currency dimension is the tenant's functional currency, so the rule still
        // resolves; what the facts are denominated in is only discovered when the legs are judged.
        val refused =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    fx.dryRun(tenant, "100.00", tenant.businessDate, currency = "USD")
                }
            }
        assertEquals(
            PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
            refused.code,
            "a dry run in a foreign currency must refuse exactly as the posting would",
        )

        val posted =
            assertFailsWith<InvalidOperationException> { post(tenant, "100.00", currency = "USD") }
        assertEquals(
            refused.code,
            posted.code,
            "the dry run and the posting share one validator, so they share one code",
        )
        assertEquals(
            refused.safeDetail,
            posted.safeDetail,
            "and one message: a caller that reads the dry run reads what the posting would say",
        )
        assertEquals(0, fx.journalCount(tenant), "neither attempt left a journal behind")
    }

    @Test
    fun `an account deactivated after the version was approved is refused, not resolved`() {
        val tenant = fx.provisionTenant("dry-run-deactivated")
        fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))

        // Approval validated the accounts under a lock; nothing stops an administrator withdrawing
        // one afterwards, and the approved version keeps naming it.
        fx.deactivateAccount(tenant, tenant.cashAccountId)

        val refused =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { fx.dryRun(tenant, "100.00", tenant.businessDate) }
            }
        assertEquals(
            PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
            refused.code,
            "a rule naming a withdrawn account is a broken configuration the dry run must report",
        )

        val posted = assertFailsWith<InvalidOperationException> { post(tenant, "100.00") }
        assertEquals(
            refused.code,
            posted.code,
            "the posting refuses the same account under the same code",
        )
        assertEquals(
            refused.safeDetail,
            posted.safeDetail,
            "and names the same account, because the unlocked read and the locked one agree",
        )
        assertEquals(0, fx.journalCount(tenant), "neither attempt left a journal behind")
    }

    @Test
    fun `the lenient mode returns the first problem as data instead of throwing`() {
        val tenant = fx.provisionTenant("dry-run-lenient")
        fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))
        fx.deactivateAccount(tenant, tenant.cashAccountId)

        val reported =
            withRequestContext {
                fx.dryRun(
                    tenant,
                    "100.00",
                    tenant.businessDate,
                    mode = PostingRuleDryRunMode.REPORT_PROBLEM,
                )
            }

        val problem = assertNotNull(reported.problem, "the lenient mode reports rather than throws")
        assertEquals(
            PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
            problem.code,
            "the code is the one the strict mode would have thrown",
        )
        val thrown =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { fx.dryRun(tenant, "100.00", tenant.businessDate) }
            }
        assertEquals(
            thrown.safeDetail,
            problem.detail,
            "same validator, same detail; only the delivery differs",
        )
        assertEquals(
            2,
            reported.legs.size,
            "the legs under judgement are still returned, so the caller can see what failed",
        )
    }

    @Test
    fun `the lenient mode reports only the first problem, and says so by finding the currency`() {
        val tenant = fx.provisionTenant("dry-run-lenient-first")
        fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))
        fx.deactivateAccount(tenant, tenant.cashAccountId)

        // Two defects at once: a foreign currency on every leg and a withdrawn account on the
        // first. One validator, stopping at the first failure, so the currency is what comes back.
        val reported =
            withRequestContext {
                fx.dryRun(
                    tenant,
                    "100.00",
                    tenant.businessDate,
                    currency = "USD",
                    mode = PostingRuleDryRunMode.REPORT_PROBLEM,
                )
            }

        assertEquals(
            PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
            assertNotNull(reported.problem).code,
            "the lenient mode is one pass that stops early, not a diagnostic that collects all",
        )
    }

    @Test
    fun `a clean dry run still returns the resolver's legs, at the resolver's scale`() {
        val tenant = fx.provisionTenant("dry-run-clean")
        val version =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate, feeShare = "2.5"),
            )

        val dryRun =
            withRequestContext { fx.dryRun(tenant, "1000.00", tenant.businessDate) }

        assertEquals(version.id, dryRun.postingRuleVersionId, "the version is unchanged")
        assertEquals(
            listOf("1000.00", "25.00", "975.00"),
            dryRun.legs.map { it.amount.amount.toPlainString() },
            "validation settles copies to storage scale; what comes back is what resolved",
        )
        assertNull(dryRun.problem, "a configuration that would post reports no problem")
        assertEquals(0, fx.journalCount(tenant), "a dry run persists nothing")
    }

    /** Posts the same intent the dry run was asked about, so the two answers can be compared. */
    private fun post(
        tenant: PostingRuleFixture.Tenant,
        principal: String,
        currency: String = PostingRuleFixture.FUNCTIONAL_CURRENCY,
    ) = fx.inContext(tenant, MAKER) {
        transactions.execute {
            postingService.post(
                PostFinancialFactsCommand(
                    context = fx.context(tenant, MAKER),
                    source =
                        AccountingSourceReference(
                            "savings",
                            "SAVINGS_DEPOSIT",
                            uuidV7(),
                            "dry-run-compare-${uuidV7()}",
                        ),
                    intent = fx.intent(principal, currency = currency),
                ),
            )
        }
    }
}
