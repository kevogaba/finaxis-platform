package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ChartOfAccountsService
import com.finaxis.platform.accounting.application.CreateGlAccountCommand
import com.finaxis.platform.accounting.application.GlAccountLifecycleService
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.GlAccountTransitionCommand
import com.finaxis.platform.accounting.application.UpdateGlAccountCommand
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleCommand
import com.finaxis.platform.accounting.application.rules.CreatePostingRuleVersionCommand
import com.finaxis.platform.accounting.application.rules.PostingRuleService
import com.finaxis.platform.accounting.application.rules.PostingRuleVersionTransitionCommand
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountCode
import com.finaxis.platform.accounting.domain.AccountResolution
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.GlAccountStatus
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRuleLeg
import com.finaxis.platform.accounting.domain.PostingRuleSelector
import com.finaxis.platform.accounting.domain.PostingRuleVersionStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
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
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DeadlockLoserDataAccessException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Issue #90's posting-time `gl_account` locking, proven against real PostgreSQL.
 *
 * Every scenario is latch-driven with generous absolute timeouts and *relative* ordering
 * assertions; none uses a wall-clock sleep to create the interleaving, for the same reason
 * `FiscalPeriodConcurrencyIntegrationTests` gives: a sleep-based race test passes or fails on
 * machine speed rather than on the property under test.
 *
 * The guarantee: [GlAccountStore.lockForPosting] is a shared lock that many postings may hold on
 * one account at once, but that excludes - and is excluded by - the exclusive lock
 * [GlAccountStore.lockForStateChange] takes, whichever of [GlAccountLifecycleService.deactivate],
 * [ChartOfAccountsService.update]'s structural-change path, or [PostingRuleService.approve]'s
 * account validation takes it. A posting and an account lifecycle or chart-structure change
 * against the same account therefore always serialise, and a multi-leg posting takes its account
 * locks in ascending id order so that two postings referencing the same accounts can never
 * deadlock each other. See `docs/adr/0023-posting-idempotency-and-account-locking.md`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class GlAccountPostingLockConcurrencyIntegrationTests(
    private val accounts: GlAccountStore,
    private val lifecycle: GlAccountLifecycleService,
    private val chart: ChartOfAccountsService,
    private val rules: PostingRuleService,
    private val engine: PostingEngine,
    private val postings: PostingTransactionBoundary,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)

    /**
     * For L1 to L6, which take the account locks directly and post nothing.
     *
     * Those scenarios are about `FOR SHARE` against `FOR UPDATE` on one `gl_account` row, so the
     * transaction they need is any transaction; raising them to the posting path's `SERIALIZABLE`
     * would change what they prove rather than strengthen it. Only L7, which drives the real
     * engine, goes through [PostingTransactionBoundary].
     */
    private val transactions = TransactionTemplate(transactionManager)
    private val probe = LockOverlapProbe(dsl)

    @Test
    fun `L1 two postings hold the shared account lock on the same account at the same time`() {
        val organisationId = organisation("l1")
        val account = activeAccount(organisationId, "9010")
        val bothLocked = CountDownLatch(2)
        val release = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures =
                (1..2).map {
                    executor.submit {
                        transactions.execute {
                            accounts.lockForPosting(organisationId, account.id)
                            bothLocked.countDown()
                            assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(
                bothLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "both postings must hold the shared lock concurrently - if this times out, " +
                    "FOR SHARE has turned posting to one account into a queue",
            )
            release.countDown()
            futures.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `L2 a posting's account lock blocks a deactivation, which completes once released`() {
        val organisationId = organisation("l2")
        val account = activeAccount(organisationId, "9010")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    transactions.execute {
                        accounts.lockForPosting(organisationId, account.id)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val deactivation =
                executor.submit {
                    withRequestContext {
                        lifecycle.deactivate(
                            GlAccountTransitionCommand(
                                organisationId,
                                account.id,
                                MAKER,
                                "Closing",
                            ),
                        )
                    }
                }
            // The barrier that gives this test its teeth, exactly as in the fiscal-period suite:
            // without it the posting's release fires before the deactivation ever blocks, and the
            // test would pass whether or not GlAccountLifecycleService.deactivate takes the lock.
            awaitBlockedOnLock()

            releasePosting.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            deactivation.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(
            GlAccountStatus.INACTIVE,
            accounts.findById(organisationId, account.id)?.status,
            "the deactivation must complete, and observe no other outcome, once the posting's " +
                "shared lock is released",
        )
    }

    @Test
    fun `L3 a deactivation's exclusive lock blocks a posting, which proceeds once released`() {
        val organisationId = organisation("l3")
        val account = activeAccount(organisationId, "9010")
        val exclusiveHeld = CountDownLatch(1)
        val releaseExclusive = CountDownLatch(1)
        val postedAccount = AtomicReference<GlAccount>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val deactivating =
                executor.submit {
                    transactions.execute {
                        // GlAccountLifecycleService.deactivate commits in one uninterruptible
                        // step, so there is no way to pause it mid-transition from outside; its
                        // exclusive lock is taken directly here to hold it open across the latch,
                        // simulating "the lifecycle service is mid-transition" for this account.
                        accounts.lockForStateChange(organisationId, account.id)
                        exclusiveHeld.countDown()
                        assertTrue(releaseExclusive.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(exclusiveHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val posting =
                executor.submit {
                    transactions.execute {
                        postedAccount.set(accounts.lockForPosting(organisationId, account.id))
                    }
                }
            awaitBlockedOnLock()

            releaseExclusive.countDown()
            deactivating.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(
            account.id,
            postedAccount.get()?.id,
            "the posting-side shared lock must proceed once the exclusive lock is released",
        )
    }

    @Test
    fun `L4 a posting's account lock blocks a structural chart update, completing once released`() {
        // A DRAFT account, not an approved one: ChartOfAccountsService.requireAmendable refuses
        // every structural change once an account is ACTIVE - whatever hasChildren or
        // hasJournalLines says - so a DRAFT account is the only one against which a code change is
        // a *legal* edit, which is what lets this test isolate the lock interaction from that
        // separate, unrelated refusal.
        val organisationId = organisation("l4")
        val account = draftAccount(organisationId, "9010")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    transactions.execute {
                        accounts.lockForPosting(organisationId, account.id)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val update =
                executor.submit {
                    withRequestContext {
                        chart.update(
                            UpdateGlAccountCommand(
                                organisationId = organisationId,
                                actorId = MAKER,
                                accountId = account.id,
                                code = AccountCode("9099"),
                            ),
                        )
                    }
                }
            awaitBlockedOnLock()

            releasePosting.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(
            AccountCode("9099"),
            accounts.findById(organisationId, account.id)?.code,
            "the structural update must complete once the posting's shared lock is released",
        )
    }

    @Test
    fun `L5 a chart update's exclusive lock blocks a posting, which proceeds once released`() {
        val organisationId = organisation("l5")
        val account = draftAccount(organisationId, "9010")
        val exclusiveHeld = CountDownLatch(1)
        val releaseExclusive = CountDownLatch(1)
        val postedAccount = AtomicReference<GlAccount>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val updating =
                executor.submit {
                    transactions.execute {
                        // ChartOfAccountsService.update takes this same exclusive lock after the
                        // chart-hierarchy advisory lock and before hasJournalLines/hasChildren are
                        // consulted; taken directly here to hold it open across the latch, as L3
                        // does to simulate deactivation mid-transition.
                        accounts.lockForStateChange(organisationId, account.id)
                        exclusiveHeld.countDown()
                        assertTrue(releaseExclusive.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(exclusiveHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val posting =
                executor.submit {
                    transactions.execute {
                        postedAccount.set(accounts.lockForPosting(organisationId, account.id))
                    }
                }
            awaitBlockedOnLock()

            releaseExclusive.countDown()
            updating.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(
            account.id,
            postedAccount.get()?.id,
            "the posting-side shared lock must proceed once the chart update's exclusive lock " +
                "is released",
        )
    }

    @Test
    fun `L6 approving a rule version blocks behind a deactivation's exclusive account lock`() {
        // The direction proven here - a deactivation in flight blocks approval - is the one that
        // matters for INV-10: an approval racing ahead of a deactivation that is already under way
        // must never activate a version against an account that is about to leave service. The
        // mirror direction is not constructed: PostingRuleService.approve takes its account lock
        // deep inside one @Transactional method with no seam to pause it from outside, the same
        // limitation L3 and L5 solve by taking the lock directly - but there is no equivalent
        // stand-in for "approve, but paused after locking the account", so proving that direction
        // would mean editing production code to add one, which this task does not do.
        val organisationId = organisation("l6")
        grantDirectly(organisationId, checker(), AccountingPermissions.POSTING_RULE_APPROVE)
        val referencedAccountId = schema.insertAccount(organisationId, "9010", "ASSET")
        val otherAccountId = schema.insertAccount(organisationId, "9020", "LIABILITY")
        val versionId = submittedRuleVersion(organisationId, referencedAccountId, otherAccountId)

        val exclusiveHeld = CountDownLatch(1)
        val releaseExclusive = CountDownLatch(1)
        val approvedStatus = AtomicReference<PostingRuleVersionStatus>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val deactivating =
                executor.submit {
                    transactions.execute {
                        // Simulates GlAccountLifecycleService.deactivate mid-transition, as L3
                        // does: its exclusive lock is what approve's
                        // requirePostableAccounts(lockAccounts = true) must wait behind.
                        accounts.lockForStateChange(organisationId, referencedAccountId)
                        exclusiveHeld.countDown()
                        assertTrue(releaseExclusive.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(exclusiveHeld.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val approving =
                executor.submit {
                    withRequestContext {
                        approvedStatus.set(
                            rules
                                .approve(
                                    PostingRuleVersionTransitionCommand(
                                        organisationId = organisationId,
                                        actorId = checker(),
                                        versionId = versionId,
                                    ),
                                ).status,
                        )
                    }
                }
            awaitBlockedOnLock()

            releaseExclusive.countDown()
            deactivating.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            approving.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(
            PostingRuleVersionStatus.ACTIVE,
            approvedStatus.get(),
            "approval must proceed and activate the version once the exclusive lock is released",
        )
    }

    @Test
    fun `L7 two postings on the same two accounts in opposite leg order do not deadlock`() {
        // The deadlock-avoidance proof for #90's core design decision: PostingEngine.lockAccounts
        // sorts every posting's distinct account ids into the same ascending order before locking
        // them, so two postings that reference the same two accounts - whatever order their own
        // legs name them in - always take those locks in the one order every posting agrees on.
        // Without that sort this pairing is the textbook deadlock: each posting holds the row the
        // other wants next. A real deadlock would not merely fail an assertion, it would hang, so
        // the proof is that both futures return within a generous bound rather than one raising
        // PostgreSQL's deadlock_detected.
        //
        // DELIBERATELY WEAKENED ON THIS BRANCH; PR 2 RESTORES IT. The posting path now runs at
        // SERIALIZABLE, and every posting in a tenant increments the one gapless
        // `reference_sequence` row - measured, not feared - so of two overlapping same-tenant
        // postings the second aborts with 40001 where it used to block and proceed. There is no
        // retry on this branch, so this test can no longer assert that both postings commit, and
        // pretending otherwise would make it pass for a reason that is not the one it is named
        // for. What it still asserts is the property it exists for, and the number allocator runs
        // *after* lockAccounts, so that property is still exercised on both attempts: neither
        // posting hangs, and neither dies of PostgreSQL's deadlock_detected. PR 2's
        // retry re-runs the loser on a fresh snapshot taken after the winner committed, which is
        // what makes "both postings commit" true again.
        val tenant = provisionEngineTenant("l7")

        val outcomes =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val first =
                    executor.submit<Throwable?> {
                        postInOwnTransaction(tenant, "l7-1", tenant.accountAId, tenant.accountBId)
                    }
                val second =
                    executor.submit<Throwable?> {
                        postInOwnTransaction(tenant, "l7-2", tenant.accountBId, tenant.accountAId)
                    }
                listOf(
                    first.get(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                    second.get(DEADLOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                )
            }

        outcomes.filterNotNull().forEach { failure ->
            assertFalse(
                failure is DeadlockLoserDataAccessException,
                "PostgreSQL raised deadlock_detected, which is the one outcome the ascending-id " +
                    "lock order exists to make impossible - and the one SERIALIZABLE does not " +
                    "excuse: $failure",
            )
            assertTrue(
                failure is ConcurrencyFailureException,
                "the only failure this branch tolerates is the serialization abort the gapless " +
                    "journal counter forces on the second of two overlapping same-tenant " +
                    "postings; anything else is a real defect wearing its clothes: $failure",
            )
        }
        assertTrue(
            outcomes.any { it == null },
            "SERIALIZABLE aborts the loser of an overlapping pair, not both of them: if neither " +
                "posting committed, the contention is not the one this branch accepts",
        )
    }

    // ---- account and chart helpers ------------------------------------------------------------

    private fun organisation(label: String): UUID {
        val organisationId = tenants.createActiveOrganisation("gl-lock-$label", MAKER)
        tenants.grantTenantAdmin(organisationId, checker())
        return organisationId
    }

    private fun draftAccount(
        organisationId: UUID,
        code: String,
    ): GlAccount =
        withRequestContext {
            chart.create(
                CreateGlAccountCommand(
                    organisationId = organisationId,
                    actorId = MAKER,
                    code = AccountCode(code),
                    name = "Account $code",
                    accountClass = AccountClass.ASSET,
                    usage = AccountUsage.POSTABLE,
                ),
            )
        }

    /** A DRAFT account submitted by [MAKER] and approved by [checker], landing in `ACTIVE`. */
    private fun activeAccount(
        organisationId: UUID,
        code: String,
    ): GlAccount {
        val account = draftAccount(organisationId, code)
        withRequestContext {
            lifecycle.submit(GlAccountTransitionCommand(organisationId, account.id, MAKER))
        }
        withRequestContext {
            lifecycle.approve(GlAccountTransitionCommand(organisationId, account.id, checker()))
        }
        return requireNotNull(accounts.findById(organisationId, account.id))
    }

    // ---- posting-rule helpers ------------------------------------------------------------------

    /**
     * Creates a rule and a version over [debitAccountId]/[creditAccountId], submitted for approval.
     */
    private fun submittedRuleVersion(
        organisationId: UUID,
        debitAccountId: UUID,
        creditAccountId: UUID,
    ): UUID {
        val ruleId =
            withRequestContext {
                rules
                    .createRule(
                        CreatePostingRuleCommand(
                            organisationId = organisationId,
                            actorId = MAKER,
                            code = "LOCK-RULE",
                            name = "Lock rule",
                            selector = PostingRuleSelector("LOCK_EVENT"),
                        ),
                    ).id
            }
        val version =
            withRequestContext {
                rules.createVersion(
                    CreatePostingRuleVersionCommand(
                        organisationId = organisationId,
                        actorId = MAKER,
                        ruleId = ruleId,
                        effectiveFrom = RULE_EFFECTIVE_FROM,
                        legs =
                            listOf(
                                ruleLeg(1, PostingSide.DEBIT, debitAccountId),
                                ruleLeg(2, PostingSide.CREDIT, creditAccountId),
                            ),
                    ),
                )
            }
        withRequestContext {
            rules.submit(
                PostingRuleVersionTransitionCommand(
                    organisationId = organisationId,
                    actorId = MAKER,
                    versionId = version.id,
                ),
            )
        }
        return version.id
    }

    private fun ruleLeg(
        number: Int,
        side: PostingSide,
        accountId: UUID,
    ) = PostingRuleLeg(
        number,
        side,
        AccountResolution.FIXED_ACCOUNT,
        accountId,
        "PRINCIPAL",
        ONE_HUNDRED,
        false,
        null,
    )

    // ---- posting-engine helpers ----------------------------------------------------------------

    private data class EngineTenant(
        val organisationId: UUID,
        val branchId: UUID,
        val accountAId: UUID,
        val accountBId: UUID,
    )

    /** An ACTIVE organisation with an OPEN period covering its business date, and two accounts. */
    private fun provisionEngineTenant(label: String): EngineTenant {
        val organisationId = tenants.createActiveOrganisation("gl-lock-$label", MAKER)
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
        openPeriodCovering(organisationId, businessDate)
        return EngineTenant(
            organisationId = organisationId,
            branchId = branchId,
            accountAId = schema.insertAccount(organisationId, "9010", "ASSET"),
            accountBId = schema.insertAccount(organisationId, "9020", "LIABILITY"),
        )
    }

    /** One OPEN period covering [date], inside a fiscal year covering its calendar year. */
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

    /**
     * Posts through the real boundary and returns what it threw, or `null` if it committed.
     *
     * Returning the failure rather than letting [java.util.concurrent.Future.get] wrap it keeps
     * `L7`'s assertions about the *posting's* outcome instead of about `ExecutionException`
     * nesting, and lets both futures be awaited before either is judged - so a genuine deadlock
     * still shows up as the timeout it is rather than as whichever exception arrived first.
     */
    private fun postInOwnTransaction(
        tenant: EngineTenant,
        reference: String,
        debitAccountId: UUID,
        creditAccountId: UUID,
    ): Throwable? =
        runCatching {
            inContext(tenant) {
                postings.execute("Posting an account-lock ordering scenario") {
                    postLegs(tenant, reference, debitAccountId, creditAccountId)
                }
            }
        }.exceptionOrNull()

    /** Posts a two-leg journal for [reference] with legs in the exact order given. */
    private fun postLegs(
        tenant: EngineTenant,
        reference: String,
        debitAccountId: UUID,
        creditAccountId: UUID,
    ) = engine.post(
        LedgerPostingRequest(
            context = AccountingContext(tenant.organisationId, tenant.branchId, MAKER),
            source =
                AccountingSourceReference("lock_test", "LOCK_EVENT", UUID.randomUUID(), reference),
            eventCode = "LOCK_EVENT",
            entryType = JournalEntryType.STANDARD,
        ),
    ) {
        ResolvedLegs(
            listOf(
                PostingLeg(debitAccountId, PostingSide.DEBIT, MonetaryAmount(TEN, "KES")),
                PostingLeg(creditAccountId, PostingSide.CREDIT, MonetaryAmount(TEN, "KES")),
            ),
            null,
        )
    }

    /** Installs the ambient request context the engine reconciles the caller's claim against. */
    private fun <T> inContext(
        tenant: EngineTenant,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(MAKER, null, null, null),
            ),
        ) {
            withRequestContext(block)
        }

    // ---- shared actor and permission plumbing -----------------------------------------------

    /** A checker owned by this suite, so no seeded actor accumulates roles another test counts. */
    private fun checker(): UUID {
        val existing =
            dsl
                .select(USER_ACCOUNT.ID)
                .from(USER_ACCOUNT)
                .where(USER_ACCOUNT.USERNAME.eq(CHECKER_USERNAME))
                .fetchOne(USER_ACCOUNT.ID)
        if (existing != null) {
            return existing
        }
        val now = OffsetDateTime.now()
        return dsl
            .insertInto(USER_ACCOUNT)
            .set(USER_ACCOUNT.USERNAME, CHECKER_USERNAME)
            .set(USER_ACCOUNT.EMAIL, "$CHECKER_USERNAME@finaxis.test")
            .set(USER_ACCOUNT.DISPLAY_NAME, "GL Account Lock Checker")
            .set(USER_ACCOUNT.STATUS, "ACTIVE")
            .set(USER_ACCOUNT.CREATED_AT, now)
            .set(USER_ACCOUNT.UPDATED_AT, now)
            .returning(USER_ACCOUNT.ID)
            .fetchOne()!!
            .id!!
    }

    /**
     * Grants [permissionCode] directly, for the codes `TENANT_ADMIN` does not itself carry -
     * `posting_rule.approve` chief among them, per [PostingRuleService.approve]'s separation of
     * duties from [PostingRuleService.submit].
     */
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

    /**
     * Blocks until PostgreSQL reports another backend waiting on a lock.
     *
     * Copied verbatim from `FiscalPeriodConcurrencyIntegrationTests`: this is what makes the
     * ordering scenarios discriminating. Releasing the lock holder immediately after submitting
     * the contending transaction lets the assertion pass on thread scheduling alone - the
     * contender simply has not issued its statement yet - so the test would stay green with the
     * production lock deleted. Waiting for a real `Lock` wait event means a missing lock produces
     * a timeout here instead of a false pass.
     *
     * Polls rather than sleeps a fixed interval: the loop ends as soon as the wait is observable.
     */
    private fun awaitBlockedOnLock() = probe.awaitAnyBackendBlocked()

    private companion object {
        /** The V3 bootstrap administrator: `audit_event.actor_user_id` is a real foreign key. */
        val MAKER: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val TIMEOUT_SECONDS = 20L
        const val DEADLOCK_TIMEOUT_SECONDS = 20L
        const val CHECKER_USERNAME = "gl.lock.checker"
        val ONE_HUNDRED: BigDecimal = BigDecimal(100)
        val TEN: BigDecimal = BigDecimal("10.00")

        /** Arbitrary: `PostingRuleService` never checks `effectiveFrom` against any period. */
        val RULE_EFFECTIVE_FROM: LocalDate = LocalDate.of(2026, 1, 1)
    }
}
