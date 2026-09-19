package com.finaxis.platform.accounting

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * One subsidiary-ledger position a statement can be asked for: a member's savings account, a loan,
 * a share holding.
 *
 * [ownerModule] and [reference] together are exactly how `idx_journal_line_subledger` is keyed, and
 * that is not a coincidence: they are the same pair the owning module stamps on
 * `journal_line.source_module` and `journal_line.subledger_reference` when it posts. That is what
 * lets a reader move between a product statement and the general-ledger lines behind it without
 * either side knowing the other's primary keys.
 *
 * The module is part of the identity rather than derived from [kind], because a kind is what a
 * position *is* and a module is who keeps it. A tenant could run two modules answering for
 * different savings products, and `SAV-0001` would then mean two positions rather than one.
 *
 * [kind] identifies which control account the position rolls into, so a statement and a
 * reconciliation speak about the same class of ledger. A tenant has at most one control account per
 * class, which is why the class is enough for that purpose.
 */
data class SubledgerPosition(
    val organisationId: UUID,
    val ownerModule: String,
    val kind: ControlSubledgerKind,
    val reference: String,
)

/**
 * A statement request: one position, one closed date window, one page.
 *
 * The window is **mandatory and bounded**, and that is the point rather than an inconvenience.
 * `INV-15` requires every accounting-shaped query to be bounded, and a statement is the request
 * most likely to be written without a bound — *"show me everything"* is what a member asks for and
 * what a support tool offers. At the design envelope that is a scan toward a billion rows.
 *
 * [cursor] resumes a walk; [pageSize] is capped by the platform's
 * `finaxis.pagination.max-page-size` and validated by
 * [com.finaxis.platform.accounting.application.subledger.StatementWindowPolicy] before a provider
 * is called, so an implementation never has to defend itself against an unbounded page.
 */
data class SubledgerStatementQuery(
    val position: SubledgerPosition,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val branchId: UUID? = null,
    val cursor: SubledgerStatementCursor? = null,
    val pageSize: Int? = null,
)

/**
 * A keyset position in a statement: the last movement of the page just returned.
 *
 * `(posting_date, id)` and never an offset. `OFFSET` is banned by the platform's pagination
 * contract for the reason a statement makes vivid: at page 500 of a long-lived savings account,
 * PostgreSQL still reads and discards every earlier row, so the cost of a page grows with how deep
 * the reader has walked. The pair is a total order because the id is unique, which is what keeps
 * the next page exact on a day carrying many movements.
 */
data class SubledgerStatementCursor(
    val postingDate: LocalDate,
    val movementId: UUID,
)

/**
 * One movement on a subsidiary-ledger position, in the general ledger's sign convention.
 *
 * [signedAmount] is **debits positive, credits negative** (`INV-3`), the same convention
 * [SubledgerAggregate.balance] uses, so a statement's closing balance and a reconciliation's
 * aggregate are the same number and comparing them is a subtraction rather than a rule about
 * account classes. A savings deposit of 1,000 is therefore `-1000`: it increases what the
 * institution owes the member.
 *
 * [runningBalanceSigned] is filled in by
 * [com.finaxis.platform.accounting.application.subledger.SubledgerStatementAssembler] as it walks
 * the page. A provider leaves it at zero — a running balance is a property of where a row sits in a
 * statement, not of the movement itself, and a provider that computed one per row would be issuing
 * the query per line this contract exists to forbid.
 */
data class SubledgerMovement(
    val movementId: UUID,
    val postingDate: LocalDate,
    val signedAmount: BigDecimal,
    val currencyCode: String,
    val narrative: String?,
    val branchId: UUID? = null,
    val journalEntryId: UUID? = null,
    val runningBalanceSigned: BigDecimal = BigDecimal.ZERO,
)

/**
 * One page of movements as a provider returns them, oldest first.
 *
 * Ascending, which is the deliberate exception to the platform's `(posting_date DESC, id DESC)`
 * default: a running balance only means anything read forward from an opening balance, so a page
 * arriving newest-first could not carry one. The same index serves both directions, and descending
 * stays the default everywhere a running balance is not being carried.
 *
 * [nextCursor] is null on the last page, so a caller looping until null terminates rather than
 * asking forever.
 */
data class SubledgerMovementPage(
    val movements: List<SubledgerMovement>,
    val nextCursor: SubledgerStatementCursor?,
)

/**
 * Accounting-owned port a product module implements so its positions can be stated (`INV-15`).
 *
 * The sibling of [SubledgerProofProvider]: that one answers *"what did your ledger total"* for a
 * reconciliation, this one answers *"what happened to this position, and what did it start from"*
 * for a statement. Both are read-only, both are scoped by parameter rather than by ambient context,
 * and neither reaches into the other module's persistence.
 *
 * ## What the implementation owes
 *
 * **An opening balance that reads a bounded number of rows.** [openingBalanceAt] is asked for the
 * close of the day before a window starts, which for a member's account of ten years' standing is a
 * question about ten years of movements.
 *
 * What "bounded" means here is worth stating precisely, because the general ledger's answer and a
 * sub-ledger's are bounded by different things. An account's balance is bounded by the *tenant's*
 * history — every line every member ever posted to it — which is why `gl_account_daily_balance`
 * exists. A **position's** balance is bounded by that position's own history, and a savings account
 * with fifty movements a year holds five hundred rows after a decade. Summing those is one index
 * range scan and entirely reasonable.
 *
 * So the obligation is the bound, not the mechanism: an opening balance must not grow with the
 * tenant's ledger. A module whose positions stay small may sum from inception and be done. A module
 * with high-velocity positions — a teller's cash drawer, a pooled suspense position — needs a
 * checkpoint it maintains, plus the movements after it, which is the shape
 * `gl_account_daily_balance` demonstrates for the general ledger. Choosing the second before it is
 * needed is a maintenance obligation bought for nothing; choosing the first after it is needed is
 * how a statement endpoint becomes a timeout.
 *
 * **A checkpoint, where one is used, taken where no unrecorded movement can reach it.** This is the
 * part that is easy to get subtly wrong, and
 * [com.finaxis.platform.accounting.application.balances.DailyBalanceReader] documents the general
 * ledger's version at length. A position's checkpoint is only safe for dates strictly before the
 * earliest date any movement recorded *since the checkpoint was built* is dated at. A movement
 * backdated onto a day the checkpoint already covers is otherwise invisible to the checkpoint and
 * to the delta alike, and the statement omits it silently.
 *
 * **Movements that include reversals and corrections rather than hiding them.** A reversal is an
 * ordinary movement of opposite sign; it nets out of the closing balance by arithmetic. A provider
 * that filtered reversed movements out would show a member a history their money did not have, and
 * would disagree with the control account by exactly the amount it hid.
 *
 * **Ordering that is total and stable.** `(posting_date, id)` ascending, so a page boundary cannot
 * repeat or drop a movement on a day carrying many.
 *
 * ## What accounting owes in return
 *
 * The window and page bounds are validated before a provider is called, the running balance is
 * computed once per page by the assembler, and the statement's shape is fixed here so that every
 * product module's statement reads the same way. A module supplies two reads and gets a statement.
 */
interface SubledgerStatementProvider {
    /** A stable name recorded against statements this provider answers, e.g. `savings`. */
    val providerName: String

    /** Whether this provider owns the subsidiary ledger for [kind]. Pure, cheap and constant. */
    fun supports(kind: ControlSubledgerKind): Boolean

    /**
     * The position's signed balance at the close of [asOfDate], or zero when it had none.
     *
     * Asked once per statement, for the day before the window opens. See the class KDoc for the two
     * obligations this carries: a checkpoint plus a bounded delta, and a checkpoint taken where no
     * unrecorded movement can reach it.
     */
    fun openingBalanceAt(
        position: SubledgerPosition,
        branchId: UUID?,
        asOfDate: LocalDate,
    ): BigDecimal

    /**
     * One page of the position's movements inside the query's window, oldest first.
     *
     * The page size is already bounded when this is called, so an implementation applies it as a
     * `LIMIT` rather than re-validating it.
     */
    fun movements(query: SubledgerStatementQuery): SubledgerMovementPage
}
