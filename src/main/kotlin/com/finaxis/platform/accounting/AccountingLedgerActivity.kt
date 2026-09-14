package com.finaxis.platform.accounting

import java.util.UUID

/**
 * Accounting-owned query port other foundation modules consult before changing something the
 * ledger has already committed to.
 *
 * The first consumer is the functional-currency freeze: once a tenant has a single posted journal
 * its functional currency cannot change, because journal lines are immutable and a later line in a
 * different unit would make every balance a sum of incompatible units. Lifecycle asks this before
 * accepting a `base_currency` change. See `docs/architecture/accounting-foundation.md`, *"The
 * functional currency is frozen once the tenant has posted"*.
 *
 * Deliberately a boolean, not a count or a journal: the consumer needs a yes or no, and anything
 * richer would tempt a module that is not accounting to reason about journals.
 */
interface AccountingLedgerActivity {
    /** True when at least one journal has been posted for the organisation. */
    fun hasPostedJournals(organisationId: UUID): Boolean

    /**
     * Serialises this transaction's currency change against the tenant's first posting, and must
     * be called immediately before [hasPostedJournals] on any path going on to change the currency.
     *
     * Without it the freeze is a check-then-write across two transactions that cannot see each
     * other: the settings transaction is told no journal exists, the posting transaction inserts
     * the first one, and both commit - leaving an immutable ledger written under an answer about a
     * currency the tenant no longer declares. The window is only ever a tenant's *first* posting,
     * but the ledger is the one place the platform cannot go back and fix.
     *
     * Blocks until postings in flight for this tenant have finished, and is released when the
     * caller's transaction ends, so a refused or failed change frees it with no unlock to forget.
     * Separate from [hasPostedJournals] rather than folded into it so that the lock is visible at
     * the call site, and so a future read-only caller is not silently made to take it.
     */
    fun lockFunctionalCurrencyForChange(organisationId: UUID)
}
