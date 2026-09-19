package com.finaxis.platform.accounting.application.balances

import com.finaxis.platform.accounting.application.reconciliation.LedgerBalanceQuery
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Answers an as-of balance as **the latest trusted checkpoint plus a bounded delta**, replacing the
 * inception-to-date scan the general-ledger side of a reconciliation used to be.
 *
 * ## Why not simply read the projection
 *
 * Because a projection lags by construction. It is built when a tenant's business date advances, so
 * on any date the tenant has not yet rolled over — which includes *today*, the date a
 * reconciliation is most often asked about — the projection has no row at all. Reading it alone
 * would answer yesterday's balance for today's question, compare it against a sub-ledger aggregate
 * that is current, and report a `BREAK` that is an artefact of the read rather than a fact about
 * the ledger. `control_account_reconciliation_run` would then fill with evidence rows that are
 * wrong rather than absent, and wrong evidence is filed where absent evidence is noticed.
 *
 * ## Where a checkpoint may safely be taken
 *
 * The obvious choice — the latest posting date the account has a projected row for — is
 * **wrong**, and the reason is that a posting's business date and its posting date are different
 * facts.
 *
 * The projection holds exactly the journals recorded on or before the watermark. A journal recorded
 * *after* it may be backdated to a posting date earlier than any row the projection offers: post on
 * business date 18 September into posting date 16 September, and the projection still shows 16
 * September's balance as it stood before that line existed. A checkpoint taken at 16 September
 * would therefore be stale, and a delta bounded by *"posting dates after 16 September"* would not
 * contain the line either. The balance would omit it silently — and a control-account
 * reconciliation would report that omission as a break in the sub-ledger, which is the one failure
 * this class exists to avoid.
 *
 * So the checkpoint is taken at the latest date **no unprojected line can reach**:
 *
 * 1. `W` — the projection watermark, the latest business date the build has settled.
 * 2. `P` — the earliest posting date touched by any journal recorded after `W`. Every line the
 *    projection is missing has a posting date at or after it.
 * 3. `S = min(asOfDate, P - 1)`, or `asOfDate` when nothing has been recorded since `W`. Every
 *    line dated on or before `S` is projected, by construction rather than by assumption.
 * 4. The closing balance of every series at `S`, one index-only row read each.
 * 5. The signed sum of `journal_line` over `(S, asOfDate]`, which contains every line the
 *    checkpoint does not.
 *
 * The two sets are disjoint and together they are every line dated on or before [asOfDate], so the
 * answer is **exact**, not approximately fresh. How far `S` falls behind [asOfDate] is bounded by
 * how far back a posting may be dated at all, and that is bounded by the open fiscal periods:
 * posting into a `CLOSED` period takes break-glass authority, so a backdated correction reaches
 * days or weeks into the past, never years.
 *
 * With nothing projected at all, `W` is null, `P` is the tenant's first posting date and the whole
 * answer is the journal scan this class replaces — correct, and no slower than before.
 *
 * ## The two meanings of "no branch"
 *
 * [LedgerBalanceQuery] applies **no branch predicate** when `branchId` is null, so its null means
 * *every branch* — what a tenant-wide control-account reconciliation asks for, and what
 * `ControlAccountReconciliationService` passes by default.
 *
 * A projection row with `branch_id IS NULL` is a **head-office or tenant-level posting** and
 * nothing else, because that is what `journal_line.branch_id` being null means.
 *
 * The two are opposites, and reading one as the other would answer a tenant-wide question with a
 * head-office balance. A null branch therefore enumerates *every* series this account has and
 * leaves the journal delta unfiltered; a named branch reads that one series and filters the delta
 * to it.
 *
 * ## What it does not do
 *
 * It does not repair its own input. A series whose projected rows are missing or wrong is drift,
 * and drift is [DailyBalanceProjectionService.proveAccount]'s business — a reader that quietly
 * recomputed what it found suspicious would make the proof unable to fail, which is the one thing a
 * proof must always be able to do.
 */
@Component
class DailyBalanceReader(
    private val store: DailyBalanceStore,
    private val journal: LedgerMovementSource,
) : LedgerBalanceQuery {
    override fun signedBalanceAsOf(
        organisationId: UUID,
        accountId: UUID,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal {
        val checkpointDate = safeCheckpointDate(organisationId, asOfDate)
        val checkpoint =
            store
                .seriesOf(organisationId, accountId, branchId)
                .fold(BigDecimal.ZERO) { total, key ->
                    total.add(
                        store.latestRowAsOf(key, checkpointDate)?.closingSignedFunctional
                            ?: BigDecimal.ZERO,
                    )
                }
        val delta =
            journal.signedMovement(
                organisationId = organisationId,
                accountId = accountId,
                branchId = branchId,
                afterDate = checkpointDate,
                toDate = asOfDate,
            )
        return checkpoint.add(delta)
    }

    /**
     * The latest date at which the projection is complete, never later than [asOfDate].
     *
     * Tenant-wide rather than per account, because the bound it computes — *"no unprojected line
     * is dated on or before this"* — is a fact about the tenant's journals and not about one
     * account's series, and because a per-account form would cost one aggregate per account on the
     * tenant-wide reads issue #49 builds on this.
     */
    private fun safeCheckpointDate(
        organisationId: UUID,
        asOfDate: LocalDate,
    ): LocalDate {
        val watermark = store.projectionWatermark(organisationId)
        val earliestUnprojected =
            journal.earliestPostingDateRecordedAfter(organisationId, watermark)
                ?: return asOfDate
        return minOf(asOfDate, earliestUnprojected.minusDays(1))
    }
}
