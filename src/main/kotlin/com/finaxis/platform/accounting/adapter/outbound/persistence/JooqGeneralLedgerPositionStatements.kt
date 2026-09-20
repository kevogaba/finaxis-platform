package com.finaxis.platform.accounting.adapter.outbound.persistence

import com.finaxis.platform.accounting.ControlSubledgerKind
import com.finaxis.platform.accounting.SubledgerMovement
import com.finaxis.platform.accounting.SubledgerMovementPage
import com.finaxis.platform.accounting.SubledgerPosition
import com.finaxis.platform.accounting.SubledgerStatementCursor
import com.finaxis.platform.accounting.SubledgerStatementProvider
import com.finaxis.platform.accounting.SubledgerStatementQuery
import com.finaxis.platform.accounting.application.GlAccountStore
import com.finaxis.platform.accounting.application.RequiredSnapshotIsolation
import com.finaxis.platform.accounting.application.SnapshotIsolationGuard
import com.finaxis.platform.jooq.tables.references.JOURNAL_LINE
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.impl.DSL
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * The **general ledger's own** view of a subsidiary-ledger position, as a
 * [SubledgerStatementProvider].
 *
 * ## Why accounting implements a port meant for product modules
 *
 * Two reasons, and neither is that accounting owns a sub-ledger — it does not.
 *
 * **It is the drill-down a reconciliation break needs.** When a control account and a sub-ledger
 * disagree, the question is *which position*, and then *which movement of it*. This answers both
 * from the journal, which is the side of the disagreement accounting can speak for. Comparing it
 * against the owning module's own statement of the same position is how a break is localised; the
 * two are meant to be **compared, never substituted**, and a product module that implemented this
 * port by delegating here would be proving the ledger against itself.
 *
 * **It is the contract's proof of implementability.** A contract with no implementation is a
 * document, and `SubledgerStatementContractTests` runs the conformance suite against this one at
 * the volume `AccountingQueryPlanTests` seeds, so the obligations in
 * [SubledgerStatementProvider]'s KDoc are demonstrated rather than asserted.
 *
 * ## Bounded by the position, not by the ledger
 *
 * Both reads are range scans of `idx_journal_line_subledger`, whose key is
 * `(organisation_id, source_module, subledger_reference, posting_date, id)` — this predicate in its
 * own order. The opening balance sums one position's lines up to a date, which is bounded by how
 * much *that position* has moved rather than by the tenant's ledger, exactly the distinction the
 * port's KDoc draws. A position busy enough for that to stop being bounded is one whose owning
 * module should carry a checkpoint; the general ledger has no per-position checkpoint to offer it,
 * and inventing one would be the speculative projection
 * `docs/architecture/accounting-foundation.md` forbids.
 *
 * ## The module that owns the position, not the one that requested the posting
 *
 * `source_module` on a line names the owner of the position it moved. A reversal is requested by
 * accounting and moves a product module's position, so its lines carry the product's module — which
 * is what makes a reversed position net to zero here rather than showing an open balance with its
 * cancelling credit filed elsewhere.
 */
@Component
class JooqGeneralLedgerPositionStatements(
    private val dsl: DSLContext,
    private val accounts: GlAccountStore,
    private val snapshots: SnapshotIsolationGuard,
) : SubledgerStatementProvider {
    override val providerName: String = PROVIDER_NAME

    /**
     * Every class, because the journal holds lines for all of them.
     *
     * This is not a claim of ownership. Nothing resolves a *proof* provider through this port, so
     * answering for every class cannot shadow a product module's own provider; it simply means the
     * general ledger can be asked about any position that has ever been posted to.
     */
    override fun supports(kind: ControlSubledgerKind): Boolean = true

    override fun openingBalanceAt(
        position: SubledgerPosition,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal {
        val controlAccountId = controlAccountIdFor(position) ?: return BigDecimal.ZERO
        return dsl
            .select(
                DSL.coalesce(DSL.sum(JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT), BigDecimal.ZERO),
            ).from(JOURNAL_LINE)
            .where(positionPredicate(position, branchId, controlAccountId))
            .and(JOURNAL_LINE.POSTING_DATE.le(asOfDate))
            .fetchOne(0, BigDecimal::class.java)
            ?: BigDecimal.ZERO
    }

    override fun movements(query: SubledgerStatementQuery): SubledgerMovementPage {
        val pageSize = query.pageSize ?: DEFAULT_PAGE_SIZE
        val controlAccountId =
            controlAccountIdFor(query.position)
                ?: return SubledgerMovementPage(movements = emptyList(), nextCursor = null)
        // One row more than the page, so "is there a next page" is answered by the same read rather
        // than by a count over the remainder of the window.
        val fetched =
            dsl
                .select(
                    JOURNAL_LINE.ID,
                    JOURNAL_LINE.POSTING_DATE,
                    JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT,
                    JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE,
                    JOURNAL_LINE.NARRATIVE,
                    JOURNAL_LINE.BRANCH_ID,
                    JOURNAL_LINE.JOURNAL_ENTRY_ID,
                ).from(JOURNAL_LINE)
                .where(positionPredicate(query.position, query.branchId, controlAccountId))
                .and(JOURNAL_LINE.POSTING_DATE.between(query.fromDate, query.toDate))
                .and(keysetAfter(query.cursor))
                .orderBy(JOURNAL_LINE.POSTING_DATE.asc(), JOURNAL_LINE.ID.asc())
                .limit(pageSize + 1)
                .fetch { record ->
                    SubledgerMovement(
                        movementId = record.get(JOURNAL_LINE.ID)!!,
                        postingDate = record.get(JOURNAL_LINE.POSTING_DATE)!!,
                        signedAmount = record.get(JOURNAL_LINE.SIGNED_FUNCTIONAL_AMOUNT)!!,
                        currencyCode = record.get(JOURNAL_LINE.FUNCTIONAL_CURRENCY_CODE)!!,
                        narrative = record.get(JOURNAL_LINE.NARRATIVE),
                        branchId = record.get(JOURNAL_LINE.BRANCH_ID),
                        journalEntryId = record.get(JOURNAL_LINE.JOURNAL_ENTRY_ID),
                    )
                }
        val hasMore = fetched.size > pageSize
        val movements = fetched.take(pageSize)
        return SubledgerMovementPage(
            movements = movements,
            nextCursor =
                movements.lastOrNull().takeIf { hasMore }?.let {
                    SubledgerStatementCursor(it.postingDate, it.movementId)
                },
        )
    }

    /**
     * `pg_current_snapshot()::text` — PostgreSQL's own MVCC snapshot, captured now and returned as
     * the token [recordedBehindConsumedRange] later compares a row's *visibility* against, through
     * `pg_visible_in_snapshot`, rather than a row's *id*.
     *
     * Not `pg_export_snapshot()`, which [PostgresProofSnapshot] uses for the reconciliation proof:
     * an export is only adoptable by another session while the exporting transaction stays open,
     * which fits a proof's two reads inside one request but not a statement walk spanning several
     * requests, each its own transaction. A `pg_snapshot` value carries no such requirement — it
     * can be stored, parsed back on an unrelated later connection, and compared.
     *
     * [snapshots] guards this the same way it guards [recordedBehindConsumedRange]: this call and
     * the first page's own [movements] call must share one snapshot for the token to mean what
     * [SubledgerStatementProvider.currentWatermark]'s KDoc promises — exactly what the first page's
     * own movements read saw, no more, no less.
     */
    override fun currentWatermark(): String {
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "A subledger statement's watermark",
        )
        return requireNotNull(
            dsl.fetchValue(DSL.field("pg_current_snapshot()::text", String::class.java)),
        ) {
            "pg_current_snapshot() returned no snapshot identity"
        }
    }

    /**
     * The row-value mirror of [keysetAfter]: whether the ledger holds a line at or before
     * [cursor]'s own `(posting_date, id)` position that was not yet visible when [cursor]'s
     * watermark was minted.
     *
     * Reuses [positionPredicate] and [controlAccountIdFor] rather than re-deriving which lines
     * belong to this position — the same bound [openingBalanceAt] and [movements] read within.
     * `POSTING_DATE.le(cursor.postingDate)` alone would over-match: a line dated on the cursor's
     * own day, with an id *greater* than [SubledgerStatementCursor.movementId], is a row
     * [keysetAfter] on the very next page would still select — it is forward progress, not a
     * backdated arrival. The row-value comparison below is [keysetAfter]'s own predicate flipped
     * from `>` to `<=`, which is what lets the two cases be told apart on a day carrying many
     * movements, exactly the case this file's own KDoc on [positionPredicate] and [keysetAfter]
     * already calls out.
     *
     * `NOT pg_visible_in_snapshot(xmin::text::xid8, cursor.recordedThrough)` is the other half: a
     * line at or before the cursor whose inserting transaction *was already visible* under the
     * watermark was there when this walk began and is not what this method is asked to detect — it
     * is simply a row an earlier page already served. This is deliberately **not** `id >
     * cursor.recordedThrough`, which an earlier version of this method used: a `uuidv7()` id orders
     * by when it was *generated* inside its inserting transaction, not by when that transaction
     * *committed*, and a transaction that starts before the watermark is minted but stays open past
     * it generates an id that still sorts before the watermark while its row becomes visible after.
     * `pg_visible_in_snapshot` asks PostgreSQL's own MVCC machinery the commit-visibility question
     * directly, from the line's `xmin` system column and the stored `pg_snapshot` token, with no
     * such gap. `cursor.recordedThrough` is bound as a plain parameter, never interpolated into the
     * SQL text, because a future public API accepting this cursor would be handing this value to
     * PostgreSQL on a client's word.
     *
     * [snapshots] is asked first, before either this query or [movements]' own query runs, for the
     * reason [SubledgerStatementProvider.recordedBehindConsumedRange]'s own KDoc gives at length:
     * this check and the movements read that follows it must share one snapshot, or a line
     * committed in the gap between two independent `READ COMMITTED` reads is invisible to both.
     * `@Transactional(isolation = REPEATABLE_READ)` on a caller that joins an already-open
     * transaction is a request Spring can silently drop, so this asks PostgreSQL what is actually
     * in force rather than trusting the annotation - the same reasoning `ManualJournalService.get`
     * and `ControlAccountReconciliationService.run` already act on.
     */
    override fun recordedBehindConsumedRange(
        position: SubledgerPosition,
        branchId: UUID?,
        cursor: SubledgerStatementCursor,
    ): Boolean {
        snapshots.requireStableSnapshot(
            RequiredSnapshotIsolation.REPEATABLE_READ,
            "A subledger statement's stale-cursor check",
        )
        val controlAccountId = controlAccountIdFor(position) ?: return false
        return dsl
            .selectOne()
            .from(JOURNAL_LINE)
            .where(positionPredicate(position, branchId, controlAccountId))
            .and(
                DSL
                    .row(JOURNAL_LINE.POSTING_DATE, JOURNAL_LINE.ID)
                    .le(DSL.row(cursor.postingDate, cursor.movementId)),
            ).and(
                DSL.condition(
                    "NOT pg_visible_in_snapshot(xmin::text::xid8, ?::pg_snapshot)",
                    cursor.recordedThrough,
                ),
            ).limit(1)
            .fetchOne() != null
    }

    /**
     * The lines that represent this position in the general ledger.
     *
     * `(organisation_id, source_module, subledger_reference)` is `idx_journal_line_subledger`'s
     * leading key in its own order, which is what makes both reads a range scan rather than a
     * filter.
     *
     * **The branch and the control account are recheck predicates, not index columns.** `V9`
     * declares the index as `(organisation_id, source_module, subledger_reference, posting_date,
     * id) WHERE subledger_reference IS NOT NULL` — `branch_id` and `gl_account_id` are in neither
     * its key nor an `INCLUDE` list, so both are applied to the heap tuple after the index has
     * chosen it. That is the right trade here and not an oversight: the range is already bounded by
     * one position's history, so these two only narrow rows already being read, and adding either
     * to the index would widen every `journal_line` write to speed up a read that is bounded
     * already. It does mean the access is not index-only, which is what the Q2 budget of 900 shared
     * blocks for 1,016 lines measures.
     *
     * Branch stays a predicate rather than a key for a second reason: a position belongs to a
     * member and not to a branch, so a statement filtered by branch is a narrower question, not a
     * different position.
     *
     * **Narrowed to the control account for the position's class, and that is not a refinement.**
     * `PostingRulePolicy.allocate` groups legs by `(amountSource, side)` and stamps the fact's
     * position reference on every group it drives — so a balanced posting derived from one fact
     * carries the reference on its debit leg *and* its credit leg. Matching on the reference alone
     * therefore selects both halves: the opening balance sums them to exactly zero, and a
     * statement lists each leg as a separate movement of the position.
     *
     * Only one of those legs is the position's own, and it is the one on the control account for
     * its class — which `uq_gl_account_control_kind` makes unique per tenant, so naming the class
     * names the account. The other leg is the income, expense or settlement side of the same fact,
     * and it belongs to that account's story rather than to this position's.
     */
    private fun positionPredicate(
        position: SubledgerPosition,
        branchId: UUID?,
        controlAccountId: UUID,
    ): Condition =
        JOURNAL_LINE.ORGANISATION_ID
            .eq(position.organisationId)
            .and(JOURNAL_LINE.GL_ACCOUNT_ID.eq(controlAccountId))
            .and(JOURNAL_LINE.SOURCE_MODULE.eq(position.ownerModule))
            .and(JOURNAL_LINE.SUBLEDGER_REFERENCE.eq(position.reference))
            .and(branchId?.let { JOURNAL_LINE.BRANCH_ID.eq(it) } ?: DSL.noCondition())

    /**
     * The control account this position's class maps to, or null when the tenant configures none.
     *
     * A tenant that has not named a control account for the class has no general-ledger view of
     * the position to give, and an empty statement says exactly that. Inventing one by falling
     * back to every referenced line is what produced the netting-to-zero above.
     */
    private fun controlAccountIdFor(position: SubledgerPosition): UUID? =
        accounts.findControlAccountFor(position.organisationId, position.kind)?.id

    /**
     * `(posting_date, id) > (cursor…)`, as a row-value comparison.
     *
     * A row value rather than the expanded `date > d OR (date = d AND id > i)`, because the
     * expanded form is what silently drops or repeats rows on a day carrying many movements when a
     * reader gets one of the branches wrong, and PostgreSQL turns the row value into the same index
     * range either way.
     */
    private fun keysetAfter(cursor: SubledgerStatementCursor?): Condition =
        cursor?.let {
            DSL
                .row(JOURNAL_LINE.POSTING_DATE, JOURNAL_LINE.ID)
                .gt(DSL.row(it.postingDate, it.movementId))
        } ?: DSL.noCondition()

    private companion object {
        const val PROVIDER_NAME = "accounting.general-ledger"
        const val DEFAULT_PAGE_SIZE = 25
    }
}
