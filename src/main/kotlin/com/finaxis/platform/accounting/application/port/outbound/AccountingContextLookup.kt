package com.finaxis.platform.accounting.application.port.outbound

import com.finaxis.platform.accounting.domain.AccountingContext

/**
 * Narrow read port over the ambient request context.
 *
 * Exactly one accounting adapter implements this, so accounting application services never touch a
 * thread-local directly and stay unit-testable with a plain fake.
 */
interface AccountingContextLookup {
    /**
     * Returns the active organisation, branch and actor, or null when no context is installed - a
     * background worker, a test, or an unauthenticated path.
     */
    fun current(): AccountingContext?

    /**
     * Returns the active context, or raises an invalid-operation failure carrying
     * [com.finaxis.platform.accounting.application.posting.PostingErrorCodes.NO_ACTIVE_CONTEXT].
     */
    fun require(): AccountingContext
}
