package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.AccountingBusinessDateLookup
import com.finaxis.platform.accounting.application.ledger.PostingEngine
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.PostingDatePolicy
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.support.BreakGlassGrantFixture
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.accounting.support.PostingTenantFixture
import com.finaxis.platform.accounting.support.PostingTenantFixture.Tenant
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.jooq.tables.references.BUSINESS_DATE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import com.finaxis.platform.jooq.tables.references.ORGANISATION
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The close-of-business gate against a posting whose snapshot predates the close.
 *
 * `accounting.business_date_not_open` exists to stop a current-dated journal landing on a day
 * whose close-of-business has started. Until issue #125 it could not do that, and raising the
 * posting path to `SERIALIZABLE` widened the hole rather than opening it:
 *
 * - `PostingEngine.post` pins the transaction's snapshot at its **second** statement, the isolation
 *   guard's `current_setting` read. Under `PostingTransactions.execute { … }` it is pinned
 *   earlier still, by the product module's own first statement.
 * - `PostingPeriodResolver.resolveDates` then read `business_date` with a plain `SELECT`, from that
 *   pinned snapshot, and nothing locked the row or re-read it before the journal committed.
 * - A `startCob` committing in between is therefore invisible: the gate sees `OPEN`,
 *   `postingAllowed` is true, and the journal commits into a closing day.
 *
 * At `READ COMMITTED` the read was at least fresh *at read time*, so the window was
 * read-to-commit rather than snapshot-pin-to-commit. It was never correct; the raise made it wider.
 * There is nothing for SSI to catch either way - the posting reads the row and `startCob` writes
 * it, which is one rw-dependency edge and no cycle - and the #124 retry cannot help, because there
 * is no serialization failure to retry.
 *
 * The repair is the shape already proven for the fiscal period and the functional currency: a
 * **locking** read, `AccountingBusinessDateLookup.currentBusinessDateForPosting`, taken after the
 * idempotency claim so a replay still locks nothing. The three tests below are the three things
 * that has to mean, and the second is the one that catches a lock deleted on the theory that
 * `SERIALIZABLE` covers it.
 *
 * See `docs/adr/0026-real-time-gates-on-the-serializable-posting-path.md`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class BusinessDateCloseRaceIntegrationTests(
    private val postingTransactions: PostingTransactionBoundary,
    private val dsl: DSLContext,
    private val businessDates: AccountingBusinessDateLookup,
    private val transactionManager: PlatformTransactionManager,
    engine: PostingEngine,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val posting =
        PostingTenantFixture(dsl, organisationProvisioningService, engine, ACTOR)

    private val grants = BreakGlassGrantFixture(dsl)

    private val probe = LockOverlapProbe(dsl)

    /**
     * The regression this change exists for.
     *
     * The interleaving is forced rather than hoped for. The posting's transaction is opened at
     * `SERIALIZABLE` through the production boundary and its snapshot is pinned by a read of the
     * tenant's `organisation` row - which is what happens in production, where the caller's own
     * source mutation runs in this same transaction before the posting. Only then, from a second
     * thread and therefore a second connection, does the close commit; the posting resumes
     * afterwards and would otherwise read a day its snapshot still shows as open.
     *
     * The close is written straight to `business_date`, and that is deliberate rather than lazy. It
     * is the narrowest possible statement of *"another transaction committed a close"*, with no
     * permission checks, history rows or events in between to explain a pass or a failure. It runs
     * at `READ COMMITTED`, as `BusinessDateService` does, which matters: a `SERIALIZABLE` writer
     * would let SSI abort the posting on its own and the test would pass with or without the
     * locking read. It commits exactly once however many times the boundary retries the posting,
     * because a second close would move the row again and change what the retry is racing.
     *
     * What is asserted is the ledger first. Without the locking read the posting commits a journal
     * into a closing day and `committedJournals` is what catches it; the exception assertion alone
     * would not, because without the read there is no exception at all.
     */
    @Test
    fun `a current-dated posting cannot commit into a day whose close has started`() {
        val tenant = posting.provisionTenant("engine-cob-window")
        val closed = AtomicBoolean()

        val outcome =
            Executors.newVirtualThreadPerTaskExecutor().use { executor ->
                runCatching {
                    posting.inContext(tenant) {
                        postingTransactions.execute("A current-dated posting") {
                            pinSnapshot(tenant)
                            if (closed.compareAndSet(false, true)) {
                                executor
                                    .submit<Unit> { startCob(tenant) }
                                    .get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                            }
                            posting.post(tenant, reference = "dep-cob-window")
                        }
                    }
                }
            }

        assertEquals(CLOSING, businessDateStatus(tenant), "the close must have committed")
        assertEquals(
            0,
            committedJournals(tenant),
            "a journal committed into a day whose close-of-business had already started: the " +
                "business-date gate was decided from the posting's pinned snapshot",
        )
        val failure = outcome.exceptionOrNull()
        assertNotNull(failure, "the posting neither aborted nor was refused")
        assertTrue(
            isAbortedOrRefused(failure),
            "the posting failed, but not as a serialization failure or a closed-day refusal: " +
                "${failure::class.qualifiedName}: ${failure.message}",
        )
    }

    /**
     * The other acquisition order, which is what makes the lock a lock rather than a detector.
     *
     * A posting is held open past the point where it has taken `business_date` `FOR SHARE`. A close
     * is then started and proved - out of PostgreSQL's own catalogues, not from thread timing - to
     * be parked on that row behind this exact backend. Only when the posting commits does the close
     * proceed.
     *
     * Both orders are asserted because each passes without the other's guarantee. The first test
     * passes if the posting merely aborts; this one fails unless a concurrent close genuinely
     * *waits*, which is the half that keeps `startCob` from sailing past postings already in
     * flight - and the half that made `BusinessDateService` a waiter, and therefore made
     * `BusinessDateProperties` necessary.
     */
    @Test
    fun `a close waits for the postings already in flight`() {
        val tenant = posting.provisionTenant("engine-cob-wait")
        val postingApplied = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val holder = AtomicInteger()
        val closeReturned = AtomicBoolean()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val first =
                executor.submit {
                    posting.inContext(tenant) {
                        postingTransactions.execute("A posting the close must wait for") {
                            holder.set(probe.currentBackendPid())
                            posting.post(tenant, reference = "dep-cob-wait")
                            postingApplied.countDown()
                            assertTrue(releasePosting.await(LATCH_TIMEOUT, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(postingApplied.await(LATCH_TIMEOUT, TimeUnit.SECONDS))

            val close =
                executor.submit { startCob(tenant).also { closeReturned.set(true) } }

            probe.awaitBlockedOnRelationBehind(holder.get(), "business_date")
            assertTrue(
                !closeReturned.get(),
                "the close resolved while the posting was still uncommitted, so it never waited " +
                    "and a close can still start under postings already in flight",
            )

            releasePosting.countDown()
            first.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
            close.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
        }

        assertEquals(CLOSING, businessDateStatus(tenant))
        assertEquals(1, committedJournals(tenant), "the posting that held the lock must commit")
    }

    /**
     * The deliberate exception, and a positive control on the two tests above.
     *
     * Close-of-business must not deadlock corrections: a backdated posting into a still-open prior
     * period stays legal while the current day is closing. Both tests above would pass for a
     * locking read that refused *everything* once the day left `OPEN`, which would be a different
     * defect with the same green build. The business date is moved to its month end first so the
     * backdated day is still inside the period the fixture opened, whatever today's date is, and
     * the actor is granted `journal.post_prior_period` because no default bundle carries it -
     * without the grant this would be refused by the break-glass gate rather than admitted by the
     * business-date one, and would prove the opposite of what it claims.
     */
    @Test
    fun `a backdated posting is still admitted while the day is closing`() {
        val provisioned = posting.provisionTenant("engine-cob-backdated")
        val monthEnd = posting.moveBusinessDateToMonthEnd(provisioned)
        val tenant = provisioned.copy(businessDate = monthEnd)
        grants.grantBreakGlass(tenant.organisationId, ACTOR)
        startCob(tenant)

        posting.inContext(tenant) {
            postingTransactions.execute("A backdated correction during close-of-business") {
                posting.post(
                    tenant,
                    reference = "dep-cob-backdated",
                    dates = PostingDateRequest(postingDate = tenant.businessDate.minusDays(1)),
                )
            }
        }

        assertEquals(CLOSING, businessDateStatus(tenant))
        assertEquals(1, committedJournals(tenant))
    }

    /**
     * The defect and its repair, measured side by side in one transaction.
     *
     * The three tests above assert the *outcome*. This one asserts the **mechanism**, which is what
     * makes them more than a pair of green runs: inside a single `SERIALIZABLE` transaction whose
     * snapshot predates a committed close, the plain read the gate used to be decided from still
     * reports the day open, and the locking read raises `40001` rather than answering. Delete the
     * `FOR SHARE` and the first assertion is exactly what the posting path would then do.
     *
     * Deliberately driven from a bare `TransactionTemplate` rather than through
     * `PostingTransactionBoundary`, because the boundary retries: a second attempt takes a fresh
     * snapshot, the plain read is no longer stale, and the very thing being measured disappears.
     * Both reads are recorded and asserted after the transaction has unwound, since a transaction
     * that has taken `40001` cannot issue another statement.
     */
    @Test
    fun `the plain read is stale exactly where the locking read refuses to answer`() {
        val tenant = posting.provisionTenant("engine-cob-mechanism")
        val template =
            TransactionTemplate(transactionManager).apply {
                isolationLevel = TransactionDefinition.ISOLATION_SERIALIZABLE
            }
        var plainStillOpen: Boolean? = null
        var lockingFailure: Throwable? = null

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            runCatching {
                template.execute {
                    pinSnapshot(tenant)
                    executor.submit<Unit> { startCob(tenant) }.get(FUTURE_TIMEOUT, TimeUnit.SECONDS)
                    plainStillOpen =
                        businessDates.currentBusinessDate(tenant.organisationId)?.postingAllowed
                    lockingFailure =
                        runCatching {
                            businessDates.currentBusinessDateForPosting(tenant.organisationId)
                        }.exceptionOrNull()
                    null
                }
            }
        }

        assertEquals(
            true,
            plainStillOpen,
            "the plain read stopped being stale, so this scenario no longer reproduces the " +
                "condition the locking read exists for and proves nothing about it",
        )
        val failure = assertNotNull(lockingFailure, "the locking read answered from a stale row")
        assertTrue(
            isAbortedOrRefused(failure),
            "the locking read failed, but not as a serialization failure: " +
                "${failure::class.qualifiedName}: ${failure.message}",
        )
    }

    /**
     * Fixes the transaction's snapshot before the concurrent close commits.
     *
     * A real posting's transaction has already read by the time it reaches the business date - the
     * caller's own source mutation, and the engine's isolation guard - so a snapshot pinned here is
     * the production shape rather than a contrivance. The `organisation` row is read because
     * nothing in this test writes it, so pinning cannot itself create the conflict under test.
     */
    private fun pinSnapshot(tenant: Tenant) {
        dsl
            .select(ORGANISATION.BASE_CURRENCY_CODE)
            .from(ORGANISATION)
            .where(ORGANISATION.ID.eq(tenant.organisationId))
            .fetchOne()
    }

    /** Commits `OPEN -> CLOSING` from another connection, at the default isolation level. */
    private fun startCob(tenant: Tenant) {
        val updated =
            dsl
                .update(BUSINESS_DATE)
                .set(BUSINESS_DATE.STATUS, CLOSING)
                .set(BUSINESS_DATE.CURRENT_COB_DATE, BUSINESS_DATE.CURRENT_BUSINESS_DATE)
                .set(BUSINESS_DATE.ROW_VERSION, BUSINESS_DATE.ROW_VERSION.plus(1))
                .where(BUSINESS_DATE.ORGANISATION_ID.eq(tenant.organisationId))
                .execute()
        assertEquals(1, updated, "the close must have hit exactly one business-date row")
    }

    private fun businessDateStatus(tenant: Tenant): String? =
        dsl
            .select(BUSINESS_DATE.STATUS)
            .from(BUSINESS_DATE)
            .where(BUSINESS_DATE.ORGANISATION_ID.eq(tenant.organisationId))
            .fetchOne(BUSINESS_DATE.STATUS)

    private fun committedJournals(tenant: Tenant): Int =
        dsl
            .fetchCount(JOURNAL_ENTRY, JOURNAL_ENTRY.ORGANISATION_ID.eq(tenant.organisationId))

    /**
     * Both outcomes the design admits, and nothing else.
     *
     * The refusal is what the retry produces: the aborted attempt rolls back, the next one opens a
     * new transaction with a fresh snapshot, reads `CLOSING`, and `PostingDatePolicy` answers
     * `accounting.business_date_not_open` before the claim. The raw serialization failure is what a
     * caller sees when the budget is spent instead. The raw `SQLSTATE` is checked as well as
     * Spring's translated type, because a failure raised by `COMMIT` is thrown by the transaction
     * interceptor rather than by a statement and does not always arrive already translated.
     */
    private fun isAbortedOrRefused(failure: Throwable): Boolean =
        failure is ConcurrencyFailureException ||
            (
                failure is ConflictException &&
                    failure.code in
                    setOf(
                        PostingDatePolicy.BUSINESS_DATE_NOT_OPEN,
                        PostingErrorCodes.POSTING_RETRIES_EXHAUSTED,
                    )
            ) ||
            generateSequence(failure) { it.cause.takeIf { cause -> cause !== it } }
                .any { it is SQLException && it.sqlState == SERIALIZATION_FAILURE }

    private companion object {
        /** The `V3` bootstrap administrator; `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        const val LATCH_TIMEOUT = 10L
        const val FUTURE_TIMEOUT = 60L
        const val CLOSING = "CLOSING"

        /** `serialization_failure`; both of its messages map to `ConcurrencyFailureException`. */
        const val SERIALIZATION_FAILURE = "40001"
    }
}
