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
}
