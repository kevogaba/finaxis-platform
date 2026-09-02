package com.finaxis.platform.accounting.domain

import com.finaxis.platform.common.application.InvalidOperationException
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.Currency

/**
 * The arithmetic rules of ADR 0019, as pure functions.
 *
 * Amounts are stored at scale [STORAGE_SCALE]; anything presented or settled is rounded
 * `HALF_EVEN` at the currency's minor unit; binary floating point never appears. No Spring, no
 * persistence, so every rule is unit-testable and reusable by the posting-rule resolver (#45)
 * without dragging an adapter behind it.
 */
object MoneyPolicy {
    /** `NUMERIC(23, 6)`: six fractional digits cover every ISO 4217 exponent with headroom. */
    const val STORAGE_SCALE = 6

    /** The rounding mode for anything presented or settled. */
    val ROUNDING: RoundingMode = RoundingMode.HALF_EVEN

    /** The currency code is not an ISO 4217 code the JDK knows. */
    const val CURRENCY_INVALID = "accounting.currency_invalid"

    /** The amount is zero or negative; direction carries the sign (`INV-3`). */
    const val AMOUNT_NOT_POSITIVE = "accounting.amount_not_positive"

    /** The amount carries more fractional digits than the currency's minor unit. */
    const val AMOUNT_PRECISION_EXCEEDED = "accounting.amount_precision_exceeded"

    /** The amount has more integer digits than `NUMERIC(23, 6)` can store. */
    const val AMOUNT_TOO_LARGE = "accounting.amount_too_large"

    /** `NUMERIC(23, 6)`: twenty-three digits less the six fractional ones. */
    const val MAX_INTEGER_DIGITS = 17

    /**
     * Validates [amount] as a settled amount in its currency and returns it at storage scale.
     *
     * A settled amount is one a caller asserts as final - a leg the engine is asked to record - so
     * it must already be at the currency's minor unit: rounding it here would silently change what
     * the caller asked for. Intermediate computation, which may legitimately carry more digits, is
     * rounded once through [roundToMinorUnit] before it becomes a leg.
     */
    fun requireSettled(amount: MonetaryAmount): MonetaryAmount {
        val currency = requireCurrency(amount.currency)
        if (amount.amount.signum() <= 0) {
            throw InvalidOperationException(
                code = AMOUNT_NOT_POSITIVE,
                safeDetail = "A posting amount must be positive; direction carries the sign.",
            )
        }
        if (amount.amount.stripTrailingZeros().scale() > currency.defaultFractionDigits) {
            throw InvalidOperationException(
                code = AMOUNT_PRECISION_EXCEEDED,
                safeDetail =
                    "A ${currency.currencyCode} amount may carry at most " +
                        "${currency.defaultFractionDigits} fractional digits.",
            )
        }
        val settled = amount.amount.setScale(STORAGE_SCALE, ROUNDING)
        requireStorable(settled, currency.currencyCode)
        return MonetaryAmount(amount = settled, currency = currency.currencyCode)
    }

    /**
     * Refuses an amount too large for the ledger's `NUMERIC(23, 6)` columns.
     *
     * Without this the value reaches PostgreSQL and comes back as a data-access exception rather
     * than a posting error with a code, which is the difference between a caller that can handle
     * the refusal and one that sees a 500.
     */
    private fun requireStorable(
        settled: BigDecimal,
        currencyCode: String,
    ) {
        if (settled.precision() - settled.scale() > MAX_INTEGER_DIGITS) {
            throw InvalidOperationException(
                code = AMOUNT_TOO_LARGE,
                safeDetail =
                    "A $currencyCode amount may carry at most $MAX_INTEGER_DIGITS integer digits.",
            )
        }
    }

    /** Rounds an intermediate result `HALF_EVEN` at the currency's minor unit, once. */
    fun roundToMinorUnit(
        amount: BigDecimal,
        currencyCode: String,
    ): BigDecimal = amount.setScale(requireCurrency(currencyCode).defaultFractionDigits, ROUNDING)

    /** Resolves [code] against the JDK's ISO 4217 table, which is the only currency authority. */
    fun requireCurrency(code: String): Currency =
        runCatching { Currency.getInstance(code) }.getOrElse {
            throw InvalidOperationException(
                code = CURRENCY_INVALID,
                safeDetail = "'$code' is not an ISO 4217 currency code.",
            )
        }
}
