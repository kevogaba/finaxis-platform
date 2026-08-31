package com.finaxis.platform.accounting.domain

import java.math.BigDecimal
import java.util.UUID

/**
 * A currency-qualified amount.
 *
 * Never a binary floating-point type: see
 * `docs/adr/0019-accounting-money-representation-and-rounding.md`. The authoritative scale and
 * rounding policy is frozen by that ADR and enforced by the posting engine (issue #41); this type
 * only carries the value and its currency.
 */
data class MonetaryAmount(
    val amount: BigDecimal,
    val currency: String,
)

/** The double-entry side of a journal line. */
enum class PostingSide {
    DEBIT,
    CREDIT,
}

/** Tenant, branch and actor scope every accounting operation is evaluated against. */
data class AccountingContext(
    val organisationId: UUID,
    val branchId: UUID?,
    val actorId: UUID,
    val correlationId: String? = null,
)

/**
 * Durable lineage from the originating business transaction to the accounting record.
 *
 * The ([sourceModule], [sourceType], [sourceId]) triple identifies the business event for
 * drill-down; [idempotencyKey] is what makes a retried posting attempt a no-op rather than a
 * duplicate journal. None of these are foreign keys - they point at modules that may not exist yet.
 */
data class AccountingSourceReference(
    val sourceModule: String,
    val sourceType: String,
    val sourceId: UUID,
    val idempotencyKey: String,
)
