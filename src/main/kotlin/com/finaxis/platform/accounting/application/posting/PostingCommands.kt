package com.finaxis.platform.accounting.application.posting

import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.AccountingSourceReference
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingDateRequest
import com.finaxis.platform.accounting.domain.PostingSide
import java.time.LocalDate
import java.util.UUID

/**
 * A request to record the accounting effect of one business transaction.
 *
 * [dates] carries the caller-supplied dates, each defaulting to the tenant business date when
 * omitted, so an ordinary same-day posting supplies none of them. Only the posting date selects a
 * fiscal period, and a posting date earlier than the business date is a backdated posting that
 * requires `journal.post_prior_period` - see `docs/architecture/accounting-dates-and-periods.md`.
 *
 * The business date is deliberately **not** a field here. It is read from the tenant's controlled
 * `business_date` table inside the posting transaction and can never be asserted by a caller; an
 * earlier revision of this command accepted one, which `PostingDatePolicy` would then have
 * silently ignored.
 *
 * [context] is likewise not a trust boundary: the implementation must reconcile it against
 * `AccountingContextLookup` and reject a mismatch, or a caller could name another tenant.
 */
data class PostFinancialFactsCommand(
    val context: AccountingContext,
    val source: AccountingSourceReference,
    val intent: PostingIntent,
    val dates: PostingDateRequest = PostingDateRequest(),
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
