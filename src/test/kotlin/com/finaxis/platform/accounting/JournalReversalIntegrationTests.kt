package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.JournalReadStore
import com.finaxis.platform.accounting.application.ledger.JournalReversalService
import com.finaxis.platform.accounting.application.ledger.LedgerPostingRequest
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.posting.ReversePostingCommand
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.JournalEntryType
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.accounting.schema.JournalSchemaFixture
import com.finaxis.platform.accounting.support.FinancialTransactionAtomicityFixture
import com.finaxis.platform.accounting.support.FoundationAtomicityProbes
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.ForbiddenOperationException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.context.ActorContext
import com.finaxis.platform.common.context.BranchContext
import com.finaxis.platform.common.context.RequestContext
import com.finaxis.platform.common.context.RequestContexts
import com.finaxis.platform.common.context.TenantContext
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_YEAR
import com.finaxis.platform.jooq.tables.references.AUDIT_EVENT
import com.finaxis.platform.jooq.tables.references.BRANCH
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import com.finaxis.platform.jooq.tables.references.MEMBERSHIP_PERMISSION
import com.finaxis.platform.jooq.tables.references.PERMISSION
import com.finaxis.platform.jooq.tables.references.POSTING_REQUEST
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
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.interceptor.TransactionAspectSupport
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Reversal against PostgreSQL through the production wiring (issue #43).
 *
 * The original is posted by the bootstrap maker and reversed by a per-tenant checker who holds
 * `journal.reverse`, because the control under test is that the poster cannot reverse their own
 * journal. Every reversal is proved to leave the original rows byte-for-byte unchanged.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class JournalReversalIntegrationTests(
    private val engine: PostingEngine,
    private val postingService: PostingService,
    private val reversals: JournalReversalService,
    private val postings: PostingTransactionBoundary,
    private val journals: JournalReadStore,
    private val dsl: DSLContext,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val tenants = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val schema = JournalSchemaFixture(dsl)

    /** For the scenarios that only hold a lock or read; nothing that posts may use it. */
    private val transactions = TransactionTemplate(transactionManager)
    private val fx = JournalReversalFixture(dsl, engine, tenants, schema, postings)
    private val probe = LockOverlapProbe(dsl)

    @Test
    fun `a reversal exactly offsets the original and leaves its rows untouched`() {
        val tenant = fx.provisionTenant("reversal-offsets")
        val original = fx.postOriginal(tenant, debit = "1250.50")
        val originalRows = fx.rowsOf(tenant, original.journalEntryId)

        val reversal =
            fx.inContext(tenant, tenant.checker) {
                postingService.reverse(
                    fx.command(tenant, original.journalEntryId, reason = "Mis-keyed"),
                )
            }

        val header =
            assertNotNull(journals.findJournalEntry(tenant.organisationId, reversal.journalEntryId))
        assertEquals(JournalEntryType.REVERSAL, header.entryType)
        assertEquals(original.journalEntryId, header.reversesJournalEntryId)
        assertEquals(BigDecimal("1250.500000"), header.totalDebitFunctional)
        assertEquals("Mis-keyed", header.narrative)
        val originalLines =
            journals.findJournalLines(tenant.organisationId, original.journalEntryId)
        val reversalLines =
            journals.findJournalLines(tenant.organisationId, reversal.journalEntryId)
        assertEquals(originalLines.map { it.accountId }, reversalLines.map { it.accountId })
        assertEquals(
            originalLines.map { it.amount },
            reversalLines.map { it.amount },
            "same positive amounts",
        )
        assertEquals(
            listOf(PostingSide.CREDIT, PostingSide.DEBIT),
            reversalLines.map { it.side },
            "sides flipped, never negative amounts",
        )
        assertEquals(
            originalLines.map { it.subledgerReference },
            reversalLines.map { it.subledgerReference },
        )
        assertEquals(
            originalRows,
            fx.rowsOf(tenant, original.journalEntryId),
            "posted history is never rewritten",
        )
        assertEquals(
            BigDecimal.ZERO.setScale(6),
            dsl
                .select(
                    org.jooq.impl.DSL
                        .sum(JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT),
                ).from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(tenant.debitAccountId))
                .fetchOne(0, BigDecimal::class.java),
            "the account nets to zero while both turnovers remain visible",
        )
    }

    @Test
    fun `a reversal is audited with the actor and the reason in the same transaction`() {
        val tenant = fx.provisionTenant("reversal-audit")
        val original = fx.postOriginal(tenant)

        val reversal =
            fx.inContext(tenant, tenant.checker) {
                postingService.reverse(
                    fx.command(tenant, original.journalEntryId, reason = "Duplicate deposit"),
                )
            }

        val audit =
            dsl
                .selectFrom(AUDIT_EVENT)
                .where(AUDIT_EVENT.ORGANISATION_ID.eq(tenant.organisationId))
                .and(AUDIT_EVENT.ACTION.eq("journal.reverse"))
                .fetchOne()
        assertNotNull(audit)
        assertEquals(tenant.checker, audit.actorUserId)
        assertEquals(original.journalEntryId.toString(), audit.entityId?.toString())
        assertEquals("Duplicate deposit", audit.reason)
        assertTrue(audit.metadataJsonb.toString().contains(reversal.journalEntryId.toString()))
    }

    @Test
    fun `a journal is reversed at most once, sequentially`() {
        val tenant = fx.provisionTenant("reversal-once")
        val original = fx.postOriginal(tenant)
        fx.inContext(tenant, tenant.checker) {
            postingService.reverse(
                fx.command(tenant, original.journalEntryId, reason = "First"),
            )
        }

        val sequential =
            assertFailsWith<ConflictException> {
                fx.inContext(tenant, tenant.checker) {
                    postingService.reverse(
                        fx.command(tenant, original.journalEntryId, reason = "Second"),
                    )
                }
            }
        assertEquals(PostingErrorCodes.JOURNAL_ALREADY_REVERSED, sequential.code)
        assertEquals(1, fx.reversalsOf(tenant, original.journalEntryId))
    }

    /**
     * Two checkers race to reverse a fresh journal.
     *
     * Releasing them from one latch would prove only that they started together - the first could
     * commit before the second issued a statement, leaving the sequential path above as the only
     * thing actually exercised, and the scenario green with `PostgresJournalReversalLock` deleted.
     *
     * So the overlap is established first: a third transaction takes the reversal's own advisory
     * key and holds it, both racers are proved to be parked on that exact key at the same moment,
     * and only then is the key released. Both are then demonstrably inside their transactions
     * together. The advisory lock serialises them, exactly one reversal exists, and the loser is
     * refused - never with a unique violation, and never by writing a second reversal.
     *
     * **The loser's refusal is deliberately weaker on this branch, and PR 2 restores it.** The
     * postings now run at `SERIALIZABLE`, and a racer that waits on the advisory key fixed its
     * snapshot *before* it began to wait: waiting is precisely what makes that snapshot stale. So
     * the loser wakes, reads at its own pre-winner snapshot, does not see the reversal the winner
     * committed, and proceeds - until it reaches the tenant's single gapless `reference_sequence`
     * row, which the winner has since updated, and PostgreSQL refuses to serialize the access
     * (`40001`). There is no retry on this branch, so the failure reaches the caller as a
     * `ConcurrencyFailureException` instead of `accounting.journal_already_reversed`. Once PR 2's
     * retry boundary lands, the retried attempt opens on a fresh snapshot that contains the
     * winner's reversal and is refused by name again, and this assertion narrows back to the
     * named conflict alone. The invariant this scenario exists for - one reversal per journal, and
     * the loser refused rather than admitted - holds either way, so it is what is asserted here.
     */
    @Test
    fun `a journal is reversed at most once by two overlapping reversers`() {
        val tenant = fx.provisionTenant("reversal-once-concurrent")
        val racedOriginal = fx.postOriginal(tenant, reference = "dep-race")
        val reversalKey = fx.reversalLockKey(tenant, racedOriginal.journalEntryId)
        val keyHeld = CountDownLatch(1)
        val releaseKey = CountDownLatch(1)
        val outcomes =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val holder = executor.submit { holdReversalKey(reversalKey, keyHeld, releaseKey) }
                assertTrue(keyHeld.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val racers =
                    (1..2).map { executor.submitReversal(tenant, racedOriginal.journalEntryId) }

                probe.awaitBlockedOnAdvisoryKey(
                    AdvisoryLockNamespace.ACCOUNTING_JOURNAL_REVERSAL,
                    reversalKey,
                    waiters = 2,
                )
                assertEquals(
                    0,
                    fx.reversalsOf(tenant, racedOriginal.journalEntryId),
                    "neither racer got past the advisory lock while it was held elsewhere",
                )

                releaseKey.countDown()
                holder.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                racers.map { it.outcome() }
            }
        assertEquals(1, outcomes.count { it.isSuccess }, "exactly one reversal wins: $outcomes")
        val loser = outcomes.single { it.isFailure }.exceptionOrNull()
        if (loser is ConflictException) {
            assertEquals(PostingErrorCodes.JOURNAL_ALREADY_REVERSED, loser.code)
        } else {
            assertTrue(
                loser is ConcurrencyFailureException,
                "the loser is refused by name or aborted as a serialization failure, never " +
                    "admitted and never a unique violation, but got $loser",
            )
        }
        assertEquals(1, fx.reversalsOf(tenant, racedOriginal.journalEntryId))
    }

    /**
     * Marks the transaction [PostingTransactionBoundary] opened rollback-only, from inside its
     * lambda.
     *
     * The boundary hands the work no `TransactionStatus`, on purpose - it owns the transaction so
     * that it alone can retry it - so a scenario that needs the transaction abandoned rather than
     * committed reaches the status the same way any Spring-advised method would.
     */
    private fun abandonTransaction() =
        TransactionAspectSupport.currentTransactionStatus().setRollbackOnly()

    /** Holds the journal-reversal advisory key open until [release], so a reverser parks on it. */
    private fun holdReversalKey(
        objectId: Int,
        held: CountDownLatch,
        release: CountDownLatch,
    ) {
        transactions.execute {
            fx.takeReversalLock(objectId)
            held.countDown()
            assertTrue(release.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
        }
    }

    /** Submits one racing reversal, capturing its refusal rather than letting it escape. */
    private fun ExecutorService.submitReversal(
        tenant: JournalReversalFixture.Tenant,
        journalEntryId: UUID,
    ) = submit<Result<PostingReceipt>> {
        runCatching {
            fx.inContext(tenant, tenant.checker) {
                postingService.reverse(fx.command(tenant, journalEntryId, reason = "Race"))
            }
        }
    }

    /** Unwraps the executor's own wrapper so the caller sees the failure the service raised. */
    private fun Future<Result<PostingReceipt>>.outcome() =
        try {
            get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (ex: ExecutionException) {
            Result.failure(ex.cause ?: ex)
        }

    /**
     * The other side of the advisory lock, and the one nothing covered: the first claimant rolls
     * back while a second reverser is parked on its key.
     *
     * `pg_advisory_xact_lock` is released by rollback exactly as by commit, so the waiter wakes,
     * finds no reversal, and becomes the one that writes it. A journal whose only reversal attempt
     * rolled back must stay reversible - otherwise a failed correction would strand it forever.
     *
     * The abandoned attempt rolls back without committing anything, so the waiter's own snapshot
     * is never stale against it and no `40001` enters this scenario: what the waiter contends for
     * is the advisory key alone.
     */
    @Test
    fun `a reversal that rolls back leaves the journal reversible and the waiter wins`() {
        val tenant = fx.provisionTenant("reversal-rollback")
        val original = fx.postOriginal(tenant, reference = "dep-rollback")
        val reversalKey = fx.reversalLockKey(tenant, original.journalEntryId)
        val reversalApplied = CountDownLatch(1)
        val releaseRollback = CountDownLatch(1)
        val waiterReturned = AtomicBoolean()

        val winner =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                val abandoned =
                    executor.submit {
                        fx.inContext(tenant, tenant.checker) {
                            // The abandoned attempt enters through the same boundary production
                            // enters through, and calls `JournalReversalService.reverse` - which is
                            // `Propagation.MANDATORY` - rather than `PostingService.reverse`, which
                            // opens a boundary transaction of its own and would be refused inside
                            // this one. Marking the boundary's own transaction rollback-only is
                            // what makes the abandonment real rather than simulated: it is the
                            // transaction the reversal actually wrote in, and the one holding the
                            // advisory key the waiter below is parked on.
                            postings.execute("An abandoned reversal") {
                                reversals.reverse(
                                    fx.command(tenant, original.journalEntryId, reason = "Aborted"),
                                )
                                reversalApplied.countDown()
                                assertTrue(
                                    releaseRollback.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS),
                                )
                                abandonTransaction()
                            }
                        }
                    }
                assertTrue(reversalApplied.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))

                val waiter =
                    executor.submit<PostingReceipt> {
                        fx
                            .inContext(tenant, tenant.checker) {
                                postingService.reverse(
                                    fx.command(tenant, original.journalEntryId, reason = "Retry"),
                                )
                            }.also { waiterReturned.set(true) }
                    }

                probe.awaitBlockedOnAdvisoryKey(
                    AdvisoryLockNamespace.ACCOUNTING_JOURNAL_REVERSAL,
                    reversalKey,
                )
                assertFalse(
                    waiterReturned.get(),
                    "the second reverser returned before the first rolled back, so it never " +
                        "waited on the abandoned attempt this scenario is about",
                )

                releaseRollback.countDown()
                abandoned.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                waiter.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            }

        assertEquals(
            1,
            fx.reversalsOf(tenant, original.journalEntryId),
            "the abandoned attempt left nothing behind and the waiter wrote the one reversal",
        )
        assertEquals(
            original.journalEntryId,
            assertNotNull(journals.findJournalEntry(tenant.organisationId, winner.journalEntryId))
                .reversesJournalEntryId,
            "the surviving reversal is the waiter's, and it reverses the original",
        )
    }

    @Test
    fun `the actor who posted a journal cannot reverse it`() {
        val tenant = fx.provisionTenant("reversal-self")
        val original = fx.postOriginal(tenant)
        fx.grantDirectly(
            tenant.organisationId,
            JournalReversalFixture.MAKER,
            AccountingPermissions.JOURNAL_REVERSE,
        )

        val failure =
            assertFailsWith<ForbiddenOperationException> {
                fx.inContext(tenant, JournalReversalFixture.MAKER) {
                    postingService.reverse(
                        fx.command(tenant, original.journalEntryId, reason = "Oops"),
                    )
                }
            }

        assertEquals(PostingErrorCodes.JOURNAL_SELF_REVERSAL, failure.code)
        assertEquals(0, fx.reversalsOf(tenant, original.journalEntryId))
    }

    @Test
    fun `a reversal cannot be reversed, and a reason and the permission are required`() {
        val tenant = fx.provisionTenant("reversal-controls")
        val original = fx.postOriginal(tenant)
        val reversal =
            fx.inContext(tenant, tenant.checker) {
                postingService.reverse(
                    fx.command(tenant, original.journalEntryId, reason = "First"),
                )
            }

        val reversalOfReversal =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, tenant.checker) {
                    postingService.reverse(
                        fx.command(tenant, reversal.journalEntryId, reason = "Undo"),
                    )
                }
            }
        assertEquals(PostingErrorCodes.REVERSAL_NOT_REVERSIBLE, reversalOfReversal.code)

        val another = fx.postOriginal(tenant, reference = "dep-2")
        listOf("", "   ").forEach { reason ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    fx.inContext(tenant, tenant.checker) {
                        postingService.reverse(
                            fx.command(tenant, another.journalEntryId, reason = reason),
                        )
                    }
                }
            assertEquals(PostingErrorCodes.REVERSAL_REASON_REQUIRED, failure.code)
        }

        assertFailsWith<ForbiddenOperationException> {
            fx.inContext(tenant, JournalReversalFixture.STRANGER) {
                postingService.reverse(
                    fx.command(tenant, another.journalEntryId, reason = "No rights"),
                )
            }
        }
        assertEquals(0, fx.reversalsOf(tenant, another.journalEntryId))
    }

    @Test
    fun `a closed period refuses a reversal and a backdated one needs break-glass authority`() {
        val tenant = fx.provisionTenant("reversal-period")
        val original = fx.postOriginal(tenant)

        fx.setPeriodStatus(tenant, "CLOSED")
        val closed =
            assertFailsWith<ConflictException> {
                fx.inContext(tenant, tenant.checker) {
                    postingService.reverse(
                        fx.command(tenant, original.journalEntryId, reason = "Late"),
                    )
                }
            }
        assertEquals(PostingErrorCodes.PERIOD_CLOSED, closed.code)
        fx.setPeriodStatus(tenant, "OPEN")

        // Backdating the reversal by a day inside the open period needs journal.post_prior_period,
        // which the checker does not hold - the engine's period resolver enforces it unchanged.
        val backdated =
            assertFailsWith<ForbiddenOperationException> {
                fx.inContext(tenant, tenant.checker) {
                    postingService.reverse(
                        fx.command(
                            tenant,
                            original.journalEntryId,
                            reason = "Backdated",
                            dates =
                                PostingDateRequest(
                                    postingDate = tenant.businessDate.minusDays(1),
                                ),
                        ),
                    )
                }
            }
        assertNotNull(backdated)
        assertEquals(0, fx.reversalsOf(tenant, original.journalEntryId))
    }

    @Test
    fun `a refused reversal leaves nothing behind`() {
        val tenant = fx.provisionTenant("reversal-atomic")
        val original = fx.postOriginal(tenant)
        val harness =
            FinancialTransactionAtomicityFixture(
                dsl,
                transactionManager,
                listOf(
                    FoundationAtomicityProbes.postingRequestRows(tenant.organisationId),
                    FoundationAtomicityProbes.journalEntryRows(tenant.organisationId),
                    FoundationAtomicityProbes.journalLineRows(tenant.organisationId),
                    FoundationAtomicityProbes.journalSequenceValue(tenant.organisationId),
                    FoundationAtomicityProbes.auditEventRows(
                        tenant.organisationId,
                        "journal.reverse",
                        "SUCCESS",
                    ),
                ),
            )

        // The harness's own `TransactionTemplate` can no longer carry this. `PostingService`'s
        // `reverse` opens the boundary's `SERIALIZABLE` transaction and refuses to run inside a
        // caller's, so wrapping it in `assertRollsBackAtomically(IllegalStateException::class)`
        // would have gone green on the boundary's `check` firing before a reversal existed at all -
        // a probe reading "unchanged" because nothing was ever attempted. The failure is injected
        // where it belongs instead: inside the transaction the boundary owns, beside
        // `JournalReversalService.reverse`, which is `MANDATORY` and joins it. The message is
        // asserted for the same reason, so this can never again pass on a refusal to start.
        val before = harness.snapshot()
        val failure =
            assertFailsWith<IllegalStateException> {
                fx.inContext(tenant, tenant.checker) {
                    postings.execute<Unit>("A reversal that fails after it is written") {
                        reversals.reverse(
                            fx.command(tenant, original.journalEntryId, reason = "Then fail"),
                        )
                        error(SIMULATED_POST_REVERSAL_FAILURE)
                    }
                }
            }
        assertEquals(
            SIMULATED_POST_REVERSAL_FAILURE,
            failure.message,
            "the rollback must be the injected failure's, never the boundary refusing to nest",
        )
        assertEquals(
            before,
            harness.snapshot(),
            "every durable effect of the reversal rolled back with the transaction that wrote it",
        )
    }

    @Test
    fun `a correction is a reversal followed by a fresh posting that names what it corrects`() {
        val tenant = fx.provisionTenant("reversal-correction")
        val original = fx.postOriginal(tenant, debit = "100.00")
        fx.inContext(tenant, tenant.checker) {
            postingService.reverse(
                fx.command(tenant, original.journalEntryId, reason = "Wrong amount"),
            )
        }

        val replacement =
            fx.inContext(tenant, JournalReversalFixture.MAKER) {
                postings.execute("Posting a correction") {
                    fx.postExplicit(
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
                .where(POSTING_REQUEST.ID.eq(replacement.postingRequestId))
                .fetchOne(POSTING_REQUEST.CORRECTS_POSTING_REQUEST_ID),
        )
        assertEquals(
            listOf("STANDARD", "REVERSAL", "STANDARD"),
            dsl
                .select(JOURNAL_ENTRY.ENTRY_TYPE)
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
                .orderBy(JOURNAL_ENTRY.ENTRY_NUMBER)
                .fetch(JOURNAL_ENTRY.ENTRY_TYPE),
            "three ordinary immutable journals, numbered without a gap",
        )
    }

    @Test
    fun `a reversal is booked to the original's branch, and only from that branch`() {
        val tenant = fx.provisionTenant("reversal-branch")
        val original = fx.postOriginal(tenant)

        // A checker who has not selected the original's branch is refused, rather than having the
        // reversal silently re-attributed to whatever branch they are in.
        val mismatch =
            assertFailsWith<ForbiddenOperationException> {
                fx.atTenantLevel(tenant, tenant.checker) {
                    postingService.reverse(
                        ReversePostingCommand(
                            context =
                                AccountingContext(tenant.organisationId, null, tenant.checker),
                            originalJournalEntryId = original.journalEntryId,
                            reason = "From head office",
                            dates = PostingDateRequest(),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.REVERSAL_BRANCH_MISMATCH, mismatch.code)
        assertEquals(0, fx.reversalsOf(tenant, original.journalEntryId))

        val reversal =
            fx.inContext(tenant, tenant.checker) {
                postingService.reverse(
                    fx.command(tenant, original.journalEntryId, reason = "Wrong branch"),
                )
            }

        assertEquals(
            listOf(tenant.branchId, tenant.branchId),
            dsl
                .select(JOURNAL_ENTRY.BRANCH_ID)
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))
                .orderBy(JOURNAL_ENTRY.ENTRY_NUMBER)
                .fetch(JOURNAL_ENTRY.BRANCH_ID),
            "the reversal lands in the ledger the original moved",
        )
        assertEquals(
            listOf(tenant.branchId, tenant.branchId, tenant.branchId, tenant.branchId),
            dsl
                .select(JOURNAL_LINE.BRANCH_ID)
                .from(JOURNAL_LINE)
                .where(JOURNAL_LINE.ORGANISATION_ID.eq(tenant.organisationId))
                .fetch(JOURNAL_LINE.BRANCH_ID),
            "and so does every mirrored line",
        )
        assertNotNull(reversal.journalEntryId)
    }

    @Test
    fun `a mismatched context is refused before the journal is read, and the reason is bounded`() {
        val tenant = fx.provisionTenant("reversal-context")
        val other = fx.provisionTenant("reversal-context-other")
        val original = fx.postOriginal(tenant)

        // Claiming another tenant must not be answerable from the difference between
        // "no such journal" and "already reversed": the context is reconciled first.
        val crossTenant =
            assertFailsWith<ForbiddenOperationException> {
                fx.inContext(tenant, tenant.checker) {
                    postingService.reverse(
                        ReversePostingCommand(
                            context =
                                AccountingContext(
                                    other.organisationId,
                                    other.branchId,
                                    tenant.checker,
                                ),
                            originalJournalEntryId = original.journalEntryId,
                            reason = "Probing",
                            dates = PostingDateRequest(),
                        ),
                    )
                }
            }
        assertEquals(PostingErrorCodes.CONTEXT_MISMATCH, crossTenant.code)

        val tooLong =
            assertFailsWith<InvalidOperationException> {
                fx.inContext(tenant, tenant.checker) {
                    postingService.reverse(
                        fx.command(tenant, original.journalEntryId, reason = "x".repeat(501)),
                    )
                }
            }
        assertEquals(PostingErrorCodes.REVERSAL_REASON_TOO_LONG, tooLong.code)
        assertEquals(0, fx.reversalsOf(tenant, original.journalEntryId))
    }

    private companion object {
        const val LATCH_TIMEOUT_SECONDS = 10L
        const val FUTURE_TIMEOUT_SECONDS = 60L

        /**
         * The injected failure's message, asserted so the atomicity scenario cannot go green on
         * `PostingTransactionBoundary`'s own `IllegalStateException` instead.
         */
        const val SIMULATED_POST_REVERSAL_FAILURE =
            "simulated failure after the reversal and its audit were written"
    }
}
