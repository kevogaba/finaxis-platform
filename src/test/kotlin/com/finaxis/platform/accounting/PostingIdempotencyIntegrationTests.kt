package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalLineageQuery
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingLineageService
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.ledger.SourceEntityLineageQuery
import com.finaxis.platform.accounting.application.ledger.SourceLineageQuery
import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingRequestStatus
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.ResourceNotFoundException
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
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import com.finaxis.platform.lifecycle.withRequestContext
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.interceptor.TransactionAspectSupport
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exactly-once financial effect under retries and concurrent duplicates, and lineage in both
 * directions, proved against PostgreSQL through the production wiring (issue #42).
 *
 * The database is the authority for every assertion here: `uq_posting_request_source` and the
 * `ON CONFLICT` claim decide the concurrent case, and no in-memory lock or cache takes part.
 *
 * Which makes the isolation level part of the subject rather than a setting. Every posting enters
 * through [PostingTransactionBoundary] at `SERIALIZABLE`, as production does, and at that level the
 * concurrent case is decided by the boundary's retry as much as by `ON CONFLICT` - see the note on
 * that test.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingIdempotencyIntegrationTests(
    private val engine: PostingEngine,
    private val lineage: PostingLineageService,
    private val postingService: PostingService,
    private val postingTransactions: PostingTransactionBoundary,
    private val dsl: DSLContext,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)
    private val probe = LockOverlapProbe(dsl)

    // Correction lineage needs a reversal, and a reversal needs a checker distinct from the maker
    // who posted the original (`journal.self_reversal`) - reused rather than re-implemented, so
    // this suite and JournalReversalIntegrationTests do not drift on how a checker is provisioned.
    private val reversals =
        JournalReversalFixture(dsl, engine, tenants, schema, postingTransactions)

    @Test
    fun `a sequential duplicate returns the same receipt and one financial effect`() {
        val tenant = provisionTenant("idem-sequential")

        val first = inContext(tenant) { posting { post(tenant, "dep-1") } }
        val second = inContext(tenant) { posting { post(tenant, "dep-1") } }

        assertEquals(first, second)
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `concurrent duplicates produce exactly one committed posting`() {
        // Two real transactions on two connections that demonstrably OVERLAP. The first claims the
        // source reference and is held open, uncommitted; the second is then proved - out of
        // PostgreSQL's own lock catalogue - to be parked behind it *on posting_request* before the
        // first is allowed to commit. Only then does the loser observe the committed request and
        // replay it, and neither ever sees a unique violation - the claim is decided by
        // `ON CONFLICT`, not by an error.
        //
        // The relation is part of the proof, not decoration: the holder's open transaction also
        // holds the tenant's reference_sequence counter row and its own uncommitted journal rows,
        // so "blocked behind the holder" alone would be satisfied by a duplicate that contended at
        // none of the things this scenario is about.
        //
        // Releasing both from one latch would prove only simultaneous start: the scheduler could
        // let the first commit before the second issued a statement, degrading this to the
        // sequential replay two tests above already cover.
        //
        // WHY THE REPLAY IS STILL CORRECT AT SERIALIZABLE, which is the one thing about this
        // scenario that needs saying now and did not at READ COMMITTED: the duplicate's *first*
        // attempt cannot replay, because `INSERT ... ON CONFLICT DO NOTHING` against a row
        // committed after that attempt's snapshot raises 40001 rather than quietly doing nothing,
        // so its follow-up `SELECT ... FOR UPDATE` never runs. The boundary's retry is what makes
        // the assertion below true again: a retry opens a NEW transaction, whose snapshot is taken
        // after the winner committed, so the claim reports zero rows, the `FOR UPDATE` read finds
        // the winner's request, and the replay is the ordinary one. The wait this test proves is
        // unaffected - it is a genuine row-lock wait on the uncommitted claim, which is exactly
        // what `pg_locks` can see, not an SSI conflict that it cannot.
        //
        // Three connections: the holder's, the duplicate's, and the probe's.
        val tenant = provisionTenant("idem-concurrent")
        val claimApplied = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val duplicateReturned = AtomicBoolean()

        val outcomes =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val holder =
                    executor.submit<Result<PostingReceipt>> {
                        runCatching {
                            inContext(tenant) {
                                posting {
                                    val receipt = post(tenant, "dep-race")
                                    holderPid.set(probe.currentBackendPid())
                                    claimApplied.countDown()
                                    assertTrue(
                                        releaseClaim.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                    )
                                    receipt
                                }
                            }
                        }
                    }
                assertTrue(claimApplied.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))

                val duplicate =
                    executor.submit<Result<PostingReceipt>> {
                        // Captured rather than thrown, so a failure is judged by the assertions
                        // below - which can name an exhausted retry budget - instead of arriving
                        // as an ExecutionException nobody reads past.
                        runCatching { inContext(tenant) { posting { post(tenant, "dep-race") } } }
                            .also { duplicateReturned.set(true) }
                    }

                probe.awaitClaimBlockedBehind(holderPid.get())
                assertFalse(
                    duplicateReturned.get(),
                    "the duplicate returned while the first claim was still uncommitted, so the " +
                        "two never overlapped",
                )

                releaseClaim.countDown()
                listOf(holder, duplicate).map { it.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS) }
            }

        val receipts = outcomes.map(::committedReceipt)
        assertEquals(receipts[0], receipts[1], "both callers hold the receipt of the one journal")
        assertEquals(1, journalCount(tenant))
        assertEquals("1", receipts[0].journalReference, "no gapless number was burnt by the loser")
    }

    @Test
    fun `a duplicate that waited on a claim that rolled back becomes the winner`() {
        // The mirror of the case above, and the one branch `ON CONFLICT` has that nothing else
        // reaches. The first claimant rolls back while the duplicate is parked behind it, so the
        // speculative row dies and the waiter's own `ON CONFLICT ... DO NOTHING RETURNING id`
        // returns a row: it becomes the claimant rather than the replayer, and never takes the
        // `FOR UPDATE` re-read whose `checkNotNull` would fire if the two branches disagreed.
        //
        // The wait is proved on `posting_request` specifically, for the same reason as above: a
        // duplicate parked on the counter row or on an uncommitted journal row would satisfy a
        // bare "blocked behind the holder" identically, and would be a different scenario - one
        // that never reached the claim whose rollback this test is entirely about.
        val tenant = provisionTenant("idem-rollback")
        val claimApplied = CountDownLatch(1)
        val releaseClaim = CountDownLatch(1)
        val holderPid = AtomicInteger()
        val duplicateReturned = AtomicBoolean()

        val receipt =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val holder =
                    executor.submit {
                        inContext(tenant) {
                            posting {
                                post(tenant, "dep-rollback")
                                holderPid.set(probe.currentBackendPid())
                                claimApplied.countDown()
                                assertTrue(
                                    releaseClaim.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                )
                                // The boundary owns the transaction and hands out no
                                // TransactionStatus, so the rollback is asked for through the
                                // same static hook Spring's own `@Transactional` code uses. It
                                // marks the transaction local-rollback-only, which rolls back
                                // without raising - exactly what the TransactionTemplate callback
                                // did before, and what this scenario needs: the claim must die of
                                // a rollback, not of an exception the duplicate could see.
                                TransactionAspectSupport
                                    .currentTransactionStatus()
                                    .setRollbackOnly()
                            }
                        }
                    }
                assertTrue(claimApplied.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))

                val duplicate =
                    executor.submit<PostingReceipt> {
                        inContext(tenant) { posting { post(tenant, "dep-rollback") } }
                            .also { duplicateReturned.set(true) }
                    }

                probe.awaitClaimBlockedBehind(holderPid.get())
                assertFalse(
                    duplicateReturned.get(),
                    "the duplicate returned before the first claim rolled back, so it never " +
                        "waited on the claim this scenario is about",
                )

                releaseClaim.countDown()
                holder.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                duplicate.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

        assertEquals(1, journalCount(tenant), "the rolled-back attempt left no journal behind")
        assertEquals(
            1,
            dsl.fetchCount(
                POSTING_REQUEST,
                POSTING_REQUEST.ORGANISATION_ID.eq(tenant.organisationId),
            ),
            "the rolled-back claim left no posting_request behind",
        )
        assertEquals(
            "1",
            receipt.journalReference,
            "the rolled-back attempt burnt no gapless number: reference_sequence is a counter " +
                "row, so its bump rolls back with everything else",
        )
    }

    @Test
    fun `a retry after a lost post-commit response resolves to the existing journal`() {
        // The client posted, the transaction committed, the response never arrived. The retry is
        // an ordinary second call with the same durable identity.
        val tenant = provisionTenant("idem-retry")
        val committed = inContext(tenant) { posting { post(tenant, "dep-lost") } }
        val storedBefore = journalIds(tenant)

        val retried = inContext(tenant) { posting { post(tenant, "dep-lost") } }

        assertEquals(committed.journalEntryId, retried.journalEntryId)
        assertEquals(storedBefore, journalIds(tenant))
    }

    @Test
    fun `the same reference with a different request is a deterministic conflict`() {
        val tenant = provisionTenant("idem-conflict")
        inContext(tenant) { posting { post(tenant, "dep-c", amount = "500.00") } }

        repeat(2) {
            val failure =
                assertFailsWith<ConflictException> {
                    inContext(tenant) { posting { post(tenant, "dep-c", amount = "501.00") } }
                }
            assertEquals(PostingErrorCodes.POSTING_REQUEST_CONFLICT, failure.code)
        }
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `idempotency is scoped to the tenant and the source module`() {
        val tenant = provisionTenant("idem-tenant-a")
        val other = provisionTenant("idem-tenant-b")

        inContext(tenant) { posting { post(tenant, "dep-shared") } }
        inContext(other) { posting { post(other, "dep-shared") } }
        inContext(tenant) { posting { post(tenant, "dep-shared", module = "loans") } }

        assertEquals(2, journalCount(tenant), "a different module is a different business event")
        assertEquals(1, journalCount(other))
    }

    @Test
    fun `lineage is queryable forward from the source and backward from the journal`() {
        val tenant = provisionTenant("idem-lineage")
        val entityId = uuidV7()
        val receipt =
            inContext(tenant) {
                posting {
                    post(
                        tenant,
                        "dep-l1",
                        entityId = entityId,
                        narrative = "First",
                    )
                }
            }
        inContext(tenant) { posting { post(tenant, "dep-l2", entityId = entityId) } }

        val forward =
            assertNotNull(
                lineage.findBySource(
                    SourceLineageQuery(tenant.organisationId, ACTOR, "savings", "dep-l1"),
                ),
            )
        assertEquals(receipt.postingRequestId, forward.request.id)
        assertEquals(PostingRequestStatus.POSTED, forward.request.status)
        assertEquals(entityId, forward.request.sourceEntityId)
        assertEquals("First", forward.request.narrative)
        assertEquals(receipt.journalEntryId, forward.journal?.id)
        assertEquals(2, forward.lines.size)

        val backward =
            assertNotNull(
                lineage.findByJournal(
                    JournalLineageQuery(tenant.organisationId, ACTOR, receipt.journalEntryId),
                ),
            )
        assertEquals("dep-l1", backward.request.sourceReference)
        assertEquals("SAVINGS_DEPOSIT", backward.request.sourceEntityType)
        assertEquals(entityId, backward.request.sourceEntityId)
    }

    @Test
    fun `lineage from a business entity is newest first, bounded and walkable by cursor`() {
        val tenant = provisionTenant("idem-lineage-paging")
        val entityId = uuidV7()
        inContext(tenant) { posting { post(tenant, "dep-l1", entityId = entityId) } }
        inContext(tenant) { posting { post(tenant, "dep-l2", entityId = entityId) } }

        val page1 =
            lineage.listForSourceEntity(
                SourceEntityLineageQuery(
                    tenant.organisationId,
                    ACTOR,
                    "savings",
                    "SAVINGS_DEPOSIT",
                    entityId,
                    pageSize = 1,
                ),
            )
        assertEquals(listOf("dep-l2"), page1.items.map { it.request.sourceReference })
        val page2 =
            lineage.listForSourceEntity(
                SourceEntityLineageQuery(
                    tenant.organisationId,
                    ACTOR,
                    "savings",
                    "SAVINGS_DEPOSIT",
                    entityId,
                    pageSize = 1,
                    cursor = page1.nextCursor,
                ),
            )
        assertEquals(listOf("dep-l1"), page2.items.map { it.request.sourceReference })
        assertNull(
            lineage
                .listForSourceEntity(
                    SourceEntityLineageQuery(
                        tenant.organisationId,
                        ACTOR,
                        "savings",
                        "SAVINGS_DEPOSIT",
                        entityId,
                        pageSize = 1,
                        cursor = page2.nextCursor,
                    ),
                ).nextCursor,
        )
    }

    @Test
    fun `lineage reads are permission gated and tenant scoped`() {
        val tenant = provisionTenant("idem-lineage-auth")
        val other = provisionTenant("idem-lineage-other")
        val receipt = inContext(tenant) { posting { post(tenant, "dep-auth") } }

        assertFailsWith<ForbiddenOperationException> {
            lineage.findBySource(
                SourceLineageQuery(tenant.organisationId, STRANGER, "savings", "dep-auth"),
            )
        }
        // The other tenant's administrator, asking about their own tenant, sees nothing of ours.
        assertNull(
            lineage.findByJournal(
                JournalLineageQuery(other.organisationId, ACTOR, receipt.journalEntryId),
            ),
        )
    }

    @Test
    fun `a retry replays even after the fiscal period has since closed`() {
        // postNew is the only place the period is consulted; a replay never reaches it, so closing
        // the period between the original post and its retry must not turn the retry into
        // `accounting.fiscal_period_closed` (issue #89's core claim-ordering guarantee).
        val tenant = provisionTenant("idem-period-closed")
        val original = inContext(tenant) { posting { post(tenant, "dep-x") } }

        dsl
            .update(ACCOUNTING_FISCAL_PERIOD)
            .set(ACCOUNTING_FISCAL_PERIOD.STATUS, "CLOSED")
            .where(ACCOUNTING_FISCAL_PERIOD.ORGANISATION_ID.eq(tenant.organisationId))
            .execute()

        val retried = inContext(tenant) { posting { post(tenant, "dep-x") } }

        assertEquals(original.journalEntryId, retried.journalEntryId)
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `a retry replays even after an account has since been deactivated`() {
        // Same guarantee from the account side: a replay never calls GlAccountStore.lockForPosting,
        // so deactivating a referenced account between the original post and its retry must not
        // turn the retry into `accounting.account_not_postable`.
        val tenant = provisionTenant("idem-account-inactive")
        val original = inContext(tenant) { posting { post(tenant, "dep-x") } }

        dsl
            .update(GL_ACCOUNT)
            .set(GL_ACCOUNT.STATUS, "INACTIVE")
            .where(GL_ACCOUNT.ID.eq(tenant.debitAccountId))
            .execute()

        val retried = inContext(tenant) { posting { post(tenant, "dep-x") } }

        assertEquals(original.journalEntryId, retried.journalEntryId)
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `a retry replays even after the organisation has since been suspended`() {
        // requireTenantPostable runs only in postNew; LifecycleAccountingTenantAdapter reads this
        // column directly with no cache, so flipping it is a faithful simulation of a suspension
        // landing between the original post and its retry - which must not become
        // `accounting.organisation_not_postable`.
        val tenant = provisionTenant("idem-org-suspended")
        val original = inContext(tenant) { posting { post(tenant, "dep-x") } }

        dsl
            .update(ORGANISATION)
            .set(ORGANISATION.STATUS, "SUSPENDED")
            .where(ORGANISATION.ID.eq(tenant.organisationId))
            .execute()

        val retried = inContext(tenant) { posting { post(tenant, "dep-x") } }

        assertEquals(original.journalEntryId, retried.journalEntryId)
        assertEquals(1, journalCount(tenant))
    }

    @Test
    fun `a correction naming a target this tenant does not hold is refused`() {
        val tenant = provisionTenant("idem-correction-unknown")

        val failure =
            assertFailsWith<ResourceNotFoundException> {
                inContext(tenant) { posting { post(tenant, "dep-x", corrects = uuidV7()) } }
            }

        assertEquals(PostingErrorCodes.CORRECTION_TARGET_NOT_FOUND, failure.code)
        assertEquals(0, journalCount(tenant))
    }

    @Test
    fun `a correction naming a target that was never reversed is refused`() {
        val tenant = provisionTenant("idem-correction-unreversed")
        val original = inContext(tenant) { posting { post(tenant, "dep-original") } }

        val failure =
            assertFailsWith<ConflictException> {
                inContext(tenant) {
                    posting {
                        post(tenant, "dep-correction", corrects = original.postingRequestId)
                    }
                }
            }

        assertEquals(PostingErrorCodes.CORRECTION_TARGET_NOT_REVERSED, failure.code)
        assertEquals(1, journalCount(tenant), "only the unreversed original was ever written")
    }

    @Test
    fun `a correction naming a reversed target succeeds`() {
        val tenant = reversals.provisionTenant("idem-correction-reversed")
        val original = reversals.postOriginal(tenant, debit = "100.00")
        reversals.inContext(tenant, tenant.checker) {
            postingService.reverse(
                reversals.command(tenant, original.journalEntryId, reason = "Wrong amount"),
            )
        }

        val correction =
            reversals.inContext(tenant, JournalReversalFixture.MAKER) {
                posting {
                    reversals.postExplicit(
                        tenant,
                        "dep-corrected",
                        debit = "110.00",
                        corrects = original.postingRequestId,
                    )
                }
            }

        assertEquals(
            original.postingRequestId,
            dsl
                .select(POSTING_REQUEST.CORRECTS_POSTING_REQUEST_ID)
                .from(POSTING_REQUEST)
                .where(POSTING_REQUEST.ID.eq(correction.postingRequestId))
                .fetchOne(POSTING_REQUEST.CORRECTS_POSTING_REQUEST_ID),
        )
    }

    // ---- helpers ------------------------------------------------------------------------------

    /**
     * Unwraps one caller's outcome, naming an exhausted retry budget before anything else.
     *
     * A caller that spent every attempt would fail the `assertNull` below anyway, but as an opaque
     * conflict. This suite is the place where one retry is supposed to be enough - the duplicate
     * races exactly one other posting, and its second attempt starts on a snapshot that already
     * contains the winner - so `accounting.posting_retries_exhausted` here means the retry is not
     * reaching that fresh snapshot, and is worth its own sentence rather than a stack trace.
     */
    private fun committedReceipt(outcome: Result<PostingReceipt>): PostingReceipt {
        val failure = outcome.exceptionOrNull()
        assertNotEquals(
            PostingErrorCodes.POSTING_RETRIES_EXHAUSTED,
            (failure as? ConflictException)?.code,
            "neither caller may spend its whole retry budget on a race against one other posting",
        )
        assertNull(failure, "both callers must come back with the one journal's receipt: $failure")
        return outcome.getOrThrow()
    }

    /**
     * Opens the one `SERIALIZABLE` transaction a posting is allowed to commit in, and runs [block].
     *
     * Every posting in this suite enters here rather than through a `TransactionTemplate` of its
     * own. A bare template opens at the server default, `READ COMMITTED`, and `PostingEngine.post`
     * refuses that outright - so a suite that kept one would be asserting the isolation refusal
     * under the name of whatever rule the test was really about. The boundary is also what
     * production uses, which is the point: these scenarios are about the database deciding, and
     * the transaction they decide in has to be the real one.
     */
    private fun <T : Any> posting(block: () -> T): T =
        postingTransactions.execute("A posting under test", block)

    private data class Tenant(
        val organisationId: UUID,
        val branchId: UUID,
        val debitAccountId: UUID,
        val creditAccountId: UUID,
    )

    private fun provisionTenant(label: String): Tenant {
        val organisationId = tenants.createActiveOrganisation(label, ACTOR)
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
        return Tenant(
            organisationId = organisationId,
            branchId = branchId,
            debitAccountId = schema.insertAccount(organisationId, "1010", "ASSET"),
            creditAccountId = schema.insertAccount(organisationId, "2010", "LIABILITY"),
        )
    }

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

    @Suppress("LongParameterList")
    private fun post(
        tenant: Tenant,
        reference: String,
        amount: String = "500.00",
        module: String = "savings",
        entityId: UUID = UUID.nameUUIDFromBytes(reference.toByteArray()),
        narrative: String? = null,
        corrects: UUID? = null,
    ): PostingReceipt =
        engine.post(
            LedgerPostingRequest(
                context = AccountingContext(tenant.organisationId, tenant.branchId, ACTOR),
                source = AccountingSourceReference(module, "SAVINGS_DEPOSIT", entityId, reference),
                eventCode = "SAVINGS_DEPOSIT",
                entryType = JournalEntryType.STANDARD,
                narrative = narrative,
                correctsPostingRequestId = corrects,
                // A real DefaultPostingService caller carries these from PostingIntent.Facts; this
                // helper calls the engine directly, so it supplies the same shape by hand to keep
                // the amount-conflict scenario faithful to how a product module actually posts.
                financialFacts = listOf(FinancialFact("AMOUNT", kes(amount))),
            ),
        ) {
            ResolvedLegs(
                listOf(
                    PostingLeg(tenant.debitAccountId, PostingSide.DEBIT, kes(amount)),
                    PostingLeg(tenant.creditAccountId, PostingSide.CREDIT, kes(amount)),
                ),
                null,
            )
        }

    private fun kes(amount: String) = MonetaryAmount(BigDecimal(amount), "KES")

    private fun <T> inContext(
        tenant: Tenant,
        block: () -> T,
    ): T =
        RequestContexts.with(
            RequestContext(
                tenant = TenantContext(tenant.organisationId),
                branch = BranchContext(tenant.branchId),
                actor = ActorContext(ACTOR, null, null, null),
            ),
        ) { withRequestContext(block) }

    private fun journalCount(tenant: Tenant) =
        dsl.fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

    private fun journalIds(tenant: Tenant) =
        dsl
            .select(JOURNAL_ENTRY.ID)
            .from(JOURNAL_ENTRY)
            .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
            .fetch(JOURNAL_ENTRY.ID)
            .toSet()

    private companion object {
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val STRANGER: UUID = UUID.fromString("33333333-3333-3333-3333-333333333333")
        const val LATCH_TIMEOUT_SECONDS = 10L
        const val FUTURE_TIMEOUT_SECONDS = 60L
    }
}
