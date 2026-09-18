package com.finaxis.platform.accounting.application.balances

import com.finaxis.platform.accounting.AccountingDayRollover
import com.finaxis.platform.accounting.AccountingPermissionGuard
import com.finaxis.platform.accounting.AccountingTenantLookup
import com.finaxis.platform.accounting.application.posting.PostingErrorCodes
import com.finaxis.platform.accounting.config.AccountingProperties
import com.finaxis.platform.accounting.domain.AccountingAuditActions
import com.finaxis.platform.accounting.domain.AccountingPermissions
import com.finaxis.platform.common.application.ConflictException
import com.finaxis.platform.common.audit.AuditCommand
import com.finaxis.platform.common.audit.AuditOutcome
import com.finaxis.platform.common.audit.AuditService
import com.finaxis.platform.common.audit.AuditSeverity
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.util.UUID

/**
 * Builds, rebuilds and proves `gl_account_daily_balance`, the one derived balance projection the
 * accounting foundation approves.
 *
 * **The journal is the system of record and this is not.** Every row here is recomputed from
 * `journal_line` by the documented query, and when the two disagree the journal wins and the
 * projection is rebuilt (`INV-13`). Nothing in the posting path reads or writes it, so a projection
 * that is stale, missing or wrong can delay a report but cannot make a posting wrong.
 *
 * ## Why a build enumerates by business date and rebuilds by posting date
 *
 * The two dates do different jobs. `posting_date` is the day amounts belong to and is this table's
 * grain; `journal_entry.business_date` is the day a posting was *recorded*. Only the second sees a
 * backdated correction, because a correction recorded today into an earlier open period carries
 * today's business date on its header and the earlier date on its lines.
 *
 * So [settleDay] asks *"which series did this tenant move on business date B"*, takes the earliest
 * posting date each was moved on, and **recomputes that series from that date forward**. A
 * backdated correction is therefore repaired by the same code path that handles the day's ordinary
 * postings — there is no separate repair path, because a repair path that runs only when someone
 * notices is how a projection becomes a second ledger that diverges silently.
 *
 * ## Three bounds
 *
 * A carried-forward opening balance makes every later row of a series depend on every earlier one,
 * which is what makes a late arrival expensive. Three things bound it:
 *
 * 1. **The watermark, plus a trailing re-scan behind it.** The window starts at the latest
 *    business date the projection has settled, not at a fixed offset from the day being built, so
 *    a day whose build was never enqueued is caught up by the next build that runs rather than
 *    being skipped forever once the watermark passes it. `advance` takes the `business_date` row
 *    lock exclusively and a current-dated posting holds it shared, so no current-dated posting
 *    straddles the advance — but a *backdated* one takes no lock on that row at all, so one that
 *    read business date `B` before the advance may commit after it.
 *    [AccountingProperties.dailyBalanceTrailingDays] re-scans behind the watermark for that case,
 *    and costs only the series that moved, because the recompute is idempotent by construction.
 * 2. **The oldest open fiscal period** bounds how far back a rebuild can be asked to reach, because
 *    a backdated posting needs an open covering period and a closed one refuses it whatever
 *    permission the actor holds. That bound is a property of the ledger rather than a setting
 *    somebody has to keep right.
 * 3. **A per-tenant advisory lock**, so a retry and a scheduled build cannot interleave on one
 *    series — which matters because a rebuild deletes a series' tail before reinserting it, and the
 *    interleaving window is one in which that series has no rows at all.
 *
 * ## One transaction per day, and what that costs
 *
 * [settleDay] rebuilds every affected series in a single transaction, and holds the tenant's build
 * lock for its whole length. That is the simple choice and it is stated here rather than discovered
 * later: a day that moved many series, or a backdated correction reaching a long way back, makes
 * one long-running transaction that holds the lock and accumulates WAL.
 *
 * It is acceptable because of what the projection is. Per-series transactions would bound the
 * length, but a partial build would then be a state nothing records, and the lock would stop
 * serialising the thing it exists to serialise. One transaction means a failed build leaves the
 * projection exactly as it was, and the retry is the same work rather than a different one. The
 * cost is bounded by the oldest open fiscal period — a closed period refuses the backdated posting
 * that would reach behind it — and the remedy, if a deployment ever needs one, is to close periods
 * promptly rather than to split the transaction.
 *
 * If this does become a problem it will show up as build duration in #53's monitoring, which is the
 * right place to learn it.
 */
@Service
class DailyBalanceProjectionService(
    private val store: DailyBalanceStore,
    private val journal: LedgerMovementSource,
    private val lock: DailyBalanceProjectionLock,
    private val tenants: AccountingTenantLookup,
    private val permissions: AccountingPermissionGuard,
    private val properties: AccountingProperties,
    private val clock: Clock,
    private val auditService: AuditService,
) : AccountingDayRollover {
    /**
     * Settles the projection for the journals [organisationId] recorded on [businessDate].
     *
     * Runs in one transaction, holding the tenant's build lock for its whole length. Takes no
     * permission check: its caller is the business-date rollover, which is machinery rather than an
     * actor, and there is no actor to check. The operator-facing entry points — [rebuild] and
     * [proveAccount] — do check, and they are the ones a person can reach.
     */
    @Transactional
    override fun settleDay(
        organisationId: UUID,
        businessDate: LocalDate,
    ) {
        lock.lockForBuild(organisationId)
        val currency = requireFunctionalCurrency(organisationId)
        val from = buildWindowStart(organisationId, businessDate) ?: return
        journal
            .seriesAffectedBetween(organisationId, from, businessDate, currency)
            .groupBy { it.key }
            .map { (key, seen) -> AffectedSeries(key, seen.minOf { it.fromDate }) }
            .groupBy { it.key.accountId }
            .forEach { (accountId, series) -> rebuildAccount(accountId, series, businessDate) }
    }

    /**
     * Rebuilds every affected series of one account, reading the journal **once** for all of them.
     *
     * An account that moved in several branches on a day yields one affected series per branch,
     * and rebuilding each from its own query rescans the account's whole date tail per branch:
     * `branch_id` is an `INCLUDE` payload of `idx_journal_line_account_date`, not a key column, so
     * the scan cannot be narrowed to one branch. One grouped read from the earliest date any of
     * them needs, partitioned here, reads that range once instead of once per branch.
     */
    private fun rebuildAccount(
        accountId: UUID,
        series: List<AffectedSeries>,
        businessDate: LocalDate,
    ) {
        val first = series.minOf { it.fromDate }
        val currency = series.first().key.currencyCode
        val byKey =
            journal
                .movementsForAccountFrom(
                    series.first().key.organisationId,
                    accountId,
                    first,
                    currency,
                ).groupBy { it.key }
        series.forEach { affected ->
            val movements =
                byKey[affected.key]
                    .orEmpty()
                    .filter { !it.postingDate.isBefore(affected.fromDate) }
            rebuildSeries(affected, businessDate, movements)
        }
    }

    /**
     * The earliest business date this build must cover, or null when the tenant has never posted.
     *
     * **Driven by the watermark rather than by a fixed trailing count**, because the two failures
     * a trailing window has to absorb are not both one day wide. A backdated posting straddling
     * the advance is — it commits shortly after the build queried its date — but a *lost* build is
     * not: an enqueue registered after the advance commits is gone if the process dies before the
     * callback runs, and nothing else revisits that day. With a fixed window of one, two such
     * losses leave a day permanently unprojected while a later build advances the watermark past
     * it, and the `business_date >= W` delta can no longer see it. Starting from the watermark
     * instead means every unsettled day is caught up by the next build that runs, however many
     * have accumulated.
     *
     * [AccountingProperties.dailyBalanceTrailingDays] still applies, now as a re-scan *behind* the
     * watermark for the straddling-posting case rather than as the whole window.
     *
     * With no watermark the projection has never been built, so the floor is the tenant's earliest
     * journal business date: a cold start over a journal that already holds history is a full
     * backfill, and it has to be, because the watermark it leaves behind claims every journal
     * recorded up to it is projected. That is a one-time cost proportional to the tenant's
     * history, and an operator who does not want it inside a rollover can pay it in advance
     * through [rebuild].
     */
    private fun buildWindowStart(
        organisationId: UUID,
        businessDate: LocalDate,
    ): LocalDate? {
        val trailing = properties.dailyBalanceTrailingDays.toLong()
        val watermark = store.projectionWatermark(organisationId)
        val floor = watermark?.minusDays(trailing) ?: journal.earliestBusinessDate(organisationId)
        return floor?.let { minOf(it, businessDate) }
    }

    /**
     * Rebuilds one account's projection from [fromDate] forward, for an operator repairing drift.
     *
     * Deliberately the same code path [settleDay] uses, so the repair cannot behave differently
     * from the routine build — a repair with its own implementation is a second thing to keep
     * correct, and the one nobody exercises.
     *
     * Guarded by `reconciliation.run`: this is the "prove and repair the ledger's derived state"
     * family, and it is the same authority that runs a control-account proof.
     */
    @Transactional
    fun rebuild(command: RebuildDailyBalancesCommand): Int {
        permissions.requireTenantPermission(
            command.actorId,
            command.organisationId,
            AccountingPermissions.RECONCILIATION_RUN,
        )
        lock.lockForBuild(command.organisationId)
        val currency = requireFunctionalCurrency(command.organisationId)
        val series =
            store
                .seriesOf(command.organisationId, command.accountId, command.branchId)
                .ifEmpty {
                    journal.journalSeriesOf(
                        command.organisationId,
                        command.accountId,
                        command.branchId,
                        currency,
                    )
                }
        val stamp = repairStamp(command)
        series.forEach { rebuildSeries(AffectedSeries(it, command.fromDate), stamp) }
        auditService.record(
            AuditCommand(
                actorType = ACTOR_TYPE_USER,
                actorId = command.actorId.toString(),
                tenantId = command.organisationId.toString(),
                branchId = command.branchId?.toString(),
                action = AccountingAuditActions.DAILY_BALANCE_REBUILD,
                resourceType = RESOURCE_TYPE,
                resourceId = command.accountId.toString(),
                outcome = AuditOutcome.SUCCESS,
                severity = AuditSeverity.CRITICAL,
                reason = command.reason,
                metadata =
                    mapOf(
                        "fromDate" to command.fromDate.toString(),
                        "seriesRebuilt" to series.size.toString(),
                        "builtForBusinessDate" to stamp.toString(),
                    ),
            ),
        )
        return series.size
    }

    /**
     * The `built_for_business_date` a repair stamps on the rows it replaces.
     *
     * **A repair must never raise the tenant's watermark**, and stamping `command.fromDate` did:
     * that is a *posting* date chosen by the operator, while the watermark is the latest *business*
     * date every account's build has settled. Repairing one account with a recent `fromDate` would
     * assert tenant-wide that journals recorded up to it are projected, and every other account's
     * as-of balance would then silently drop the journals that claim covers but nothing built.
     *
     * So the existing watermark is preserved. `MAX` over the table is unchanged by a row stamped
     * at or below it, which makes the repair invisible to the reader's bound — which is correct,
     * because repairing one account tells the reader nothing new about the rest.
     *
     * Where nothing is projected at all there is no watermark to preserve, and the tenant's
     * earliest journal business date is used: any later value would claim coverage the repair has
     * not established, and this one leaves the reader maximally conservative instead.
     */
    private fun repairStamp(command: RebuildDailyBalancesCommand): LocalDate =
        store.projectionWatermark(command.organisationId)
            ?: journal.earliestBusinessDate(command.organisationId)
            ?: command.fromDate

    /**
     * The projection-versus-journal proof, for one account over one bounded date range.
     *
     * **A sound projection returns an empty list.** The query behind it is a `FULL OUTER JOIN`
     * rather than an inner one, because the two failure directions are opposite and both matter: a
     * day the journal has and the projection does not is a missed build, and a day the projection
     * has and the journal does not is a phantom that would be added into a balance.
     *
     * This is the backstop and not the mechanism. A projection whose correctness depended on
     * someone running this would be the second independent ledger issue #47 forbids.
     */
    @Transactional(readOnly = true)
    fun proveAccount(query: ProveDailyBalancesQuery): List<ProjectionDrift> {
        permissions.requireTenantPermission(
            query.actorId,
            query.organisationId,
            AccountingPermissions.RECONCILIATION_VIEW,
        )
        requireOrderedRange(query.fromDate, query.toDate)
        return store.drift(query.organisationId, query.accountId, query.fromDate, query.toDate)
    }

    /**
     * The opening-balance chain proof, for one account over one bounded date range.
     *
     * **A sound projection returns an empty list.** The sibling of [proveAccount] and deliberately
     * not folded into it: that one asks whether each projected day matches the journal, this one
     * asks whether each row continues the one before it, and the second failure is invisible to the
     * first. A carried-forward opening makes every row depend on its predecessor, so one wrong
     * opening — or a first row starting from anything but zero — displaces every later closing
     * balance by a constant and leaves every day's debit, credit and line count correct. The
     * journal comparison passes, and [DailyBalanceReader] then takes one of those closing balances
     * as its checkpoint.
     *
     * Guarded by `reconciliation.view`, the same authority [proveAccount] takes.
     */
    @Transactional(readOnly = true)
    fun proveOpeningChain(query: ProveDailyBalancesQuery): List<OpeningChainBreak> {
        permissions.requireTenantPermission(
            query.actorId,
            query.organisationId,
            AccountingPermissions.RECONCILIATION_VIEW,
        )
        requireOrderedRange(query.fromDate, query.toDate)
        return store.openingChainBreaks(
            query.organisationId,
            query.accountId,
            query.fromDate,
            query.toDate,
        )
    }

    /**
     * Recomputes one series from a date forward: delete the tail, replay the movements, carry the
     * opening balance through.
     *
     * The opening comes from the series' last row **strictly before** [series] `fromDate`, read
     * before the delete. Reading it after would find nothing, because the delete has just removed
     * every row from that date on — and the row that supplies the opening is the one immediately
     * before that boundary, which the delete does not touch.
     *
     * Where there is no such row the opening is summed from the journal rather than assumed to be
     * zero. Zero is right only when the series genuinely has no history before `fromDate`, and it
     * is silently wrong whenever it does — on a cold start over an existing journal above all.
     * Such a projection chains consistently from a false origin, so every row is out by the same
     * amount and neither proof reports it: both compare a day's debit, credit and line totals,
     * which an opening offset leaves untouched.
     */
    private fun rebuildSeries(
        series: AffectedSeries,
        builtForBusinessDate: LocalDate,
        movements: List<DailyMovement> = journal.movementsFrom(series.key, series.fromDate),
    ) {
        val opening =
            store.lastRowBefore(series.key, series.fromDate)?.closingSignedFunctional
                ?: journal.signedBalanceBefore(series.key, series.fromDate)
        store.deleteFrom(series.key, series.fromDate)
        var running = opening
        val rows =
            movements.sortedBy { it.postingDate }.map { movement ->
                val row =
                    DailyBalanceRow(
                        key = series.key,
                        postingDate = movement.postingDate,
                        openingSignedFunctional = running,
                        debitFunctional = movement.debitFunctional,
                        creditFunctional = movement.creditFunctional,
                        lineCount = movement.lineCount,
                    )
                running = row.closingSignedFunctional
                row
            }
        if (rows.isNotEmpty()) {
            store.insertAll(rows, clock.instant(), builtForBusinessDate)
        }
    }

    private fun requireFunctionalCurrency(organisationId: UUID): String =
        tenants.functionalCurrencyOf(organisationId)
            ?: throw ConflictException(
                code = PostingErrorCodes.FUNCTIONAL_CURRENCY_UNAVAILABLE,
                safeDetail =
                    "The organisation has no functional currency, so no balance can be expressed.",
            )

    private companion object {
        const val ACTOR_TYPE_USER = "USER"
        const val RESOURCE_TYPE = "GL_ACCOUNT_DAILY_BALANCE"
    }

    private fun requireOrderedRange(
        fromDate: LocalDate,
        toDate: LocalDate,
    ) {
        if (toDate.isBefore(fromDate)) {
            throw ConflictException(
                code = PostingErrorCodes.PROJECTION_RANGE_INVALID,
                safeDetail = "The proof range must end on or after it starts.",
            )
        }
    }
}

/**
 * Repairs one account's projection from a date forward, on an operator's authority.
 *
 * [reason] is mandatory and is recorded on the audit trail. A rebuild discards derived financial
 * rows and recomputes them, so the question an auditor asks afterwards is never *"was this person
 * allowed to"* — the permission check settles that — but *"why did they"*, and a field that may be
 * omitted is one that is omitted.
 */
data class RebuildDailyBalancesCommand(
    val organisationId: UUID,
    val accountId: UUID,
    val branchId: UUID? = null,
    val fromDate: LocalDate,
    val actorId: UUID,
    val reason: String,
)

/** Proves one account's projection against the journal over a bounded range. */
data class ProveDailyBalancesQuery(
    val organisationId: UUID,
    val accountId: UUID,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val actorId: UUID,
)
