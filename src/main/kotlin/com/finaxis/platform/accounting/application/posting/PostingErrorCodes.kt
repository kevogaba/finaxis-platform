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

    /** The fiscal period is already in the state the caller asked for. */
    const val PERIOD_ALREADY_IN_STATE = "accounting.fiscal_period_already_in_state"

    /** The fiscal period is locked and can no longer change state. */
    const val PERIOD_LOCKED = "accounting.fiscal_period_locked"

    /**
     * A fiscal-period state change matched no row.
     *
     * Not "another operation moved it first": the caller holds `FOR UPDATE` over the row, so no
     * concurrent change is possible. Defensive rather than provokable.
     */
    const val PERIOD_STATE_CHANGE_FAILED = "accounting.fiscal_period_state_change_failed"

    /** The actor reopening a period is the one who closed it. */
    const val PERIOD_SELF_APPROVAL = "accounting.fiscal_period_self_approval"

    /** Reopening a period requires a reason, and the reason cannot be blank. */
    const val PERIOD_REASON_REQUIRED = "accounting.fiscal_period_reason_required"

    /** The close could not take its exclusive lock within the configured bound. */
    const val PERIOD_LOCK_TIMEOUT = "accounting.fiscal_period_lock_timeout"

    /** The requested state change is not a legal transition from the period's current state. */
    const val PERIOD_TRANSITION_NOT_ALLOWED = "accounting.fiscal_period_transition_not_allowed"
}
