package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.application.reporting.BalanceCheckpoint
import com.finaxis.platform.accounting.application.reporting.BalanceCheckpointQueries
import com.finaxis.platform.accounting.application.reporting.ChartReportingQueries
import com.finaxis.platform.accounting.application.reporting.ReportingAccount
import com.finaxis.platform.accounting.application.reporting.ReportingAccountNode
import com.finaxis.platform.accounting.domain.AccountClass
import com.finaxis.platform.accounting.domain.AccountUsage
import com.finaxis.platform.accounting.domain.ChartHierarchyPolicy
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT
import com.finaxis.platform.jooq.tables.references.GL_ACCOUNT_DAILY_BALANCE
import com.finaxis.platform.jooq.tables.references.JOURNAL_ENTRY
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The chart-of-accounts adapter behind [ChartReportingQueries].
 *
 * Reads configuration rather than the ledger, which is why it is separate from
 * [JooqLedgerReportingQueries]: the chart is small, mutable and bounded by design, and the journal
 * is none of those things. Both walks carry the tenant predicate inside the recursive term as well
 * as the anchor, and both are bounded by [ChartHierarchyPolicy.MAX_DEPTH] in the SQL, so a
 * corrupted `parent_account_id` terminates rather than spinning — the same rule
 * [JooqGlAccountStore] follows for the same reason.
 */
@Component
class JooqChartReportingQueries(
    private val dsl: DSLContext,
) : ChartReportingQueries {
    override fun accountsById(organisationId: UUID): Map<UUID, ReportingAccount> =
        dsl
            .select(
                GL_ACCOUNT.ID,
                GL_ACCOUNT.ACCOUNT_CODE,
                GL_ACCOUNT.ACCOUNT_NAME,
                GL_ACCOUNT.ACCOUNT_CLASS,
                GL_ACCOUNT.ACCOUNT_USAGE,
                GL_ACCOUNT.IS_CONTRA_ACCOUNT,
            ).from(GL_ACCOUNT)
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .fetch()
            .associate { it.get(GL_ACCOUNT.ID)!! to toAccount(it) }

    override fun find(
        organisationId: UUID,
        accountId: UUID,
    ): ReportingAccount? =
        dsl
            .select(
                GL_ACCOUNT.ID,
                GL_ACCOUNT.ACCOUNT_CODE,
                GL_ACCOUNT.ACCOUNT_NAME,
                GL_ACCOUNT.ACCOUNT_CLASS,
                GL_ACCOUNT.ACCOUNT_USAGE,
                GL_ACCOUNT.IS_CONTRA_ACCOUNT,
            ).from(GL_ACCOUNT)
            .where(GL_ACCOUNT.ORGANISATION_ID.eq(organisationId))
            .and(GL_ACCOUNT.ID.eq(accountId))
            .fetchOne()
            ?.let(::toAccount)

    /**
     * One `WITH RECURSIVE` descent, depth-bounded in the SQL and returned in code order.
     *
     * A null root anchors on the accounts that have no parent, so the whole chart comes back in one
     * statement. The depth column is what lets the caller accumulate a subtree total from the
     * leaves up without a second query — issue #49's *"hierarchy rollup must not issue one query
     * per account node"* in the only form that actually holds.
     */
    override fun hierarchy(
        organisationId: UUID,
        rootAccountId: UUID?,
    ): List<ReportingAccountNode> =
        dsl
            .resultQuery(
                """
                WITH RECURSIVE walk (id, depth) AS (
                    SELECT id, 1
                    FROM gl_account
                    WHERE organisation_id = ?
                      AND (
                          CAST(? AS UUID) IS NULL AND parent_account_id IS NULL
                          OR id = CAST(? AS UUID)
                      )
                    UNION ALL
                    SELECT a.id, w.depth + 1
                    FROM gl_account a
                    JOIN walk w ON a.parent_account_id = w.id
                    WHERE a.organisation_id = ? AND w.depth < ?
                )
                SELECT a.id, a.account_code, a.account_name, a.account_class, a.account_usage,
                       a.is_contra_account, a.parent_account_id, w.depth
                FROM walk w
                JOIN gl_account a ON a.organisation_id = ? AND a.id = w.id
                ORDER BY a.account_code
                """.trimIndent(),
                organisationId,
                rootAccountId,
                rootAccountId,
                organisationId,
                ChartHierarchyPolicy.MAX_DEPTH,
                organisationId,
            ).fetch { record ->
                ReportingAccountNode(
                    account = toAccount(record),
                    parentAccountId = record.get("parent_account_id", UUID::class.java),
                    depth = record.get("depth", Int::class.java)!!,
                )
            }

    private fun toAccount(record: Record) =
        ReportingAccount(
            accountId = record.get("id", UUID::class.java)!!,
            accountCode = record.get("account_code", String::class.java)!!,
            accountName = record.get("account_name", String::class.java)!!,
            accountClass = AccountClass.valueOf(record.get("account_class", String::class.java)!!),
            accountUsage = AccountUsage.valueOf(record.get("account_usage", String::class.java)!!),
            isContraAccount = record.get("is_contra_account", Boolean::class.java)!!,
        )
}

/**
 * The bulk projection read behind [BalanceCheckpointQueries].
 *
 * ## Where the checkpoint is taken
 *
 * The same rule
 * [com.finaxis.platform.accounting.application.balances.DailyBalanceReader] applies for one
 * account, applied to the whole chart at once: the projection holds the journals recorded on or
 * before its watermark and no others, so the checkpoint is taken strictly before the earliest
 * posting date any journal recorded since has touched. Taking it at the latest projected posting
 * date would make a posting backdated onto an already-projected day invisible to the checkpoint
 * *and* to the delta the caller adds, and a trial balance is the last place an omission should be
 * able to hide.
 *
 * ## Why the read is a `LATERAL` per key rather than a sweep
 *
 * The driver is the chart crossed with the branch list — configuration, so bounded by how the
 * tenant is set up rather than by how long it has been running — and each correlated probe is a
 * backward range scan of `uq_gl_account_daily_balance_key` stopping at the first row. Scanning the
 * projection instead would cost one row per account, branch, currency and day that moved, which
 * grows without bound over the retention window; this costs one index descent per key however long
 * the tenant has been posting.
 *
 * ## Why two statements and not one
 *
 * `branch_id` is nullable and PostgreSQL cannot use an index for `IS NOT DISTINCT FROM`, so a
 * single probe parameterised over a nullable branch would fall back to scanning each account's
 * whole key range — the thing the `LATERAL` exists to avoid. The named-branch arm uses `=` and the
 * head-office arm uses `IS NULL`; both are index qualifications, and the two sets are disjoint, so
 * summing them per account is the same answer a single statement would give.
 */
@Component
class JooqBalanceCheckpointQueries(
    private val dsl: DSLContext,
) : BalanceCheckpointQueries {
    override fun checkpoint(
        organisationId: UUID,
        branchId: UUID?,
        currencyCode: String,
        asOfDate: LocalDate,
    ): BalanceCheckpoint {
        val checkpointDate = safeCheckpointDate(organisationId, asOfDate)
        val closings = mutableMapOf<UUID, BigDecimal>()
        branchClosings(organisationId, branchId, currencyCode, checkpointDate)
            .forEach { (accountId, closing) -> closings.merge(accountId, closing, BigDecimal::add) }
        if (branchId == null) {
            headOfficeClosings(organisationId, currencyCode, checkpointDate)
                .forEach { (accountId, closing) ->
                    closings.merge(accountId, closing, BigDecimal::add)
                }
        }
        return BalanceCheckpoint(checkpointDate = checkpointDate, closingByAccount = closings)
    }

    /**
     * The latest date at or before [asOfDate] that the projection is complete for.
     *
     * Two index reads, and only the first is O(1): the watermark is a backward scan of
     * `idx_gl_account_daily_balance_watermark` stopping at its first row, while the earliest
     * posting date recorded since is a range aggregate over `idx_journal_entry_business_date` —
     * index-only, because of its `INCLUDE (posting_date)`, but a scan of the matching entries
     * rather than a probe, since `posting_date` is a payload and not an ordered key.
     *
     * A null watermark — a tenant whose projection has never been built — makes the second read
     * return the tenant's first posting date,
     * and the checkpoint retreats before the whole ledger, which is correct.
     */
    private fun safeCheckpointDate(
        organisationId: UUID,
        asOfDate: LocalDate,
    ): LocalDate {
        val watermark =
            dsl
                .select(DSL.max(GL_ACCOUNT_DAILY_BALANCE.BUILT_FOR_BUSINESS_DATE))
                .from(GL_ACCOUNT_DAILY_BALANCE)
                .where(GL_ACCOUNT_DAILY_BALANCE.ORGANISATION_ID.eq(organisationId))
                .fetchOne(0, LocalDate::class.java)
        val earliestUnprojected =
            dsl
                .select(DSL.min(JOURNAL_ENTRY.POSTING_DATE))
                .from(JOURNAL_ENTRY)
                .where(JOURNAL_ENTRY.ORGANISATION_ID.eq(organisationId))
                .and(
                    // Inclusive, and it must stay identical to
                    // `LedgerMovementSource.earliestPostingDateRecordedAfter`. The watermark's own
                    // business date is not safely closed: a backdated posting takes no lock on
                    // `business_date`, so one that read it before the advance can commit after the
                    // build queried it. Excluding that day here while the per-account reader
                    // includes it would make a trial balance and an account's own balance disagree
                    // about where a checkpoint may be taken - for the same tenant, at the same
                    // instant. This rule wants one home, not two; see the note in the pull request.
                    watermark
                        ?.let { JOURNAL_ENTRY.BUSINESS_DATE.ge(it) }
                        ?: DSL.noCondition(),
                ).fetchOne(0, LocalDate::class.java)
                ?: return asOfDate
        return minOf(asOfDate, earliestUnprojected.minusDays(1))
    }

    /** The closing balance of every `(account, named branch)` series at the checkpoint. */
    private fun branchClosings(
        organisationId: UUID,
        branchId: UUID?,
        currencyCode: String,
        checkpointDate: LocalDate,
    ): List<Pair<UUID, BigDecimal>> =
        dsl
            .resultQuery(
                """
                SELECT s.gl_account_id, c.closing_signed_functional
                FROM (
                    SELECT a.id AS gl_account_id, b.id AS branch_id
                    FROM gl_account a
                    JOIN branch b ON b.organisation_id = a.organisation_id
                    WHERE a.organisation_id = ?
                      AND (CAST(? AS UUID) IS NULL OR b.id = CAST(? AS UUID))
                ) s
                JOIN LATERAL (
                    SELECT d.closing_signed_functional
                    FROM gl_account_daily_balance d
                    WHERE d.organisation_id = ?
                      AND d.gl_account_id = s.gl_account_id
                      AND d.branch_id = s.branch_id
                      AND d.currency_code = ?
                      AND d.posting_date <= ?
                    ORDER BY d.posting_date DESC
                    LIMIT 1
                ) c ON TRUE
                """.trimIndent(),
                organisationId,
                branchId,
                branchId,
                organisationId,
                currencyCode,
                checkpointDate,
            ).fetch { it.get(0, UUID::class.java)!! to it.get(1, BigDecimal::class.java)!! }

    /** The same for the head-office series, whose `branch_id` is null. */
    private fun headOfficeClosings(
        organisationId: UUID,
        currencyCode: String,
        checkpointDate: LocalDate,
    ): List<Pair<UUID, BigDecimal>> =
        dsl
            .resultQuery(
                """
                SELECT a.id, c.closing_signed_functional
                FROM gl_account a
                JOIN LATERAL (
                    SELECT d.closing_signed_functional
                    FROM gl_account_daily_balance d
                    WHERE d.organisation_id = ?
                      AND d.gl_account_id = a.id
                      AND d.branch_id IS NULL
                      AND d.currency_code = ?
                      AND d.posting_date <= ?
                    ORDER BY d.posting_date DESC
                    LIMIT 1
                ) c ON TRUE
                WHERE a.organisation_id = ?
                """.trimIndent(),
                organisationId,
                currencyCode,
                checkpointDate,
                organisationId,
            ).fetch { it.get(0, UUID::class.java)!! to it.get(1, BigDecimal::class.java)!! }
}
