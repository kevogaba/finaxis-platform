package com.finaxis.platform.accounting.application.posting

/**
 * Stable public error codes accounting supplies to the shared
 * [com.finaxis.platform.common.application.ApplicationException] family, so accounting failures
 * surface through the existing RFC 9457 problem contract without a parallel exception hierarchy.
 * That family is sealed, so accounting reuses it rather than extending it.
 */
object PostingErrorCodes {
    /** No active organisation, branch and actor context is installed. */
    const val NO_ACTIVE_CONTEXT = "accounting.no_active_context"

    /** Debit and credit totals do not match. */
    const val UNBALANCED_POSTING = "accounting.unbalanced_posting"

    /** The fiscal period covering the posting date is not open for posting. */
    const val PERIOD_CLOSED = "accounting.fiscal_period_closed"

    /** No fiscal period covers the posting date. Periods are provisioned, never auto-created. */
    const val PERIOD_NOT_FOUND = "accounting.fiscal_period_not_found"

    /** The organisation has no initialized business date, or posting is currently blocked. */
    const val BUSINESS_DATE_UNAVAILABLE = "accounting.business_date_unavailable"

    /** A referenced general-ledger account is unknown, inactive or not postable. */
    const val ACCOUNT_NOT_POSTABLE = "accounting.account_not_postable"

    /** The same durable source reference was already posted. */
    const val DUPLICATE_SOURCE_REFERENCE = "accounting.duplicate_source_reference"
}
