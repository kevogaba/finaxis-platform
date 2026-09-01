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
