package com.finaxis.platform.accounting

import java.time.LocalDate
import java.util.UUID

/**
 * Accounting-owned read port for the controlled tenant business date, implemented by the lifecycle
 * module which owns the business date and its close-of-business state machine.
 *
 * Read-only by construction: accounting can never advance, reopen or close a business date. This is
 * the first cross-module business-date port in the repository - nothing outside
 * `BusinessDateService` previously read it.
 */
interface AccountingBusinessDateLookup {
    /**
     * Returns the organisation's current business date and posting eligibility, or null when the
     * organisation has no initialized business date.
     */
    fun currentBusinessDate(organisationId: UUID): AccountingBusinessDate?
}

/**
 * A tenant's current business date as accounting sees it. [postingAllowed] is the lifecycle
 * module's judgement, so accounting never has to interpret lifecycle's business-date status
 * vocabulary.
 */
data class AccountingBusinessDate(
    val organisationId: UUID,
    val businessDate: LocalDate,
    val postingAllowed: Boolean,
)
