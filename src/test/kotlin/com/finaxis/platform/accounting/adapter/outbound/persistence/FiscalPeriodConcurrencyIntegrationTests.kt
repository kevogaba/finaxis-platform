package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.FiscalPeriodKey
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.FiscalPeriodStatus
import com.finaxis.platform.common.id.uuidV7
import com.finaxis.platform.jooq.tables.references.ORGANISATION_SETTING
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The posting-versus-close race, proven against real PostgreSQL.
 *
 * Every scenario is latch-driven with generous absolute timeouts and *relative* ordering
 * assertions; none uses a wall-clock sleep to create the interleaving, because a sleep-based race
 * test passes or fails on machine speed rather than on the property under test.
 *
 * The guarantee: a posting either holds a shared lock on an OPEN period for the rest of its
 * transaction, or it fails. Never both, and never a journal committed into a period the same
 * transaction observed as closed.
 *
 * See `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FiscalPeriodConcurrencyIntegrationTests(
    private val applicationContext: org.springframework.context.ApplicationContext,
    private val dsl: DSLContext,
    private val rowLock: PostgresRowLock,
    organisationProvisioningService: OrganisationProvisioningService,
    transactionManager: PlatformTransactionManager,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val transactions = TransactionTemplate(transactionManager)
    private val periods = FiscalPeriodStandIn(dsl, rowLock)

    @Test
    fun `S1 a posting in flight delays a close until it commits`() {
        val key = openPeriod("s1")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val closeAcquiredAt = AtomicLong()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    transactions.execute {
                        periods.lockForPosting(key)
                        periods.recordJournal(key)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val close =
                executor.submit {
                    transactions.execute {
                        periods.lockForStateChange(key)
                        closeAcquiredAt.set(System.nanoTime())
                        periods.updateStatus(key, FiscalPeriodStatus.CLOSED)
                    }
                }
            // The barrier that gives this test its teeth. Without it the release below fires
            // nanoseconds after submit(), so the assertion would hold on scheduling delay whether
            // or not any lock was taken - the test would pass with FOR SHARE deleted. Waiting
            // until PostgreSQL reports a backend actually blocked means a missing lock times out
            // here instead of passing.
            awaitBlockedOnLock()

            // Asserted while the posting still holds its lock. Comparing timestamps across the two
            // threads afterwards cannot work: PostgreSQL releases the lock AT COMMIT, so the close
            // wakes and stamps its time while the posting thread has yet to be rescheduled to
            // stamp its own. This is the property that assertion was reaching for anyway.
            assertEquals(
                0L,
                closeAcquiredAt.get(),
                "the close acquired its exclusive lock while a posting still held a shared one",
            )

            releasePosting.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertTrue(closeAcquiredAt.get() > 0L, "the close must proceed once the posting commits")
        assertEquals(1, periods.journalCount(key.organisationId))
    }

    @Test
    fun `S2 a close committed mid-flight is observed by the posting under its lock`() {
        // The critical scenario, and the only one that exercises the isolation dependency: both
        // reads happen inside ONE posting transaction, with a close committing between them.
        //
        // Structuring it as three sequential transactions - as an earlier revision of this test
        // did - proves nothing. A fresh transaction takes a fresh snapshot for trivial reasons,
        // so it would pass with both locks deleted and at any isolation level. The race is only
        // real when the second read is made by a transaction that already read the row.
        val key = openPeriod("s2")
        val beforeLock = AtomicReference<FiscalPeriodStatus>()
        val underLock = AtomicReference<FiscalPeriodStatus>()
        val postingRead = CountDownLatch(1)
        val closeCommitted = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    transactions.execute {
                        beforeLock.set(
                            requireNotNull(periods.findCovering(key.organisationId, PERIOD_DAY))
                                .status,
                        )
                        postingRead.countDown()
                        assertTrue(closeCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        // Same transaction, after a close has committed elsewhere. Under READ
                        // COMMITTED this statement takes a new snapshot and sees CLOSED; under
                        // REPEATABLE READ it would still see OPEN and the protocol would be wrong.
                        underLock.set(requireNotNull(periods.lockForPosting(key)).status)
                    }
                }

            assertTrue(postingRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            transactions.execute { periods.updateStatus(key, FiscalPeriodStatus.CLOSED) }
            closeCommitted.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(FiscalPeriodStatus.OPEN, beforeLock.get(), "the unlocked read saw it open")
        assertEquals(
            FiscalPeriodStatus.CLOSED,
            underLock.get(),
            "the locking read must observe the committed close, not the transaction's earlier read",
        )
        assertEquals(0, periods.journalCount(key.organisationId), "no journal may have committed")
    }

    @Test
    fun `S3 two postings hold the shared lock at the same time`() {
        val key = openPeriod("s3")
        val bothLocked = CountDownLatch(2)
        val release = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures =
                (1..2).map {
                    executor.submit {
                        transactions.execute {
                            periods.lockForPosting(key)
                            bothLocked.countDown()
                            assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                        }
                    }
                }
            assertTrue(
                bothLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS),
                "both postings must hold the shared lock concurrently - if this times out, " +
                    "FOR SHARE has turned the ledger into a queue",
            )
            release.countDown()
            futures.forEach { it.get(TIMEOUT_SECONDS, TimeUnit.SECONDS) }
        }
    }

    @Test
    fun `S4 concurrent closes serialize and the second sees the first`() {
        val key = openPeriod("s4")
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondObserved = AtomicReference<FiscalPeriodStatus>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val first =
                executor.submit {
                    transactions.execute {
                        periods.lockForStateChange(key)
                        periods.updateStatus(key, FiscalPeriodStatus.CLOSED)
                        firstLocked.countDown()
                        assertTrue(releaseFirst.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(firstLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val second =
                executor.submit {
                    transactions.execute {
                        secondObserved.set(requireNotNull(periods.lockForStateChange(key)).status)
                    }
                }
            awaitBlockedOnLock()
            releaseFirst.countDown()
            first.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            second.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(
            FiscalPeriodStatus.CLOSED,
            secondObserved.get(),
            "the second close must observe the first, so only one transition can succeed",
        )
    }

    @Test
    fun `S5 a bare UPDATE still blocks behind a posting's shared lock`() {
        // Proves the FOR SHARE choice: FOR KEY SHARE would not conflict with the FOR NO KEY UPDATE
        // that a plain UPDATE takes, so a close path that forgot its explicit lock would slip past.
        val key = openPeriod("s5")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val updateCompletedAt = AtomicLong()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    transactions.execute {
                        periods.lockForPosting(key)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val update =
                executor.submit {
                    transactions.execute {
                        periods.updateStatusWithoutLocking(key, FiscalPeriodStatus.CLOSED)
                        updateCompletedAt.set(System.nanoTime())
                    }
                }
            awaitBlockedOnLock()

            // Checked while the shared lock is still held. FOR KEY SHARE would not conflict with
            // the FOR NO KEY UPDATE this plain UPDATE takes, so it would have completed by now.
            assertEquals(
                0L,
                updateCompletedAt.get(),
                "a bare UPDATE completed while a posting held its FOR SHARE lock, so the lock " +
                    "mode does not conflict with FOR NO KEY UPDATE",
            )

            releasePosting.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            update.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertTrue(updateCompletedAt.get() > 0L, "the UPDATE must proceed once the lock is gone")
    }

    @Test
    fun `S6 a reopen after a rejected posting resurrects nothing`() {
        val key = openPeriod("s6")
        transactions.execute { periods.updateStatus(key, FiscalPeriodStatus.CLOSED) }

        transactions.execute {
            assertEquals(
                FiscalPeriodStatus.CLOSED,
                requireNotNull(periods.lockForPosting(key)).status,
            )
        }
        assertEquals(0, periods.journalCount(key.organisationId))

        transactions.execute { periods.updateStatus(key, FiscalPeriodStatus.OPEN) }
        transactions.execute {
            assertEquals(
                FiscalPeriodStatus.OPEN,
                requireNotNull(periods.lockForPosting(key)).status,
            )
            periods.recordJournal(key)
        }

        assertEquals(
            1,
            periods.journalCount(key.organisationId),
            "only the posting made after the reopen may exist",
        )
    }

    @Test
    fun `S7 the business date is not a posting serialization point`() {
        // A posting holds its period lock; an unrelated write to the same organisation must not
        // block behind it, because the period lock is scoped to the period row alone.
        val key = openPeriod("s7")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val unrelatedCompleted = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    transactions.execute {
                        periods.lockForPosting(key)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val unrelated =
                executor.submit {
                    transactions.execute { periods.createPeriod(key.organisationId, OPEN_STATUS) }
                    unrelatedCompleted.countDown()
                }

            assertTrue(
                unrelatedCompleted.await(UNBLOCKED_SECONDS, TimeUnit.SECONDS),
                "an unrelated write must not block behind a held period lock",
            )
            releasePosting.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            unrelated.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }
    }

    @Test
    fun `S8 taking a period lock outside a transaction fails loudly`() {
        val key = openPeriod("s8")

        assertFailsWith<IllegalStateException> {
            rowLock.lockForShare(
                ORGANISATION_SETTING,
                ORGANISATION_SETTING.ID,
                ORGANISATION_SETTING.ORGANISATION_ID,
                key.fiscalPeriodId,
                key.organisationId,
            )
        }
    }

    @Test
    fun `a period cannot be locked or read through another tenant's key`() {
        // The lock and the re-read both carry the tenant predicate, so a caller naming another
        // tenant's period id gets nothing rather than a lock on that tenant's row. Without the
        // predicate this returns a snapshot - and the snapshot's organisation id would be the
        // caller's, so a downstream tenant check would pass on a row the caller does not own.
        val victim = openPeriod("tenant-victim")
        val attackerOrganisationId = fixture.createActiveOrganisation("tenant-attacker", ACTOR_ID)
        val forgedKey = victim.copy(organisationId = attackerOrganisationId)

        val locked = transactions.execute { periods.lockForStateChange(forgedKey) }

        assertEquals(null, locked, "another tenant's period must not be lockable")
        assertEquals(
            FiscalPeriodStatus.OPEN,
            requireNotNull(transactions.execute { periods.lockForPosting(victim) }).status,
            "the victim's period must be untouched",
        )
    }

    @Test
    fun `no fiscal period store bean exists yet`() {
        // Deliberate, and the reason PostingPeriodResolver and FiscalPeriodStateChangeGuard are
        // not @Service beans: their FiscalPeriodStateStore dependency has no adapter until issue
        // #36. Annotating them early broke application context startup platform-wide, which this
        // suite caught. Asserting the absence keeps that decision visible.
        assertTrue(
            applicationContext.getBeanNamesForType(FiscalPeriodStateStore::class.java).isEmpty(),
            "the store adapter arrives with accounting_fiscal_period in issue #36",
        )
    }

    @Test
    fun `the locking read runs at READ COMMITTED`() {
        // The lookup-lock-revalidate protocol is correct only at READ COMMITTED: under REPEATABLE
        // READ the transaction's second read would return its first snapshot, and S2's guarantee
        // would not hold.
        //
        // The isolation is read from inside a transaction that has actually taken the posting
        // lock, not from a bare TransactionTemplate. Asserting the server default separately would
        // stay green if the posting path were later annotated with an explicit isolation level,
        // which is precisely the change that would break the protocol.
        val key = openPeriod("isolation")
        val isolation =
            requireNotNull(
                transactions.execute {
                    periods.lockForPosting(key)
                    dsl.fetchValue("SHOW transaction_isolation")
                },
            )

        assertEquals("read committed", isolation.toString())
    }

    /**
     * Blocks until PostgreSQL reports another backend waiting on a lock.
     *
     * This is what makes the ordering scenarios discriminating. Releasing the lock holder
     * immediately after submitting the contending transaction lets the assertion pass on thread
     * scheduling alone - the contender simply has not issued its statement yet - so the test would
     * stay green with the production lock deleted. Waiting for a real `Lock` wait event means a
     * missing lock produces a timeout here instead of a false pass.
     *
     * Polls rather than sleeps a fixed interval: the loop ends as soon as the wait is observable.
     */
    private fun awaitBlockedOnLock() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS)
        while (System.nanoTime() < deadline) {
            val waiting =
                dsl.fetchValue(
                    "select count(*) from pg_stat_activity " +
                        "where datname = current_database() " +
                        "and wait_event_type = 'Lock' and pid <> pg_backend_pid()",
                )
            if ((waiting as? Number)?.toLong()?.let { it > 0L } == true) {
                return
            }
            Thread.onSpinWait()
        }
        throw AssertionError(
            "no backend ever blocked on a lock: the contending statement was never obstructed, " +
                "so this scenario would pass without the production lock",
        )
    }

    private fun openPeriod(label: String): FiscalPeriodKey {
        val organisationId = fixture.createActiveOrganisation("period-$label", ACTOR_ID)
        return periods.createPeriod(organisationId, OPEN_STATUS)
    }

    private companion object {
        /** The V3 bootstrap administrator: `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OPEN_STATUS = FiscalPeriodStatus.OPEN
        val PERIOD_DAY: java.time.LocalDate = java.time.LocalDate.of(2026, 8, 15)
        const val TIMEOUT_SECONDS = 20L
        const val UNBLOCKED_SECONDS = 5L
    }
}
