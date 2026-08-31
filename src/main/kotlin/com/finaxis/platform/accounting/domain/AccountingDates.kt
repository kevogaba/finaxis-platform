package com.finaxis.platform.accounting.domain

import java.time.Instant
import java.time.LocalDate

/**
 * The four dates a financial transaction carries, plus its system timestamp.
 *
 * Only [postingDate] selects a fiscal period. Every other date is business or technical metadata.
 * See `docs/architecture/accounting-dates-and-periods.md`.
 */
data class AccountingDates(
    val businessDate: LocalDate,
    val transactionDate: LocalDate,
    val valueDate: LocalDate,
    val postingDate: LocalDate,
    val recordedAt: Instant,
)

/** How a posting date relates to the tenant business date. */
enum class PostingDateClassification {
    /** The posting date equals the tenant business date. */
    CURRENT,

    /** The posting date precedes the tenant business date. */
    BACKDATED,

    /** The posting date follows the tenant business date, which is never accepted. */
    FUTURE_DATED,
}

/**
 * Caller-supplied dates for a posting. Each omitted date defaults to the tenant business date, so
 * an ordinary same-day posting needs no dates at all.
 */
data class PostingDateRequest(
    val transactionDate: LocalDate? = null,
    val valueDate: LocalDate? = null,
    val postingDate: LocalDate? = null,
)
