package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.posting.PostFinancialFactsCommand
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.application.posting.PostingReceipt
import com.finaxis.platform.accounting.application.posting.PostingService
import com.finaxis.platform.accounting.application.posting.ReversePostingCommand
import com.finaxis.platform.accounting.domain.JournalEntryType
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * The public posting API over the internal engine.
 *
 * A product module's intent is turned into legs by the [PostingLegResolver] against the rule
 * version effective on the resolved posting date, and everything else - context, tenant, period,
 * accounts, balance, idempotency, numbering, the verification read - is the engine's.
 *
 * `MANDATORY` propagation, not `REQUIRED`: a posting must join the transaction that owns the
 * business mutation, and a call from outside one is a caller defect rather than something to
 * silently wrap in a transaction of its own (`INV-12`). Accounting's own callers - the reversal
 * and manual-journal services - open the transaction themselves and call the engine directly.
 * The annotation sits on the class so every public method carries it, and so the Kotlin Spring
 * plugin opens the class for the proxy the annotation needs.
 *
 * No `journal.*` permission is checked here. The catalogue has no code for automated posting -
 * `journal.approve` is the manual-journal checker's code - and the product module holds the
 * business permission for the transaction it is recording. What is checked is the context, the
 * tenant's postability, and the break-glass code for a backdated posting.
 */
@Transactional(propagation = Propagation.MANDATORY)
class DefaultPostingService(
    private val engine: PostingEngine,
    private val resolver: PostingLegResolver,
) : PostingService {
    override fun post(command: PostFinancialFactsCommand): PostingReceipt {
        val facts = command.intent as PostingIntent.Facts
        return engine.post(
            LedgerPostingRequest(
                context = command.context,
                source = command.source,
                eventCode = facts.eventCode,
                entryType = JournalEntryType.STANDARD,
                dates = command.dates,
                narrative = command.narrative,
                correctsPostingRequestId = command.correctsPostingRequestId,
            ),
        ) { dates -> resolver.resolve(command.context, facts, dates.postingDate) }
    }

    /**
     * Reversal ships with issue #43, which replaces this body. Until then the contract is
     * published and the bean exists, so a caller gets a stable failure rather than a wiring error.
     */
    override fun reverse(command: ReversePostingCommand): PostingReceipt =
        throw UnsupportedOperationException(
            "Journal reversal is delivered by issue #43; this build has the posting engine only.",
        )
}
