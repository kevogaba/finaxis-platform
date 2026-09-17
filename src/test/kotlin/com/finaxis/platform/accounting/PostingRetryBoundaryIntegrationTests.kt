package com.finaxis.platform.accounting

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.accounting.application.ledger.PostingRetryPolicy
import com.finaxis.platform.accounting.application.ledger.PostingTransactionBoundary
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.common.application.ConflictException
import org.junit.jupiter.api.Test
import org.springframework.aop.framework.Advised
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.CannotAcquireLockException
import org.springframework.dao.ConcurrencyFailureException
import org.springframework.dao.DeadlockLoserDataAccessException
import org.springframework.dao.OptimisticLockingFailureException
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.interceptor.TransactionInterceptor
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The retry boundary itself, against the real proxied bean.
 *
 * This suite is the first thing written on this branch and the reason the branch is safe to build
 * on. Three of its claims cannot be established any other way, and the first two fail *silently*
 * if they are wrong:
 *
 * 1. **spring-retry actually loads and advises.** Its 2.0.13 POM declares `spring-context 6.2.19`
 *    while this build runs Spring Framework 7.0.9. Nothing about that is checked at compile time.
 * 2. **The generic `@Recover` resolves.** `PostingTransactionBoundary.exhausted` mirrors
 *    `execute`, and both erase to `Object`/`Function0`, so `RecoverAnnotationRecoveryHandler`
 *    should match them — but a mismatch is **not** a startup error. Spring Retry rethrows the
 *    original `ConcurrencyFailureException` and `accounting.posting_retries_exhausted` never
 *    reaches a caller. Without this test the error code could be dead in production and every
 *    other test on this branch would still pass.
 * 3. **The retry sits outside the transaction, structurally.** `PostingTransactionBoundary`
 *    carries retry advice and no transaction advice; `SerializablePostingTransaction`, one call
 *    later, carries transaction advice and no retry advice. That separation is what lets a
 *    serialization failure raised *by `COMMIT`* — an SSI pivot is cancelled during the commit
 *    attempt, not at the offending statement — reach the retry at all, and it is why the two are
 *    separate beans rather than one bean with two annotations ordered by precedence.
 *
 * The failures here are forced rather than raced. A real database cannot be made to exhaust a
 * five-attempt budget on demand — a losing posting blocks once and aborts once — so the state
 * machine is driven through the lambda, which is exactly what the lambda shape makes possible.
 * The genuine unforced `40001` is proven separately against real concurrent postings.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class PostingRetryBoundaryIntegrationTests(
    private val boundary: PostingTransactionBoundary,
) {
    @Test
    fun `an exhausted budget surfaces the named code, not the underlying failure`() {
        val attempts = AtomicInteger()

        val failure =
            assertFailsWith<ConflictException> {
                boundary.execute("Exhausting probe") {
                    attempts.incrementAndGet()
                    throw CannotAcquireLockException("forced serialization failure")
                }
            }

        assertEquals(
            PostingErrorCodes.POSTING_RETRIES_EXHAUSTED,
            failure.code,
            "a @Recover mismatch is not a startup error - Spring Retry rethrows the original and " +
                "this code never reaches a caller, so seeing the raw ConcurrencyFailureException " +
                "here means the generic @Recover did not resolve and the boundary must fall back " +
                "to one non-generic execute/exhausted pair per concrete return type",
        )
        assertEquals(
            PostingRetryPolicy.MAX_ATTEMPTS,
            attempts.get(),
            "the budget is spent exactly once over, neither short nor long",
        )
        assertTrue(
            failure.cause is CannotAcquireLockException,
            "the original failure is preserved as the cause for the logs, though never in the " +
                "RFC 9457 body; saw ${failure.cause}",
        )
    }

    @Test
    fun `a failure that clears is retried and the work runs to completion`() {
        val attempts = AtomicInteger()

        val result =
            boundary.execute("Recovering probe") {
                if (attempts.incrementAndGet() < 3) {
                    throw CannotAcquireLockException("forced, attempts 1 and 2")
                }
                "committed"
            }

        assertEquals("committed", result)
        assertEquals(3, attempts.get(), "two failures, then the work's own return value")
    }

    @Test
    fun `a deadlock is retried and an optimistic-lock conflict is not`() {
        val deadlocked = AtomicInteger()
        assertFailsWith<ConflictException> {
            boundary.execute("Deadlock probe") {
                deadlocked.incrementAndGet()
                throw DeadlockLoserDataAccessException(
                    "40P01",
                    RuntimeException("deadlock detected"),
                )
            }
        }
        assertEquals(
            PostingRetryPolicy.MAX_ATTEMPTS,
            deadlocked.get(),
            "40P01 is a ConcurrencyFailureException and a deadlock loser is exactly what a retry " +
                "is for",
        )

        // OptimisticLockingFailureException also extends ConcurrencyFailureException, so without
        // the explicit noRetryFor it would be swept into the same budget. Retrying a row-version
        // conflict is a different decision, taken by whoever owns that row.
        val optimistic = AtomicInteger()
        assertFailsWith<OptimisticLockingFailureException> {
            boundary.execute("Optimistic probe") {
                optimistic.incrementAndGet()
                throw OptimisticLockingFailureException("row version moved")
            }
        }
        assertEquals(1, optimistic.get(), "noRetryFor must win over the retryFor supertype")
    }

    @Test
    fun `an accounting refusal is never retried`() {
        val attempts = AtomicInteger()

        val refusal =
            assertFailsWith<ConflictException> {
                boundary.execute("Closed-period probe") {
                    attempts.incrementAndGet()
                    throw ConflictException(
                        code = PostingErrorCodes.PERIOD_CLOSED,
                        safeDetail = "The fiscal period covering the posting date is not open.",
                    )
                }
            }

        assertEquals(PostingErrorCodes.PERIOD_CLOSED, refusal.code, "the refusal passes through")
        assertEquals(
            1,
            attempts.get(),
            "accounting's ConflictException is an ApplicationException, outside the " +
                "ConcurrencyFailureException hierarchy, so a closed period is answered at once " +
                "rather than after a second of pointless backoff",
        )
    }

    @Test
    fun `no transaction is open while the retry backs off`() {
        val activeBetweenAttempts = mutableListOf<Boolean>()
        val attempts = AtomicInteger()

        assertFailsWith<ConflictException> {
            boundary.execute("Backoff probe") {
                // Read at the TOP of each attempt: attempt 2 onwards runs after the previous
                // attempt's transaction has rolled back and after the backoff has slept. The
                // transaction must be open here (SerializablePostingTransaction opened it one call
                // in) but must NOT have survived from the previous attempt - the whole reason the
                // boundary and the transaction are separate beans is that the connection goes back
                // to the pool before the sleep. HikariCP is at its unconfigured default of ten, so
                // a backoff that pinned a connection would be an outage rather than a slowdown.
                activeBetweenAttempts +=
                    TransactionSynchronizationManager.isActualTransactionActive()
                attempts.incrementAndGet()
                throw CannotAcquireLockException("forced")
            }
        }

        assertEquals(PostingRetryPolicy.MAX_ATTEMPTS, activeBetweenAttempts.size)
        assertTrue(
            activeBetweenAttempts.all { it },
            "every attempt runs inside its own transaction, opened afresh by " +
                "SerializablePostingTransaction; saw $activeBetweenAttempts",
        )
        assertNull(
            TransactionSynchronizationManager.getCurrentTransactionName(),
            "and none of them outlives the boundary",
        )
    }

    @Test
    fun `the boundary refuses to run inside a transaction it did not open`() {
        // Nesting would drop SERIALIZABLE silently - Spring does not validate an existing
        // transaction against a joiner's attributes - and would leave the retry inside the
        // transaction it is meant to retry, where re-running only earns 25P02 against a connection
        // the failure has already doomed.
        val failure =
            assertFailsWith<IllegalStateException> {
                boundary.execute("Outer") {
                    boundary.execute("Nested") { "unreachable" }
                }
            }
        assertTrue(
            failure.message.orEmpty().contains("must own its transaction"),
            "the refusal must name the contract it is enforcing; saw ${failure.message}",
        )
    }

    @Test
    fun `the boundary is retry-advised and deliberately not transaction-advised`() {
        val advised = boundary as Advised
        val advisors = advised.advisors.map { it.advice }

        // Matched by name rather than by type: @EnableRetry installs
        // AnnotationAwareRetryOperationsInterceptor, which does NOT extend the
        // RetryOperationsInterceptor an author would reach for first. Asserting the wrong type
        // fails against a correctly wired bean, which is how this assertion was written the first
        // time.
        assertTrue(
            advisors.any { it::class.java.simpleName.contains("Retry") },
            "the retry interceptor must be on this bean; saw " +
                "${advisors.map { it::class.java.simpleName }}",
        )
        assertTrue(
            advisors.none { it is TransactionInterceptor },
            "this bean must carry NO transaction advice. The transaction is opened one call " +
                "later, on SerializablePostingTransaction, so the retry is outside it " +
                "structurally rather than by advisor precedence - which matters because an SSI " +
                "pivot is raised by COMMIT, thrown by the transaction interceptor itself, and is " +
                "therefore invisible to any advice nested inside it. Saw " +
                "${advisors.map { it::class.java.simpleName }}",
        )
    }

    @Test
    fun `both translations of SQLSTATE 40001 are covered by the retry type`() {
        // The retry names ConcurrencyFailureException rather than either concrete class, because
        // one SQLSTATE arrives as two unrelated Spring types depending on WHERE it is raised: a
        // statement-level 40001 through jOOQ's SQLErrorCodeSQLExceptionTranslator, and a
        // commit-time 40001 through JdbcTransactionManager, which in Spring 7 falls through to
        // SQLStateSQLExceptionTranslator. Pinning the hierarchy here means a Spring upgrade that
        // changes either translator breaks a named test rather than silently stopping the retry.
        assertTrue(
            ConcurrencyFailureException::class.java
                .isAssignableFrom(CannotAcquireLockException::class.java),
            "the commit-time translation must be retryable",
        )
        assertTrue(
            ConcurrencyFailureException::class.java
                .isAssignableFrom(DeadlockLoserDataAccessException::class.java),
            "40P01 must be retryable",
        )
        assertTrue(
            ConcurrencyFailureException::class.java
                .isAssignableFrom(OptimisticLockingFailureException::class.java),
            "and this one must be a supertype match too, which is precisely why noRetryFor is " +
                "needed to exclude it rather than it simply falling outside retryFor",
        )
    }
}
