package com.finaxis.platform.accounting.domain

import com.finaxis.platform.accounting.AccountingBusinessDate
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.application.InvalidOperationException
import java.time.Instant
import java.time.LocalDate

/**
 * Derives and validates the dates of a financial transaction from the tenant business date and the
 * caller's optional overrides.
 *
 * Pure by design: no persistence, no clock, no permission checks, so every rule in
 * `docs/architecture/accounting-dates-and-periods.md` is unit-testable without Spring. The
 * permission gate for a backdated posting lives in
 * [com.finaxis.platform.accounting.application.PostingPeriodResolver], which knows the actor.
 */
object PostingDatePolicy {
    /** Raised when a posting date follows the tenant business date. */
    const val POSTING_DATE_IN_FUTURE = "accounting.posting_date_in_future"

    /** Raised when a transaction date follows the tenant business date. */
    const val TRANSACTION_DATE_IN_FUTURE = "accounting.transaction_date_in_future"

    /** Raised when the tenant business date is not open for posting. */
    const val BUSINESS_DATE_NOT_OPEN = "accounting.business_date_not_open"

    /**
     * Resolves the full [AccountingDates] set.
     *
     * Rejects a posting date after the business date, a transaction date after the business date,
     * and a current-dated posting while the business date is not open for posting. A backdated
     * posting is allowed here and gated on permission by the caller, so close-of-business does not
     * deadlock corrections.
     */
    fun resolve(
        request: PostingDateRequest,
        businessDate: AccountingBusinessDate,
        recordedAt: Instant,
    ): AccountingDates {
        val today = businessDate.businessDate
        val postingDate = request.postingDate ?: today
        val transactionDate = request.transactionDate ?: today
        rejectFuturePostingDate(postingDate, today)
        rejectFutureTransactionDate(transactionDate, today)
        rejectClosedBusinessDate(postingDate, today, businessDate.postingAllowed)
        return AccountingDates(
            businessDate = today,
            transactionDate = transactionDate,
            valueDate = request.valueDate ?: today,
            postingDate = postingDate,
            recordedAt = recordedAt,
        )
    }

    /** Classifies [postingDate] against [businessDate] for the prior-period permission gate. */
    fun classify(
        postingDate: LocalDate,
        businessDate: LocalDate,
    ): PostingDateClassification =
        when {
            postingDate.isAfter(businessDate) -> PostingDateClassification.FUTURE_DATED
            postingDate.isBefore(businessDate) -> PostingDateClassification.BACKDATED
            else -> PostingDateClassification.CURRENT
        }

    private fun rejectFuturePostingDate(
        postingDate: LocalDate,
        businessDate: LocalDate,
    ) {
        if (postingDate.isAfter(businessDate)) {
            throw InvalidOperationException(
                code = POSTING_DATE_IN_FUTURE,
                safeDetail = "A posting date may not follow the organisation business date.",
            )
        }
    }

    private fun rejectFutureTransactionDate(
        transactionDate: LocalDate,
        businessDate: LocalDate,
    ) {
        if (transactionDate.isAfter(businessDate)) {
            throw InvalidOperationException(
                code = TRANSACTION_DATE_IN_FUTURE,
                safeDetail = "A transaction date may not follow the organisation business date.",
            )
        }
    }

    private fun rejectClosedBusinessDate(
        postingDate: LocalDate,
        businessDate: LocalDate,
        postingAllowed: Boolean,
    ) {
        val isCurrent = classify(postingDate, businessDate) == PostingDateClassification.CURRENT
        if (isCurrent && !postingAllowed) {
            throw ConflictException(
                code = BUSINESS_DATE_NOT_OPEN,
                safeDetail = "The organisation business date is not open for posting.",
            )
        }
    }
}
