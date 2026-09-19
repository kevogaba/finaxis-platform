package com.finaxis.platform.accounting.application.subledger

import com.finaxis.platform.accounting.SubledgerMovement
import com.finaxis.platform.accounting.SubledgerPosition
import com.finaxis.platform.accounting.SubledgerStatementCursor
import com.finaxis.platform.accounting.SubledgerStatementProvider
import com.finaxis.platform.accounting.SubledgerStatementQuery
import com.finaxis.platform.common.application.InvalidOperationException
import java.math.BigDecimal
import java.time.LocalDate

/**
 * One page of a subsidiary-ledger statement: what the position started from, what moved, and what
 * it ended at.
 *
 * [openingSigned] is the balance at the close of the day before [fromDate]; [closingSigned] is that
 * plus every movement on the page. On a walk through a long window, the closing balance of one page
 * is the opening balance of the next, which is why [SubledgerStatementAssembler] takes a carried
 * balance and a caller never needs a second as-of read.
 *
 * Both are signed in the general ledger's convention — debits positive, credits negative — so a
 * statement's closing balance is directly comparable with the control account it rolls into.
 */
data class SubledgerStatement(
    val position: SubledgerPosition,
    val branchId: java.util.UUID?,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val openingSigned: BigDecimal,
    val movements: List<SubledgerMovement>,
    val closingSigned: BigDecimal,
    val nextCursor: SubledgerStatementCursor?,
) {
    /**
     * True when the arithmetic holds: opening plus the page's movements is the closing balance.
     *
     * Kept as a property rather than left to the caller because it is the one thing a statement
     * must never get wrong, and because it makes the obligation checkable in a product module's own
     * tests without reaching for the assembler's internals.
     */
    val consistent: Boolean
        get() =
            movements
                .fold(openingSigned) { running, movement -> running.add(movement.signedAmount) }
                .compareTo(closingSigned) == 0
}

/**
 * The bounds every statement request is held to, before a product module's provider is called.
 *
 * Stated once here rather than in each product module, because a bound each implementation applies
 * for itself is a bound one of them will eventually forget. `INV-15` is not a style rule for a
 * statement: a member's account of ten years' standing holds tens of thousands of movements, and an
 * unbounded request for them is the request that takes the database down.
 */
object StatementWindowPolicy {
    /**
     * The widest window a statement may cover, in days.
     *
     * A year and a day, which is what a full financial year and a comparative annual statement
     * need, and the point past which a request stops being a statement and becomes an export —
     * which is background work with its own budget, not an interactive read.
     */
    const val MAXIMUM_WINDOW_DAYS = 366

    /** A window that ends before it starts, or covers more than [MAXIMUM_WINDOW_DAYS]. */
    const val WINDOW_INVALID = "accounting.statement_window_invalid"

    /** A page size outside `1..finaxis.pagination.max-page-size`. */
    const val PAGE_SIZE_INVALID = "accounting.statement_page_size_invalid"

    /** A cursor without its carried balance, or a carried balance without its cursor. */
    const val CURSOR_INVALID = "accounting.statement_cursor_invalid"

    /**
     * Refuses a query a provider should never be asked to answer.
     *
     * Called by [SubledgerStatementAssembler.assemble], which is the one path every statement goes
     * through, so an implementation genuinely never has to defend itself against an unbounded page
     * — a claim worth making only because something enforces it.
     */
    fun require(
        query: SubledgerStatementQuery,
        maxPageSize: Int,
        carriedBalance: BigDecimal? = null,
    ) {
        requireWindow(query.fromDate, query.toDate)
        requirePageSize(query.pageSize, maxPageSize)
        requirePaginationState(query, carriedBalance)
    }

    /**
     * A cursor and the balance the previous page closed at are one pagination state, or neither.
     *
     * Accepting them independently is how a statement comes back plausible and wrong. A cursor with
     * no carried balance skips the earlier pages' movements and then reopens from the window's
     * opening, so every running balance on the page is short by exactly what those pages moved; a
     * carried balance with no cursor applies a mid-walk opening to the first page. Neither produces
     * anything a reader could tell was broken, which is why this refuses rather than guesses.
     *
     * The general-ledger equivalent is `LedgerReportingService.requirePaginationState`; the two
     * exist separately because the ports are separate, and a product module implementing this
     * contract gets the check from here rather than having to remember it.
     */
    private fun requirePaginationState(
        query: SubledgerStatementQuery,
        carriedBalance: BigDecimal?,
    ) {
        if ((query.cursor == null) != (carriedBalance == null)) {
            throw InvalidOperationException(
                code = CURSOR_INVALID,
                safeDetail =
                    "A statement page takes a cursor and the balance the previous page closed " +
                        "at, or neither.",
            )
        }
    }

    private fun requireWindow(
        fromDate: LocalDate,
        toDate: LocalDate,
    ) {
        val detail =
            when {
                toDate.isBefore(fromDate) -> {
                    "A statement window must end on or after it starts."
                }

                // Inclusive of both ends, so the widest legal window is
                // `from + (MAXIMUM_WINDOW_DAYS - 1)`. Comparing against
                // `from + MAXIMUM_WINDOW_DAYS` admitted one day more than the constant and the
                // message both promise.
                fromDate.plusDays(MAXIMUM_WINDOW_DAYS.toLong() - 1).isBefore(toDate) -> {
                    "A statement window covers at most $MAXIMUM_WINDOW_DAYS days."
                }

                else -> {
                    return
                }
            }
        throw InvalidOperationException(code = WINDOW_INVALID, safeDetail = detail)
    }

    private fun requirePageSize(
        pageSize: Int?,
        maxPageSize: Int,
    ) {
        if (pageSize != null && pageSize !in 1..maxPageSize) {
            throw InvalidOperationException(
                code = PAGE_SIZE_INVALID,
                safeDetail = "The page size must be between 1 and $maxPageSize.",
            )
        }
    }
}

/**
 * Turns a provider's two reads into a statement, computing the running balance in **one pass over
 * the page**.
 *
 * This exists so that no product module writes the loop itself. The rule it enforces — *"the
 * running balance is opening plus the movements so far, never a query per row"* — is the one a
 * statement implementation gets wrong under deadline, and the failure is invisible in a small test:
 * a per-row balance query returns the same numbers and costs one round trip per line, so it is
 * correct on ten movements and unusable on ten thousand.
 *
 * It is a pure function of its inputs and touches no database, which is what lets a product module
 * test its provider against [SubledgerStatementContract] without a Spring context.
 */
object SubledgerStatementAssembler {
    /**
     * Reads a statement from [provider], **validating the query before either read runs**.
     *
     * This is the entry point a caller should use, and [assemble] is the pure half it delegates
     * to. The distinction is not stylistic: [assemble] receives an already-fetched page, and
     * Kotlin evaluates those arguments before its body runs, so bounds checked there are checked
     * *after* the database has already answered the very query the policy exists to forbid. An
     * oversized page or a decade-wide window would be refused only once it had been served.
     *
     * Validating here, before [provider] is touched, is what makes the port's promise that a
     * provider never sees an unbounded request true rather than merely intended.
     */
    fun statementOf(
        provider: SubledgerStatementProvider,
        query: SubledgerStatementQuery,
        maxPageSize: Int,
        carriedBalance: BigDecimal? = null,
    ): SubledgerStatement {
        StatementWindowPolicy.require(query, maxPageSize, carriedBalance)
        val opening =
            carriedBalance
                ?: provider.openingBalanceAt(
                    query.position,
                    query.branchId,
                    query.fromDate.minusDays(1),
                )
        return assemble(query, opening, provider.movements(query), maxPageSize, carriedBalance)
    }

    /**
     * Assembles an already-read page, carrying [carriedBalance] forward when mid-walk.
     *
     * [carriedBalance] is the previous page's [SubledgerStatement.closingSigned]. Absent on the
     * first page, where the provider's opening balance is the start.
     *
     * The bounds are re-checked here so a caller reaching this directly is still refused, but the
     * check cannot protect the reads that produced [page] — it runs after them. [statementOf] is
     * the path that does.
     */
    fun assemble(
        query: SubledgerStatementQuery,
        openingSigned: BigDecimal,
        page: com.finaxis.platform.accounting.SubledgerMovementPage,
        maxPageSize: Int,
        carriedBalance: BigDecimal? = null,
    ): SubledgerStatement {
        StatementWindowPolicy.require(query, maxPageSize, carriedBalance)
        val opening = carriedBalance ?: openingSigned
        var running = opening
        val movements =
            page.movements.map { movement ->
                running = running.add(movement.signedAmount)
                movement.copy(runningBalanceSigned = running)
            }
        return SubledgerStatement(
            position = query.position,
            branchId = query.branchId,
            fromDate = query.fromDate,
            toDate = query.toDate,
            openingSigned = opening,
            movements = movements,
            closingSigned = running,
            nextCursor = page.nextCursor,
        )
    }
}
