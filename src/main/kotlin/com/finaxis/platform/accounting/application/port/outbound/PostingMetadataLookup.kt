package com.finaxis.platform.accounting.application.port.outbound

/**
 * Narrow read port over the ambient facts a posting *observes* rather than is *told*.
 *
 * Separate from [AccountingContextLookup] on purpose, and deliberately not a field on
 * `AccountingContext`. That context is the caller's **assertion** - a product module constructs it
 * to say which tenant, branch and actor it is posting for, and the engine reconciles it against the
 * ambient one precisely because a caller could get it wrong. The request id is the opposite kind of
 * fact: nothing about a savings deposit knows or should know the id of the HTTP request that
 * triggered it, and widening `AccountingContext` would oblige every product module in the platform
 * to supply a value it has no business holding - which in practice means passing null and losing
 * the lineage anyway.
 *
 * So the engine asks for it here, at the moment it claims a request, and the one adapter that is
 * allowed to read the thread-local answers.
 */
fun interface PostingMetadataLookup {
    /**
     * The ambient request id, or null when the posting did not originate from a request that has
     * one - a JobRunr job, a broker listener, a test.
     *
     * There is deliberately no `require()` twin, unlike [AccountingContextLookup]: `request_id` is
     * a nullable column because a background posting legitimately has no request behind it, so an
     * absent value is an ordinary outcome rather than a failure.
     */
    fun currentRequestId(): String?
}
