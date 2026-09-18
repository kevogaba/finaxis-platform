package com.finaxis.platform.accounting.domain

import java.util.UUID

/**
 * The kind of journal a header records, matching `chk_journal_entry_type` exactly.
 *
 * There is deliberately no `CORRECTION`: a correction is a [REVERSAL] followed by a fresh
 * [STANDARD] or [MANUAL] posting, per ADR 0020.
 */
enum class JournalEntryType {
    /** Produced from a product module's posting intent through a posting rule. */
    STANDARD,

    /** Produced from a manual journal approved under `journal.approve`. */
    MANUAL,

    /** The equal-and-opposite entry that corrects another journal. */
    REVERSAL,
}

/**
 * The lifecycle of a posting request, matching `chk_posting_request_status` exactly.
 *
 * There is no `REJECTED`: a rejected posting rolls back with the transaction that attempted it,
 * and with it the request row it had claimed. See `docs/database/accounting-erd.md`.
 */
enum class PostingRequestStatus {
    /** Claimed, journal not yet written. Visible only inside the posting transaction. */
    PENDING,

    /** The journal is committed. */
    POSTED,
}

/**
 * One debit or credit the ledger is asked to record, already resolved to a general-ledger account.
 *
 * Product modules never build one of these — they express [com.finaxis.platform.accounting
 * .application.posting.PostingIntent] and accounting resolves it. A leg is what the resolver
 * produces and what accounting's own callers (reversal, manual journals) supply directly.
 *
 * [subledgerReference] is the product-owned position this leg moves, carried on the line for
 * drill-down and control-account reconciliation only; it is descriptive, never a foreign key.
 *
 * [subledgerModule] names the module that **owns** that position, and is set only when the module
 * requesting the posting is not the one that owns it. That happens on a reversal: accounting
 * requests it, but the legs mirror a product module's position, and `journal_line.source_module`
 * names the owner of the position a line moved rather than the requester of the posting. Left null,
 * the line takes the requesting module, which is the same thing whenever a product module posts its
 * own position.
 */
data class PostingLeg(
    val accountId: UUID,
    val side: PostingSide,
    val amount: MonetaryAmount,
    val narrative: String? = null,
    val subledgerReference: String? = null,
    val subledgerModule: String? = null,
) {
    init {
        require(subledgerModule == null || subledgerReference != null) {
            "A subledger module names the owner of a position, so it needs a position to name"
        }
    }
}

/**
 * One financial fact as the allocation rules see it: an amount, and the subsidiary-ledger position
 * that amount moved.
 *
 * The domain counterpart of
 * [com.finaxis.platform.accounting.application.posting.FinancialFact], so
 * [PostingRulePolicy.allocate] can carry [positionReference] onto every leg it derives from this
 * fact without the domain depending on the application layer. Two facts of one posting can name
 * different positions - a principal repayment and the interest accrued on it - which is why the
 * reference belongs on the fact rather than on the posting as a whole.
 */
data class FactAmount(
    val amount: MonetaryAmount,
    val positionReference: String? = null,
)
