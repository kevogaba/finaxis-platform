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

/**
 * The scope of a proof: one control class, one tenant, optionally one branch, as of a date.
 *
 * There is deliberately no account here. A tenant has at most one control account per class -
 * `uq_gl_account_control_kind` - so the class identifies the position being proven, and a provider
 * that had to be told which account to answer for would be choosing general-ledger accounts, which
 * `INV-11` reserves to accounting. Widening this with an account or partition key is the change to
 * make when a product module genuinely partitions one class across accounts; narrowing it later
 * would not be.
 */
data class SubledgerProofQuery(
    val organisationId: UUID,
    val branchId: UUID?,
    val kind: ControlSubledgerKind,
    val asOfDate: LocalDate,
    val currencyCode: String,
    val snapshotId: String,
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
 * production implementation does either;
 * [com.finaxis.platform.accounting.application.reconciliation.SubledgerProofProviderRegistry]
 * resolves providers by [supports] and validates every registration once at startup, and a control
 * class with no provider is reported per run rather than silently matched.
 */
interface SubledgerProofProvider {
    /**
     * A stable name recorded on every run this provider answers, e.g. `savings`.
     *
     * Must match [PROVIDER_NAME_PATTERN], which is
     * `chk_control_account_reconciliation_run_provider` verbatim. The registry checks every
     * provider at startup, so a name the evidence row would reject fails the context rather than
     * every insertion at the end of a proof that has already done both of its reads.
     */
    val providerName: String

    /**
     * Whether this provider owns the subsidiary ledger for [kind].
     *
     * Must be a pure, cheap, constant answer - no database, no configuration read, no ambient
     * context. The registry asks it once per control class while the application context is still
     * building, so a `supports` that reached for a `DataSource` or a tenant would either deadlock
     * startup or answer differently later, and ownership of a subsidiary ledger would stop being
     * a fixed fact about the module.
     */
    fun supports(kind: ControlSubledgerKind): Boolean

    /**
     * The ledger's aggregate in the scope asked for, or null when the scope has no positions.
     *
     * **The implementation must read [SubledgerProofQuery.snapshotId]'s snapshot, not the newest
     * committed state.** Accounting reads the general-ledger side of the proof from that snapshot,
     * and a provider observing a later one turns a posting that lands mid-proof into a false
     * `BREAK`, or hides a real one behind a `MATCHED`. A provider that reads on the transaction
     * accounting called it in already satisfies this and need do nothing. One that reads on another
     * connection of the same database adopts the snapshot explicitly:
     *
     * ```sql
     * BEGIN TRANSACTION ISOLATION LEVEL REPEATABLE READ;
     * SET TRANSACTION SNAPSHOT '<snapshotId>';
     * ```
     *
     * before its first statement, while accounting's transaction is still open - which it is for
     * the whole of this call. A provider that cannot honour the snapshot must throw rather than
     * answer from a different one: no evidence is better than evidence that quietly means nothing.
     */
    fun aggregate(query: SubledgerProofQuery): SubledgerAggregate?

    /** The format `provider` is stored in, and the startup check the registry applies. */
    companion object {
        /**
         * `chk_control_account_reconciliation_run_provider` as a Kotlin regex.
         *
         * Stated here because the port is the contract a product module implements against, and a
         * name only the database rejects is one nobody learns about until a proof has run.
         */
        val PROVIDER_NAME_PATTERN = Regex("^[A-Za-z0-9._-]{1,128}$")
    }
}
