package com.finaxis.platform.accounting

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The subsidiary-ledger classes a general-ledger control account can represent.
 *
 * Matches `chk_gl_account_control_kind` exactly. Each is the aggregate financial position of one
 * product-owned ledger; the product module that owns that ledger is the one that answers for it
 * through [SubledgerProofProvider].
 */
enum class ControlSubledgerKind {
    /** Member savings and deposit balances - a liability control. */
    SAVINGS_DEPOSITS,

    /** Member share capital - an equity control. */
    SHARE_CAPITAL,

    /** Outstanding loan principal - an asset control. */
    LOAN_PRINCIPAL,

    /** Interest earned but not yet received. */
    ACCRUED_INTEREST_RECEIVABLE,

    /** Interest owed but not yet paid. */
    ACCRUED_INTEREST_PAYABLE,

    /** Cash held by tellers. */
    TELLER_CASH,

    /** Unallocated or in-transit amounts awaiting classification. */
    SUSPENSE,
}

/** The scope of a proof: one control class, one tenant, optionally one branch, as of a date. */
data class SubledgerProofQuery(
    val organisationId: UUID,
    val branchId: UUID?,
    val kind: ControlSubledgerKind,
    val asOfDate: LocalDate,
    val currencyCode: String,
)

/**
 * What the owning module says its subsidiary ledger totalled in the scope asked for.
 *
 * [balance] is **signed in the general ledger's convention** - debits positive, credits negative -
 * so a savings module reports member deposits of 1,000 as `-1000`, and equality with the control
 * account is a subtraction rather than a rule about account classes. [detail] is drill-down
 * metadata the module chooses to expose; accounting stores it verbatim and never interprets it.
 */
data class SubledgerAggregate(
    val balance: BigDecimal,
    val currencyCode: String,
    val positionCount: Long,
    val detail: Map<String, Any?> = emptyMap(),
)

/**
 * Accounting-owned port a product module implements to prove its subsidiary ledger against the
 * general ledger (`INV-14`).
 *
 * The contract is deliberately narrow and read-only. Accounting asks *"what did your ledger total,
 * in this scope, as of this date"*, receives one aggregate, and never reaches into the module's
 * persistence. The module never reaches into accounting's. No product module exists yet, so no
 * production implementation does either; the reconciliation service resolves providers by
 * [supports], and a control class with no provider is reported as such rather than silently
 * matched.
 */
interface SubledgerProofProvider {
    /** A stable name recorded on every run this provider answers, e.g. `savings`. */
    val providerName: String

    /** Whether this provider owns the subsidiary ledger for [kind]. */
    fun supports(kind: ControlSubledgerKind): Boolean

    /** The ledger's aggregate in the scope asked for, or null when the scope has no positions. */
    fun aggregate(query: SubledgerProofQuery): SubledgerAggregate?
}
