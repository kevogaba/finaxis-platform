package com.finaxis.platform.accounting.application.ledger

import com.finaxis.platform.accounting.application.GlAccountPostingPolicy
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.domain.GlAccount
import com.finaxis.platform.accounting.domain.MoneyPolicy
import com.finaxis.platform.accounting.domain.PostingLeg
import com.finaxis.platform.accounting.domain.PostingSide
import com.finaxis.platform.common.application.InvalidOperationException
import java.math.BigDecimal
import java.util.UUID

/** Legs that passed eligibility, at storage scale, with the totals they balance to. */
data class SettledLegs(
    val legs: List<PostingLeg>,
    val totalDebit: BigDecimal,
    val totalCredit: BigDecimal,
)

/**
 * Whether a set of legs is fit to become a journal: the eligibility pass, with no side effects.
 *
 * A **pure object** on purpose - no store, no lock, no Spring bean - so side-effect freedom is
 * structural rather than a property each call site has to be trusted to preserve. Accounts arrive
 * as an [settle] `accountOf` function, which is what lets [PostingEngine] hand it the snapshots it
 * already locked while
 * [com.finaxis.platform.accounting.application.rules.PostingRuleService.dryRun] hands it an
 * unlocked read. Neither caller can accidentally give this object a write handle, because it has
 * nowhere to put one.
 *
 * It exists because the engine and the dry run must agree. Before it, `dryRun` stopped at leg
 * resolution, so a posting whose facts arrived in a currency the tenant does not post in, or whose
 * rule named an account deactivated since the version was approved, dry-ran clean and posted red
 * (issue #95). One entry point does the whole pass - the minimum-leg count, every per-leg check,
 * and the balance - so no caller can run half of it and believe it ran all of it.
 *
 * **The per-leg order is contract, not incident**: the amount is settled, then its currency is
 * checked, then its account's postability, one leg at a time in leg order. A leg defective in more
 * than one way therefore reports the same code whichever caller found it, which is the whole point
 * of there being one validator. What this does *not* decide is where and when a posting happens -
 * tenant and branch postability, fiscal-period status and posting-date admissibility are the
 * engine's, because a dry run is asked about a configuration and must stay answerable during
 * tenant setup, at close of business, and for a rule that takes effect tomorrow.
 */
object PostingLegsPolicy {
    /** A journal needs a debit and a credit, so fewer than two legs cannot balance. */
    private const val MINIMUM_LEGS = 2

    /**
     * Validates [legs] against [functionalCurrency] and the accounts [accountOf] resolves, and
     * returns them at storage scale with the totals they balance to.
     *
     * Single-currency for now, and deliberately so: ADR 0019 ships no rate table, so a leg in any
     * currency but the functional one is refused rather than converted at a silent rate of one.
     *
     * [accountOf] returning null is an account the caller could not read at all, refused as
     * `ACCOUNT_NOT_POSTABLE` - the same code and the same message the engine has always raised for
     * one it could not lock.
     */
    fun settle(
        legs: List<PostingLeg>,
        functionalCurrency: String,
        accountOf: (UUID) -> GlAccount?,
    ): SettledLegs {
        requireMinimumLegs(legs)
        return requireBalanced(legs.map { settleLeg(it, functionalCurrency, accountOf) })
    }

    private fun requireMinimumLegs(legs: List<PostingLeg>) {
        if (legs.size < MINIMUM_LEGS) {
            throw InvalidOperationException(
                code = PostingErrorCodes.UNBALANCED_POSTING,
                safeDetail = "A journal needs at least two legs.",
            )
        }
    }

    private fun settleLeg(
        leg: PostingLeg,
        functionalCurrency: String,
        accountOf: (UUID) -> GlAccount?,
    ): PostingLeg {
        val amount = MoneyPolicy.requireSettled(leg.amount)
        if (amount.currency != functionalCurrency) {
            throw InvalidOperationException(
                code = PostingErrorCodes.CURRENCY_NOT_SUPPORTED,
                safeDetail =
                    "Postings are accepted in the functional currency $functionalCurrency " +
                        "only; no exchange rate is configured.",
            )
        }
        val account =
            accountOf(leg.accountId)
                ?: throw InvalidOperationException(
                    code = PostingErrorCodes.ACCOUNT_NOT_POSTABLE,
                    safeDetail = "A referenced general-ledger account does not exist.",
                )
        GlAccountPostingPolicy.requirePostable(account)
        return leg.copy(amount = amount)
    }

    private fun requireBalanced(legs: List<PostingLeg>): SettledLegs {
        val debit = legs.filter { it.side == PostingSide.DEBIT }.sumOf { it.amount.amount }
        val credit = legs.filter { it.side == PostingSide.CREDIT }.sumOf { it.amount.amount }
        if (debit.compareTo(credit) != 0 || debit.signum() <= 0) {
            throw InvalidOperationException(
                code = PostingErrorCodes.UNBALANCED_POSTING,
                safeDetail = "Debit and credit totals must be equal and positive.",
            )
        }
        return SettledLegs(legs, debit, credit)
    }
}
