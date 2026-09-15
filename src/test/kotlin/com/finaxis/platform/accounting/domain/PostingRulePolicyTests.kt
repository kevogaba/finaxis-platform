package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class PostingRulePolicyTests {
    private val cash = uuidV7()
    private val liability = uuidV7()
    private val fee = uuidV7()

    @Test
    fun `a whole fact on each side allocates the fact exactly`() {
        val legs =
            PostingRulePolicy.allocate(
                listOf(leg(1, PostingSide.DEBIT, cash), leg(2, PostingSide.CREDIT, liability)),
                mapOf("PRINCIPAL" to kes("1000.00")),
            )

        assertEquals(listOf(cash, liability), legs.map { it.accountId })
        assertEquals(listOf(PostingSide.DEBIT, PostingSide.CREDIT), legs.map { it.side })
        assertEquals(listOf("1000.00", "1000.00"), legs.map { it.amount.amount.toPlainString() })
    }

    @Test
    fun `a split rounds half-even at the minor unit and the residual absorbs the remainder`() {
        // 33.333% of 100.00 is 33.33; the residual takes 66.67, so the split reproduces 100.00
        // exactly rather than 99.99 or 100.01.
        val legs =
            PostingRulePolicy.allocate(
                listOf(
                    leg(1, PostingSide.DEBIT, cash),
                    leg(2, PostingSide.CREDIT, fee, percentage = "33.333"),
                    leg(3, PostingSide.CREDIT, liability, residual = true),
                ),
                mapOf("PRINCIPAL" to kes("100.00")),
            )

        assertEquals("33.33", legs[1].amount.amount.toPlainString())
        assertEquals("66.67", legs[2].amount.amount.toPlainString())
        assertEquals(
            legs[0].amount.amount,
            legs[1].amount.amount + legs[2].amount.amount,
            "debit equals the sum of the credits, so the engine's balance check will pass",
        )
    }

    @Test
    fun `a fact the intent does not supply or a fact no leg consumes is a resolution failure`() {
        val legs = listOf(leg(1, PostingSide.DEBIT, cash), leg(2, PostingSide.CREDIT, liability))

        val missing =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.allocate(legs, mapOf("FEE" to kes("1.00")))
            }
        assertEquals(PostingRulePolicy.FACT_UNUSED, missing.code)

        val unused =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.allocate(
                    legs,
                    mapOf("PRINCIPAL" to kes("1.00"), "FEE" to kes("1.00")),
                )
            }
        assertEquals(PostingRulePolicy.FACT_UNUSED, unused.code)

        val none =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.allocate(
                    listOf(
                        leg(1, PostingSide.DEBIT, cash),
                        leg(2, PostingSide.CREDIT, liability, source = "FEE"),
                    ),
                    mapOf("PRINCIPAL" to kes("1.00")),
                )
            }
        assertEquals(PostingRulePolicy.FACT_MISSING, none.code)
    }

    @Test
    fun `a fact's position reference reaches every leg derived from that fact, and only those`() {
        val legs =
            PostingRulePolicy.allocate(
                listOf(
                    leg(1, PostingSide.DEBIT, cash),
                    leg(2, PostingSide.CREDIT, fee, source = "FEE"),
                    leg(3, PostingSide.CREDIT, liability, percentage = "40"),
                    leg(4, PostingSide.CREDIT, liability, residual = true),
                ),
                mapOf(
                    "PRINCIPAL" to kes("100.00", positionReference = "SAV-0001"),
                    "FEE" to kes("100.00"),
                ),
            )

        // Both credit legs of PRINCIPAL carry it, the split and the residual alike, and the debit
        // leg of the same fact does too: the reference identifies the position, not the direction.
        assertEquals(
            listOf("SAV-0001", null, "SAV-0001", "SAV-0001"),
            legs.map { it.subledgerReference },
        )
    }

    @Test
    fun `a position reference the journal line could not store is refused before anything posts`() {
        val legs = listOf(leg(1, PostingSide.DEBIT, cash), leg(2, PostingSide.CREDIT, liability))

        val blank =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.allocate(
                    legs,
                    mapOf("PRINCIPAL" to kes("1.00", positionReference = "   ")),
                )
            }
        assertEquals(PostingRulePolicy.SUBLEDGER_REFERENCE_INVALID, blank.code)

        val tooLong =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.allocate(
                    legs,
                    mapOf(
                        "PRINCIPAL" to
                            kes(
                                "1.00",
                                positionReference =
                                    "S".repeat(
                                        PostingRulePolicy.SUBLEDGER_REFERENCE_MAX_LENGTH + 1,
                                    ),
                            ),
                    ),
                )
            }
        assertEquals(PostingRulePolicy.SUBLEDGER_REFERENCE_INVALID, tooLong.code)

        // Exactly at the bound is storable, so the check is a bound and not an off-by-one.
        PostingRulePolicy.allocate(
            legs,
            mapOf(
                "PRINCIPAL" to
                    kes(
                        "1.00",
                        positionReference =
                            "S".repeat(PostingRulePolicy.SUBLEDGER_REFERENCE_MAX_LENGTH),
                    ),
            ),
        )
    }

    /**
     * The bound counts what the column counts.
     *
     * `chk_journal_line_subledger_reference` is `char_length(...) BETWEEN 1 AND 200`, and
     * PostgreSQL's `char_length` counts characters. A supplementary character - anything outside
     * the Basic Multilingual Plane, which every emoji is - is one character there and two UTF-16
     * units in a Kotlin `String`, so measuring `length` would refuse at 100 of them a reference the
     * column stores happily, and the contract the caller reads would not be the one enforced.
     */
    @Test
    fun `a position reference is bounded in characters rather than UTF-16 units`() {
        val legs = listOf(leg(1, PostingSide.DEBIT, cash), leg(2, PostingSide.CREDIT, liability))
        val maximal = "\uD83C\uDFE6".repeat(PostingRulePolicy.SUBLEDGER_REFERENCE_MAX_LENGTH)

        PostingRulePolicy.allocate(
            legs,
            mapOf("PRINCIPAL" to kes("1.00", positionReference = maximal)),
        )

        val overLong = maximal + "\uD83C\uDFE6"
        val tooLong =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.allocate(
                    legs,
                    mapOf("PRINCIPAL" to kes("1.00", positionReference = overLong)),
                )
            }
        assertEquals(PostingRulePolicy.SUBLEDGER_REFERENCE_INVALID, tooLong.code)
    }

    @Test
    fun `a version needs both sides and a residual wherever a fact is split`() {
        val oneSided =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.requireWellFormed(listOf(leg(1, PostingSide.DEBIT, cash)))
            }
        assertEquals(PostingRulePolicy.LEGS_ONE_SIDED, oneSided.code)

        val splitWithoutResidual =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.requireWellFormed(
                    listOf(
                        leg(1, PostingSide.DEBIT, cash),
                        leg(2, PostingSide.CREDIT, fee, percentage = "10"),
                        leg(3, PostingSide.CREDIT, liability, percentage = "90"),
                    ),
                )
            }
        assertEquals(PostingRulePolicy.SPLIT_WITHOUT_RESIDUAL, splitWithoutResidual.code)

        val overAllocated =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.requireWellFormed(
                    listOf(
                        leg(1, PostingSide.DEBIT, cash),
                        leg(2, PostingSide.CREDIT, fee, percentage = "60"),
                        leg(3, PostingSide.CREDIT, liability, percentage = "60"),
                        leg(4, PostingSide.CREDIT, cash, residual = true),
                    ),
                )
            }
        assertEquals(PostingRulePolicy.SPLIT_WITHOUT_RESIDUAL, overAllocated.code)

        PostingRulePolicy.requireWellFormed(
            listOf(
                leg(1, PostingSide.DEBIT, cash),
                leg(2, PostingSide.CREDIT, fee, percentage = "10"),
                leg(3, PostingSide.CREDIT, liability, residual = true),
            ),
        )
    }

    @Test
    fun `the most specific matching rule wins and ties fail fast`() {
        val any = rule("ANY")
        val byProduct = rule("PROD", productClass = "SAVINGS:REGULAR")
        val byCurrency = rule("CCY", currencyCode = "KES")
        val both = rule("BOTH", productClass = "SAVINGS:REGULAR", currencyCode = "KES")

        assertEquals(
            both,
            selected(listOf(any, byProduct, byCurrency, both), "SAVINGS:REGULAR", "KES"),
        )
        assertEquals(
            byProduct,
            selected(listOf(any, byProduct), "SAVINGS:REGULAR", "USD"),
        )
        assertEquals(
            any,
            selected(listOf(any, byProduct), "LOANS:TERM", "KES"),
        )
        assertIs<RuleSelection.None>(PostingRulePolicy.select(listOf(byProduct), "E", null, "KES"))
        assertIs<RuleSelection.None>(PostingRulePolicy.select(listOf(any), "OTHER", null, "KES"))

        // Two rules pinning one dimension each is a configuration defect, not a coin toss.
        assertIs<RuleSelection.Ambiguous>(
            PostingRulePolicy.select(listOf(byProduct, byCurrency), "E", "SAVINGS:REGULAR", "KES"),
        )
    }

    @Test
    fun `the currency dimension admits anything when unpinned and only its own when pinned`() {
        assertTrue(
            PostingRuleSelector("E").governsCurrency("KES"),
            "an unpinned currency dimension means any currency",
        )
        assertTrue(
            PostingRuleSelector("E", currencyCode = "KES").governsCurrency("KES"),
            "a pinned dimension admits the currency it pins",
        )
        assertFalse(
            PostingRuleSelector("E", currencyCode = "USD").governsCurrency("KES"),
            "a rule pinned to USD can never be selected by a tenant that posts in KES",
        )

        // It judges one dimension, never reachability, and the caller that reports it -
        // PostingRuleVersionPreview.selectorMatchesFunctionalCurrency - is named for that
        // narrowness. Two rules pinning one dimension each are ambiguous and both still pass here.
        val byProduct = rule("PROD", productClass = "SAVINGS:REGULAR")
        val byCurrency = rule("CCY", currencyCode = "KES")
        assertIs<RuleSelection.Ambiguous>(
            PostingRulePolicy.select(listOf(byProduct, byCurrency), "E", "SAVINGS:REGULAR", "KES"),
            "the pair is a configuration defect selection refuses",
        )
        assertTrue(
            byCurrency.selector.governsCurrency("KES"),
            "and the currency dimension of one of them is still, correctly, satisfied",
        )
    }

    @Test
    fun `a residual with nothing left to absorb is refused, not approved and left unpostable`() {
        // 40 + 60 leaves the residual zero, and a zero leg is refused at posting time. A version
        // that can be approved and can never post is worse than one that is refused now.
        val starved =
            assertFailsWith<InvalidOperationException> {
                PostingRulePolicy.requireWellFormed(
                    listOf(
                        leg(1, PostingSide.DEBIT, cash),
                        leg(2, PostingSide.CREDIT, fee, percentage = "40"),
                        leg(3, PostingSide.CREDIT, liability, percentage = "60"),
                        leg(4, PostingSide.CREDIT, cash, residual = true),
                    ),
                )
            }
        assertEquals(PostingRulePolicy.SPLIT_WITHOUT_RESIDUAL, starved.code)

        // A single full-share leg with no residual beside it is still fine.
        PostingRulePolicy.requireWellFormed(
            listOf(leg(1, PostingSide.DEBIT, cash), leg(2, PostingSide.CREDIT, liability)),
        )
    }

    @Test
    fun `a share is rounded once, at the minor unit, not twice`() {
        // 0.01 KES at 50.001% is exactly 0.0050001. Rounding to storage scale first gives
        // 0.005000, which then ties and rounds half-even to 0.00; rounding once gives 0.01.
        // The double-rounded value is a zero leg the engine refuses, or a cent silently moved.
        val legs =
            PostingRulePolicy.allocate(
                listOf(
                    leg(1, PostingSide.DEBIT, cash),
                    leg(2, PostingSide.CREDIT, fee, percentage = "50.001"),
                    leg(3, PostingSide.CREDIT, liability, residual = true),
                ),
                mapOf("PRINCIPAL" to kes("0.01")),
            )

        assertEquals("0.01", legs[1].amount.amount.toPlainString())
        assertEquals(
            legs[0].amount.amount,
            legs[1].amount.amount + legs[2].amount.amount,
            "the split still reproduces the fact exactly",
        )
    }

    private fun leg(
        number: Int,
        side: PostingSide,
        account: UUID,
        source: String = "PRINCIPAL",
        percentage: String = "100",
        residual: Boolean = false,
    ) = PostingRuleLeg(
        legNumber = number,
        side = side,
        accountResolution = AccountResolution.FIXED_ACCOUNT,
        accountId = account,
        amountSource = source,
        amountPercentage = BigDecimal(percentage),
        isResidual = residual,
        narrative = null,
    )

    private fun rule(
        code: String,
        productClass: String? = null,
        currencyCode: String? = null,
    ) = PostingRule(
        id = uuidV7(),
        organisationId = uuidV7(),
        code = code,
        name = code,
        description = null,
        selector = PostingRuleSelector("E", productClass, currencyCode),
    )

    private fun kes(
        amount: String,
        positionReference: String? = null,
    ) = FactAmount(MonetaryAmount(BigDecimal(amount), "KES"), positionReference)

    private fun selected(
        candidates: List<PostingRule>,
        productClass: String?,
        currencyCode: String,
    ) = (
        PostingRulePolicy.select(
            candidates,
            "E",
            productClass,
            currencyCode,
        ) as RuleSelection.Selected
    ).rule
}
