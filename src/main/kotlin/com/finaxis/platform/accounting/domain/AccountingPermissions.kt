package com.finaxis.platform.accounting.domain

/**
 * Accounting permission codes, seeded into the `permission` catalogue by
 * `V5__accounting_permission_catalogue.sql`.
 *
 * This is the repository's first central permission-code constants object. Foundation code refers
 * to codes as inline string literals, which works but offers no protection against a typo or a
 * code that was never seeded. `AccountingPermissionCatalogueTests` asserts this object and the
 * database catalogue agree in both directions, closing that gap for accounting from day one.
 *
 * Runtime authorization evaluates these codes, never role names.
 */
object AccountingPermissions {
    /** View the organisation chart of accounts. */
    const val GL_ACCOUNT_VIEW = "gl_account.view"

    /** Create a general-ledger account draft. */
    const val GL_ACCOUNT_CREATE = "gl_account.create"

    /** Amend a general-ledger account. */
    const val GL_ACCOUNT_UPDATE = "gl_account.update"

    /** Submit a general-ledger account for approval. */
    const val GL_ACCOUNT_SUBMIT = "gl_account.submit"

    /** Approve and activate a general-ledger account. */
    const val GL_ACCOUNT_APPROVE = "gl_account.approve"

    /** Stop posting to a general-ledger account. */
    const val GL_ACCOUNT_DEACTIVATE = "gl_account.deactivate"

    /** View fiscal years and periods. */
    const val FISCAL_PERIOD_VIEW = "fiscal_period.view"

    /** Open a fiscal year or period for posting. */
    const val FISCAL_PERIOD_OPEN = "fiscal_period.open"

    /** Close a fiscal year or period. */
    const val FISCAL_PERIOD_CLOSE = "fiscal_period.close"

    /** Reopen a closed fiscal period. Break-glass: granted deliberately, per tenant. */
    const val FISCAL_PERIOD_REOPEN = "fiscal_period.reopen"

    /** View journal entries and lines. */
    const val JOURNAL_VIEW = "journal.view"

    /** Prepare a manual journal. */
    const val JOURNAL_CREATE_MANUAL = "journal.create_manual"

    /** Submit a manual journal for approval. */
    const val JOURNAL_SUBMIT = "journal.submit"

    /** Approve a manual journal, which posts it. */
    const val JOURNAL_APPROVE = "journal.approve"

    /** Reverse a posted journal with a contra entry. */
    const val JOURNAL_REVERSE = "journal.reverse"

    /** Post into a period earlier than the business date. Break-glass. */
    const val JOURNAL_POST_PRIOR_PERIOD = "journal.post_prior_period"

    /** View posting rules and their versions. */
    const val POSTING_RULE_VIEW = "posting_rule.view"

    /** Create a posting rule. */
    const val POSTING_RULE_CREATE = "posting_rule.create"

    /** Create a new version of a posting rule. */
    const val POSTING_RULE_UPDATE = "posting_rule.update"

    /** Submit a posting-rule version for approval. */
    const val POSTING_RULE_SUBMIT = "posting_rule.submit"

    /** Approve a posting-rule version, which activates it. */
    const val POSTING_RULE_APPROVE = "posting_rule.approve"

    /** View control-account reconciliation results. */
    const val RECONCILIATION_VIEW = "reconciliation.view"

    /** Run a control-account reconciliation. */
    const val RECONCILIATION_RUN = "reconciliation.run"

    /** Resolve or override a reconciliation break. */
    const val RECONCILIATION_RESOLVE = "reconciliation.resolve"

    /** View trial balance, GL ledger and financial statements. */
    const val ACCOUNTING_REPORT_VIEW = "accounting_report.view"

    /** Export accounting reports out of the platform. */
    const val ACCOUNTING_REPORT_EXPORT = "accounting_report.export"

    /** Chart-of-accounts lifecycle codes. */
    val CHART_OF_ACCOUNTS: Set<String> =
        setOf(
            GL_ACCOUNT_VIEW,
            GL_ACCOUNT_CREATE,
            GL_ACCOUNT_UPDATE,
            GL_ACCOUNT_SUBMIT,
            GL_ACCOUNT_APPROVE,
            GL_ACCOUNT_DEACTIVATE,
        )

    /** Fiscal-calendar codes, covering both fiscal years and their periods. */
    val FISCAL_PERIOD: Set<String> =
        setOf(
            FISCAL_PERIOD_VIEW,
            FISCAL_PERIOD_OPEN,
            FISCAL_PERIOD_CLOSE,
            FISCAL_PERIOD_REOPEN,
        )

    /** Journal codes, covering manual preparation, approval, posting and reversal. */
    val JOURNAL: Set<String> =
        setOf(
            JOURNAL_VIEW,
            JOURNAL_CREATE_MANUAL,
            JOURNAL_SUBMIT,
            JOURNAL_APPROVE,
            JOURNAL_REVERSE,
            JOURNAL_POST_PRIOR_PERIOD,
        )

    /** Posting-rule codes. */
    val POSTING_RULE: Set<String> =
        setOf(
            POSTING_RULE_VIEW,
            POSTING_RULE_CREATE,
            POSTING_RULE_UPDATE,
            POSTING_RULE_SUBMIT,
            POSTING_RULE_APPROVE,
        )

    /** Control-account reconciliation codes. */
    val RECONCILIATION: Set<String> =
        setOf(RECONCILIATION_VIEW, RECONCILIATION_RUN, RECONCILIATION_RESOLVE)

    /** Accounting reporting codes. */
    val REPORTING: Set<String> = setOf(ACCOUNTING_REPORT_VIEW, ACCOUNTING_REPORT_EXPORT)

    /**
     * Codes that must never appear in a default role bundle. Reopening a closed period and posting
     * into a prior one are exceptional operations, granted deliberately per tenant rather than
     * inherited by anyone who happens to hold an accounting role.
     */
    val BREAK_GLASS: Set<String> = setOf(FISCAL_PERIOD_REOPEN, JOURNAL_POST_PRIOR_PERIOD)

    /** Every accounting permission code. */
    val ALL: Set<String> =
        CHART_OF_ACCOUNTS + FISCAL_PERIOD + JOURNAL + POSTING_RULE + RECONCILIATION + REPORTING
}
