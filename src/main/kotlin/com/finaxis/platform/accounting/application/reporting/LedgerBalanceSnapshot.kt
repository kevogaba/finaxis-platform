package com.finaxis.platform.accounting.application.reporting

import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Every account's signed balance at the close of one date, for a whole tenant or one branch.
 *
 * **The one place the checkpoint-plus-delta rule is written for a tenant-wide read.** A trial
 * balance needs it for its opening column and a balance sheet needs it for every line, and those
 * are the same question asked twice — so they ask it here rather than each carrying its own copy. A
 * rule this subtle does not survive being implemented three times: the general ledger's per-account
 * version was wrong in exactly this way until it was corrected, and the correction would have had
 * to be made once per copy.
 *
 * The composition is the one
 * [com.finaxis.platform.accounting.application.balances.DailyBalanceReader] documents for a single
 * account, applied to the chart at once: a checkpoint taken where no unprojected line can reach it,
 * plus the journal over everything after it. The checkpoint's date is chosen by
 * [BalanceCheckpointQueries], so a posting backdated onto an already-projected day is in the delta
 * rather than lost between the two halves.
 *
 * Balances are **signed in the ledger's own convention** — debits positive, credits negative
 * (`INV-3`) — and an account absent from the result has a zero balance rather than an unknown one.
 */
@Component
class LedgerBalanceSnapshot(
    private val checkpoints: BalanceCheckpointQueries,
    private val ledger: LedgerReportingQueries,
) {
    /**
     * Balances at the close of [asOfDate].
     *
     * Two statements: the projection's checkpoint, and the journal's movement from the day after it
     * up to [asOfDate]. When the checkpoint already sits on [asOfDate] the second is skipped, which
     * is the common case for a report of a date the tenant has rolled past.
     */
    fun signedBalancesAsOf(
        organisationId: UUID,
        branchId: UUID?,
        currencyCode: String,
        asOfDate: LocalDate,
    ): Map<UUID, BigDecimal> {
        val checkpoint = checkpoints.checkpoint(organisationId, branchId, currencyCode, asOfDate)
        val balances = checkpoint.closingByAccount.toMutableMap()
        val deltaFrom = checkpoint.checkpointDate.plusDays(1)
        if (deltaFrom.isAfter(asOfDate)) {
            return balances
        }
        ledger
            .movementsByAccount(organisationId, branchId, deltaFrom, asOfDate)
            .forEach { movement ->
                balances.merge(
                    movement.accountId,
                    movement.debit.subtract(movement.credit),
                    BigDecimal::add,
                )
            }
        return balances
    }
}
