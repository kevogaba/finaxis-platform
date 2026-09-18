package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.FiscalPeriodLifecycleService
import com.finaxis.platform.accounting.application.FiscalPeriodStateChangeCommand
import com.finaxis.platform.accounting.application.FiscalPeriodStateChangeGuard
import com.finaxis.platform.accounting.application.FiscalPeriodStateStore
import com.finaxis.platform.accounting.application.PostingPeriodResolver
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.domain.FiscalPeriodKey
import com.finaxis.platform.accounting.domain.FiscalPeriodStatus
import com.finaxis.platform.accounting.support.LockOverlapProbe
import com.finaxis.platform.common.persistence.TransactionLockTimeout
import com.finaxis.platform.jooq.tables.references.ACCOUNTING_FISCAL_PERIOD
import com.finaxis.platform.lifecycle.TenantAdminOrganisationFixture
import com.finaxis.platform.lifecycle.application.OrganisationProvisioningService
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.CannotSerializeTransactionException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import java.sql.SQLException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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
 * **The two sides run at different isolation levels, and that asymmetry is deliberate.** A posting
 * enters through [PostingTransactionBoundary], which opens the one `SERIALIZABLE` transaction the
 * whole accounting write path commits in; a close stays at READ COMMITTED, because
 * `FiscalPeriodLifecycleService` catches only `55P03` and a raised close would produce `40001`
 * instead. Scenarios that model the posting side therefore go through the boundary rather than
 * through this suite's bare [TransactionTemplate] — and never *inside* one, since the boundary
 * refuses an already-open transaction outright. Scenarios about the close, or about sequential
 * behaviour where isolation is not the property, stay on the bare template.
 *
 * The lock is what supplies the guarantee, not the isolation level. Measured: a `SERIALIZABLE`
 * posting that reads the period *without* `FOR SHARE` commits its journal into an already-closed
 * period, whether the close ran at READ COMMITTED or at `SERIALIZABLE`. Deleting `FOR SHARE` on the
 * strength of the isolation level reintroduces exactly the bug this suite exists to prevent.
 *
 * See `docs/adr/0022-accounting-date-and-fiscal-period-concurrency.md` as amended by
 * `docs/adr/0025-serializable-posting-and-the-covering-period-lock.md`.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class FiscalPeriodConcurrencyIntegrationTests(
    private val applicationContext: org.springframework.context.ApplicationContext,
    private val dsl: DSLContext,
    private val periods: FiscalPeriodStateStore,
    private val lockTimeout: TransactionLockTimeout,
    private val postingTransactions: PostingTransactionBoundary,
    private val transactionManager: PlatformTransactionManager,
    organisationProvisioningService: OrganisationProvisioningService,
) {
    private val fixture = TenantAdminOrganisationFixture(organisationProvisioningService, dsl)
    private val transactions = TransactionTemplate(transactionManager)
    private val probe = LockOverlapProbe(dsl)
    private val calendar = FiscalCalendarFixture(dsl)

    @Test
    fun `S1 a posting in flight delays a close until it commits`() {
        val key = openPeriod("s1")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val closeAcquiredAt = AtomicLong()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    // Through the boundary rather than the suite's template, because the boundary
                    // is how a posting reaches the database in production and it opens the
                    // transaction at SERIALIZABLE. A bare READ COMMITTED template here would prove
                    // the lock behaviour of a protocol nothing runs.
                    postingTransactions.execute("S1 posting") {
                        periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
                        assertIsolationInForce(SERIALIZABLE)
                        calendar.recordJournal(key)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val close =
                executor.submit {
                    // The close keeps the bare READ COMMITTED template: ADR 0025 decision 14 holds
                    // the close path at the server default, so this is what production does.
                    transactions.execute {
                        periods.lockForStateChange(key)
                        closeAcquiredAt.set(System.nanoTime())
                        periods.updateStatus(key, FiscalPeriodStatus.CLOSED, ACTOR_ID, null)
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
        assertEquals(1, calendar.journalCount(key.organisationId))
    }

    @ParameterizedTest(name = "at {0}")
    @ValueSource(strings = ["read committed", "repeatable read", "serializable"])
    fun `S2 a close committed mid-flight is never observed as OPEN, at any isolation level`(
        isolation: String,
    ) {
        // The critical scenario, and the only one that exercises the isolation dependency: both
        // reads happen inside ONE posting transaction, with a close committing between them.
        //
        // Structuring it as three sequential transactions - as an earlier revision of this test
        // did - proves nothing. A fresh transaction takes a fresh snapshot for trivial reasons,
        // so it would pass with both locks deleted and at any isolation level. The race is only
        // real when the second read is made by a transaction that already read the row.
        //
        // The property is lock-or-abort, and it is the same property at every level. At READ
        // COMMITTED the locking statement takes a fresh snapshot and EvalPlanQual re-reads the
        // locked row, so CLOSED comes back and the posting is rejected. Above READ COMMITTED there
        // is no re-read at all - measured on postgres:18.4, the statement raises 40001 - so the
        // posting is aborted instead and a retry re-runs it against a fresh snapshot. What is
        // never available, at any level, is a stale OPEN. This is asserted over all three levels
        // rather than at the one the posting path happens to run at, because a test pinned to one
        // mechanism would go red on an isolation change that the guarantee survives.
        //
        // The rows run SEQUENTIALLY - JUnit does not parallelise a @ParameterizedTest here - so
        // this scenario never holds more than two of HikariCP's default ten connections.
        val key = openPeriod("s2-${isolation.replace(' ', '-')}")
        val beforeLock = AtomicReference<FiscalPeriodStatus>()
        val underLock = AtomicReference<FiscalPeriodStatus>()
        val failure = AtomicReference<Throwable>()
        val postingRead = CountDownLatch(1)
        val closeCommitted = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    runCatching {
                        transactionsAt(isolation).execute {
                            // Read from inside the transaction, not inferred from the template's
                            // attribute. A row whose level silently degraded to the server default
                            // would otherwise pass for the wrong reason - which is exactly the
                            // false pass the isolation scenario below is also written to prevent.
                            assertIsolationInForce(isolation)
                            beforeLock.set(statusOfUnlockedRead(key))
                            postingRead.countDown()
                            assertTrue(closeCommitted.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            underLock.set(statusOfCoveringLock(key))
                        }
                    }.onFailure(failure::set)
                }

            assertTrue(postingRead.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
            // Latch-sequenced, not probe-sequenced. LockOverlapProbe cannot observe this race at
            // all above READ COMMITTED: the posting never blocks - its snapshot has already lost -
            // so awaitAnyBackendBlocked() would burn its deadline and blame a lock nobody takes.
            transactions.execute {
                periods.updateStatus(key, FiscalPeriodStatus.CLOSED, ACTOR_ID, null)
            }
            closeCommitted.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertEquals(FiscalPeriodStatus.OPEN, beforeLock.get(), "the unlocked read saw it open")
        // The union property, asserted at all three levels.
        assertTrue(
            underLock.get() != FiscalPeriodStatus.OPEN,
            "at $isolation the locking read handed back a stale OPEN for a period a committed " +
                "close had already closed",
        )
        assertEquals(0, calendar.journalCount(key.organisationId), "no journal may have committed")
        assertMechanismFor(isolation, underLock.get(), failure.get())
    }

    @Test
    fun `S3 two postings hold the shared lock at the same time`() {
        val key = openPeriod("s3")
        val bothLocked = CountDownLatch(2)
        val release = CountDownLatch(1)
        val failures = ConcurrentLinkedQueue<Throwable>()
        val rowVersionBefore = rowVersionOf(key)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val futures =
                (1..2).map {
                    executor.submit {
                        try {
                            // Both holders go through the boundary, so both run at SERIALIZABLE -
                            // the shape two concurrent postings actually take. Neither allocates a
                            // journal number: every posting in a tenant updates the one gapless
                            // reference_sequence row, and measured on postgres:18.4 the second of
                            // any overlapping pair aborts there with 40001. That abort belongs to
                            // the numbering row, not to the period lock, and importing it here
                            // would make this scenario prove the opposite of what it claims.
                            postingTransactions.execute("S3 posting $it") {
                                periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
                                bothLocked.countDown()
                                assertTrue(release.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                            }
                        } catch (failure: Throwable) {
                            // Counted down on the failure path too, so a holder that aborted fails
                            // this scenario on the explicit 40001 assertion below rather than on a
                            // latch deadline that would blame FOR SHARE for a serialization
                            // failure. A second count-down on an already-open latch is harmless.
                            failures += failure
                            bothLocked.countDown()
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

        // The period lock must not itself be a source of serialization failures. Two readers of
        // one row under FOR SHARE conflict with nothing, at any level; if this ever goes red the
        // raise has made concurrent postings into a tenant abort each other on the calendar.
        assertTrue(
            failures.none { it is ConcurrencyFailureException },
            "neither posting may fail to serialize on the period lock alone, but saw " +
                "${failures.map { it::class.qualifiedName }}",
        )
        assertTrue(failures.isEmpty(), "both postings must commit, but saw $failures")
        // FOR SHARE takes a row lock without writing the tuple, so nothing bumps row_version. A
        // lock strengthened to FOR UPDATE, or a lock quietly replaced by a compare-and-set, would
        // show up here as a version the readers moved.
        assertEquals(
            rowVersionBefore,
            rowVersionOf(key),
            "a shared lock must leave the period row itself untouched",
        )
    }

    @Test
    fun `S4 concurrent closes serialize and the second sees the first`() {
        // Deliberately untouched by ADR 0025. Both sides stay on the bare READ COMMITTED template
        // because decision 14 holds the close path there, and at READ COMMITTED the collapsed
        // single-statement FOR UPDATE is strictly equivalent to the old lock-then-read pair:
        // EvalPlanQual hands the second close the first's committed CLOSED. This scenario is the
        // load-bearing evidence for that decision, so it is listed as unchanged rather than
        // assumed to be - raising it too would break it and buy nothing, since SSI does not catch
        // this anomaly even when both sides are serializable.
        val key = openPeriod("s4")
        val firstLocked = CountDownLatch(1)
        val releaseFirst = CountDownLatch(1)
        val secondObserved = AtomicReference<FiscalPeriodStatus>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val first =
                executor.submit {
                    transactions.execute {
                        periods.lockForStateChange(key)
                        periods.updateStatus(key, FiscalPeriodStatus.CLOSED, ACTOR_ID, null)
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
                    postingTransactions.execute("S5 posting") {
                        periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val update =
                executor.submit {
                    transactions.execute {
                        calendar.updateStatusWithoutLocking(key, FiscalPeriodStatus.CLOSED)
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
        // Sequential throughout, so the isolation level is not the property under test and the
        // bare template is the right instrument: nothing here overlaps anything.
        val key = openPeriod("s6")
        transactions.execute {
            periods.updateStatus(
                key,
                FiscalPeriodStatus.CLOSED,
                ACTOR_ID,
                null,
            )
        }

        transactions.execute {
            assertEquals(
                FiscalPeriodStatus.CLOSED,
                statusOfCoveringLock(key),
            )
        }
        assertEquals(0, calendar.journalCount(key.organisationId))

        transactions.execute { periods.updateStatus(key, FiscalPeriodStatus.OPEN, ACTOR_ID, null) }
        transactions.execute {
            assertEquals(
                FiscalPeriodStatus.OPEN,
                statusOfCoveringLock(key),
            )
            calendar.recordJournal(key)
        }

        assertEquals(
            1,
            calendar.journalCount(key.organisationId),
            "only the posting made after the reopen may exist",
        )
    }

    @Test
    fun `S7 the business date is not a posting serialization point`() {
        // A posting holds its period lock; an unrelated write to the same organisation must not
        // block behind it, because the period lock is scoped to the period row alone.
        //
        // Worth more since the collapse than before it: the locking statement is now a range
        // predicate rather than a primary-key equality, and at SERIALIZABLE a range predicate
        // takes SIRead predicate locks. Those never block, so a promotion to page or relation
        // granularity cannot show up as a wait - but an unrelated write to the same table is the
        // one place a regression in that area would be visible at all.
        val key = openPeriod("s7")
        val postingLocked = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val unrelatedCompleted = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    postingTransactions.execute("S7 posting") {
                        periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
                        postingLocked.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingLocked.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val unrelated =
                executor.submit {
                    // Next year's calendar, so it neither overlaps the locked period nor trips
                    // ex_accounting_fiscal_period_no_overlap - a conflicting insert would fail on
                    // the constraint rather than testing anything about locking.
                    transactions.execute {
                        calendar.createPeriod(key.organisationId, OPEN_STATUS, yearOffset = 1)
                    }
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
        // There is no locking primitive any more. The refusal used to come from PostgresRowLock,
        // which every locking adapter had to go through; ADR 0025 deleted that class so the
        // lock-then-read shape it made representable could not be written, and the two checks are
        // now hand-written first lines in JooqFiscalPeriodStateStore. Hand-written means a refactor
        // can drop one and keep the other, which is why each assertion also requires the message to
        // name its own operation rather than merely to be an IllegalStateException.
        //
        // Asserted through the store rather than against the check directly: a row lock on an
        // autocommit connection is released before the caller can rely on it, and the path a
        // caller actually uses is the one that has to refuse.
        val key = openPeriod("s8")

        val posting =
            assertFailsWith<IllegalStateException> {
                periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
            }
        assertTrue(
            posting.message.orEmpty().contains(POSTING_LOCK_OPERATION),
            "the refusal must name the posting lock, but was: ${posting.message}",
        )

        val stateChange = assertFailsWith<IllegalStateException> { periods.lockForStateChange(key) }
        assertTrue(
            stateChange.message.orEmpty().contains(STATE_CHANGE_LOCK_OPERATION),
            "the refusal must name the state-change lock, but was: ${stateChange.message}",
        )
    }

    @Test
    fun `S9 an expired lock_timeout surfaces as 55P03, never as 40001`() {
        // The assertion whose absence let a dead catch ship. FiscalPeriodLifecycleService bounds
        // the close path with SET LOCAL lock_timeout and translates the expiry into the published
        // accounting.fiscal_period_lock_timeout code - but an earlier revision caught
        // QueryTimeoutException, which PostgreSQL's 55P03 never produces. Spring's PostgreSQL
        // error codes list 55P03 under cannotAcquireLockCodes, and QueryTimeoutException is a
        // *sibling* of CannotAcquireLockException under TransientDataAccessException, never a
        // supertype. So the catch was unreachable, the published code could not be raised by any
        // path, and a close blocked behind a long posting returned a 500.
        //
        // This pins what the database actually throws, on a real lock, at a timeout short enough
        // to keep the suite fast - and now pins the SQLSTATE too. A lock timeout and a
        // serialization failure are both ConcurrencyFailureException, and only one of them is ever
        // safe to retry: re-running a posting that lost a serialization race is what PostgreSQL
        // prescribes, while re-running a close that could not get past a long posting just queues
        // behind it again. Once the retry boundary gains its policy, an assertion that only said
        // "ConcurrencyFailureException" would no longer distinguish the two.
        val key = openPeriod("s9")
        val postingHolds = CountDownLatch(1)
        val releasePosting = CountDownLatch(1)
        val observed = AtomicReference<Throwable>()

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val posting =
                executor.submit {
                    postingTransactions.execute("S9 posting") {
                        periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
                        postingHolds.countDown()
                        assertTrue(releasePosting.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            assertTrue(postingHolds.await(TIMEOUT_SECONDS, TimeUnit.SECONDS))

            val close =
                executor.submit {
                    runCatching {
                        transactions.execute {
                            lockTimeout.applyToCurrentTransaction(Duration.ofMillis(250))
                            periods.lockForStateChange(key)
                        }
                    }.onFailure(observed::set)
                }
            close.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            releasePosting.countDown()
            posting.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        assertTrue(
            observed.get() is CannotAcquireLockException,
            "an expired lock_timeout must arrive as CannotAcquireLockException, but was " +
                "${observed.get()?.let { it::class.qualifiedName }}",
        )
        assertTrue(
            observed.get() !is CannotSerializeTransactionException,
            "a lock timeout is not a serialization failure; translating it as one would make the " +
                "retry boundary re-run a close that has no chance of succeeding sooner",
        )
        assertEquals(
            LOCK_NOT_AVAILABLE,
            sqlStateOf(observed.get()),
            "the close waited out its bound, so PostgreSQL must raise lock_not_available rather " +
                "than a serialization failure",
        )
    }

    // The mandated name is longer than the 100-character line the house style holds everything
    // else to, and a back-ticked identifier cannot be wrapped. Narrowed to this one declaration.
    @Suppress("MaxLineLength", "ktlint:standard:max-line-length")
    @Test
    fun `the covering lock carries the tenant predicate in the same statement, so another tenant's period is never lockable`() {
        // Both locking statements carry the tenant predicate, and both carry it in the same
        // statement as the lock. For the state-change lock that still means a forged key: a caller
        // naming another tenant's period id gets nothing rather than a lock on that tenant's row.
        // Without the predicate this returns a snapshot - and the snapshot's organisation id would
        // be the caller's, so a downstream tenant check would pass on a row it does not own.
        //
        // The posting side is no longer addressed by key at all, so a forged key cannot express the
        // attack against it. What replaces it is the twin tenant below: two tenants holding periods
        // over exactly the same dates, where the only thing separating them is the predicate. That
        // also makes the mutation loud rather than silent - drop the tenant predicate and the
        // statement matches both rows, so fetchOne() raises TooManyRowsException instead of quietly
        // handing back whichever row the planner reached first.
        val victim = openPeriod("tenant-victim")
        val attackerOrganisationId = fixture.createActiveOrganisation("tenant-attacker", ACTOR_ID)
        val forgedKey = victim.copy(organisationId = attackerOrganisationId)

        val locked = transactions.execute { periods.lockForStateChange(forgedKey) }

        assertEquals(null, locked, "another tenant's period must not be lockable")
        assertEquals(
            null,
            transactions.execute {
                periods.lockCoveringForPosting(attackerOrganisationId, PERIOD_DAY)
            },
            "a tenant with no calendar covering the date must get nothing, not someone else's row",
        )

        val twin = openPeriod("tenant-twin")
        assertEquals(
            twin,
            requireNotNull(
                transactions.execute {
                    periods.lockCoveringForPosting(twin.organisationId, PERIOD_DAY)
                },
            ).key,
            "the covering lock must select the caller's own period, not merely a period covering " +
                "the date",
        )

        val victimSnapshot =
            requireNotNull(
                transactions.execute {
                    periods.lockCoveringForPosting(victim.organisationId, PERIOD_DAY)
                },
            )
        assertEquals(victim, victimSnapshot.key, "the victim's own row must come back")
        assertEquals(FiscalPeriodStatus.OPEN, victimSnapshot.status, "the victim is untouched")
    }

    @Test
    fun `the store, the resolver and the guard are all wired`() {
        // The inverse of the assertion this test used to make. Until `V6` there was no
        // accounting_fiscal_period, so PostgresRowLock had no table to bind to and a @Service on
        // either consumer broke application context startup platform-wide - which this suite
        // caught. All three had to become beans in one change, and asserting all three keeps a
        // future revision from wiring the store while leaving its consumers unreachable.
        listOf(
            FiscalPeriodStateStore::class.java,
            PostingPeriodResolver::class.java,
            FiscalPeriodStateChangeGuard::class.java,
        ).forEach {
            assertTrue(
                applicationContext.getBeanNamesForType(it).isNotEmpty(),
                "expected a bean of ${'$'}{it.simpleName}",
            )
        }
        // AopUtils.getTargetClass, not ::class.java: the moment the store gains @Transactional or
        // any other advice, Spring hands back a proxy and a direct class comparison starts failing
        // for a reason that has nothing to do with what this assertion is about.
        assertEquals(
            JooqFiscalPeriodStateStore::class.java,
            AopUtils.getTargetClass(applicationContext.getBean(FiscalPeriodStateStore::class.java)),
            "the concurrency scenarios must exercise the production adapter, not a test double",
        )
    }

    @Test
    fun `a posting locks at SERIALIZABLE while a close still locks at READ COMMITTED`() {
        // One adapter method now serves a serializable reader and a read-committed writer, and the
        // asymmetry is a decision rather than an accident. This is where it is pinned.
        //
        // The method is the one the withdrawn READ COMMITTED test used, and it was right: read the
        // level from inside a transaction that has ACTUALLY taken the lock. Asserting the server
        // default separately - or the template's own attribute - stays green through exactly the
        // change that matters, because what a caller declares and what the database is running are
        // different questions whenever a transaction is joined rather than opened. Only the claim
        // is inverted.
        val key = openPeriod("isolation")

        val posting =
            postingTransactions.execute("isolation probe") {
                periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)
                requireNotNull(dsl.fetchValue(SHOW_ISOLATION)).toString()
            }
        assertEquals(
            SERIALIZABLE,
            posting,
            "the posting path declares SERIALIZABLE in exactly one place - the transaction " +
                "PostingTransactionBoundary opens - and PostingEngine.post refuses anything " +
                "weaker with accounting.snapshot_isolation_unavailable, so a downgrade here is a " +
                "posting path that would refuse itself at runtime",
        )

        val close =
            requireNotNull(
                transactions.execute {
                    periods.lockForStateChange(key)
                    dsl.fetchValue(SHOW_ISOLATION)
                },
            ).toString()
        assertEquals(
            READ_COMMITTED,
            close,
            "the close path stays at READ COMMITTED on purpose: FiscalPeriodLifecycleService " +
                "catches only CannotAcquireLockException, which is 55P03, and would not catch " +
                "the 40001 a raised close would start producing",
        )

        // The behavioural half above proves what the close is running at; this proves nobody has
        // declared otherwise on the method that owns the close transaction. Both, because an
        // annotation is what a future author would reach for and the level in force is what
        // actually decides the outcome.
        assertEquals(
            Isolation.DEFAULT,
            FiscalPeriodLifecycleService::class.java
                .getMethod("close", FiscalPeriodStateChangeCommand::class.java)
                .getAnnotation(Transactional::class.java)
                .isolation,
            "the close path must not declare an isolation level of its own",
        )
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
     *
     * **Valid only where a real lock wait happens** — S1, S4, S5 and S9, where `FOR UPDATE` or a
     * bare `UPDATE` queues behind a held `FOR SHARE`. [LockOverlapProbe] is blind to serialization
     * failures: every shape it offers keys on `wait_event_type = 'Lock'` or ungranted `pg_locks`
     * rows, `SIRead` locks are always granted and never wait, and a transaction whose snapshot has
     * already lost the race does not block at all. Reaching for this in a `40001` scenario out of
     * habit burns the probe's deadline and reports a production lock that was never meant to be
     * taken; those scenarios are latch-sequenced around the losing statement instead.
     */
    private fun awaitBlockedOnLock() = probe.awaitAnyBackendBlocked()

    /**
     * A [TransactionTemplate] that opens at [isolation], named in PostgreSQL's own spelling.
     *
     * The suite otherwise has exactly one template, at the server default. `REPEATABLE READ` is
     * opened by no production bean at all, so a scenario that needs to show a property holding at
     * every level has to open it here — and taking the level from the same string the assertion
     * compares against keeps the request and the expectation from drifting apart.
     */
    private fun transactionsAt(isolation: String): TransactionTemplate =
        TransactionTemplate(transactionManager).apply {
            isolationLevel = requireNotNull(ISOLATION_LEVELS[isolation]) { "unknown: $isolation" }
        }

    /**
     * Asserts, from inside the current transaction, that [expected] is the level actually in force.
     *
     * Must be called with a transaction open. A template's attribute says what was asked for; this
     * says what PostgreSQL is running, and the two differ whenever a transaction is joined rather
     * than opened — which is the whole reason the production posting path asks the database the
     * same question instead of trusting its own annotation.
     */
    private fun assertIsolationInForce(expected: String) =
        assertEquals(
            expected,
            requireNotNull(dsl.fetchValue(SHOW_ISOLATION)).toString(),
            "the transaction degraded to another isolation level, so whatever this scenario " +
                "proves it does not prove it at $expected",
        )

    /**
     * Asserts the level-specific half of S2: `CLOSED` at READ COMMITTED, `40001` above it.
     *
     * Separated from the union property on purpose. The property — never a stale `OPEN` — is what
     * the ledger depends on; which of the two mechanisms delivers it is a fact about PostgreSQL
     * that this suite records so that a change of mechanism is read rather than guessed.
     */
    private fun assertMechanismFor(
        isolation: String,
        underLock: FiscalPeriodStatus?,
        failure: Throwable?,
    ) {
        if (isolation == READ_COMMITTED) {
            assertNull(
                failure,
                "at READ COMMITTED the locking statement must not abort, but raised $failure",
            )
            assertEquals(
                FiscalPeriodStatus.CLOSED,
                underLock,
                "at READ COMMITTED the locking statement takes a fresh snapshot and EvalPlanQual " +
                    "re-reads the locked row, so the committed close comes back",
            )
            return
        }
        assertTrue(
            failure is ConcurrencyFailureException,
            "above READ COMMITTED there is no EvalPlanQual re-read; the locking statement must " +
                "abort rather than hand back a status, but was " +
                "${failure?.let { it::class.qualifiedName }}",
        )
        assertEquals(
            SERIALIZATION_FAILURE,
            sqlStateOf(failure),
            "the abort must be PostgreSQL refusing to serialize the access, not some other " +
                "transient failure that happened to fire first",
        )
    }

    /** The SQLSTATE of the first [SQLException] in [failure]'s cause chain, if there is one. */
    private fun sqlStateOf(failure: Throwable?): String? =
        generateSequence(failure) { it.cause }
            .filterIsInstance<SQLException>()
            .firstOrNull()
            ?.sqlState

    /** The status of the period covering [key]'s posting date, read without locking. */
    private fun statusOfUnlockedRead(key: FiscalPeriodKey): FiscalPeriodStatus =
        requireNotNull(periods.findCovering(key.organisationId, PERIOD_DAY)).status

    /** The status of the period covering [key]'s posting date, read under `FOR SHARE`. */
    private fun statusOfCoveringLock(key: FiscalPeriodKey): FiscalPeriodStatus =
        requireNotNull(periods.lockCoveringForPosting(key.organisationId, PERIOD_DAY)).status

    /** Reads the period row's optimistic-locking version without taking any lock. */
    private fun rowVersionOf(key: FiscalPeriodKey) =
        requireNotNull(
            dsl
                .select(ACCOUNTING_FISCAL_PERIOD.ROW_VERSION)
                .from(ACCOUNTING_FISCAL_PERIOD)
                .where(ACCOUNTING_FISCAL_PERIOD.ID.eq(key.fiscalPeriodId))
                .fetchOne(ACCOUNTING_FISCAL_PERIOD.ROW_VERSION),
        )

    private fun openPeriod(label: String): FiscalPeriodKey {
        val organisationId = fixture.createActiveOrganisation("period-$label", ACTOR_ID)
        return calendar.createPeriod(organisationId, OPEN_STATUS)
    }

    private companion object {
        /** The V3 bootstrap administrator: `audit_event.actor_user_id` is a real foreign key. */
        val ACTOR_ID: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val OPEN_STATUS = FiscalPeriodStatus.OPEN
        val PERIOD_DAY: java.time.LocalDate = FiscalCalendarFixture.PERIOD_DAY
        const val TIMEOUT_SECONDS = 20L
        const val UNBLOCKED_SECONDS = 5L

        /** PostgreSQL's own spellings, which is what `SHOW transaction_isolation` hands back. */
        const val READ_COMMITTED = "read committed"
        const val REPEATABLE_READ = "repeatable read"
        const val SERIALIZABLE = "serializable"
        const val SHOW_ISOLATION = "SHOW transaction_isolation"
        val ISOLATION_LEVELS =
            mapOf(
                READ_COMMITTED to TransactionDefinition.ISOLATION_READ_COMMITTED,
                REPEATABLE_READ to TransactionDefinition.ISOLATION_REPEATABLE_READ,
                SERIALIZABLE to TransactionDefinition.ISOLATION_SERIALIZABLE,
            )

        /** `serialization_failure`: the access could not be serialized and the statement died. */
        const val SERIALIZATION_FAILURE = "40001"

        /** `lock_not_available`: the statement waited out its `lock_timeout` bound. */
        const val LOCK_NOT_AVAILABLE = "55P03"

        /** The operation strings the two hand-written transaction checks name in their refusals. */
        const val POSTING_LOCK_OPERATION = "Locking the fiscal period covering a posting date"
        const val STATE_CHANGE_LOCK_OPERATION = "Locking a fiscal period for a state change"
    }
}
