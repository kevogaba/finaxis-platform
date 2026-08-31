package com.finaxis.platform.accounting.support

import org.jooq.DSLContext
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.TransactionTemplate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * One durable, externally observable effect a financial operation must produce all-or-nothing.
 *
 * [countRows] must run a single bounded query and must never open its own transaction: the fixture
 * evaluates it both inside the transaction under test and from a second, independent connection.
 */
data class AtomicityProbe(
    val name: String,
    val countRows: (DSLContext) -> Long,
)

/**
 * Reusable proof harness for the accounting atomicity gate (issue #29): a financial operation
 * either produces every registered effect or none of them, and no effect is visible to another
 * connection before commit.
 *
 * Probes are supplied per test rather than baked in, so the posting-engine tests of issue #41 add
 * `posting_request`, `journal_entry`, `journal_line` and product sub-ledger probes without editing
 * this class. Transactions are driven through the application's own [PlatformTransactionManager],
 * so the production Spring and jOOQ wiring is exercised unchanged rather than replaced by a
 * test-only transaction path.
 *
 * Connection affinity is the subtlety this harness exists to make explicit. Spring's
 * `TransactionAwareDataSourceProxy` binds a connection per thread, so a thread with no active
 * transaction borrows a *different* pooled connection and therefore cannot see another thread's
 * uncommitted writes. Asserting on the same connection that holds the open transaction proves
 * nothing about durability - it is exactly the observation that made issue #12 look like a rollback
 * defect.
 */
class FinancialTransactionAtomicityFixture(
    private val dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
    private val probes: List<AtomicityProbe>,
) {
    private val transactions = TransactionTemplate(transactionManager)

    /** Row counts for every probe, read outside any transaction. */
    fun snapshot(): Map<String, Long> {
        // Rejects duplicate names rather than letting `associate` keep only the last. Two probes
        // sharing a name - two `business_date_history` probes for different organisations, say -
        // would collapse to one entry, and the assertion loop would then compare that single value
        // against itself for both. A lost effect would pass the atomicity gate silently, which is
        // the exact failure this harness exists to catch.
        val duplicates = probes.groupBy { it.name }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "atomicity probes must have unique names, but these repeat: $duplicates"
        }
        return probes.associate { it.name to it.countRows(dsl) }
    }

    /**
     * Runs [operation] in one transaction, fails it, and asserts every probe returned to its
     * pre-operation count. Returns the thrown exception so a caller can assert on its detail.
     */
    fun <E : Throwable> assertRollsBackAtomically(
        expected: KClass<E>,
        operation: (TransactionStatus) -> Unit,
    ): E {
        val before = snapshot()
        val failure = assertFailsWith(expected) { transactions.execute { operation(it) } }
        val after = snapshot()
        probes.forEach { probe ->
            assertEquals(
                before[probe.name],
                after[probe.name],
                "probe '${probe.name}' changed across a failed transaction: " +
                    "${before[probe.name]} -> ${after[probe.name]}",
            )
        }
        return failure
    }

    /**
     * Runs [operation] in one transaction, holds it open while asserting from a second connection
     * that every probe still reads its pre-operation count, then commits and asserts each probe
     * advanced by [expectedDeltas]. Proves external publication becomes observable only after
     * commit.
     */
    fun assertVisibleOnlyAfterCommit(
        expectedDeltas: Map<String, Long>,
        operation: () -> Unit,
    ) {
        val before = snapshot()
        val operationApplied = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)

        Executors.newVirtualThreadPerTaskExecutor().use { executor ->
            val committing =
                executor.submit {
                    transactions.execute {
                        operation()
                        operationApplied.countDown()
                        assertTrue(releaseCommit.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                    }
                }
            try {
                assertTrue(operationApplied.await(LATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS))
                val duringTransaction = snapshot()
                probes.forEach { probe ->
                    assertEquals(
                        before[probe.name],
                        duringTransaction[probe.name],
                        "probe '${probe.name}' was visible to a second connection before commit",
                    )
                }
            } finally {
                releaseCommit.countDown()
            }
            committing.get(FUTURE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        }

        val after = snapshot()
        probes.forEach { probe ->
            val delta = expectedDeltas[probe.name] ?: 0L
            assertEquals(
                (before[probe.name] ?: 0L) + delta,
                after[probe.name],
                "probe '${probe.name}' did not advance by $delta after commit",
            )
        }
    }

    private companion object {
        const val LATCH_TIMEOUT_SECONDS = 10L
        const val FUTURE_TIMEOUT_SECONDS = 30L
    }
}
