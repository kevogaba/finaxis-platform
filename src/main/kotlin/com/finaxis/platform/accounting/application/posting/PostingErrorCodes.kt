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

    /** A correction names a posting request that does not exist in the tenant. */
    const val CORRECTION_TARGET_NOT_FOUND = "accounting.correction_target_not_found"

    /** A correction names a posting request whose journal has not been reversed. */
    const val CORRECTION_TARGET_NOT_REVERSED = "accounting.correction_target_not_reversed"

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

    /**
     * The base-currency change could not take its exclusive lock within the configured bound.
     *
     * Retryable, and deliberately bounded: a queued exclusive request also makes every new posting
     * for the tenant queue behind it, so a change that cannot get in must give up rather than stall
     * the ledger.
     */
    const val FUNCTIONAL_CURRENCY_LOCK_TIMEOUT = "accounting.functional_currency_lock_timeout"

    /**
     * The tenant's functional currency changed between this posting reading it and locking it.
     *
     * Retryable, and only ever reachable for a tenant's *first* posting, since the currency is
     * frozen the moment one journal exists. The claim is fingerprinted against the currency read
     * before the lock, so a posting that finds a different one under the lock cannot go on to use
     * either value honestly: it rolls back - taking its claim with it - and the retry fingerprints
     * against the currency that won.
     *
     * **The window this guarded is closed, and not by this code.** The read under the lock is now
     * a locking read of the organisation row, so a base-currency change committed after the
     * posting's snapshot makes that read fail with a serialization error and the transaction
     * aborts; the retry fingerprints against the currency that won. That abort, not this code, is
     * what a caller racing a base-currency change sees.
     *
     * This code therefore does not fire on the posting path in normal operation, and no caller
     * should be written expecting it: at `SERIALIZABLE` the two reads come from one snapshot and
     * always agree, and anything that changed after that snapshot has already aborted the
     * transaction before the comparison is reached. It is published and kept because it states the
     * invariant at the point the invariant is decided, and because it would fire below
     * `SERIALIZABLE`, which the posting path's snapshot guard forbids.
     */
    const val FUNCTIONAL_CURRENCY_CHANGED = "accounting.functional_currency_changed"

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

    /** Two rules match the intent at the same specificity; the configuration must be corrected. */
    const val POSTING_RULE_AMBIGUOUS = "accounting.posting_rule_ambiguous"

    /** A rule with the same code or selector already exists in the tenant. */
    const val POSTING_RULE_DUPLICATE = "accounting.posting_rule_duplicate"

    /** The posting-rule version does not exist in the tenant. */
    const val POSTING_RULE_VERSION_NOT_FOUND = "accounting.posting_rule_version_not_found"

    /** The rule already has a draft or pending version. */
    const val POSTING_RULE_VERSION_IN_PROGRESS = "accounting.posting_rule_version_in_progress"

    /** Only a DRAFT version can be amended; an approved version is frozen. */
    const val POSTING_RULE_VERSION_NOT_EDITABLE = "accounting.posting_rule_version_not_editable"

    /** The version changed while it was being amended or moved. */
    const val POSTING_RULE_VERSION_STALE = "accounting.posting_rule_version_stale"

    /** The requested state change is not a legal transition from the version's current state. */
    const val POSTING_RULE_TRANSITION_NOT_ALLOWED = "accounting.posting_rule_transition_not_allowed"

    /** The actor who submitted or activated a version cannot also approve or retire it. */
    const val POSTING_RULE_SELF_APPROVAL = "accounting.posting_rule_self_approval"

    /** A version's legs must be numbered 1..n without gaps and name existing postable accounts. */
    const val POSTING_RULE_LEGS_INVALID = "accounting.posting_rule_legs_invalid"

    /** Retiring a version needs the last date it governs. */
    const val POSTING_RULE_EFFECTIVE_TO_REQUIRED = "accounting.posting_rule_effective_to_required"

    /** A version's window would leave dates it governed with no version, or end before it began. */
    const val POSTING_RULE_WINDOW_INVALID = "accounting.posting_rule_window_invalid"

    /** This posting-rule operation requires a reason, and the reason cannot be blank. */
    const val POSTING_RULE_REASON_REQUIRED = "accounting.posting_rule_reason_required"

    /** A control account is postable, names one sub-ledger kind, and refuses manual posting. */
    const val CONTROL_ACCOUNT_INVALID = "accounting.control_account_invalid"

    /** The organisation already has a control account for that sub-ledger class. */
    const val CONTROL_ACCOUNT_DUPLICATE = "accounting.control_account_duplicate"

    /** A control account with posted journal lines cannot give up its sub-ledger class. */
    const val CONTROL_ACCOUNT_HAS_HISTORY = "accounting.control_account_has_history"

    /** The branch named by a reconciliation scope does not belong to the organisation. */
    const val BRANCH_NOT_IN_ORGANISATION = "accounting.branch_not_in_organisation"

    /**
     * The transaction is weaker than the operation declared.
     *
     * Raised when the isolation level actually in force is below the one the operation asked for -
     * Spring drops the declaration silently when the method joins a transaction already open, so
     * the annotation alone is a request rather than a guarantee. Three operations ask, and not for
     * the same thing. The control-account proof and reading a manual journal need at least
     * `REPEATABLE READ`, so that two reads describe one instant: a pair of general-ledger and
     * sub-ledger aggregates, and a draft's header beside its lines. A posting needs `SERIALIZABLE`,
     * which is stronger than snapshot stability and is what the posting rules were decided under;
     * accepting `REPEATABLE READ` there would certify exactly the downgrade the decision forbids.
     *
     * On the posting path this is the *only* enforcement available. `PostingService.post` is
     * `MANDATORY`, so the transaction being judged belongs to the caller and no annotation of
     * accounting's can raise it - the guard refuses a misconfigured caller rather than quietly
     * fixing one. The refusal names both the level demanded and the level in force, because
     * "this transaction is repeatable read", read against an operation whose declaration says
     * otherwise, looks like a false positive.
     */
    const val SNAPSHOT_ISOLATION_UNAVAILABLE = "accounting.snapshot_isolation_unavailable"

    /** A reconciliation proves a date that has happened, not one that has not. */
    const val RECONCILIATION_DATE_IN_FUTURE = "accounting.reconciliation_date_in_future"

    /** A sub-ledger balance carries more precision than the evidence row can store. */
    const val SUBLEDGER_BALANCE_PRECISION = "accounting.subledger_balance_precision"

    /** The account is not a control account, so there is no sub-ledger to prove it against. */
    const val NOT_A_CONTROL_ACCOUNT = "accounting.not_a_control_account"

    /** No module implements the sub-ledger proof for this control class yet. */
    const val SUBLEDGER_PROVIDER_MISSING = "accounting.subledger_provider_missing"

    /** The reconciliation run does not exist in the tenant. */
    const val RECONCILIATION_RUN_NOT_FOUND = "accounting.reconciliation_run_not_found"

    /** Only a BREAK can be resolved. */
    const val RECONCILIATION_NOT_A_BREAK = "accounting.reconciliation_not_a_break"

    /** The actor who ran a reconciliation cannot resolve its break. */
    const val RECONCILIATION_SELF_RESOLUTION = "accounting.reconciliation_self_resolution"

    /** Resolving a reconciliation break requires a reason. */
    const val RECONCILIATION_REASON_REQUIRED = "accounting.reconciliation_reason_required"

    /** A reconciliation tolerance cannot be negative. */
    const val RECONCILIATION_TOLERANCE_INVALID = "accounting.reconciliation_tolerance_invalid"

    /** The manual journal does not exist in the tenant. */
    const val MANUAL_JOURNAL_NOT_FOUND = "accounting.manual_journal_not_found"

    /** Only a DRAFT manual journal can be amended. */
    const val MANUAL_JOURNAL_NOT_EDITABLE = "accounting.manual_journal_not_editable"

    /** The manual journal changed while it was being amended or moved. */
    const val MANUAL_JOURNAL_STALE = "accounting.manual_journal_stale"

    /** The requested state change is not a legal transition from the journal's current state. */
    const val MANUAL_JOURNAL_TRANSITION_NOT_ALLOWED =
        "accounting.manual_journal_transition_not_allowed"

    /** Amending, submitting or cancelling a manual journal is the maker's act alone. */
    const val MANUAL_JOURNAL_NOT_THE_MAKER = "accounting.manual_journal_not_the_maker"

    /** The actor who submitted a manual journal cannot approve it. */
    const val MANUAL_JOURNAL_SELF_APPROVAL = "accounting.manual_journal_self_approval"

    /** Lines must number at least two, be numbered 1..n, and name eligible accounts. */
    const val MANUAL_JOURNAL_LINES_INVALID = "accounting.manual_journal_lines_invalid"

    /** An external reference is blank or longer than the draft column can store. */
    const val MANUAL_JOURNAL_EXTERNAL_REFERENCE_INVALID =
        "accounting.manual_journal_external_reference_invalid"

    /** A manual journal needs a title and a reason, and a rejection needs a reason. */
    const val MANUAL_JOURNAL_REASON_REQUIRED = "accounting.manual_journal_reason_required"

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
