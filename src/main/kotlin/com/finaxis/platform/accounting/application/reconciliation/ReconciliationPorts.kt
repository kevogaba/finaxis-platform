package com.finaxis.platform.accounting.application.reconciliation

import com.finaxis.platform.accounting.ControlSubledgerKind
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** The outcome of one proof, matching `chk_control_account_reconciliation_run_status` exactly. */
enum class ReconciliationStatus {
    /** The GL and the sub-ledger agreed within the run's tolerance. */
    MATCHED,

    /** They did not, and the difference stands until a correction posts and a checker resolves. */
    BREAK,

    /** A break an actor other than the runner has investigated and signed off. */
    RESOLVED,
}

/** A reconciliation run before the database has given it an identity. */
data class NewReconciliationRun(
    val organisationId: UUID,
    val accountId: UUID,
    val branchId: UUID?,
    val kind: ControlSubledgerKind,
    val asOfDate: LocalDate,
    val currencyCode: String,
    val glBalance: BigDecimal,
    val subledgerBalance: BigDecimal,
    val tolerance: BigDecimal,
    val status: ReconciliationStatus,
    val provider: String,
    val detail: Map<String, Any?>,
    val actorId: UUID,
)

/** A reconciliation run as its callers see it. */
data class ReconciliationRun(
    val id: UUID,
    val organisationId: UUID,
    val accountId: UUID,
    val branchId: UUID?,
    val kind: ControlSubledgerKind,
    val asOfDate: LocalDate,
    val currencyCode: String,
    val glBalance: BigDecimal,
    val subledgerBalance: BigDecimal,
    val difference: BigDecimal,
    val tolerance: BigDecimal,
    val status: ReconciliationStatus,
    val provider: String,
    val detail: Map<String, Any?>,
    val resolutionReason: String?,
    val resolvedBy: UUID?,
    val resolvedAt: Instant?,
    val runBy: UUID?,
    val runAt: Instant,
)

/** Evidence-row port over `control_account_reconciliation_run`. */
interface ReconciliationRunStore {
    /** Inserts a run and returns it with its generated id. */
    fun create(run: NewReconciliationRun): ReconciliationRun

    /** Finds one run within a tenant, or null. */
    fun find(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun?

    /**
     * Takes an exclusive row lock on the run and returns it as read under that lock, so the
     * different-actor check and the status move happen against one snapshot.
     */
    fun lock(
        organisationId: UUID,
        runId: UUID,
    ): ReconciliationRun?

    /** Marks a `BREAK` resolved; false when the row was not a `BREAK` any more. */
    fun resolve(
        organisationId: UUID,
        runId: UUID,
        reason: String,
        actorId: UUID,
        resolvedAt: Instant,
    ): Boolean

    /** The runs of one account, newest first, from [beforeId] exclusive, at most [pageSize]. */
    fun listForAccount(
        organisationId: UUID,
        accountId: UUID,
        beforeId: UUID?,
        pageSize: Int,
    ): List<ReconciliationRun>
}

/**
 * The general-ledger side of a proof: the signed functional balance of one account as of a date,
 * optionally within one branch.
 *
 * One bounded aggregate over `idx_journal_line_account_date`, tenant first, posting date in the
 * key. Issue #47's daily-balance projection will answer this from one row; until then the account
 * index keeps it to the lines of one account, which is the bound `INV-15` asks for.
 */
fun interface LedgerBalanceQuery {
    /** `SUM(signed_functional_amount)` for the account as of [asOfDate], zero when no lines. */
    fun signedBalanceAsOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal
}
