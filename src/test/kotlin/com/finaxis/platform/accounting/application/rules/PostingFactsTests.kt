package com.finaxis.platform.accounting.application.rules

import com.finaxis.platform.accounting.application.posting.FinancialFact
import com.finaxis.platform.accounting.domain.MonetaryAmount
import com.finaxis.platform.accounting.domain.PostingRulePolicy
import com.finaxis.platform.common.application.InvalidOperationException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The duplicate-fact refusal both entry points now share.
 *
 * It was private to `RuleBackedPostingLegResolver` until `PostingRuleService.previewVersion` needed
 * the same conversion. A second copy would have been a second chance to lose the check, and losing
 * it is quiet: `associate` keeps the last of two `PRINCIPAL` facts and posts one of the amounts
 * with nothing to say the other was dropped.
 */
class PostingFactsTests {
    @Test
    fun `facts are keyed by code, carrying each fact's own position reference`() {
        val byCode =
            factsByCode(
                listOf(
                    FinancialFact("PRINCIPAL", kes("1000.00"), "SAV-1"),
                    FinancialFact("FEE", kes("25.00")),
                ),
            )

        assertEquals(setOf("PRINCIPAL", "FEE"), byCode.keys, "every fact is reachable by its code")
        assertEquals(
            "SAV-1",
            byCode.getValue("PRINCIPAL").positionReference,
            "a reference travels with the fact it was supplied on, not with the posting",
        )
        assertEquals(
            null,
            byCode.getValue("FEE").positionReference,
            "and a fact that moves no position keeps none",
        )
    }

    @Test
    fun `a code supplied twice is refused rather than silently resolved to one of them`() {
        val refused =
            assertFailsWith<InvalidOperationException> {
                factsByCode(
                    listOf(
                        FinancialFact("PRINCIPAL", kes("1000.00")),
                        FinancialFact("PRINCIPAL", kes("2000.00")),
                    ),
                )
            }

        assertEquals(
            PostingRulePolicy.FACT_DUPLICATED,
            refused.code,
            "which amount wins would be arbitrary, so neither does",
        )
    }

    private fun kes(amount: String) = MonetaryAmount(BigDecimal(amount), "KES")
}
