package com.finaxis.platform.accounting.application.rules

import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.domain.FactAmount
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.common.application.InvalidOperationException

/**
 * The facts a caller supplied, keyed by code, refusing a code supplied twice.
 *
 * `associate` would silently keep the last one, so two `PRINCIPAL` facts would allocate one of the
 * two amounts with nothing to say the other was dropped.
 *
 * It sits here rather than inside [RuleBackedPostingLegResolver] because two entry points now turn
 * caller-supplied facts into [PostingRulePolicy.allocate]'s input - the resolver, on the way to a
 * posting, and [PostingRuleService.previewVersion], which bypasses selection entirely. A second
 * copy of the duplicate check is a second chance for one of them to lose it.
 *
 * The refusal keeps the wording the resolver has always used, so a product module's posting reads
 * the same message after the extraction as before it.
 */
internal fun factsByCode(facts: List<FinancialFact>): Map<String, FactAmount> {
    val duplicates =
        facts
            .groupingBy { it.code }
            .eachCount()
            .filterValues { it > 1 }
            .keys
    if (duplicates.isNotEmpty()) {
        throw InvalidOperationException(
            code = PostingRulePolicy.FACT_DUPLICATED,
            safeDetail = "The intent supplies ${duplicates.sorted()} more than once.",
        )
    }
    return facts.associate { it.code to FactAmount(it.amount, it.positionReference) }
}
