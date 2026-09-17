package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.PostingRuleFixture.Companion.MAKER
import com.finaxis.platform.accounting.PostingRuleFixture.Companion.STRANGER
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.rules.AmendPostingRuleVersionCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleDryRunCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Posting-rule lifecycle, maker-checker and deterministic resolution against PostgreSQL (#45),
 * ending with the first product-style posting the platform can make: a `PostingIntent.Facts`
 * resolved by an approved rule into a journal that records the exact version it used.
 *
 * That last posting enters through [PostingTransactionBoundary], the way a product module does. A
 * bare `TransactionTemplate` opens at the server default, `READ COMMITTED`, which the engine now
 * refuses outright - so a suite that kept one would be asserting the refusal rather than the
 * resolution it exists to prove. Everything else here reads or configures and needs no transaction
 * of its own.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRuleLifecycleIntegrationTests(
    private val rules: PostingRuleService,
    private val postingService: PostingService,
    private val journals: JournalReadStore,
    private val dsl: DSLContext,
    private val postingTransactions: PostingTransactionBoundary,
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
    fun `a submitted version is approved by another actor, activated, and leaves history`() {
        val tenant = fx.provisionTenant("rule-lifecycle")
        val version = fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(1))

        withRequestContext { rules.submit(fx.transition(tenant, version.id, MAKER)) }
        val selfApproval =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { rules.approve(fx.transition(tenant, version.id, MAKER)) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_SELF_APPROVAL, selfApproval.code)

        val approved =
            withRequestContext { rules.approve(fx.transition(tenant, version.id, tenant.checker)) }

        assertEquals(PostingRuleVersionStatus.ACTIVE, approved.status)
        assertEquals(listOf("SUBMIT", "APPROVE"), fx.transitionNames(tenant, version.id))
        assertEquals(
            listOf("posting_rule.approve", "posting_rule.create", "posting_rule.create_version"),
            fx.auditActions(tenant),
        )
    }

    @Test
    fun `an approved version is immutable and a successor supersedes it without overlap`() {
        val tenant = fx.provisionTenant("rule-supersede")
        val first =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusYears(1)),
            )

        val frozen =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    rules.amendDraft(
                        AmendPostingRuleVersionCommand(
                            tenant.organisationId,
                            MAKER,
                            first.id,
                            tenant.businessDate,
                            fx.legs(tenant),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_VERSION_NOT_EDITABLE, frozen.code)

        val second =
            fx.activate(
                tenant,
                fx.draftVersion(
                    tenant,
                    from = tenant.businessDate.minusMonths(1),
                    feeShare = "10",
                ),
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
                fx.activate(
                    tenant,
                    fx.draftVersion(tenant, from = tenant.businessDate.minusYears(2)),
                )
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_WINDOW_INVALID, tooEarly.code)
    }

    @Test
    fun `rejection returns a version to draft with a reason, and one-sided legs cannot submit`() {
        val tenant = fx.provisionTenant("rule-reject")
        val version = fx.draftVersion(tenant, from = tenant.businessDate)
        withRequestContext { rules.submit(fx.transition(tenant, version.id, MAKER)) }

        assertFailsWith<InvalidOperationException> {
            withRequestContext { rules.reject(fx.transition(tenant, version.id, tenant.checker)) }
        }
        val rejected =
            withRequestContext {
                rules.reject(
                    fx.transition(tenant, version.id, tenant.checker, reason = "Wrong fee account"),
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
                    fx.legs(tenant).filter { it.side == PostingSide.DEBIT },
                ),
            )
        }
        val oneSided =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { rules.submit(fx.transition(tenant, version.id, MAKER)) }
            }
        assertEquals("accounting.posting_rule_legs_one_sided", oneSided.code)
    }

    @Test
    fun `the same intent always resolves the same version, and the posting records it`() {
        val tenant = fx.provisionTenant("rule-resolve")
        val version =
            fx.activate(
                tenant,
                fx.draftVersion(
                    tenant,
                    from = tenant.businessDate.minusMonths(1),
                    feeShare = "2.5",
                ),
            )

        val dryRun =
            withRequestContext {
                fx.dryRun(tenant, "1000.00", tenant.businessDate)
            }
        assertEquals(version.id, dryRun.postingRuleVersionId)
        assertEquals(
            listOf("1000.00", "25.00", "975.00"),
            dryRun.legs.map { it.amount.amount.toPlainString() },
        )
        assertEquals(0, fx.journalCount(tenant), "a dry run persists nothing")

        val receipt =
            fx.inContext(tenant, MAKER) {
                postingTransactions.execute("A savings deposit") {
                    postingService.post(
                        PostFinancialFactsCommand(
                            context = fx.context(tenant, MAKER),
                            source =
                                AccountingSourceReference(
                                    "savings",
                                    "SAVINGS_DEPOSIT",
                                    uuidV7(),
                                    "dep-rule-1",
                                ),
                            intent = fx.intent("1000.00"),
                        ),
                    )
                }
            }
        val again =
            withRequestContext {
                fx.dryRun(tenant, "1000.00", tenant.businessDate)
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
        val tenant = fx.provisionTenant("rule-effective")
        val old =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(3)),
            )
        val new =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate, feeShare = "10"),
            )

        val today = withRequestContext { fx.dryRun(tenant, "100.00", tenant.businessDate) }
        val lastMonth =
            withRequestContext {
                fx.dryRun(tenant, "100.00", tenant.businessDate.minusMonths(1))
            }

        assertEquals(new.id, today.postingRuleVersionId)
        assertEquals(old.id, lastMonth.postingRuleVersionId)
        assertEquals(3, today.legs.size)
        assertEquals(2, lastMonth.legs.size)
    }

    @Test
    fun `no-match and ambiguous configurations are explicit failures`() {
        val tenant = fx.provisionTenant("rule-ambiguous")
        val none =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    fx.dryRun(tenant, "1.00", tenant.businessDate)
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_FOUND, none.code)

        // One rule pinned to the product class, another to the currency: both match a KES posting
        // for that product at the same specificity.
        fx.activate(
            tenant,
            fx.draftVersion(
                tenant,
                from = tenant.businessDate,
                rule = fx.createRule(tenant, "BY-PRODUCT", productClass = "SAVINGS:REGULAR"),
            ),
        )
        fx.activate(
            tenant,
            fx.draftVersion(
                tenant,
                from = tenant.businessDate,
                rule = fx.createRule(tenant, "BY-CCY", currencyCode = "KES"),
            ),
        )

        val ambiguous =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    rules.dryRun(
                        PostingRuleDryRunCommand(
                            fx.context(tenant, MAKER),
                            fx.intent("1.00", productClass = "SAVINGS:REGULAR"),
                            tenant.businessDate,
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_AMBIGUOUS, ambiguous.code)

        // Without the product class only the currency rule matches, deterministically.
        val resolved =
            withRequestContext {
                fx.dryRun(tenant, "1.00", tenant.businessDate)
            }
        assertNotNull(resolved.postingRuleVersionId)
    }

    @Test
    fun `every operation is permission gated and a duplicate selector is a conflict`() {
        val tenant = fx.provisionTenant("rule-permissions")

        assertFailsWith<ForbiddenOperationException> {
            fx.createRule(tenant, "X", actor = STRANGER)
        }
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                fx.dryRun(tenant, "1.00", tenant.businessDate, actor = STRANGER)
            }
        }
        val duplicate = assertFailsWith<ApplicationException> { fx.createRule(tenant, "AGAIN") }
        assertEquals(PostingErrorCodes.POSTING_RULE_DUPLICATE, duplicate.code)
    }

    @Test
    fun `retirement closes the window, needs a different actor and a reason, and is audited`() {
        val tenant = fx.provisionTenant("rule-retire")
        val version = fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))

        val noReason =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { fx.retire(tenant, version.id, tenant.checker, reason = null) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_REASON_REQUIRED, noReason.code)

        // The actor who approved the version cannot also retire it.
        val selfRetire =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { fx.retire(tenant, version.id, tenant.checker) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_SELF_APPROVAL, selfRetire.code)

        val secondChecker = fx.approver(tenant, "rule-retire-second")
        val retired = withRequestContext { fx.retire(tenant, version.id, secondChecker) }
        assertEquals(PostingRuleVersionStatus.RETIRED, retired.status)
        assertEquals(tenant.businessDate, retired.effectiveTo)
        assertTrue(
            "posting_rule.retire" in fx.auditActions(tenant),
            "retirement can stop a product posting, so it is audited in its own right",
        )

        // The day after the cutoff no version governs the event any more.
        val unresolved =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { fx.dryRun(tenant, "10.00", tenant.businessDate.plusDays(1)) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_FOUND, unresolved.code)
    }

    @Test
    fun `a fact supplied twice is refused rather than silently losing an amount`() {
        val tenant = fx.provisionTenant("rule-duplicate-fact")
        fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))

        val duplicated =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    rules.dryRun(
                        PostingRuleDryRunCommand(
                            fx.context(tenant, MAKER),
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
}
