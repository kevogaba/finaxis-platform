package com.finaxis.platform.accounting.domain

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import com.finaxis.platform.common.id.uuidV7
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Every accounting-date rule, exercised without Spring, a database or a clock.
 */
class PostingDatePolicyTests {
    @Test
    fun `omitted dates all default to the business date`() {
        val dates = PostingDatePolicy.resolve(PostingDateRequest(), open(TODAY), RECORDED_AT)

        assertEquals(TODAY, dates.businessDate)
        assertEquals(TODAY, dates.transactionDate)
        assertEquals(TODAY, dates.valueDate)
        assertEquals(TODAY, dates.postingDate)
        assertEquals(RECORDED_AT, dates.recordedAt)
    }

    @Test
    fun `a posting date after the business date is rejected`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingDatePolicy.resolve(
                    PostingDateRequest(postingDate = TODAY.plusDays(1)),
                    open(TODAY),
                    RECORDED_AT,
                )
            }

        assertEquals(PostingDatePolicy.POSTING_DATE_IN_FUTURE, failure.code)
    }

    @Test
    fun `a transaction date after the business date is rejected`() {
        val failure =
            assertFailsWith<InvalidOperationException> {
                PostingDatePolicy.resolve(
                    PostingDateRequest(transactionDate = TODAY.plusDays(1)),
                    open(TODAY),
                    RECORDED_AT,
                )
            }

        assertEquals(PostingDatePolicy.TRANSACTION_DATE_IN_FUTURE, failure.code)
    }

    @Test
    fun `a value date after the posting date is allowed`() {
        // Deliberate: a value date is when the economic effect applies - future-dated interest is
        // ordinary - and it never selects a fiscal period.
        val dates =
            PostingDatePolicy.resolve(
                PostingDateRequest(valueDate = TODAY.plusMonths(1)),
                open(TODAY),
                RECORDED_AT,
            )

        assertEquals(TODAY.plusMonths(1), dates.valueDate)
        assertEquals(TODAY, dates.postingDate)
    }

    @Test
    fun `a value date before the posting date is allowed`() {
        val dates =
            PostingDatePolicy.resolve(
                PostingDateRequest(valueDate = TODAY.minusMonths(1)),
                open(TODAY),
                RECORDED_AT,
            )

        assertEquals(TODAY.minusMonths(1), dates.valueDate)
    }

    @Test
    fun `a current-dated posting is rejected when the business date is not open`() {
        val failure =
            assertFailsWith<ConflictException> {
                PostingDatePolicy.resolve(PostingDateRequest(), closed(TODAY), RECORDED_AT)
            }

        assertEquals(PostingDatePolicy.BUSINESS_DATE_NOT_OPEN, failure.code)
    }

    @Test
    fun `a backdated posting is allowed while the business date is not open`() {
        // Close-of-business must not deadlock corrections: a backdated posting into a still-open
        // prior period stays legal, gated on permission by the caller.
        val dates =
            PostingDatePolicy.resolve(
                PostingDateRequest(postingDate = TODAY.minusDays(1)),
                closed(TODAY),
                RECORDED_AT,
            )

        assertEquals(TODAY.minusDays(1), dates.postingDate)
    }

    @Test
    fun `classification distinguishes current backdated and future-dated`() {
        assertEquals(PostingDateClassification.CURRENT, PostingDatePolicy.classify(TODAY, TODAY))
        assertEquals(
            PostingDateClassification.BACKDATED,
            PostingDatePolicy.classify(TODAY.minusDays(1), TODAY),
        )
        assertEquals(
            PostingDateClassification.FUTURE_DATED,
            PostingDatePolicy.classify(TODAY.plusDays(1), TODAY),
        )
    }

    private fun open(date: LocalDate) = AccountingBusinessDate(ORGANISATION_ID, date, true)

    private fun closed(date: LocalDate) = AccountingBusinessDate(ORGANISATION_ID, date, false)

    private companion object {
        val ORGANISATION_ID = uuidV7()
        val TODAY: LocalDate = LocalDate.of(2026, 8, 31)
        val RECORDED_AT: Instant = Instant.parse("2026-08-31T09:15:00Z")
    }
}
