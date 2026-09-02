package com.finaxis.platform.accounting.application

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.common.application.InvalidOperationException

/**
 * Whether a GL account may receive a posting.
 *
 * The first thrower of [PostingErrorCodes.ACCOUNT_NOT_POSTABLE], which issue #31 published as part
 * of the public error contract and which nothing had raised.
 *
 * **It enforces eligibility, not receipt.** Issue #37's acceptance criterion reads *"only ACTIVE,
 * postable accounts may receive journal lines"*, and that sentence is about `journal_line`, which
 * issue #40 creates. What can be enforced today is that an account offered to the posting path is
 * refused unless it is `ACTIVE` and `POSTABLE`; the posting engine (#41) is what calls this before
 * it writes a line. Saying so is better than a test asserting a rule no code path can break yet.
 *
 * Lives in the application layer rather than beside [
 * com.finaxis.platform.accounting.domain.ChartHierarchyPolicy] because the code it raises is part
 * of the module's published application contract, and the domain does not depend on the
 * application.
 */
object GlAccountPostingPolicy {
    /** Rejects an account that is not both `ACTIVE` and `POSTABLE`. */
    fun requirePostable(account: GlAccount) {
        if (!account.isPostable) {
            throw notPostable("Account ${account.code} is not an active posting account.")
        }
    }

    /**
     * Rejects a manual journal against an account that has not opted into manual posting.
     *
     * `manual_posting_allowed` defaults to false in the schema, so an account fed by a posting
     * rule or a subsidiary ledger refuses hand-written entries until someone deliberately opens it.
     */
    fun requireManualPostingAllowed(account: GlAccount) {
        requirePostable(account)
        if (account.isControlAccount) {
            throw notPostable(
                "Account ${account.code} is a control account; a manual entry would break its " +
                    "sub-ledger reconciliation.",
            )
        }
        if (!account.manualPostingAllowed) {
            throw notPostable("Account ${account.code} does not accept manual journal entries.")
        }
    }

    private fun notPostable(detail: String) =
        InvalidOperationException(
            code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
            safeDetail = detail,
        )
}
