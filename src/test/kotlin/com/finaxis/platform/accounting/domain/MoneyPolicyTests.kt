package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.application.InvalidOperationException
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class MoneyPolicyTests {
    @Test
    fun `a settled amount is carried at storage scale and its currency canonicalised`() {
        val settled = MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("1250.5"), "KES"))

        assertEquals(BigDecimal("1250.500000"), settled.amount)
        assertEquals(MoneyPolicy.STORAGE_SCALE, settled.amount.scale())
        assertEquals("KES", settled.currency)
    }

    @Test
    fun `zero and negative amounts are refused because direction carries the sign`() {
        listOf(BigDecimal.ZERO, BigDecimal("-0.01"), BigDecimal("0.000000")).forEach { amount ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    MoneyPolicy.requireSettled(MonetaryAmount(amount, "KES"))
                }
            assertEquals(MoneyPolicy.AMOUNT_NOT_POSITIVE, failure.code)
        }
    }

    @Test
    fun `more fractional digits than the currency's minor unit are refused, not rounded`() {
        // Rounding a *settled* amount would silently change what the caller asked for. Rounding
        // belongs to the resolver, once, before an amount becomes a leg.
        val failure =
            assertFailsWith<InvalidOperationException> {
                MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("10.001"), "KES"))
            }
        assertEquals(MoneyPolicy.AMOUNT_PRECISION_EXCEEDED, failure.code)

        // A zero-decimal currency accepts whole units only; a four-decimal one accepts four.
        assertFailsWith<InvalidOperationException> {
            MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("10.5"), "JPY"))
        }
        assertEquals(
            BigDecimal("10.123400"),
            MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("10.1234"), "CLF")).amount,
        )
    }

    @Test
    fun `an amount wider than the ledger column is refused with a code, not by the database`() {
        // NUMERIC(23, 6) leaves seventeen integer digits. Eighteen would reach PostgreSQL and come
        // back as a data-access exception, which no caller can act on.
        val failure =
            assertFailsWith<InvalidOperationException> {
                MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("1" + "0".repeat(17)), "KES"))
            }
        assertEquals(MoneyPolicy.AMOUNT_TOO_LARGE, failure.code)

        // The widest storable amount is still accepted.
        assertEquals(
            BigDecimal("99999999999999999.000000"),
            MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("9".repeat(17)), "KES")).amount,
        )
    }

    @Test
    fun `trailing zeros beyond the minor unit are not precision`() {
        assertEquals(
            BigDecimal("10.500000"),
            MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("10.5000"), "KES")).amount,
        )
    }

    @Test
    fun `an unknown currency code is refused`() {
        listOf("kes", "KE", "XXXX", "").forEach { code ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal.ONE, code))
                }
            assertEquals(MoneyPolicy.CURRENCY_INVALID, failure.code)
        }
    }

    @Test
    fun `a settlement currency must be one the JDK knows`() {
        listOf("ZZZ", "kes", "KE", "XXXX", "").forEach { code ->
            val failure =
                assertFailsWith<InvalidOperationException> {
                    MoneyPolicy.requireSettlementCurrency(code)
                }
            assertEquals(MoneyPolicy.CURRENCY_INVALID, failure.code)
        }
        assertEquals("KES", MoneyPolicy.requireSettlementCurrency("KES").currencyCode)
        assertEquals("USD", MoneyPolicy.requireSettlementCurrency("USD").currencyCode)
        // A zero-decimal currency has a minor unit of zero digits, which is still a minor unit.
        assertEquals(0, MoneyPolicy.requireSettlementCurrency("JPY").defaultFractionDigits)
    }

    @Test
    fun `a settlement currency must have a minor unit, so XXX and the metals are refused`() {
        // These four ARE known to the JDK, so requireCurrency accepts them - and that is exactly
        // the trap. Their defaultFractionDigits is -1, so requireSettled refuses any amount whose
        // scale exceeds -1, which is every ordinary amount: 5 has scale 0. A tenant holding one as
        // its functional currency validates, activates, and can then never post.
        listOf("XXX", "XAU", "XAG", "XPT").forEach { code ->
            assertEquals(-1, MoneyPolicy.requireCurrency(code).defaultFractionDigits)
            val failure =
                assertFailsWith<InvalidOperationException> {
                    MoneyPolicy.requireSettlementCurrency(code)
                }
            assertEquals(MoneyPolicy.CURRENCY_INVALID, failure.code)
        }
        assertFailsWith<InvalidOperationException> {
            MoneyPolicy.requireSettled(MonetaryAmount(BigDecimal("5"), "XAU"))
        }
    }

    @Test
    fun `intermediate results round half-even at the minor unit`() {
        assertEquals(BigDecimal("2.50"), MoneyPolicy.roundToMinorUnit(BigDecimal("2.505"), "KES"))
        assertEquals(BigDecimal("2.52"), MoneyPolicy.roundToMinorUnit(BigDecimal("2.515"), "KES"))
        // Half-even, not half-up: 2.5 rounds to the even neighbour 2, 3.5 to 4.
        assertEquals(BigDecimal("2"), MoneyPolicy.roundToMinorUnit(BigDecimal("2.5"), "JPY"))
        assertEquals(BigDecimal("4"), MoneyPolicy.roundToMinorUnit(BigDecimal("3.5"), "JPY"))
    }
}
