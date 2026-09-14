package com.finaxis.platform.accounting.config

import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The operational bounds refuse a value that would silently disable the bound it configures.
 *
 * `SET LOCAL lock_timeout` is expressed in milliseconds and the conversion truncates, so a positive
 * but sub-millisecond duration arrives as `0` - which PostgreSQL reads as *no timeout at all*. A
 * deployment that wrote `500us` meaning "fail almost immediately" would get the exact opposite: an
 * unbounded wait, and for the currency lock that means every new posting for the tenant queued
 * behind it. Rejecting it here turns a silent inversion into a startup failure.
 */
class AccountingPropertiesTests {
    @Test
    fun `a sub-millisecond bound is refused rather than truncated to no bound at all`() {
        listOf(Duration.ofNanos(500_000), Duration.ofNanos(1)).forEach { belowOneMilli ->
            assertEquals(0L, belowOneMilli.toMillis(), "the premise: this truncates to zero")

            assertFailsWith<IllegalArgumentException> {
                AccountingProperties(functionalCurrencyLockTimeout = belowOneMilli)
            }
            assertFailsWith<IllegalArgumentException> {
                AccountingProperties(fiscalPeriodCloseLockTimeout = belowOneMilli)
            }
        }
    }

    @Test
    fun `zero and negative bounds are refused`() {
        listOf(Duration.ZERO, Duration.ofSeconds(-1)).forEach { invalid ->
            assertFailsWith<IllegalArgumentException> {
                AccountingProperties(functionalCurrencyLockTimeout = invalid)
            }
            assertFailsWith<IllegalArgumentException> {
                AccountingProperties(fiscalPeriodCloseLockTimeout = invalid)
            }
        }
    }

    @Test
    fun `exactly one millisecond is the smallest bound that survives the conversion`() {
        val properties = AccountingProperties(functionalCurrencyLockTimeout = Duration.ofMillis(1))

        assertEquals(1L, properties.functionalCurrencyLockTimeout.toMillis())
    }

    @Test
    fun `the shipped defaults are expressible in the unit they are sent in`() {
        val properties = AccountingProperties()

        assertEquals(10_000L, properties.fiscalPeriodCloseLockTimeout.toMillis())
        assertEquals(10_000L, properties.functionalCurrencyLockTimeout.toMillis())
    }
}
