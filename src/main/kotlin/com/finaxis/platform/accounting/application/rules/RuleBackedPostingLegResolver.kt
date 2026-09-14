package com.finaxis.platform.accounting.application.rules

import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.ledger.PostingLegResolver
import com.finaxis.platform.accounting.application.ledger.ResolvedLegs
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.application.posting.PostingIntent
import com.finaxis.platform.accounting.domain.AccountingContext
import com.finaxis.platform.accounting.domain.FactAmount
import com.finaxis.platform.accounting.domain.PostingRule
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.accounting.domain.PostingRuleVersion
import com.finaxis.platform.accounting.domain.RuleSelection
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import java.time.LocalDate

/**
 * The deterministic resolver issue #45 delivers behind the engine's [PostingLegResolver] port.
 *
 * Same inputs, same answer, always: the candidates are every rule for the event, the winner is the
 * most specific selector match, two winners fail fast as a configuration defect, and the version is
 * the one approved version whose window covers the **posting date** - which
 * `ex_posting_rule_version_no_overlap` guarantees is at most one. Then
 * [PostingRulePolicy.allocate] turns the facts into legs. Nothing here writes, so the same
 * resolver serves the administrator's dry run.
 *
 * The currency dimension of the selector is the tenant's functional currency, because the engine
 * accepts postings in that currency only; a rule pinned to another currency can be configured but
 * never selected until multi-currency posting exists.
 */
class RuleBackedPostingLegResolver(
    private val rules: PostingRuleStore,
    private val tenants: AccountingTenantLookup,
) : PostingLegResolver {
    override fun resolve(
        context: AccountingContext,
        intent: PostingIntent.Facts,
        postingDate: LocalDate,
    ): ResolvedLegs {
        val currency =
            tenants.functionalCurrencyOf(context.organisationId)
                ?: throw ConflictException(
                    code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                    safeDetail = "The organisation has no functional currency.",
                )
        val rule = selectRule(context, intent, currency)
        val version = governingVersion(context, rule, postingDate)
        val legs = rules.findLegs(context.organisationId, version.id)
        return ResolvedLegs(
            legs = PostingRulePolicy.allocate(legs, factsByCode(intent)),
            postingRuleVersionId = version.id,
        )
    }

    /**
     * The intent's facts keyed by code, refusing a code supplied twice.
     *
     * `associate` would silently keep the last one, so two `PRINCIPAL` facts would post one of the
     * two amounts with nothing to say the other was dropped.
     */
    private fun factsByCode(intent: PostingIntent.Facts): Map<String, FactAmount> {
        val duplicates =
            intent.facts
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
        return intent.facts.associate {
            it.code to FactAmount(it.amount, it.positionReference)
        }
    }

    private fun selectRule(
        context: AccountingContext,
        intent: PostingIntent.Facts,
        currency: String,
    ): PostingRule {
        val candidates = rules.findRulesForEvent(context.organisationId, intent.eventCode)
        return when (
            val selection =
                PostingRulePolicy.select(
                    candidates,
                    intent.eventCode,
                    intent.productClass,
                    currency,
                )
        ) {
            is RuleSelection.Selected -> {
                selection.rule
            }

            is RuleSelection.Ambiguous -> {
                throw ConflictException(
                    code = PostingErrorCodes.POSTING_RULE_AMBIGUOUS,
                    safeDetail =
                        "Rules ${selection.ruleIds} match event '${intent.eventCode}' at the " +
                            "same specificity; the configuration must be corrected.",
                )
            }

            RuleSelection.None -> {
                throw InvalidOperationException(
                    code = PostingErrorCodes.POSTING_RULE_NOT_FOUND,
                    safeDetail = "No posting rule resolves event '${intent.eventCode}'.",
                )
            }
        }
    }

    private fun governingVersion(
        context: AccountingContext,
        rule: PostingRule,
        postingDate: LocalDate,
    ): PostingRuleVersion {
        val governing =
            rules
                .findVersions(context.organisationId, rule.id)
                .filter { it.governs(postingDate) }
        check(governing.size <= 1) {
            "rule ${rule.id} has ${governing.size} approved versions governing $postingDate; " +
                "ex_posting_rule_version_no_overlap forbids this"
        }
        return governing.firstOrNull()
            ?: throw InvalidOperationException(
                code = PostingErrorCodes.POSTING_RULE_NOT_FOUND,
                safeDetail =
                    "Rule '${rule.code}' has no approved version in force on $postingDate.",
            )
    }
}
