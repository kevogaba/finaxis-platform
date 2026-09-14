package com.finaxis.platform.accounting.application

/**
 * Asserts that the current transaction reads every statement from one snapshot.
 *
 * Accounting has two operations whose correctness depends on that and cannot be expressed as a
 * single statement: the control-account proof, which compares a general-ledger aggregate against a
 * sub-ledger one, and reading a manual journal, whose header and lines are a pair a checker acts
 * on. Under the repository's default `READ COMMITTED` each statement takes a fresh snapshot, and a
 * commit landing in between is silently mixed into the answer.
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
     * Refuses unless the current transaction holds one snapshot for its whole life.
     *
     * [operation] names the caller in the refusal, because the same guard serves several and
     * "which read was it" is the first thing an operator asks. Throws
     * [com.finaxis.platform.common.application.ConflictException] with
     * [com.finaxis.platform.accounting.application.posting.PostingErrorCodes
     * .SNAPSHOT_ISOLATION_UNAVAILABLE].
     */
    fun requireStableSnapshot(operation: String)
}
