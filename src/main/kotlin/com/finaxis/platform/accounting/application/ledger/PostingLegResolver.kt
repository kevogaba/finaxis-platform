package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.common.application.InvalidOperationException
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
 * The extension point issue #41 reserves for the posting-rule resolver of issue #45. Resolution is
 * against the rule version **effective on the posting date**, not today's date, which is what lets
 * a prior-period correction re-post under the rule that was in force when the transaction
 * happened.
 */
fun interface PostingLegResolver {
    /** Resolves [intent] for [context] on [postingDate], or raises a deterministic failure. */
    fun resolve(
        context: AccountingContext,
        intent: PostingIntent.Facts,
        postingDate: LocalDate,
    ): ResolvedLegs
}

/**
 * The resolver in force until issue #45 ships posting rules: it refuses every intent.
 *
 * Deliberately a bean rather than an absence. Without a resolver the engine has no bean either and
 * the application context fails to start for the whole platform; with this one the engine is live
 * for accounting's own callers, and a product module that posts before rules exist is told so with
 * a stable code rather than a wiring error.
 */
class UnconfiguredPostingLegResolver : PostingLegResolver {
    override fun resolve(
        context: AccountingContext,
        intent: PostingIntent.Facts,
        postingDate: LocalDate,
    ): ResolvedLegs =
        throw InvalidOperationException(
            code = PostingErrorCodes.POSTING_RULE_NOT_FOUND,
            safeDetail =
                "No posting rule resolves event '${intent.eventCode}'; posting rules are not " +
                    "configured for this tenant.",
        )
}
