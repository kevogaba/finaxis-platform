package com.finaxis.platform.accounting.domain

import java.time.LocalDate
import java.util.UUID

/** Identity of the fiscal period a posting or a state change targets. */
data class FiscalPeriodKey(
    val organisationId: UUID,
    val fiscalPeriodId: UUID,
)

/**
 * Fiscal-period lifecycle status.
 *
 * These four are the set `docs/database/accounting-erd.md` adopts and
 * `chk_accounting_fiscal_period_status` enforces, so the enum and the column cannot drift.
 *
 * `SOFT_CLOSED` is deliberately absent: the distinction it would draw — postings blocked for
 * ordinary users but open to a privileged few — is already expressed by [CLOSED] plus
 * `journal.post_prior_period`, so it would add a state without adding a capability.
 */
enum class FiscalPeriodStatus {
    /** Provisioned but not yet open for posting. */
    FUTURE,

    /** Open for posting. */
    OPEN,

    /** Closed; postings are rejected until an explicit, audited reopen. */
    CLOSED,

    /**
     * Locked; postings are rejected and the period can never be reopened.
     *
     * Distinct from [CLOSED] on purpose, and the distinction is load-bearing rather than
     * decorative: `docs/architecture/accounting-foundation.md` states that a closed period may be
     * reopened and a locked one may not, and rejects Fineract's closure-date model precisely
     * because it cannot express the difference. Without this state a store adapter has no way to
     * represent a locked row, and issue #39's reopen flow has nothing to refuse against — it would
     * happily reopen permanently finalised books.
     */
    LOCKED,
}

/** A fiscal period as read under a row lock: identity, bounds and status. */
data class FiscalPeriodSnapshot(
    val key: FiscalPeriodKey,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val status: FiscalPeriodStatus,
)
