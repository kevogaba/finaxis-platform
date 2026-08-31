package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.PostgresTestConfiguration
import com.finaxis.platform.common.persistence.AdvisoryLockNamespace
import org.jooq.DSLContext
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.TestConstructor
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Proves the assumption [AdvisoryLockNamespace] rests on, rather than trusting the documentation:
 * PostgreSQL keeps the single-`bigint` and two-`int4` advisory key spaces structurally separate.
 *
 * This matters because the repository already has two single-key call sites hashing arbitrary text
 * into one shared space. If the spaces were not separate, a new accounting lock domain could
 * silently serialize against a tenant-settings or idempotency lock.
 */
@Import(PostgresTestConfiguration::class)
@SpringBootTest
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
class AdvisoryLockNamespaceIntegrationTests(
    private val dsl: DSLContext,
    transactionManager: PlatformTransactionManager,
) {
    private val transactions = TransactionTemplate(transactionManager)

    @Test
    fun `the single-key and two-int advisory key spaces do not collide`() {
        // Numerically identical keys in both spaces: classid 0, objid 42 against bigint 42.
        val locks =
            requireNotNull(
                transactions.execute {
                    dsl.execute("SELECT pg_advisory_xact_lock(?)", COLLIDING_KEY)
                    dsl.execute(
                        "SELECT pg_advisory_xact_lock(?::int4, ?::int4)",
                        ZERO_CLASS,
                        COLLIDING_KEY.toInt(),
                    )
                    dsl
                        .fetch(
                            """
                            SELECT objsubid FROM pg_locks
                            WHERE locktype = 'advisory' AND pid = pg_backend_pid()
                            ORDER BY objsubid
                            """.trimIndent(),
                        ).map { it.get(0, Int::class.java) }
                },
            )

        assertEquals(
            listOf(SINGLE_KEY_SUBID, TWO_INT_SUBID),
            locks,
            "two distinct locks must be held: PostgreSQL distinguishes the key spaces by objsubid",
        )
    }

    @Test
    fun `advisory locks are released when the transaction ends`() {
        // The backend pid is captured INSIDE the locking transaction and the check then asks about
        // that specific backend. Calling pg_backend_pid() afterwards would ask about whichever
        // pooled connection the check happened to get, so the count could read 0 because the lock
        // was released or because a different backend answered - and a session-scoped lock, which
        // genuinely does leak, would still look clean.
        val pid =
            requireNotNull(
                transactions.execute {
                    dsl.execute("SELECT pg_advisory_xact_lock(?)", COLLIDING_KEY)
                    dsl.fetchValue("SELECT pg_backend_pid()")
                },
            ).toString().toInt()

        val held =
            dsl
                .fetchValue(
                    "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' AND pid = ?",
                    pid,
                )?.toString()
                ?.toInt()

        assertEquals(0, held, "an _xact_ lock must not outlive its transaction")
    }

    @Test
    fun `object identifiers are stable and distinguish different keys`() {
        // Weak by nature - any hash satisfies it. Kept as a cheap guard on determinism; the
        // property that actually matters is that one derivation is used everywhere, which is
        // a convention documented on AdvisoryLockNamespace.objectId and not testable here.
        val first = AdvisoryLockNamespace.objectId("organisation-a:2026-08")
        val again = AdvisoryLockNamespace.objectId("organisation-a:2026-08")
        val other = AdvisoryLockNamespace.objectId("organisation-a:2026-09")

        assertEquals(first, again, "the same key must always map to the same lock identifier")
        assertNotEquals(first, other, "different periods must not share a lock identifier")
    }

    @Test
    fun `the accounting namespaces do not conflict in PostgreSQL`() {
        // Asserting the two constants differ is a tautology over two literals - it cannot fail and
        // proves nothing about locking. What matters is that the same objid under two classids
        // yields two independent locks, which is the property the registry exists to provide.
        val locks =
            requireNotNull(
                transactions.execute {
                    dsl.execute(
                        "SELECT pg_advisory_xact_lock(?, ?)",
                        AdvisoryLockNamespace.ACCOUNTING_FISCAL_PERIOD,
                        SHARED_OBJECT_ID,
                    )
                    dsl.execute(
                        "SELECT pg_advisory_xact_lock(?, ?)",
                        AdvisoryLockNamespace.ACCOUNTING_FISCAL_YEAR,
                        SHARED_OBJECT_ID,
                    )
                    dsl.fetchValue(
                        "SELECT COUNT(*) FROM pg_locks WHERE locktype = 'advisory' " +
                            "AND pid = pg_backend_pid()",
                    )
                },
            ).toString().toInt()

        assertEquals(
            2,
            locks,
            "the same object under two class identifiers must be two independent locks",
        )
    }

    private companion object {
        const val COLLIDING_KEY = 42L

        /** One object id used under both class identifiers. */
        const val SHARED_OBJECT_ID = 4242
        const val ZERO_CLASS = 0
        const val SINGLE_KEY_SUBID = 1
        const val TWO_INT_SUBID = 2
    }
}
