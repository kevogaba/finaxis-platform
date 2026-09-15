package com.finaxis.platform.accounting.support

import org.jooq.DSLContext
import java.util.concurrent.TimeUnit

/**
 * Proves that two database transactions genuinely *overlap*, rather than merely starting together.
 *
 * A `CountDownLatch` released from two threads proves simultaneous start and nothing more. The
 * scheduler is free to run the first task to completion - commit included - before the second
 * issues its first statement, which quietly degrades a concurrency scenario into the sequential
 * path it already covers elsewhere. A test written that way stays green when the production lock
 * it claims to exercise is deleted, which is the opposite of what it is for.
 *
 * This probe closes that hole by waiting until the contending transaction is *observably blocked*
 * in the database while the holder is still open and uncommitted. Overlap is then a fact read out
 * of PostgreSQL's own lock catalogues, not an inference from thread timing. When the obstruction
 * never materialises the probe fails the test by deadline, naming the false pass it prevents, so a
 * missing lock surfaces as a failure rather than as a green run.
 *
 * Three shapes, because the contended paths block differently:
 * - [awaitBlockedOnAdvisoryKey] for a nameable advisory key, e.g. the journal-reversal lock.
 * - [awaitClaimBlockedBehind] for a duplicate parked on an uncommitted `posting_request` row at
 *   `uq_posting_request_source`, which has no key to name but does have a known holder backend and
 *   a nameable *relation* the blocked statement is writing.
 * - [awaitBlockedBehind] for any other wait behind a known holder.
 *
 * All are strictly narrower than a bare `wait_event_type = 'Lock'` count over `pg_stat_activity`.
 * Many suites share one cached Spring context and therefore one PostgreSQL container, so an
 * unrelated backend waiting on an unrelated lock can satisfy that count and hand back a false
 * positive: the probe would report overlap that this scenario never achieved.
 */
internal class LockOverlapProbe(
    private val dsl: DSLContext,
    private val timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS,
) {
    /**
     * The backend process id of the connection bound to the calling thread.
     *
     * Must be called from *inside* the holder's transaction. `TransactionAwareDataSourceProxy`
     * binds one connection per thread for the life of a transaction, so a pid read afterwards may
     * belong to a different backend entirely and would tie [awaitBlockedBehind] to the wrong one.
     */
    fun currentBackendPid(): Int = (dsl.fetchValue("select pg_backend_pid()") as Number).toInt()

    /**
     * Blocks until some backend is waiting for the two-int advisory lock ([classId], [objectId]).
     *
     * `objsubid = 2` restricts the match to the two-`int4` key space, so a single-`bigint`
     * advisory lock whose numbers happen to coincide cannot satisfy the probe.
     *
     * `objid` is compared as a widened `bigint` rather than bound directly: `objectId` is a Java
     * `String.hashCode` and is routinely negative, while `pg_locks.objid` is an unsigned `oid`.
     * PostgreSQL wraps a negative `int4` on the way to `oid` - `(-12345)::oid` is `4294954951` -
     * so binding the negative value against `objid` matches nothing at all and the probe would
     * time out complaining about a lock that was in fact held.
     */
    fun awaitBlockedOnAdvisoryKey(
        classId: Int,
        objectId: Int,
        waiters: Int = 1,
    ) = await(
        query =
            "select count(*) from pg_locks " +
                "where locktype = 'advisory' and objsubid = 2 " +
                "and classid = ? and objid::bigint = ? and not granted",
        bindings = arrayOf(classId, objectId.toLong() and UNSIGNED_INT_MASK),
        expected = waiters,
        failure =
            "fewer than $waiters backends ever blocked on advisory lock ($classId, $objectId) at " +
                "once: the contending transactions were never simultaneously obstructed, so they " +
                "never overlapped and this scenario would pass with the production lock removed",
    )

    /**
     * Blocks until some backend is waiting on a lock held by [holderPid].
     *
     * *Who* is waited on, and nothing about *what* is awaited. That is too loose whenever the
     * holder's transaction holds more than the one lock the scenario is about - which a posting
     * always does - so prefer [awaitClaimBlockedBehind] for the idempotency claim. This remains for
     * a holder whose transaction has exactly one contendable effect.
     */
    fun awaitBlockedBehind(holderPid: Int) =
        await(
            query =
                "select count(*) from pg_stat_activity " +
                    "where wait_event_type = 'Lock' and ? = any(pg_blocking_pids(pid))",
            bindings = arrayOf(holderPid),
            failure =
                "no backend ever blocked behind pid $holderPid: the contending transaction was " +
                    "never obstructed by the holder, so the two never overlapped and this " +
                    "scenario would pass without the serialisation it claims to prove",
        )

    /**
     * Blocks until some backend is waiting *on `posting_request`* behind [holderPid].
     *
     * [awaitBlockedBehind] alone cannot carry the idempotency scenarios. A posting transaction held
     * open does not hold only its claim: it also holds the tenant's `reference_sequence` counter
     * row and its own uncommitted `journal_entry` and `journal_line` rows. A duplicate on any
     * of those satisfies "blocked behind the holder" identically, so the scenario could pass under
     * an interleaving that never contended at `uq_posting_request_source` at all - which is the one
     * thing it exists to prove.
     *
     * Three facts are therefore required together, each read out of PostgreSQL rather than assumed:
     * - the wait is a `transactionid` or `SpeculativeToken` lock wait. `ON CONFLICT` inserts
     *   speculatively, so a duplicate detecting the holder's uncommitted tuple waits either on the
     *   speculative token or - once that insertion has completed - on the holder's transaction id.
     * - the holder is who it waits on, via `pg_blocking_pids`.
     * - the *statement* it is blocked in writes `posting_request`, evidenced both by the executing
     *   query text and by a granted `RowExclusiveLock` on that relation. A duplicate parked on the
     *   counter row or on a journal row is executing `reference_sequence`/`journal_*` SQL instead,
     *   and so fails this however long it waits.
     *
     * The relation is asserted through those two facts rather than through an *ungranted*
     * `pg_locks` row on `'posting_request'::regclass`, because no such row exists. A dump taken
     * while this very scenario was blocked shows the waiter holding `RowExclusiveLock` on
     * `posting_request` and its indexes *granted*, and its only ungranted entry being
     * `locktype = 'transactionid'`, which carries no relation at all. A probe written the other
     * way round would never match and would fail every run by deadline.
     */
    fun awaitClaimBlockedBehind(holderPid: Int) =
        await(
            query =
                "select count(*) from pg_stat_activity a " +
                    "where a.datname = current_database() " +
                    "and a.wait_event_type = 'Lock' " +
                    "and a.wait_event in ('transactionid', 'SpeculativeToken') " +
                    "and ? = any(pg_blocking_pids(a.pid)) " +
                    "and position('posting_request' in a.query) > 0 " +
                    "and exists (select 1 from pg_locks l where l.pid = a.pid " +
                    "and l.relation = 'posting_request'::regclass " +
                    "and l.mode = 'RowExclusiveLock' and l.granted)",
            bindings = arrayOf(holderPid),
            failure =
                "no backend ever blocked on posting_request behind pid $holderPid: the duplicate " +
                    "was never parked at uq_posting_request_source on the holder's uncommitted " +
                    "claim, so this scenario proved nothing about it - it would pass under an " +
                    "interleaving that contended only on the counter row or the journal rows, " +
                    "or with no contention at all",
        )

    /**
     * Blocks until *any* backend in this database is waiting on *any* lock.
     *
     * The weakest of the three, and the last resort: it cannot tell this scenario's waiter from an
     * unrelated one, and several suites share one cached Spring context and therefore one
     * PostgreSQL container. It earns its place only where the contended lock has no key the test
     * can name and no holder whose backend the test can capture - a row lock taken deep inside a
     * production service with no seam to observe it through. Prefer [awaitBlockedOnAdvisoryKey] or
     * [awaitBlockedBehind] wherever the scenario can reach the key or the holder.
     */
    fun awaitAnyBackendBlocked() =
        await(
            query =
                "select count(*) from pg_stat_activity " +
                    "where datname = current_database() " +
                    "and wait_event_type = 'Lock' and pid <> pg_backend_pid()",
            bindings = emptyArray(),
            failure =
                "no backend ever blocked on a lock: the contending statement was never " +
                    "obstructed, so this scenario would pass without the production lock",
        )

    /**
     * Polls [query] until it reports a waiter, or fails with [failure] at the deadline.
     *
     * Spins rather than sleeping a fixed interval so the wait ends as soon as the obstruction is
     * observable, keeping the held transaction - and the connection it pins - open no longer than
     * the proof requires.
     */
    private fun await(
        query: String,
        bindings: Array<Any>,
        failure: String,
        expected: Int = 1,
    ) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
        while (System.nanoTime() < deadline) {
            val waiting = dsl.fetchValue(query, *bindings)
            if ((waiting as? Number)?.toLong()?.let { it >= expected } == true) {
                return
            }
            Thread.onSpinWait()
        }
        throw AssertionError(failure)
    }

    private companion object {
        /**
         * Shorter than the *latch* the holder waits on, which is the bound that actually decides
         * how long the transaction being measured stays open.
         *
         * The ordering that matters is `deadline < latch < future`: 5 < 10 or 20 < 60 across the
         * scenarios as they are written. Being shorter than the futures was the earlier claim, and
         * it was the wrong comparison - a future bounds how long the test waits for a *result*,
         * while the holder's latch bounds how long its transaction stays open at all. At 20 seconds
         * this probe outlived every latch in the suite and could still be polling after the
         * scenario it measures had been torn down, raising an `AssertionError` that blamed
         * production for a defect that does not exist. Ending first instead makes a missing lock
         * fail here, naming the false pass, while the holder is still demonstrably open.
         */
        const val DEFAULT_TIMEOUT_SECONDS = 5L

        /** Widens a signed `int4` hash to the unsigned `oid` PostgreSQL stores it as. */
        const val UNSIGNED_INT_MASK = 0xFFFFFFFFL
    }
}
