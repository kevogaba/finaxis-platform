package com.finaxis.platform.accounting.application

import java.util.UUID

/**
 * Serialises a tenant's first posting against a change to that tenant's functional currency.
 *
 * The freeze - once a tenant has posted a journal, its `base_currency` cannot change - is a
 * check-then-write across two different transactions, and at `READ COMMITTED` neither sees the
 * other. The settings transaction asks whether any journal exists and is told no; the posting
 * transaction inserts the first journal; both commit. The tenant then declares one currency while
 * its immutable ledger was written under the guard's answer about another. The window is only ever
 * a tenant's *first* posting, after which the currency is frozen for good - but "rare" is not
 * "closed", and the ledger is the one place the platform cannot go back and fix.
 *
 * The two modes are what make this affordable. A posting takes the lock **shared**, so postings
 * never wait on each other; a currency change takes it **exclusive**, so it waits for postings in
 * flight and they wait for it. The cost falls entirely on the rare administrative operation.
 *
 * Declared here and implemented in `adapter/outbound/persistence`, like every other port this
 * package owns: the application layer states that the two must not interleave, and does not get to
 * know that the mechanism is a PostgreSQL advisory lock.
 */
interface FunctionalCurrencyLock {
    /**
     * Taken **shared** by a posting, before any other lock it acquires.
     *
     * Ordering is the whole design, and the claim worth stating precisely. This is the first lock
     * `PostingEngine.postNew` takes - but not the first a *posting* takes: the reversal advisory
     * lock, a `manual_journal` row and the `posting_request` row of the idempotency claim all
     * precede it. What makes the placement acyclic is that none of those classes is ever acquired
     * by the currency-change flow, which takes this lock and then only the per-setting-key advisory
     * lock. `docs/adr/0023-...` states the full chain and what a future flow must re-check; it is
     * not enough to assume this lock is "always first".
     */
    fun lockForPosting(organisationId: UUID)

    /**
     * Taken **exclusive** by a `base_currency` change, before it reads whether the tenant has
     * posted.
     *
     * The adjacency is the fix: acquiring after that read would leave exactly the window this
     * exists to close. Held for the remainder of the caller's transaction and released at commit or
     * rollback, so there is no unlock to forget and a failed settings change frees it.
     *
     * The wait must be **bounded** by the caller. While this request is queued, new postings for
     * the tenant queue behind it, so an indefinite wait here freezes the tenant's ledger rather
     * than merely delaying one administrator.
     */
    fun lockForCurrencyChange(organisationId: UUID)
}
