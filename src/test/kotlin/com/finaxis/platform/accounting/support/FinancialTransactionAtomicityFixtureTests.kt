package com.finaxis.platform.accounting.support

import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.Test
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.TransactionStatus
import org.springframework.transaction.support.SimpleTransactionStatus
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Focused unit coverage for the atomicity harness itself.
 *
 * The harness is what later issues reuse — issue #41's posting engine registers its own probes
 * against it — so its snapshot and probe-handling behaviour needs testing independently of any
 * database. Every other test in this area is a `@SpringBootTest`, which exercises the harness only
 * incidentally and would not distinguish a harness defect from a production one.
 *
 * `assertLeavesNoTrace` is covered here rather than only in an integration test because it is the
 * one entry point that opens no transaction: nothing rolls its operation back, so the probe
 * comparison *is* the proof, and an implementation that skipped or short-circuited that comparison
 * would still look green everywhere it was used.
 *
 * No database is involved: the probes here ignore the `DSLContext` they are handed, so a
 * connection-less jOOQ context suffices. The database-backed behaviour is covered by
 * `FinancialTransactionAtomicityIntegrationTests`.
 */
class FinancialTransactionAtomicityFixtureTests {
    @Test
    fun `snapshot reads every probe once and keys the result by probe name`() {
        val calls = mutableListOf<String>()
        val fixture =
            fixtureOf(
                counting("first", 1L, calls),
                counting("second", 2L, calls),
            )

        val snapshot = fixture.snapshot()

        assertEquals(mapOf("first" to 1L, "second" to 2L), snapshot)
        assertEquals(listOf("first", "second"), calls, "each probe must be evaluated exactly once")
    }

    @Test
    fun `snapshot rejects duplicate probe names rather than silently dropping one`() {
        // Without this, `associate` keeps only the last probe for a repeated name, and the
        // assertion loop then compares that single value against itself for both entries - so a
        // durable effect could go missing while the atomicity gate still passed. Two probes
        // legitimately share a name whenever the same table is probed for two organisations.
        val fixture =
            fixtureOf(
                counting("business_date_history", 1L),
                counting("business_date_history", 7L),
            )

        val failure = assertFailsWith<IllegalArgumentException> { fixture.snapshot() }

        assertTrue(
            failure.message.orEmpty().contains("business_date_history"),
            "the failure must name the offending probe, not merely report that a collision exists",
        )
    }

    @Test
    fun `an empty probe list produces an empty snapshot rather than failing`() {
        // Asserted so the duplicate-name guard cannot be implemented in a way that also treats
        // "no probes" as an error, which would make the harness awkward to extend incrementally.
        assertEquals(emptyMap(), fixtureOf().snapshot())
    }

    @Test
    fun `assertLeavesNoTrace fails when a probe advances across the operation`() {
        // The new entry point runs the operation with no transaction around it, so nothing rolls
        // back for it and the probe comparison is the entire proof. The fixture's own suite
        // already refuses to let a vacuous pass hide behind a duplicate probe name; this path
        // must not be the exception, and "it never actually compares" is the way it would be.
        var rows = 3L
        val fixture = fixtureOf(AtomicityProbe("journal_entry") { rows })

        val failure =
            assertFailsWith<AssertionError> {
                fixture.assertLeavesNoTrace(emptyMap()) { rows += 1L }
            }

        assertTrue(
            failure.message.orEmpty().contains("journal_entry"),
            "the failure must name the probe that moved, not merely report a mismatch",
        )
    }

    @Test
    fun `assertLeavesNoTrace accepts the movement a caller declared`() {
        // The twin of the test above: without it, an implementation that always failed would pass
        // that one, and the entry point would be unusable for the committed case it also serves.
        var rows = 3L
        val fixture = fixtureOf(AtomicityProbe("journal_entry") { rows })

        fixture.assertLeavesNoTrace(mapOf("journal_entry" to 2L)) { rows += 2L }
    }

    @Test
    fun `assertLeavesNoTrace compares the probes before it rethrows the operation's failure`() {
        // A refused operation is the dominant use, so the probes must be compared on the failing
        // path too. Were the throwable to escape first, every rollback proof written against this
        // entry point would assert only that the refusal happened.
        var rows = 3L
        val fixture = fixtureOf(AtomicityProbe("journal_entry") { rows })

        val failure =
            assertFailsWith<AssertionError> {
                fixture.assertLeavesNoTrace(emptyMap()) {
                    rows += 1L
                    error("the operation was refused after writing")
                }
            }

        // Had the operation's own IllegalStateException escaped first, the expected AssertionError
        // would never have been raised and this assertion would have reported that instead.
        assertTrue(failure.message.orEmpty().contains("journal_entry"))
    }

    private fun fixtureOf(vararg probes: AtomicityProbe) =
        FinancialTransactionAtomicityFixture(
            DSL.using(SQLDialect.POSTGRES),
            NoOpTransactionManager(),
            probes.toList(),
        )

    private fun counting(
        name: String,
        value: Long,
        calls: MutableList<String>? = null,
    ) = AtomicityProbe(name) {
        calls?.add(name)
        value
    }

    /** The fixture builds a `TransactionTemplate` eagerly; these tests never execute one. */
    private class NoOpTransactionManager : PlatformTransactionManager {
        override fun getTransaction(definition: TransactionDefinition?): TransactionStatus =
            SimpleTransactionStatus()

        override fun commit(status: TransactionStatus) = Unit

        override fun rollback(status: TransactionStatus) = Unit
    }
}
