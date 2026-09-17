package com.finaxis.platform.accounting.application.ledger

/**
 * The retry budget a posting spends on serialization failures before it gives the request back.
 *
 * **These numbers are sized by argument, not by measurement.** No throughput or abort-rate figure
 * has been taken against this path; measurement is deferred to the posting throughput issue
 * (`#119`), which owns revising them. What follows is the reasoning they rest on, so that a later
 * change has something to argue with rather than three bare literals.
 *
 * **Jitter, not backoff, is the mechanism here.** The dominant source of `40001` on this path is
 * not a rare anomaly: every posting in a tenant updates that tenant's single gapless
 * `reference_sequence` row, so at `SERIALIZABLE` any two overlapping same-tenant postings produce
 * one winner and one abort, measured, whether or not they touch the same accounts or period. The
 * losers are therefore correlated - they were all waiting on one row and are released together -
 * and a fixed or purely exponential backoff would march them into the next collision in lockstep.
 * [MIN_BACKOFF_MILLIS] and [MAX_BACKOFF_MILLIS] with no multiplier select Spring Retry's
 * uniform-random backoff, which spreads a burst across the interval instead of preserving its
 * shape. The absence of a multiplier is a decision, not an omission.
 *
 * **Five attempts because the counter is a real ceiling, not because five is lucky.** Retrying past
 * the point where a tenant's arrival rate exceeds what gapless numbering can absorb does not find
 * capacity that is not there; it converts a capacity problem into held request threads. A bounded
 * budget turns that into a fast, named `409` - `accounting.posting_retries_exhausted` - that an
 * operator can alert on and read as a capacity signal.
 *
 * **The bound is far inside the idempotency window, deliberately.** Five attempts at up to
 * [MAX_BACKOFF_MILLIS] sleep for under a second in the worst case, against an
 * `IdempotencyProperties.inProgressTimeout` of five minutes. A retrying request can therefore never
 * be slow enough for `reacquireIfStale` to treat its own in-flight claim as abandoned and hand the
 * `Idempotency-Key` to someone else. Raising these numbers by orders of magnitude would reopen
 * that question.
 *
 * `const val` rather than literals in the annotation because `@Retryable`'s attributes must be
 * compile-time constants, and because Detekt's `MagicNumber` rule exempts constant declarations but
 * not annotation arguments - so this object is also the only place the values can be named.
 */
object PostingRetryPolicy {
    /** Five attempts: the original and four retries. */
    const val MAX_ATTEMPTS = 5

    /** The shortest backoff between attempts, in milliseconds. */
    const val MIN_BACKOFF_MILLIS = 20L

    /** The longest backoff between attempts, in milliseconds. */
    const val MAX_BACKOFF_MILLIS = 250L
}
