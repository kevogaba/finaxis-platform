package com.finaxis.platform.accounting.domain

/**
 * Audit action names for privileged accounting operations.
 *
 * `HighRiskOperationAuditCoverageTests` requires every HIGH and CRITICAL accounting permission
 * to have a mapped audit action, so adding a high-risk permission without deciding how it is
 * audited fails the build. That test keeps its own copy of the mapping and does not read this
 * object; these constants are what production call sites reference.
 *
 * None of these has a production call site yet: issue #34 seeds the permission catalogue and this
 * registry, while the behaviour that emits them arrives with the chart-of-accounts FSM (#38),
 * fiscal-period lifecycle (#39), reversal semantics (#43), posting-rule lifecycle (#45) and manual
 * journals (#48). That gap is tracked explicitly by the `pendingEnforcement` ratchet in that test
 * rather than left implicit, and the ratchet can only shrink.
 */
object AccountingAuditActions {
    /** A general-ledger account was approved, which activates it. */
    const val GL_ACCOUNT_APPROVE = "gl_account.approve"

    /** A general-ledger account was deactivated, stopping further posting to it. */
    const val GL_ACCOUNT_DEACTIVATE = "gl_account.deactivate"

    /** A fiscal year or period was opened for posting. */
    const val FISCAL_PERIOD_OPEN = "fiscal_period.open"

    /** A fiscal year or period was closed. */
    const val FISCAL_PERIOD_CLOSE = "fiscal_period.close"

    /** A closed fiscal period was reopened. */
    const val FISCAL_PERIOD_REOPEN = "fiscal_period.reopen"

    /**
     * A fiscal period was locked, which is permanent.
     *
     * Distinct from [FISCAL_PERIOD_CLOSE] even though the same permission gates both: a close can
     * be undone by an audited reopen and a lock cannot, so an auditor has to be able to tell the
     * two apart in `audit_event` without joining the transition log.
     */
    const val FISCAL_PERIOD_LOCK = "fiscal_period.lock"

    /** A manual journal was prepared. */
    const val JOURNAL_CREATE_MANUAL = "journal.create_manual"

    /**
     * A draft manual journal's header and lines were replaced by its maker.
     *
     * Audited even though it moves no state, because it is the one manual-journal operation that
     * is neither a transition nor a creation: `submit`, `approve`, `reject` and `cancel` all leave
     * a `manual_journal_transition_log` row, and an amendment left only a bumped `row_version`. It
     * can rewrite every amount, every account and the external reference a later audit event
     * claims, so an investigator reading `journal.create_manual` beside `journal.approve` would
     * otherwise see two entries that never described the same draft.
     */
    const val JOURNAL_AMEND_MANUAL = "journal.amend_manual"

    /** A manual journal was approved, which posts it. */
    const val JOURNAL_APPROVE = "journal.approve"

    /**
     * A checker refusing a manual journal. Audited beside approval because the same CRITICAL
     * permission exercises both, and an adjustment that was refused is as much a control event as
     * one that was posted.
     */
    const val JOURNAL_REJECT = "journal.reject"

    /** A posted journal was reversed by a contra entry. */
    const val JOURNAL_REVERSE = "journal.reverse"

    /** A posting was made into a period earlier than the business date. */
    const val JOURNAL_POST_PRIOR_PERIOD = "journal.post_prior_period"

    /** A posting rule was created. */
    const val POSTING_RULE_CREATE = "posting_rule.create"

    /** A new version of a posting rule was created. */
    const val POSTING_RULE_CREATE_VERSION = "posting_rule.create_version"

    /** A posting-rule version was approved, which activates it. */
    const val POSTING_RULE_APPROVE = "posting_rule.approve"

    /**
     * Retiring a rule version. Distinct from approval because it can stop a product module
     * posting altogether, which is the change an auditor most wants to find by itself.
     */
    const val POSTING_RULE_RETIRE = "posting_rule.retire"

    /** A control-account reconciliation break was resolved or overridden. */
    const val RECONCILIATION_RESOLVE = "reconciliation.resolve"
}
