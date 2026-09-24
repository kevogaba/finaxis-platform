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
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.application.ApplicationException
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.jooq.tables.references.POSTING_RULE_VERSION
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
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
    private val transactionManager: PlatformTransactionManager,
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
                            first.rowVersion,
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
                    rejected.rowVersion,
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
    fun `a draft amendment prepared against an earlier view is refused, not applied`() {
        val tenant = fx.provisionTenant("rule-stale-amend")
        val version = fx.draftVersion(tenant, from = tenant.businessDate)

        fun amend(
            rowVersion: Long,
            feeShare: String?,
        ) = withRequestContext {
            rules.amendDraft(
                AmendPostingRuleVersionCommand(
                    tenant.organisationId,
                    MAKER,
                    version.id,
                    tenant.businessDate.plusDays(1),
                    rowVersion,
                    fx.legs(tenant, feeShare),
                ),
            )
        }

        // Two edits prepared from the same read: the first lands and moves the row version on.
        val first = amend(version.rowVersion, feeShare = "10")
        assertEquals(version.rowVersion + 1, first.rowVersion)
        val stale =
            assertFailsWith<ConflictException> { amend(version.rowVersion, feeShare = null) }
        assertEquals(PostingErrorCodes.POSTING_RULE_VERSION_STALE, stale.code)

        val after = fx.version(tenant, version.id)
        assertEquals(first.rowVersion, after.rowVersion, "a refused amendment writes nothing")
        assertEquals(
            3,
            withRequestContext { fx.preview(tenant, version.id, "100.00") }.legs.size,
            "the first maker's three legs survive; the stale two-leg set never replaced them",
        )

        // Re-read and retried, the same edit is admitted.
        assertEquals(first.rowVersion + 1, amend(after.rowVersion, feeShare = null).rowVersion)
    }

    @Test
    fun `only a version's author amends or submits it, and the author can never approve it`() {
        val tenant = fx.provisionTenant("rule-author")
        val version = fx.draftVersion(tenant, from = tenant.businessDate)
        val colleague = fx.approver(tenant, "rule-author-colleague")

        val amendByOther =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext {
                    rules.amendDraft(
                        AmendPostingRuleVersionCommand(
                            tenant.organisationId,
                            colleague,
                            version.id,
                            tenant.businessDate,
                            version.rowVersion,
                            fx.legs(tenant, feeShare = "10"),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_THE_MAKER, amendByOther.code)

        // Another holder of posting_rule.submit cannot submit it on the author's behalf, which is
        // what would have made the author eligible to approve the legs they wrote.
        val submitByOther =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext { rules.submit(fx.transition(tenant, version.id, colleague)) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_NOT_THE_MAKER, submitByOther.code)
        assertEquals(PostingRuleVersionStatus.DRAFT, fx.version(tenant, version.id).status)

        // A version another actor submitted before the author guard existed: the approval still
        // refuses its author, not merely its submitter.
        fx.submit(tenant, version)
        dsl
            .update(POSTING_RULE_VERSION)
            .set(POSTING_RULE_VERSION.CREATED_BY, tenant.checker)
            .where(POSTING_RULE_VERSION.ID.eq(version.id))
            .execute()
        val authorApproval =
            assertFailsWith<ForbiddenOperationException> {
                withRequestContext {
                    rules.approve(
                        fx.transition(tenant, version.id, tenant.checker),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_SELF_APPROVAL, authorApproval.code)
        assertEquals(
            PostingRuleVersionStatus.PENDING_APPROVAL,
            fx.version(tenant, version.id).status,
        )

        val approved =
            withRequestContext { rules.approve(fx.transition(tenant, version.id, colleague)) }
        assertEquals(PostingRuleVersionStatus.ACTIVE, approved.status)
    }

    @Test
    fun `retirement cannot backdate a cutoff before the business date`() {
        val tenant = fx.provisionTenant("rule-retire-backdated")
        val version =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(2)),
            )
        val retirer = fx.approver(tenant, "rule-retire-backdated")

        val backdated =
            assertFailsWith<InvalidOperationException> {
                withRequestContext {
                    fx.retire(
                        tenant,
                        version.id,
                        retirer,
                        effectiveTo = tenant.businessDate.minusDays(1),
                    )
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_WINDOW_INVALID, backdated.code)
        val untouched = fx.version(tenant, version.id)
        assertEquals(PostingRuleVersionStatus.ACTIVE, untouched.status)
        assertNull(untouched.effectiveTo)
        assertEquals(
            version.id,
            withRequestContext {
                fx.dryRun(tenant, "10.00", tenant.businessDate.minusDays(1))
            }.postingRuleVersionId,
            "yesterday is still governed, so a prior-day correction can still be posted",
        )

        val retired = withRequestContext { fx.retire(tenant, version.id, retirer) }
        assertEquals(tenant.businessDate, retired.effectiveTo)
    }

    /**
     * A retirement and a business-date advance cannot interleave (Codex review on #146).
     *
     * The holder plays the advance: it moves the business date to tomorrow and keeps its
     * transaction open. A retirement cutting off *today* then has to wait for it - which is only
     * true if the check reads the date under a lock - and, once the advance commits, is judged
     * against tomorrow and refused. A plain read would answer from the committed row, today, never
     * block, and persist a cutoff that is already in the past by the time it commits.
     */
    @Test
    fun `a retirement waits for a concurrent business-date advance and is judged after it`() {
        val tenant = fx.provisionTenant("rule-retire-race")
        val version =
            fx.activate(
                tenant,
                fx.draftVersion(tenant, from = tenant.businessDate.minusMonths(1)),
            )
        val retirer = fx.approver(tenant, "rule-retire-race")
        val probe = LockOverlapProbe(dsl)
        val advanced = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holderPid = AtomicInteger()

        val retirement =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val advance =
                    executor.submit {
                        TransactionTemplate(transactionManager).executeWithoutResult {
                            holderPid.set(probe.currentBackendPid())
                            dsl
                                .update(BUSINESS_DATE)
                                .set(
                                    BUSINESS_DATE.CURRENT_BUSINESS_DATE,
                                    tenant.businessDate.plusDays(1),
                                ).where(BUSINESS_DATE.ORGANISATION_ID.eq(tenant.organisationId))
                                .execute()
                            advanced.countDown()
                            assertTrue(release.await(LATCH_TIMEOUT, TimeUnit.SECONDS))
                        }
                    }
                assertTrue(advanced.await(LATCH_TIMEOUT, TimeUnit.SECONDS))
                val retire =
                    executor.submit<Throwable?> {
                        runCatching {
                            withRequestContext { fx.retire(tenant, version.id, retirer) }
                        }.exceptionOrNull()
                    }
                probe.awaitBlockedBehind(holderPid.get())
                release.countDown()
                advance.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                retire.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
            }

        val refused = assertIs<InvalidOperationException>(retirement)
        assertEquals(PostingErrorCodes.POSTING_RULE_WINDOW_INVALID, refused.code)
        assertEquals(PostingRuleVersionStatus.ACTIVE, fx.version(tenant, version.id).status)
    }

    @Test
    fun `a draft is cancelled by its author, or recovered by a checker, and frees its rule`() {
        val tenant = fx.provisionTenant("rule-cancel")
        val draft = fx.draftVersion(tenant, from = tenant.businessDate)

        val noReason =
            assertFailsWith<InvalidOperationException> {
                withRequestContext { rules.cancel(fx.transition(tenant, draft.id, MAKER)) }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_REASON_REQUIRED, noReason.code)
        assertFailsWith<ForbiddenOperationException> {
            withRequestContext {
                rules.cancel(fx.transition(tenant, draft.id, STRANGER, reason = "Not mine"))
            }
        }

        val cancelled =
            withRequestContext {
                rules.cancel(fx.transition(tenant, draft.id, MAKER, reason = "Wrong event"))
            }
        assertEquals(PostingRuleVersionStatus.CANCELLED, cancelled.status)
        assertEquals("Wrong event", cancelled.statusReason)
        assertEquals(listOf("CANCEL"), fx.transitionNames(tenant, draft.id))
        assertTrue("posting_rule.cancel_version" in fx.auditActions(tenant))

        // An orphan: its author is someone who can no longer act on it. A checker withdraws it,
        // and the rule's next version can be started again.
        val orphan = fx.draftVersion(tenant, from = tenant.businessDate)
        dsl
            .update(POSTING_RULE_VERSION)
            .set(POSTING_RULE_VERSION.CREATED_BY, STRANGER)
            .where(POSTING_RULE_VERSION.ID.eq(orphan.id))
            .execute()
        val recovered =
            withRequestContext {
                rules.cancel(
                    fx.transition(tenant, orphan.id, tenant.checker, reason = "Author left"),
                )
            }
        assertEquals(PostingRuleVersionStatus.CANCELLED, recovered.status)
        val next = fx.activate(tenant, fx.draftVersion(tenant, from = tenant.businessDate))
        assertEquals(PostingRuleVersionStatus.ACTIVE, next.status)

        val terminal =
            assertFailsWith<ConflictException> {
                withRequestContext {
                    rules.cancel(fx.transition(tenant, next.id, MAKER, reason = "Too late"))
                }
            }
        assertEquals(PostingErrorCodes.POSTING_RULE_TRANSITION_NOT_ALLOWED, terminal.code)
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

    private companion object {
        const val LATCH_TIMEOUT = 10L
        const val FUTURE_TIMEOUT = 60L
    }
}
