package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.application.InvalidOperationException
import java.math.BigDecimal
import java.util.UUID

/**
 * The pure rules of posting-rule resolution and leg arithmetic, per `INV-2`.
 *
 * No Spring, no persistence. Given the facts an intent supplies and the legs of the version that
 * governs it, [allocate] produces the ledger legs: each leg takes its percentage of its fact,
 * rounded `HALF_EVEN` at the currency's minor unit, and the residual leg of a fact receives the
 * fact total minus the other legs' shares, so the split reproduces the total exactly. The total is
 * never re-rounded.
 */
object PostingRulePolicy {
    /** A leg names a fact the intent did not supply. */
    const val FACT_MISSING = "accounting.posting_rule_fact_missing"

    /** The intent supplies a fact no leg of the version consumes. */
    const val FACT_UNUSED = "accounting.posting_rule_fact_unused"

    /** The intent supplies the same fact code twice; which amount wins would be arbitrary. */
    const val FACT_DUPLICATED = "accounting.posting_rule_fact_duplicated"

    /** A version has no debit leg or no credit leg and can never balance. */
    const val LEGS_ONE_SIDED = "accounting.posting_rule_legs_one_sided"

    /** A fact is split across legs without a residual leg, or the shares exceed the whole. */
    const val SPLIT_WITHOUT_RESIDUAL = "accounting.posting_rule_split_without_residual"

    private val ONE_HUNDRED = BigDecimal(100)

    /**
     * Validates the shape of a version's legs, independent of any intent.
     *
     * A version with no debit or no credit leg can never balance and is refused before approval.
     * A fact taken by more than one leg on one side needs a residual leg among them, or the rounded
     * shares cannot be guaranteed to sum to the fact; and the non-residual shares of a fact must
     * not exceed the whole, or the residual would go negative. A residual leg's own percentage is
     * never applied - it receives whatever the other legs leave (`INV-2`).
     */
    fun requireWellFormed(legs: List<PostingRuleLeg>) {
        val oneSided =
            legs.none { it.side == PostingSide.DEBIT } ||
                legs.none { it.side == PostingSide.CREDIT }
        if (oneSided) {
            throw InvalidOperationException(
                code = LEGS_ONE_SIDED,
                safeDetail = "A posting rule needs at least one debit leg and one credit leg.",
            )
        }
        legs.groupBy { it.amountSource }.forEach { (fact, factLegs) ->
            val bySide = factLegs.groupBy { it.side }
            bySide.values.forEach { sideLegs ->
                val shares = sideLegs.filter { !it.isResidual }.sumOf { it.amountPercentage }
                val residuals = sideLegs.count { it.isResidual }
                // A residual that would receive nothing is not a leg: `allocate` would hand it
                // zero and `MoneyPolicy.requireSettled` refuses zero, so the version would be
                // approvable and unpostable. Its side's other shares must leave a remainder.
                val splitWithoutResidual = sideLegs.size > 1 && residuals == 0
                val residualStarved = residuals > 0 && shares >= ONE_HUNDRED
                if (splitWithoutResidual || shares > ONE_HUNDRED || residualStarved) {
                    throw InvalidOperationException(
                        code = SPLIT_WITHOUT_RESIDUAL,
                        safeDetail =
                            "Fact $fact is split across legs on one side without a residual leg, " +
                                "or its shares leave the residual nothing to absorb.",
                    )
                }
            }
        }
    }

    /**
     * Turns [facts] into ledger legs under [legs], in leg order.
     *
     * Percentages are applied per side of a fact: a fact debited to two accounts and credited to
     * one is three legs, and the residual on each side absorbs that side's rounding.
     */
    fun allocate(
        legs: List<PostingRuleLeg>,
        facts: Map<String, MonetaryAmount>,
    ): List<PostingLeg> {
        val consumed = legs.map { it.amountSource }.toSet()
        val unused = facts.keys - consumed
        if (unused.isNotEmpty()) {
            throw InvalidOperationException(
                code = FACT_UNUSED,
                safeDetail = "The posting rule consumes none of: ${unused.sorted()}.",
            )
        }
        val amounts = mutableMapOf<Int, MonetaryAmount>()
        legs.groupBy { it.amountSource to it.side }.forEach { (key, sideLegs) ->
            val fact =
                facts[key.first]
                    ?: throw InvalidOperationException(
                        code = FACT_MISSING,
                        safeDetail = "The intent supplies no amount for fact ${key.first}.",
                    )
            val settled = MoneyPolicy.requireSettled(fact)
            val nonResidual = sideLegs.filter { !it.isResidual }
            var allocated = BigDecimal.ZERO
            nonResidual.forEach { leg ->
                // movePointLeft(2), not divide(100, STORAGE_SCALE): dividing to six places first
                // and rounding again at the minor unit rounds twice. 0.01 KES at 50.001% is
                // exactly 0.0050001, which rounds once to 0.01 but twice to 0.00 - a leg the
                // engine then refuses as non-positive, or a cent handed to the residual.
                val share =
                    MoneyPolicy.roundToMinorUnit(
                        settled.amount.multiply(leg.amountPercentage).movePointLeft(2),
                        settled.currency,
                    )
                allocated += share
                amounts[leg.legNumber] = MonetaryAmount(share, settled.currency)
            }
            sideLegs.filter { it.isResidual }.forEach { leg ->
                amounts[leg.legNumber] =
                    MonetaryAmount(
                        MoneyPolicy.roundToMinorUnit(settled.amount - allocated, settled.currency),
                        settled.currency,
                    )
            }
        }
        return legs
            .sortedBy { it.legNumber }
            .map { leg ->
                PostingLeg(
                    accountId = leg.accountId,
                    side = leg.side,
                    amount = amounts.getValue(leg.legNumber),
                    narrative = leg.narrative,
                )
            }
    }

    /**
     * Chooses the one rule that governs an intent, or names why none does.
     *
     * The most specific matching selector wins; two matches at the same specificity are a
     * configuration defect and fail fast rather than resolving arbitrarily.
     */
    fun select(
        candidates: List<PostingRule>,
        eventCode: String,
        productClass: String?,
        currencyCode: String,
    ): RuleSelection {
        val matching =
            candidates.filter { it.selector.matches(eventCode, productClass, currencyCode) }
        if (matching.isEmpty()) {
            return RuleSelection.None
        }
        val best = matching.maxOf { it.selector.specificity }
        val winners = matching.filter { it.selector.specificity == best }
        return if (winners.size == 1) {
            RuleSelection.Selected(winners.single())
        } else {
            RuleSelection.Ambiguous(winners.map { it.id })
        }
    }
}

/** The outcome of choosing a rule for an intent. */
sealed interface RuleSelection {
    /** Exactly one rule governs the intent. */
    data class Selected(
        val rule: PostingRule,
    ) : RuleSelection

    /** Several rules match at the same specificity, which is a configuration defect. */
    data class Ambiguous(
        val ruleIds: List<UUID>,
    ) : RuleSelection

    /** No rule matches. */
    data object None : RuleSelection
}
