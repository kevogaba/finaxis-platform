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
 * The identity of the database snapshot both sides of a proof are read from.
 *
 * A reconciliation compares two numbers that must describe one instant. Under `READ COMMITTED` they
 * do not: the general-ledger aggregate and the provider's aggregate are separate statement
 * snapshots, and a posting committing between them is either invented as a `BREAK` or, worse, used
 * to cancel a real one into a `MATCHED`. The proof therefore runs at `REPEATABLE READ`, where one
 * snapshot serves the whole transaction.
 *
 * The implementation is responsible for proving that isolation actually took effect - Spring
 * silently ignores an isolation attribute when the method joins a transaction that is already
 * open - and for returning a token another connection can adopt. On PostgreSQL that is an exported
 * snapshot, valid while this transaction remains open, which is what makes
 * [com.finaxis.platform.accounting.SubledgerProofQuery.snapshotId] an obligation a provider outside
 * this transaction can actually honour rather than an opaque correlation id.
 */
fun interface ProofSnapshot {
    /**
     * The current transaction's snapshot identity.
     *
     * Throws [com.finaxis.platform.common.application.ConflictException] with
     * [com.finaxis.platform.accounting.application.posting.PostingErrorCodes
     * .SNAPSHOT_ISOLATION_UNAVAILABLE] when the transaction cannot offer a stable one.
     */
    fun currentSnapshotId(): String
}

/**
 * The general-ledger side of a proof: the signed functional balance of one account as of a date,
 * optionally within one branch.
 *
 * Answered by
 * [com.finaxis.platform.accounting.application.balances.DailyBalanceReader] as **the closest
 * trusted checkpoint plus a bounded delta** — the latest day the daily-balance projection has for
 * the account, plus the journal lines after it. An earlier revision of this port summed the
 * account's lines from inception, which is the read the accounting foundation calls infeasible at
 * production volume; reading the projection *alone* would have been worse, because the projection
 * is built when the business date advances and so holds nothing for today, which is the date a
 * proof is usually run for.
 *
 * **A null [branchId] means every branch, not head office.** No branch predicate is applied, so the
 * result is the whole tenant. That is the opposite of what a null `branch_id` means on a projection
 * row or a journal line, where it identifies a head-office or tenant-level posting; the two are
 * reconciled inside the implementation, deliberately in one place, because reading one as the other
 * turns every default reconciliation run into a `BREAK`.
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
