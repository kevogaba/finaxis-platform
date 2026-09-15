package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.rules.PostingRuleDryRunMode
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What `PostingRuleService.previewVersion` answers, and how it differs from `dryRun` (issue #95).
 *
 * The defect: a draft version could not be dry-run at all. `dryRun` reaches its version through
 * `PostingRuleVersion.governs`, which requires an approved status, so the configuration awaiting
 * approval - the one a checker most wants to test - was unreachable, and a checker approved on the
 * legs and percentages alone. `previewVersion` allocates the legs of a version the caller *names*,
 * which is the only way to see them before approval; the first test below states both halves, the
 * preview answering and the dry run refusing, so the two operations cannot quietly converge.
 *
 * The rest cover what bypassing selection costs and what this operation does about it: a rule
 * pinned to a currency the tenant does not post in allocates perfectly and can never fire, which
 * only `selectorMatchesFunctionalCurrency` now says; a version id from another tenant is
 * indistinguishable from one that does not exist; and the legs are still judged by the eligibility
 * pass and the well-formedness check approval itself runs.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRuleVersionPreviewIntegrationTests(
    rules: PostingRuleService,
    dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fx =
        PostingRuleFixture(
            dsl,
            rules,
            TenantAdminOrganisationFixture(organisationProvisioningService, dsl),
            JournalSchemaFixture(dsl),
        )

    @Test
    fun `a draft version previews its legs, which no dry run can reach`() {
        val tenant = fx.provisionTenant("preview-draft")
        val version = fx.draftVersion(tenant, from = tenant.businessDate)

        val preview = withRequestContext { fx.preview(tenant, version.id, "1000.00") }

        assertEquals(
            PostingRuleVersionStatus.DRAFT,
            preview.version.status,
            "the status comes back with the legs, so a draft can never be read as what is in force",
        )
        assertEquals(
            listOf("1000.00", "1000.00"),
            preview.legs.map { it.amount.amount.toPlainString() },
            "a draft's legs are exactly what a checker needs to see before approving them",
        )
        assertNull(preview.problem, "a draft that would post cleanly reports no problem")

        val refused =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { fx.dryRun(tenant, "1000.00", tenant.businessDate) }
            }
        assertEquals(
            PostingErrorCodes.POSTING_RULE_NOT_FOUND,
            refused.code,
            "the dry run asks what is in force, and a draft is not; that is the defect's shape",
        )
    }

    @Test
    fun `a version awaiting approval previews, which is the checker's actual moment`() {
        val tenant = fx.provisionTenant("preview-pending")
        val version = fx.draftVersion(tenant, from = tenant.businessDate, feeShare = "2.5")
        val submitted = fx.submit(tenant, version)

        val preview = withRequestContext { fx.preview(tenant, submitted.id, "1000.00") }

        assertEquals(
            PostingRuleVersionStatus.PENDING_APPROVAL,
            preview.version.status,
            "the version awaiting a checker is the one the checker must be able to test",
        )
        assertEquals(
            listOf("1000.00", "25.00", "975.00"),
            preview.legs.map { it.amount.amount.toPlainString() },
            "the split and its residual are what a checker approves, so they are what is shown",
        )
    }

    @Test
    fun `a version id from another tenant is refused exactly as one that does not exist`() {
        val other = fx.provisionTenant("preview-other-tenant")
        val otherVersion = fx.draftVersion(other, from = other.businessDate)
        val tenant = fx.provisionTenant("preview-isolation")

        // The positive control: the id is real and previewable by the tenant that owns it, so the
        // refusals below are about the tenant boundary and not about a version that never existed.
        assertEquals(
            otherVersion.id,
            withRequestContext { fx.preview(other, otherVersion.id, "100.00") }.version.id,
            "the owning tenant previews its own version",
        )

        val crossTenant =
            assertFailsWith<ResourceNotFoundException> {
                withRequestContext { fx.preview(tenant, otherVersion.id, "100.00") }
            }
        val absent =
            assertFailsWith<ResourceNotFoundException> {
                withRequestContext { fx.preview(tenant, uuidV7(), "100.00") }
            }

        assertEquals(
            PostingErrorCodes.POSTING_RULE_VERSION_NOT_FOUND,
            crossTenant.code,
            "a version belonging to another tenant is not found, never previewed",
        )
        assertEquals(
            absent.code,
            crossTenant.code,
            "and is refused under the same code as an id that was never issued",
        )
        assertEquals(
            absent.safeDetail,
            crossTenant.safeDetail,
            "and the same message, so a caller cannot probe another tenant's identifiers",
        )
    }

    @Test
    fun `a rule pinned to a foreign currency previews its legs and says it can never fire`() {
        val tenant = fx.provisionTenant("preview-currency")
        val pinned = fx.createRule(tenant, "SAVINGS-DEPOSIT-USD", currencyCode = "USD")
        val version = fx.draftVersion(tenant, from = tenant.businessDate, rule = pinned)

        val preview = withRequestContext { fx.preview(tenant, version.id, "1000.00") }

        assertEquals(
            PostingRuleFixture.FUNCTIONAL_CURRENCY,
            preview.functionalCurrency,
            "the tenant posts in KES, which is the currency selection would have matched against",
        )
        assertEquals(
            "USD",
            preview.rule.selector.currencyCode,
            "the rule pins USD, and preview says so",
        )
        assertFalse(
            preview.selectorMatchesFunctionalCurrency,
            "a rule pinned to a currency the tenant does not post in can never be selected",
        )
        assertEquals(
            2,
            preview.legs.size,
            "the version is valid and allocates perfectly; it is merely unreachable",
        )
        assertNull(preview.problem, "the facts are in the functional currency, so the legs pass")

        val reachable =
            withRequestContext {
                fx.preview(
                    tenant,
                    fx.draftVersion(tenant, from = tenant.businessDate).id,
                    "1000.00",
                )
            }
        assertTrue(
            reachable.selectorMatchesFunctionalCurrency,
            "the unpinned rule of the same tenant matches every currency, including this one",
        )
    }

    @Test
    fun `a draft naming a deactivated account is refused, and reported in the lenient mode`() {
        val tenant = fx.provisionTenant("preview-deactivated")
        val version = fx.draftVersion(tenant, from = tenant.businessDate)
        fx.deactivateAccount(tenant, tenant.cashAccountId)

        val refused =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    fx.preview(
                        tenant,
                        version.id,
                        "1000.00",
                        mode = PostingRuleDryRunMode.STRICT,
                    )
                }
            }
        assertEquals(
            PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
            refused.code,
            "the shared eligibility pass runs for a preview exactly as it does for a dry run",
        )

        // No mode: a preview reports by default, where a dry run throws by default. The checker
        // this operation exists for is refusing an approval, not replaying a posting, and legs
        // they cannot see are legs they cannot judge.
        val reported = withRequestContext { fx.preview(tenant, version.id, "1000.00") }
        val problem = assertNotNull(reported.problem, "a preview reports rather than throws")
        assertEquals(refused.code, problem.code, "the code is the one the strict mode threw")
        assertEquals(refused.safeDetail, problem.detail, "and the detail; only delivery differs")
        assertEquals(
            2,
            reported.legs.size,
            "the legs under judgement come back, so a checker sees what was refused",
        )
    }

    @Test
    fun `an active version previews the legs its dry run resolves`() {
        val tenant = fx.provisionTenant("preview-active")
        val version =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate, feeShare = "2.5"),
            )

        val dryRun = withRequestContext { fx.dryRun(tenant, "1000.00", tenant.businessDate) }
        val preview = withRequestContext { fx.preview(tenant, version.id, "1000.00") }

        assertEquals(
            PostingRuleVersionStatus.ACTIVE,
            preview.version.status,
            "the version in force previews too; naming it does not require it to be a draft",
        )
        assertEquals(
            dryRun.postingRuleVersionId,
            preview.version.id,
            "selection found the version the caller named, so the two answers are comparable",
        )
        assertEquals(
            dryRun.legs.map { it.accountId to it.side },
            preview.legs.map { it.accountId to it.side },
            "same accounts, same sides, same order",
        )
        // compareTo, not equals: both paths run the same allocation, but a scale difference would
        // fail an equals on BigDecimal while meaning nothing about the amounts.
        dryRun.legs.zip(preview.legs).forEachIndexed { index, (resolved, previewed) ->
            assertEquals(
                0,
                resolved.amount.amount.compareTo(previewed.amount.amount),
                "leg ${index + 1} must carry the amount the dry run resolved",
            )
        }
    }

    @Test
    fun `a malformed draft is refused before its legs are allocated, in either mode`() {
        val tenant = fx.provisionTenant("preview-malformed")
        val version =
            fx.draftVersion(
                tenant,
                from = tenant.businessDate,
                versionLegs = fx.oneSidedLegs(tenant),
            )

        val refused =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    fx.preview(
                        tenant,
                        version.id,
                        "1000.00",
                        mode = PostingRuleDryRunMode.STRICT,
                    )
                }
            }
        assertEquals(
            PostingRulePolicy.LEGS_ONE_SIDED,
            refused.code,
            "a preview runs the well-formedness check approval runs, not the balance check alone",
        )

        val lenient =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    fx.preview(
                        tenant,
                        version.id,
                        "1000.00",
                        mode = PostingRuleDryRunMode.REPORT_PROBLEM,
                    )
                }
            }
        assertEquals(
            refused.code,
            lenient.code,
            "the mode covers the eligibility pass only: a version with no allocatable legs has " +
                "nothing for a preview to carry a problem about",
        )
    }

    @Test
    fun `a retired version still previews, carrying the window it used to govern`() {
        val tenant = fx.provisionTenant("preview-retired")
        val version = fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))
        val retired =
            withRequestContext {
                fx.retire(tenant, version.id, fx.approver(tenant, "preview-retired-approver"))
            }

        val preview = withRequestContext { fx.preview(tenant, retired.id, "1000.00") }

        assertEquals(
            PostingRuleVersionStatus.RETIRED,
            preview.version.status,
            "'what would the version that governed last March have posted' is answerable",
        )
        assertEquals(
            tenant.businessDate,
            preview.version.effectiveTo,
            "and the closed window comes back with it, so the answer cannot be read as current",
        )
        assertEquals(2, preview.legs.size, "a closed version's legs are still allocatable")
    }
}
