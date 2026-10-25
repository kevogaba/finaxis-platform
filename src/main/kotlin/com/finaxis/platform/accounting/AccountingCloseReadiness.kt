package com.finaxis.platform.accounting

import java.util.UUID

/**
 * Accounting-owned read port the module that owns the business date consults to tell an operator
 * what accounting work is still open before the tenant starts close-of-business (#168).
 *
 * It answers with three counts, not with journals or runs, so a module that is not accounting never
 * reasons about ledger rows (the same restraint as [AccountingLedgerActivity]), and it is a
 * snapshot: nothing it reports is locked, and a posting or an approval can land the moment after
 * it is read. That is why lifecycle uses it for advice and not for enforcement. See
 * `docs/adr/0038-cob-readiness-and-approval-counts.md`.
 *
 * Accounting declares the port and implements it with a jOOQ read of its own tables, so lifecycle
 * never reads an accounting table. Each count is one bounded, tenant-filtered statement.
 */
interface AccountingCloseReadiness {
    /** The tenant's open accounting work. Read-only, non-locking, tenant-scoped. */
    fun snapshot(organisationId: UUID): CloseReadinessSnapshot
}

/**
 * What accounting still has open for one tenant.
 *
 * @property pendingApprovalManualJournals manual journals submitted and awaiting a checker. They
 * are the one open item that changes the ledger once decided, so readiness treats them as blocking.
 * @property draftManualJournals manual journals still being prepared; they change nothing until
 * submitted, so readiness only warns.
 * @property latestBreakRuns control accounts whose **latest** reconciliation run is a `BREAK`. A
 * break a later run superseded (a matching run, or the break marked resolved) is not counted.
 */
data class CloseReadinessSnapshot(
    val pendingApprovalManualJournals: Long,
    val draftManualJournals: Long,
    val latestBreakRuns: Long,
)
