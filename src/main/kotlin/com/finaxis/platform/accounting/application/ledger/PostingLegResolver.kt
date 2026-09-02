package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.PostingLeg
import java.time.LocalDate
import java.util.UUID

/** The legs a resolver produced, and the exact rule version they came from. */
data class ResolvedLegs(
    val legs: List<PostingLeg>,
    val postingRuleVersionId: UUID?,
)

/**
 * Turns a product module's posting intent into general-ledger legs.
 *
 * The extension point issue #41 reserved and issue #45 filled with the rule-backed resolver.
 * Resolution is against the rule version **effective on the posting date**, not today's date,
 * which is what lets a prior-period correction re-post under the rule that was in force when the
 * transaction happened.
 */
fun interface PostingLegResolver {
    /** Resolves [intent] for [context] on [postingDate], or raises a deterministic failure. */
    fun resolve(
        context: AccountingContext,
        intent: PostingIntent.Facts,
        postingDate: LocalDate,
    ): ResolvedLegs
}
