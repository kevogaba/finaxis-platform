package com.finaxis.platform.accounting.application

/**
 * Asserts that the current transaction runs at least at the isolation the caller demands.
 *
 * Accounting has two reads whose correctness depends on one snapshot and cannot be expressed as a
 * single statement: the control-account proof, which compares a general-ledger aggregate against a
 * sub-ledger one, and reading a manual journal, whose header and lines are a pair a checker acts
 * on. Under the repository's default `READ COMMITTED` each statement takes a fresh snapshot, and a
 * commit landing in between is silently mixed into the answer.
 *
 * The guard now serves a **write** path as well. The posting path runs at `SERIALIZABLE`, and the
 * question it asks here is not the read pairs' question. A read pair wants one snapshot for the
 * transaction's whole life, which `REPEATABLE READ` supplies; a posting wants the isolation its
 * owner decided the posting rules under. Those differ exactly where it matters: a posting that
 * joined a `REPEATABLE READ` transaction has a perfectly stable snapshot and is nevertheless
 * running at a level the decision forbids. That is why the minimum is an argument
 * ([RequiredSnapshotIsolation]) rather than a constant folded into the port - accepting
 * `REPEATABLE READ` for every caller would certify precisely the downgrade a posting must refuse.
 *
 * The guard exists because `@Transactional(isolation = …)` is a **request, not a guarantee**.
 * Spring's transaction managers ship with `validateExistingTransaction = false`, so a method that
 * joins a transaction already open at `READ COMMITTED` runs at `READ COMMITTED` with its declared
 * isolation dropped - no log line, no exception, and an annotation that reads as a lie. Asking the
 * database what is actually in force is the only honest check, and it belongs behind a port because
 * the question is answerable only by the adapter that owns the connection.
 */
fun interface SnapshotIsolationGuard {
    /**
     * Refuses unless the current transaction runs at [minimum] or stronger.
     *
     * [operation] names the caller in the refusal, because the same guard serves several and
     * "which read was it" is the first thing an operator asks. The refusal names [minimum] as well
     * as the level actually in force: an operator told only "this transaction is repeatable read",
     * about an operation whose annotation says `SERIALIZABLE`, reads it as a false positive.
     *
     * [minimum] is an explicit argument at every call site and carries no default. A default value
     * on a `fun interface`'s single abstract method does not compile - the pinned Kotlin compiler
     * rejects it with `FUN_INTERFACE_ABSTRACT_METHOD_WITH_DEFAULT_VALUE` - and the alternative,
     * dropping `fun interface`, would turn the one SAM fake in the test suite into a named class
     * for no gain.
     *
     * Throws [com.finaxis.platform.common.application.ConflictException] with
     * [com.finaxis.platform.accounting.application.posting.PostingErrorCodes
     * .SNAPSHOT_ISOLATION_UNAVAILABLE].
     */
    fun requireStableSnapshot(
        minimum: RequiredSnapshotIsolation,
        operation: String,
    )
}
