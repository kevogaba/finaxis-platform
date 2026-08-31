package com.finaxis.platform.accounting.application.posting

import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingSide
import java.time.LocalDate
import java.util.UUID

/**
 * A request to record the accounting effect of one business transaction. [businessDate] is resolved
 * from the tenant's controlled business date when null; [valueDate] is the economic date the caller
 * is asserting. Only the posting date selects a fiscal period - see
 * `docs/architecture/accounting-foundation.md`.
 */
data class PostFinancialFactsCommand(
    val context: AccountingContext,
    val source: AccountingSourceReference,
    val intent: PostingIntent,
    val valueDate: LocalDate,
    val businessDate: LocalDate? = null,
    val narrative: String? = null,
)

/** A request to reverse a posted journal entry with a compensating entry. */
data class ReversePostingCommand(
    val context: AccountingContext,
    val source: AccountingSourceReference,
    val originalJournalEntryId: UUID,
    val reason: String,
    val valueDate: LocalDate? = null,
)

/**
 * What the caller is asserting: semantic financial facts, never general-ledger accounts.
 *
 * Deliberately offers no way to supply explicit double-entry legs. An earlier revision did — a
 * `Legs` variant carrying `glAccountCode` — which contradicted `INV-11` and this module's own
 * `PostingService` contract, both of which say product modules never choose GL accounts. A public
 * escape hatch makes the invariant advisory: any module consuming `accounting::posting` could name
 * accounts directly and bypass posting-rule resolution entirely. Nothing consumed it, so it is
 * removed rather than deprecated.
 *
 * Manual journals are the legitimate case for naming accounts, and they are an
 * accounting-owned operation performed by an accountant rather than a product module expressing
 * intent. Issue #48 gives them their own entry point, gated by `journal.create_manual`; it does
 * not belong on the interface product modules consume.
 */
sealed interface PostingIntent {
    /** Semantic financial facts resolved against a posting rule owned by accounting. */
    data class Facts(
        val eventCode: String,
        val facts: List<FinancialFact>,
    ) : PostingIntent
}

/** One economically meaningful amount produced by a business event, named by [code]. */
data class FinancialFact(
    val code: String,
    val amount: MonetaryAmount,
)
