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

    /**
     * The same durable source reference was already posted.
     *
     * Not raised for a faithful retry: a request whose source reference and fingerprint both match
     * a posted request gets that request's receipt back (`INV-7`). Raised only for the case the
     * engine cannot make sense of - a committed request for the reference that is not `POSTED`.
     */
    const val DUPLICATE_SOURCE_REFERENCE = "accounting.duplicate_source_reference"

    /** The same source reference was reused for a materially different request. */
    const val POSTING_REQUEST_CONFLICT = "accounting.posting_request_conflict"

    /** No posting rule resolves the event for this tenant on the posting date. */
    const val POSTING_RULE_NOT_FOUND = "accounting.posting_rule_not_found"

    /** The caller's posting context does not match the active request context. */
    const val CONTEXT_MISMATCH = "accounting.context_mismatch"

    /** The organisation is not in a lifecycle state that permits financial activity. */
    const val ORGANISATION_NOT_POSTABLE = "accounting.organisation_not_postable"

    /** The branch does not exist in the organisation or does not permit financial activity. */
    const val BRANCH_NOT_POSTABLE = "accounting.branch_not_postable"

    /** The organisation has no functional currency to post in. */
    const val FUNCTIONAL_CURRENCY_UNAVAILABLE = "accounting.functional_currency_unavailable"

    /** A leg is denominated in a currency other than the tenant's functional currency. */
    const val CURRENCY_NOT_SUPPORTED = "accounting.currency_not_supported"

    /** The tenant has no `JOURNAL` reference sequence to number the journal from. */
    const val JOURNAL_SEQUENCE_MISSING = "accounting.journal_sequence_missing"

    /** The tenant's functional currency cannot change once a journal has been posted. */
    const val FUNCTIONAL_CURRENCY_FROZEN = "accounting.functional_currency_frozen"

    /** The journal entry does not exist in the tenant. */
    const val JOURNAL_NOT_FOUND = "accounting.journal_not_found"

    /** The journal entry already has a reversal; a journal is reversed at most once. */
    const val JOURNAL_ALREADY_REVERSED = "accounting.journal_already_reversed"

    /** A reversal cannot itself be reversed; post the correction afresh. */
    const val REVERSAL_NOT_REVERSIBLE = "accounting.reversal_not_reversible"

    /** The actor who posted a journal cannot be the one who reverses it. */
    const val JOURNAL_SELF_REVERSAL = "accounting.journal_self_reversal"

    /** Reversing a journal requires a reason, and the reason cannot be blank. */
    const val REVERSAL_REASON_REQUIRED = "accounting.reversal_reason_required"

    /** The reversal reason is longer than the ledger's narrative columns can store. */
    const val REVERSAL_REASON_TOO_LONG = "accounting.reversal_reason_too_long"

    /** A journal is reversed from the branch it was posted to, not from another one. */
    const val REVERSAL_BRANCH_MISMATCH = "accounting.reversal_branch_mismatch"

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
